package handlers

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/push"
	"messenger/server/internal/ws"
)

// sendMessageRequest is the JSON body for POST /api/v1/messages/send.
// It mirrors the WebSocket MsgSendEncrypted payload but over REST.
type sendMessageRequest struct {
	ToUserID     int64              `json:"to_user_id"`
	MsgID        string             `json:"msg_id"`        // client-generated idempotency key
	ReplyToMsgID *string            `json:"reply_to_msg_id,omitempty"`
	Devices      []sendDevicePayload `json:"devices"`
}

type sendDevicePayload struct {
	DeviceID   int64  `json:"device_id"`
	Ciphertext string `json:"ciphertext"` // base64-encoded
	Salt       string `json:"salt"`       // base64-encoded
	Nonce      string `json:"nonce"`      // base64-encoded
}

type sendMessageResponse struct {
	MsgID       int64  `json:"msg_id"`        // server-assigned id of the first stored row
	ClientMsgID string `json:"client_msg_id"` // echoed back for ack matching
}

// SendMessage handles POST /api/v1/messages/send — a REST equivalent of the
// WebSocket OpMsgSend frame. Used by background receivers (e.g. notification
// quick-reply) where the WebSocket may not be connected.
func SendMessage(database *db.DB, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		senderUserID := middleware.UserIDFromCtx(r.Context())
		senderDeviceID := middleware.DeviceIDFromCtx(r.Context())

		var req sendMessageRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid JSON body", http.StatusBadRequest)
			return
		}
		if req.ToUserID <= 0 || len(req.Devices) == 0 || req.MsgID == "" {
			http.Error(w, "to_user_id, msg_id and devices are required", http.StatusBadRequest)
			return
		}

		ctx := r.Context()
		now := time.Now().Unix()

		// Verify recipient exists.
		var recipientExists int
		if err := database.QueryRowContext(ctx,
			`SELECT 1 FROM users WHERE id=?`, req.ToUserID).Scan(&recipientExists); err != nil {
			if err == sql.ErrNoRows {
				http.Error(w, "recipient not found", http.StatusNotFound)
				return
			}
			http.Error(w, "db error", http.StatusInternalServerError)
			return
		}

		// Idempotency: return existing ack if already stored.
		var existingMsgID int64
		if err := database.QueryRowContext(ctx,
			`SELECT id FROM messages WHERE sender_user_id=? AND client_msg_id=? AND recipient_device_id IS NOT NULL LIMIT 1`,
			senderUserID, req.MsgID).Scan(&existingMsgID); err == nil {
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(sendMessageResponse{MsgID: existingMsgID, ClientMsgID: req.MsgID})
			return
		}

		// Ensure chat row exists.
		u1, u2 := senderUserID, req.ToUserID
		if u1 > u2 {
			u1, u2 = u2, u1
		}
		var chatID int64
		err := database.QueryRowContext(ctx, `SELECT id FROM chats WHERE user1_id=? AND user2_id=?`, u1, u2).Scan(&chatID)
		if err == sql.ErrNoRows {
			res, err2 := database.ExecContext(ctx,
				`INSERT INTO chats(user1_id,user2_id,created_at) VALUES(?,?,?)`, u1, u2, now)
			if err2 != nil {
				http.Error(w, "create chat: "+err2.Error(), http.StatusInternalServerError)
				return
			}
			chatID, _ = res.LastInsertId()
		} else if err != nil {
			http.Error(w, "lookup chat: "+err.Error(), http.StatusInternalServerError)
			return
		}

		// Look up sender's public identity key for the MsgRecv frame.
		var senderIKPub []byte
		_ = database.QueryRowContext(ctx, `SELECT x25519_pub FROM device_public_keys WHERE device_id=?`, senderDeviceID).Scan(&senderIKPub)

		type pendingDelivery struct {
			deviceID int64
			msgRecv  ws.MsgRecvEncrypted
		}
		var deliveries []pendingDelivery
		var firstMsgID int64

		tx, err := database.BeginTx(ctx, nil)
		if err != nil {
			http.Error(w, "begin tx: "+err.Error(), http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		for _, dev := range req.Devices {
			ciphertext, err1 := base64.StdEncoding.DecodeString(dev.Ciphertext)
			salt, err2 := base64.StdEncoding.DecodeString(dev.Salt)
			nonce, err3 := base64.StdEncoding.DecodeString(dev.Nonce)
			if err1 != nil || err2 != nil || err3 != nil {
				http.Error(w, "invalid base64 in device payload", http.StatusBadRequest)
				return
			}

			var ownerID int64
			if err := tx.QueryRowContext(ctx, `SELECT user_id FROM devices WHERE id=?`, dev.DeviceID).Scan(&ownerID); err != nil {
				continue // unknown device, skip
			}

			var recipientID, chatUserID int64
			if ownerID == senderUserID {
				recipientID = senderUserID
				chatUserID = req.ToUserID
			} else {
				recipientID = req.ToUserID
				chatUserID = senderUserID
			}

			res, err := tx.ExecContext(ctx,
				`INSERT INTO messages(
					chat_id, sender_user_id, recipient_user_id, client_msg_id, reply_to_msg_id,
					plaintext, ciphertext, encryption_salt, encryption_nonce,
					sender_device_id, recipient_device_id, prekey_id, timestamp, delivered
				) VALUES(?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, NULL, ?, 0)`,
				chatID, senderUserID, recipientID, req.MsgID, req.ReplyToMsgID,
				ciphertext, salt, nonce,
				senderDeviceID, dev.DeviceID, now)
			if err != nil {
				http.Error(w, fmt.Sprintf("insert message: %v", err), http.StatusInternalServerError)
				return
			}
			messageID, _ := res.LastInsertId()
			if firstMsgID == 0 {
				firstMsgID = messageID
			}

			deliveries = append(deliveries, pendingDelivery{
				deviceID: dev.DeviceID,
				msgRecv: ws.MsgRecvEncrypted{
					FromUserID:        senderUserID,
					FromDeviceID:      senderDeviceID,
					RecipientDeviceID: dev.DeviceID,
					FromIdentityKey:   senderIKPub,
					ChatUserID:        chatUserID,
					MsgID:             messageID,
					ClientMsgID:       req.MsgID,
					ReplyToMsgID:      req.ReplyToMsgID,
					Ciphertext:        ciphertext,
					Salt:              salt,
					Nonce:             nonce,
					TS:                now,
				},
			})
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "commit: "+err.Error(), http.StatusInternalServerError)
			return
		}

		// Push FCM to offline recipient devices.
		var senderName string
		_ = database.QueryRowContext(ctx, "SELECT name FROM users WHERE id=?", senderUserID).Scan(&senderName)
		if senderName == "" {
			senderName = "Пользователь"
		}
		for _, dev := range req.Devices {
			var devOwnerID int64
			_ = database.QueryRowContext(ctx, "SELECT user_id FROM devices WHERE id=?", dev.DeviceID).Scan(&devOwnerID)
			if devOwnerID == senderUserID {
				continue
			}
			if hub.IsOnline(dev.DeviceID) {
				continue
			}
			var fcmToken string
			_ = database.QueryRowContext(ctx, "SELECT fcm_token FROM devices WHERE id=?", dev.DeviceID).Scan(&fcmToken)
			if fcmToken == "" {
				continue
			}
			ct, _ := base64.StdEncoding.DecodeString(dev.Ciphertext)
			s, _ := base64.StdEncoding.DecodeString(dev.Salt)
			n, _ := base64.StdEncoding.DecodeString(dev.Nonce)
			push.SendDevicePush(fcmToken, map[string]string{
				"type":           "direct",
				"chat_user_id":   fmt.Sprintf("%d", senderUserID),
				"sender_name":    senderName,
				"text":           "Новое сообщение",
				"ciphertext":     base64.StdEncoding.EncodeToString(ct),
				"salt":           base64.StdEncoding.EncodeToString(s),
				"nonce":          base64.StdEncoding.EncodeToString(n),
				"timestamp":      fmt.Sprintf("%d", now*1000),
				"sender_user_id": fmt.Sprintf("%d", senderUserID),
			})
		}

		// Deliver in real-time to online devices.
		for _, deliv := range deliveries {
			frame, err := ws.EncodeFrame(ws.OpMsgRecv, deliv.msgRecv)
			if err == nil {
				hub.SendToDevice(deliv.deviceID, frame)
			}
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(sendMessageResponse{MsgID: firstMsgID, ClientMsgID: req.MsgID})
	}
}

// MarkMessagesRead handles POST /api/v1/messages/{user_id}/read — marks all
// unread messages from a peer as read and sends read receipts over WebSocket.
func MarkMessagesRead(database *db.DB, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		myUserID := middleware.UserIDFromCtx(r.Context())
		ctx := r.Context()

		peerIDStr := r.PathValue("user_id")
		var peerID int64
		if _, err := fmt.Sscan(peerIDStr, &peerID); err != nil || peerID <= 0 {
			http.Error(w, "invalid user_id", http.StatusBadRequest)
			return
		}

		// Fetch all unread message IDs from this peer.
		rows, err := database.QueryContext(ctx,
			`SELECT id, sender_user_id, client_msg_id FROM messages
			 WHERE recipient_user_id=? AND sender_user_id=? AND read=0`,
			myUserID, peerID)
		if err != nil {
			http.Error(w, "db error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		type msgRow struct {
			id          int64
			senderID    int64
			clientMsgID string
		}
		var msgs []msgRow
		for rows.Next() {
			var m msgRow
			_ = rows.Scan(&m.id, &m.senderID, &m.clientMsgID)
			msgs = append(msgs, m)
		}
		rows.Close()

		if len(msgs) == 0 {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		// Mark as read in DB and push read receipts to sender's devices.
		for _, m := range msgs {
			_, _ = database.ExecContext(ctx, `UPDATE messages SET read=1 WHERE id=?`, m.id)

			frame, err := ws.EncodeFrame(ws.OpMsgRead, ws.MsgRead{
				MsgID:       m.id,
				ClientMsgID: m.clientMsgID,
			})
			if err == nil {
				hub.SendToUser(m.senderID, frame)
			}
		}

		w.WriteHeader(http.StatusNoContent)
	}
}
