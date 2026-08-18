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

func TestLogoutAllRevokesEverySession(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "logoutall.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "bob")
	otherUser, otherDevice := setupUserDevice(t, database, "carol")
	seedSession(t, database, userID, deviceID, "b1")
	seedSession(t, database, userID, deviceID, "b2")
	seedSession(t, database, otherUser, otherDevice, "c1")

	req := httptest.NewRequest(http.MethodPost, "/api/v1/logout/all", nil)
	req = req.WithContext(context.WithValue(req.Context(), middleware.ContextUserID, userID))
	rr := httptest.NewRecorder()
	LogoutAll(database)(rr, req)

	if rr.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d", rr.Code)
	}
	if n := countSessions(t, database, userID); n != 0 {
		t.Fatalf("expected 0 sessions for user, got %d", n)
	}
	// Other users are unaffected.
	if n := countSessions(t, database, otherUser); n != 1 {
		t.Fatalf("other user's session must survive, got %d", n)
	}
}
