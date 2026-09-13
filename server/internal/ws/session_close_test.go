package ws

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"nhooyr.io/websocket"
)

func TestHubCloseSession(t *testing.T) {
	hub := NewHub()

	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		c := NewClient(hub, conn, 1, 10, "token-hash-1", 0, nil)
		hub.register <- c
		// Keep server handler running so connection stays open
		<-r.Context().Done()
	}))
	defer s.Close()

	wsURL := "ws" + strings.TrimPrefix(s.URL, "http")
	clientConn, _, err := websocket.Dial(context.Background(), wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer clientConn.Close(websocket.StatusNormalClosure, "")

	time.Sleep(50 * time.Millisecond)

	if !hub.IsOnline(10) {
		t.Fatal("expected client to be online")
	}

	// Close session with token-hash-1
	hub.CloseSession("token-hash-1")

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, _, err = clientConn.Read(ctx)
	if err == nil {
		t.Fatal("expected error reading from closed connection, got nil")
	}
	if websocket.CloseStatus(err) != websocket.StatusPolicyViolation {
		t.Fatalf("expected StatusPolicyViolation (1008), got: %v", err)
	}
}

func TestHubCloseUserSessionsExcept(t *testing.T) {
	hub := NewHub()

	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		dev := int64(1)
		th := "token-1"
		if r.URL.Query().Get("dev") == "2" {
			dev = 2
			th = "token-2"
		}
		c := NewClient(hub, conn, 42, dev, th, 0, nil)
		hub.register <- c
		<-r.Context().Done()
	}))
	defer s.Close()

	wsURL := "ws" + strings.TrimPrefix(s.URL, "http")

	// Device 1
	c1, _, err := websocket.Dial(context.Background(), wsURL+"?dev=1", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c1.Close(websocket.StatusNormalClosure, "")

	// Device 2
	c2, _, err := websocket.Dial(context.Background(), wsURL+"?dev=2", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c2.Close(websocket.StatusNormalClosure, "")

	time.Sleep(50 * time.Millisecond)

	// Close all sessions of user 42 except token-2 (Device 2)
	hub.CloseUserSessionsExcept(42, "token-2")

	// Device 1 must be closed with StatusPolicyViolation
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, _, err = c1.Read(ctx)
	if err == nil {
		t.Fatal("expected c1 to be closed")
	}
	if websocket.CloseStatus(err) != websocket.StatusPolicyViolation {
		t.Fatalf("expected c1 StatusPolicyViolation (1008), got: %v", err)
	}

	// Device 2 must remain open
	if !hub.IsOnline(2) {
		t.Fatal("expected c2 to still be online")
	}
}
