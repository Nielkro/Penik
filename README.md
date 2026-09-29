<div align="center">

<img src="logo_fixed.webp" alt="Penik Messenger" width="128" height="128">

# Penik Messenger

Мультиплатформенный мессенджер с облачными чатами и E2EE-звонками: Go-бэкенд, нативное Rust-ядро, веб-клиент и Android-приложение

</div>

Мультиплатформенный мессенджер: производительный Go-бэкенд, нативное криптографическое ядро на Rust, веб-клиент на ванильном JS/WebCrypto/WASM и Android-клиент на Jetpack Compose. Мульти-девайс, быстрые облачные личные и групповые чаты с мгновенной синхронизацией истории, сквозное шифрование 1:1 аудио/видеозвонков (X25519 DH + Safety Words) через LiveKit, стикеры с импортом из Telegram, вложения с поддержкой HTTP Range (206 Partial Content), бинарный WebSocket-протокол на MessagePack, динамическая смена идентичности приложения (Penik / Репик).

## Стек

| Часть | Технологии |
|-------|-----------|
| Сервер | Go 1.22, SQLite (`modernc.org/sqlite`, pure Go без CGo), `nhooyr.io/websocket`, MessagePack, Argon2id, GeoIP (MaxMind .mmdb), LiveKit Server SDK |
| Нативное криптоядро | Rust 2021 (`penik-crypto`), JNI FFI (`jni` crate), C-ABI exports, WebAssembly (`wasm-bindgen`), Zeroize (secure RAM wipe) |
| Веб-клиент | Vanilla JS (ES-модули), Vite 8, WebCrypto, WebRTC Insertable Streams, IndexedDB, LiveKit Client SDK |
| Android | Kotlin 2.2, Compose (Material 3), Hilt, Room, Retrofit, OkHttp WebSocket, msgpack-core, Coil, Media3 ExoPlayer, LiveKit Android SDK |
| Криптография звонков | Прямой эфемерный Diffie-Hellman (X25519), HKDF-SHA256, WebRTC FrameCryptor, кодовые слова безопасности (Safety Words) |

## Структура репозитория

```
Docs/            Подробная документация: REST API, WebSocket, Architecture, Calls
server/          Go-бэкенд: REST + WebSocket, SQLite, встроенная раздача веб-клиента
  cmd/server/    точка входа, embed собранного фронтенда (go generate)
  internal/      config, db, handlers, middleware, ws, push (FCM), stickers
rust/            Нативное ядро penik-crypto: X25519, KDF, Zeroize, JNI, C-ABI, WASM
scripts/         build_rust.sh (NDK cross-compilation под arm64-v8a, armeabi-v7a, x86_64, x86), fetch_crypto.sh (готовые .so/WASM из CI)
.github/         CI: сборка и публикация образов, Android APK и Desktop релизов
client/          Веб-клиент (Vite, WebRTC, WASM)
  js/            api, ws, groups, presence, call, sounds, storage, app + ui/
android/         Android-клиент (Gradle, Compose, WebRTC FrameCryptor)
  data/          network (api, ws, time), repository, local (Room)
  ui/            screen (auth, chats, chatroom, groups, calls, call overlay, settings), theme (AppIconManager)
landing/         Лендинг penik.ru
Dockerfile / docker-compose.yml / penik.caddy  упаковка и деплой сервера
PROJECT_MAP.md   Индекс файлов проекта с описанием назначения каждого
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
npm run build   # сборка в dist/
```

### Android

```bash
cd android
bash ./gradlew assembleDebug
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
| `UPLOAD_DIR` | `./data/upload` | Каталог для аватаров, стикеров и вложений |
| `LIVEKIT_URL` / `LIVEKIT_FALLBACK_URL` | — | URL серверов LiveKit для 1:1 аудио/видеозвонков |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | — | API-ключи LiveKit |
| `GEOIP_DB_PATH` | — | Путь к MaxMind GeoLite2 City `.mmdb` для определения геолокации сессий |

## Архитектура

### Облачные чаты

- **Личные и групповые сообщения:** Все сообщения хранятся на сервере в облачной базе данных с поддержкой мгновенной доставки через WebSocket, пагинации истории по `before_id` / `since_id`, редактирования, удаления и статусов доставки/прочтения.
- **Мгновенная синхронизация мульти-девайса:** Сообщения автоматически доставляются на все подключенные устройства пользователя в реальном времени. При входе с нового устройства вся история диалогов и групп становится доступна сразу без необходимости переноса ключей.
- **Боты:** Боты аутентифицируются по постоянному API-токену (`bot_<hex>`) и работают напрямую с облачным REST и WebSocket API, легко читая и отправляя текстовые сообщения.

### Сквозное шифрование звонков 1:1 (E2EE)

- **Прямой эфемерный Diffie-Hellman (X25519):** При установке звонка каждое устройство генерирует одноразовую пару ключей $X25519$. Публичные ключи безопасно пересылаются в кадре сигнализации `OpCallOffer` / `OpCallAccept`.
- **Вывод ключей:** На базе общего секрета $X25519$ через HKDF-SHA256 выводятся ключ шифрования медиа-фреймов WebRTC (Insertable Streams / FrameCryptor) и 4 проверочных слова (Safety Words).
- **Safety Words:** Собеседники могут сверить 4 кодовых слова на экранах звонка для защиты от атак Man-in-the-Middle.

### Динамическая идентичность (Penik / Репик)

Android-клиент поддерживает переключение названия и иконки лаунчера в настройках без переустановки:
- **Penik** — основная темная фирменная тема и брендинг.
- **Репик** — альтернативное название и маскировочная иконка приложения.
- Переключение реализовано через `activity-alias` в AndroidManifest и менеджер `AppIconManager`.

### Мульти-девайс и управление сессиями

Пользователь может входить со скольких угодно устройств. Список активных устройств и их сессий с платформами и геолокацией отображается в настройках. Доступен удаленный отзыв выбранного устройства или всех остальных устройств (`/logout/all`).

### Стикеры

Поддерживаются как в Web, так и на Android:
- Просмотр каталога установленных паков и недавних стикеров (кэш до 32 штук).
- Импорт любых стикерпаков из Telegram по ссылке вида `https://t.me/addstickers/...` через Telegram Bot API.
- Нативный рендеринг WebP/WebM стикеров без фонового пузыря сообщения (с наложением времени и статусов доставки/прочтения).
- Просмотр деталей пака и установка/удаление в один клик по стикеру в диалоге.

### Звонки 1:1 (LiveKit)

Аудио- и видеозвонки со сквозной сигнализацией через WebSocket (опкоды `0x30`–`0x39`):
- Звонок одновременно поступает на все активные устройства вызываемого пользователя (multi-device ring).
- При ответе на одном устройстве остальные получают кадр `CALL_TAKEN` (`0x36`) и прекращают звонить.
- `0x37` — запись в историю звонков, `0x38` — реплей состояния вернувшемуся устройству, `0x39` — состояние пира (обрыв/возврат связи).
- Автоматический failover на резервный LiveKit сервер при сбоях связи.

### Транспорт

REST под `/api/v1/` — регистрация, профили, синхронизация времени (`/api/v1/time`), группы, сообщения, история, вложения, стикеры, устройства, боты. Реалтайм — один бинарный WebSocket на `/api/v1/ws`, токен передаётся через `Sec-WebSocket-Protocol: access_token, <token>`.

Формат кадра: первый байт — опкод, остаток — MessagePack payload.

| Диапазон | Назначение |
|----------|-----------|
| `0x01`–`0x0f` | личные сообщения: отправка, доставка, ack, оффлайн-батч, ping/pong, удаление и очистка чата, правки, обновление профиля, смена устройств |
| `0x10`–`0x1f` | прочтения, статусы, аватары, presence, shutdown, typing |
| `0x20`–`0x2c` | группы: сообщения, ack, доставка/прочтение, смена состава, история, аватар, правки и удаления |
| `0x30`–`0x39` | звонки: offer, incoming, accept/accepted, reject, end, «принято на другом устройстве», log, state replay, peer state |

Точные структуры — в `server/internal/ws/protocol.go`, описание протокола — в `Docs/WEBSOCKET.md` (REST — `Docs/REST_API.md`).

### Хранение

SQLite в режиме WAL с включёнными внешними ключами. Сессионные токены хранятся в виде криптографических SHA-256 хешей (`token_hash`). Основные таблицы: `users`, `devices`, `chats`, `messages`, `sessions`, `groups`, `group_members`, `group_messages`, `group_message_devices`, `sticker_packs`, `stickers`, `user_sticker_packs`, `calls`, `attachments`, `bots`. Канонический DDL — `server/internal/db/schema.sql`.

Аватары хранятся на диске в `UPLOAD_DIR` как WebP 256×256; при загрузке аватаров действует защита от декомпрессионных бомб (предварительное чтение заголовков через `image.DecodeConfig` и лимит габаритов).

### Вложения

Файлы загружаются напрямую на сервер (`POST /api/v1/attachments/upload`). Сервер сохраняет бинарные блобы на диск в `UPLOAD_DIR/attachments/` и отдаёт их по `GET /api/v1/attachments/file/:id` с поддержкой HTTP Range (`206 Partial Content`) для надёжной докачки и стриминга видео/аудио.

## Лимиты и защита

| Действие | Лимит |
|----------|-------|
| Регистрация / вход | 10 / мин на IP |
| Проверка никнейма и публичный профиль | 20 / 10 мин на IP |
| Смена никнейма | не чаще 1 раза в 7 дней |
| Групповые изменения | 30 / мин на пользователя |
| Загрузка вложений | 60 / мин на пользователя |
| Исходящие звонки (`OpCallOffer`, WS) | 5 / мин на аккаунт |
| Кадры WebSocket | 10 кадров / 2 с на опкод (drop, затем close 1008) |

Плюс глобальный лимит размера тела запроса, строгий Content-Security-Policy (CSP), CORS с проверкой origin и CSRF-защита в `server/internal/middleware/`.

## Тестирование

```bash
# 1. Тесты Go-сервера
cd server && go test ./...

# 2. Проверка типов Web-клиента
cd client && npm run typecheck

# 3. Компиляция Android-клиента
cd android && bash ./gradlew compileDebugKotlin
```

## Документация

- [`Docs/README.md`](Docs/README.md) — Главный индекс и навигация по документации
- [`Docs/REST_API.md`](Docs/REST_API.md) — Подробная спецификация REST API
- [`Docs/WEBSOCKET.md`](Docs/WEBSOCKET.md) — Бинарный протокол WebSocket (опкоды 0x01–0x39)
- [`Docs/CALLS.md`](Docs/CALLS.md) — Архитектура и сигнализация LiveKit звонков
- [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) — Архитектура чатов, звонков, устройств, вложений и базы данных
- [`PROJECT_MAP.md`](PROJECT_MAP.md) — Индекс исходников с назначением каждого файла
- [`SECURITY_AUDIT.md`](SECURITY_AUDIT.md) — Аудит безопасности с реестром находок
