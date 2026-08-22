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
	ID                int64   `json:"id"`
	ChatID            int64   `json:"chat_id"`
	SenderID          int64   `json:"sender_id"`
	RecipientID       int64   `json:"recipient_id"`
	ChatUserID        int64   `json:"chat_user_id"` // Owner of the other side of the chat
	ClientMsgID       *string `json:"client_msg_id,omitempty"`
	ReplyToMsgID      *string `json:"reply_to_msg_id,omitempty"`
	Plaintext         *string `json:"plaintext,omitempty"`
	Timestamp         int64   `json:"timestamp"`
	Delivered         int     `json:"delivered"`
	DeliveredAt       *int64  `json:"delivered_at,omitempty"`
	Read              int     `json:"read"`
	Ciphertext        []byte  `json:"ciphertext,omitempty"`
	EncryptionSalt    []byte  `json:"encryption_salt,omitempty"`
	EncryptionNonce   []byte  `json:"encryption_nonce,omitempty"`
	SenderDeviceID    *int64  `json:"sender_device_id,omitempty"`
	RecipientDeviceID *int64  `json:"recipient_device_id,omitempty"`
	PrekeyID          *int64  `json:"prekey_id,omitempty"`
}

// GetMessageHistory handles GET /api/v1/messages/history.
// History is scoped to the authenticated device. A user can have several
// devices, and each fan-out copy is encrypted for that device identity key.
// Returning another device's copy makes the client attempt decryption with a
// private identity key it does not possess.
func GetMessageHistory(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		deviceID := middleware.DeviceIDFromCtx(r.Context())

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
				m.reply_to_msg_id,
				m.plaintext,
				m.timestamp,
				m.delivered,
				m.delivered_at,
				m.read,
				m.ciphertext,
				m.encryption_salt,
				m.encryption_nonce,
				m.sender_device_id,
				m.recipient_device_id,
				m.prekey_id
			 FROM messages m
			 JOIN chats c ON c.id = m.chat_id
			 WHERE m.purge_pending = 0
			   AND m.id NOT IN (SELECT message_id FROM device_history_exclusions WHERE device_id = ?)
			   AND (
             (m.sender_user_id = ? AND m.sender_device_id = ? AND m.deleted_by_sender = 0
              AND (m.sender_user_id != m.recipient_user_id OR m.recipient_device_id = ?))
             OR
             (m.recipient_user_id = ? AND m.recipient_device_id = ? AND m.deleted_by_recipient = 0)
			   )
			 ORDER BY m.id DESC
			 LIMIT ?`,
			userID, deviceID, userID, deviceID, deviceID, userID, deviceID, limit)
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
				&m.ReplyToMsgID,
				&m.Plaintext,
				&m.Timestamp,
				&m.Delivered,
				&m.DeliveredAt,
				&m.Read,
				&m.Ciphertext,
				&m.EncryptionSalt,
				&m.EncryptionNonce,
				&m.SenderDeviceID,
				&m.RecipientDeviceID,
				&m.PrekeyID,
			); err != nil {
				continue
			}
			list = append(list, m)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(list)
	}
}

// GetMessageByID handles GET /api/v1/messages/by-id/{id}.
//
// Push notifications carry only a message id (FCM caps a data payload at ~4 KB,
// so shipping the ciphertext silently lost long messages). The device resolves
// the id here. Scoped to the requesting device exactly like the history query: a
// copy addressed to another device is encrypted under a key this caller does not
// hold, and must not be handed out.
func GetMessageByID(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		deviceID := middleware.DeviceIDFromCtx(r.Context())
		id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err != nil || id <= 0 {
			http.Error(w, "invalid message id", http.StatusBadRequest)
			return
		}

		var m historyMessageResponse
		err = database.QueryRowContext(r.Context(),
			`SELECT
				m.id, m.chat_id, m.sender_user_id, m.recipient_user_id,
				CASE WHEN c.user1_id = ? THEN c.user2_id ELSE c.user1_id END,
				m.client_msg_id, m.reply_to_msg_id, m.plaintext, m.timestamp,
				m.delivered, m.delivered_at, m.read,
				m.ciphertext, m.encryption_salt, m.encryption_nonce,
				m.sender_device_id, m.recipient_device_id, m.prekey_id
			 FROM messages m
			 JOIN chats c ON c.id = m.chat_id
			 WHERE m.id = ?
			   AND m.purge_pending = 0
			   AND (
			     (m.sender_user_id = ? AND m.sender_device_id = ? AND m.deleted_by_sender = 0)
			     OR
			     (m.recipient_user_id = ? AND m.recipient_device_id = ? AND m.deleted_by_recipient = 0)
			   )`,
			userID, id, userID, deviceID, userID, deviceID).
			Scan(&m.ID, &m.ChatID, &m.SenderID, &m.RecipientID, &m.ChatUserID,
				&m.ClientMsgID, &m.ReplyToMsgID, &m.Plaintext, &m.Timestamp,
				&m.Delivered, &m.DeliveredAt, &m.Read,
				&m.Ciphertext, &m.EncryptionSalt, &m.EncryptionNonce,
				&m.SenderDeviceID, &m.RecipientDeviceID, &m.PrekeyID)
		if err == sql.ErrNoRows {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(m)
	}
}

func GetMessageStatuses(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		peerID, err := strconv.ParseInt(r.PathValue("user_id"), 10, 64)
		if err != nil {
			http.Error(w, "invalid user id", http.StatusBadRequest)
			return
		}
		rows, err := database.QueryContext(r.Context(), `SELECT id, delivered, read FROM messages WHERE sender_user_id=? AND recipient_user_id=? ORDER BY id ASC`, userID, peerID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()
		statuses := make([]map[string]interface{}, 0)
		for rows.Next() {
			var id int64
			var delivered, read int
			if rows.Scan(&id, &delivered, &read) == nil {
				statuses = append(statuses, map[string]interface{}{"msg_id": id, "delivered": delivered == 1, "read": read == 1})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(statuses)
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
