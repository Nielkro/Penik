package handlers

import (
	"database/sql"
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
			`SELECT id, chat_id, sender_id, sender_device_id, recipient_id, chat_user_id, ciphertext, timestamp, delivered
			 FROM (
				 -- Incoming messages from other users addressed to our current device (excluding soft-deleted ones)
				 SELECT m.id, m.chat_id, d_sender.user_id as sender_id, m.sender_device_id, d_recv.user_id as recipient_id,
				        CASE WHEN c.user1_id = ? THEN c.user2_id ELSE c.user1_id END as chat_user_id,
				        m.ciphertext, m.timestamp, m.delivered
				 FROM messages m
				 JOIN chats c ON m.chat_id = c.id
				 JOIN devices d_sender ON m.sender_device_id = d_sender.id
				 JOIN devices d_recv   ON m.recipient_device_id = d_recv.id
				 WHERE m.recipient_device_id = ? AND d_sender.user_id != ? AND m.deleted_by_recipient = 0

				 UNION ALL

				 -- Outgoing messages sent by us, deduplicated by timestamp to avoid multi-device row duplication (excluding soft-deleted ones)
				 SELECT MAX(m.id) as id, m.chat_id, d_sender.user_id as sender_id, m.sender_device_id, d_recv.user_id as recipient_id,
				        CASE WHEN c.user1_id = ? THEN c.user2_id ELSE c.user1_id END as chat_user_id,
				        m.ciphertext, m.timestamp, m.delivered
				 FROM messages m
				 JOIN chats c ON m.chat_id = c.id
				 JOIN devices d_sender ON m.sender_device_id = d_sender.id
				 JOIN devices d_recv   ON m.recipient_device_id = d_recv.id
				 WHERE d_sender.user_id = ? AND m.deleted_by_sender = 0
				 GROUP BY m.chat_id, m.sender_device_id, m.timestamp
			 )
			 ORDER BY timestamp ASC`,
			userID, deviceID, userID, userID, userID)
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
// Deletes messages in the chat only for the caller.
// If messages are already deleted by both participants, they are purged from the database.
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

		// Find the chat_id
		var chatID int64
		err := database.QueryRowContext(r.Context(),
			`SELECT id FROM chats WHERE user1_id=? AND user2_id=?`, u1, u2).Scan(&chatID)
		if err != nil {
			if err == sql.ErrNoRows {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		// Use a transaction to perform all soft-delete updates
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		// Mark as deleted by recipient where recipient is the caller
		_, err = tx.ExecContext(r.Context(),
			`UPDATE messages SET deleted_by_recipient = 1
			 WHERE chat_id = ? AND recipient_device_id IN (SELECT id FROM devices WHERE user_id = ?)`,
			chatID, userID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		// Mark as deleted by sender where sender is the caller
		_, err = tx.ExecContext(r.Context(),
			`UPDATE messages SET deleted_by_sender = 1
			 WHERE chat_id = ? AND sender_device_id IN (SELECT id FROM devices WHERE user_id = ?)`,
			chatID, userID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		// Delete messages that are soft-deleted by both sides
		_, err = tx.ExecContext(r.Context(),
			`DELETE FROM messages 
			 WHERE chat_id = ? 
			   AND deleted_by_sender = 1 
			   AND deleted_by_recipient = 1`,
			chatID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}
