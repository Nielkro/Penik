package main

import (
	"context"
	"encoding/binary"
	"fmt"
	"sync"
	"time"

	"github.com/gen2brain/malgo"
	"github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	wruntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

type NativeCallConnectResult struct {
	Ok    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
}

type NativeAudioDevice struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	IsDefault bool   `json:"isDefault"`
}

type NativeAudioDevicesList struct {
	Inputs  []NativeAudioDevice `json:"inputs"`
	Outputs []NativeAudioDevice `json:"outputs"`
}

type AudioRingBuffer struct {
	mu     sync.Mutex
	buf    []byte
	maxLen int
}

func NewAudioRingBuffer(maxLen int) *AudioRingBuffer {
	return &AudioRingBuffer{
		buf:    make([]byte, 0, maxLen),
		maxLen: maxLen,
	}
}

func (r *AudioRingBuffer) Write(data []byte) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if len(r.buf)+len(data) > r.maxLen {
		overflow := len(r.buf) + len(data) - r.maxLen
		if overflow >= len(r.buf) {
			r.buf = r.buf[:0]
		} else {
			r.buf = r.buf[overflow:]
		}
	}
	r.buf = append(r.buf, data...)
}

func (r *AudioRingBuffer) Read(out []byte) int {
	r.mu.Lock()
	defer r.mu.Unlock()

	n := copy(out, r.buf)
	if n < len(out) {
		for i := n; i < len(out); i++ {
			out[i] = 0
		}
	}
	r.buf = r.buf[n:]
	return n
}

func (r *AudioRingBuffer) Available() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.buf)
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

	// Selected device IDs
	selectedCaptureDeviceID  string
	selectedPlaybackDeviceID string

	// Native malgo audio context and devices
	malgoCtx       *malgo.AllocatedContext
	playbackDevice *malgo.Device
	captureDevice  *malgo.Device
	playbackRing   *AudioRingBuffer
	captureRing    *AudioRingBuffer
}

var globalCallManager *NativeCallManager

func initCallManager(app *App) {
	globalCallManager = &NativeCallManager{
		app:          app,
		playbackRing: NewAudioRingBuffer(48000 * 2 * 2), // 2 seconds capacity
		captureRing:  NewAudioRingBuffer(48000 * 2 * 2),
	}
}

func (m *NativeCallManager) stopAudioHardware() {
	if m.playbackDevice != nil {
		m.playbackDevice.Uninit()
		m.playbackDevice = nil
	}
	if m.captureDevice != nil {
		m.captureDevice.Uninit()
		m.captureDevice = nil
	}
	if m.malgoCtx != nil {
		_ = m.malgoCtx.Uninit()
		m.malgoCtx.Free()
		m.malgoCtx = nil
	}
}

func (m *NativeCallManager) startAudioHardware(ctx context.Context) error {
	m.stopAudioHardware()

	malgoCtx, err := malgo.InitContext(nil, malgo.ContextConfig{}, nil)
	if err != nil {
		return fmt.Errorf("failed to init malgo audio context: %w", err)
	}
	m.malgoCtx = malgoCtx

	// 1. Playback device (Speakers / Headphones)
	playbackConfig := malgo.DefaultDeviceConfig(malgo.Playback)
	playbackConfig.Playback.Format = malgo.FormatS16
	playbackConfig.Playback.Channels = 1
	playbackConfig.SampleRate = 48000
	playbackConfig.Alsa.NoMMap = 1

	if m.selectedPlaybackDeviceID != "" {
		playbackDevices, _ := malgoCtx.Devices(malgo.Playback)
		for i := range playbackDevices {
			if playbackDevices[i].ID.String() == m.selectedPlaybackDeviceID || playbackDevices[i].Name() == m.selectedPlaybackDeviceID {
				playbackConfig.Playback.DeviceID = playbackDevices[i].ID.Pointer()
				break
			}
		}
	}

	playbackDevice, err := malgo.InitDevice(malgoCtx.Context, playbackConfig, malgo.DeviceCallbacks{
		Data: func(pOutputSample, pInputSamples []byte, frameCount uint32) {
			m.playbackRing.Read(pOutputSample)
		},
	})
	if err != nil {
		m.stopAudioHardware()
		return fmt.Errorf("failed to init playback device: %w", err)
	}
	if err := playbackDevice.Start(); err != nil {
		m.stopAudioHardware()
		return fmt.Errorf("failed to start playback device: %w", err)
	}
	m.playbackDevice = playbackDevice

	// 2. Capture device (Microphone)
	captureConfig := malgo.DefaultDeviceConfig(malgo.Capture)
	captureConfig.Capture.Format = malgo.FormatS16
	captureConfig.Capture.Channels = 1
	captureConfig.SampleRate = 48000
	captureConfig.Alsa.NoMMap = 1

	if m.selectedCaptureDeviceID != "" {
		captureDevices, _ := malgoCtx.Devices(malgo.Capture)
		for i := range captureDevices {
			if captureDevices[i].ID.String() == m.selectedCaptureDeviceID || captureDevices[i].Name() == m.selectedCaptureDeviceID {
				captureConfig.Capture.DeviceID = captureDevices[i].ID.Pointer()
				break
			}
		}
	}

	captureDevice, err := malgo.InitDevice(malgoCtx.Context, captureConfig, malgo.DeviceCallbacks{
		Data: func(pOutputSample, pInputSamples []byte, frameCount uint32) {
			if len(pInputSamples) > 0 {
				m.captureRing.Write(pInputSamples)
			}
		},
	})
	if err != nil {
		m.stopAudioHardware()
		return fmt.Errorf("failed to init capture device: %w", err)
	}
	if err := captureDevice.Start(); err != nil {
		m.stopAudioHardware()
		return fmt.Errorf("failed to start capture device: %w", err)
	}
	m.captureDevice = captureDevice

	// 3. Background microphone Opus encoding pump
	go func() {
		encoder, err := NewNativeOpusEncoder(48000, 1)
		if err != nil {
			fmt.Printf("[native_call] Failed to create opus encoder: %v\n", err)
			return
		}
		defer encoder.Close()

		const frameSamples = 960 // 20ms at 48kHz
		const frameBytes = frameSamples * 2 // 16-bit mono PCM = 1920 bytes
		pcmChunk := make([]byte, frameBytes)
		pcmSamples := make([]int16, frameSamples)
		opusBuf := make([]byte, 1000)

		ticker := time.NewTicker(20 * time.Millisecond)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if m.captureRing.Available() < frameBytes {
					continue
				}
				m.captureRing.Read(pcmChunk)

				m.mu.Lock()
				track := m.localAudio
				muted := m.isMuted
				m.mu.Unlock()

				if track == nil || muted {
					continue
				}

				// Direct, zero-allocation binary unpacking
				for i := 0; i < frameSamples; i++ {
					pcmSamples[i] = int16(binary.LittleEndian.Uint16(pcmChunk[i*2 : i*2+2]))
				}

				n, err := encoder.Encode(pcmSamples, opusBuf)
				if err == nil && n > 0 {
					_ = track.WriteSample(media.Sample{
						Data:     opusBuf[:n],
						Duration: 20 * time.Millisecond,
					}, nil)
				}
			}
		}
	}()

	return nil
}

// GetAudioDevices returns all available system microphone and speaker devices.
func (a *App) GetAudioDevices() (*NativeAudioDevicesList, error) {
	malgoCtx, err := malgo.InitContext(nil, malgo.ContextConfig{}, nil)
	if err != nil {
		return nil, err
	}
	defer func() {
		_ = malgoCtx.Uninit()
		malgoCtx.Free()
	}()

	res := &NativeAudioDevicesList{
		Inputs:  make([]NativeAudioDevice, 0),
		Outputs: make([]NativeAudioDevice, 0),
	}

	if inputs, err := malgoCtx.Devices(malgo.Capture); err == nil {
		for _, d := range inputs {
			res.Inputs = append(res.Inputs, NativeAudioDevice{
				ID:        d.ID.String(),
				Name:      d.Name(),
				IsDefault: d.IsDefault > 0,
			})
		}
	}

	if outputs, err := malgoCtx.Devices(malgo.Playback); err == nil {
		for _, d := range outputs {
			res.Outputs = append(res.Outputs, NativeAudioDevice{
				ID:        d.ID.String(),
				Name:      d.Name(),
				IsDefault: d.IsDefault > 0,
			})
		}
	}

	return res, nil
}

// SetAudioDevices switches the selected microphone and playback output device.
func (a *App) SetAudioDevices(playbackID, captureID string) error {
	if globalCallManager == nil {
		initCallManager(a)
	}

	globalCallManager.mu.Lock()
	defer globalCallManager.mu.Unlock()

	if playbackID != "" {
		globalCallManager.selectedPlaybackDeviceID = playbackID
	}
	if captureID != "" {
		globalCallManager.selectedCaptureDeviceID = captureID
	}

	// If call is active, restart audio hardware with new device selection
	if globalCallManager.room != nil && globalCallManager.cancelFn != nil {
		ctx := context.Background()
		_ = globalCallManager.startAudioHardware(ctx)
	}
	return nil
}

// NativeCallConnect connects the Go desktop backend directly to LiveKit SFU via Pion WebRTC.
func (a *App) NativeCallConnect(url, token string, isVideo bool) (*NativeCallConnectResult, error) {
	if globalCallManager == nil {
		initCallManager(a)
	}

	globalCallManager.mu.Lock()
	defer globalCallManager.mu.Unlock()

	if globalCallManager.room != nil {
		globalCallManager.room.Disconnect()
		globalCallManager.room = nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	globalCallManager.cancelFn = cancel
	globalCallManager.isVideo = isVideo
	globalCallManager.isMuted = false

	if err := globalCallManager.startAudioHardware(ctx); err != nil {
		fmt.Printf("[native_call] Audio hardware warning: %v\n", err)
	}

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

		if track.Kind() == webrtc.RTPCodecTypeAudio {
			go func() {
				decoder, err := NewNativeOpusDecoder(48000, 1)
				if err != nil {
					return
				}
				defer decoder.Close()
				pcmSamples := make([]int16, 960)
				pcmBytes := make([]byte, 960*2)

				for {
					select {
					case <-ctx.Done():
						return
					default:
						pkt, _, err := track.ReadRTP()
						if err != nil {
							return
						}
						if len(pkt.Payload) > 0 {
							n, err := decoder.Decode(pkt.Payload, pcmSamples)
							if err == nil && n > 0 {
								for i := 0; i < n; i++ {
									binary.LittleEndian.PutUint16(pcmBytes[i*2:i*2+2], uint16(pcmSamples[i]))
								}
								globalCallManager.playbackRing.Write(pcmBytes[:n*2])
							}
						}
					}
				}
			}()
		}
	}

	room, err := lksdk.ConnectToRoomWithToken(url, token, cb,
		lksdk.WithAutoSubscribe(true),
		lksdk.WithConnectTimeout(10*time.Second),
	)
	if err != nil {
		cancel()
		globalCallManager.stopAudioHardware()
		return &NativeCallConnectResult{Ok: false, Error: err.Error()}, fmt.Errorf("failed to connect to LiveKit: %w", err)
	}

	globalCallManager.room = room

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

	return &NativeCallConnectResult{
		Ok: true,
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
	globalCallManager.stopAudioHardware()

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
