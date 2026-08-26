<div align="center">

<img src="logo_fixed.webp" alt="Penik Messenger" width="128" height="128">

# Penik Messenger

Мессенджер со сквозным шифрованием: Go-бэкенд, веб-клиент и Android-приложение

</div>

Мультиплатформенный мессенджер с E2EE: Go-бэкенд, веб-клиент на ванильном JS и Android-клиент на Jetpack Compose. Мульти-девайс, личные и групповые чаты, аудио/видеозвонки через LiveKit, стикеры с импортом из Telegram, собственное E2EE-хранилище вложений, бинарный WebSocket-протокол на MessagePack.

Сервер маршрутизирует шифртекст и не имеет доступа к содержимому сообщений: ключи генерируются и остаются на устройствах, на сервер уходят только публичные ключи и запечатанные конверты.

## Стек

| Часть | Технологии |
|-------|-----------|
| Сервер | Go 1.22, SQLite (`modernc.org/sqlite`, pure Go без CGo), `nhooyr.io/websocket`, MessagePack, Argon2id, GeoIP (MaxMind .mmdb), LiveKit Server SDK |
| Веб-клиент | Vanilla JS (ES-модули), Vite 8, libsodium-wrappers, WebCrypto, IndexedDB, Service Worker, LiveKit Client SDK |
| Android | Kotlin 2.2, Compose (Material 3), Hilt, Room + SQLCipher, Retrofit, OkHttp WebSocket, msgpack-core, Coil, Media3 ExoPlayer, LiveKit Android SDK |
| Криптография | X25519, HKDF, ChaCha20-Poly1305, AES-GCM, PBKDF2 (600k итераций), TOFU Key Pinning |

## Структура репозитория

```
Docs/            Подробная документация: REST API, WebSocket, Architecture, Calls
server/          Go-бэкенд: REST + WebSocket, SQLite, встроенная раздача веб-клиента
  cmd/server/    точка входа, embed собранного фронтенда
  internal/      config, db, handlers (auth, stickers, attachments, ws, call), middleware, ws
client/          Веб-клиент (Vite)
  js/            api, ws, crypto, groups, pairing, presence, call, app + ui/ (chat, stickers, call_modal)
  css/           стили
  sw.js          Service Worker: стриминг зашифрованной медиа через HTTP 206
android/         Android-клиент (Gradle, Compose)
  data/          network (api, ws), crypto, repository (stickers, attachment, auth, group), local
  ui/            screen (auth, chats, chatroom, groups, call), components (stickers, video, bubble)
plan/            Спецификации протоколов: api_protocol, e2ee_plan, groups_plan, android_client_plan
PROJECT_MAP.md   Индекс файлов проекта с описанием назначения каждого
AUDIT.md         Аудит криптографии клиента
SECURITY_AUDIT.md Аудит безопасности с реестром находок
```

Навигация по коду — через `PROJECT_MAP.md`: там перечислены все значимые файлы с описанием назначения.

## Быстрый старт

### Сервер

```bash
cd server
go mod tidy
go run ./cmd/server
```

Порт по умолчанию — **8143**. Схема SQLite применяется при старте автоматически, миграции тоже.

Сервер отдаёт собранный веб-клиент из `embed.FS`, поэтому для полноценного запуска фронтенд нужно собрать заранее:

```bash
cd server/cmd/server
go generate    # npm run build в client/ + копирование dist/
```

### Веб-клиент (dev)

```bash
cd client
npm install
npm run dev     # Vite dev-сервер
npm run build   # сборка в dist/ + копирование sw.js
```

### Android

```bash
cd android
./gradlew assembleDebug
```

`minSdk` 26, `targetSdk` 36, `compileSdk` 37.

## Конфигурация

Читается из переменных окружения; `.env` в корне или в `server/` подхватывается автоматически.

| Переменная | Дефолт | Описание |
|------------|--------|----------|
| `PORT` | `8143` | TCP-порт |
| `DB_PATH` | `./data/messenger.db` | Путь к файлу SQLite |
| `SESSION_TTL` | `720h` | Время жизни сессии (30 дней) |
| `MAX_AVATAR_SIZE` | `5242880` | Максимальный размер аватара, байт (5 МБ) |
| `MAX_BODY_SIZE` | `220200960` | Лимит тела запроса (~210 МБ для вложений) |
| `ALLOWED_ORIGINS` | — (обязательна) | Список origin через запятую. Wildcard запрещён: без явного списка сервер не стартует |
| `UPLOAD_DIR` | `./data/upload` | Каталог для аватаров, стикеров и зашифрованных вложений |
| `RELAY_TICKET_SECRET` | — | Секрет HMAC для авторизации загрузок через relay |
| `LIVEKIT_URL` / `LIVEKIT_FALLBACK_URL` | — | URL серверов LiveKit для 1:1 аудио/видеозвонков |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | — | API-ключи LiveKit |
| `GEOIP_DB_PATH` | — | Путь к MaxMind GeoLite2 City `.mmdb` для определения геолокации сессий |

## Архитектура

### Сквозное шифрование

**Личные сообщения.** У каждого устройства своя долговременная пара X25519 (identity key). Общий секрет выводится через X25519, из него по HKDF со случайной 32-байтной солью — ключ сообщения (`info: penik-pairwise-message-v1`), шифрование ChaCha20-Poly1305 со случайным nonce. Дополнительные аутентификационные данные (AAD v1) связывают отправителя, получателя и таймстемп кадра. Приватный ключ в вебе запечатан в IndexedDB, на Android — в зашифрованном SQLCipher/Keystore хранилище; на сервер уходит только публичная часть.

**Группы.** У каждой эпохи группы свой 32-байтный ключ. Он оборачивается отдельно под каждое устройство-получателя на парном X25519-секрете и складывается на сервер как непрозрачный конверт (`group_key_envelopes`). Ключ сообщения выводится из группового по HKDF (`info: penik-group-message-v1`), а `groupId`, версия ключа, id сообщения, транспортный отправитель (`sender_user_id`) и время привязываются как AAD v2 — это исключает подмену атрибуции сервером и переносы шифртекста между чатами. При смене состава группы ключ ротируется, конверты рассылаются заново.

**Бэкап ключей.** Приватный ключ шифруется парольной фразой: PBKDF2 (600 000 итераций) → AES-GCM. Сервер хранит только непрозрачный blob.

**Safety numbers и TOFU Pinning.** Отпечаток пары identity-ключей для ручной сверки собеседниками. Автоматический TOFU-пининг (Trust-On-First-Use) запоминает открытые ключи собеседников и предупреждает о смене ключей.

### Мульти-девайс и pairing

Новое устройство привязывается по QR-коду: создаётся pairing-сессия, существующее устройство шифрует историю на общем секрете и выгружает её пакетом, новое устройство забирает и расшифровывает. Пакеты истории имеют TTL и подчищаются фоновой задачей раз в минуту. Список активных устройств и их сессий доступен в настройках с возможностью удалённого отзыва (`/logout/all`).

### Стикеры

Поддерживаются как в Web, так и на Android:
- Просмотр каталога установленных паков и недавних стикеров (кэш до 32 штук).
- Импорт любых стикерпаков из Telegram по ссылке вида `https://t.me/addstickers/...` через Telegram Bot API.
- Отправка стикеров как E2EE JSON payload (`type: "sticker"`).
- Нативный рендеринг WebP/WebM стикеров без фонового пузыря сообщения (с наложением времени и статусов доставки/прочтения).
- Просмотр деталей пака и установка/удаление в один клик по стикеру в диалоге.

### Звонки 1:1 (LiveKit)

Аудио- и видеозвонки со сквозной сигнализацией через WebSocket (опкоды `0x30`–`0x36`):
- Звонок одновременно поступает на все активные устройства вызываемого пользователя (multi-device ring).
- При ответе на одном устройстве остальные получают кадр `CALL_TAKEN` и прекращают звонить.
- Автоматический failover на резервный LiveKit сервер при сбоях связи.

### Транспорт

REST под `/api/v1/` — регистрация, профили, ключи, группы, история, pairing, вложения, стикеры, устройства. Реалтайм — один бинарный WebSocket на `/api/v1/ws`, токен передаётся через `Sec-WebSocket-Protocol: access_token, <token>`.

Формат кадра: первый байт — опкод, остаток — MessagePack payload.

| Диапазон | Назначение |
|----------|-----------|
| `0x01`–`0x0b` | личные сообщения: отправка, доставка, ack, оффлайн-батч, ping/pong, удаление и очистка чата |
| `0x10`–`0x1e` | ключи, retry, прочтения, pairing, статусы, аватары, presence, shutdown |
| `0x20`–`0x28` | группы: сообщения, ack, доставка/прочтение, доступность ключа, смена состава, история, аватар |
| `0x30`–`0x36` | звонки: offer, incoming, accept/accepted, reject, end, «принято на другом устройстве» |

Точные структуры — в `server/internal/ws/protocol.go`, описание протокола — в `plan/api_protocol.md` и `Docs/WEBSOCKET.md`.

### Хранение

SQLite в режиме WAL с включёнными внешними ключами. Сессионные токены хранятся в виде криптографических SHA-256 хешей (`token_hash`). Основные таблицы: `users`, `devices`, `identity_keys`, `device_public_keys`, `chats`, `messages`, `sessions`, `key_backups`, `pairing_sessions`, `groups`, `group_members`, `group_key_versions`, `group_key_envelopes`, `group_messages`, `group_message_devices`, `group_history_packets`, `sticker_packs`, `stickers`, `user_stickers`. Канонический DDL — `server/internal/db/schema.sql`.

Аватары хранятся на диске в `UPLOAD_DIR` как WebP 256×256; при загрузке аватаров действует защита от декомпрессионных бомб (предварительное чтение заголовков через `image.DecodeConfig` и лимит габаритов).

### Вложения

Файлы предварительно шифруются на клиенте (ChaCha20-Poly1305) и загружаются напрямую на сервер (`POST /api/v1/attachments/upload`). Сервер сохраняет зашифрованные бинарные блобы на диск в `UPLOAD_DIR/attachments/` и отдаёт их по `GET /api/v1/attachments/file/:id` с поддержкой HTTP Range (`206 Partial Content`) для надёжной докачки. Ключи дешифрования передаются только внутри защищённых E2EE сообщений и никогда не попадают на сервер. В вебе Service Worker перехватывает `/sw-stream/<id>`, расшифровывает блоб на лету и отдаёт чанки плеерам для бесшовного воспроизведения и перемотки.

## Лимиты и защита

| Действие | Лимит |
|----------|-------|
| Регистрация / вход | по IP |
| Запрос key bundle | 60 / мин на пользователя |
| Групповые изменения | 30 / мин на пользователя |
| Ротация группового ключа | 10 / мин на пользователя |
| Запросы звонков | 10 / мин на пользователя |

Плюс глобальный лимит размера тела запроса, строгий Content-Security-Policy (CSP), CORS с проверкой origin и CSRF-защита в `server/internal/middleware/`.

## Тесты

```bash
# Тесты сервера
cd server
go test ./...

# Кросс-платформенные тесты криптографии (байт-в-байт AAD Web ↔ Android)
cd client
npm test
```

## Статус безопасности

Текущее состояние криптографической модели и известные ограничения:

- **Forward Secrecy не реализован**: в текущей версии протокола нет механизма Double Ratchet / ротации предключей (OTPK) на каждое сообщение. Компрометация приватного identity-ключа устройства раскрывает историю входящих сообщений этого устройства.
- **Аутентификация ключей**: реализован TOFU (Trust-On-First-Use) пининг публичных ключей собеседников с оповещением при их смене, а также 60-значные Safety Numbers для ручной сверки.
- **Защита контекста (AAD)**: личные сообщения используют AAD v1, а групповые сообщения — AAD v2 с авторитарной привязкой ID отправителя (`sender_user_id`), ID группы, эпохи ключа, ID сообщения и времени, что исключает подмену авторства сервером и replay-атаки между чатами.
- **Хранение секретов**:
  - **Web**: приватные identity и групповые ключи запечатаны в IndexedDB мастер-ключом WebCrypto (non-extractable vault).
  - **Android**: база данных Room защищена SQLCipher, приватные ключи хранятся в Keystore/EncryptedSharedPreferences.
  - **Сервер**: сессионные токены хранятся исключительно в виде SHA-256 хешей (`token_hash`). Реализован отзыв сессий (`/logout`) и удаленный сброс других сессий (`/logout/all`).
- **Сетевая защита**: реализована валидация габаритов изображений перед декодированием (защита от декомпрессионных бомб памяти), строгий Content-Security-Policy (CSP), CORS без wildcard и rate limiting на чувствительные операции.

## Документация

- [`Docs/README.md`](Docs/README.md) — Главный индекс и навигация по документации
- [`Docs/REST_API.md`](Docs/REST_API.md) — Подробная спецификация REST API
- [`Docs/WEBSOCKET.md`](Docs/WEBSOCKET.md) — Бинарный протокол WebSocket (опкоды 0x01–0x36)
- [`Docs/CALLS.md`](Docs/CALLS.md) — Архитектура и сигнализация LiveKit звонков
- [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) — Архитектура E2EE, группы, устройства, защищённые вложения и база данных
- [`PROJECT_MAP.md`](PROJECT_MAP.md) — Индекс исходников с назначением каждого файла
- [`SECURITY_AUDIT.md`](SECURITY_AUDIT.md) — Аудит безопасности с реестром находок
- [`AUDIT.md`](AUDIT.md) — Аудит криптографии клиента

