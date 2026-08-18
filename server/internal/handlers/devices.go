package handlers

import (
	"encoding/json"
	"net/http"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

type deviceResponse struct {
	ID          int64  `json:"id"`
	DeviceName  string `json:"device_name"`
	Platform    string `json:"platform"`
	Location    string `json:"location"`
	CreatedAt   int64  `json:"created_at"`
	LastSeen    int64  `json:"last_seen"`
	IsCurrent   bool   `json:"is_current"`
	HasSession  bool   `json:"has_session"`
	SessionsCnt int    `json:"sessions_count"`
}

// ListDevices returns every device belonging to the authenticated user, marking
// which one issued the current request so the client can show "this device".
func ListDevices(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		currentDeviceID := middleware.DeviceIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		rows, err := database.QueryContext(r.Context(),
			`SELECT d.id, d.device_name, d.platform, d.location, d.created_at, d.last_seen,
			        COUNT(s.token) AS sessions_count
			   FROM devices d
			   LEFT JOIN sessions s ON s.device_id = d.id
			  WHERE d.user_id = ?
			  GROUP BY d.id
			  ORDER BY d.last_seen DESC`, userID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		list := make([]deviceResponse, 0)
		for rows.Next() {
			var d deviceResponse
			if err := rows.Scan(&d.ID, &d.DeviceName, &d.Platform, &d.Location, &d.CreatedAt, &d.LastSeen, &d.SessionsCnt); err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			d.IsCurrent = d.ID == currentDeviceID
			d.HasSession = d.SessionsCnt > 0
			list = append(list, d)
		}
		if err := rows.Err(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(list)
	}
}
