package handlers

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"messenger/server/internal/config"
	"messenger/server/internal/db"
)

func TestLoginPreservesOfflineMessagesForExistingDevice(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "login.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	passwordHash, err := hashPassword("secret123")
	if err != nil {
		t.Fatal(err)
	}

	now := time.Now().Unix()
	userResult, err := database.Exec(
		`INSERT INTO users(name,nickname,password_hash,created_at) VALUES(?,?,?,?)`,
		"Recipient", "recipient", passwordHash, now,
	)
	if err != nil {
		t.Fatal(err)
	}
	userID, _ := userResult.LastInsertId()

	senderResult, err := database.Exec(
		`INSERT INTO users(name,nickname,password_hash,created_at) VALUES(?,?,?,?)`,
		"Sender", "sender", passwordHash, now,
	)
	if err != nil {
		t.Fatal(err)
	}
	senderID, _ := senderResult.LastInsertId()

	recipientDeviceResult, err := database.Exec(
		`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,?,?,?)`,
		userID, "Web Client TEST", now, now,
	)
	if err != nil {
		t.Fatal(err)
	}
	recipientDeviceID, _ := recipientDeviceResult.LastInsertId()

	chatResult, err := database.Exec(
		`INSERT INTO chats(user1_id,user2_id,created_at) VALUES(?,?,?)`,
		userID, senderID, now,
	)
	if err != nil {
		t.Fatal(err)
	}
	chatID, _ := chatResult.LastInsertId()

	messageResult, err := database.Exec(
		`INSERT INTO messages(
			chat_id, sender_user_id, recipient_user_id, plaintext, timestamp, delivered
		) VALUES(?,?,?,?,?,0)`,
		chatID, senderID, userID, "offline", now,
	)
	if err != nil {
		t.Fatal(err)
	}
	messageID, _ := messageResult.LastInsertId()

	body, err := json.Marshal(loginRequest{
		Nickname:   "recipient",
		Password:   "secret123",
		DeviceName: "Web Client TEST",
	})
	if err != nil {
		t.Fatal(err)
	}

	request := httptest.NewRequest(http.MethodPost, "/api/v1/login", bytes.NewReader(body))
	response := httptest.NewRecorder()
	Login(database, &config.Config{SessionTTL: time.Hour})(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("login status = %d, body = %q", response.Code, response.Body.String())
	}

	var login loginResponse
	if err := json.NewDecoder(response.Body).Decode(&login); err != nil {
		t.Fatal(err)
	}
	if login.DeviceID != recipientDeviceID {
		t.Fatalf("device id = %d, want existing id %d", login.DeviceID, recipientDeviceID)
	}

	var storedRecipientUserID int64
	if err := database.QueryRow(
		`SELECT recipient_user_id FROM messages WHERE id=?`,
		messageID,
	).Scan(&storedRecipientUserID); err != nil {
		t.Fatalf("offline message was deleted during login: %v", err)
	}
	if storedRecipientUserID != userID {
		t.Fatalf(
			"message recipient user = %d, want %d",
			storedRecipientUserID,
			userID,
		)
	}
}

// TestLoginMatchesDeviceByIdentityKey verifies that the same identity key maps
// to the same device row even when device_name differs between logins (the web
// client's device_name is volatile), and that a different identity key mints a
// new device. This prevents device proliferation that would leave later logins
// without group key envelopes.
func TestLoginMatchesDeviceByIdentityKey(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "ikmatch.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	passwordHash, _ := hashPassword("secret123")
	now := time.Now().Unix()
	if _, err := database.Exec(
		`INSERT INTO users(name,nickname,password_hash,created_at) VALUES(?,?,?,?)`,
		"User", "user", passwordHash, now,
	); err != nil {
		t.Fatal(err)
	}

	ikA := bytes.Repeat([]byte{0x11}, 32)
	ikB := bytes.Repeat([]byte{0x22}, 32)

	login := func(deviceName string, ik []byte) loginResponse {
		body, _ := json.Marshal(loginRequest{
			Nickname: "user", Password: "secret123", DeviceName: deviceName, IKPub: ik,
		})
		req := httptest.NewRequest(http.MethodPost, "/api/v1/login", bytes.NewReader(body))
		w := httptest.NewRecorder()
		Login(database, &config.Config{SessionTTL: time.Hour})(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("login status = %d, body = %q", w.Code, w.Body.String())
		}
		var resp loginResponse
		if err := json.NewDecoder(w.Body).Decode(&resp); err != nil {
			t.Fatal(err)
		}
		return resp
	}

	// First login mints device 1.
	first := login("Web Client AAA", ikA)

	// Same identity key, different (volatile) device name → same device.
	second := login("Web Client BBB", ikA)
	if second.DeviceID != first.DeviceID {
		t.Fatalf("same IK should reuse device: got %d, want %d", second.DeviceID, first.DeviceID)
	}

	// Different identity key → new device.
	third := login("Web Client CCC", ikB)
	if third.DeviceID == first.DeviceID {
		t.Fatalf("different IK should mint a new device, but reused %d", third.DeviceID)
	}

	// Exactly two devices should exist for this user.
	var count int
	if err := database.QueryRow(`SELECT COUNT(*) FROM devices`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 2 {
		t.Fatalf("device count = %d, want 2", count)
	}
}
