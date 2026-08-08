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
| `MAX_AVATAR_SIZE`| `102400`                | Макс размер аватара в байтах    |

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
  "ik_pub": "<base64>",
  "spk_pub": "<base64>",
  "spk_sig": "<base64>",
  "opk_list": ["<base64>", ...]   // одноразовые ключи (рекомендуется 100)
}

POST /api/v1/login
{
  "nickname": "ivan_petrov",
  "password": "...",
  "device_name": "Pixel 8",
  "ik_pub": "<base64>",
  "spk_pub": "<base64>",
  "spk_sig": "<base64>"
}
→ { "token": "...", "user_id": 1, "device_id": 1 }
```

### Профиль

```
GET  /api/v1/users/search?q=ivan&limit=20   // поиск по нику и имени
GET  /api/v1/users/:id                       // профиль пользователя
PUT  /api/v1/users/me/name                   // { "name": "..." }  rate limit: 10/час
PUT  /api/v1/users/me/nickname               // { "nickname": "..." }  кулдаун: 7 дней
PUT  /api/v1/avatar                          // multipart, WebP, ≤100KB → сохраняется 128×128
GET  /api/v1/avatar/:user_id
```

### WebSocket

```
WS /api/v1/ws
Передача токена через Sec-WebSocket-Protocol: access_token, <token>
```

Бинарный протокол: первый байт — опкод, остаток — MessagePack payload.

| Опкод | Направление     | Тип              | Поля                                                                 |
|-------|-----------------|------------------|----------------------------------------------------------------------|
| 0x01  | клиент → сервер | MSG_SEND         | `to_user_id`, `cipher_bytes`, `msg_id`                               |
| 0x02  | сервер → клиент | MSG_RECV         | `from_user_id`, `from_device_id`, `cipher_bytes`, `msg_id`, `ts`    |
| 0x03  | сервер → клиент | MSG_ACK          | `msg_id`                                                             |
| 0x04  | клиент → сервер | MSG_DELIVERED    | `msg_id`                                                             |
| 0x05  | сервер → клиент | OFFLINE_BATCH    | `msgs[]` — при подключении                                           |
| 0x06  | оба             | PING             | —                                                                    |
| 0x07  | оба             | PONG             | —                                                                    |
## База данных

SQLite с WAL режимом. Миграции применяются при старте автоматически.

Таблицы: `users`, `devices`, `chats`, `messages`, `sessions`

## Rate limits

| Действие          | Лимит              |
|-------------------|--------------------|
| Смена имени       | 10 раз / час       |
| Смена никнейма    | 1 раз / 7 дней     |
| Загрузка аватара  | 5 раз / час        |
| Поиск             | 30 запросов / мин  |
