package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"sync"

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

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		// Allow local origin (Wails app)
		return true
	},
}

// ScreenCapServer manages the local WebSocket stream of captured screen/window frames.
type ScreenCapServer struct {
	mu           sync.Mutex
	listener     net.Listener
	server       *http.Server
	clients      map[*websocket.Conn]bool
	activeSource string
	cancelCap    context.CancelFunc
	port         int
}

var globalScreenCapServer = &ScreenCapServer{
	clients: make(map[*websocket.Conn]bool),
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

	s.mu.Lock()
	s.clients[conn] = true
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.clients, conn)
		s.mu.Unlock()
		_ = conn.Close()
	}()

	// Keep alive and read until disconnected
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			break
		}
	}
}

// broadcastFrame sends a binary JPEG frame to all connected local clients.
func (s *ScreenCapServer) broadcastFrame(frameBytes []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()

	for client := range s.clients {
		_ = client.WriteMessage(websocket.BinaryMessage, frameBytes)
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
	go startPlatformCapture(ctx, sourceID, func(jpegBytes []byte) {
		globalScreenCapServer.broadcastFrame(jpegBytes)
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

