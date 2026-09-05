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
	if viewerID == 0 || uploaderID == 0 {
		return false, nil
	}
	if viewerID == uploaderID {
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

