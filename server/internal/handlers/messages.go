package handlers

import (
	"encoding/json"
	"net/http"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

type historyMessageResponse struct {
	ID          int64  `json:"id"`
	ChatID      int64  `json:"chat_id"`
	SenderID    int64  `json:"sender_id"`
	RecipientID int64  `json:"recipient_id"`
	CipherBytes []byte `json:"cipher_bytes"`
	Timestamp   int64  `json:"timestamp"`
	Delivered   int    `json:"delivered"`
}

// GetMessageHistory handles GET /api/v1/messages/history.
func GetMessageHistory(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())

		rows, err := database.QueryContext(r.Context(),
			`SELECT m.id, m.chat_id, d_sender.user_id, d_recv.user_id, m.ciphertext, m.timestamp, m.delivered
			 FROM messages m
			 JOIN chats c ON m.chat_id = c.id
			 JOIN devices d_sender ON m.sender_device_id = d_sender.id
			 JOIN devices d_recv ON m.recipient_device_id = d_recv.id
			 WHERE c.user1_id=? OR c.user2_id=?
			 ORDER BY m.timestamp ASC`,
			userID, userID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		list := make([]historyMessageResponse, 0)
		for rows.Next() {
			var m historyMessageResponse
			err := rows.Scan(&m.ID, &m.ChatID, &m.SenderID, &m.RecipientID, &m.CipherBytes, &m.Timestamp, &m.Delivered)
			if err != nil {
				continue
			}
			list = append(list, m)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(list)
	}
}
