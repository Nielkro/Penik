package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"messenger/server/internal/db"
)

func TestEscapeLikePattern(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"", ""},
		{"alice", "alice"},
		{"100%", `100\%`},
		{"user_1", `user\_1`},
		{`c:\test`, `c:\\test`},
		{`%_\`, `\%\_\\`},
	}
	for _, tc := range cases {
		got := escapeLikePattern(tc.in)
		if got != tc.want {
			t.Errorf("escapeLikePattern(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestSearchUsers_EmptyQuery(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "users_empty.db"))
	if err != nil {
		t.Fatalf("failed to open test db: %v", err)
	}
	defer database.Close()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/search?q=", nil)
	rec := httptest.NewRecorder()
	SearchUsers(database)(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	var res []any
	if err := json.Unmarshal(rec.Body.Bytes(), &res); err != nil {
		t.Fatalf("failed to unmarshal JSON: %v", err)
	}
	if len(res) != 0 {
		t.Fatalf("expected 0 results for empty query, got %d", len(res))
	}
}

func TestSearchUsers_WildcardEscaping(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "users_wildcard.db"))
	if err != nil {
		t.Fatalf("failed to open test db: %v", err)
	}
	defer database.Close()

	now := time.Now().Unix()
	_, _ = database.Exec(`INSERT INTO users(name, nickname, password_hash, created_at) VALUES('Alice Smith', 'alice', 'h', ?)`, now)
	_, _ = database.Exec(`INSERT INTO users(name, nickname, password_hash, created_at) VALUES('Bob Jones', 'bob', 'h', ?)`, now)
	_, _ = database.Exec(`INSERT INTO users(name, nickname, password_hash, created_at) VALUES('Percent % Guy', 'pct_user', 'h', ?)`, now)

	// Searching for "%" should ONLY match "Percent % Guy", not all 3 users
	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/search?q=%25", nil) // %25 is url-encoded %
	rec := httptest.NewRecorder()
	SearchUsers(database)(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	type userResult struct {
		ID       int64  `json:"id"`
		Name     string `json:"name"`
		Nickname string `json:"nickname"`
	}
	var results []userResult
	if err := json.Unmarshal(rec.Body.Bytes(), &results); err != nil {
		t.Fatalf("failed to unmarshal JSON: %v", err)
	}

	if len(results) != 1 {
		t.Fatalf("expected 1 result matching literal '%%', got %d", len(results))
	}
	if results[0].Nickname != "pct_user" {
		t.Errorf("expected pct_user, got %s", results[0].Nickname)
	}
}
