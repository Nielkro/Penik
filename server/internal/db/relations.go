package db

import "context"

// UsersShareChat reports whether two users have an existing mutual relationship:
// both users have exchanged messages in 1:1 chat (each sent at least one message),
// or they share membership in at least one common group.
//
// Presence and typing state leak metadata — when a person is at their device and
// when they are writing — so they must only be visible to peers with mutual
// interaction. Without this check a single unsolicited message allows tracking.
func (d *DB) UsersShareChat(ctx context.Context, a, b int64) (bool, error) {
	if a == 0 || b == 0 {
		return false, nil
	}
	if a == b {
		return true, nil
	}
	var related bool
	err := d.QueryRowContext(ctx, `
		SELECT (
			EXISTS(SELECT 1 FROM messages WHERE sender_user_id=?1 AND recipient_user_id=?2)
			AND EXISTS(SELECT 1 FROM messages WHERE sender_user_id=?2 AND recipient_user_id=?1)
		) OR EXISTS(
			SELECT 1 FROM group_members gm1
			  JOIN group_members gm2 ON gm2.group_id = gm1.group_id
			 WHERE gm1.user_id=?1 AND gm2.user_id=?2
		)`, a, b).Scan(&related)
	if err != nil {
		return false, err
	}
	return related, nil
}

// CanAccessAttachment reports whether viewerID is permitted to access an attachment
// uploaded by uploaderID. Access is allowed if:
// 1. viewerID is the uploader.
// 2. viewerID and uploaderID have a chat record in the chats table.
// 3. viewerID and uploaderID share membership in any common group.
func (d *DB) CanAccessAttachment(ctx context.Context, viewerID, uploaderID int64) (bool, error) {
	if viewerID == 0 {
		return false, nil
	}
	if uploaderID == 0 || viewerID == uploaderID {
		return true, nil
	}
	var allowed bool
	err := d.QueryRowContext(ctx, `
		SELECT (
			EXISTS(
				SELECT 1 FROM chats
				WHERE (user1_id=?1 AND user2_id=?2) OR (user1_id=?2 AND user2_id=?1)
			)
		) OR EXISTS(
			SELECT 1 FROM group_members gm1
			  JOIN group_members gm2 ON gm2.group_id = gm1.group_id
			 WHERE gm1.user_id=?1 AND gm2.user_id=?2
			   AND gm1.status IN ('active', 'pending')
			   AND gm2.status IN ('active', 'pending')
		)`, viewerID, uploaderID).Scan(&allowed)
	if err != nil {
		return false, err
	}
	return allowed, nil
}

// RelatedPeerDevices lists the device IDs of every 1:1 chat partner and group peer
// of userID, plus the user's own devices.
func (d *DB) RelatedPeerDevices(ctx context.Context, userID int64) ([]int64, error) {
	if userID <= 0 {
		return nil, nil
	}
	query := `
		SELECT DISTINCT d.id FROM devices d WHERE d.user_id IN (
			SELECT sender_user_id FROM messages WHERE recipient_user_id = ?
			UNION
			SELECT recipient_user_id FROM messages WHERE sender_user_id = ?
			UNION
			SELECT user_id FROM group_members WHERE group_id IN (
				SELECT group_id FROM group_members WHERE user_id = ?
			)
			UNION
			SELECT ?
		)
	`
	rows, err := d.QueryContext(ctx, query, userID, userID, userID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var deviceIDs []int64
	for rows.Next() {
		var devID int64
		if err := rows.Scan(&devID); err == nil {
			deviceIDs = append(deviceIDs, devID)
		}
	}
	return deviceIDs, rows.Err()
}

