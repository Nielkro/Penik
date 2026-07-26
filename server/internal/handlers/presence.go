package handlers

import (
	"context"

	"messenger/server/internal/db"
	"messenger/server/internal/ws"
)

// userPresence reports whether any of the user's devices currently has a live
// websocket connection, and the most recent last_seen timestamp across all of
// their devices (updated on connect/disconnect — see ws/client.go).
func userPresence(ctx context.Context, database *db.DB, hub *ws.Hub, userID int64) (online bool, lastSeen int64) {
	rows, err := database.QueryContext(ctx, `SELECT id, last_seen FROM devices WHERE user_id=?`, userID)
	if err != nil {
		return false, 0
	}
	defer rows.Close()

	for rows.Next() {
		var deviceID, ls int64
		if rows.Scan(&deviceID, &ls) != nil {
			continue
		}
		if ls > lastSeen {
			lastSeen = ls
		}
		if hub != nil && hub.IsOnline(deviceID) {
			online = true
		}
	}
	return online, lastSeen
}
