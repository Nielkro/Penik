<div align="center">

<img src="logo_fixed.webp" alt="Penik Messenger" width="128" height="128">

# Penik Messenger

Мессенджер со сквозным шифрованием: Go-бэкенд, нативное Rust-ядро, веб-клиент и Android-приложение

</div>

Мультиплатформенный мессенджер с E2EE: производительный Go-бэкенд, нативное криптографическое ядро на Rust, веб-клиент на ванильном JS/WebCrypto/WASM и Android-клиент на Jetpack Compose с JNI-интеграцией. Мульти-девайс, личные и групповые чаты, аудио/видеозвонки через LiveKit, стикеры с импортом из Telegram, собственное E2EE-хранилище вложений, бинарный WebSocket-протокол на MessagePack, динамическая смена идентичности приложения (Penik / Репик).

Сервер маршрутизирует шифртекст и не имеет доступа к содержимому сообщений: ключи генерируются и остаются на устройствах, на сервер уходят только публичные ключи и запечатанные конверты.

## Стек

| Часть | Технологии |
|-------|-----------|
| Сервер | Go 1.22, SQLite (`modernc.org/sqlite`, pure Go без CGo), `nhooyr.io/websocket`, MessagePack, Argon2id, GeoIP (MaxMind .mmdb), LiveKit Server SDK |
| Нативное криптоядро | Rust 2021 (`penik-crypto`), JNI FFI (`jni` crate), C-ABI exports, WebAssembly (`wasm-bindgen`), Zeroize (secure RAM wipe) |
| Веб-клиент | Vanilla JS (ES-модули), Vite 8, libsodium-wrappers, WebCrypto, IndexedDB, Service Worker (HTTP 206 streaming), LiveKit Client SDK |
| Android | Kotlin 2.2, Compose (Material 3), Hilt, Room + SQLCipher, JNI Rust Crypto Core, Retrofit, OkHttp WebSocket, msgpack-core, Coil, Media3 ExoPlayer, LiveKit Android SDK |
| Криптография | X25519, HKDF, ChaCha20-Poly1305, AES-GCM, PBKDF2 (600k итераций в Rust), Zeroize очистка RAM, TOFU Key Pinning, Pairwise AAD v2 |

## Структура репозитория

```
Docs/            Подробная документация: REST API, WebSocket, Architecture, Calls
server/          Go-бэкенд: REST + WebSocket, SQLite, встроенная раздача веб-клиента
  cmd/server/    точка входа, embed собранного фронтенда (go generate)
  internal/      config, db, handlers, middleware, ws, push (FCM), stickers
rust/            Нативное ядро penik-crypto: X25519, ChaCha20-Poly1305, KDF, Zeroize, JNI, C-ABI, WASM
scripts/         build_rust.sh (NDK cross-compilation под arm64-v8a, armeabi-v7a, x86_64, x86), fetch_crypto.sh (готовые .so/WASM из CI), run_e2e.py (запуск E2E)
.github/         CI: crypto.yml (сборка WASM + .so под 4 ABI, релиз crypto-latest)
client/          Веб-клиент (Vite, WebCrypto, WASM)
  js/            api, ws, crypto, groups, pairing, presence, call, sounds, storage, vault, wordcoder, pinning, app + ui/
  sw.js          Service Worker: стриминг зашифрованной медиа через HTTP 206
  scripts/       build-sw.js (штамп версии service worker)
android/         Android-клиент (Gradle, Compose, JNI Rust Crypto)
  data/          network (api, ws, time), crypto (RustCryptoCore, E2EE, GroupCrypto), repository, local (Room + SQLCipher)
  ui/            screen (auth, chats, chatroom, groups, calls, call overlay, settings, pairing), theme (AppIconManager)
  jniLibs/       готовые libpenik_crypto.so (не в гите, через fetch_crypto.sh или build_rust.sh)
landing/         Лендинг penik.ru
Dockerfile / docker-compose.yml / penik.caddy  упаковка и деплой сервера
tests/           E2E и кросс-языковые тесты криптографии (Python, Rust, JS)
plan/            Спецификации протоколов: api_protocol, e2ee_plan, groups_plan, android_client_plan, micro_rust_core_plan, new_device_key_invalidation_plan
PROJECT_MAP.md   Индекс файлов проекта с описанием назначения каждого
SECURITY_AUDIT.md Аудит безопасности с реестром находок
```

Навигация по коду — через `PROJECT_MAP.md`: там перечислены все значимые файлы с описанием назначения.

## Быстрый старт

### Нативное криптоядро (Rust / Android NDK)

Скрипт кросс-компилирует библиотеки `libpenik_crypto.so` для 4 целевых платформ Android (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) и копирует их в `android/app/src/main/jniLibs/`:

```bash
bash scripts/build_rust.sh
```

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
| `UPLOAD_DIR` | `./data/upload` | Каталог для аватаров, стикеров и зашифрованных вложений |
| `RELAY_TICKET_SECRET` | — | Секрет HMAC для авторизации загрузок через relay |
| `LIVEKIT_URL` / `LIVEKIT_FALLBACK_URL` | — | URL серверов LiveKit для 1:1 аудио/видеозвонков |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | — | API-ключи LiveKit |
| `GEOIP_DB_PATH` | — | Путь к MaxMind GeoLite2 City `.mmdb` для определения геолокации сессий |

## Архитектура

### Сквозное шифрование

- **Личные сообщения (AAD v2):** У каждого устройства своя долговременная пара X25519 (identity key). Общий секрет выводится через X25519, из него по HKDF со случайной 32-байтной солью — ключ сообщения (`info: penik-pairwise-message-v1`), шифрование ChaCha20-Poly1305 со случайным nonce. Аутентификационные данные (AAD v2: `["2", sender, recipient, client_msg_id]`) связывают участников и ID сообщения без привязки к локальному времени устройств, что исключает ошибки расшифровки при рассинхронизации часов. Поддерживается обратная совместимость с сообщениями AAD v1.
- **Группы:** У каждой эпохи группы свой 32-байтный ключ. Он оборачивается отдельно под каждое устройство-получателя на парном X25519-секрете и складывается на сервер как непрозрачный конверт (`group_key_envelopes`). Ключ сообщения выводится из группового по HKDF (`info: penik-group-message-v1`), а `groupId`, версия ключа, id сообщения, транспортный отправитель (`sender_user_id`) и время привязываются как AAD v2 — это исключает подмену авторства сервером и переносы шифртекста между чатами. При смене состава группы ключ ротируется.
- **Нативное ядро Rust & Zeroize:** Все тяжелые и критические криптооперации на Android (X25519, ChaCha20-Poly1305, HKDF, PBKDF2, AAD, Safety Numbers) выполняются через нативные JNI-биндинги `penik-crypto` с автоматическим fallback на Kotlin. Память приватных ключей при уничтожении объектов очищается нулями (`zeroize`). Разблокировка бэкапа ключей через нативный Rust PBKDF2 (600 000 итераций) происходит в 10–20 раз быстрее.
- **Синхронизация времени:** Сервер предоставляет эндпоинт `GET /api/v1/time`. Клиенты (Android и Web) при старте и реконнекте калибруют локальное смещение времени относительно сервера с компенсацией половины RTT. Сервер клэмпит время входящих сообщений (`msgTS <= now`) и атомарно обновляет `devices.last_seen`, гарантируя, что статус присутствия никогда не отстает от времени отправленных сообщений.
- **Бэкап ключей:** Приватный ключ шифруется парольной фразой: PBKDF2 (600 000 итераций) → AES-GCM. Сервер хранит только непрозрачный blob.
- **Safety numbers и TOFU Pinning:** Отпечаток пары identity-ключей для ручной сверки собеседниками. Автоматический TOFU-пининг (Trust-On-First-Use) запоминает открытые ключи собеседников и предупреждает о смене ключей.

### Динамическая идентичность (Penik / Репик)

Android-клиент поддерживает переключение названия и иконки лаунчера в настройках без переустановки:
- **Penik** — основная темная фирменная тема и брендинг.
- **Репик** — альтернативное название и маскировочная иконка приложения.
- Переключение реализовано через `activity-alias` в AndroidManifest и менеджер `AppIconManager`.

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

Аудио- и видеозвонки со сквозной сигнализацией через WebSocket (опкоды `0x30`–`0x39`):
- Звонок одновременно поступает на все активные устройства вызываемого пользователя (multi-device ring).
- При ответе на одном устройстве остальные получают кадр `CALL_TAKEN` (`0x36`) и прекращают звонить.
- `0x37` — запись в историю звонков, `0x38` — реплей состояния вернувшемуся устройству, `0x39` — состояние пира (обрыв/возврат связи).
- Автоматический failover на резервный LiveKit сервер при сбоях связи.

### Транспорт

REST под `/api/v1/` — регистрация, профили, синхронизация времени (`/api/v1/time`), ключи, группы, история, pairing, вложения, стикеры, устройства. Реалтайм — один бинарный WebSocket на `/api/v1/ws`, токен передаётся через `Sec-WebSocket-Protocol: access_token, <token>`.

Формат кадра: первый байт — опкод, остаток — MessagePack payload.

| Диапазон | Назначение |
|----------|-----------|
| `0x01`–`0x0e` | личные сообщения: отправка, доставка, ack, оффлайн-батч, ping/pong, удаление и очистка чата, правки, обновление профиля |
| `0x10`–`0x1f` | ключи, retry, прочтения, pairing, статусы, аватары, presence, shutdown, typing |
| `0x20`–`0x29` | группы: сообщения, ack, доставка/прочтение, доступность ключа, смена состава, история, аватар, правки |
| `0x30`–`0x39` | звонки: offer, incoming, accept/accepted, reject, end, «принято на другом устройстве», log, state replay, peer state |

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

## Тестирование

```bash
# 1. Тесты Go-сервера
cd server && go test ./...

# 2. Тесты Rust-криптоядра
cd rust/penik-crypto && cargo test

# 3. Кросс-платформенная сверка криптографии (Rust ↔ Python стандарты RFC 7748 / 8439 / 2898)
python3 tests/e2e/test_crypto_core.py

# 4. Кросс-платформенные тесты криптографии веб-клиента (JS + Rust WASM)
node client/js/crypto.test.js
node client/js/groups.crypto.test.js

# 5. Полный E2E прогон (поднимает эфемерный сервер, 34 проверки)
python3 scripts/run_e2e.py
```

## Статус безопасности

Текущее состояние криптографической модели и известные ограничения:

- **Forward Secrecy не реализован**: в текущей версии протокола нет механизма Double Ratchet / ротации предключей (OTPK) на каждое сообщение. Компрометация приватного identity-ключа устройства раскрывает историю входящих сообщений этого устройства.
- **Аутентификация ключей**: реализован TOFU (Trust-On-First-Use) пининг публичных ключей собеседников с оповещением при их смене, а также 60-значные Safety Numbers для ручной сверки.
- **Защита контекста (AAD)**: личные сообщения используют AAD v2 (независимый от времени, с обратной совместимостью с v1), а групповые сообщения — AAD v2 с авторитарной привязкой ID отправителя (`sender_user_id`), ID группы, эпохи ключа, ID сообщения и времени, что исключает подмену авторства сервером и replay-атаки между чатами.
- **Защита RAM**: приватные ключи и промежуточные секреты в нативной памяти Android/Rust очищаются нулями перед освобождением через crate `zeroize`.
- **Хранение секретов**:
  - **Web**: приватные identity и групповые ключи запечатаны в IndexedDB мастер-ключом WebCrypto (non-extractable vault).
  - **Android**: база данных Room защищена SQLCipher, приватные ключи хранятся в Keystore/EncryptedSharedPreferences.
  - **Сервер**: сессионные токены хранятся исключительно в виде SHA-256 хешей (`token_hash`). Реализован отзыв сессий (`/logout`) и удаленный сброс других сессий (`/logout/all`).
- **Сетевая защита**: валидация габаритов изображений перед декодированием (защита от декомпрессионных бомб памяти), строгий Content-Security-Policy (CSP), CORS без wildcard, клэмп будущих таймстампов и rate limiting на чувствительные операции.

## Документация

- [`Docs/README.md`](Docs/README.md) — Главный индекс и навигация по документации
- [`Docs/REST_API.md`](Docs/REST_API.md) — Подробная спецификация REST API
- [`Docs/WEBSOCKET.md`](Docs/WEBSOCKET.md) — Бинарный протокол WebSocket (опкоды 0x01–0x39)
- [`Docs/CALLS.md`](Docs/CALLS.md) — Архитектура и сигнализация LiveKit звонков
- [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) — Архитектура E2EE, группы, устройства, защищённые вложения и база данных
- [`PROJECT_MAP.md`](PROJECT_MAP.md) — Индекс исходников с назначением каждого файла
- [`SECURITY_AUDIT.md`](SECURITY_AUDIT.md) — Аудит безопасности с реестром находок
