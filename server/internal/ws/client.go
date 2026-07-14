package ws

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"runtime/debug"
	"time"

	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/db"
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
	c.hub.register <- c
	defer func() {
		c.hub.unregister <- c
		c.conn.Close(websocket.StatusNormalClosure, "bye")
	}()

	// Send offline messages immediately.
	if err := c.sendOfflineBatch(ctx); err != nil {
		log.Printf("ws client %d/%d offline batch: %v", c.userID, c.deviceID, err)
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

	// Get or create chat between sender's user and recipient user.
	senderUserID := c.userID
	recipientUserID := msg.ToUserID

	var chatID int64
	u1, u2 := senderUserID, recipientUserID
	if u1 > u2 {
		u1, u2 = u2, u1
	}
	err := c.db.QueryRowContext(ctx,
		`SELECT id FROM chats WHERE user1_id=? AND user2_id=?`, u1, u2).Scan(&chatID)
	if err == sql.ErrNoRows {
		res, err2 := c.db.ExecContext(ctx,
			`INSERT INTO chats(user1_id,user2_id,created_at) VALUES(?,?,?)`, u1, u2, now)
		if err2 != nil {
			return fmt.Errorf("create chat: %w", err2)
		}
		chatID, _ = res.LastInsertId()
	} else if err != nil {
		return fmt.Errorf("lookup chat: %w", err)
	}

	// Get all devices of recipient.
	rows, err := c.db.QueryContext(ctx,
		`SELECT id FROM devices WHERE user_id=?`, recipientUserID)
	if err != nil {
		return fmt.Errorf("get recipient devices: %w", err)
	}
	var recipientDeviceIDs []int64
	for rows.Next() {
		var did int64
		if err := rows.Scan(&did); err == nil {
			recipientDeviceIDs = append(recipientDeviceIDs, did)
		}
	}
	rows.Close()

	var lastInsertedID int64

	for _, rdid := range recipientDeviceIDs {
		res, err := c.db.ExecContext(ctx,
			`INSERT INTO messages(chat_id,sender_device_id,recipient_device_id,ciphertext,timestamp,delivered)
			 VALUES(?,?,?,?,?,0)`,
			chatID, c.deviceID, rdid, msg.CipherBytes, now)
		if err != nil {
			log.Printf("store message for device %d: %v", rdid, err)
			continue
		}
		id, _ := res.LastInsertId()
		if lastInsertedID == 0 {
			lastInsertedID = id
		}

		recv := MsgRecv{
			FromUserID:   senderUserID,
			FromDeviceID: c.deviceID,
			CipherBytes:  msg.CipherBytes,
			MsgID:        id,
			TS:           now,
		}
		frame, err := encodeFrame(OpMsgRecv, recv)
		if err != nil {
			continue
		}
		c.hub.SendToDevice(rdid, frame)
	}

	// Ack back to sender with first message id.
	if lastInsertedID > 0 {
		ack := MsgAck{MsgID: lastInsertedID}
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

func (c *Client) handleKeyFetchReq(ctx context.Context, req *KeyFetchReq) error {
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	rows, err := tx.QueryContext(ctx,
		`SELECT d.id, ik.ik_pub, ik.spk_pub, ik.spk_sig
		 FROM devices d
		 JOIN identity_keys ik ON ik.device_id = d.id
		 WHERE d.user_id=?`, req.UserID)
	if err != nil {
		return err
	}
	defer rows.Close()

	var bundles []DeviceKeyBundle
	for rows.Next() {
		var b DeviceKeyBundle
		if err := rows.Scan(&b.DeviceID, &b.IKPub, &b.SPKPub, &b.SPKSig); err != nil {
			continue
		}
		// Consume one OTK atomically using DELETE RETURNING inside the transaction.
		var opk []byte
		err := tx.QueryRowContext(ctx,
			`DELETE FROM one_time_keys WHERE device_id=? AND used=0 RETURNING opk_pub`,
			b.DeviceID).Scan(&opk)
		if err != nil && err != sql.ErrNoRows {
			log.Printf("ws key fetch: delete otk for device %d: %v", b.DeviceID, err)
		} else if err == nil && opk != nil {
			b.OPKPub = opk
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
		`SELECT m.id, d.user_id, m.sender_device_id, m.ciphertext, m.timestamp
		 FROM messages m
		 JOIN devices d ON d.id = m.sender_device_id
		 WHERE m.recipient_device_id=? AND m.delivered=0
		 ORDER BY m.timestamp ASC`,
		c.deviceID)
	if err != nil {
		return err
	}
	defer rows.Close()

	var msgs []MsgRecv
	for rows.Next() {
		var m MsgRecv
		if err := rows.Scan(&m.MsgID, &m.FromUserID, &m.FromDeviceID, &m.CipherBytes, &m.TS); err != nil {
			continue
		}
		msgs = append(msgs, m)
	}
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
