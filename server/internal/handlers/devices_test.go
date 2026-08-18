package handlers

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

func listDevicesReq(userID, deviceID int64) *http.Request {
	req := httptest.NewRequest(http.MethodGet, "/api/v1/devices", nil)
	ctx := context.WithValue(req.Context(), middleware.ContextUserID, userID)
	ctx = context.WithValue(ctx, middleware.ContextDeviceID, deviceID)
	return req.WithContext(ctx)
}

func TestListDevicesReturnsOwnDevicesMarkingCurrent(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "devices.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "alice")
	// A second device for the same user.
	other, err := database.Exec(
		`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,?,?,?)`,
		userID, "dev2", 1, 1)
	if err != nil {
		t.Fatal(err)
	}
	otherID, _ := other.LastInsertId()
	seedSession(t, database, userID, deviceID, "tok-current")

	rr := httptest.NewRecorder()
	ListDevices(database)(rr, listDevicesReq(userID, deviceID))

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}
	var list []deviceResponse
	if err := json.NewDecoder(rr.Body).Decode(&list); err != nil {
		t.Fatal(err)
	}
	if len(list) != 2 {
		t.Fatalf("expected 2 devices, got %d", len(list))
	}
	var current, hasSess int
	for _, d := range list {
		if d.IsCurrent {
			current++
			if d.ID != deviceID {
				t.Errorf("wrong device flagged current: %d", d.ID)
			}
			if !d.HasSession {
				t.Error("current device should report an active session")
			}
		}
		if d.HasSession {
			hasSess++
		}
		if d.ID != deviceID && d.ID != otherID {
			t.Errorf("unexpected device id %d", d.ID)
		}
	}
	if current != 1 {
		t.Fatalf("expected exactly 1 current device, got %d", current)
	}
	if hasSess != 1 {
		t.Fatalf("expected exactly 1 device with a session, got %d", hasSess)
	}
}

func TestListDevicesDoesNotLeakOtherUsers(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "devices2.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "bob")
	otherUser, _ := setupUserDevice(t, database, "carol")

	rr := httptest.NewRecorder()
	ListDevices(database)(rr, listDevicesReq(userID, deviceID))

	var list []deviceResponse
	if err := json.NewDecoder(rr.Body).Decode(&list); err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 {
		t.Fatalf("expected only own device, got %d", len(list))
	}
	if list[0].ID != deviceID {
		t.Errorf("expected own device %d, got %d", deviceID, list[0].ID)
	}
	_ = otherUser
}

func TestListDevicesUnauthorized(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "devices3.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/devices", nil)
	rr := httptest.NewRecorder()
	ListDevices(database)(rr, req)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", rr.Code)
	}
}

func TestListDevicesReturnsPlatformAndLocation(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "devices4.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userID, deviceID := setupUserDevice(t, database, "dave")
	if _, err := database.Exec(
		`UPDATE devices SET platform=?, location=? WHERE id=?`,
		"Android 14", "Moscow", deviceID); err != nil {
		t.Fatal(err)
	}

	rr := httptest.NewRecorder()
	ListDevices(database)(rr, listDevicesReq(userID, deviceID))

	var list []deviceResponse
	if err := json.NewDecoder(rr.Body).Decode(&list); err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 {
		t.Fatalf("expected 1 device, got %d", len(list))
	}
	if list[0].Platform != "Android 14" || list[0].Location != "Moscow" {
		t.Errorf("expected platform/location echoed, got %q / %q", list[0].Platform, list[0].Location)
	}
}

func TestPlatformFromUserAgent(t *testing.T) {
	cases := map[string]string{
		"Mozilla/5.0 (X11; Linux x86_64) Chrome/120.0":       "Linux · Chrome",
		"Mozilla/5.0 (Windows NT 10.0) Gecko Firefox/121.0":  "Windows · Firefox",
		"Mozilla/5.0 (Linux; Android 14) Chrome/120":          "Android · Chrome",
		"":                                                    "",
	}
	for ua, want := range cases {
		if got := platformFromUserAgent(ua); got != want {
			t.Errorf("platformFromUserAgent(%q) = %q, want %q", ua, got, want)
		}
	}
}
