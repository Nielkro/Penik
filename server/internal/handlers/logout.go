package handlers

import (
	"net/http"
	"time"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

// oneDaySeconds is the age threshold above which the requesting session is
// preserved by LogoutAll.
const oneDaySeconds int64 = 24 * 60 * 60

// Logout revokes the session token used for the current request, so a stolen
// token stops working immediately instead of waiting for the TTL to lapse.
func Logout(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := middleware.TokenFromCtx(r.Context())
		if token == "" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		if _, err := database.ExecContext(r.Context(),
			`DELETE FROM sessions WHERE token=?`, token); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// LogoutAll revokes the user's other sessions across all devices, keeping the
// requesting session. It is allowed only if the requesting session has been
// active for more than a day; a fresher token is rejected without changing
// anything, since it may have just been obtained by an attacker.
func LogoutAll(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		token := middleware.TokenFromCtx(r.Context())
		if userID == 0 || token == "" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		var createdAt int64
		err := database.QueryRowContext(r.Context(),
			`SELECT created_at FROM sessions WHERE token=?`, token).Scan(&createdAt)
		if err != nil {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		if time.Now().Unix()-createdAt <= oneDaySeconds {
			// Session too young to authorize a mass revocation; do nothing.
			http.Error(w, "session too recent to revoke others", http.StatusForbidden)
			return
		}

		if _, err := database.ExecContext(r.Context(),
			`DELETE FROM sessions WHERE user_id=? AND token<>?`, userID, token); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
