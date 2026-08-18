package handlers

import (
	"net/http"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

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

// LogoutAll revokes every session for the authenticated user across all
// devices. Used when a token may be compromised.
func LogoutAll(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		if _, err := database.ExecContext(r.Context(),
			`DELETE FROM sessions WHERE user_id=?`, userID); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
