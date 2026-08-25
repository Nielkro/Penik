package ws

import (
	"context"
	"database/sql"
	"fmt"
	"time"

	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/push"
)

const (
	maxGroupCiphertext = 1 * 1024 * 1024
	maxGroupSalt       = 64
	maxGroupNonce      = 64

	// created_at bounds (seconds). The client's timestamp is bound into the AAD,
	// so it cannot be rewritten by the server; these limits only reject clearly
	// bogus values (far-future clocks or ancient replays).
	maxGroupClockSkew    = 5 * 60           // 5 minutes into the future
	maxGroupTimestampAge = 30 * 24 * 60 * 60 // 30 days into the past
)

// handleGroupMessageSend persists an encrypted group message and fans it out to
// every active member device except the sender's own. Sender identity is taken
// from the authenticated connection, never from the payload.
func (c *Client) handleGroupMessageSend(ctx context.Context, msg *GroupMessageSend) error {
	if msg.MessageID == "" ||
		len(msg.Ciphertext) == 0 || len(msg.Ciphertext) > maxGroupCiphertext ||
		len(msg.Salt) > maxGroupSalt || len(msg.Nonce) > maxGroupNonce {
		return fmt.Errorf("group message: invalid field sizes")
	}
	// The client binds created_at into the message AAD, so the server must
	// persist and relay the client's timestamp verbatim — regenerating it here
	// would make the ciphertext undecryptable for every recipient. We only
	// sanity-check that it is present and within an acceptable skew window.
	if msg.CreatedAt <= 0 {
		return fmt.Errorf("group message: missing created_at")
	}
	nowUnix := time.Now().Unix()
	if len(msg.Ciphertext) == 0 || len(msg.Ciphertext) > 128*1024 {
		return fmt.Errorf("group message: invalid ciphertext size (max 128KB)")
	}
	if msg.CreatedAt > nowUnix+maxGroupClockSkew ||
		msg.CreatedAt < nowUnix-maxGroupTimestampAge {
		return fmt.Errorf("group message: created_at out of acceptable range")
	}

	// The sender's device must be an active member.
	var role, status string
	err := c.db.QueryRowContext(ctx,
		`SELECT gm.role, gm.status FROM group_members gm
		 JOIN groups g ON g.id = gm.group_id
		 WHERE gm.group_id=? AND gm.user_id=? AND g.deleted_at IS NULL`,
		msg.GroupID, c.userID).Scan(&role, &status)
	if err == sql.ErrNoRows || (err == nil && status != "active") {
		return fmt.Errorf("group message: sender not an active member")
	}
	if err != nil {
		return fmt.Errorf("group message: membership lookup: %w", err)
	}

	// The key version must exist for this group and not be revoked.
	var revoked sql.NullInt64
	err = c.db.QueryRowContext(ctx,
		`SELECT revoked_at FROM group_key_versions WHERE group_id=? AND key_version=?`,
		msg.GroupID, msg.KeyVersion).Scan(&revoked)
	if err == sql.ErrNoRows {
		return fmt.Errorf("group message: unknown key version")
	}
	if err != nil {
		return fmt.Errorf("group message: key version lookup: %w", err)
	}
	if revoked.Valid {
		return fmt.Errorf("group message: key version %d is revoked", msg.KeyVersion)
	}

	now := msg.CreatedAt
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	// Idempotency: a repeated (group_id, sender_user_id, message_id) returns the
	// prior ack. Scoped to the group so the same message_id may be reused across
	// different groups.
	var existingID int64
	err = tx.QueryRowContext(ctx,
		`SELECT id FROM group_messages WHERE group_id=? AND sender_user_id=? AND message_id=?`,
		msg.GroupID, c.userID, msg.MessageID).Scan(&existingID)
	if err == nil {
		tx.Rollback()
		c.pushFrame(OpGroupMessageAck, GroupMessageAck{GroupID: msg.GroupID, MessageID: msg.MessageID, ID: existingID})
		return nil
	}
	if err != sql.ErrNoRows {
		return fmt.Errorf("group message: idempotency check: %w", err)
	}

	res, err := tx.ExecContext(ctx,
		`INSERT INTO group_messages(group_id,message_id,reply_to_msg_id,sender_user_id,sender_device_id,key_version,ciphertext,encryption_salt,encryption_nonce,created_at)
		 VALUES(?,?,?,?,?,?,?,?,?,?)`,
		msg.GroupID, msg.MessageID, msg.ReplyToMsgID, c.userID, c.deviceID, msg.KeyVersion,
		msg.Ciphertext, msg.Salt, msg.Nonce, now)
	if err != nil {
		return fmt.Errorf("group message: insert: %w", err)
	}
	rowID, _ := res.LastInsertId()

	// Recipient set: every device of an active member, excluding the sender device.
	rows, err := tx.QueryContext(ctx,
		`SELECT d.id FROM devices d
		 JOIN group_members gm ON gm.user_id = d.user_id
		 WHERE gm.group_id=? AND gm.status='active' AND d.id != ?`,
		msg.GroupID, c.deviceID)
	if err != nil {
		return fmt.Errorf("group message: recipient lookup: %w", err)
	}
	var recipients []int64
	for rows.Next() {
		var did int64
		if err := rows.Scan(&did); err != nil {
			rows.Close()
			return err
		}
		recipients = append(recipients, did)
	}
	rows.Close()

	for _, did := range recipients {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO group_message_devices(message_id,device_id) VALUES(?,?)
			 ON CONFLICT(message_id,device_id) DO NOTHING`, rowID, did); err != nil {
			return fmt.Errorf("group message: track device: %w", err)
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}

	var senderName string
	_ = c.db.QueryRowContext(ctx, "SELECT name FROM users WHERE id = ?", c.userID).Scan(&senderName)
	if senderName == "" {
		senderName = "Участник"
	}
	var groupName string
	_ = c.db.QueryRowContext(ctx, "SELECT name FROM groups WHERE id = ?", msg.GroupID).Scan(&groupName)
	if groupName == "" {
		groupName = "Группа"
	}

	for _, did := range recipients {
		if c.hub.IsOnline(did) {
			continue
		}

		var fcmToken string
		_ = c.db.QueryRowContext(ctx, "SELECT fcm_token FROM devices WHERE id=?", did).Scan(&fcmToken)
		if fcmToken == "" {
			continue
		}

		// Pointer only — see the direct-message push: the ciphertext does not fit
		// in FCM's ~4 KB data budget, so the device pulls the row by row_id.
		push.SendDevicePush(fcmToken, map[string]string{
			"type":           "group",
			"group_id":       fmt.Sprintf("%d", msg.GroupID),
			"group_name":     groupName,
			"sender_user_id": fmt.Sprintf("%d", c.userID),
			"sender_name":    senderName,
			"text":           "Новое сообщение в группе",
			"row_id":         fmt.Sprintf("%d", rowID),
			"message_id":     msg.MessageID,
			"timestamp":      fmt.Sprintf("%d", now*1000),
		})
	}

	recv := GroupMessageRecv{
		GroupID:      msg.GroupID,
		ID:           rowID,
		MessageID:    msg.MessageID,
		ReplyToMsgID: msg.ReplyToMsgID,
		SenderUserID: c.userID,
		SenderDeviceID: c.deviceID,
		KeyVersion:   msg.KeyVersion,
		Ciphertext:   msg.Ciphertext,
		Salt:         msg.Salt,
		Nonce:        msg.Nonce,
		CreatedAt:    now,
	}
	if frame, err := encodeFrame(OpGroupMessageRecv, recv); err == nil {
		for _, did := range recipients {
			c.hub.SendToDevice(did, frame)
		}
	}

	c.pushFrame(OpGroupMessageAck, GroupMessageAck{GroupID: msg.GroupID, MessageID: msg.MessageID, ID: rowID})
	return nil
}

// handleGroupMessageReceipt marks a group message delivered or read for the
// caller's device. Read implies delivered.
func (c *Client) handleGroupMessageReceipt(ctx context.Context, messageID int64, read bool) error {
	now := time.Now().Unix()
	if read {
		_, err := c.db.ExecContext(ctx,
			`UPDATE group_message_devices SET read_at=?, delivered_at=COALESCE(delivered_at,?)
			 WHERE message_id=? AND device_id=?`, now, now, messageID, c.deviceID)
		return err
	}
	_, err := c.db.ExecContext(ctx,
		`UPDATE group_message_devices SET delivered_at=? WHERE message_id=? AND device_id=? AND delivered_at IS NULL`,
		now, messageID, c.deviceID)
	return err
}

// sendGroupOfflineBatch delivers group messages the device has not yet received.
func (c *Client) sendGroupOfflineBatch(ctx context.Context) error {
	rows, err := c.db.QueryContext(ctx,
		`SELECT gm.id, gm.group_id, gm.message_id, gm.reply_to_msg_id, gm.sender_user_id, gm.sender_device_id,
		        gm.key_version, gm.ciphertext, gm.encryption_salt, gm.encryption_nonce, gm.created_at
		 FROM group_message_devices gmd
		 JOIN group_messages gm ON gm.id = gmd.message_id
		 WHERE gmd.device_id=? AND gmd.delivered_at IS NULL
		 ORDER BY gm.id ASC`, c.deviceID)
	if err != nil {
		return err
	}
	defer rows.Close()

	for rows.Next() {
		var m GroupMessageRecv
		if err := rows.Scan(&m.ID, &m.GroupID, &m.MessageID, &m.ReplyToMsgID, &m.SenderUserID, &m.SenderDeviceID,
			&m.KeyVersion, &m.Ciphertext, &m.Salt, &m.Nonce, &m.CreatedAt); err != nil {
			continue
		}
		c.pushFrame(OpGroupMessageRecv, m)
	}
	return rows.Err()
}

func (c *Client) sendGroupOfflineEditBatch(ctx context.Context) error {
	threshold := time.Now().Add(-14 * 24 * time.Hour).Unix()
	rows, err := c.db.QueryContext(ctx,
		`SELECT gm.group_id, gm.message_id, gm.sender_user_id, gm.sender_device_id,
		        gm.key_version, gm.ciphertext, gm.encryption_salt, gm.encryption_nonce, gm.edited_at
		 FROM group_messages gm
		 JOIN group_members mem ON gm.group_id = mem.group_id AND mem.user_id = ?
		 WHERE gm.edited_at IS NOT NULL AND gm.edited_at > ?
		 ORDER BY gm.edited_at ASC`, c.userID, threshold)
	if err != nil {
		return err
	}
	defer rows.Close()

	for rows.Next() {
		var notify GroupMessageEditNotify
		if err := rows.Scan(
			&notify.GroupID, &notify.MessageID, &notify.SenderUserID, &notify.SenderDeviceID,
			&notify.KeyVersion, &notify.Ciphertext, &notify.Salt, &notify.Nonce, &notify.EditedAt,
		); err != nil {
			continue
		}
		c.pushFrame(OpGroupMessageEditNotify, notify)
	}
	return rows.Err()
}

func (c *Client) handleGroupMessageEdit(ctx context.Context, msg *GroupMessageEdit) error {
	if msg.MessageID == "" || len(msg.Ciphertext) == 0 || len(msg.Ciphertext) > maxGroupCiphertext ||
		len(msg.Salt) > maxGroupSalt || len(msg.Nonce) > maxGroupNonce {
		return fmt.Errorf("group message edit: invalid field sizes")
	}

	nowUnix := time.Now().Unix()
	editedAt := nowUnix
	if msg.EditedAt > 0 {
		editedAt = msg.EditedAt
		if editedAt > 1e11 {
			editedAt = editedAt / 1000
		}
	}

	// Verify caller is the author of the group message
	var msgID int64
	var keyVersion int64
	err := c.db.QueryRowContext(ctx,
		`SELECT id, key_version FROM group_messages 
		 WHERE group_id=? AND message_id=? AND sender_user_id=?`,
		msg.GroupID, msg.MessageID, c.userID).Scan(&msgID, &keyVersion)
	if err != nil {
		return fmt.Errorf("group message not found or unauthorized to edit")
	}

	_, err = c.db.ExecContext(ctx,
		`UPDATE group_messages 
		 SET ciphertext=?, encryption_salt=?, encryption_nonce=?, edited_at=? 
		 WHERE id=?`,
		msg.Ciphertext, msg.Salt, msg.Nonce, editedAt, msgID)
	if err != nil {
		return fmt.Errorf("update group message: %w", err)
	}

	notify := GroupMessageEditNotify{
		GroupID:        msg.GroupID,
		MessageID:      msg.MessageID,
		SenderUserID:   c.userID,
		SenderDeviceID: c.deviceID,
		KeyVersion:     msg.KeyVersion,
		Ciphertext:     msg.Ciphertext,
		Salt:           msg.Salt,
		Nonce:          msg.Nonce,
		EditedAt:       editedAt,
	}

	notifyBytes, err := msgpack.Marshal(notify)
	if err != nil {
		return fmt.Errorf("marshal group edit notify: %w", err)
	}

	frame := append([]byte{byte(OpGroupMessageEditNotify)}, notifyBytes...)

	// Fan out to all active members of the group
	rows, err := c.db.QueryContext(ctx,
		`SELECT user_id FROM group_members WHERE group_id=? AND status='active'`,
		msg.GroupID)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var memberUID int64
			if err := rows.Scan(&memberUID); err == nil {
				c.hub.SendToUser(memberUID, frame)
			}
		}
	}

	return nil
}
