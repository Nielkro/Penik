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

func TestLoginSuccess(t *testing.T) {
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
	if login.UserID != userID {
		t.Fatalf("user id = %d, want %d", login.UserID, userID)
	}
	if login.Token == "" {
		t.Fatal("token is empty")
	}
}
