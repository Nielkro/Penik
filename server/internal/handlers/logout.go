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

// LogoutAll revokes the user's sessions across all devices. The requesting
// session is preserved only if it has been active for more than a day, so a
// freshly minted (potentially attacker-obtained) token is revoked along with
// the rest. Older, trusted sessions stay signed in.
func LogoutAll(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		token := middleware.TokenFromCtx(r.Context())

		// Decide whether to keep the requesting session: only if it exists and
		// was created more than one day ago.
		keepCurrent := false
		if token != "" {
			var createdAt int64
			err := database.QueryRowContext(r.Context(),
				`SELECT created_at FROM sessions WHERE token=?`, token).Scan(&createdAt)
			if err == nil && time.Now().Unix()-createdAt > oneDaySeconds {
				keepCurrent = true
			}
		}

		var execErr error
		if keepCurrent {
			_, execErr = database.ExecContext(r.Context(),
				`DELETE FROM sessions WHERE user_id=? AND token<>?`, userID, token)
		} else {
			_, execErr = database.ExecContext(r.Context(),
				`DELETE FROM sessions WHERE user_id=?`, userID)
		}
		if execErr != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
