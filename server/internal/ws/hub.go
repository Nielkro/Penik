package ws

import "sync"

// Hub manages all connected clients and routes messages between them.
type Hub struct {
	// mu protects clients map.
	mu sync.RWMutex
	// clients maps deviceID → *Client for connected sessions.
	clients map[int64]*Client

	register   chan *Client
	unregister chan *Client
}

// NewHub creates a new Hub and starts its event loop.
func NewHub() *Hub {
	h := &Hub{
		clients:    make(map[int64]*Client),
		register:   make(chan *Client, 16),
		unregister: make(chan *Client, 16),
	}
	go h.run()
	return h
}

func (h *Hub) run() {
	for {
		select {
		case c := <-h.register:
			h.mu.Lock()
			h.clients[c.deviceID] = c
			h.mu.Unlock()

		case c := <-h.unregister:
			h.mu.Lock()
			if existing, ok := h.clients[c.deviceID]; ok && existing == c {
				delete(h.clients, c.deviceID)
				close(c.send)
			}
			h.mu.Unlock()
		}
	}
}

// SendToDevice delivers a pre-encoded frame to the given device if it is online.
func (h *Hub) SendToDevice(deviceID int64, frame []byte) {
	h.mu.RLock()
	c, ok := h.clients[deviceID]
	h.mu.RUnlock()
	if !ok {
		return
	}
	select {
	case c.send <- frame:
	default:
		// Client send buffer full; drop the frame (it is already persisted in DB).
	}
}

// IsOnline reports whether a device has an active connection.
func (h *Hub) IsOnline(deviceID int64) bool {
	h.mu.RLock()
	_, ok := h.clients[deviceID]
	h.mu.RUnlock()
	return ok
}
