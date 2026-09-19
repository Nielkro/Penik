package main

import (
	"bytes"
	"context"
	"fmt"
	"image"
	"image/jpeg"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// CaptureSource represents an available screen (monitor) or application window.
type CaptureSource struct {
	ID        string `json:"id"`        // e.g. "screen:0", "window:12345"
	Name      string `json:"name"`      // e.g. "Display 1", "Google Chrome"
	Type      string `json:"type"`      // "screen" or "window"
	Thumbnail string `json:"thumbnail"` // base64 JPEG thumbnail preview
	Width     int    `json:"width"`
	Height    int    `json:"height"`
}

// CapturedFrame represents an uncompressed raw frame from platform screen capture.
type CapturedFrame struct {
	Width  int
	Height int
	Format string // "i420" or "rgba"
	Data   []byte
}

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		// Allow local origin (Wails app)
		return true
	},
}

// ScreenCapServer manages the local WebSocket stream of captured screen/window frames.
type ScreenCapServer struct {
	mu            sync.Mutex
	listener      net.Listener
	server        *http.Server
	clients       map[*websocket.Conn]chan []byte
	remoteClients map[*websocket.Conn]chan []byte
	activeSource  string
	cancelCap     context.CancelFunc
	port          int
	rawMu         sync.RWMutex
	onRawFrame    func(frame *CapturedFrame)
	lastPreview   time.Time
}

var globalScreenCapServer = &ScreenCapServer{
	clients:       make(map[*websocket.Conn]chan []byte),
	remoteClients: make(map[*websocket.Conn]chan []byte),
}

func (s *ScreenCapServer) setOnRawFrame(fn func(frame *CapturedFrame)) {
	s.rawMu.Lock()
	defer s.rawMu.Unlock()
	s.onRawFrame = fn
}

func (s *ScreenCapServer) handleCapturedFrame(frame *CapturedFrame) {
	if frame == nil || len(frame.Data) == 0 {
		return
	}

	// 1. Deliver raw frame immediately to WebRTC VP8 encoder (real-time, zero-delay)
	s.rawMu.RLock()
	rawFn := s.onRawFrame
	s.rawMu.RUnlock()
	if rawFn != nil {
		rawFn(frame)
	}

	// 2. Local UI preview: throttle strictly to ~2 FPS (500ms), only when UI is listening
	s.mu.Lock()
	hasClients := len(s.clients) > 0
	now := time.Now()
	due := now.Sub(s.lastPreview) >= 500*time.Millisecond
	if hasClients && due {
		s.lastPreview = now
	}
	s.mu.Unlock()

	if hasClients && due {
		if frame.Format == "i420" {
			yLen := frame.Width * frame.Height
			uvW := (frame.Width + 1) / 2
			uvH := (frame.Height + 1) / 2
			uvLen := uvW * uvH
			if len(frame.Data) >= yLen+2*uvLen {
				img := &image.YCbCr{
					Y:              frame.Data[:yLen],
					Cb:             frame.Data[yLen : yLen+uvLen],
					Cr:             frame.Data[yLen+uvLen : yLen+2*uvLen],
					YStride:        frame.Width,
					CStride:        uvW,
					SubsampleRatio: image.YCbCrSubsampleRatio420,
					Rect:           image.Rect(0, 0, frame.Width, frame.Height),
				}
				var buf bytes.Buffer
				if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 35}); err == nil {
					s.broadcastFrame(buf.Bytes())
				}
			}
		} else if frame.Format == "rgba" {
			img := &image.RGBA{
				Pix:    frame.Data,
				Stride: frame.Width * 4,
				Rect:   image.Rect(0, 0, frame.Width, frame.Height),
			}
			var buf bytes.Buffer
			if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 35}); err == nil {
				s.broadcastFrame(buf.Bytes())
			}
		}
	}
}

func (s *ScreenCapServer) ensureServerRunning() (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.server != nil && s.port > 0 {
		return s.port, nil
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("failed to bind local screencap server: %w", err)
	}
	s.listener = ln
	s.port = ln.Addr().(*net.TCPAddr).Port

	mux := http.NewServeMux()
	mux.HandleFunc("/stream/screenshare", s.handleWS)
	mux.HandleFunc("/stream/remote_video", s.handleRemoteWS)

	s.server = &http.Server{
		Handler: mux,
	}

	go func() {
		_ = s.server.Serve(ln)
	}()

	return s.port, nil
}

func (s *ScreenCapServer) handleWS(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	frameCh := make(chan []byte, 1)

	s.mu.Lock()
	s.clients[conn] = frameCh
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.clients, conn)
		s.mu.Unlock()
		close(frameCh)
		_ = conn.Close()
	}()

	// Non-blocking asynchronous sender: drops if network is busy, zero backlog
	go func() {
		for frame := range frameCh {
			if err := conn.WriteMessage(websocket.BinaryMessage, frame); err != nil {
				break
			}
		}
	}()

	// Keep alive and read until disconnected
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			break
		}
	}
}

func (s *ScreenCapServer) handleRemoteWS(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	frameCh := make(chan []byte, 1)

	s.mu.Lock()
	s.remoteClients[conn] = frameCh
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.remoteClients, conn)
		s.mu.Unlock()
		close(frameCh)
		_ = conn.Close()
	}()

	go func() {
		for frame := range frameCh {
			if err := conn.WriteMessage(websocket.BinaryMessage, frame); err != nil {
				break
			}
		}
	}()

	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			break
		}
	}
}

// broadcastFrame sends a binary JPEG frame to connected preview clients with drop-old policy.
func (s *ScreenCapServer) broadcastFrame(frameBytes []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()

	for _, ch := range s.clients {
		select {
		case ch <- frameBytes:
		default:
			// Previous preview frame not consumed yet: drop to prevent any queue buildup
		}
	}
}

// broadcastRemoteVideo sends a binary JPEG frame of remote video to all connected local clients.
func (s *ScreenCapServer) broadcastRemoteVideo(frameBytes []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()

	for _, ch := range s.remoteClients {
		select {
		case ch <- frameBytes:
		default:
		}
	}
}

// StartCapture initiates the capture loop for the given source ID and returns the local ws stream URL.
func (a *App) StartScreenCapture(sourceID string) (string, error) {
	port, err := globalScreenCapServer.ensureServerRunning()
	if err != nil {
		return "", err
	}

	globalScreenCapServer.mu.Lock()
	if globalScreenCapServer.cancelCap != nil {
		globalScreenCapServer.cancelCap()
		globalScreenCapServer.cancelCap = nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	globalScreenCapServer.cancelCap = cancel
	globalScreenCapServer.activeSource = sourceID
	globalScreenCapServer.mu.Unlock()

	// Launch platform-specific capture loop
	go startPlatformCapture(ctx, sourceID, func(frame *CapturedFrame) {
		globalScreenCapServer.handleCapturedFrame(frame)
	})

	return fmt.Sprintf("ws://127.0.0.1:%d/stream/screenshare", port), nil
}

// StopScreenCapture terminates any active screen capture stream.
func (a *App) StopScreenCapture() error {
	globalScreenCapServer.mu.Lock()
	defer globalScreenCapServer.mu.Unlock()

	if globalScreenCapServer.cancelCap != nil {
		globalScreenCapServer.cancelCap()
		globalScreenCapServer.cancelCap = nil
	}
	globalScreenCapServer.activeSource = ""
	return nil
}

// GetCaptureSources returns available screens and application windows.
func (a *App) GetCaptureSources() ([]CaptureSource, error) {
	return getPlatformCaptureSources()
}

// GetSourceThumbnail returns a base64 thumbnail for a source ID.
func (a *App) GetSourceThumbnail(sourceID string) (string, error) {
	return getPlatformSourceThumbnail(sourceID)
}

// GetRemoteVideoStreamURL returns the local websocket stream URL for remote video/screenshare.
func (a *App) GetRemoteVideoStreamURL() (string, error) {
	port, err := globalScreenCapServer.ensureServerRunning()
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("ws://127.0.0.1:%d/stream/remote_video", port), nil
}

