package handlers

import (
	"encoding/json"
	"fmt"
	"net/http"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

type historyMessageResponse struct {
	ID             int64  `json:"id"`
	ChatID         int64  `json:"chat_id"`
	SenderID       int64  `json:"sender_id"`
	SenderDeviceID int64  `json:"sender_device_id"`
	RecipientID    int64  `json:"recipient_id"`
	ChatUserID     int64  `json:"chat_user_id"` // Owner of the other side of the chat
	CipherBytes    []byte `json:"cipher_bytes"`
	Timestamp      int64  `json:"timestamp"`
	Delivered      int    `json:"delivered"`
}

// GetMessageHistory handles GET /api/v1/messages/history.
// Returns only messages where the caller is either:
// - the sender (their own device sent it), or
// - the recipient (addressed to their current device_id).
// This prevents returning N duplicate rows when a user has multiple historical devices.
func GetMessageHistory(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		deviceID := middleware.DeviceIDFromCtx(r.Context())

		rows, err := database.QueryContext(r.Context(),
			`SELECT m.id, m.chat_id, d_sender.user_id, m.sender_device_id, d_recv.user_id,
			        CASE WHEN d_sender.user_id = ? THEN d_recv.user_id ELSE d_sender.user_id END as chat_user_id,
			        m.ciphertext, m.timestamp, m.delivered
			 FROM messages m
			 JOIN devices d_sender ON m.sender_device_id = d_sender.id
			 JOIN devices d_recv   ON m.recipient_device_id = d_recv.id
			 WHERE d_sender.user_id = ?         -- messages we sent
			    OR m.recipient_device_id = ?    -- messages sent to our current device
			 ORDER BY m.timestamp ASC`,
			userID, userID, deviceID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		list := make([]historyMessageResponse, 0)
		for rows.Next() {
			var m historyMessageResponse
			if err := rows.Scan(&m.ID, &m.ChatID, &m.SenderID, &m.SenderDeviceID, &m.RecipientID, &m.ChatUserID, &m.CipherBytes, &m.Timestamp, &m.Delivered); err != nil {
				continue
			}
			list = append(list, m)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(list)
	}
}

// DeleteChat handles DELETE /api/v1/chats/{peer_id}.
// Deletes the chat and all of its messages from the server database.
func DeleteChat(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		peerIDStr := r.PathValue("peer_id")

		var peerID int64
		if _, err := fmt.Sscan(peerIDStr, &peerID); err != nil {
			http.Error(w, "invalid peer id", http.StatusBadRequest)
			return
		}

		u1, u2 := userID, peerID
		if u1 > u2 {
			u1, u2 = u2, u1
		}

		_, err := database.ExecContext(r.Context(),
			`DELETE FROM chats WHERE user1_id=? AND user2_id=?`, u1, u2)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}
