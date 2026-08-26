package handlers

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"strconv"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

type CallLogItem struct {
	ID           int64   `json:"id"`
	CallID       string  `json:"call_id"`
	CallerID     int64   `json:"caller_id"`
	CalleeID     int64   `json:"callee_id"`
	IsVideo      bool    `json:"is_video"`
	Status       string  `json:"status"` // "completed", "missed", "declined", "cancelled", "busy"
	StartedAt    int64   `json:"started_at"`
	AnsweredAt   *int64  `json:"answered_at,omitempty"`
	EndedAt      int64   `json:"ended_at"`
	Duration     int64   `json:"duration"` // in seconds
	PeerID       int64   `json:"peer_id"`
	PeerName     string  `json:"peer_name"`
	PeerNickname string  `json:"peer_nickname"`
	IsOutgoing   bool    `json:"is_outgoing"`
}

// ListCalls returns paginated call history for the authenticated user.
func ListCalls(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}

		limit := 50
		offset := 0
		if l := r.URL.Query().Get("limit"); l != "" {
			if parsed, err := strconv.Atoi(l); err == nil && parsed > 0 && parsed <= 100 {
				limit = parsed
			}
		}
		if o := r.URL.Query().Get("offset"); o != "" {
			if parsed, err := strconv.Atoi(o); err == nil && parsed >= 0 {
				offset = parsed
			}
		}

		rows, err := database.QueryContext(r.Context(), `
			SELECT
				c.id, c.call_id, c.caller_id, c.callee_id, c.is_video, c.status,
				c.started_at, c.answered_at, c.ended_at, c.duration,
				CASE WHEN c.caller_id = ? THEN u2.id ELSE u1.id END AS peer_id,
				CASE WHEN c.caller_id = ? THEN u2.name ELSE u1.name END AS peer_name,
				CASE WHEN c.caller_id = ? THEN u2.nickname ELSE u1.nickname END AS peer_nickname
			FROM calls c
			JOIN users u1 ON u1.id = c.caller_id
			JOIN users u2 ON u2.id = c.callee_id
			WHERE c.caller_id = ? OR c.callee_id = ?
			ORDER BY c.started_at DESC
			LIMIT ? OFFSET ?
		`, userID, userID, userID, userID, userID, limit, offset)
		if err != nil {
			http.Error(w, `{"error":"failed to query calls"}`, http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		items := make([]CallLogItem, 0)
		for rows.Next() {
			var item CallLogItem
			var answeredAt sql.NullInt64
			if err := rows.Scan(
				&item.ID, &item.CallID, &item.CallerID, &item.CalleeID, &item.IsVideo, &item.Status,
				&item.StartedAt, &answeredAt, &item.EndedAt, &item.Duration,
				&item.PeerID, &item.PeerName, &item.PeerNickname,
			); err != nil {
				continue
			}
			if answeredAt.Valid {
				item.AnsweredAt = &answeredAt.Int64
			}
			item.IsOutgoing = (item.CallerID == userID)
			items = append(items, item)
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(items)
	}
}

// ListPeerCalls returns call history with a specific user.
func ListPeerCalls(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}

		peerIDStr := r.PathValue("user_id")
		peerID, err := strconv.ParseInt(peerIDStr, 10, 64)
		if err != nil || peerID <= 0 {
			http.Error(w, `{"error":"invalid user id"}`, http.StatusBadRequest)
			return
		}

		limit := 50
		if l := r.URL.Query().Get("limit"); l != "" {
			if parsed, err := strconv.Atoi(l); err == nil && parsed > 0 && parsed <= 100 {
				limit = parsed
			}
		}

		rows, err := database.QueryContext(r.Context(), `
			SELECT
				c.id, c.call_id, c.caller_id, c.callee_id, c.is_video, c.status,
				c.started_at, c.answered_at, c.ended_at, c.duration,
				u.name, u.nickname
			FROM calls c
			JOIN users u ON u.id = ?
			WHERE (c.caller_id = ? AND c.callee_id = ?) OR (c.caller_id = ? AND c.callee_id = ?)
			ORDER BY c.started_at ASC
			LIMIT ?
		`, peerID, userID, peerID, peerID, userID, limit)
		if err != nil {
			http.Error(w, `{"error":"failed to query peer calls"}`, http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		items := make([]CallLogItem, 0)
		for rows.Next() {
			var item CallLogItem
			var answeredAt sql.NullInt64
			if err := rows.Scan(
				&item.ID, &item.CallID, &item.CallerID, &item.CalleeID, &item.IsVideo, &item.Status,
				&item.StartedAt, &answeredAt, &item.EndedAt, &item.Duration,
				&item.PeerName, &item.PeerNickname,
			); err != nil {
				continue
			}
			if answeredAt.Valid {
				item.AnsweredAt = &answeredAt.Int64
			}
			item.PeerID = peerID
			item.IsOutgoing = (item.CallerID == userID)
			items = append(items, item)
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(items)
	}
}
