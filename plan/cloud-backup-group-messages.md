# Plan: Include Group Messages in E2EE Cloud Backup (Payload v3)

**Status: implemented**

## 1. Problem Statement

### 1.1 Context & The Disappearing Pre-Join Messages
In Penik's E2EE group architecture:
- When a user joins an existing group, the group key is automatically rotated to a new epoch (`key_version: N`) to ensure **backward secrecy** (new members do not receive the private symmetric keys of previous epochs).
- To allow the new member to read past messages, the inviter/admin can check "Share chat history", which builds a one-shot `group_history_packet` encrypted pairwise for that specific invited device.
- The server stores this packet temporarily with `delete-on-fetch`: once the invited device fetches it, the server permanently deletes it from `group_history_packets`.
- The invited device decrypts the backlog messages and saves them as plain text into its local database (`group_messages`).

### 1.2 The Failure on Device Switch / Loss
Currently, the cloud backup (`POST /api/v1/keys/backup`) only backs up **cryptographic keys** (payload format `version: 2`):
```json
{
  "version": 2,
  "identity_key": "<base64>",
  "group_keys": [
    { "group_id": 11, "version": 4, "key": "<base64>" }
  ]
}
```
If the user's phone is lost or stolen, or when logging in from scratch on a new device:
1. The new device restores the key backup (`identity_key` + `group_keys: [v4]`).
2. The new device queries `GET /api/v1/groups/:id/history` for raw server messages.
3. The server delivers all 7 messages: 4 messages encrypted with `v1` and `v3`, and 3 messages encrypted with `v4`.
4. The one-shot history packet on the server was already deleted by the old device.
5. The new device does not possess group keys `v1` or `v3` (they were never shared with this user).
6. Result: **The 4 pre-join backlog messages fail decryption and are permanently lost** for the new device.

---

## 2. Proposed Solution: Backup Payload v3

Extend the E2EE cloud backup payload from `version: 2` to `version: 3` to include the locally stored group messages (and optionally direct messages metadata).

Because the entire cloud backup blob is already encrypted client-side using `***REDACTED-BY-FILTER-REPO***` or `AES-256-GCM` with a key derived from the user's master E2EE passphrase / 12-word mnemonic seed (`PBKDF2-SHA256` with high iterations), storing the decrypted text inside the backup blob preserves zero-knowledge E2EE guarantees: the server cannot read the messages.

### 2.1 Payload Format Specification (Version 3)

```json
{
  "version": 3,
  "identity_key": "<base64 raw 32-byte X25519 private key>",
  "signing_key": "<base64 raw 32-byte Ed25519 private key>",
  "group_keys": [
    {
      "group_id": 11,
      "version": 4,
      "key": "<base64 raw 32-byte ChaCha20 group key>"
    }
  ],
  "group_messages": [
    {
      "group_id": 11,
      "message_id": "38170c6e-843d-41bd-a952-877d34a85b27",
      "server_id": 8,
      "sender_user_id": 33,
      "sender_device_id": 35,
      "key_version": 1,
      "text": "Hello, welcome to the group!",
      "created_at": 1790074392,
      "edited_at": null,
      "reply_to_msg_id": null
    }
  ]
}
```

### 2.2 Size & Quota Considerations
- Text message payloads average 100–300 bytes of JSON.
- 1,000 group messages ~= 200 KB compressed / raw JSON.
- 5,000 group messages ~= 1 MB.
- The Go server's `MaxBodySize` is currently configured to **12 MiB** (`server/internal/config/config.go`), leaving ample headroom.
- **Safety Cap:** The client will export up to the most recent **3,000 group messages** per group (or across all groups), prioritizing pre-join and recent messages, ensuring the payload stays comfortably under 3–5 MiB.

---

## 3. Implementation Details

### 3.1 Android Client

#### A. Export Path (`AuthRepository.kt` -> `uploadKeyBackup`)
1. Read `groupDao.getAllKeys()`.
2. Read `groupDao.getAllMessages()` (with optional limit/sorting by `createdAt DESC`).
3. Construct `payload` JSON object with `version: 3`:
   - Serialize `identity_key` and `group_keys`.
   - Serialize `group_messages` array with fields: `groupId`, `messageId`, `serverId`, `senderUserId`, `senderDeviceId`, `keyVersion`, `text`, `createdAt`, `editedAt`, `replyToMsgId`.
4. Encrypt with `e2eeCrypto.encryptKeyBackup(payloadBytes, passphrase)` and upload to `POST /api/v1/keys/backup`.

#### B. Import / Restore Path (`AuthRepository.kt` -> `restoreKeyBackup`)
1. Decrypt blob with user's passphrase/mnemonic.
2. Parse JSON payload:
   - If `version >= 2`: restore `identity_key` and `group_keys` into `groupDao.saveGroupKeys(...)`.
   - If `version >= 3`: parse `group_messages` array into `List<GroupMessageEntity>`.
3. Use Room's existing `groupDao.insertGroupMessages(messages)` with `OnConflictStrategy.REPLACE` (or `IGNORE` to not overwrite newer server receipts).
4. When `GroupRepository.syncHistory(groupId)` runs subsequently:
   - It checks `val existing = dao.getMessage(groupId, m.messageId)`.
   - Since the restored messages are already present in Room, it skips duplicate decryption and preserves all backlog messages!

### 3.2 Web / Desktop Client (`client/js/`)

#### A. Export Path (`client/js/app.js` -> `backupE2EEKeys`)
1. Read plain group keys via `getAllGroupKeysPlain()`.
2. Read group messages via `getAllGroupMessages()`.
3. Construct payload v3 with `group_messages` array.
4. Encrypt and upload via `apiPost("/keys/backup", ...)`.

#### B. Import / Restore Path (`client/js/app.js` -> `restoreE2EEKeys`)
1. Decrypt backup payload.
2. If `parsed.group_messages` is present:
   - For each message, store in IndexedDB `group_messages` store via `saveGroupMessage(msg)`.
3. Both pre-join and regular group messages become immediately readable in the UI without re-fetching or failing key acquisition.

### 3.3 Server
- **Zero Schema Changes Required!**
  The server treats `encrypted_blob`, `salt`, and `iv` in `key_backups` as opaque bytes.
  The server does not inspect or validate the inner JSON; it only enforces the 12 MiB HTTP body limit.

---

## 4. Backward & Forward Compatibility

| Scenario | Behavior |
|---|---|
| **Old Client restores v3 Backup** | Old client expects v2 (only looks for `identity_key` and `group_keys`), ignores the unknown `group_messages` field, restores keys successfully without crashing. |
| **New Client restores v1/v2 Backup** | New client checks `if (parsed.group_messages)` — if absent, restores keys normally and falls back to server history sync for groups. |
| **Duplicate Sync Prevention** | Database entities use compound primary key `(groupId, messageId)`. Restoring existing messages does not duplicate rows. |

---

## 5. Verification Plan

### Automated / Unit Tests
1. Test serializing and deserializing backup payload v3 in Kotlin (`AuthRepositoryTest`).
2. Test that restoring v3 correctly inserts entities into `group_messages` Room table.
3. Test that `syncHistory()` retains pre-restored messages.

### Manual Verification Flow
1. On Device A, join a group with pre-join backlog history.
2. Verify all messages are visible on Device A.
3. Create Cloud Backup on Device A.
4. Log in on Device B from scratch, choose Device A's backup and enter E2EE passphrase.
5. Open the group on Device B: verify that all messages (including pre-join history) are instantly visible and readable!
