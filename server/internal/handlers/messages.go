package handlers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

type historyMessageResponse struct {
	ID          int64   `json:"id"`
	ChatID      int64   `json:"chat_id"`
	SenderID    int64   `json:"sender_id"`
	RecipientID int64   `json:"recipient_id"`
	ChatUserID  int64   `json:"chat_user_id"` // Owner of the other side of the chat
	ClientMsgID *string `json:"client_msg_id,omitempty"`
	Plaintext   string  `json:"plaintext"`
	Timestamp   int64   `json:"timestamp"`
	Delivered   int     `json:"delivered"`
	DeliveredAt *int64  `json:"delivered_at,omitempty"`
}

// GetMessageHistory handles GET /api/v1/messages/history.
// Messages are user-owned, so every logical message appears exactly once.
func GetMessageHistory(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())

		limitStr := r.URL.Query().Get("limit")

		limit := 100

		if limitStr != "" {
			if l, err := strconv.Atoi(limitStr); err == nil && l > 0 {
				limit = l
				if limit > 500 {
					limit = 500
				}
			}
		}
		rows, err := database.QueryContext(r.Context(),
			`SELECT
				m.id,
				m.chat_id,
				m.sender_user_id,
				m.recipient_user_id,
				CASE WHEN c.user1_id = ? THEN c.user2_id ELSE c.user1_id END,
				m.client_msg_id,
				m.plaintext,
				m.timestamp,
				m.delivered,
				m.delivered_at
			 FROM messages m
			 JOIN chats c ON c.id = m.chat_id
			 WHERE m.purge_pending = 0
			   AND (
			     (m.sender_user_id = ? AND m.deleted_by_sender = 0)
			     OR
			     (m.recipient_user_id = ? AND m.deleted_by_recipient = 0)
			   )
			 ORDER BY m.id DESC
			 LIMIT ?`,
			userID, userID, userID, limit)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		list := make([]historyMessageResponse, 0)
		for rows.Next() {
			var m historyMessageResponse
			if err := rows.Scan(
				&m.ID,
				&m.ChatID,
				&m.SenderID,
				&m.RecipientID,
				&m.ChatUserID,
				&m.ClientMsgID,
				&m.Plaintext,
				&m.Timestamp,
				&m.Delivered,
				&m.DeliveredAt,
			); err != nil {
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
func DeleteChat(database *db.DB, hub *ws.Hub) http.HandlerFunc {
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

		everyone := r.URL.Query().Get("everyone") == "true"
		if everyone {
			// Find the chat_id first.
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

			// Keep a user-level tombstone until the peer confirms the local purge.
			_, err = database.ExecContext(r.Context(),
				`UPDATE messages
				 SET purge_pending = 1, purge_for_user_id = ?
				 WHERE chat_id = ?`,
				peerID,
				chatID)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}

			// Push a live purge to any online devices of the peer.
			if hub != nil {
				rows, err := database.QueryContext(r.Context(),
					`SELECT id FROM devices WHERE user_id = ?`, peerID)
				if err == nil {
					var deviceIDs []int64
					for rows.Next() {
						var id int64
						if err := rows.Scan(&id); err == nil {
							deviceIDs = append(deviceIDs, id)
						}
					}
					rows.Close()
					frame, encErr := ws.EncodeFrame(ws.OpChatPurge, ws.ChatPurge{ChatUserID: userID})
					if encErr == nil {
						for _, did := range deviceIDs {
							hub.SendToDevice(did, frame)
						}
					}
				}
			}

			w.WriteHeader(http.StatusNoContent)
			return
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

		// Soft-delete each message according to the caller's role in it.
		_, err = tx.ExecContext(r.Context(),
			`UPDATE messages
			 SET deleted_by_recipient = 1
			 WHERE chat_id = ? AND recipient_user_id = ?`,
			chatID, userID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		_, err = tx.ExecContext(r.Context(),
			`UPDATE messages
			 SET deleted_by_sender = 1
			 WHERE chat_id = ? AND sender_user_id = ?`,
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
