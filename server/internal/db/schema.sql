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

CREATE TABLE IF NOT EXISTS device_public_keys (
    device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    x25519_pub BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Login matches a device by (user_id, identity key), so a stable crypto
-- identity maps to one device row regardless of a volatile device_name.
CREATE INDEX IF NOT EXISTS idx_device_public_keys_pub ON device_public_keys(x25519_pub);

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

CREATE TABLE IF NOT EXISTS groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    owner_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
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

CREATE TABLE IF NOT EXISTS group_key_versions (
    group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    key_version INTEGER NOT NULL,
    created_by_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    membership_version INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    revoked_at INTEGER DEFAULT NULL,
    PRIMARY KEY(group_id, key_version)
);

CREATE TABLE IF NOT EXISTS group_key_envelopes (
    group_id INTEGER NOT NULL,
    key_version INTEGER NOT NULL,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    encrypted_key BLOB NOT NULL,
    encryption_salt BLOB NOT NULL,
    encryption_nonce BLOB NOT NULL,
    sender_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    delivered_at INTEGER DEFAULT NULL,
    PRIMARY KEY(group_id, key_version, device_id),
    FOREIGN KEY(group_id, key_version)
        REFERENCES group_key_versions(group_id, key_version)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_group_key_envelopes_device
    ON group_key_envelopes(device_id, delivered_at);

CREATE TABLE IF NOT EXISTS group_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL,
    sender_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    key_version INTEGER NOT NULL,
    ciphertext BLOB NOT NULL,
    encryption_salt BLOB NOT NULL,
    encryption_nonce BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(group_id, key_version)
        REFERENCES group_key_versions(group_id, key_version),
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

-- One-shot delivery of pre-join chat history to a newly invited device. The
-- inviter re-encrypts their locally held plaintext under the pairwise secret
-- shared with each invitee device (variant B); the server only stores opaque
-- ciphertext. A packet is deleted the moment its device fetches it, and a TTL
-- sweep drops any that were never claimed (invite declined, device offline).
CREATE TABLE IF NOT EXISTS group_history_packets (
    group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    for_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    encrypted_history BLOB NOT NULL,
    encryption_salt BLOB NOT NULL,
    encryption_nonce BLOB NOT NULL,
    sender_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    PRIMARY KEY(group_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_group_history_packets_expiry
    ON group_history_packets(expires_at);
