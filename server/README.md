# Penik Messenger — сервер

Go backend для Penik Messenger. Мульти-девайс, бинарный WebSocket (MessagePack).

## Стек

- **Go 1.22+**
- **SQLite** — `modernc.org/sqlite` (pure Go, без CGo)
- **WebSocket** — `nhooyr.io/websocket`, бинарные фреймы
- **MessagePack** — `github.com/shamaton/msgpack/v2`
- **Пароли** — Argon2id

## Запуск

```bash
go mod tidy
go build ./...
go run ./cmd/server
```

Дефолтный порт: **8143**

## Конфигурация (env)

| Переменная       | Дефолт                  | Описание                        |
|------------------|-------------------------|---------------------------------|
| `PORT`           | `8143`                  | TCP порт                        |
| `DB_PATH`        | `./data/messenger.db`   | Путь к SQLite файлу             |
| `SESSION_TTL`    | `720h`                  | Время жизни сессии (30 дней)    |
| `MAX_AVATAR_SIZE`| `5242880`               | Макс размер аватара в байтах (5 МиБ) |

```bash
PORT=8143 DB_PATH=/var/lib/messenger/db.sqlite go run ./cmd/server
```

## API

### Auth

```
POST /api/v1/register
{
  "name": "Иван Петров",
  "nickname": "ivan_petrov",      // без @, только a-z0-9_
  "password": "...",
  "device_name": "Pixel 8",
  "platform": "Android 14",
  "location": "Moscow",
  "crypto_version": 2,
  "ik_pub": "<base64>",
  "signing_key": "<base64>"
  // опционально: spk_pub, spk_sig (легаси, обратная совместимость).
  // opk_list / OTK не принимаются — OTK deprecated (см. AGENTS.md).
}

POST /api/v1/login
{
  "nickname": "ivan_petrov",
  "password": "...",
  "device_name": "Pixel 8",
  "platform": "Android 14",
  "location": "Moscow",
  "crypto_version": 2,
  "ik_pub": "<base64>",
  "signing_key": "<base64>"
}
→ { "token": "...", "user_id": 1, "device_id": 1,
    // опционально при временном устройстве:
    // "rebind_required": true, "target_device_id": 2
    // далее POST /api/v1/auth/device-challenge → /api/v1/auth/device-rebind
  }
```

### Профиль

```
GET  /api/v1/users/search?q=ivan&limit=20   // поиск по нику и имени; online/last_seen — только при взаимном контакте
GET  /api/v1/users/:id                       // профиль пользователя
PUT  /api/v1/users/me/name                   // { "name": "..." }
PUT  /api/v1/users/me/nickname               // { "nickname": "..." }  кулдаун: 7 дней
PUT  /api/v1/avatar                          // multipart, WebP/PNG/JPEG, ≤MAX_AVATAR_SIZE (5 МиБ) → WebP 256×256
GET  /api/v1/avatar/:user_id
```

### WebSocket

```
WS /api/v1/ws
Передача токена через Sec-WebSocket-Protocol: access_token, <token>
```

Бинарный протокол: первый байт — опкод (`0x01`–`0x39`), остаток — MessagePack payload. Полный справочник опкодов и структур payload — в [`Docs/WEBSOCKET.md`](../Docs/WEBSOCKET.md), канонические типы — в `server/internal/ws/protocol.go`.

## База данных

SQLite с WAL режимом. Миграции применяются при старте автоматически.

Таблицы: `users`, `devices`, `identity_keys`, `device_public_keys`, `chats`, `messages`, `sessions`, `key_backups`, `pairing_sessions`, `pairing_tokens`, `device_history_exclusions`, `groups`, `group_members`, `group_key_versions`, `group_key_envelopes`, `group_messages`, `group_message_devices`, `group_history_packets`, `sticker_packs`, `stickers`, `user_sticker_packs`, `calls`, `attachments`, `bots`. Канонический DDL — `server/internal/db/schema.sql`.

## Rate limits

Список синхронизирован с `README.md` и `Docs/REST_API.md`:

| Действие | Лимит |
|----------|-------|
| Регистрация / вход | 10 / мин на IP |
| Проверка никнейма и публичный профиль | 20 / 10 мин на IP |
| Смена никнейма | не чаще 1 раза в 7 дней |
| Запрос key bundle | 60 / мин на пользователя |
| Групповые изменения | 30 / мин на пользователя |
| Ротация группового ключа | 10 / мин на пользователя |
| Загрузка вложений | 60 / мин на пользователя |
| Device challenge (rebind устройства) | 5 / мин на пользователя |
| Исходящие звонки (`OpCallOffer`, WS) | 5 / мин на аккаунт |
| Кадры WebSocket | 10 кадров / 2 с на опкод (drop, затем close 1008) |
