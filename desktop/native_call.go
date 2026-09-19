package main

import (
	"context"
	"encoding/binary"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/gen2brain/malgo"
	"github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"github.com/pion/rtp/codecs"
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
	videoTrack   *lksdk.LocalSampleTrack
	videoPub     *lksdk.LocalTrackPublication
	isMuted      bool
	isVideo      bool
	cancelFn     context.CancelFunc
	cancelScreen context.CancelFunc
	activeCallID string

	// Selected device IDs
	selectedCaptureDeviceID  string
	selectedPlaybackDeviceID string

	// Native malgo audio context and devices
	malgoCtx       *malgo.AllocatedContext
	duplexDevice   *malgo.Device
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
	if m.duplexDevice != nil {
		m.duplexDevice.Uninit()
		m.duplexDevice = nil
	}
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

	m.playbackRing = NewAudioRingBuffer(48000 * 2 * 2)
	m.captureRing = NewAudioRingBuffer(48000 * 2 * 2)

	onAudioData := func(pOutputSample, pInputSamples []byte, frameCount uint32) {
		if len(pOutputSample) > 0 {
			m.playbackRing.Read(pOutputSample)
		}
		if len(pInputSamples) > 0 {
			m.captureRing.Write(pInputSamples)
		}
	}

	// 1. Try Full Duplex device first
	duplexConfig := malgo.DefaultDeviceConfig(malgo.Duplex)
	duplexConfig.Playback.Format = malgo.FormatS16
	duplexConfig.Playback.Channels = 1
	duplexConfig.Capture.Format = malgo.FormatS16
	duplexConfig.Capture.Channels = 1
	duplexConfig.SampleRate = 48000
	duplexConfig.Alsa.NoMMap = 1

	if m.selectedPlaybackDeviceID != "" {
		playbackDevices, _ := malgoCtx.Devices(malgo.Playback)
		for i := range playbackDevices {
			if playbackDevices[i].ID.String() == m.selectedPlaybackDeviceID || playbackDevices[i].Name() == m.selectedPlaybackDeviceID {
				duplexConfig.Playback.DeviceID = playbackDevices[i].ID.Pointer()
				break
			}
		}
	}
	if m.selectedCaptureDeviceID != "" {
		captureDevices, _ := malgoCtx.Devices(malgo.Capture)
		for i := range captureDevices {
			if captureDevices[i].ID.String() == m.selectedCaptureDeviceID || captureDevices[i].Name() == m.selectedCaptureDeviceID {
				duplexConfig.Capture.DeviceID = captureDevices[i].ID.Pointer()
				break
			}
		}
	}

	duplexDev, err := malgo.InitDevice(malgoCtx.Context, duplexConfig, malgo.DeviceCallbacks{
		Data: onAudioData,
	})
	if err == nil {
		if err := duplexDev.Start(); err == nil {
			m.duplexDevice = duplexDev
		} else {
			duplexDev.Uninit()
		}
	}

	// 2. If duplex is not active, fallback to separate Playback and Capture devices
	if m.duplexDevice == nil {
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
		playbackDev, err := malgo.InitDevice(malgoCtx.Context, playbackConfig, malgo.DeviceCallbacks{
			Data: onAudioData,
		})
		if err == nil {
			if err := playbackDev.Start(); err == nil {
				m.playbackDevice = playbackDev
			}
		}

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
		captureDev, err := malgo.InitDevice(malgoCtx.Context, captureConfig, malgo.DeviceCallbacks{
			Data: onAudioData,
		})
		if err == nil {
			if err := captureDev.Start(); err == nil {
				m.captureDevice = captureDev
			}
		}
	}

	// 3. Background microphone Opus encoding pump
	go func() {
		encoder, err := NewNativeOpusEncoder(48000, 1)
		if err != nil {
			fmt.Printf("[native_call] Failed to create opus encoder: %v\n", err)
			return
		}
		defer encoder.Close()

		denoiser, err := NewNativeRNNoise()
		if err != nil {
			fmt.Printf("[native_call] RNNoise init info: %v\n", err)
		} else {
			defer denoiser.Close()
		}

		const frameSamples = 960            // 20ms at 48kHz
		const frameBytes = frameSamples * 2 // 16-bit mono PCM = 1920 bytes
		pcmChunk := make([]byte, frameBytes)
		pcmSamples := make([]int16, frameSamples)
		opusBuf := make([]byte, 1000)

		var hpfPrevIn, hpfPrevOut float64
		gateHangover := 0

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

				// 1. High-pass filter (~80Hz) to cut DC offset and mechanical desk/fan rumble
				var sumSq float64
				for i := 0; i < frameSamples; i++ {
					inVal := float64(int16(binary.LittleEndian.Uint16(pcmChunk[i*2 : i*2+2])))
					outVal := 0.989 * (hpfPrevOut + inVal - hpfPrevIn)
					hpfPrevIn = inVal
					hpfPrevOut = outVal

					if outVal > 32767 {
						outVal = 32767
					} else if outVal < -32768 {
						outVal = -32768
					}

					pcmSamples[i] = int16(outVal)
					sumSq += outVal * outVal
				}

				rms := math.Sqrt(sumSq / float64(frameSamples))

				// 2. RNNoise Recurrent Neural Network noise suppression
				var vad float32 = 1.0
				if denoiser != nil {
					vad = denoiser.ProcessFrameDenoise(pcmSamples)
				}

				// 3. Dual-stage Voice Activity Gate (Neural VAD + RMS energy)
				if vad >= 0.40 || rms >= 350.0 {
					gateHangover = 12 // ~240ms hangover to preserve natural speech decay and soft consonants
				} else if gateHangover > 0 {
					gateHangover--
				} else {
					for i := 0; i < frameSamples; i++ {
						pcmSamples[i] = 0
					}
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
		source := "camera"
		if pub != nil {
			trackSID = pub.SID()
			if pub.Source() == livekit.TrackSource_SCREEN_SHARE {
				source = "screen_share"
			}
		}
		wruntime.EventsEmit(a.ctx, "native_call_track", map[string]interface{}{
			"event":    "SUBSCRIBED",
			"kind":     track.Kind().String(),
			"sid":      trackSID,
			"source":   source,
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
		} else if track.Kind() == webrtc.RTPCodecTypeVideo {
			go func() {
				decoder, err := NewNativeVP8Decoder()
				if err != nil {
					fmt.Printf("[native_call] Failed to create VP8 decoder: %v\n", err)
					return
				}
				defer decoder.Close()

				var vp8Packet codecs.VP8Packet
				frameBuffer := make([]byte, 0, 512*1024)

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
							vp8Payload, err := vp8Packet.Unmarshal(pkt.Payload)
							if err == nil {
								frameBuffer = append(frameBuffer, vp8Payload...)
								if pkt.Marker {
									if len(frameBuffer) > 0 {
										jpegBytes, _, _, err := decoder.DecodeJPEG(frameBuffer)
										if err == nil && len(jpegBytes) > 0 {
											globalScreenCapServer.broadcastRemoteVideo(jpegBytes)
										}
										frameBuffer = frameBuffer[:0]
									}
								}
							}
						}
					}
				}
			}()
		}
	}

	cb.OnTrackUnsubscribed = func(track *webrtc.TrackRemote, pub *lksdk.RemoteTrackPublication, rp *lksdk.RemoteParticipant) {
		trackSID := ""
		source := "camera"
		if pub != nil {
			trackSID = pub.SID()
			if pub.Source() == livekit.TrackSource_SCREEN_SHARE {
				source = "screen_share"
			}
		}
		wruntime.EventsEmit(a.ctx, "native_call_track", map[string]interface{}{
			"event":    "UNSUBSCRIBED",
			"kind":     track.Kind().String(),
			"sid":      trackSID,
			"source":   source,
			"identity": rp.Identity(),
		})
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
		Channels:  2, // RFC 7587 requires Channels: 2 in SDP for Opus
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

	if globalCallManager.cancelScreen != nil {
		globalCallManager.cancelScreen()
		globalCallManager.cancelScreen = nil
	}

	if globalCallManager.room != nil {
		globalCallManager.room.Disconnect()
		globalCallManager.room = nil
	}
	globalCallManager.localAudio = nil
	globalCallManager.audioPub = nil
	globalCallManager.videoTrack = nil
	globalCallManager.videoPub = nil
	globalCallManager.stopAudioHardware()
	_ = a.StopScreenCapture()

	wruntime.EventsEmit(a.ctx, "native_call_state", map[string]interface{}{
		"state": "DISCONNECTED",
	})
	return true
}

// NativeCallStartScreenShare starts publishing the selected screen/window to the LiveKit room.
func (a *App) NativeCallStartScreenShare(sourceID string) (string, error) {
	if globalCallManager == nil || globalCallManager.room == nil {
		return "", fmt.Errorf("no active call")
	}

	globalCallManager.mu.Lock()
	defer globalCallManager.mu.Unlock()

	if globalCallManager.videoTrack != nil {
		if globalCallManager.cancelScreen != nil {
			globalCallManager.cancelScreen()
			globalCallManager.cancelScreen = nil
		}
		globalScreenCapServer.setOnRawFrame(nil)
		if globalCallManager.videoPub != nil {
			_ = globalCallManager.room.LocalParticipant.UnpublishTrack(globalCallManager.videoTrack.ID())
			globalCallManager.videoPub = nil
			globalCallManager.videoTrack = nil
		}
	}

	streamURL, err := a.StartScreenCapture(sourceID)
	if err != nil {
		return "", err
	}

	videoTrack, err := lksdk.NewLocalSampleTrack(webrtc.RTPCodecCapability{
		MimeType:  webrtc.MimeTypeVP8,
		ClockRate: 90000,
	})
	if err != nil {
		_ = a.StopScreenCapture()
		return "", fmt.Errorf("failed to create video track: %w", err)
	}

	pub, err := globalCallManager.room.LocalParticipant.PublishTrack(videoTrack, &lksdk.TrackPublicationOptions{
		Name:   "screen_share",
		Source: livekit.TrackSource_SCREEN_SHARE,
	})
	if err != nil {
		_ = a.StopScreenCapture()
		return "", fmt.Errorf("failed to publish screen share track: %w", err)
	}

	globalCallManager.videoTrack = videoTrack
	globalCallManager.videoPub = pub

	capCtx, capCancel := context.WithCancel(context.Background())
	globalCallManager.cancelScreen = capCancel

	var encoder *NativeVP8Encoder
	var curW, curH int
	vp8Buf := make([]byte, 1024*1024)
	frameIdx := 0
	var encMu sync.Mutex

	globalScreenCapServer.setOnRawFrame(func(frame *CapturedFrame) {
		if frame == nil || len(frame.Data) == 0 {
			return
		}

		select {
		case <-capCtx.Done():
			return
		default:
		}

		encMu.Lock()
		defer encMu.Unlock()

		w, h := frame.Width, frame.Height
		if w != curW || h != curH || encoder == nil {
			if encoder != nil {
				encoder.Close()
			}
			enc, err := NewNativeVP8Encoder(w, h, 30, 2500)
			if err != nil {
				return
			}
			encoder = enc
			curW, curH = w, h
		}

		forceKey := (frameIdx%60 == 0)
		frameIdx++

		var n int
		var err error
		if frame.Format == "i420" {
			n, err = encoder.EncodeRawI420(frame.Data, vp8Buf, forceKey)
		} else if frame.Format == "rgba" {
			n, err = encoder.EncodeRGBA(frame.Data, vp8Buf, forceKey)
		}

		if err == nil && n > 0 {
			_ = videoTrack.WriteSample(media.Sample{
				Data:     vp8Buf[:n],
				Duration: 33 * time.Millisecond,
			}, nil)
		}
	})

	go func() {
		<-capCtx.Done()
		globalScreenCapServer.setOnRawFrame(nil)
		encMu.Lock()
		if encoder != nil {
			encoder.Close()
			encoder = nil
		}
		encMu.Unlock()
	}()

	return streamURL, nil
}

// NativeCallStopScreenShare unpublishes the screen share video track.
func (a *App) NativeCallStopScreenShare() bool {
	if globalCallManager == nil {
		return true
	}

	globalCallManager.mu.Lock()
	defer globalCallManager.mu.Unlock()

	if globalCallManager.cancelScreen != nil {
		globalCallManager.cancelScreen()
		globalCallManager.cancelScreen = nil
	}
	globalScreenCapServer.setOnRawFrame(nil)

	if globalCallManager.videoPub != nil && globalCallManager.room != nil {
		_ = globalCallManager.room.LocalParticipant.UnpublishTrack(globalCallManager.videoTrack.ID())
		globalCallManager.videoPub = nil
		globalCallManager.videoTrack = nil
	}

	_ = a.StopScreenCapture()
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
