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
			EXISTS(SELECT 1 FROM messages WHERE (sender_user_id=?1 AND recipient_user_id=?2) OR (sender_user_id=?2 AND recipient_user_id=?1))
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
