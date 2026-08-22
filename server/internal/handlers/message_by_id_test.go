package handlers

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

// seedDirectMessage stores one fan-out copy addressed to a specific device pair.
func seedDirectMessage(t *testing.T, database *db.DB, chatID, senderUser, senderDev, recipUser, recipDev int64) int64 {
	t.Helper()
	res, err := database.Exec(
		`INSERT INTO messages(chat_id,sender_user_id,recipient_user_id,client_msg_id,ciphertext,
		 encryption_salt,encryption_nonce,sender_device_id,recipient_device_id,timestamp)
		 VALUES(?,?,?,?,?,?,?,?,?,?)`,
		chatID, senderUser, recipUser, "c-1", []byte("ct"), []byte("s"), []byte("n"),
		senderDev, recipDev, time.Now().Unix())
	if err != nil {
		t.Fatal(err)
	}
	id, _ := res.LastInsertId()
	return id
}

func authedRequest(target string, userID, deviceID int64) *http.Request {
	r := httptest.NewRequest(http.MethodGet, target, nil)
	ctx := context.WithValue(r.Context(), middleware.ContextUserID, userID)
	ctx = context.WithValue(ctx, middleware.ContextDeviceID, deviceID)
	return r.WithContext(ctx)
}

// The push notification carries only an id, so this endpoint is the resolution
// path — and it must stay scoped to the calling device: another device's copy is
// encrypted under a key this caller does not hold.
func TestGetMessageByIDIsDeviceScoped(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "byid.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	aliceID, aliceDev := setupUserDevice(t, database, "byidalice")
	bobID, bobDev := setupUserDevice(t, database, "byidbob")
	cr, err := database.Exec(`INSERT INTO chats(user1_id,user2_id,created_at) VALUES(?,?,?)`, aliceID, bobID, time.Now().Unix())
	if err != nil {
		t.Fatal(err)
	}
	chatID, _ := cr.LastInsertId()

	// A second device for Bob, which is not the addressee of the message.
	otherDevRes, err := database.Exec(`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,?,?,?)`,
		bobID, "bob-tablet", time.Now().Unix(), time.Now().Unix())
	if err != nil {
		t.Fatal(err)
	}
	bobOtherDev, _ := otherDevRes.LastInsertId()

	msgID := seedDirectMessage(t, database, chatID, aliceID, aliceDev, bobID, bobDev)

	mux := http.NewServeMux()
	mux.Handle("GET /api/v1/messages/{id}/envelope", GetMessageByID(database))

	// Addressed device: 200 with the envelope.
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, authedRequest("/api/v1/messages/"+itoa(msgID)+"/envelope", bobID, bobDev))
	if rec.Code != http.StatusOK {
		t.Fatalf("addressee: got %d, want 200 (%s)", rec.Code, rec.Body.String())
	}
	var got historyMessageResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.ID != msgID || got.ChatUserID != aliceID {
		t.Fatalf("unexpected envelope: id=%d chat_user_id=%d", got.ID, got.ChatUserID)
	}

	// Sender's own device also resolves its copy.
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, authedRequest("/api/v1/messages/"+itoa(msgID)+"/envelope", aliceID, aliceDev))
	if rec.Code != http.StatusOK {
		t.Fatalf("sender: got %d, want 200", rec.Code)
	}

	// Same user, wrong device — the copy is not theirs to read.
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, authedRequest("/api/v1/messages/"+itoa(msgID)+"/envelope", bobID, bobOtherDev))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("other device of same user: got %d, want 404", rec.Code)
	}

	// Unrelated user.
	carolID, carolDev := setupUserDevice(t, database, "byidcarol")
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, authedRequest("/api/v1/messages/"+itoa(msgID)+"/envelope", carolID, carolDev))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("stranger: got %d, want 404", rec.Code)
	}

	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, authedRequest("/api/v1/messages/0/envelope", bobID, bobDev))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("id=0: got %d, want 400", rec.Code)
	}
}

// Presence is metadata: it may only be disclosed to a peer that already shares a
// chat or a group, otherwise a guessable id turns GET /users/{id} into a tracker.
func TestGetUserHidesPresenceFromStrangers(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "presacl.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	aliceID, aliceDev := setupUserDevice(t, database, "presalice")
	bobID, _ := setupUserDevice(t, database, "presbob")
	if _, err := database.Exec(`UPDATE devices SET last_seen=? WHERE user_id=?`, time.Now().Unix(), bobID); err != nil {
		t.Fatal(err)
	}

	mux := http.NewServeMux()
	mux.Handle("GET /api/v1/users/{id}", GetUser(database, ws.NewHub()))

	lastSeenFor := func(viewer, viewerDev int64) float64 {
		t.Helper()
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, authedRequest("/api/v1/users/"+itoa(bobID), viewer, viewerDev))
		if rec.Code != http.StatusOK {
			t.Fatalf("got %d, want 200", rec.Code)
		}
		var body map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		v, _ := body["last_seen"].(float64)
		return v
	}

	if v := lastSeenFor(aliceID, aliceDev); v != 0 {
		t.Fatalf("stranger saw last_seen=%v, want 0", v)
	}

	if _, err := database.Exec(`INSERT INTO chats(user1_id,user2_id,created_at) VALUES(?,?,?)`, aliceID, bobID, time.Now().Unix()); err != nil {
		t.Fatal(err)
	}
	if v := lastSeenFor(aliceID, aliceDev); v == 0 {
		t.Fatal("chat partner should see last_seen")
	}
}

// Device metadata is rendered in the device list of the user's other clients, so
// control characters must be stripped and the length capped.
func TestSanitizeDeviceField(t *testing.T) {
	cases := []struct{ in, want string }{
		{"  Pixel 8  ", "Pixel 8"},
		{"bad\x00name", "badname"},
		{"line\nbreak", "line break"},
		{"\x1b[31mred", "[31mred"},
		{"", ""},
		{"\x00\x01", ""},
	}
	for _, c := range cases {
		if got := sanitizeDeviceField(c.in, maxDeviceFieldRunes); got != c.want {
			t.Errorf("sanitizeDeviceField(%q) = %q, want %q", c.in, got, c.want)
		}
	}

	long := strings.Repeat("я", 200)
	got := sanitizeDeviceField(long, maxDeviceFieldRunes)
	if n := len([]rune(got)); n != maxDeviceFieldRunes {
		t.Errorf("cap: got %d runes, want %d", n, maxDeviceFieldRunes)
	}
}
