package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	wruntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

type NativeCallConnectResult struct {
	Ok    bool   `json:"ok"`
	WsURL string `json:"wsUrl"`
	Error string `json:"error,omitempty"`
}

type NativeCallManager struct {
	mu           sync.Mutex
	app          *App
	room         *lksdk.Room
	localAudio   *lksdk.LocalSampleTrack
	audioPub     *lksdk.LocalTrackPublication
	isMuted      bool
	isVideo      bool
	cancelFn     context.CancelFunc
	activeCallID string

	// Loopback audio WebSocket server
	audioListener net.Listener
	audioServer   *http.Server
	audioPort     int
	wsClients     map[*websocket.Conn]bool
	wsMu          sync.Mutex
}

var globalCallManager *NativeCallManager

func initCallManager(app *App) {
	globalCallManager = &NativeCallManager{
		app:       app,
		wsClients: make(map[*websocket.Conn]bool),
	}
}

func (m *NativeCallManager) ensureAudioServer() (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.audioServer != nil && m.audioPort > 0 {
		return m.audioPort, nil
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("failed to bind audio bridge server: %w", err)
	}
	m.audioListener = ln
	m.audioPort = ln.Addr().(*net.TCPAddr).Port

	mux := http.NewServeMux()
	mux.HandleFunc("/stream/call_audio", m.handleAudioWebSocket)

	m.audioServer = &http.Server{
		Handler: mux,
	}

	go func() {
		_ = m.audioServer.Serve(ln)
	}()

	return m.audioPort, nil
}

func (m *NativeCallManager) handleAudioWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	m.wsMu.Lock()
	m.wsClients[conn] = true
	m.wsMu.Unlock()

	defer func() {
		m.wsMu.Lock()
		delete(m.wsClients, conn)
		m.wsMu.Unlock()
		_ = conn.Close()
	}()

	// Read mic PCM/audio data from Web Audio and publish to LiveKit
	for {
		messageType, data, err := conn.ReadMessage()
		if err != nil {
			break
		}
		if messageType == websocket.BinaryMessage && len(data) > 0 {
			m.mu.Lock()
			track := m.localAudio
			muted := m.isMuted
			m.mu.Unlock()

			if track != nil && !muted {
				_ = track.WriteSample(media.Sample{
					Data:     data,
					Duration: 20 * time.Millisecond,
				}, nil)
			}
		}
	}
}

func (m *NativeCallManager) broadcastRemoteAudio(data []byte) {
	m.wsMu.Lock()
	defer m.wsMu.Unlock()

	for client := range m.wsClients {
		_ = client.WriteMessage(websocket.BinaryMessage, data)
	}
}

// NativeCallConnect connects the Go desktop backend directly to LiveKit SFU via Pion WebRTC.
func (a *App) NativeCallConnect(url, token string, isVideo bool) (*NativeCallConnectResult, error) {
	if globalCallManager == nil {
		initCallManager(a)
	}

	port, err := globalCallManager.ensureAudioServer()
	if err != nil {
		return &NativeCallConnectResult{Ok: false, Error: err.Error()}, err
	}

	globalCallManager.mu.Lock()
	defer globalCallManager.mu.Unlock()

	// If already in a call, disconnect first
	if globalCallManager.room != nil {
		globalCallManager.room.Disconnect()
		globalCallManager.room = nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	globalCallManager.cancelFn = cancel
	globalCallManager.isVideo = isVideo
	globalCallManager.isMuted = false

	cb := lksdk.NewRoomCallback()

	cb.OnDisconnected = func() {
		wruntime.EventsEmit(a.ctx, "native_call_state", map[string]interface{}{
			"state": "DISCONNECTED",
		})
	}

	cb.OnParticipantConnected = func(rp *lksdk.RemoteParticipant) {
		wruntime.EventsEmit(a.ctx, "native_call_participant", map[string]interface{}{
			"event":    "JOINED",
			"identity": rp.Identity(),
			"name":     rp.Name(),
		})
	}

	cb.OnParticipantDisconnected = func(rp *lksdk.RemoteParticipant) {
		wruntime.EventsEmit(a.ctx, "native_call_participant", map[string]interface{}{
			"event":    "LEFT",
			"identity": rp.Identity(),
		})
	}

	cb.OnActiveSpeakersChanged = func(speakers []lksdk.Participant) {
		identities := make([]string, 0, len(speakers))
		for _, s := range speakers {
			identities = append(identities, s.Identity())
		}
		wruntime.EventsEmit(a.ctx, "native_call_speakers", identities)
	}

	cb.OnTrackSubscribed = func(track *webrtc.TrackRemote, pub *lksdk.RemoteTrackPublication, rp *lksdk.RemoteParticipant) {
		trackSID := ""
		if pub != nil {
			trackSID = pub.SID()
		}
		wruntime.EventsEmit(a.ctx, "native_call_track", map[string]interface{}{
			"event":    "SUBSCRIBED",
			"kind":     track.Kind().String(),
			"sid":      trackSID,
			"identity": rp.Identity(),
		})

		// Stream incoming audio frames to the local audio bridge
		go func() {
			buf := make([]byte, 1500)
			for {
				select {
				case <-ctx.Done():
					return
				default:
					n, _, err := track.Read(buf)
					if err != nil {
						return
					}
					if n > 0 && track.Kind() == webrtc.RTPCodecTypeAudio {
						globalCallManager.broadcastRemoteAudio(buf[:n])
					}
				}
			}
		}()
	}

	room, err := lksdk.ConnectToRoomWithToken(url, token, cb,
		lksdk.WithAutoSubscribe(true),
		lksdk.WithConnectTimeout(10*time.Second),
	)
	if err != nil {
		cancel()
		return &NativeCallConnectResult{Ok: false, Error: err.Error()}, fmt.Errorf("failed to connect to LiveKit: %w", err)
	}

	globalCallManager.room = room

	// Create and publish local Opus audio track
	audioTrack, err := lksdk.NewLocalSampleTrack(webrtc.RTPCodecCapability{
		MimeType:  webrtc.MimeTypeOpus,
		ClockRate: 48000,
		Channels:  1,
	})
	if err == nil {
		globalCallManager.localAudio = audioTrack
		pub, err := room.LocalParticipant.PublishTrack(audioTrack, &lksdk.TrackPublicationOptions{
			Name:   "audio",
			Source: livekit.TrackSource_MICROPHONE,
		})
		if err == nil {
			globalCallManager.audioPub = pub
		}
	}

	wruntime.EventsEmit(a.ctx, "native_call_state", map[string]interface{}{
		"state": "CONNECTED",
	})

	wsURL := fmt.Sprintf("ws://127.0.0.1:%d/stream/call_audio", port)
	return &NativeCallConnectResult{
		Ok:    true,
		WsURL: wsURL,
	}, nil
}

// NativeCallDisconnect disconnects the active native LiveKit call.
func (a *App) NativeCallDisconnect() bool {
	if globalCallManager == nil {
		return true
	}

	globalCallManager.mu.Lock()
	defer globalCallManager.mu.Unlock()

	if globalCallManager.cancelFn != nil {
		globalCallManager.cancelFn()
		globalCallManager.cancelFn = nil
	}

	if globalCallManager.room != nil {
		globalCallManager.room.Disconnect()
		globalCallManager.room = nil
	}
	globalCallManager.localAudio = nil
	globalCallManager.audioPub = nil

	wruntime.EventsEmit(a.ctx, "native_call_state", map[string]interface{}{
		"state": "DISCONNECTED",
	})
	return true
}

// NativeCallSetMute toggles mute on the native audio track.
func (a *App) NativeCallSetMute(muted bool) bool {
	if globalCallManager == nil {
		return false
	}
	globalCallManager.mu.Lock()
	defer globalCallManager.mu.Unlock()

	globalCallManager.isMuted = muted
	if globalCallManager.audioPub != nil {
		globalCallManager.audioPub.SetMuted(muted)
	}
	return true
}

// NativeCallIsActive checks if a native call is currently connected.
func (a *App) NativeCallIsActive() bool {
	if globalCallManager == nil {
		return false
	}
	globalCallManager.mu.Lock()
	defer globalCallManager.mu.Unlock()
	return globalCallManager.room != nil
}
