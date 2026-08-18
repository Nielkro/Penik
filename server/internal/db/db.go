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

	if err := migrateDeviceMeta(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate device meta: %w", err)
	}

	if err := migrateMessagesClientMsgId(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate messages client msg id: %w", err)
	}

	if err := migrateMessagesUserOwnership(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate messages user ownership: %w", err)
	}

	if err := migrateDeliveredAt(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate delivered_at: %w", err)
	}
	if err := migrateMessageRead(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate read: %w", err)
	}

	if err := migrateToE2EE(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate to e2ee: %w", err)
	}

	if err := migrateMessagesE2EE(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate messages e2ee: %w", err)
	}

	if err := migratePairingSchema(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate pairing schema: %w", err)
	}

	if err := migrateReplyToMsgId(sqlDB); err != nil {
		sqlDB.Close()
		return nil, fmt.Errorf("db: migrate reply_to_msg_id: %w", err)
	}

	return &DB{sqlDB}, nil
}

// migratePairingSchema upgrades databases created before the pairing columns
// were added to schema.sql. CREATE TABLE IF NOT EXISTS does not alter an
// already existing SQLite table.
func migratePairingSchema(database *sql.DB) error {
	columns := []struct {
		name string
		def  string
	}{
		{"claimed_by_device_id", "INTEGER REFERENCES devices(id) ON DELETE SET NULL"},
		{"claimed_by_public_key", "BLOB"},
		{"transfer_direction", "TEXT NOT NULL DEFAULT 'web_to_phone'"},
	}
	for _, column := range columns {
		has, err := tableHasColumn(database, "pairing_sessions", column.name)
		if err != nil {
			return err
		}
		if !has {
			if _, err := database.Exec("ALTER TABLE pairing_sessions ADD COLUMN " + column.name + " " + column.def); err != nil {
				return fmt.Errorf("add %s: %w", column.name, err)
			}
		}
	}
	return nil
}

func migrateLegacySchema(database *sql.DB) error {
	userOwned, err := tableHasColumn(database, "messages", "sender_user_id")
	if err != nil {
		return err
	}
	if userOwned {
		return nil
	}

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
	return addDeviceColumnIfMissing(database, "registration_id",
		"ALTER TABLE devices ADD COLUMN registration_id INTEGER NOT NULL DEFAULT 0")
}

// migrateDeviceMeta adds the platform and location columns that record where a
// device is signing in from, for databases created before they existed.
func migrateDeviceMeta(database *sql.DB) error {
	if err := addDeviceColumnIfMissing(database, "platform",
		"ALTER TABLE devices ADD COLUMN platform TEXT NOT NULL DEFAULT ''"); err != nil {
		return err
	}
	return addDeviceColumnIfMissing(database, "location",
		"ALTER TABLE devices ADD COLUMN location TEXT NOT NULL DEFAULT ''")
}

// addDeviceColumnIfMissing runs alterSQL only when the devices table lacks the
// named column, making the ALTER idempotent across restarts.
func addDeviceColumnIfMissing(database *sql.DB, column, alterSQL string) error {
	rows, err := database.Query("PRAGMA table_info(devices)")
	if err != nil {
		return err
	}
	defer rows.Close()

	has := false
	for rows.Next() {
		var cid int
		var name, typeStr string
		var notnull, pk int
		var dfltVal sql.NullString
		if err := rows.Scan(&cid, &name, &typeStr, &notnull, &dfltVal, &pk); err != nil {
			return err
		}
		if name == column {
			has = true
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if !has {
		if _, err := database.Exec(alterSQL); err != nil {
			return err
		}
	}
	return nil
}

func migrateMessagesClientMsgId(database *sql.DB) error {
	userOwned, err := tableHasColumn(database, "messages", "sender_user_id")
	if err != nil {
		return err
	}

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

	indexSQL := "CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_client_msg_id ON messages(sender_device_id, recipient_device_id, client_msg_id)"
	if userOwned {
		indexSQL = `CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_client_msg_id
			ON messages(sender_user_id, client_msg_id)
			WHERE client_msg_id IS NOT NULL`
	}
	if _, err := database.Exec(indexSQL); err != nil {
		return err
	}
	return nil
}

func migrateMessagesUserOwnership(database *sql.DB) error {
	userOwned, err := tableHasColumn(database, "messages", "sender_user_id")
	if err != nil {
		return err
	}
	if userOwned {
		return ensureUserMessageIndexes(database)
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

	const migration = `
DROP INDEX IF EXISTS idx_messages_recipient_undelivered;
DROP INDEX IF EXISTS idx_messages_client_msg_id;

CREATE TABLE messages_user_owned (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
  sender_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_msg_id TEXT,
  plaintext TEXT NOT NULL,
  timestamp INTEGER NOT NULL,
  delivered INTEGER NOT NULL DEFAULT 0,
  deleted_by_sender INTEGER NOT NULL DEFAULT 0,
  deleted_by_recipient INTEGER NOT NULL DEFAULT 0,
  purge_pending INTEGER NOT NULL DEFAULT 0,
  purge_for_user_id INTEGER REFERENCES users(id) ON DELETE CASCADE
);

INSERT INTO messages_user_owned (
  id, chat_id, sender_user_id, recipient_user_id, client_msg_id,
  plaintext, timestamp, delivered, deleted_by_sender,
  deleted_by_recipient, purge_pending, purge_for_user_id
)
SELECT
  MIN(m.id),
  m.chat_id,
  sender.user_id,
  recipient.user_id,
  m.client_msg_id,
  CAST(MAX(m.ciphertext) AS TEXT),
  MIN(m.timestamp),
  MAX(m.delivered),
  MAX(m.deleted_by_sender),
  MAX(m.deleted_by_recipient),
  MAX(m.purge_pending),
  CASE WHEN MAX(m.purge_pending) = 1 THEN recipient.user_id ELSE NULL END
FROM messages m
JOIN devices sender ON sender.id = m.sender_device_id
JOIN devices recipient ON recipient.id = m.recipient_device_id
WHERE sender.user_id != recipient.user_id
GROUP BY
  m.chat_id,
  sender.user_id,
  recipient.user_id,
  COALESCE(m.client_msg_id, 'legacy-' || m.id);

DROP TABLE messages;
ALTER TABLE messages_user_owned RENAME TO messages;

CREATE INDEX idx_messages_recipient_undelivered
  ON messages(recipient_user_id, delivered);
CREATE UNIQUE INDEX idx_messages_client_msg_id
  ON messages(sender_user_id, client_msg_id)
  WHERE client_msg_id IS NOT NULL;
`
	if _, err := tx.ExecContext(ctx, migration); err != nil {
		return fmt.Errorf("rebuild messages: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit: %w", err)
	}
	if _, err := database.ExecContext(ctx, `PRAGMA foreign_keys = ON`); err != nil {
		return fmt.Errorf("enable foreign keys: %w", err)
	}
	return nil
}

func ensureUserMessageIndexes(database *sql.DB) error {
	if _, err := database.Exec(`CREATE INDEX IF NOT EXISTS idx_messages_recipient_undelivered
		ON messages(recipient_user_id, delivered)`); err != nil {
		return err
	}
	// Drop old index to update its unique constraint columns
	_, _ = database.Exec(`DROP INDEX IF EXISTS idx_messages_client_msg_id`)

	if _, err := database.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_client_msg_id
		ON messages(sender_user_id, recipient_device_id, client_msg_id)
		WHERE client_msg_id IS NOT NULL`); err != nil {
		return err
	}
	return nil
}

func tableHasColumn(database *sql.DB, table, column string) (bool, error) {
	rows, err := database.Query("PRAGMA table_info(" + table + ")")
	if err != nil {
		return false, err
	}
	defer rows.Close()

	for rows.Next() {
		var cid int
		var name, typeStr string
		var notnull, pk int
		var dfltVal sql.NullString
		if err := rows.Scan(&cid, &name, &typeStr, &notnull, &dfltVal, &pk); err != nil {
			return false, err
		}
		if name == column {
			return true, nil
		}
	}
	return false, rows.Err()
}

func migrateDeliveredAt(database *sql.DB) error {
	rows, err := database.Query("PRAGMA table_info(messages)")
	if err != nil {
		return err
	}
	defer rows.Close()

	hasDeliveredAt := false
	for rows.Next() {
		var cid int
		var name, typeStr string
		var notnull, pk int
		var dfltVal sql.NullString
		if err := rows.Scan(&cid, &name, &typeStr, &notnull, &dfltVal, &pk); err != nil {
			return err
		}
		if name == "delivered_at" {
			hasDeliveredAt = true
		}
	}

	if !hasDeliveredAt {
		if _, err := database.Exec("ALTER TABLE messages ADD COLUMN delivered_at INTEGER DEFAULT NULL"); err != nil {
			return err
		}
	}
	return nil
}

func migrateMessageRead(database *sql.DB) error {
	hasRead, err := tableHasColumn(database, "messages", "read")
	if err != nil {
		return err
	}
	if !hasRead {
		_, err = database.Exec("ALTER TABLE messages ADD COLUMN read INTEGER NOT NULL DEFAULT 0")
	}
	return err
}

func migrateToE2EE(database *sql.DB) error {
	_, err := database.Exec(`CREATE TABLE IF NOT EXISTS device_public_keys (
		device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
		x25519_pub BLOB NOT NULL,
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL
	)`)
	if err != nil {
		return fmt.Errorf("create device_public_keys table: %w", err)
	}

	// Login matches a device by (user_id, identity key) so a stable crypto
	// identity maps to one device row regardless of a volatile device_name.
	_, err = database.Exec(`CREATE INDEX IF NOT EXISTS idx_device_public_keys_pub ON device_public_keys(x25519_pub)`)
	if err != nil {
		return fmt.Errorf("create idx_device_public_keys_pub index: %w", err)
	}

	_, err = database.Exec(`CREATE TABLE IF NOT EXISTS key_backups (
		user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
		encrypted_blob BLOB NOT NULL,
		salt BLOB NOT NULL,
		iv BLOB NOT NULL,
		created_at INTEGER NOT NULL
	)`)
	if err != nil {
		return fmt.Errorf("create key_backups table: %w", err)
	}

	return nil
}

func migrateMessagesE2EE(database *sql.DB) error {
	cols := []struct {
		name string
		def  string
	}{
		{"ciphertext", "BLOB DEFAULT NULL"},
		{"encryption_salt", "BLOB DEFAULT NULL"},
		{"encryption_nonce", "BLOB DEFAULT NULL"},
		{"sender_device_id", "INTEGER DEFAULT NULL"},
		{"recipient_device_id", "INTEGER DEFAULT NULL"},
		{"prekey_id", "INTEGER DEFAULT NULL"},
		{"deleted_by_sender", "INTEGER NOT NULL DEFAULT 0"},
		{"deleted_by_recipient", "INTEGER NOT NULL DEFAULT 0"},
		{"purge_pending", "INTEGER NOT NULL DEFAULT 0"},
		{"purge_for_user_id", "INTEGER REFERENCES users(id) ON DELETE CASCADE"},
	}

	for _, col := range cols {
		hasCol, err := tableHasColumn(database, "messages", col.name)
		if err != nil {
			return err
		}
		if !hasCol {
			_, err = database.Exec(fmt.Sprintf("ALTER TABLE messages ADD COLUMN %s %s", col.name, col.def))
			if err != nil {
				return fmt.Errorf("add column %s: %w", col.name, err)
			}
		}
	}

	var isNotNull bool
	rows, err := database.Query("PRAGMA table_info(messages)")
	if err != nil {
		return err
	}
	for rows.Next() {
		var cid int
		var name string
		var typeStr string
		var notnull int
		var dfltVal *string
		var pk int
		if err := rows.Scan(&cid, &name, &typeStr, &notnull, &dfltVal, &pk); err == nil {
			if name == "plaintext" && notnull == 1 {
				isNotNull = true
			}
		}
	}
	rows.Close()

	if isNotNull {
		tx, err := database.Begin()
		if err != nil {
			return err
		}
		defer tx.Rollback()

		_, err = tx.Exec(`CREATE TABLE messages_new (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
			sender_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			recipient_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			client_msg_id TEXT,
			plaintext TEXT DEFAULT NULL,
			ciphertext BLOB DEFAULT NULL,
			encryption_salt BLOB DEFAULT NULL,
			encryption_nonce BLOB DEFAULT NULL,
			sender_device_id INTEGER REFERENCES devices(id) ON DELETE SET NULL,
			recipient_device_id INTEGER REFERENCES devices(id) ON DELETE SET NULL,
			prekey_id INTEGER DEFAULT NULL,
			timestamp INTEGER NOT NULL,
			delivered INTEGER NOT NULL DEFAULT 0,
			delivered_at INTEGER,
			deleted_by_sender INTEGER NOT NULL DEFAULT 0,
			deleted_by_recipient INTEGER NOT NULL DEFAULT 0,
			purge_pending INTEGER NOT NULL DEFAULT 0,
			purge_for_user_id INTEGER REFERENCES users(id) ON DELETE CASCADE
		)`)
		if err != nil {
			return err
		}

		_, err = tx.Exec(`INSERT INTO messages_new (
			id, chat_id, sender_user_id, recipient_user_id, client_msg_id, plaintext,
			ciphertext, encryption_salt, encryption_nonce, sender_device_id, recipient_device_id,
			prekey_id, timestamp, delivered, delivered_at, deleted_by_sender, deleted_by_recipient,
			purge_pending, purge_for_user_id
		) SELECT 
			id, chat_id, sender_user_id, recipient_user_id, client_msg_id, plaintext,
			ciphertext, encryption_salt, encryption_nonce, sender_device_id, recipient_device_id,
			prekey_id, timestamp, delivered, delivered_at, deleted_by_sender, deleted_by_recipient,
			purge_pending, purge_for_user_id
		FROM messages`)
		if err != nil {
			return err
		}

		_, err = tx.Exec(`DROP TABLE messages`)
		if err != nil {
			return err
		}

		_, err = tx.Exec(`ALTER TABLE messages_new RENAME TO messages`)
		if err != nil {
			return err
		}

		if err := tx.Commit(); err != nil {
			return err
		}
	}

	return nil
}

// migrateReplyToMsgId adds reply_to_msg_id column to messages and group_messages tables
func migrateReplyToMsgId(database *sql.DB) error {
	for _, table := range []string{"messages", "group_messages"} {
		has, err := tableHasColumn(database, table, "reply_to_msg_id")
		if err != nil {
			return err
		}
		if !has {
			if _, err := database.Exec("ALTER TABLE " + table + " ADD COLUMN reply_to_msg_id TEXT"); err != nil {
				return fmt.Errorf("add reply_to_msg_id to %s: %w", table, err)
			}
		}
	}
	return nil
}

