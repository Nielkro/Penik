package handlers

import (
	"context"
	"database/sql"
	"errors"
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
		tokenHash := db.HashSessionToken(token)
		if _, err := database.ExecContext(r.Context(),
			`DELETE FROM sessions WHERE token=? OR token=?`, tokenHash, token); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// revokeOtherSessions deletes every session of the user except the one making
// the request. The requesting session must be older than oneDaySeconds: a token
// obtained minutes ago may belong to an attacker, and letting it lock the real
// owner out of every other device is worse than making them wait. Returns false
// when the quarantine blocks the revocation.
func revokeOtherSessions(ctx context.Context, database *db.DB, userID int64, token string) (bool, error) {
	tokenHash := db.HashSessionToken(token)
	var createdAt int64
	if err := database.QueryRowContext(ctx,
		`SELECT created_at FROM sessions WHERE token=? OR token=?`, tokenHash, token).Scan(&createdAt); err != nil {
		return false, err
	}
	if time.Now().Unix()-createdAt <= oneDaySeconds {
		return false, nil
	}
	if _, err := database.ExecContext(ctx,
		`DELETE FROM sessions WHERE user_id=? AND token<>? AND token<>?`, userID, tokenHash, token); err != nil {
		return false, err
	}
	return true, nil
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

		revoked, err := revokeOtherSessions(r.Context(), database, userID, token)
		if err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if !revoked {
			// Session too young to authorize a mass revocation; nothing changed.
			http.Error(w, "session too recent to revoke others", http.StatusForbidden)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
