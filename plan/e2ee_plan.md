# E2EE + Мультидевайсинг: Технический план

## 1. Анализ текущей архитектуры

### 1.1 Сущности и хранение данных

| Сущность | Сервер (SQLite) | Android (Room) | Web (IndexedDB) |
|---|---|---|---|
| **User** | `users` (id, name, nickname, password_hash, avatar) | `chats` (userId, nickname, name) | `contacts` (user_id, name, nickname) |
| **Device** | `devices` (id, user_id, device_name, registration_id, last_seen) | localStorage `device_id` | localStorage `device_id` |
| **Message** | `messages` (id, chat_id, sender_user_id, recipient_user_id, plaintext, timestamp, delivered) | `messages` (localId, serverId, chatUserId, text, timestamp) | `messages` (msg_id, chat_id, plaintext, created_at) |
| **Chat** | `chats` (user1_id, user2_id, created_at) | `chats` (userId, nickname, lastMessage) | `contacts` (user_id, last_message, last_ts) |
| **Session** | `sessions` (token, user_id, device_id, expires_at) | EncryptedSharedPreferences (auth_token, user_id, device_id) | localStorage (penik_token, user_id, device_id) |
| **Identity Keys** | `identity_keys` (device_id, ik_pub, spk_pub, spk_sig) | — | — |
| **One-Time Keys** | `one_time_keys` (device_id, key_id, opk_pub, used) | — | — |

### 1.2 API эндпоинты

**HTTP (REST):**
- `POST /api/v1/register` — регистрация (user + device + keys)
- `POST /api/v1/login` — логин (user + device + keys)
- `POST /api/v1/keys/init` — загрузка Identity Key + Signed Pre-Key
- `POST /api/v1/keys/otk` — загрузка One-Time Pre-Key
- `GET /api/v1/messages/history?limit=100` — история сообщений (plaintext!)
- `DELETE /api/v1/chats/{peer_id}` — удаление чата
- `GET/PUT /api/v1/users/*` — профиль

**WebSocket (binary, msgpack):**

| Opcode | Название | Направление | Payload |
|---|---|---|---|
| `0x01` | `MSG_SEND` | Client→Server | `{to_user_id, plaintext, msg_id}` |
| `0x02` | `MSG_RECV` | Server→Client | `{from_user_id, chat_user_id, plaintext, msg_id, ts}` |
| `0x03` | `MSG_ACK` | Server→Client | `{msg_id, client_msg_id}` |
| `0x04` | `MSG_DELIVERED` | Both | `{msg_id}` |
| `0x05` | `OFFLINE_BATCH` | Server→Client | `{msgs: [MsgRecv, ...]}` |
| `0x06`/`0x07` | `PING`/`PONG` | Both | — |
| `0x08`/`0x09` | `CHAT_PURGE`/`CHAT_PURGE_ACK` | Server→Client / Client→Server | `{chat_user_id}` |
| `0x10`/`0x11` | `KEY_FETCH_REQ`/`KEY_FETCH_RESP` | Client→Server / Server→Client | `{user_id}` / `{devices: [...]}` |

### 1.3 Что уже есть

| Компонент | Сервер (Go) | Android (Kotlin) | Web (JS) |
|---|---|---|---|
| X25519 ключи | Таблицы `identity_keys`, `one_time_keys` | Нет | Нет |
| Argon2id | Пароль хэшируется (`handlers/auth.go`) | Нет | Нет |
| Шифрование сообщений | **Нет** (plaintext) | **Нет** | **Нет** |
| Backup ключей | Структура `KeyBackup` в `models/keys.go` (не используется) | Нет | `crypto.js` — AES-GCM envelope |
| Ed25519 | `spk_sig` хранится | Нет | `verifySignature()` |

### 1.4 Ключевые файлы

**Сервер:**
- `server/internal/db/schema.sql` — схема БД
- `server/internal/db/db.go` — миграции
- `server/internal/ws/protocol.go` — WebSocket протокол
- `server/internal/ws/client.go` — обработка WS фреймов
- `server/internal/handlers/keys.go` — загрузка ключей
- `server/internal/handlers/messages.go` — история сообщений

**Android:**
- `android/.../data/network/api/ApiModels.kt` — DTO
- `android/.../data/network/api/ApiService.kt` — Retrofit API
- `android/.../data/network/websocket/WebSocketManager.kt` — WS клиент
- `android/.../data/repository/MessageRepository.kt` — отправка/получение
- `android/.../data/repository/AuthRepository.kt` — регистрация/логин
- `android/.../data/di/Modules.kt` — DI

**Web:**
- `client/js/crypto.js` — крипто-утилиты
- `client/js/storage.js` — IndexedDB
- `client/js/ws.js` — WebSocket
- `client/js/app.js` — оркестрация
- `client/js/ui/chat.js` — UI чата

---

## 2. Криптографический протокол

### 2.1 Модель ключей (PreKey Bundle, per device)

Каждое устройство генерирует:

**1. Identity Key (IK)** — долгоживущий X25519-ключ (фиксированный на всё время жизни устройства):
- `privateKey`: 32 bytes (хранится ТОЛЬКО на клиенте)
- `publicKey`: 32 bytes (загружается на сервер при регистрации/логине)

**2. Пул One-Time PreKeys (OTPK)** — одноразовые X25519-ключи:
- Генерируются заранее пачками по 20 штук
- Каждый ключ используется ОДИН раз, затем помечается как использованный
- При исчерпании (< 5 осталось) — генерируются новые
- Приватные OTPK хранятся локально на клиенте

**Почему OTPK добавляют partial PFS:**
- Если ключ Боба скомпрометирован — злоумышленник расшифрует сообщения, отправленные на Identity Key
- Но сообщения, отправленные на OTPK, расшифровать не сможет (одноразовый приватный ключ удалён)
- Для 10 школьников — достаточный уровень безопасности

### 2.2 Шифрование сообщения (self-fanout + PreKey Bundle)

Отправка одного сообщения пользователю `B` с устройства `A`:

```
1. A запрашивает PreKey Bundle для КАЖДОГО устройства:

   a. Устройства ПОЛУЧАТЕЛЯ (B):
      GET /api/v1/keys/bundle/{recipient_user_id}
      → [{ device_id: 456, identity_key: "...", one_time_key: "...", key_id: 42 },
         { device_id: 789, identity_key: "...", one_time_key: "...", key_id: 43 }]

   b. Свои устройства, КРОМЕ ТЕКУЩЕГО (self-fanout):
      GET /api/v1/keys/bundle/{my_user_id}
      → [{ device_id: 111, identity_key: "...", one_time_key: "...", key_id: 44 }]

2. Для КАЖДОГО устройства из объединённого списка:
   a. Если есть one_time_key → использовать OTPK:
      shared_secret = X25519(A.privateKey, device.one_time_key)
      prekey_id = device.key_id
   b. Если one_time_key == null → fallback на Identity Key:
      shared_secret = X25519(A.privateKey, device.identity_key)
      prekey_id = null
   c. salt = random(32)
   d. nonce = random(12)
   e. key = HKDF(salt, shared_secret, info="penik-e2ee-v1", length=32)
   f. ciphertext = ChaCha20-Poly1305(key, nonce, plaintext)
   g. client_msg_id = UUID (один на всё сообщение)

3. A отправляет на сервер:
   MSG_SEND с payload = {
     to_user_id: B,
     msg_id: "uuid",
     devices: [
       // Устройства Боба
       { device_id: 456, prekey_id: 42, ciphertext: "...", salt: "...", nonce: "..." },
       { device_id: 789, prekey_id: 43, ciphertext: "...", salt: "...", nonce: "..." },
       // Self-fanout: планшет Алисы
       { device_id: 111, prekey_id: 44, ciphertext: "...", salt: "...", nonce: "..." }
     ]
   }

4. Сервер:
   a. Для каждого device_id: определить владельца
   b. Пометить OTPK как used (если prekey_id != null)
   c. Сохранить отдельную строку в messages (fanout)
   d. Если OTPK закончились — сгенерировать новый пул для этого устройства
   e. Рассылать MsgRecvEncrypted на каждое устройство

5. Сервер сохраняет в messages:
   - Для устройств Боба: recipient_user_id = B, chat_user_id = B
   - Для устройств Алисы: recipient_user_id = A, chat_user_id = B
```

**Пример: Алиса (телефон) пишет Бобу, у Алисы ещё планшет:**

```
devices = [
  { device_id: bob_phone,    prekey_id: 42, ... },  // Боб: телефон
  { device_id: bob_laptop,   prekey_id: 43, ... },  // Боб: ноутбук
  { device_id: alice_tablet, prekey_id: 44, ... }   // Алиса: планшет (self-fanout)
]
```

### 2.3 Дешифрование

```
1. Устройство получает MSG_RECV:
   {from_user_id, from_device_id, prekey_id, ciphertext, salt, nonce, client_msg_id}

2. Определить приватный ключ:
   a. Если prekey_id != null → это OTPK:
      myPrivateKey = getPreKeyPrivate(prekey_id)  // из локального хранилища
   b. Если prekey_id == null → это Identity Key:
      myPrivateKey = getIdentityPrivateKey()

3. Получить публичный ключ отправителя:
   senderPublicKey = getOrFetchPublicKey(from_device_id)

4. shared_secret = X25519(myPrivateKey, senderPublicKey)

5. key = HKDF(salt, shared_secret, info="penik-e2ee-v1", length=32)

6. plaintext = ChaCha20-Poly1305.decrypt(key, nonce, ciphertext)

7. Если использовали OTPK → удалить приватный ключ из локального хранилища
```

### 2.4 Self-fanout: запуск шифрования

Отправитель перед шифрованием должен:

```
1. Запросить PreKey Bundle своих других устройств:
   GET /api/v1/keys/bundle/{my_user_id}
   → [{device_id: 111, identity_key: "...", one_time_key: "...", key_id: 44}]

2. Запросить PreKey Bundle устройств получателя:
   GET /api/v1/keys/bundle/{recipient_user_id}
   → [{device_id: 456, identity_key: "...", one_time_key: "...", key_id: 42},
      {device_id: 789, identity_key: "...", one_time_key: "...", key_id: 43}]

3. Объединить списки, исключив текущее device_id:
   all_devices = myOtherDevices + recipientDevices

4. Для КАЖДОГО устройства: X25519 agreement (с OTPK или IK) → HKDF → ChaCha20-Poly1305
   (разный shared_secret для каждого устройства!)
```

### 2.5 Управление OTPK-пулом (клиентская логика)

```kotlin
class PreKeyManager {
    private val POOL_SIZE = 20
    private val MIN_POOL = 5

    // При регистрации / каждом подключении
    suspend fun ensurePreKeyPool() {
        val status = api.getPreKeyStatus()
        if (status.available < MIN_POOL) {
            val newKeys = generatePreKeys(POOL_SIZE - status.available)
            api.uploadPreKeys(newKeys)
            // Сохранить приватные ключи локально
            newKeys.forEach { keyStore.savePreKeyPrivate(key.keyId, key.privateKey) }
        }
    }

    private fun generatePreKeys(count: Int): List<PreKey> {
        return (1..count).map {
            val keyPair = generateX25519KeyPair()
            PreKey(keyId = randomKeyId(), publicKey = keyPair.publicKey, privateKey = keyPair.privateKey)
        }
    }
}
```

### 2.6 Защита от replay

- Каждое сообщение имеет уникальный `client_msg_id` (UUID на клиенте)
- Сервер проверяет уникальность `(sender_user_id, client_msg_id)` через unique index
- Дубликат получает ACK с тем же `msg_id` без повторной вставки

---

## 3. Изменения в базе данных

### 3.1 Новая таблица `device_public_keys`

```sql
CREATE TABLE device_public_keys (
    device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    x25519_pub BLOB NOT NULL,          -- Identity Key (публичный)
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

### 3.2 Новая таблица `one_time_prekeys`

```sql
CREATE TABLE one_time_prekeys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    key_id INTEGER NOT NULL,
    public_key BLOB NOT NULL,
    used INTEGER NOT NULL DEFAULT 0,
    reserved_at INTEGER DEFAULT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE(device_id, key_id)
);

CREATE INDEX idx_prekeys_device_used ON one_time_prekeys(device_id, used);
```

**Логика работы:**
- Клиент генерирует 20 OTPK и загружает на сервер
- При запросе `/keys/bundle` — OTPK **только резервируется** (UPDATE used=1, reserved_at=NOW), не удаляется
- При фактической отправке (`MSG_SEND`) — OTPK удаляется (`DELETE`)
- Если сообщение не отправлено (таймаут 5 минут) — cron возвращает OTPK в пул
- Если OTPK < 5 — сервер уведомляет клиента (`REFILL_PREKEYS`) сгенерировать новый пул
- Сервер **не хранит** приватные OTPK — они только на клиенте

### 3.2.1 Cron-задача: возврат зарезервированных OTPK

```sql
-- Раз в минуту
UPDATE one_time_prekeys
SET used=0, reserved_at=NULL
WHERE used=1 AND reserved_at IS NOT NULL
  AND reserved_at < strftime('%s', 'now') - 300;
```

### 3.2.2 Таблица `used_prekeys_audit` (для отладки)

```sql
CREATE TABLE used_prekeys_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id INTEGER NOT NULL,
    key_id INTEGER NOT NULL,
    used_by_message_id INTEGER,
    used_at INTEGER NOT NULL
);
```

### 3.3 Новая таблица `key_backups`

```sql
CREATE TABLE key_backups (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    encrypted_blob BLOB NOT NULL,       -- зашифрованный приватный IK
    salt BLOB NOT NULL,                 -- Argon2id salt
    iv BLOB NOT NULL,                   -- AES-GCM IV
    created_at INTEGER NOT NULL
);
```

### 3.4 Изменение таблицы `messages`

**Текущее:**
```sql
plaintext TEXT NOT NULL
```

**Целевое:**
```sql
plaintext TEXT,                              -- NULL для новых E2EE сообщений
ciphertext BLOB,                             -- зашифрованный payload
encryption_salt BLOB,                        -- HKDF salt
encryption_nonce BLOB,                       -- ChaCha20 nonce
sender_device_id INTEGER REFERENCES devices(id),
recipient_device_id INTEGER REFERENCES devices(id),
prekey_id INTEGER DEFAULT NULL               -- ID использованного OTPK (NULL если fallback на IK)
```

**Миграция (db.go):**
```sql
ALTER TABLE messages ADD COLUMN ciphertext BLOB DEFAULT NULL;
ALTER TABLE messages ADD COLUMN encryption_salt BLOB DEFAULT NULL;
ALTER TABLE messages ADD COLUMN encryption_nonce BLOB DEFAULT NULL;
ALTER TABLE messages ADD COLUMN sender_device_id INTEGER DEFAULT NULL;
ALTER TABLE messages ADD COLUMN recipient_device_id INTEGER DEFAULT NULL;
ALTER TABLE messages ADD COLUMN prekey_id INTEGER DEFAULT NULL;

-- Уникальный индекс для защиты от replay
CREATE UNIQUE INDEX idx_messages_sender_client_msg
    ON messages(sender_user_id, client_msg_id)
    WHERE client_msg_id IS NOT NULL;
```

**Пример: Алиса (device 100) пишет Бобу (device 456, 789), self-fanout на планшет 111:**

```sql
-- Фанアウト: 3 строки в messages для одного logical message
INSERT INTO messages (chat_id, sender_user_id, recipient_user_id,
                      sender_device_id, recipient_device_id,
                      client_msg_id, ciphertext, encryption_salt, encryption_nonce,
                      prekey_id, timestamp)
VALUES
  -- Боб: телефон (зашифровано на OTPK key_id=42)
  (1, 1, 2, 100, 456, 'uuid-1', '...', '...', '...', 42, 1700000000),
  -- Боб: ноутбук (зашифровано на OTPK key_id=43)
  (1, 1, 2, 100, 789, 'uuid-1', '...', '...', '...', 43, 1700000000),
  -- Алиса: планшет (self-fanout, зашифровано на OTPK key_id=44)
  (1, 1, 1, 100, 111, 'uuid-1', '...', '...', '...', 44, 1700000000);
```

---

## 4. Изменения в WebSocket протоколе

### 4.1 Новые HTTP эндпоинты

| Метод | Путь | Описание |
|---|---|---|
| `GET` | `/api/v1/keys/bundle/{user_id}` | PreKey Bundle: Identity Key + OTPK для каждого устройства пользователя |
| `POST` | `/api/v1/keys/prekeys` | Загрузка нового пула OTPK (клиент → сервер) |
| `GET` | `/api/v1/keys/prekeys/status` | Количество доступных OTPK |

**`GET /api/v1/keys/bundle/{user_id}` — Response:**
```json
{
  "devices": [
    {
      "device_id": 456,
      "identity_key": "base64...",
      "one_time_key": "base64...",
      "key_id": 42
    },
    {
      "device_id": 789,
      "identity_key": "base64...",
      "one_time_key": null,
      "key_id": null
    }
  ]
}
```

**`POST /api/v1/keys/prekeys` — Request:**
```json
{
  "prekeys": [
    {"key_id": 1, "public_key": "base64..."},
    {"key_id": 2, "public_key": "base64..."}
  ]
}
```

**`GET /api/v1/keys/prekeys/status` — Response:**
```json
{"available": 18, "total": 20}
```

### 4.2 Новые WebSocket опкоды

| Opcode | Название | Направление | Payload |
|---|---|---|---|
| `0x12` | `KEY_PUBLISH` | Client→Server | `{x25519_pub: bytes, prekeys: [{key_id, public_key}]}` |
| `0x13` | `KEY_BUNDLE_RESP` | Server→Client | `{devices: [{device_id, identity_key, one_time_key, key_id}]}` |
| `0x14` | `KEY_BUNDLE_REQ` | Client→Server | `{user_id: int}` |
| `0x15` | `REFILL_PREKEYS` | Server→Client | `{}` (сигнал клиенту сгенерировать новые OTPK) |

### 4.3 Изменение MSG_SEND (0x01)

**Текущий:**
```json
{"to_user_id": 123, "plaintext": "hello", "msg_id": "uuid"}
```

**Новый (с self-fanout + prekey_id):**
```json
{
  "to_user_id": 123,
  "msg_id": "uuid",
  "devices": [
    {"device_id": 456, "prekey_id": 42, "ciphertext": "...", "salt": "...", "nonce": "..."},
    {"device_id": 789, "prekey_id": null, "ciphertext": "...", "salt": "...", "nonce": "..."},
    {"device_id": 111, "prekey_id": 44, "ciphertext": "...", "salt": "...", "nonce": "..."}
  ]
}
```

Где:
- `device_id: 456` — Боб телефон (зашифровано на OTPK key_id=42)
- `device_id: 789` — Боб ноутбук (OTPK закончились, fallback на Identity Key)
- `device_id: 111` — планшет Алисы (self-fanout, зашифровано на OTPK key_id=44)

**Логика сервера при обработке:**
1. Для каждого `device_id`: определить владельца
2. Если `device.owner == to_user_id` → `recipient_user_id = to_user_id`, `chat_user_id = to_user_id`
3. Если `device.owner == sender_user_id` → `recipient_user_id = sender_user_id`, `chat_user_id = to_user_id`
4. Если `prekey_id != null` → пометить OTPK как used (`DELETE FROM one_time_prekeys WHERE device_id=? AND key_id=?`)
5. Сохранить отдельную строку в `messages` с `prekey_id`
6. Доставить `MsgRecvEncrypted` на каждое устройство
7. Если OTPK закончились → отправить `REFILL_PREKEYS` (0x15) клиенту

### 4.3 Изменение MSG_RECV (0x02)

**Новый:**
```json
{
  "from_user_id": 123,
  "from_device_id": 789,
  "from_identity_key": "base64...",
  "chat_user_id": 123,
  "msg_id": 42,
  "prekey_id": 43,
  "ciphertext": "base64...",
  "salt": "base64...",
  "nonce": "base64...",
  "ts": 1700000000
}
```

**Поля:**
- `from_device_id` — устройство отправителя
- `from_identity_key` — публичный IK отправителя (чтобы получатель мог вычислить shared_secret без дополнительного HTTP-запроса)
- `prekey_id` — если `!= null`, получатель использует OTPK с этим ID; если `null` — Identity Key

### 4.4 Изменение OFFLINE_BATCH (0x05)

Каждый элемент содержит `ciphertext`, `salt`, `nonce`, `prekey_id` вместо `plaintext`.

### 4.5 Изменение MESSAGE HISTORY API

**Новый ответ:**
```json
{
  "id": 1,
  "ciphertext": "base64...",
  "salt": "base64...",
  "nonce": "base64...",
  "prekey_id": 42,
  "from_device_id": 789,
  "to_device_id": 456,
  "plaintext": null,
  ...
}
```

---

## 5. Изменения в коде (по файлам)

### 5.1 Сервер (Go)

| Файл | Изменения |
|---|---|
| `server/internal/db/schema.sql` | Добавить таблицы `device_public_keys`, `one_time_prekeys`, `key_backups`; изменить `messages` |
| `server/internal/db/db.go` | Добавить миграцию `migrateToE2EE()` |
| `server/internal/ws/protocol.go` | Добавить опкоды `0x12`, `0x13`, `0x14`, `0x15`; структуры `E2EEPayload` (с `prekey_id`), `MsgSendEncrypted`, `MsgRecvEncrypted` |
| `server/internal/ws/client.go` | Изменить `handleMsgSend()`: self-fanout + пометка OTPK как used; добавить `handleKeyPublish()`, `handleKeyBundleReq()`; изменить `sendOfflineBatch()`; добавить `sendRefillPreKeys()` |
| `server/internal/handlers/messages.go` | `GetMessageHistory()`: возвращать `ciphertext`, `salt`, `nonce`, `prekey_id` |
| `server/internal/handlers/keys.go` | Добавить `GET /api/v1/keys/bundle/{user_id}`, `POST /api/v1/keys/prekeys`, `GET /api/v1/keys/prekeys/status`; при `UploadIdentityKeys()` сохранять `x25519_pub` |

**Логика handleMsgSend() (детали self-fanout + OTPK):**

```go
func (c *Client) handleMsgSend(ctx context.Context, msg *MsgSendEncrypted) error {
    for _, dev := range msg.Devices {
        var ownerID int64
        db.QueryRow("SELECT user_id FROM devices WHERE id=?", dev.DeviceID).Scan(&ownerID)

        var recipientUserID, chatUserID int64
        if ownerID == c.userID {
            recipientUserID = c.userID
            chatUserID = msg.ToUserID
        } else {
            recipientUserID = msg.ToUserID
            chatUserID = msg.ToUserID
        }

        // Удалить зарезервированный OTPK (проверка + удаление + аудит)
        if dev.PrekeyID != nil {
            var exists bool
            db.QueryRow(`SELECT 1 FROM one_time_prekeys
                WHERE device_id=? AND key_id=? AND used=1`,
                dev.DeviceID, *dev.PrekeyID).Scan(&exists)
            if !exists {
                return fmt.Errorf("invalid prekey_id %d for device %d", *dev.PrekeyID, dev.DeviceID)
            }
            db.Exec(`DELETE FROM one_time_prekeys WHERE device_id=? AND key_id=?`,
                dev.DeviceID, *dev.PrekeyID)
            db.Exec(`INSERT INTO used_prekeys_audit (device_id, key_id, used_by_message_id, used_at)
                VALUES (?, ?, ?, strftime('%s','now'))`,
                dev.DeviceID, *dev.PrekeyID, messageID)
        }

        // Вставить строку в messages
        db.Exec(`INSERT INTO messages(
            chat_id, sender_user_id, recipient_user_id,
            sender_device_id, recipient_device_id,
            client_msg_id, ciphertext, encryption_salt, encryption_nonce,
            prekey_id, timestamp
        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
            chatID, c.userID, recipientUserID,
            c.deviceID, dev.DeviceID,
            msg.MsgID, dev.Ciphertext, dev.Salt, dev.Nonce, dev.PrekeyID, now)

        sendToDevice(dev.DeviceID, recipientFrame)
    }

    checkAndNotifyLowPreKeys(msg.ToUserID)
    sendToClient(c, MsgAck{MsgID: messageID, ClientMsgID: msg.MsgID})
    return nil
}
```

**Логика GET /api/v1/keys/bundle/{user_id} (детали):**

```go
func GetKeyBundle(database *db.DB) http.HandlerFunc {
    return func(w http.ResponseWriter, r *http.Request) {
        targetUserID := mux.Vars(r)["user_id"]
        tx, _ := database.Begin()
        defer tx.Rollback()

        rows := tx.Query(`SELECT id FROM devices WHERE user_id=?`, targetUserID)

        var bundles []KeyBundle
        for rows.Next() {
            var deviceID int64
            rows.Scan(&deviceID)

            // Identity Key
            var ikPub []byte
            tx.QueryRow(`SELECT ik_pub FROM identity_keys WHERE device_id=?`, deviceID).Scan(&ikPub)

            // Зарезервировать OTPK (не удалять!)
            var otpkPub []byte
            var keyID int64
            err := tx.QueryRow(`SELECT public_key, key_id FROM one_time_prekeys
                WHERE device_id=? AND used=0 ORDER BY id LIMIT 1`,
                deviceID).Scan(&otpkPub, &keyID)
            if err == nil {
                // Пометить как зарезервированный (used=1, reserved_at=now)
                tx.Exec(`UPDATE one_time_prekeys SET used=1, reserved_at=strftime('%s','now')
                    WHERE device_id=? AND key_id=?`, deviceID, keyID)
            }
            // Если OTPK нет — otpkPub = nil, keyID = 0

            bundles = append(bundles, KeyBundle{
                DeviceID:    deviceID,
                IdentityKey: ikPub,
                OneTimeKey:  otpkPub,
                KeyID:       &keyID,
            })
        }

        tx.Commit()
        json.NewEncoder(w).Encode(bundles)
    }
}
```

**Логика handleMsgSend() — удаление зарезервированного OTPK:**

```go
// При фактической отправке: удалить зарезервированный OTPK
if dev.PrekeyID != nil {
    // Проверить, что OTPK существует и принадлежит этому device_id
    var exists bool
    db.QueryRow(`SELECT 1 FROM one_time_prekeys
        WHERE device_id=? AND key_id=? AND used=1`,
        dev.DeviceID, *dev.PrekeyID).Scan(&exists)
    if !exists {
        return errors.New("invalid prekey_id for device")
    }
    // Удалить
    db.Exec(`DELETE FROM one_time_prekeys WHERE device_id=? AND key_id=?`,
        dev.DeviceID, *dev.PrekeyID)

    // Аудит
    db.Exec(`INSERT INTO used_prekeys_audit (device_id, key_id, used_by_message_id, used_at)
        VALUES (?, ?, ?, strftime('%s','now'))`,
        dev.DeviceID, *dev.PrekeyID, messageID)
}
```

### 5.2 Android (Kotlin)

| Файл | Изменения |
|---|---|
| `android/.../data/network/api/ApiModels.kt` | Добавить `E2EEPayload` (с `prekey_id`), `PreKeyBundleResponse`, `PreKeyStatusResponse` |
| `android/.../data/network/api/ApiService.kt` | Добавить `getKeyBundle(userId)`, `uploadPreKeys(prekeys)`, `getPreKeyStatus()` |
| `android/.../data/network/websocket/WebSocketManager.kt` | Добавить опкоды `0x12`, `0x13`, `0x14`, `0x15`; обработку `REFILL_PREKEYS` |
| `android/.../data/repository/MessageRepository.kt` | `sendMessage()`: запрос bundle → зашифровать для каждого → отправить; `handleMsgRecv()`: дешифровать |
| `android/.../data/repository/SecureTokenStorage.kt` | Добавить `savePrivateKey()`, `getPrivateKey()`, `savePreKeyPrivate()`, `getPreKeyPrivate()`, `deletePreKeyPrivate()` |
| `android/.../data/di/Modules.kt` | Добавить Provides для `E2EECrypto`, `PreKeyManager` |
| **Новый:** `android/.../crypto/E2EECrypto.kt` | X25519, HKDF, ChaCha20-Poly1305 |
| **Новый:** `android/.../crypto/PreKeyManager.kt` | Генерация OTPK, загрузка на сервер, проверка пула |

**Логика MessageRepository.sendMessage() (детали):**

```kotlin
suspend fun sendMessage(toUserId: Long, text: String) {
    val myId = tokenStorage.getUserId()
    val myDeviceId = tokenStorage.getDeviceId()
    val myPrivateKey = secureTokenStorage.getPrivateKey()!!

    // 1. Запросить PreKey Bundle получателя
    val recipientBundles = apiService.getKeyBundle(toUserId).body()?.devices ?: emptyList()

    // 2. Запросить PreKey Bundle своих других устройств (self-fanout)
    val myBundles = apiService.getKeyBundle(myId).body()?.devices.orEmpty()
        .filter { it.deviceId != myDeviceId }

    // 3. Объединить
    val allBundles = recipientBundles + myBundles

    // 4. Зашифровать для каждого устройства
    val e2eePayloads = allBundles.map { bundle ->
        val theirPublicKey = bundle.oneTimeKey ?: bundle.identityKey
        val prekeyId = bundle.keyId

        val sharedSecret = e2eeCrypto.deriveSharedSecret(myPrivateKey, theirPublicKey)
        val encrypted = e2eeCrypto.encrypt(text.toByteArray(), sharedSecret)
        E2EEPayload(bundle.deviceId, prekeyId, encrypted.ciphertext, encrypted.salt, encrypted.nonce)
    }

    // 5. Отправить через WebSocket
    webSocketManager.sendMessage(toUserId, e2eePayloads, clientMsgId)

    // 6. Проверить/обновить OTPK-пул
    preKeyManager.ensurePool()
}
```

**Логика handleMsgRecv() (детали):**

```kotlin
suspend fun handleMsgRecv(event: WebSocketEvent.MsgRecv) {
    val plaintext = try {
        val myPrivateKey = if (event.prekeyId != null) {
            val key = secureTokenStorage.getPreKeyPrivate(event.prekeyId)
            secureTokenStorage.deletePreKeyPrivate(event.prekeyId)
            key
        } else {
            secureTokenStorage.getPrivateKey()
        }

        // fromIdentityKey приходит прямо в MSG_RECV — не нужен HTTP-запрос
        val senderPublicKey = event.fromIdentityKey
        val sharedSecret = e2eeCrypto.deriveSharedSecret(myPrivateKey, senderPublicKey)
        e2eeCrypto.decrypt(event.ciphertext, sharedSecret, event.salt, event.nonce)
    } catch (e: Exception) {
        // Ключ утерян (переустановка приложения, повреждение хранилища)
        "[Зашифрованное сообщение, ключ утерян]".toByteArray()
    }

    messageDao.insertMessage(MessageEntity(
        text = String(plaintext),
        // ... остальные поля
    ))
}
```

### 5.3 Web (JS)

| Файл | Изменения |
|---|---|
| `client/js/crypto.js` | Добавить `generateKeyPair()`, `e2eeEncrypt()`, `e2eeDecrypt()`, `deriveSharedSecret()`, `hkdfDerive()` |
| `client/js/storage.js` | Добавить Object Store `e2ee_keys` (IK + OTPK приватники); добавить `ciphertext`, `salt`, `nonce`, `prekey_id` в messages |
| `client/js/ws.js` | Добавить обработку `0x12`, `0x13`, `0x14`, `0x15`; изменить `send()` → `devices` массив |
| `client/js/app.js` | При подключении: `KEY_PUBLISH` + загрузка OTPK; при отправке: запрос bundle → E2EE; при получении: дешифрование; обработка `REFILL_PREKEYS` |
| `client/js/ui/chat.js` | `sendMessage()`: self-fanout E2EE с PreKey Bundle; отображение: расшифрованный текст |

**Логика отправки (Web):**

```javascript
async function sendMessageE2EE(toUserId, plaintext) {
    const myDeviceId = Number(localStorage.device_id);
    const myPrivateKey = await getPrivateKey();

    // 1. Запросить bundle получателя + своих устройств
    const recipientBundles = await api.get(`/keys/bundle/${toUserId}`);
    const myBundles = await api.get(`/keys/bundle/${myUserId}`);
    const myOtherBundles = myBundles.filter(b => b.device_id !== myDeviceId);

    const allBundles = [...recipientBundles, ...myOtherBundles];

    // 2. Зашифровать для каждого
    const devices = await Promise.all(allBundles.map(async (bundle) => {
        const theirKey = bundle.one_time_key || bundle.identity_key;
        const sharedSecret = await deriveSharedSecret(myPrivateKey, theirKey);
        const { ciphertext, salt, nonce } = await encryptWithSecret(sharedSecret, plaintext);
        return {
            device_id: bundle.device_id,
            prekey_id: bundle.key_id || null,
            ciphertext: encodeBase64(ciphertext),
            salt: encodeBase64(salt),
            nonce: encodeBase64(nonce)
        };
    }));

    // 3. Отправить
    ws.send(0x01, { to_user_id: toUserId, msg_id: crypto.randomUUID(), devices });
}
```

---

## 6. Бэкап и восстановление ключей

### 6.1 Создание бэкапа

```
1. Генерируется парольная фраза: 4 случайных слова из словаря (2048 слов BIP-39)
2. salt = random(16)
3. key = Argon2id(phrase, salt, time=3, memory=256MB, threads=4, keyLen=32)
4. iv = random(12)
5. encrypted_private_key = AES-GCM(key, iv, privateKey)
6. POST /api/v1/keys/backup
   { encrypted_blob, salt, iv }
```

### 6.2 Восстановление

```
1. Ввести парольную фразу
2. GET /api/v1/keys/backup → { encrypted_blob, salt, iv }
3. key = Argon2id(phrase, salt, time=3, memory=256MB, threads=4, keyLen=32)
4. privateKey = AES-GCM.decrypt(key, iv, encrypted_blob)
5. Проверить: сгенерировать publicKey, загрузить с сервера, сравнить
6. Сохранить privateKey локально
```

### 6.3 Серверные эндпоинты

- `POST /api/v1/keys/backup` (authenticated) — сохранить/обновить бэкап
- `GET /api/v1/keys/backup` (authenticated) — получить бэкап текущего пользователя

---

## 7. Последовательность внедрения

### Этап 1: Криптографические утилиты (8-10 часов)

- [ ] Создать `E2EECrypto.kt` — X25519, HKDF, ChaCha20-Poly1305
- [ ] Добавить X25519, HKDF, ChaCha20-Poly1305 в `crypto.js`
- [ ] JUnit-тесты для Android, тесты для JS
- [ ] Go: `golang.org/x/crypto` уже доступен

### Этап 2: Хранение ключей на клиенте (6-8 часов)

- [ ] Android: приватный IK в EncryptedSharedPreferences
- [ ] Android: приватные OTPK в EncryptedSharedPreferences
- [ ] Web: приватный IK в IndexedDB (зашифрован паролем)
- [ ] Web: приватные OTPK в IndexedDB
- [ ] Сервер: добавить таблицы `device_public_keys`, `one_time_prekeys`

### Этап 3: PreKey Bundle + регистрация (8-10 часов)

- [ ] Сервер: `GET /api/v1/keys/bundle/{user_id}` (Identity Key + OTPK)
- [ ] Сервер: `POST /api/v1/keys/prekeys` (загрузка OTPK)
- [ ] Сервер: `GET /api/v1/keys/prekeys/status` (количество доступных)
- [ ] Сервер: при регистрации/логине — сохранять `x25519_pub` + начальный пул OTPK
- [ ] Android: генерировать IK + 20 OTPK при регистрации
- [ ] Android: `PreKeyManager` — проверка/генерация пула при каждом подключении
- [ ] Web: аналогично

### Этап 4: Шифрование при отправке (10-12 часов)

- [ ] Сервер: изменить `handleMsgSend()` — self-fanout + пометка OTPK как used
- [ ] Сервер: `REFILL_PREKEYS` (0x15) — уведомление клиента о необходимости генерации
- [ ] Android: `sendMessage()` → запрос bundle (получатель + свои устройства) → зашифровать → отправить
- [ ] Web: аналогично

### Этап 5: Дешифрование при получении (8-10 часов)

- [ ] Сервер: изменить `sendOfflineBatch()`, `GetMessageHistory()` — возвращать `ciphertext`, `salt`, `nonce`, `prekey_id`
- [ ] Android: `handleMsgRecv()` — определить ключ (OTPK по prekey_id или IK) → дешифровать → удалить OTPK
- [ ] Android: `handleOfflineBatch()`, `syncHistory()`
- [ ] Web: `onMsgRecvGlobal()`, `syncMessageHistory()`

### Этап 6: Бэкап и восстановление (6-8 часов)

- [ ] Сервер: таблица `key_backups`, эндпоинты
- [ ] Android: UI экран бэкапа + восстановления
- [ ] Web: аналогично

### Этап 7: Мультидевайсинг (6-8 часов)

- [ ] Сервер: self-fanout — определение владельца device_id
- [ ] Сервер: `GET /api/v1/keys/bundle/{user_id}` — возвращает bundle для ВСЕХ устройств
- [ ] Клиенты: `KEY_PUBLISH` при подключении
- [ ] Клиенты: при отправке — запрашивать bundle своих других устройств

### Этап 8: Миграция + тестирование (4-6 часов)

- [ ] Миграция messages: plaintext → ciphertext + prekey_id
- [ ] Backward-compatible чтение
- [ ] Интеграционные тесты

**Итого: 56-72 часов**

---

## 8. Примеры кода

### 8.1 Генерация X25519 ключей (Kotlin)

```kotlin
import java.security.KeyPairGenerator

fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
    val kpg = KeyPairGenerator.getInstance("X25519")
    val keyPair = kpg.generateKeyPair()
    val privateKey = keyPair.private.encoded
    val publicKey = keyPair.public.encoded
    return Pair(privateKey, publicKey)
}
```

### 8.2 Шифрование (Kotlin)

```kotlin
fun encryptMessage(
    plaintext: ByteArray,
    myPrivateKey: ByteArray,
    theirPublicKey: ByteArray
): E2EEncrypted {
    val keyFactory = KeyFactory.getInstance("X25519")
    val privKey = keyFactory.generatePrivate(EncodedPrivateKeySpec(myPrivateKey))
    val pubKey = keyFactory.generatePublic(EncodedPublicKeySpec(theirPublicKey))

    val agreement = KeyAgreement.getInstance("X25519")
    agreement.init(privKey)
    agreement.doPhase(pubKey, true)
    val sharedSecret = agreement.generateSecret()

    val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val hkdf = HKDFParameterSpec.builder(sharedSecret)
        .extractAndExpand(salt, "penik-e2ee-v1".toByteArray(), 32)
        .build()
    val derivedKey = SecretKeySpec(hkdf.outputKeyMaterial, "ChaCha20-Poly1305")

    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("ChaCha20-Poly1305/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, derivedKey, GCMParameterSpec(128, nonce))
    val ciphertext = cipher.doFinal(plaintext)

    return E2EEncrypted(ciphertext, salt, nonce)
}

data class E2EEncrypted(
    val ciphertext: ByteArray,
    val salt: ByteArray,
    val nonce: ByteArray
)
```

### 8.3 Дешифрование (Kotlin)

```kotlin
fun decryptMessage(
    ciphertext: ByteArray,
    salt: ByteArray,
    nonce: ByteArray,
    myPrivateKey: ByteArray,
    theirPublicKey: ByteArray
): ByteArray {
    val keyFactory = KeyFactory.getInstance("X25519")
    val privKey = keyFactory.generatePrivate(EncodedPrivateKeySpec(myPrivateKey))
    val pubKey = keyFactory.generatePublic(EncodedPublicKeySpec(theirPublicKey))

    val agreement = KeyAgreement.getInstance("X25519")
    agreement.init(privKey)
    agreement.doPhase(pubKey, true)
    val sharedSecret = agreement.generateSecret()

    val hkdf = HKDFParameterSpec.builder(sharedSecret)
        .extractAndExpand(salt, "penik-e2ee-v1".toByteArray(), 32)
        .build()
    val derivedKey = SecretKeySpec(hkdf.outputKeyMaterial, "ChaCha20-Poly1305")

    val cipher = Cipher.getInstance("ChaCha20-Poly1305/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, derivedKey, GCMParameterSpec(128, nonce))
    return cipher.doFinal(ciphertext)
}
```

### 8.4 Шифрование бэкапа (Web JS)

```javascript
async function encryptBackup(privateKey, passphrase) {
    const enc = new TextEncoder();
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const keyMaterial = await crypto.subtle.importKey(
        'raw', enc.encode(passphrase), 'PBKDF2', false, ['deriveKey']
    );
    const key = await crypto.subtle.deriveKey(
        { name: 'PBKDF2', salt, iterations: 600000, hash: 'SHA-256' },
        keyMaterial,
        { name: 'AES-GCM', length: 256 },
        false,
        ['encrypt']
    );
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const encrypted = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        key,
        privateKey
    );
    return { encrypted: new Uint8Array(encrypted), salt, iv };
}
```

### 8.5 Генерация OTPK (Kotlin)

```kotlin
fun generatePreKeys(count: Int): List<PreKey> {
    return (1..count).map { i ->
        val keyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        PreKey(
            keyId = Random.nextLong(0, Long.MAX_VALUE),
            publicKey = keyPair.public.encoded,
            privateKey = keyPair.private.encoded
        )
    }
}

data class PreKey(
    val keyId: Long,
    val publicKey: ByteArray,
    val privateKey: ByteArray
)
```

### 8.6 Генерация OTPK (Web JS)

```javascript
async function generatePreKeys(count) {
    const keys = [];
    for (let i = 0; i < count; i++) {
        const keyPair = await crypto.subtle.generateKey(
            { name: "X25519" },
            true,
            ["deriveBits"]
        );
        const publicKey = new Uint8Array(await crypto.subtle.exportKey("raw", keyPair.publicKey));
        const privateKey = new Uint8Array(await crypto.subtle.exportKey("pkcs8", keyPair.privateKey));
        keys.push({
            key_id: crypto.getRandomValues(new BigInt64Array(1))[0],
            public_key: publicKey,
            private_key: privateKey
        });
    }
    return keys;
}
```

### 8.7 Генерация парольной фразы (Web JS)

```javascript
const BIP39_WORDS = [/* 2048 слов из wordlist.json */];

function generatePassphrase() {
    const array = new Uint32Array(4);
    crypto.getRandomValues(array);
    return Array.from(array)
        .map(n => BIP39_WORDS[n % 2048])
        .join(' ');
}
```

---

## 9. Safety Numbers (проверка ключей)

Safety numbers позволяют пользователям вручную проверить, что сервер не подменил публичные ключи.

### 9.1 Как это работает

1. Каждое устройство вычисляет fingerprint из своего Identity Key:
   ```
   fingerprint = SHA-512(ik_pub)
   ```
2. Fingerprint отображается в профиле как QR-код и набор цифр (12 групп по 5 цифр)
3. При встрече школьники сканируют QR друг друга и сравнивают fingerprints
4. Если fingerprints совпадают — ключи подлинные, сервер не подменял

### 9.2 Реализация

**Клиент (Kotlin/JS):**
```kotlin
fun computeSafetyNumber(ikPubA: ByteArray, ikPubB: ByteArray): String {
    // Сортируем ключи (A всегда меньше B)
    val (first, second) = if (ikPubA.contentCompare(ikPubB) < 0)
        Pair(ikPubA, ikPubB) else Pair(ikPubB, ikPubA)

    val hash = MessageDigest.getInstance("SHA-512")
        .digest(first + second)

    // Формат: 12 групп по 5 цифр
    return hash.take(30)
        .map { "%05d".format(it.toLong() % 100000) }
        .chunked(4)
        .joinToString("\n") { it.joinToString("  ") }
}
```

**UI:**
- Экран профиля → "Проверить безопасность" → QR-код + цифры
- Системное сообщение в чате: "Safety numbers совпадают" / "Safety numbers НЕ совпадают!"

---

## 10. Риски и ограничения

### 10.1 Риски

| Риск | Вероятность | Влияние | Митигация |
|---|---|---|---|
| Потеря приватного ключа = потеря доступа | Средняя | Критическое | Бэкап с парольной фразой |
| Сервер подменяет публичный ключ | Низкая | Критическое | Safety numbers |
| Replay старых сообщений | Низкая | Низкое | `client_msg_id` уникален |
| Бэкап скомпрометирован | Низкая | Критическое | Argon2id + AES-GCM |

### 10.2 Известные ограничения

1. **Partial PFS** — OTPK добавляют защиту, но Identity Key по-прежнему долгоживущий (компрометация IK раскрывает сообщения, отправленные без OTPK)
2. **Нет аутентификации ключей** — нет встроенной проверки принадлежности ключа (нужны safety numbers)
3. **Нет GROUP E2EE** — групповые чаты не поддерживаются
4. **Legacy** — старые сообщения видны как plaintext

### 10.3 Тестирование

**Unit-тесты:**
- Генерация ключей (длина 32 bytes)
- Round-trip: шифрование → дешифрование (IK и OTPK)
- HKDF: детерминированность
- Argon2id: время выполнения
- OTPK: генерация пула, использование, генерация нового

**Интеграционные:**
- Клиент A → Клиент B (OTPK → шифрование → дешифрование)
- Fallback на Identity Key (когда OTPK закончились)
- Self-fanout: Алиса (телефон) → Боб + Алиса (планшет)
- Оффлайн-пакет (OTPK → дешифрование)
- Мультидевайс (одно сообщение → 3 устройства: 2 получателя + 1 self)
- OTPK пул: исчерпание → REFILL_PREKEYS → генерация нового пула
- Бэкап/восстановление

---

## 11. Оценка времени

| Этап | Описание | Часы |
|---|---|---|
| 1 | Крипто-утилиты (Kotlin + JS + Go) | 8-10 |
| 2 | Хранение ключей + OTPK на клиенте | 6-8 |
| 3 | PreKey Bundle API + регистрация | 8-10 |
| 4 | Шифрование при отправке (self-fanout + OTPK) | 10-12 |
| 5 | Дешифрование при получении (OTPK + IK) | 8-10 |
| 6 | Бэкап и восстановление | 6-8 |
| 7 | Мультидевайсинг (self-fanout) | 6-8 |
| 8 | Миграция + тестирование | 4-6 |
| **Итого** | | **56-72 часов** |

### Рекомендуемый порядок

1. Этап 1 → 2 → 3 (фундамент: крипто + хранилище + PreKey Bundle API)
2. Этап 4 → 5 (шифрование/дешифрование с OTPK)
3. Этап 6 (бэкап)
4. Этап 7 (мультидевайсинг + self-fanout)
5. Этап 8 (миграция + тестирование)
