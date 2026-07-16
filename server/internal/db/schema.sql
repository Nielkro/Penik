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
  sender_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  recipient_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  client_msg_id TEXT,
  ciphertext BLOB NOT NULL,
  timestamp INTEGER NOT NULL,
  delivered INTEGER NOT NULL DEFAULT 0,
  deleted_by_sender INTEGER NOT NULL DEFAULT 0,
  deleted_by_recipient INTEGER NOT NULL DEFAULT 0,
  purge_pending INTEGER NOT NULL DEFAULT 0,
  UNIQUE(sender_device_id, recipient_device_id, client_msg_id)
);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_messages_recipient_undelivered ON messages(recipient_device_id, delivered);
CREATE INDEX IF NOT EXISTS idx_users_nickname ON users(nickname);
CREATE INDEX IF NOT EXISTS idx_one_time_keys_device ON one_time_keys(device_id, used);
