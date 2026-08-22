<div align="center">

<img src="logo_fixed.webp" alt="Penik Messenger" width="128" height="128">

# Penik Messenger

Мессенджер со сквозным шифрованием: Go-бэкенд, веб-клиент и Android-приложение

</div>

Мультиплатформенный мессенджер с E2EE: Go-бэкенд, веб-клиент на ванильном JS и Android-клиент на Compose. Мульти-девайс, личные и групповые чаты, бинарный WebSocket-протокол на MessagePack.

Сервер маршрутизирует шифртекст и не имеет доступа к содержимому сообщений: ключи генерируются и остаются на устройствах, на сервер уходят только публичные ключи и запечатанные конверты.

## Стек

| Часть | Технологии |
|-------|-----------|
| Сервер | Go 1.22, SQLite (`modernc.org/sqlite`, pure Go без CGo), `nhooyr.io/websocket`, MessagePack, Argon2id |
| Веб-клиент | Vanilla JS (ES-модули), Vite 8, libsodium-wrappers, WebCrypto, IndexedDB, Service Worker |
| Android | Kotlin 2.2, Compose (Material 3), Hilt, Room + SQLCipher, Retrofit, OkHttp WebSocket, msgpack-core |
| Криптография | X25519, HKDF, ChaCha20-Poly1305, AES-GCM, PBKDF2 (600k итераций) |

## Структура репозитория

```
Docs/            Подробная документация: REST API, WebSocket, Architecture
server/          Go-бэкенд: REST + WebSocket, SQLite, встроенная раздача веб-клиента
  cmd/server/    точка входа, embed собранного фронтенда
  internal/      config, db, handlers, middleware, models, ws
client/          Веб-клиент (Vite)
  js/            api, ws, crypto, groups, pairing, presence, app + ui/
  css/           стили
  sw.js          Service Worker: стриминг зашифрованной медиа через HTTP 206
android/         Android-клиент (Gradle, Compose)
Developments/    Вспомогательные утилиты: Vkproxy, VkUpload, sqtt
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
| `MAX_AVATAR_SIZE` | `5242880` | Максимальный размер аватара, байт |
| `MAX_BODY_SIZE` | `220200960` | Лимит тела запроса (~200 МБ для вложений) |
| `ALLOWED_ORIGINS` | — (обязательна) | Список origin через запятую. Wildcard запрещён: без явного списка сервер не стартует |
| `UPLOAD_DIR` | `./data/upload` | Каталог для аватаров |
| `VK_BOT_TOKEN` | — | Токен VK для загрузки вложений в VK CDN |

## Архитектура

### Сквозное шифрование

**Личные сообщения.** У каждого устройства своя долговременная пара X25519 (identity key). Общий секрет выводится через X25519, из него по HKDF со случайной 32-байтной солью — ключ сообщения (`info: penik-pairwise-message-v1`), шифрование ChaCha20-Poly1305 со случайным nonce. Приватный ключ в вебе лежит в IndexedDB, на Android — в зашифрованном хранилище; на сервер уходит только публичная часть.

**Группы.** У каждой эпохи группы свой 32-байтный ключ. Он оборачивается отдельно под каждое устройство-получателя на парном X25519-секрете и складывается на сервер как непрозрачный конверт (`group_key_envelopes`). Ключ сообщения выводится из группового по HKDF (`info: penik-group-message-v1`), а `groupId`, версия ключа, id сообщения и время привязываются как AAD — это исключает переносы шифртекста между контекстами. При смене состава группы ключ ротируется, конверты рассылаются заново.

**Бэкап ключей.** Приватный ключ шифруется парольной фразой: PBKDF2 (600 000 итераций) → AES-GCM. Сервер хранит только непрозрачный blob.

**Safety numbers.** Отпечаток пары identity-ключей для ручной сверки собеседниками — защита от подмены ключей сервером.

### Мульти-девайс и pairing

Новое устройство привязывается по QR-коду: создаётся pairing-сессия, существующее устройство шифрует историю на общем секрете и выгружает её пакетом, новое устройство забирает и расшифровывает. Пакеты истории имеют TTL и подчищаются фоновой задачей раз в минуту.

### Транспорт

REST под `/api/v1/` — регистрация, профили, ключи, группы, история, pairing, вложения. Реалтайм — один бинарный WebSocket на `/api/v1/ws`, токен передаётся через `Sec-WebSocket-Protocol: access_token, <token>`.

Формат кадра: первый байт — опкод, остаток — MessagePack payload.

| Диапазон | Назначение |
|----------|-----------|
| `0x01`–`0x0b` | личные сообщения: отправка, доставка, ack, оффлайн-батч, ping/pong, удаление и очистка чата |
| `0x10`–`0x1e` | ключи, retry, прочтения, pairing, статусы, аватары, presence, shutdown |
| `0x20`–`0x28` | группы: сообщения, ack, доставка/прочтение, доступность ключа, смена состава, история, аватар |
| `0x30`–`0x36` | звонки: offer, incoming, accept/accepted, reject, end, «принято на другом устройстве» |

Точные структуры — в `server/internal/ws/protocol.go`, описание протокола — в `plan/api_protocol.md`.

### Хранение

SQLite в режиме WAL с включёнными внешними ключами. Основные таблицы: `users`, `devices`, `identity_keys`, `device_public_keys`, `chats`, `messages`, `sessions`, `key_backups`, `pairing_sessions`, `groups`, `group_members`, `group_key_versions`, `group_key_envelopes`, `group_messages`, `group_message_devices`, `group_history_packets`. Канонический DDL — `server/internal/db/schema.sql`.

Аватары хранятся на диске в `UPLOAD_DIR` как WebP 256×256; при старте выполняется миграция старых аватаров из БД в файлы с последующим `VACUUM`.

### Вложения

Файлы шифруются на клиенте (ChaCha20-Poly1305) и заливаются через сервер в VK CDN по VK Docs API (`POST /api/v1/attachments/vk-upload`). Скачивание идёт через `GET /api/v1/attachments/proxy` — прокси нужен, чтобы обойти CORS у CDN. Крупные медиа веб-клиент отдаёт плееру через Service Worker: он перехватывает `/sw-stream/<id>`, расшифровывает на лету и отвечает `206 Partial Content`, так что видео проигрывается без полной загрузки в память.

## Лимиты и защита

| Действие | Лимит |
|----------|-------|
| Регистрация / вход | по IP |
| Запрос key bundle | 60 / мин на пользователя |
| Групповые изменения | 30 / мин на пользователя |
| Ротация группового ключа | 10 / мин на пользователя |

Плюс глобальный лимит размера тела запроса, CORS с проверкой origin и CSRF-защита в `server/internal/middleware/`.

## Тесты

```bash
cd server
go test ./...
```

Покрыты: handlers (auth, groups, group_keys, group_history), ws (client, group, presence), db, middleware (rate limit).

## Статус безопасности

Известные ограничения текущей версии:

- **Forward secrecy не реализован** — ни в личных, ни в групповых чатах. Компрометация identity-ключа раскрывает всю переписку. Одноразовые предключи (OTPK) присутствуют в схеме, но не используются — мёртвый код под удаление.
- Сессии живут 30 дней, отзыв не реализован.
- Энтропия backup-фразы низкая (~48 бит) — требуется увеличить длину.
- Токен авторизации в вебе хранится в localStorage.
- Сервер технически способен подменить публичные ключи; смягчено safety numbers, но проверка ручная.

Не используйте проект там, где эти ограничения критичны.

## Документация

- [`Docs/README.md`](Docs/README.md) — Главный индекс и навигация по документации
- [`Docs/REST_API.md`](Docs/REST_API.md) — Подробная спецификация REST API
- [`Docs/WEBSOCKET.md`](Docs/WEBSOCKET.md) — Бинарный протокол WebSocket (опкоды 0x01–0x36)
- [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) — Архитектура E2EE, группы, устройства, VK CDN и база данных
- `PROJECT_MAP.md` — Индекс исходников с назначением каждого файла
- `plan/api_protocol.md` — REST и WebSocket-протокол
- `plan/e2ee_plan.md` — схема сквозного шифрования
- `plan/groups_plan.md` — групповая криптография и ротация ключей
- `plan/android_client_plan.md` — архитектура Android-клиента
- `server/README.md` — детали серверного API

