package ws

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/push"
	"nhooyr.io/websocket"
)

const (
	writeTimeout = 10 * time.Second
	readTimeout  = 70 * time.Second // slightly longer than ping interval
	pingInterval = 30 * time.Second

	msgSendRate         = 50.0
	msgSendBurst        = 100.0
	serviceFrameRate    = 100.0
	serviceFrameBurst   = 200.0
	rateViolationLimit  = 10
	rateViolationWindow = 2 * time.Second
)

var errFrameRateLimit = errors.New("websocket frame rate limit exceeded")

type frameRateCounter struct {
	tokens         float64
	lastRefill     time.Time
	violations     int
	firstViolation time.Time
}

// Client represents a single connected WebSocket session.
type Client struct {
	hub      *Hub
	conn     *websocket.Conn
	userID   int64
	deviceID int64
	db       *db.DB
	cfg      *config.Config
	send     chan []byte
	done     chan struct{}
	rateMu   sync.Mutex
	rate     map[Opcode]*frameRateCounter
}

// NewClient creates a new Client. Called from handlers package.
func NewClient(h *Hub, conn *websocket.Conn, userID, deviceID int64, database *db.DB, cfgs ...*config.Config) *Client {
	return newClient(h, conn, userID, deviceID, database, cfgs...)
}

func newClient(h *Hub, conn *websocket.Conn, userID, deviceID int64, database *db.DB, cfgs ...*config.Config) *Client {
	var cfg *config.Config
	if len(cfgs) > 0 {
		cfg = cfgs[0]
	}
	return &Client{
		hub:      h,
		conn:     conn,
		userID:   userID,
		deviceID: deviceID,
		db:       database,
		cfg:      cfg,
		send:     make(chan []byte, 256),
		done:     make(chan struct{}),
		rate:     make(map[Opcode]*frameRateCounter),
	}
}

// Run starts the read and write pumps, registers with the hub, sends offline
// batch, and waits until the connection closes.
func (c *Client) Run(ctx context.Context) {
	c.conn.SetReadLimit(5 * 1024 * 1024) // 5 MB max WebSocket frame limit
	c.hub.register <- c
	go c.broadcastPresenceConnect(context.Background())
	defer func() {
		c.hub.unregister <- c
		// A reconnect that landed before this socket finished tearing down now
		// owns the device; releasing its call here would kill the live session.
		if !c.hub.deviceReplaced(c) {
			CleanupDeviceCalls(c.hub, c.userID, c.deviceID)
		}
		c.conn.Close(websocket.StatusNormalClosure, "bye")
		// Best-effort: record when this device went offline, so last_seen
		// reflects "last active" rather than only "last connected."
		now := time.Now().Unix()
		_, _ = c.db.Exec(`UPDATE devices SET last_seen=? WHERE id=?`, now, c.deviceID)
		go c.broadcastPresenceDisconnect(context.Background(), now)
	}()

	// Send offline messages immediately.
	if err := c.sendOfflineBatch(ctx); err != nil {
		log.Printf("ws client %d/%d offline batch: %v", c.userID, c.deviceID, err)
	}

	// Send pending chat purges (delete-for-everyone) accumulated while offline.
	if err := c.sendPendingPurges(ctx); err != nil {
		log.Printf("ws client %d/%d pending purges: %v", c.userID, c.deviceID, err)
	}

	// Send undelivered group messages accumulated while offline.
	if err := c.sendGroupOfflineBatch(ctx); err != nil {
		log.Printf("ws client %d/%d group offline batch: %v", c.userID, c.deviceID, err)
	}

	// Send offline status updates for sent messages.
	if err := c.sendOfflineStatusBatch(ctx); err != nil {
		log.Printf("ws client %d/%d offline status batch: %v", c.userID, c.deviceID, err)
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
			if errors.Is(err, errFrameRateLimit) {
				errCh <- err
				return
			}
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
	rateResult := c.checkFrameRate(op)
	if rateResult == frameRateDrop {
		return nil
	}
	if rateResult == frameRateClose {
		return fmt.Errorf("%w: %s opcode 0x%02x", errFrameRateLimit, rateProfileName(op), op)
	}

	switch op {
	case OpMsgSend:
		var msg MsgSendEncrypted
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal MsgSendEncrypted: %w", err)
		}
		return c.handleMsgSend(ctx, &msg)

	case OpMsgDelivered:
		var msg MsgDelivered
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal MsgDelivered: %w", err)
		}
		return c.handleMsgDelivered(ctx, &msg)
	case OpMsgRead:
		var msg MsgRead
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal MsgRead: %w", err)
		}
		return c.handleMsgRead(ctx, &msg)

	case OpMsgDelete:
		var msg MsgDelete
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal MsgDelete: %w", err)
		}
		return c.handleMsgDelete(ctx, &msg)

	case OpChatPurgeAck:
		var msg ChatPurgeAck
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal ChatPurgeAck: %w", err)
		}
		return c.handleChatPurgeAck(ctx, &msg)

	case OpCallOffer:
		return c.handleCallOffer(payload)
	case OpCallAccept:
		return c.handleCallAccept(payload)
	case OpCallReject:
		return c.handleCallReject(payload)
	case OpCallEnd:
		return c.handleCallEnd(payload)

	case OpTyping:
		var req TypingNotify
		if err := msgpack.Unmarshal(payload, &req); err != nil {
			return fmt.Errorf("unmarshal TypingNotify: %w", err)
		}
		return c.handleTyping(ctx, &req)

	case OpKeyFetchReq:
		var req KeyFetchReq
		if err := msgpack.Unmarshal(payload, &req); err != nil {
			return fmt.Errorf("unmarshal KeyFetchReq: %w", err)
		}
		return c.handleKeyFetchReq(ctx, &req)

	case OpKeyPublish:
		var req KeyPublishReq
		if err := msgpack.Unmarshal(payload, &req); err != nil {
			return fmt.Errorf("unmarshal KeyPublishReq: %w", err)
		}
		return c.handleKeyPublish(ctx, &req)

	case OpKeyBundleReq:
		var req KeyBundleReq
		if err := msgpack.Unmarshal(payload, &req); err != nil {
			return fmt.Errorf("unmarshal KeyBundleReq: %w", err)
		}
		return c.handleKeyBundleReq(ctx, &req)

	case OpMsgRetryReq:
		var req MsgRetryReq
		if err := msgpack.Unmarshal(payload, &req); err != nil {
			return fmt.Errorf("unmarshal MsgRetryReq: %w", err)
		}
		return c.handleMsgRetryReq(ctx, &req)

	case OpMsgRetryResp:
		var req MsgRetryResp
		if err := msgpack.Unmarshal(payload, &req); err != nil {
			return fmt.Errorf("unmarshal MsgRetryResp: %w", err)
		}
		return c.handleMsgRetryResp(ctx, &req)

	case OpGroupMessageSend:
		var msg GroupMessageSend
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal GroupMessageSend: %w", err)
		}
		return c.handleGroupMessageSend(ctx, &msg)

	case OpGroupMessageDelivered:
		var msg GroupMessageDelivered
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal GroupMessageDelivered: %w", err)
		}
		return c.handleGroupMessageReceipt(ctx, msg.ID, false)

	case OpGroupMessageRead:
		var msg GroupMessageRead
		if err := msgpack.Unmarshal(payload, &msg); err != nil {
			return fmt.Errorf("unmarshal GroupMessageRead: %w", err)
		}
		return c.handleGroupMessageReceipt(ctx, msg.ID, true)

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

// checkFrameRate applies a separate token bucket to every incoming opcode.
// A few excess frames are dropped, but repeated excess in a short window closes the client.
type frameRateResult uint8

const (
	frameRateAllowed frameRateResult = iota
	frameRateDrop
	frameRateClose
)

func (c *Client) checkFrameRate(op Opcode) frameRateResult {
	rate, burst := serviceFrameRate, serviceFrameBurst
	if op == OpMsgSend {
		rate, burst = msgSendRate, msgSendBurst
	}

	now := time.Now()
	c.rateMu.Lock()
	defer c.rateMu.Unlock()

	counter, ok := c.rate[op]
	if !ok {
		counter = &frameRateCounter{tokens: burst, lastRefill: now}
		c.rate[op] = counter
	}

	elapsed := now.Sub(counter.lastRefill).Seconds()
	counter.tokens = minFloat(burst, counter.tokens+elapsed*rate)
	counter.lastRefill = now
	if counter.tokens >= 1 {
		counter.tokens--
		if counter.violations > 0 && now.Sub(counter.firstViolation) > rateViolationWindow {
			counter.violations = 0
			counter.firstViolation = time.Time{}
		}
		return frameRateAllowed
	}

	if counter.violations == 0 || now.Sub(counter.firstViolation) > rateViolationWindow {
		counter.violations = 1
		counter.firstViolation = now
	} else {
		counter.violations++
	}
	if counter.violations >= rateViolationLimit {
		return frameRateClose
	}
	return frameRateDrop
}

func rateProfileName(op Opcode) string {
	if op == OpMsgSend {
		return "message"
	}
	return "service"
}

func minFloat(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}

func (c *Client) handleMsgSend(ctx context.Context, msg *MsgSendEncrypted) error {
	now := time.Now().Unix()

	senderUserID := c.userID
	recipientUserID := msg.ToUserID

	var recipientExists int
	if err := c.db.QueryRowContext(ctx,
		`SELECT 1 FROM users WHERE id=?`,
		recipientUserID).Scan(&recipientExists); err != nil {
		if err == sql.ErrNoRows {
			return fmt.Errorf("recipient user not found")
		}
		return fmt.Errorf("lookup recipient: %w", err)
	}

	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin transaction: %w", err)
	}
	defer tx.Rollback()

	if msg.MsgID != "" {
		var existingMsgID int64
		err := tx.QueryRowContext(ctx,
			`SELECT id FROM messages WHERE sender_user_id=? AND client_msg_id=? AND recipient_device_id IS NOT NULL LIMIT 1`,
			senderUserID, msg.MsgID).Scan(&existingMsgID)
		if err != nil && err != sql.ErrNoRows {
			return fmt.Errorf("lookup existing client_msg_id inside tx: %w", err)
		}
		if err == nil {
			tx.Rollback()
			ack := MsgAck{
				MsgID:       existingMsgID,
				ClientMsgID: msg.MsgID,
			}
			c.pushFrame(OpMsgAck, ack)
			return nil
		}
	}

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

	var senderIKPub []byte
	err = tx.QueryRowContext(ctx, `SELECT x25519_pub FROM device_public_keys WHERE device_id=?`, c.deviceID).Scan(&senderIKPub)
	if err != nil && err != sql.ErrNoRows {
		return fmt.Errorf("lookup sender ik_pub: %w", err)
	}

	type pendingDelivery struct {
		deviceID int64
		msgRecv  MsgRecvEncrypted
	}
	var deliveries []pendingDelivery

	for _, dev := range msg.Devices {
		var ownerID int64
		err := tx.QueryRowContext(ctx, `SELECT user_id FROM devices WHERE id=?`, dev.DeviceID).Scan(&ownerID)
		if err == sql.ErrNoRows {
			continue
		} else if err != nil {
			return fmt.Errorf("lookup device owner: %w", err)
		}

		// The device list is attacker-controlled, so a device that belongs to
		// neither participant must be dropped: accepting it would file the
		// ciphertext under this conversation while delivering it to a third
		// party's device.
		var recipientID, chatUserID int64
		switch ownerID {
		case senderUserID:
			recipientID = senderUserID
			chatUserID = recipientUserID
		case recipientUserID:
			recipientID = recipientUserID
			chatUserID = senderUserID
		default:
			continue
		}

		res, err := tx.ExecContext(ctx,
			`INSERT INTO messages(
				chat_id, sender_user_id, recipient_user_id, client_msg_id, reply_to_msg_id,
				plaintext, ciphertext, encryption_salt, encryption_nonce,
				sender_device_id, recipient_device_id, prekey_id, timestamp, delivered
			 ) VALUES(?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, NULL, ?, 0)`,
			chatID, senderUserID, recipientID, msg.MsgID, msg.ReplyToMsgID,
			dev.Ciphertext, dev.Salt, dev.Nonce,
			c.deviceID, dev.DeviceID, now)
		if err != nil {
			return fmt.Errorf("insert message: %w", err)
		}
		messageID, err := res.LastInsertId()
		if err != nil {
			return fmt.Errorf("get message id: %w", err)
		}

		deliveries = append(deliveries, pendingDelivery{
			deviceID: dev.DeviceID,
			msgRecv: MsgRecvEncrypted{
				FromUserID:        senderUserID,
				FromDeviceID:      c.deviceID,
				RecipientDeviceID: dev.DeviceID,
				FromIdentityKey:   senderIKPub,
				ChatUserID:        chatUserID,
				MsgID:             messageID,
				ClientMsgID:       msg.MsgID,
				ReplyToMsgID:      msg.ReplyToMsgID,
				Ciphertext:        dev.Ciphertext,
				Salt:              dev.Salt,
				Nonce:             dev.Nonce,
				TS:                now,
			},
		})
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit transaction: %w", err)
	}

	var senderName string
	_ = c.db.QueryRowContext(ctx, "SELECT name FROM users WHERE id = ?", senderUserID).Scan(&senderName)
	if senderName == "" {
		senderName = "Пользователь"
	}

	for _, dev := range msg.Devices {
		var ownerID int64
		_ = c.db.QueryRowContext(ctx, "SELECT user_id FROM devices WHERE id=?", dev.DeviceID).Scan(&ownerID)
		if ownerID == senderUserID {
			continue
		}

		var fcmToken string
		_ = c.db.QueryRowContext(ctx, "SELECT fcm_token FROM devices WHERE id=?", dev.DeviceID).Scan(&fcmToken)
		if fcmToken == "" {
			continue
		}

		if c.hub.IsOnline(dev.DeviceID) {
			continue
		}

		// The push carries a pointer, not the payload. FCM caps a data message at
		// ~4 KB, so an ordinary attachment or a long message would be dropped by
		// Google without any error the user could see; the device fetches the
		// envelope over REST by msg_id instead.
		push.SendDevicePush(fcmToken, map[string]string{
			"type":           "direct",
			"chat_user_id":   fmt.Sprintf("%d", senderUserID),
			"sender_name":    senderName,
			"text":           "Новое сообщение",
			"timestamp":      fmt.Sprintf("%d", now*1000),
			"sender_user_id": fmt.Sprintf("%d", senderUserID),
			"msg_id": func() string {
				for _, d := range deliveries {
					if d.deviceID == dev.DeviceID {
						return fmt.Sprintf("%d", d.msgRecv.MsgID)
					}
				}
				return ""
			}(),
		})
	}

	for _, deliv := range deliveries {
		frame, err := encodeFrame(OpMsgRecv, deliv.msgRecv)
		if err == nil {
			c.hub.SendToDevice(deliv.deviceID, frame)
		}
	}

	var firstMsgID int64
	if len(deliveries) > 0 {
		firstMsgID = deliveries[0].msgRecv.MsgID
	}

	ack := MsgAck{
		MsgID:       firstMsgID,
		ClientMsgID: msg.MsgID,
	}
	c.pushFrame(OpMsgAck, ack)

	return nil
}

func (c *Client) handleTyping(ctx context.Context, req *TypingNotify) error {
	if req.ToUserID <= 0 {
		return nil
	}
	// Typing state is metadata about when someone is at their keyboard, so it may
	// only be pushed to a peer that already shares a chat or a group with the
	// sender. Otherwise any account id is enough to spam — or probe — a stranger.
	related, err := c.db.UsersShareChat(ctx, c.userID, req.ToUserID)
	if err != nil || !related {
		return nil
	}
	notify := TypingNotify{
		FromUserID: c.userID,
		IsTyping:   req.IsTyping,
	}
	data, err := msgpack.Marshal(&notify)
	if err != nil {
		return err
	}
	frame := append([]byte{byte(OpTyping)}, data...)
	c.hub.SendToUser(req.ToUserID, frame)
	return nil
}

func (c *Client) handleMsgDelivered(ctx context.Context, msg *MsgDelivered) error {
	now := time.Now().Unix()
	var senderUserID, recipientUserID int64
	var clientMsgID sql.NullString
	if err := c.db.QueryRowContext(ctx, `SELECT sender_user_id, recipient_user_id, client_msg_id FROM messages WHERE id=?`, msg.MsgID).Scan(&senderUserID, &recipientUserID, &clientMsgID); err != nil {
		return nil
	}
	res, err := c.db.ExecContext(ctx,
		`UPDATE messages SET delivered=1, delivered_at=? WHERE id=? AND recipient_user_id=?`,
		now, msg.MsgID, c.userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return nil
	}

	// Fan-out creates a separate row per device. Notify each sender device using its own row ID.
	if clientMsgID.Valid {
		rows, err := c.db.QueryContext(ctx, `
			SELECT m.recipient_device_id, m.id, m.sender_device_id, d.user_id 
			FROM messages m
			LEFT JOIN devices d ON m.recipient_device_id = d.id
			WHERE m.sender_user_id=? AND m.client_msg_id=?
			ORDER BY m.id ASC
		`, senderUserID, clientMsgID.String)
		if err == nil {
			defer rows.Close()
			var firstMsgID int64
			var senderDeviceID int64
			type targetDevice struct {
				devID int64
				msgID int64
			}
			var targets []targetDevice
			isFirst := true
			for rows.Next() {
				var recDevID, mID, sendDevID int64
				var ownerID sql.NullInt64
				if err := rows.Scan(&recDevID, &mID, &sendDevID, &ownerID); err == nil {
					if isFirst {
						firstMsgID = mID
						senderDeviceID = sendDevID
						isFirst = false
					}
					if ownerID.Valid && ownerID.Int64 == senderUserID {
						targets = append(targets, targetDevice{recDevID, mID})
					}
				}
			}
			if senderDeviceID != 0 {
				targets = append(targets, targetDevice{senderDeviceID, firstMsgID})
			}
			sentDevices := make(map[int64]bool)
			for _, t := range targets {
				if !sentDevices[t.devID] {
					sentDevices[t.devID] = true
					payload, err := msgpack.Marshal(MsgDelivered{MsgID: t.msgID, ClientMsgID: clientMsgID.String})
					if err == nil {
						c.hub.SendToDeviceFrame(t.devID, OpMsgDelivered, payload)
					}
				}
			}
		}
	} else {
		senderMsgID := msg.MsgID
		frame, err := encodeFrame(OpMsgDelivered, MsgDelivered{MsgID: senderMsgID, ClientMsgID: clientMsgID.String})
		if err == nil {
			c.sendToUserDevices(ctx, senderUserID, 0, frame)
		}
	}
	return nil
}

func (c *Client) handleMsgRead(ctx context.Context, msg *MsgRead) error {
	var senderUserID int64
	var clientMsgID sql.NullString
	if err := c.db.QueryRowContext(ctx, `SELECT sender_user_id, client_msg_id FROM messages WHERE id=? AND recipient_user_id=?`, msg.MsgID, c.userID).Scan(&senderUserID, &clientMsgID); err != nil {
		return nil
	}
	res, err := c.db.ExecContext(ctx, `UPDATE messages SET read=1 WHERE id=? AND recipient_user_id=? AND delivered=1`, msg.MsgID, c.userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return nil
	}
	// Fan-out creates a separate row per device. Notify each sender device using its own row ID.
	if clientMsgID.Valid {
		rows, err := c.db.QueryContext(ctx, `
			SELECT m.recipient_device_id, m.id, m.sender_device_id, d.user_id 
			FROM messages m
			LEFT JOIN devices d ON m.recipient_device_id = d.id
			WHERE m.sender_user_id=? AND m.client_msg_id=?
			ORDER BY m.id ASC
		`, senderUserID, clientMsgID.String)
		if err == nil {
			defer rows.Close()
			var firstMsgID int64
			var senderDeviceID int64
			type targetDevice struct {
				devID int64
				msgID int64
			}
			var targets []targetDevice
			isFirst := true
			for rows.Next() {
				var recDevID, mID, sendDevID int64
				var ownerID sql.NullInt64
				if err := rows.Scan(&recDevID, &mID, &sendDevID, &ownerID); err == nil {
					if isFirst {
						firstMsgID = mID
						senderDeviceID = sendDevID
						isFirst = false
					}
					if ownerID.Valid && ownerID.Int64 == senderUserID {
						targets = append(targets, targetDevice{recDevID, mID})
					}
				}
			}
			if senderDeviceID != 0 {
				targets = append(targets, targetDevice{senderDeviceID, firstMsgID})
			}
			sentDevices := make(map[int64]bool)
			for _, t := range targets {
				if !sentDevices[t.devID] {
					sentDevices[t.devID] = true
					payload, err := msgpack.Marshal(MsgRead{MsgID: t.msgID, ClientMsgID: clientMsgID.String})
					if err == nil {
						c.hub.SendToDeviceFrame(t.devID, OpMsgRead, payload)
					}
				}
			}
		}
	} else {
		senderMsgID := msg.MsgID
		frame, err := encodeFrame(OpMsgRead, MsgRead{MsgID: senderMsgID, ClientMsgID: clientMsgID.String})
		if err == nil {
			c.sendToUserDevices(ctx, senderUserID, 0, frame)
		}
	}
	return nil
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
		// session build ("Invalid public key").
		if !validPubKey(b.IKPub) || !validPubKey(b.SPKPub) || len(b.SPKSig) != 64 {
			log.Printf("ws key fetch: skip device %d with malformed keys (ik=%d spk=%d sig=%d)",
				b.DeviceID, len(b.IKPub), len(b.SPKPub), len(b.SPKSig))
			continue
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
		`SELECT m.id, m.sender_user_id, m.sender_device_id, m.recipient_device_id,
		        COALESCE(dpk.x25519_pub, ''),
		        CASE WHEN ch.user1_id = ? THEN ch.user2_id ELSE ch.user1_id END as chat_user_id,
		        m.ciphertext, m.encryption_salt, m.encryption_nonce, m.timestamp, m.reply_to_msg_id
		 FROM messages m
		 JOIN chats ch ON m.chat_id = ch.id
		 LEFT JOIN device_public_keys dpk ON m.sender_device_id = dpk.device_id
		 WHERE m.recipient_user_id=? AND m.recipient_device_id=? AND m.delivered=0 AND m.deleted_by_recipient=0 AND m.purge_pending=0
		 ORDER BY m.timestamp ASC`,
		c.userID, c.userID, c.deviceID)
	if err != nil {
		return err
	}
	defer rows.Close()

	var currentBatch []MsgRecvEncrypted
	var currentBatchBytes int

	sendBatch := func(msgs []MsgRecvEncrypted) error {
		if len(msgs) == 0 {
			return nil
		}
		batch := OfflineBatchEncrypted{Msgs: msgs}
		frame, err := encodeFrame(OpOfflineBatch, batch)
		if err != nil {
			return err
		}
		wCtx, cancel := context.WithTimeout(ctx, writeTimeout)
		defer cancel()
		return c.conn.Write(wCtx, websocket.MessageBinary, frame)
	}

	for rows.Next() {
		var m MsgRecvEncrypted
		var senderIK []byte
		if err := rows.Scan(
			&m.MsgID, &m.FromUserID, &m.FromDeviceID, &m.RecipientDeviceID, &senderIK,
			&m.ChatUserID, &m.Ciphertext, &m.Salt, &m.Nonce, &m.TS, &m.ReplyToMsgID,
		); err != nil {
			continue
		}
		m.FromIdentityKey = senderIK
		currentBatch = append(currentBatch, m)
		currentBatchBytes += len(m.Ciphertext)

		if len(currentBatch) >= 100 || currentBatchBytes >= 1*1024*1024 {
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

func (c *Client) sendOfflineStatusBatch(ctx context.Context) error {
	threshold := time.Now().Add(-7 * 24 * time.Hour).Unix()
	rows, err := c.db.QueryContext(ctx,
		`SELECT m.id, COALESCE(m.client_msg_id, ''), m.delivered, m.delivered_at, m.read
		 FROM messages m
		 WHERE m.sender_user_id = ? AND m.timestamp > ?`,
		c.userID, threshold)
	if err != nil {
		return err
	}
	defer rows.Close()

	var statuses []MsgStatusItem
	for rows.Next() {
		var item MsgStatusItem
		var deliveredVal int
		var readVal int
		var deliveredAtVal sql.NullInt64
		if err := rows.Scan(&item.MsgID, &item.ClientMsgID, &deliveredVal, &deliveredAtVal, &readVal); err != nil {
			continue
		}
		item.Delivered = deliveredVal == 1
		item.Read = readVal == 1
		if deliveredAtVal.Valid {
			val := deliveredAtVal.Int64
			item.DeliveredAt = &val
		}
		statuses = append(statuses, item)
	}

	if len(statuses) == 0 {
		return nil
	}

	batch := MsgStatusBatch{Statuses: statuses}
	frame, err := encodeFrame(OpMsgStatusBatch, batch)
	if err != nil {
		return err
	}

	wCtx, cancel := context.WithTimeout(ctx, writeTimeout)
	defer cancel()
	return c.conn.Write(wCtx, websocket.MessageBinary, frame)
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
		 WHERE m.purge_for_user_id = ? AND m.purge_pending = 1`,
		c.userID, c.userID)
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
		   AND purge_for_user_id = ?
		   AND chat_id IN (SELECT id FROM chats WHERE user1_id = ? AND user2_id = ?)`,
		c.userID, u1, u2)
	return err
}

// broadcastPresenceConnect notifies peers that this device just came online.
func (c *Client) broadcastPresenceConnect(ctx context.Context) {
	c.broadcastPresenceFrame(ctx, true, time.Now().Unix())
}

// broadcastPresenceDisconnect notifies peers that this device went offline,
// unless another of the user's devices is still connected.
func (c *Client) broadcastPresenceDisconnect(ctx context.Context, lastSeen int64) {
	online := false
	rows, err := c.db.QueryContext(ctx, `SELECT id FROM devices WHERE user_id=?`, c.userID)
	if err == nil {
		for rows.Next() {
			var devID int64
			if rows.Scan(&devID) == nil && devID != c.deviceID && c.hub.IsOnline(devID) {
				online = true
			}
		}
		rows.Close()
	}
	c.broadcastPresenceFrame(ctx, online, lastSeen)
}

func (c *Client) broadcastPresenceFrame(ctx context.Context, online bool, lastSeen int64) {
	deviceIDs := c.peerDevices(ctx)
	if len(deviceIDs) == 0 {
		return
	}
	payload, err := msgpack.Marshal(PresenceUpdate{UserID: c.userID, Online: online, LastSeen: lastSeen})
	if err != nil {
		return
	}
	c.hub.BroadcastPresence(deviceIDs, payload)
}

// peerDevices finds all devices belonging to users who share a 1:1 chat or a
// group with c.userID, so they can be notified of this user's presence changes.
func (c *Client) peerDevices(ctx context.Context) []int64 {
	query := `
		SELECT DISTINCT d.id FROM devices d WHERE d.user_id IN (
			SELECT sender_user_id FROM messages WHERE recipient_user_id = ?
			UNION
			SELECT recipient_user_id FROM messages WHERE sender_user_id = ?
			UNION
			SELECT user_id FROM group_members WHERE group_id IN (
				SELECT group_id FROM group_members WHERE user_id = ?
			)
		)`
	rows, err := c.db.QueryContext(ctx, query, c.userID, c.userID, c.userID)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var deviceIDs []int64
	for rows.Next() {
		var devID int64
		if rows.Scan(&devID) == nil {
			deviceIDs = append(deviceIDs, devID)
		}
	}
	return deviceIDs
}

func (c *Client) sendToUserDevices(ctx context.Context, userID, excludeDeviceID int64, frame []byte) {
	rows, err := c.db.QueryContext(ctx, `SELECT id FROM devices WHERE user_id=?`, userID)
	if err != nil {
		return
	}
	defer rows.Close()

	for rows.Next() {
		var deviceID int64
		if err := rows.Scan(&deviceID); err != nil || deviceID == excludeDeviceID {
			continue
		}
		c.hub.SendToDevice(deviceID, frame)
	}
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

func (c *Client) handleKeyPublish(ctx context.Context, req *KeyPublishReq) error {
	now := time.Now().Unix()
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	_, err = tx.ExecContext(ctx,
		`INSERT OR REPLACE INTO device_public_keys(device_id, x25519_pub, created_at, updated_at)
		 VALUES(?, ?, ?, ?)`,
		c.deviceID, req.X25519Pub, now, now)
	if err != nil {
		return fmt.Errorf("insert device_public_keys: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit tx: %w", err)
	}
	return nil
}

func (c *Client) handleKeyBundleReq(ctx context.Context, req *KeyBundleReq) error {
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	rows, err := tx.QueryContext(ctx, `SELECT id FROM devices WHERE user_id=?`, req.UserID)
	if err != nil {
		return fmt.Errorf("query devices: %w", err)
	}
	defer rows.Close()

	var devices []DeviceKeyBundle
	for rows.Next() {
		var deviceID int64
		if err := rows.Scan(&deviceID); err != nil {
			return err
		}

		var x25519Pub []byte
		err := tx.QueryRowContext(ctx, `SELECT x25519_pub FROM device_public_keys WHERE device_id=?`, deviceID).Scan(&x25519Pub)
		if err == sql.ErrNoRows {
			continue
		} else if err != nil {
			return err
		}

		var bundle DeviceKeyBundle
		bundle.DeviceID = deviceID
		bundle.IKPub = x25519Pub
		devices = append(devices, bundle)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit tx: %w", err)
	}

	c.pushFrame(OpKeyBundleResp, KeyFetchResp{Devices: devices})
	return nil
}

func (c *Client) handleMsgRetryReq(ctx context.Context, req *MsgRetryReq) error {
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	// 1. Verify message in database
	var senderUserID, recipientUserID, senderDeviceID int64
	err = tx.QueryRowContext(ctx,
		`SELECT sender_user_id, recipient_user_id, sender_device_id
		 FROM messages WHERE id=?`, req.MsgID).Scan(&senderUserID, &recipientUserID, &senderDeviceID)
	if err == sql.ErrNoRows {
		return fmt.Errorf("retry request: message %d not found", req.MsgID)
	} else if err != nil {
		return err
	}

	// 2. Ensure the caller (c.userID) is indeed the recipient of this message
	if recipientUserID != c.userID {
		return fmt.Errorf("unauthorized retry request for msg %d", req.MsgID)
	}

	// Ensure requester_device_id is always the caller's actual device (server-authoritative).
	// Do NOT trust the value sent by the client.
	req.RequesterDeviceID = c.deviceID

	// 3. Ensure target device is the sender's device
	if senderDeviceID != req.SenderDeviceID {
		return fmt.Errorf("mismatched sender device for msg %d", req.MsgID)
	}

	// 4. Forward the retry request to the sender's device Y
	payload, err := msgpack.Marshal(req)
	if err != nil {
		return err
	}
	c.hub.SendToDevice(senderDeviceID, append([]byte{byte(OpMsgRetryReq)}, payload...))
	return nil
}

func (c *Client) handleMsgRetryResp(ctx context.Context, req *MsgRetryResp) error {
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	// 1. Verify message in database
	var senderUserID, recipientUserID, senderDeviceID, recipientDeviceID int64
	var timestamp int64
	var clientMsgID string
	var replyToMsgID *string
	err = tx.QueryRowContext(ctx,
		`SELECT sender_user_id, recipient_user_id, sender_device_id, recipient_device_id, timestamp, client_msg_id, reply_to_msg_id
		 FROM messages WHERE id=?`, req.MsgID).Scan(&senderUserID, &recipientUserID, &senderDeviceID, &recipientDeviceID, &timestamp, &clientMsgID, &replyToMsgID)
	if err == sql.ErrNoRows {
		return fmt.Errorf("retry response: message %d not found", req.MsgID)
	} else if err != nil {
		return err
	}

	// 2. Ensure the caller (c.userID) is indeed the sender of this message
	if senderUserID != c.userID {
		return fmt.Errorf("unauthorized retry response for msg %d", req.MsgID)
	}

	// 3. Update the message ciphertext, salt, nonce, prekey_id, and reset delivered to 0
	_, err = tx.ExecContext(ctx,
		`UPDATE messages
		 SET ciphertext=?, encryption_salt=?, encryption_nonce=?, prekey_id=NULL, delivered=0, delivered_at=NULL
		 WHERE id=?`,
		req.Ciphertext, req.Salt, req.Nonce, req.MsgID)
	if err != nil {
		return err
	}

	// 4. Retrieve sender public identity key
	var senderIKPub []byte
	err = tx.QueryRowContext(ctx, `SELECT x25519_pub FROM device_public_keys WHERE device_id=?`, senderDeviceID).Scan(&senderIKPub)
	if err != nil {
		return fmt.Errorf("lookup sender ik: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return err
	}

	// 6. Deliver the newly encrypted message to the recipient's device
	inMsg := MsgRecvEncrypted{
		FromUserID:        senderUserID,
		FromDeviceID:      senderDeviceID,
		RecipientDeviceID: recipientDeviceID,
		FromIdentityKey:   senderIKPub,
		ChatUserID:        senderUserID,
		MsgID:             req.MsgID,
		ClientMsgID:       clientMsgID,
		ReplyToMsgID:      replyToMsgID,
		Ciphertext:        req.Ciphertext,
		Salt:              req.Salt,
		Nonce:             req.Nonce,
		TS:                timestamp,
	}

	payload, err := msgpack.Marshal(inMsg)
	if err != nil {
		return err
	}
	frame := append([]byte{byte(OpMsgRecv)}, payload...)
	c.hub.SendToDevice(recipientDeviceID, frame)
	return nil
}

func (c *Client) handleMsgDelete(ctx context.Context, req *MsgDelete) error {
	if req.MsgID == "" {
		return fmt.Errorf("msg_id required")
	}

	log.Printf("[ws] handleMsgDelete requested by userID=%d for msg_id=%s, deleteForEveryone=%v", c.userID, req.MsgID, req.DeleteForEveryone)

	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		log.Printf("[ws] handleMsgDelete BeginTx error: %v", err)
		return err
	}
	defer tx.Rollback()

	var msgID int64
	var senderUserID, recipientUserID int64
	var rawCiphertext []byte

	var clientMsgIdStr sql.NullString
	const selectMsg = `SELECT id, sender_user_id, recipient_user_id, ciphertext, client_msg_id FROM messages WHERE `

	// client_msg_id is TEXT and id is INTEGER. Matching both in one OR makes
	// SQLite coerce across types, so a numeric client_msg_id could resolve to an
	// unrelated row. Resolve by client id first, then fall back to the server id
	// only when the value is actually numeric.
	err = tx.QueryRowContext(ctx, selectMsg+`client_msg_id=?`,
		req.MsgID).Scan(&msgID, &senderUserID, &recipientUserID, &rawCiphertext, &clientMsgIdStr)
	if err == sql.ErrNoRows {
		if serverID, convErr := strconv.ParseInt(req.MsgID, 10, 64); convErr == nil {
			err = tx.QueryRowContext(ctx, selectMsg+`id=?`,
				serverID).Scan(&msgID, &senderUserID, &recipientUserID, &rawCiphertext, &clientMsgIdStr)
		}
	}

	if err == sql.ErrNoRows {
		log.Printf("[ws] handleMsgDelete: message %s NOT FOUND in DB", req.MsgID)
		return nil
	} else if err != nil {
		log.Printf("[ws] handleMsgDelete: DB query error: %v", err)
		return err
	}

	log.Printf("[ws] handleMsgDelete found msg: serverID=%d, sender=%d, recipient=%d", msgID, senderUserID, recipientUserID)

	// Ensure caller is either the sender or the recipient
	if c.userID != senderUserID && c.userID != recipientUserID {
		log.Printf("[ws] handleMsgDelete: unauthorized attempt by user %d", c.userID)
		return fmt.Errorf("unauthorized message deletion attempt")
	}

	if req.DeleteForEveryone {
		// Delete message from database
		_, err = tx.ExecContext(ctx, `DELETE FROM messages WHERE id=?`, msgID)
		if err != nil {
			return fmt.Errorf("delete message: %w", err)
		}

		if err := tx.Commit(); err != nil {
			return err
		}

		// Clean up file on VK CDN if message plaintext payload contained a VK attachment
		go func(rawBytes []byte) {
			fileURL := extractFileURLFromPayload(rawBytes)
			if fileURL != "" {
				deleteVKFileByURL(fileURL, c.cfg.VKBotToken)
			}
		}(rawCiphertext)

		// Notify active peer devices
		peerUserID := recipientUserID
		if c.userID == recipientUserID {
			peerUserID = senderUserID
		}

		clientMsgIDVal := req.MsgID
		if clientMsgIdStr.Valid && clientMsgIdStr.String != "" {
			clientMsgIDVal = clientMsgIdStr.String
		}

		log.Printf("[ws] Sending OpMsgDeleteNotify for msgID=%s to peerUserID=%d and userID=%d", clientMsgIDVal, peerUserID, c.userID)
		notifyPayload, err := msgpack.Marshal(MsgDeleteNotify{
			MsgID:             clientMsgIDVal,
			ChatID:            c.userID,
			DeleteForEveryone: true,
		})
		if err == nil {
			frame := append([]byte{byte(OpMsgDeleteNotify)}, notifyPayload...)
			c.hub.SendToUser(peerUserID, frame)
			c.hub.SendToUser(c.userID, frame)
		} else {
			log.Printf("[ws] Error marshalling MsgDeleteNotify: %v", err)
		}
		// Also send second notification frame with server numeric ID if different
		if fmt.Sprintf("%d", msgID) != clientMsgIDVal {
			log.Printf("[ws] Sending secondary OpMsgDeleteNotify for numeric msgID=%d to peerUserID=%d", msgID, peerUserID)
			numPayload, _ := msgpack.Marshal(MsgDeleteNotify{
				MsgID:             fmt.Sprintf("%d", msgID),
				ChatID:            c.userID,
				DeleteForEveryone: true,
			})
			numFrame := append([]byte{byte(OpMsgDeleteNotify)}, numPayload...)
			c.hub.SendToUser(peerUserID, numFrame)
			c.hub.SendToUser(c.userID, numFrame)
		}
	} else {
		// Soft/Local delete logic if needed
		if err := tx.Commit(); err != nil {
			return err
		}
	}

	return nil
}

func extractFileURLFromPayload(raw []byte) string {
	s := string(raw)
	idx := strings.Index(s, "https://")
	if idx == -1 {
		idx = strings.Index(s, "http://")
	}
	if idx == -1 {
		return ""
	}
	sub := s[idx:]
	end := strings.IndexAny(sub, `" \}'`)
	if end != -1 {
		sub = sub[:end]
	}
	return sub
}

func deleteVKFileByURL(docURL string, botToken string) {
	// Parse doc_id and owner_id from URL if present
	// E.g. https://psv4.userapi.com/s/v1/doc/... or https://vk.com/doc<owner_id>_<doc_id>
	// Or call VK docs.delete
}
