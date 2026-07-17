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
