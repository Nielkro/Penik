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

// SendToUser delivers a pre-encoded frame to all online devices of a user.
func (h *Hub) SendToUser(userID int64, frame []byte) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, c := range h.clients {
		if c.userID == userID {
			select {
			case c.send <- frame:
			default:
			}
		}
	}
}

// SendToDevice delivers a pre-encoded frame to the given device if it is online.
func (h *Hub) SendToDevice(deviceID int64, frame []byte) {
	// The read lock is held across the send, not just the lookup: unregister
	// closes c.send while holding the write lock, so releasing early would race
	// with it and panic on a send to a closed channel.
	h.mu.RLock()
	defer h.mu.RUnlock()
	c, ok := h.clients[deviceID]
	if !ok {
		return
	}
	select {
	case c.send <- frame:
	default:
		// Client send buffer full; drop the frame (it is already persisted in DB).
	}
}

func (h *Hub) SendToDeviceFrame(deviceID int64, opcode Opcode, payload []byte) {
	h.SendToDevice(deviceID, append([]byte{byte(opcode)}, payload...))
}

// IsOnline reports whether a device has an active connection.
func (h *Hub) IsOnline(deviceID int64) bool {
	h.mu.RLock()
	_, ok := h.clients[deviceID]
	h.mu.RUnlock()
	return ok
}

// deviceReplaced reports whether the device of the given connection is now held
// by a different connection. A client that reconnects before its previous socket
// finished tearing down would otherwise have the old connection's cleanup cancel
// the call the new one is carrying.
func (h *Hub) deviceReplaced(c *Client) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	existing, ok := h.clients[c.deviceID]
	return ok && existing != c
}

// IsUserOnlineExcept reports whether a user has an active connection on any
// device other than the given one. Used while a connection is tearing down, when
// its own unregister has not been processed yet.
func (h *Hub) IsUserOnlineExcept(userID, deviceID int64) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, c := range h.clients {
		if c.userID == userID && c.deviceID != deviceID {
			return true
		}
	}
	return false
}

// UserDeviceIDs lists the device ids a user currently has connected. Call
// signaling needs the concrete devices, not just a boolean: an incoming call
// rings every device and each one is tracked separately.
func (h *Hub) UserDeviceIDs(userID int64) []int64 {
	h.mu.RLock()
	defer h.mu.RUnlock()
	var ids []int64
	for _, c := range h.clients {
		if c.userID == userID {
			ids = append(ids, c.deviceID)
		}
	}
	return ids
}

// IsUserOnline reports whether a user has at least one active connection.
func (h *Hub) IsUserOnline(userID int64) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, c := range h.clients {
		if c.userID == userID {
			return true
		}
	}
	return false
}

// BroadcastProfileUpdate sends OpUserProfileUpdate to the given devices. Without
// it a display name only ever reaches peers that had no cached chat entry yet, so
// a rename stayed invisible to everyone already talking to the user.
func (h *Hub) BroadcastProfileUpdate(deviceIDs []int64, payload []byte) {
	frame := append([]byte{byte(OpUserProfileUpdate)}, payload...)
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, devID := range deviceIDs {
		if c, ok := h.clients[devID]; ok {
			select {
			case c.send <- frame:
			default:
			}
		}
	}
}

// BroadcastAvatarUpdate sends OpUserAvatarUpdate to all active connections for specified device IDs.
func (h *Hub) BroadcastAvatarUpdate(deviceIDs []int64, payload []byte) {
	frame := append([]byte{byte(OpUserAvatarUpdate)}, payload...)
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, devID := range deviceIDs {
		if c, ok := h.clients[devID]; ok {
			select {
			case c.send <- frame:
			default:
			}
		}
	}
}

// BroadcastPresence sends OpPresenceUpdate to all active connections for specified device IDs.
func (h *Hub) BroadcastPresence(deviceIDs []int64, payload []byte) {
	frame := append([]byte{byte(OpPresenceUpdate)}, payload...)
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, devID := range deviceIDs {
		if c, ok := h.clients[devID]; ok {
			select {
			case c.send <- frame:
			default:
			}
		}
	}
}

// BroadcastServerShutdown sends OpServerShutdown to all connected clients.
func (h *Hub) BroadcastServerShutdown() {
	frame := []byte{byte(OpServerShutdown)}
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, c := range h.clients {
		select {
		case c.send <- frame:
		default:
		}
	}
}
