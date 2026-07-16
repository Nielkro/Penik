package db

import (
	"database/sql"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

func TestOpenMigratesLegacyMessageForeignKeys(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.db")
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}

	const legacySchema = `
CREATE TABLE users (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  nickname TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE devices (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_name TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL
);
CREATE TABLE chats (
  id INTEGER PRIMARY KEY,
  user1_id INTEGER NOT NULL REFERENCES users(id),
  user2_id INTEGER NOT NULL REFERENCES users(id),
  created_at INTEGER NOT NULL,
  UNIQUE(user1_id, user2_id)
);
CREATE TABLE messages (
  id INTEGER PRIMARY KEY,
  chat_id INTEGER NOT NULL REFERENCES chats(id),
  sender_device_id INTEGER NOT NULL REFERENCES devices(id),
  recipient_device_id INTEGER NOT NULL REFERENCES devices(id),
  ciphertext BLOB NOT NULL,
  timestamp INTEGER NOT NULL,
  delivered INTEGER NOT NULL DEFAULT 0
);
INSERT INTO users VALUES
  (1, 'one', 'one', 'hash', 1),
  (2, 'two', 'two', 'hash', 1);
INSERT INTO devices VALUES
  (1, 1, 'first', 1, 1),
  (2, 2, 'second', 1, 1);
INSERT INTO chats VALUES (1, 1, 2, 1);
INSERT INTO messages VALUES (1, 1, 1, 2, X'01', 1, 0);
`
	if _, err := legacy.Exec(legacySchema); err != nil {
		t.Fatal(err)
	}
	if err := legacy.Close(); err != nil {
		t.Fatal(err)
	}

	database, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	if _, err := database.Exec(`DELETE FROM devices WHERE id=1`); err != nil {
		t.Fatalf("delete device after migration: %v", err)
	}

	var messages int
	if err := database.QueryRow(`SELECT count(*) FROM messages`).Scan(&messages); err != nil {
		t.Fatal(err)
	}
	if messages != 0 {
		t.Fatalf("messages after device deletion = %d, want 0", messages)
	}
}
