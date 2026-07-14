package handlers

import (
	"encoding/json"
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
			`SELECT m.id, m.chat_id, d_sender.user_id, m.sender_device_id, d_recv.user_id, m.ciphertext, m.timestamp, m.delivered
			 FROM messages m
			 JOIN devices d_sender ON m.sender_device_id = d_sender.id
			 JOIN devices d_recv   ON m.recipient_device_id = d_recv.id
			 WHERE d_sender.user_id = ?         -- messages we sent
			    OR m.recipient_device_id = ?    -- messages sent to our current device
			 ORDER BY m.timestamp ASC`,
			userID, deviceID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		list := make([]historyMessageResponse, 0)
		for rows.Next() {
			var m historyMessageResponse
			if err := rows.Scan(&m.ID, &m.ChatID, &m.SenderID, &m.SenderDeviceID, &m.RecipientID, &m.CipherBytes, &m.Timestamp, &m.Delivered); err != nil {
				continue
			}
			list = append(list, m)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(list)
	}
}
