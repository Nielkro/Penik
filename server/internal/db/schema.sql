CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  nickname TEXT UNIQUE NOT NULL,
  nickname_changed_at INTEGER DEFAULT 0,
  password_hash TEXT NOT NULL,
  avatar BLOB,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_name TEXT NOT NULL,
  registration_id INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS identity_keys (
  device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  ik_pub BLOB NOT NULL,
  spk_pub BLOB NOT NULL,
  spk_sig BLOB NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS one_time_keys (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  key_id INTEGER NOT NULL DEFAULT 0,
  opk_pub BLOB NOT NULL,
  used INTEGER NOT NULL DEFAULT 0
);



CREATE TABLE IF NOT EXISTS chats (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user1_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  user2_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  UNIQUE(user1_id, user2_id)
);

CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
  sender_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_msg_id TEXT,
  plaintext TEXT,
  ciphertext BLOB DEFAULT NULL,
  encryption_salt BLOB DEFAULT NULL,
  encryption_nonce BLOB DEFAULT NULL,
  sender_device_id INTEGER REFERENCES devices(id) ON DELETE SET NULL,
  recipient_device_id INTEGER REFERENCES devices(id) ON DELETE SET NULL,
  prekey_id INTEGER DEFAULT NULL,
  timestamp INTEGER NOT NULL,
  delivered INTEGER NOT NULL DEFAULT 0,
  read INTEGER NOT NULL DEFAULT 0,
  deleted_by_sender INTEGER NOT NULL DEFAULT 0,
  deleted_by_recipient INTEGER NOT NULL DEFAULT 0,
  purge_pending INTEGER NOT NULL DEFAULT 0,
  purge_for_user_id INTEGER REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_users_nickname ON users(nickname);
CREATE INDEX IF NOT EXISTS idx_one_time_keys_device ON one_time_keys(device_id, used);

CREATE TABLE IF NOT EXISTS device_public_keys (
    device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    x25519_pub BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS one_time_prekeys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    key_id INTEGER NOT NULL,
    public_key BLOB NOT NULL,
    used INTEGER NOT NULL DEFAULT 0,
    reserved_at INTEGER DEFAULT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE(device_id, key_id)
);

CREATE INDEX IF NOT EXISTS idx_prekeys_device_used ON one_time_prekeys(device_id, used);

CREATE TABLE IF NOT EXISTS used_prekeys_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL,
    key_id INTEGER NOT NULL,
    used_by_message_id INTEGER,
    used_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS key_backups (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    encrypted_blob BLOB NOT NULL,
    salt BLOB NOT NULL,
    iv BLOB NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS pairing_sessions (
  id TEXT PRIMARY KEY,
  owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  owner_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  ephemeral_public_key BLOB NOT NULL,
  encrypted_history BLOB,
  expires_at INTEGER NOT NULL,
  claimed_at INTEGER,
  claimed_by_device_id INTEGER REFERENCES devices(id) ON DELETE SET NULL,
  claimed_by_public_key BLOB,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS pairing_tokens (
  session_id TEXT PRIMARY KEY REFERENCES pairing_sessions(id) ON DELETE CASCADE,
  token_hash BLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pairing_sessions_expiry ON pairing_sessions(expires_at);
