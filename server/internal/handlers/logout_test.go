package handlers

import (
	"context"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

func seedSession(t *testing.T, database *db.DB, userID, deviceID int64, token string) {
	t.Helper()
	now := time.Now().Unix()
	_, err := database.Exec(
		`INSERT INTO sessions(token,user_id,device_id,created_at,expires_at) VALUES(?,?,?,?,?)`,
		token, userID, deviceID, now, now+3600,
	)
	if err != nil {
		t.Fatal(err)
	}
}

func setupUserDevice(t *testing.T, database *db.DB, nickname string) (int64, int64) {
	t.Helper()
	now := time.Now().Unix()
	u, err := database.Exec(
		`INSERT INTO users(name,nickname,password_hash,created_at) VALUES(?,?,?,?)`,
		nickname, nickname, "x", now,
	)
	if err != nil {
		t.Fatal(err)
	}
	userID, _ := u.LastInsertId()
	d, err := database.Exec(
		`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,?,?,?)`,
		userID, "dev", now, now,
	)
	if err != nil {
		t.Fatal(err)
	}
	deviceID, _ := d.LastInsertId()
	return userID, deviceID
}

func countSessions(t *testing.T, database *db.DB, userID int64) int {
	t.Helper()
	var n int
	if err := database.QueryRow(`SELECT COUNT(*) FROM sessions WHERE user_id=?`, userID).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestLogoutRevokesOnlyCurrentToken(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "logout.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "alice")
	seedSession(t, database, userID, deviceID, "tok-current")
	seedSession(t, database, userID, deviceID, "tok-other")

	req := httptest.NewRequest(http.MethodPost, "/api/v1/logout", nil)
	req = req.WithContext(context.WithValue(req.Context(), middleware.ContextToken, "tok-current"))
	rr := httptest.NewRecorder()
	Logout(database)(rr, req)

	if rr.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d", rr.Code)
	}
	if n := countSessions(t, database, userID); n != 1 {
		t.Fatalf("expected 1 remaining session, got %d", n)
	}
	// The other device's session must survive.
	var remaining string
	if err := database.QueryRow(`SELECT token FROM sessions WHERE user_id=?`, userID).Scan(&remaining); err != nil {
		t.Fatal(err)
	}
	if remaining != "tok-other" {
		t.Fatalf("wrong session revoked, remaining=%s", remaining)
	}
}

func TestLogoutWithoutTokenUnauthorized(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "logout2.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/logout", nil)
	rr := httptest.NewRecorder()
	Logout(database)(rr, req)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", rr.Code)
	}
}

func seedSessionAge(t *testing.T, database *db.DB, userID, deviceID int64, token string, ageSeconds int64) {
	t.Helper()
	now := time.Now().Unix()
	_, err := database.Exec(
		`INSERT INTO sessions(token,user_id,device_id,created_at,expires_at) VALUES(?,?,?,?,?)`,
		token, userID, deviceID, now-ageSeconds, now+3600,
	)
	if err != nil {
		t.Fatal(err)
	}
}

func sessionExists(t *testing.T, database *db.DB, token string) bool {
	t.Helper()
	var n int
	if err := database.QueryRow(`SELECT COUNT(*) FROM sessions WHERE token=?`, token).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n > 0
}

func logoutAllReq(userID int64, token string) *http.Request {
	req := httptest.NewRequest(http.MethodPost, "/api/v1/logout/all", nil)
	ctx := context.WithValue(req.Context(), middleware.ContextUserID, userID)
	ctx = context.WithValue(ctx, middleware.ContextToken, token)
	return req.WithContext(ctx)
}

func TestLogoutAllKeepsCurrentSessionOlderThanOneDay(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "logoutall_keep.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "bob")
	seedSessionAge(t, database, userID, deviceID, "current-old", oneDaySeconds+60)
	seedSession(t, database, userID, deviceID, "other")

	rr := httptest.NewRecorder()
	LogoutAll(database)(rr, logoutAllReq(userID, "current-old"))

	if rr.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d", rr.Code)
	}
	if !sessionExists(t, database, "current-old") {
		t.Error("current session older than a day must be preserved")
	}
	if sessionExists(t, database, "other") {
		t.Error("other session must be revoked")
	}
}

func TestLogoutAllRejectsFreshCurrentSession(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "logoutall_fresh.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "bob")
	seedSessionAge(t, database, userID, deviceID, "current-fresh", 60) // 1 minute old
	seedSession(t, database, userID, deviceID, "other")

	rr := httptest.NewRecorder()
	LogoutAll(database)(rr, logoutAllReq(userID, "current-fresh"))

	if rr.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for fresh session, got %d", rr.Code)
	}
	// Nothing must be revoked.
	if n := countSessions(t, database, userID); n != 2 {
		t.Fatalf("no sessions should be revoked, got %d remaining", n)
	}
}

func TestLogoutAllDoesNotTouchOtherUsers(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "logoutall.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "bob")
	otherUser, otherDevice := setupUserDevice(t, database, "carol")
	seedSessionAge(t, database, userID, deviceID, "b1", oneDaySeconds+60)
	seedSession(t, database, userID, deviceID, "b2")
	seedSession(t, database, otherUser, otherDevice, "c1")

	rr := httptest.NewRecorder()
	LogoutAll(database)(rr, logoutAllReq(userID, "b1"))

	if rr.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d", rr.Code)
	}
	if n := countSessions(t, database, otherUser); n != 1 {
		t.Fatalf("other user's session must survive, got %d", n)
	}
}
