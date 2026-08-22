package handlers

import (
	"net/http"
	"strings"
	"time"

	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
	"nhooyr.io/websocket"
)

// WebSocketHandler handles WS /api/v1/ws
// Auth is validated by the middleware before this handler runs.
func WebSocketHandler(hub *ws.Hub, database *db.DB, cfg *config.Config) http.HandlerFunc {
	// Build origin pattern list for WebSocket upgrade check. ALLOWED_ORIGINS is
	// validated at startup, so the list is never empty in practice; there is no
	// wildcard branch, because InsecureSkipVerify here would let any page on the
	// internet open an authenticated socket.
	var originPatterns []string
	for _, o := range strings.Split(cfg.AllowedOrigins, ",") {
		if s := strings.TrimSpace(o); s != "" && s != "*" {
			originPatterns = append(originPatterns, s)
		}
	}

	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		deviceID := middleware.DeviceIDFromCtx(r.Context())

		opts := &websocket.AcceptOptions{
			Subprotocols:   []string{"access_token"},
			OriginPatterns: originPatterns,
		}

		// The server sets a global write deadline so a stuck HTTP response cannot
		// pin a connection forever. A WebSocket outlives any such deadline, so
		// clear it for this connection before taking over the socket.
		rc := http.NewResponseController(w)
		_ = rc.SetWriteDeadline(time.Time{})
		_ = rc.SetReadDeadline(time.Time{})

		conn, err := websocket.Accept(w, r, opts)
		if err != nil {
			// websocket.Accept already wrote the HTTP error response.
			return
		}

		client := ws.NewClient(hub, conn, userID, deviceID, database, cfg)
		if loc := resolveLocation("", r); loc != "" {
			_, _ = database.ExecContext(r.Context(),
				`UPDATE devices SET location=? WHERE id=?`,
				loc, deviceID)
		}
		client.Run(r.Context())
	}
}
