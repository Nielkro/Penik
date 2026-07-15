package handlers

import (
	"net/http"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
	"nhooyr.io/websocket"
)

// WebSocketHandler handles WS /api/v1/ws?token=...
// Auth is validated by the middleware before this handler runs.
func WebSocketHandler(hub *ws.Hub, database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		deviceID := middleware.DeviceIDFromCtx(r.Context())

		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
			InsecureSkipVerify: true, // auth/CORS already enforced by middleware
			Subprotocols:       []string{"access_token"},
		})
		if err != nil {
			// websocket.Accept already wrote the HTTP error response.
			return
		}

		client := ws.NewClient(hub, conn, userID, deviceID, database)
		client.Run(r.Context())
	}
}
