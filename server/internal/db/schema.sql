CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  nickname TEXT UNIQUE NOT NULL,
  nickname_changed_at INTEGER DEFAULT 0,
  password_hash TEXT NOT NULL,
  avatar BLOB,
  is_bot INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_name TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT '',
  location TEXT NOT NULL DEFAULT '',
  registration_id INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  fcm_token TEXT NOT NULL DEFAULT '',
  crypto_version INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS chats (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user1_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  user2_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  is_e2ee INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  UNIQUE(user1_id, user2_id)
);

CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
  sender_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_msg_id TEXT,
  reply_to_msg_id TEXT,
  plaintext TEXT,
  ciphertext BLOB DEFAULT NULL,
  encryption_salt BLOB DEFAULT NULL,
  encryption_nonce BLOB DEFAULT NULL,
  sender_device_id INTEGER REFERENCES devices(id) ON DELETE SET NULL,
  recipient_device_id INTEGER REFERENCES devices(id) ON DELETE SET NULL,
  prekey_id INTEGER DEFAULT NULL,
  timestamp INTEGER NOT NULL,
  edited_at INTEGER DEFAULT NULL,
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

CREATE TABLE IF NOT EXISTS groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    is_e2ee INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    membership_version INTEGER NOT NULL DEFAULT 1,
    current_key_version INTEGER NOT NULL DEFAULT 1,
    deleted_at INTEGER DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS group_members (
    group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL DEFAULT 'member',
    status TEXT NOT NULL DEFAULT 'active',
    joined_at INTEGER NOT NULL,
    removed_at INTEGER DEFAULT NULL,
    membership_version INTEGER NOT NULL,
    PRIMARY KEY(group_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members(user_id, status);

CREATE TABLE IF NOT EXISTS group_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL,
    reply_to_msg_id TEXT,
    sender_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    key_version INTEGER NOT NULL DEFAULT 0,
    plaintext TEXT DEFAULT NULL,
    ciphertext BLOB DEFAULT NULL,
    encryption_salt BLOB DEFAULT NULL,
    encryption_nonce BLOB DEFAULT NULL,
    created_at INTEGER NOT NULL,
    edited_at INTEGER DEFAULT NULL,
    UNIQUE(group_id, sender_user_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_group_messages_group ON group_messages(group_id, id);

CREATE TABLE IF NOT EXISTS group_message_devices (
    message_id INTEGER NOT NULL REFERENCES group_messages(id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    delivered_at INTEGER DEFAULT NULL,
    read_at INTEGER DEFAULT NULL,
    PRIMARY KEY(message_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_group_message_devices_undelivered
    ON group_message_devices(device_id, delivered_at);

CREATE TABLE IF NOT EXISTS sticker_packs (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    author_id INTEGER NOT NULL DEFAULT 0,
    cover_sticker_id TEXT NOT NULL DEFAULT '',
    is_animated INTEGER NOT NULL DEFAULT 0,
    is_video INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS stickers (
    id TEXT NOT NULL,
    pack_id TEXT NOT NULL REFERENCES sticker_packs(id) ON DELETE CASCADE,
    emoji TEXT NOT NULL DEFAULT '',
    file_name TEXT NOT NULL,
    width INTEGER NOT NULL DEFAULT 512,
    height INTEGER NOT NULL DEFAULT 512,
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (pack_id, id)
);

CREATE INDEX IF NOT EXISTS idx_stickers_pack ON stickers(pack_id, sort_order);

CREATE TABLE IF NOT EXISTS user_sticker_packs (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pack_id TEXT NOT NULL REFERENCES sticker_packs(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    installed_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, pack_id)
);

CREATE INDEX IF NOT EXISTS idx_user_sticker_packs ON user_sticker_packs(user_id, sort_order);

CREATE TABLE IF NOT EXISTS calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    call_id TEXT NOT NULL UNIQUE,
    caller_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    callee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    is_video BOOLEAN NOT NULL DEFAULT 0,
    status TEXT NOT NULL, -- 'completed', 'missed', 'declined', 'cancelled', 'busy'
    started_at INTEGER NOT NULL,
    answered_at INTEGER,
    ended_at INTEGER NOT NULL,
    duration INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_calls_participants ON calls(caller_id, callee_id, started_at);
CREATE INDEX IF NOT EXISTS idx_calls_call_id ON calls(call_id);

CREATE TABLE IF NOT EXISTS attachments (
    id TEXT PRIMARY KEY,
    uploader_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_attachments_uploader ON attachments(uploader_user_id);

CREATE TABLE IF NOT EXISTS bots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bots_owner ON bots(owner_user_id);

