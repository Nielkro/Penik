package ws

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"runtime/debug"
	"strings"
	"time"

	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/db"
	"modernc.org/sqlite"
	"nhooyr.io/websocket"
)

const (
	writeTimeout = 10 * time.Second
	readTimeout  = 70 * time.Second // slightly longer than ping interval
	pingInterval = 30 * time.Second
)

// Client represents a single connected WebSocket session.
type Client struct {
	hub      *Hub
	conn     *websocket.Conn
	userID   int64
	deviceID int64
	db       *db.DB
	send     chan []byte
	done     chan struct{}
}

// NewClient creates a new Client. Called from handlers package.
func NewClient(h *Hub, conn *websocket.Conn, userID, deviceID int64, database *db.DB) *Client {
	return newClient(h, conn, userID, deviceID, database)
}

func newClient(h *Hub, conn *websocket.Conn, userID, deviceID int64, database *db.DB) *Client {
	return &Client{
		hub:      h,
		conn:     conn,
		userID:   userID,
		deviceID: deviceID,
		db:       database,
		send:     make(chan []byte, 256),
		done:     make(chan struct{}),
	}
}

// Run starts the read and write pumps, registers with the hub, sends offline
// batch, and waits until the connection closes.
func (c *Client) Run(ctx context.Context) {
	c.conn.SetReadLimit(5 * 1024 * 1024) // 5 MB max WebSocket frame limit
	c.hub.register <- c
	defer func() {
		c.hub.unregister <- c
		c.conn.Close(websocket.StatusNormalClosure, "bye")
	}()

	// Send offline messages immediately.
	if err := c.sendOfflineBatch(ctx); err != nil {
		log.Printf("ws client %d/%d offline batch: %v", c.userID, c.deviceID, err)
	}

	// Send pending chat purges (delete-for-everyone) accumulated while offline.
	if err := c.sendPendingPurges(ctx); err != nil {
		log.Printf("ws client %d/%d pending purges: %v", c.userID, c.deviceID, err)
	}

	// Update last_seen on connect.
	_, _ = c.db.ExecContext(ctx,
		`UPDATE devices SET last_seen=? WHERE id=?`,
		time.Now().Unix(), c.deviceID)

	errCh := make(chan error, 2)

	go c.writePump(ctx, errCh)
	go c.readPump(ctx, errCh)

	select {
	case err := <-errCh:
		if err != nil {
			log.Printf("ws client %d/%d: %v", c.userID, c.deviceID, err)
		}
	case <-ctx.Done():
	}
	close(c.done)
}

func (c *Client) readPump(ctx context.Context, errCh chan<- error) {
	defer func() {
		if rec := recover(); rec != nil {
			log.Printf("ws readPump panic: %v\n%s", rec, debug.Stack())
			errCh <- fmt.Errorf("panic in readPump: %v", rec)
		}
	}()

	for {
		msgType, data, err := c.conn.Read(ctx)
		if err != nil {
			errCh <- err
			return
		}
		if msgType != websocket.MessageBinary {
			continue
		}
		if len(data) < 1 {
			continue
		}
		if err := c.handleFrame(ctx, data); err != nil {
			log.Printf("ws handle frame: %v", err)
		}
	}
}

func (c *Client) writePump(ctx context.Context, errCh chan<- error) {
	defer func() {
		if rec := recover(); rec != nil {
			log.Printf("ws writePump panic: %v\n%s", rec, debug.Stack())
			errCh <- fmt.Errorf("panic in writePump: %v", rec)
		}
	}()

	ticker := time.NewTicker(pingInterval)
	defer ticker.Stop()

	for {
		select {
		case <-c.done:
			return
		case msg, ok := <-c.send:
			if !ok {
				return
			}
			wCtx, cancel := context.WithTimeout(ctx, writeTimeout)
			err := c.conn.Write(wCtx, websocket.MessageBinary, msg)
			cancel()
			if err != nil {
				errCh <- err
				return
			}
		case <-ticker.C:
			frame := []byte{byte(OpPing)}
			wCtx, cancel := context.WithTimeout(ctx, writeTimeout)
			err := c.conn.Write(wCtx, websocket.MessageBinary, frame)
			cancel()
			if err != nil {
				errCh <- err
				return
			}
		}
	}
}

func (c *Client) handleFrame(ctx context.Context, data []byte) error {
	op := Opcode(data[0])
	payload := data[1:]

	switch op {
	case OpMsgSend:
		var msg MsgSend
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal MsgSend: %w", err)
		}
		return c.handleMsgSend(ctx, &msg)

	case OpMsgDelivered:
		var msg MsgDelivered
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal MsgDelivered: %w", err)
		}
		return c.handleMsgDelivered(ctx, &msg)

	case OpChatPurgeAck:
		var msg ChatPurgeAck
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal ChatPurgeAck: %w", err)
		}
		return c.handleChatPurgeAck(ctx, &msg)

	case OpKeyFetchReq:
		var req KeyFetchReq
		if err := msgpack.Unmarshal(payload, &req); err != nil {
			return fmt.Errorf("unmarshal KeyFetchReq: %w", err)
		}
		return c.handleKeyFetchReq(ctx, &req)

	case OpPong:
		// no-op
		return nil

	case OpPing:
		c.pushFrame(OpPong, nil)
		return nil

	default:
		return fmt.Errorf("unknown opcode 0x%02x", op)
	}
}

func (c *Client) handleMsgSend(ctx context.Context, msg *MsgSend) error {
	now := time.Now().Unix()

	// 1. Enforce size, count, and duplication security limits
	const maxCiphertextSize = 64 * 1024            // 64 KB
	const maxTotalCiphertextSize = 4 * 1024 * 1024 // 4 MB
	const maxTargetDevices = 100

	if len(msg.Devices) == 0 {
		return fmt.Errorf("no target devices specified")
	}
	if len(msg.Devices) > maxTargetDevices {
		return fmt.Errorf("too many target devices: %d (max %d)", len(msg.Devices), maxTargetDevices)
	}

	seenDevices := make(map[int64]struct{}, len(msg.Devices))
	var totalCiphertextSize int
	for _, devCipher := range msg.Devices {
		if _, exists := seenDevices[devCipher.DeviceID]; exists {
			return fmt.Errorf("duplicate target device: %d", devCipher.DeviceID)
		}
		seenDevices[devCipher.DeviceID] = struct{}{}

		if len(devCipher.CipherBytes) > maxCiphertextSize {
			return fmt.Errorf("ciphertext too large for device %d: %d bytes (max %d)", devCipher.DeviceID, len(devCipher.CipherBytes), maxCiphertextSize)
		}
		totalCiphertextSize += len(devCipher.CipherBytes)
		if totalCiphertextSize > maxTotalCiphertextSize {
			return fmt.Errorf("total ciphertext payload too large: %d bytes (max %d)", totalCiphertextSize, maxTotalCiphertextSize)
		}
	}

	senderUserID := c.userID
	recipientUserID := msg.ToUserID

	// 2. Gather all device IDs
	deviceIDs := make([]int64, len(msg.Devices))
	for i, dev := range msg.Devices {
		deviceIDs[i] = dev.DeviceID
	}

	// 3. Fetch owners of these devices in a single query
	placeholders := make([]string, len(deviceIDs))
	args := make([]interface{}, len(deviceIDs))
	for i, id := range deviceIDs {
		placeholders[i] = "?"
		args[i] = id
	}
	query := fmt.Sprintf("SELECT id, user_id FROM devices WHERE id IN (%s)", strings.Join(placeholders, ","))
	rows, err := c.db.QueryContext(ctx, query, args...)
	if err != nil {
		return fmt.Errorf("lookup devices: %w", err)
	}
	defer rows.Close()

	deviceOwnerMap := make(map[int64]int64)
	for rows.Next() {
		var id, ownerID int64
		if err := rows.Scan(&id, &ownerID); err != nil {
			return fmt.Errorf("scan device owner: %w", err)
		}
		deviceOwnerMap[id] = ownerID
	}

	// 4. Verify ownership before doing any writes (prevents unauthorized routing)
	for _, devCipher := range msg.Devices {
		devOwnerID, exists := deviceOwnerMap[devCipher.DeviceID]
		if !exists {
			return fmt.Errorf("ws msg send: device %d not found", devCipher.DeviceID)
		}
		// Security check: Only allow recipient devices or sender's own other devices
		if devOwnerID != recipientUserID && devOwnerID != senderUserID {
			return fmt.Errorf("ws msg send: security violation: device %d belongs to user %d", devCipher.DeviceID, devOwnerID)
		}
	}

	// 5. Begin database transaction
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin transaction: %w", err)
	}
	defer tx.Rollback()

	// 6. Atomic duplicate check
	if msg.MsgID != "" {
		var existingMsgID int64
		err := tx.QueryRowContext(ctx,
			`SELECT id FROM messages WHERE sender_device_id=? AND client_msg_id=? LIMIT 1`,
			c.deviceID, msg.MsgID).Scan(&existingMsgID)
		if err != nil && err != sql.ErrNoRows {
			return fmt.Errorf("lookup existing client_msg_id inside tx: %w", err)
		}
		if err == nil {
			tx.Rollback()
			ack := MsgAck{
				MsgID:       existingMsgID,
				ClientMsgID: msg.MsgID,
			}
			frame, err := encodeFrame(OpMsgAck, ack)
			if err == nil {
				select {
				case c.send <- frame:
				default:
				}
			}
			return nil
		}
	}

	// 7. Lookup or create chat
	var chatID int64
	u1, u2 := senderUserID, recipientUserID
	if u1 > u2 {
		u1, u2 = u2, u1
	}
	err = tx.QueryRowContext(ctx,
		`SELECT id FROM chats WHERE user1_id=? AND user2_id=?`, u1, u2).Scan(&chatID)
	if err == sql.ErrNoRows {
		res, err2 := tx.ExecContext(ctx,
			`INSERT INTO chats(user1_id,user2_id,created_at) VALUES(?,?,?)`, u1, u2, now)
		if err2 != nil {
			return fmt.Errorf("create chat: %w", err2)
		}
		chatID, _ = res.LastInsertId()
	} else if err != nil {
		return fmt.Errorf("lookup chat: %w", err)
	}

	// 8. Insert E2EE messages
	var lastInsertedID int64
	insertedMessages := make([]struct {
		deviceID    int64
		chatUserID  int64
		cipherBytes []byte
		msgID       int64
	}, 0, len(msg.Devices))

	for _, devCipher := range msg.Devices {
		var cMsgId interface{} = nil
		if msg.MsgID != "" {
			cMsgId = msg.MsgID
		}
		res, err := tx.ExecContext(ctx,
			`INSERT INTO messages(chat_id, sender_device_id, recipient_device_id, client_msg_id, ciphertext, timestamp, delivered)
			 VALUES(?, ?, ?, ?, ?, ?, 0)`,
			chatID, c.deviceID, devCipher.DeviceID, cMsgId, devCipher.CipherBytes, now)
		if err != nil {
			// Handle UNIQUE constraint error gracefully
			isUniqueConstraint := false
			if sqliteErr, ok := err.(*sqlite.Error); ok {
				isUniqueConstraint = (sqliteErr.Code()&0xff == 19) // SQLITE_CONSTRAINT
			} else if strings.Contains(err.Error(), "UNIQUE constraint failed") {
				isUniqueConstraint = true
			}

			if isUniqueConstraint && msg.MsgID != "" {
				tx.Rollback() // Rollback the failed transaction first!
				var existingMsgID int64
				errLookup := c.db.QueryRowContext(ctx,
					`SELECT id FROM messages WHERE sender_device_id=? AND client_msg_id=? LIMIT 1`,
					c.deviceID, msg.MsgID).Scan(&existingMsgID)
				if errLookup == nil {
					ack := MsgAck{
						MsgID:       existingMsgID,
						ClientMsgID: msg.MsgID,
					}
					frame, errFrame := encodeFrame(OpMsgAck, ack)
					if errFrame == nil {
						select {
						case c.send <- frame:
						default:
						}
					}
					return nil
				}
			}
			return fmt.Errorf("insert message for device %d: %w", devCipher.DeviceID, err)
		}
		id, err := res.LastInsertId()
		if err != nil {
			return fmt.Errorf("get last insert id: %w", err)
		}
		if lastInsertedID == 0 {
			lastInsertedID = id
		}

		devOwnerID := deviceOwnerMap[devCipher.DeviceID]
		var chatUserID int64
		if devOwnerID == senderUserID {
			chatUserID = recipientUserID
		} else {
			chatUserID = senderUserID
		}

		insertedMessages = append(insertedMessages, struct {
			deviceID    int64
			chatUserID  int64
			cipherBytes []byte
			msgID       int64
		}{
			deviceID:    devCipher.DeviceID,
			chatUserID:  chatUserID,
			cipherBytes: devCipher.CipherBytes,
			msgID:       id,
		})
	}

	// Commit transaction
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit transaction: %w", err)
	}

	// 9. Send real-time ws notifications to online devices
	for _, mInfo := range insertedMessages {
		recv := MsgRecv{
			FromUserID:   senderUserID,
			FromDeviceID: c.deviceID,
			ChatUserID:   mInfo.chatUserID,
			CipherBytes:  mInfo.cipherBytes,
			MsgID:        mInfo.msgID,
			TS:           now,
		}
		frame, err := encodeFrame(OpMsgRecv, recv)
		if err != nil {
			continue
		}
		c.hub.SendToDevice(mInfo.deviceID, frame)
	}

	// Ack back to sender with first message id.
	if lastInsertedID > 0 {
		ack := MsgAck{
			MsgID:       lastInsertedID,
			ClientMsgID: msg.MsgID,
		}
		frame, err := encodeFrame(OpMsgAck, ack)
		if err == nil {
			select {
			case c.send <- frame:
			default:
			}
		}
	}
	return nil
}

func (c *Client) handleMsgDelivered(ctx context.Context, msg *MsgDelivered) error {
	_, err := c.db.ExecContext(ctx,
		`UPDATE messages SET delivered=1 WHERE id=? AND recipient_device_id=?`,
		msg.MsgID, c.deviceID)
	return err
}

// validPubKey reports whether b is a well-formed curve25519 public key:
// 32 raw bytes, or 33 bytes with a 0x05 version prefix.
func validPubKey(b []byte) bool {
	return len(b) == 32 || (len(b) == 33 && b[0] == 0x05)
}

func (c *Client) handleKeyFetchReq(ctx context.Context, req *KeyFetchReq) error {
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	query := `SELECT d.id, d.registration_id, ik.ik_pub, ik.spk_pub, ik.spk_sig
		 FROM devices d
		 JOIN identity_keys ik ON ik.device_id = d.id
		 WHERE d.user_id=?`
	var args []interface{}
	args = append(args, req.UserID)
	if req.DeviceID > 0 {
		query += ` AND d.id=?`
		args = append(args, req.DeviceID)
	}

	rows, err := tx.QueryContext(ctx, query, args...)
	if err != nil {
		return err
	}
	defer rows.Close()

	var bundles []DeviceKeyBundle
	for rows.Next() {
		var b DeviceKeyBundle
		if err := rows.Scan(&b.DeviceID, &b.RegistrationID, &b.IKPub, &b.SPKPub, &b.SPKSig); err != nil {
			continue
		}
		// Skip devices with malformed key material (legacy/corrupt rows). A valid
		// curve25519 pubkey is 32 bytes or 33 with a 0x05 version prefix; an
		// Ed25519 signature is 64 bytes. Serving garbage here breaks the sender's
		// libsignal session build ("Invalid public key").
		if !validPubKey(b.IKPub) || !validPubKey(b.SPKPub) || len(b.SPKSig) != 64 {
			log.Printf("ws key fetch: skip device %d with malformed keys (ik=%d spk=%d sig=%d)",
				b.DeviceID, len(b.IKPub), len(b.SPKPub), len(b.SPKSig))
			continue
		}
		// Consume one OTK atomically using DELETE RETURNING inside the transaction.
		var opk []byte
		var keyID int64
		if !req.NoOTK {
			err := tx.QueryRowContext(ctx,
				`DELETE FROM one_time_keys
				 WHERE id = (
					 SELECT id
					 FROM one_time_keys
					 WHERE device_id=? AND used=0
					 ORDER BY id
					 LIMIT 1
				 ) RETURNING opk_pub, key_id`,
				b.DeviceID).Scan(&opk, &keyID)
			if err != nil && err != sql.ErrNoRows {
				log.Printf("ws key fetch: delete otk for device %d: %v", b.DeviceID, err)
			} else if err == nil && opk != nil {
				b.OPKPub = opk
				b.OPKID = keyID
			}
		}
		bundles = append(bundles, b)
	}

	resp := KeyFetchResp{Devices: bundles}
	frame, err := encodeFrame(OpKeyFetchResp, resp)
	if err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit tx: %w", err)
	}
	select {
	case c.send <- frame:
	default:
	}
	return nil
}

func (c *Client) sendOfflineBatch(ctx context.Context) error {
	rows, err := c.db.QueryContext(ctx,
		`SELECT m.id, d_sender.user_id, m.sender_device_id,
		        CASE WHEN c.user1_id = ? THEN c.user2_id ELSE c.user1_id END as chat_user_id,
		        m.ciphertext, m.timestamp
		 FROM messages m
		 JOIN chats c ON m.chat_id = c.id
		 JOIN devices d_sender ON d_sender.id = m.sender_device_id
		 JOIN devices d_recv   ON d_recv.id = m.recipient_device_id
		 WHERE m.recipient_device_id=? AND m.delivered=0 AND m.deleted_by_recipient=0 AND m.purge_pending=0
		 ORDER BY m.timestamp ASC`,
		c.userID, c.deviceID)
	if err != nil {
		return err
	}
	defer rows.Close()

	var currentBatch []MsgRecv
	var currentBatchBytes int

	sendBatch := func(msgs []MsgRecv) error {
		if len(msgs) == 0 {
			return nil
		}
		batch := OfflineBatch{Msgs: msgs}
		frame, err := encodeFrame(OpOfflineBatch, batch)
		if err != nil {
			return err
		}
		wCtx, cancel := context.WithTimeout(ctx, writeTimeout)
		defer cancel()
		return c.conn.Write(wCtx, websocket.MessageBinary, frame)
	}

	for rows.Next() {
		var m MsgRecv
		if err := rows.Scan(&m.MsgID, &m.FromUserID, &m.FromDeviceID, &m.ChatUserID, &m.CipherBytes, &m.TS); err != nil {
			continue
		}
		currentBatch = append(currentBatch, m)
		currentBatchBytes += len(m.CipherBytes)

		if len(currentBatch) >= 100 || currentBatchBytes >= 1*1024*1024 { // 100 messages or 1 MB of ciphertext
			if err := sendBatch(currentBatch); err != nil {
				return err
			}
			currentBatch = nil
			currentBatchBytes = 0
		}
	}

	if len(currentBatch) > 0 {
		if err := sendBatch(currentBatch); err != nil {
			return err
		}
	}

	return nil
}

// sendPendingPurges pushes a ChatPurge for every chat that another user
// deleted-for-everyone while this device was offline. The tombstoned rows
// stay in the DB until the client acks with OpChatPurgeAck.
func (c *Client) sendPendingPurges(ctx context.Context) error {
	rows, err := c.db.QueryContext(ctx,
		`SELECT DISTINCT
		        CASE WHEN ch.user1_id = ? THEN ch.user2_id ELSE ch.user1_id END as chat_user_id
		 FROM messages m
		 JOIN chats ch ON m.chat_id = ch.id
		 WHERE m.recipient_device_id = ? AND m.purge_pending = 1`,
		c.userID, c.deviceID)
	if err != nil {
		return err
	}
	defer rows.Close()

	var chatUserIDs []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			continue
		}
		chatUserIDs = append(chatUserIDs, id)
	}
	rows.Close()

	for _, id := range chatUserIDs {
		frame, err := encodeFrame(OpChatPurge, ChatPurge{ChatUserID: id})
		if err != nil {
			continue
		}
		wCtx, cancel := context.WithTimeout(ctx, writeTimeout)
		err = c.conn.Write(wCtx, websocket.MessageBinary, frame)
		cancel()
		if err != nil {
			return err
		}
	}
	return nil
}

// handleChatPurgeAck hard-deletes the tombstoned rows for a chat once the
// client confirms it wiped them locally.
func (c *Client) handleChatPurgeAck(ctx context.Context, msg *ChatPurgeAck) error {
	u1, u2 := c.userID, msg.ChatUserID
	if u1 > u2 {
		u1, u2 = u2, u1
	}
	_, err := c.db.ExecContext(ctx,
		`DELETE FROM messages
		 WHERE purge_pending = 1
		   AND recipient_device_id = ?
		   AND chat_id IN (SELECT id FROM chats WHERE user1_id = ? AND user2_id = ?)`,
		c.deviceID, u1, u2)
	return err
}

func (c *Client) pushFrame(op Opcode, v any) {
	var frame []byte
	if v == nil {
		frame = []byte{byte(op)}
	} else {
		var err error
		frame, err = encodeFrame(op, v)
		if err != nil {
			return
		}
	}
	select {
	case c.send <- frame:
	default:
	}
}

// encodeFrame prepends the opcode byte to a msgpack-encoded value.
func encodeFrame(op Opcode, v any) ([]byte, error) {
	payload, err := msgpack.Marshal(v)
	if err != nil {
		return nil, err
	}
	frame := make([]byte, 1+len(payload))
	frame[0] = byte(op)
	copy(frame[1:], payload)
	return frame, nil
}

// EncodeFrame is the exported form of encodeFrame for callers outside this
// package (e.g. HTTP handlers pushing frames through the hub).
func EncodeFrame(op Opcode, v any) ([]byte, error) {
	return encodeFrame(op, v)
}
