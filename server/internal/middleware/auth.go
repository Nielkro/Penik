package middleware

import (
	"context"
	"net/http"
	"strings"
	"time"

	"messenger/server/internal/db"
)

type contextKey string

const (
	ContextUserID   contextKey = "userID"
	ContextDeviceID contextKey = "deviceID"
)

// Auth validates the Bearer token (or ?token= query param) and injects
// user_id and device_id into the request context.
func Auth(database *db.DB) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token := extractToken(r)
			if token == "" {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}

			var userID, deviceID int64
			var expiresAt int64
			err := database.QueryRowContext(r.Context(),
				`SELECT user_id, device_id, expires_at FROM sessions WHERE token=?`, token).
				Scan(&userID, &deviceID, &expiresAt)
			if err != nil || time.Now().Unix() > expiresAt {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}

			// Touch last_seen.
			_, _ = database.ExecContext(r.Context(),
				`UPDATE devices SET last_seen=? WHERE id=?`, time.Now().Unix(), deviceID)

			ctx := context.WithValue(r.Context(), ContextUserID, userID)
			ctx = context.WithValue(ctx, ContextDeviceID, deviceID)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func extractToken(r *http.Request) string {
	// Check Authorization: Bearer <token>
	if h := r.Header.Get("Authorization"); h != "" {
		if strings.HasPrefix(h, "Bearer ") {
			return strings.TrimPrefix(h, "Bearer ")
		}
	}
	// Check Sec-WebSocket-Protocol for WebSocket auth token
	if proto := r.Header.Get("Sec-WebSocket-Protocol"); proto != "" {
		parts := strings.Split(proto, ",")
		for i, p := range parts {
			p = strings.TrimSpace(p)
			if p == "access_token" && i+1 < len(parts) {
				return strings.TrimSpace(parts[i+1])
			}
		}
	}
	// Fall back to ?token= for WebSocket handshakes.
	return r.URL.Query().Get("token")
}

// UserIDFromCtx retrieves the authenticated user ID from the context.
func UserIDFromCtx(ctx context.Context) int64 {
	v, _ := ctx.Value(ContextUserID).(int64)
	return v
}

// DeviceIDFromCtx retrieves the authenticated device ID from the context.
func DeviceIDFromCtx(ctx context.Context) int64 {
	v, _ := ctx.Value(ContextDeviceID).(int64)
	return v
}
