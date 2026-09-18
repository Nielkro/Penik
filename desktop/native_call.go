package main

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"github.com/pion/webrtc/v4"
	wruntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

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
}

var globalCallManager *NativeCallManager

func initCallManager(app *App) {
	globalCallManager = &NativeCallManager{
		app: app,
	}
}

// NativeCallConnect connects the Go desktop backend directly to LiveKit SFU via Pion WebRTC.
func (a *App) NativeCallConnect(url, token string, isVideo bool) (bool, error) {
	if globalCallManager == nil {
		initCallManager(a)
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

		// Read RTP packets in the background to keep the pipeline alive
		go func() {
			buf := make([]byte, 1500)
			for {
				select {
				case <-ctx.Done():
					return
				default:
					_, _, err := track.Read(buf)
					if err != nil {
						return
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
		return false, fmt.Errorf("failed to connect to LiveKit: %w", err)
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

	return true, nil
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
