package db

import "context"

// UsersShareChat reports whether two users have an existing relationship: a 1:1
// chat row, or membership in at least one common group.
//
// Presence and typing state leak metadata — when a person is at their device and
// when they are writing — so they must only be visible to peers the user already
// talks to. Without this check any account id is enough to track anyone.
func (d *DB) UsersShareChat(ctx context.Context, a, b int64) (bool, error) {
	if a == 0 || b == 0 {
		return false, nil
	}
	if a == b {
		return true, nil
	}
	var related bool
	err := d.QueryRowContext(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM chats
			 WHERE (user1_id=?1 AND user2_id=?2) OR (user1_id=?2 AND user2_id=?1)
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
