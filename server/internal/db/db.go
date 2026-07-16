package db

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaFS embed.FS

// DB wraps a *sql.DB with the messenger schema applied.
type DB struct {
	*sql.DB
}

// Open opens (or creates) the SQLite database at path and runs migrations.
func Open(path string) (*DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("db: create data dir: %w", err)
	}

	sqlDB, err := sql.Open("sqlite", path+"?_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)")
	if err != nil {
		return nil, fmt.Errorf("db: open: %w", err)
	}

	sqlDB.SetMaxOpenConns(1) // SQLite is single-writer

	schema, err := schemaFS.ReadFile("schema.sql")
	if err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: read schema: %w", err)
	}

	if _, err := sqlDB.Exec(string(schema)); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate: %w", err)
	}

	if err := migrateLegacySchema(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate legacy schema: %w", err)
	}

	if err := migrateDeleteFlags(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate delete flags: %w", err)
	}

	if err := migratePurgePending(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate purge pending: %w", err)
	}

	if err := migrateRegistrationId(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate registration id: %w", err)
	}

	if err := migrateOneTimeKeysKeyId(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate otk key id: %w", err)
	}



	if err := migrateMessagesClientMsgId(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate messages client msg id: %w", err)
	}

	return &DB{sqlDB}, nil
}

func migrateLegacySchema(database *sql.DB) error {
	current, err := hasCascadeForeignKeys(database)
	if err != nil {
		return err
	}
	if current {
		return nil
	}

	ctx := context.Background()
	if _, err := database.ExecContext(ctx, `PRAGMA foreign_keys = OFF`); err != nil {
		return fmt.Errorf("disable foreign keys: %w", err)
	}
	defer database.ExecContext(ctx, `PRAGMA foreign_keys = ON`)

	tx, err := database.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin: %w", err)
	}
	defer tx.Rollback()

	// Old databases did not cascade device deletion into messages. Remove rows
	// whose parent was already deleted before rebuilding the affected tables.
	const migration = `
DELETE FROM messages
WHERE chat_id NOT IN (SELECT id FROM chats)
   OR sender_device_id NOT IN (SELECT id FROM devices)
   OR recipient_device_id NOT IN (SELECT id FROM devices);
DELETE FROM sessions
WHERE user_id NOT IN (SELECT id FROM users)
   OR device_id NOT IN (SELECT id FROM devices);
DELETE FROM identity_keys WHERE device_id NOT IN (SELECT id FROM devices);
DELETE FROM one_time_keys WHERE device_id NOT IN (SELECT id FROM devices);

DELETE FROM chats
WHERE user1_id NOT IN (SELECT id FROM users)
   OR user2_id NOT IN (SELECT id FROM users);

CREATE TEMP TABLE legacy_chats AS SELECT * FROM chats;
CREATE TEMP TABLE legacy_messages AS SELECT * FROM messages;
DROP TABLE messages;
DROP TABLE chats;

CREATE TABLE chats (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user1_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  user2_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  UNIQUE(user1_id, user2_id)
);
CREATE TABLE messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
  sender_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  recipient_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  ciphertext BLOB NOT NULL,
  timestamp INTEGER NOT NULL,
  delivered INTEGER NOT NULL DEFAULT 0
);

INSERT INTO chats SELECT * FROM legacy_chats;
INSERT INTO messages SELECT * FROM legacy_messages;
DROP TABLE legacy_messages;
DROP TABLE legacy_chats;

CREATE INDEX idx_messages_recipient_undelivered
ON messages(recipient_device_id, delivered);
`
	if _, err := tx.ExecContext(ctx, migration); err != nil {
		return fmt.Errorf("rebuild chats and messages: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit: %w", err)
	}

	if _, err := database.ExecContext(ctx, `PRAGMA foreign_keys = ON`); err != nil {
		return fmt.Errorf("enable foreign keys: %w", err)
	}

	var table string
	if err := database.QueryRowContext(ctx, `PRAGMA foreign_key_check`).Scan(&table, new(any), new(any), new(any)); err != sql.ErrNoRows {
		if err != nil {
			return fmt.Errorf("check foreign keys: %w", err)
		}
		return fmt.Errorf("foreign key check failed for table %s", table)
	}
	return nil
}

func hasCascadeForeignKeys(database *sql.DB) (bool, error) {
	type requiredKey struct {
		table string
		from  string
	}
	required := map[requiredKey]bool{
		{table: "users", from: "user1_id"}:              false,
		{table: "users", from: "user2_id"}:              false,
		{table: "chats", from: "chat_id"}:               false,
		{table: "devices", from: "sender_device_id"}:    false,
		{table: "devices", from: "recipient_device_id"}: false,
	}

	for _, table := range []string{"chats", "messages"} {
		rows, err := database.Query(`PRAGMA foreign_key_list(` + table + `)`)
		if err != nil {
			return false, fmt.Errorf("inspect %s foreign keys: %w", table, err)
		}
		for rows.Next() {
			var id, seq int
			var parent, from, to, onUpdate, onDelete, match string
			if err := rows.Scan(&id, &seq, &parent, &from, &to, &onUpdate, &onDelete, &match); err != nil {
				rows.Close()
				return false, fmt.Errorf("scan %s foreign keys: %w", table, err)
			}
			key := requiredKey{table: parent, from: from}
			if _, ok := required[key]; ok && onDelete == "CASCADE" {
				required[key] = true
			}
		}
		if err := rows.Close(); err != nil {
			return false, fmt.Errorf("close %s foreign keys: %w", table, err)
		}
	}

	for _, present := range required {
		if !present {
			return false, nil
		}
	}
	return true, nil
}

func migrateDeleteFlags(database *sql.DB) error {
	rows, err := database.Query("PRAGMA table_info(messages)")
	if err != nil {
		return err
	}
	defer rows.Close()

	hasSender := false
	hasRecipient := false
	for rows.Next() {
		var cid int
		var name, typeStr string
		var notnull, pk int
		var dfltVal sql.NullString
		if err := rows.Scan(&cid, &name, &typeStr, &notnull, &dfltVal, &pk); err != nil {
			return err
		}
		if name == "deleted_by_sender" {
			hasSender = true
		}
		if name == "deleted_by_recipient" {
			hasRecipient = true
		}
	}

	if !hasSender {
		if _, err := database.Exec("ALTER TABLE messages ADD COLUMN deleted_by_sender INTEGER NOT NULL DEFAULT 0"); err != nil {
			return err
		}
	}
	if !hasRecipient {
		if _, err := database.Exec("ALTER TABLE messages ADD COLUMN deleted_by_recipient INTEGER NOT NULL DEFAULT 0"); err != nil {
			return err
		}
	}
	return nil
}

func migratePurgePending(database *sql.DB) error {
	rows, err := database.Query("PRAGMA table_info(messages)")
	if err != nil {
		return err
	}
	defer rows.Close()

	hasPurge := false
	for rows.Next() {
		var cid int
		var name, typeStr string
		var notnull, pk int
		var dfltVal sql.NullString
		if err := rows.Scan(&cid, &name, &typeStr, &notnull, &dfltVal, &pk); err != nil {
			return err
		}
		if name == "purge_pending" {
			hasPurge = true
		}
	}

	if !hasPurge {
		if _, err := database.Exec("ALTER TABLE messages ADD COLUMN purge_pending INTEGER NOT NULL DEFAULT 0"); err != nil {
			return err
		}
	}
	return nil
}

func migrateRegistrationId(database *sql.DB) error {
	rows, err := database.Query("PRAGMA table_info(devices)")
	if err != nil {
		return err
	}
	defer rows.Close()

	hasReg := false
	for rows.Next() {
		var cid int
		var name, typeStr string
		var notnull, pk int
		var dfltVal sql.NullString
		if err := rows.Scan(&cid, &name, &typeStr, &notnull, &dfltVal, &pk); err != nil {
			return err
		}
		if name == "registration_id" {
			hasReg = true
		}
	}

	if !hasReg {
		if _, err := database.Exec("ALTER TABLE devices ADD COLUMN registration_id INTEGER NOT NULL DEFAULT 0"); err != nil {
			return err
		}
	}
	return nil
}

func migrateOneTimeKeysKeyId(database *sql.DB) error {
	rows, err := database.Query("PRAGMA table_info(one_time_keys)")
	if err != nil {
		return err
	}
	defer rows.Close()

	hasKeyId := false
	for rows.Next() {
		var cid int
		var name, typeStr string
		var notnull, pk int
		var dfltVal sql.NullString
		if err := rows.Scan(&cid, &name, &typeStr, &notnull, &dfltVal, &pk); err != nil {
			return err
		}
		if name == "key_id" {
			hasKeyId = true
		}
	}

	if !hasKeyId {
		if _, err := database.Exec("ALTER TABLE one_time_keys ADD COLUMN key_id INTEGER NOT NULL DEFAULT 0"); err != nil {
			return err
		}
		// Clean up old OPKs to force client key rotation / re-upload with correct key_id
		if _, err := database.Exec("DELETE FROM one_time_keys;"); err != nil {
			return err
		}
	}
	// Create the unique index now that the key_id column is guaranteed to exist
	if _, err := database.Exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_otk_device_key ON one_time_keys(device_id, key_id)"); err != nil {
		return err
	}
	return nil
}



func migrateMessagesClientMsgId(database *sql.DB) error {
	rows, err := database.Query("PRAGMA table_info(messages)")
	if err != nil {
		return err
	}
	defer rows.Close()

	hasClientMsgId := false
	for rows.Next() {
		var cid int
		var name, typeStr string
		var notnull, pk int
		var dfltVal sql.NullString
		if err := rows.Scan(&cid, &name, &typeStr, &notnull, &dfltVal, &pk); err != nil {
			return err
		}
		if name == "client_msg_id" {
			hasClientMsgId = true
		}
	}

	if !hasClientMsgId {
		if _, err := database.Exec("ALTER TABLE messages ADD COLUMN client_msg_id TEXT"); err != nil {
			return err
		}
	}

	// Create unique index for idempotency
	if _, err := database.Exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_client_msg_id ON messages(sender_device_id, recipient_device_id, client_msg_id)"); err != nil {
		return err
	}
	return nil
}
