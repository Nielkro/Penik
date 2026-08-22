package handlers

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

// changePassword drives UpdatePassword with the given body and an authenticated
// context for token, returning the recorder.
func changePassword(t *testing.T, database *db.DB, userID int64, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPatch, "/api/v1/users/me/password", strings.NewReader(body))
	ctx := context.WithValue(req.Context(), middleware.ContextUserID, userID)
	ctx = context.WithValue(ctx, middleware.ContextToken, token)
	rr := httptest.NewRecorder()
	UpdatePassword(database)(rr, req.WithContext(ctx))
	return rr
}

// seedPassword replaces the user's stored hash so verifyPassword accepts plain.
func seedPassword(t *testing.T, database *db.DB, userID int64, plain string) {
	t.Helper()
	hash, err := hashPassword(plain)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := database.Exec(`UPDATE users SET password_hash=? WHERE id=?`, hash, userID); err != nil {
		t.Fatal(err)
	}
}

func TestUpdatePasswordRevokesOtherSessionsWhenRequested(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "pw-revoke.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "revoker")
	seedPassword(t, database, userID, "oldpass")
	seedSessionAge(t, database, userID, deviceID, "tok-current", oneDaySeconds+60)
	seedSession(t, database, userID, deviceID, "tok-other")

	rr := changePassword(t, database, userID, "tok-current",
		`{"old_password":"oldpass","new_password":"newpass","revoke_other_sessions":true}`)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d (%s)", rr.Code, rr.Body.String())
	}

	var resp updatePasswordResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if !resp.RevokedOtherSessions || resp.RevokeSkippedReason != "" {
		t.Fatalf("expected revocation to happen, got %+v", resp)
	}
	if sessionExists(t, database, "tok-other") {
		t.Error("other session must be revoked")
	}
	if !sessionExists(t, database, "tok-current") {
		t.Error("current session must survive")
	}
}

// The 24h quarantine also guards the password path: a token minted minutes ago
// must not be able to evict the user's real devices.
func TestUpdatePasswordRespectsRevokeQuarantine(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "pw-quarantine.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "fresh")
	seedPassword(t, database, userID, "oldpass")
	seedSessionAge(t, database, userID, deviceID, "tok-current", 60)
	seedSession(t, database, userID, deviceID, "tok-other")

	rr := changePassword(t, database, userID, "tok-current",
		`{"old_password":"oldpass","new_password":"newpass","revoke_other_sessions":true}`)
	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d (%s)", rr.Code, rr.Body.String())
	}

	var resp updatePasswordResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.RevokedOtherSessions || resp.RevokeSkippedReason != "session_too_recent" {
		t.Fatalf("expected quarantine to block revocation, got %+v", resp)
	}
	if !sessionExists(t, database, "tok-other") {
		t.Error("other session must survive the quarantine")
	}

	// The password itself must still have changed.
	var hash string
	if err := database.QueryRow(`SELECT password_hash FROM users WHERE id=?`, userID).Scan(&hash); err != nil {
		t.Fatal(err)
	}
	if !verifyPassword("newpass", hash) {
		t.Error("password change must apply even when revocation is skipped")
	}
}

func TestUpdatePasswordWithoutFlagKeepsSessions(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "pw-noflag.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "keeper")
	seedPassword(t, database, userID, "oldpass")
	seedSessionAge(t, database, userID, deviceID, "tok-current", oneDaySeconds+60)
	seedSession(t, database, userID, deviceID, "tok-other")

	rr := changePassword(t, database, userID, "tok-current",
		`{"old_password":"oldpass","new_password":"newpass"}`)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d (%s)", rr.Code, rr.Body.String())
	}
	if countSessions(t, database, userID) != 2 {
		t.Error("sessions must be untouched without the flag")
	}
}
