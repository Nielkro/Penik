# АУДИТ БЕЗОПАСНОСТИ: SECURITY_AUDIT.md

## 1. Общая оценка

Проект содержит существенный набор защитных механизмов: Argon2id для паролей, криптографически случайные сессионные токены, E2EE на X25519/HKDF/ChaCha20-Poly1305, авторизацию REST и WebSocket, ограничения размеров запросов и кадров, rate limiting, allowlist для прокси вложений, а также защищённое хранилище и SQLCipher на Android.

При этом до развёртывания в чувствительной production-среде необходимо устранить логирование HTTP-тел в Android release-сборке. Дополнительного внимания требуют небезопасные значения CORS/WebSocket по умолчанию, неявное определение схемы WebSocket на Android и архитектурные ограничения E2EE.

## 2. Критические уязвимости

### 2.1 Android логирует тела всех HTTP-запросов и ответов

- **Описание:** В `android/app/src/main/java/niel/kro/penik/data/di/Modules.kt` подключён `HttpLoggingInterceptor`. Этот же клиент добавляет Bearer-токен.
- **Уровень риска:** Высокий
- **Последствия:** В системные и диагностические логи могут попасть пароли, Bearer-токены, зашифрованные бэкапы, ключевой материал и метаданные сообщений.
- **Статус:** Исправлено. Уровень логирования вычисляется через `NetworkModule.httpLogLevel(BuildConfig.DEBUG)`: `BODY` только в debug-сборке, `NONE` в release. Поведение зафиксировано юнит-тестом `HttpLogLevelTest`.

### 2.2 CORS и WebSocket разрешают любой origin по умолчанию

- **Описание:** `server/internal/config/config.go:32` задаёт `ALLOWED_ORIGINS=*` по умолчанию. В `server/internal/middleware/cors.go:23-27` это приводит к `Access-Control-Allow-Origin: *`, а `server/internal/handlers/ws.go:34-39` отключает проверку origin для WebSocket.
- **Уровень риска:** Высокий
- **Последствия:** При ошибочной production-конфигурации сторонние origin смогут обращаться к API и WebSocket с доступным токеном; CSRF-проверка также не включается для wildcard-конфигурации.
- **Статус:** Исправлено. Добавлена переменная `ENV`; `Config.Validate()` в production требует явный, не-wildcard, HTTPS-only список `ALLOWED_ORIGINS` и вызывается при старте (`main.go`), иначе сервер отказывается запускаться (fail-closed). Wildcard остаётся допустимым только в development. Покрыто тестами `config_test.go`.

### 2.3 Android WebSocket: неявное определение схемы

- **Описание:** В `android/app/src/main/java/niel/kro/penik/data/network/websocket/WebSocketManager.kt:271-278` схема `wss` выбирается только для порта `443`; для остальных портов используется `ws`.
- **Уровень риска:** Низкий / Средний (зависит от конфигурации)
- **Последствия:** Если production-сервер использует TLS на порту, отличном от `443`, а клиент подключается к нему, будет использован plaintext WebSocket.
- **Статус:** Требует рефакторинга (не критично)

## 3. Найденные решения

- Пароли хэшируются Argon2id со случайной солью: `server/internal/handlers/auth.go:47-54,336-370`.
- Сессии используют 32 случайных байта из `crypto/rand`, а срок действия проверяется middleware: `server/internal/handlers/auth.go:376-383`, `server/internal/middleware/auth.go:30-46`.
- REST-маршруты и WebSocket защищены аутентификацией: `server/cmd/server/main.go:80-162`, `server/internal/handlers/ws.go:14-16`.
- Реализованы allowlist CORS, `Vary: Origin` и CSRF-проверка для изменяющих запросов при явной настройке origin: `server/internal/middleware/cors.go:18-55,90-103`.
- Для аутентификации, групп и ключей применяются rate limits: `server/internal/middleware/rate_limit.go:21-50`, `server/cmd/server/main.go:58-75,102-103,114-152`.
- WebSocket ограничивает размер кадра и частоту операций: `server/internal/ws/client.go:80-84,205-214,250-280`.
- Защищённое хранилище вложений использует потоковую запись на диск с рандомизированными 128-битными ID, валидацией заголовков и защитой от переполнения памяти: `server/internal/handlers/attachments.go`.
- Веб-клиент использует X25519, HKDF-SHA-256, ChaCha20-Poly1305 и случайные salt/nonce: `client/js/crypto.js:254-393`.
- Бэкапы ключей защищены AES-GCM и PBKDF2-SHA-256 с 600 000 итераций: `client/js/crypto.js:28-113,416-480`.
- Android отключает backup, хранит токены и ключи в EncryptedSharedPreferences с MasterKey и использует SQLCipher: `android/app/src/main/AndroidManifest.xml:9-19`, `SecureTokenStorage.kt:15-99`, `Modules.kt:39-66`.

## 4. Остаточные риски и устранённые уязвимости

- **Токен веб-клиента в localStorage.** Исправлено. Токен перенесён в IndexedDB (`e2ee_keys`/`session_token`), в памяти кэшируется через `primeToken()`, устаревшая копия из `localStorage` мигрируется и удаляется (`client/js/api.js`, `client/js/storage.js`). Добавлен строгий CSP (удалён `unsafe-eval`, `connect-src` ограничен `self`, `wss:` и `https:`) и защитные заголовки с `Strict-Transport-Security` (`server/internal/middleware/security_headers.go`).
- **Отзыв сессий.** Исправлено. Добавлены endpoint `POST /api/v1/logout` (отзыв текущего токена) и `POST /api/v1/logout/all` (отзыв всех сессий пользователя); веб-клиент вызывает logout на сервере при выходе (`server/internal/handlers/logout.go`, `client/js/app.js`). Покрыто тестами `logout_test.go`.
- **Утечка Presence/Typing в односторонних чатах.** Исправлено. `UsersShareChat` и `peerDevices` теперь требуют взаимного диалога (оба пользователя отправляли сообщения друг другу) либо общего группового чата (`server/internal/db/relations.go`, `server/internal/ws/client.go`). Незнакомец или спамер больше не может отслеживать статус присутствия.
- **M4 Ring-spam / FCM flooding.** Исправлено. Вызов `OpCallOffer` ограничен проверкой взаимного контакта `UsersShareChat` и отдельным in-memory rate limiter'ом (макс. 5 вызовов в минуту на аккаунт звонящего) (`server/internal/ws/call.go`).
- **M5 Storage-DoS.** Исправлено. Лимит входящего WebSocket-кадра снижен с 5 МБ до 512 КБ (`SetReadLimit(512*1024)`), ограничено количество целевых устройств в пакете `Devices` (макс. 50) и размер ciphertext (макс. 128 КБ) (`server/internal/ws/client.go`, `server/internal/ws/group.go`).
- **M6 Декомпрессионная бомба аватаров.** Исправлено. Проверка габаритов через `image.DecodeConfig` до вызова `image.Decode` с ограничением макс. 4096×4096 px / 16 MP (`server/internal/handlers/users.go`).
- **M7 Буферизация вложений в памяти.** Исправлено. `UploadAttachment` переведён на потоковую передачу через `io.Copy()` и `multipart.Reader` со сбросом напрямую на защищённый диск вместо троекратного выделения 200 МБ в RAM (`server/internal/handlers/attachments.go`).
- **DM без AAD-binding.** Исправлено. `buildPairwiseAAD` связывает `sender_user_id`, `recipient_user_id`, `client_msg_id` и `timestamp` в заголовок Poly1305 MAC (`client/js/crypto.js`, `android/app/src/main/java/niel/kro/penik/data/crypto/E2EECrypto.kt`). Реплей или подкладывание шифротекста в другой чат невозможно.
- **Групповой AAD без отправителя.** Исправлено. `buildGroupAAD` обновлён до протокола v2 и включает `sender_user_id` (`client/js/crypto.js`, `android/app/src/main/java/niel/kro/penik/data/crypto/GroupCrypto.kt`). Сервер или злоумышленник не могут подделать атрибуцию автора сообщения.
- **Session-токены в БД в открытом виде.** Исправлено. Токены сессий в SQLite теперь хранятся исключительно в виде SHA-256 хешей (`db.HashSessionToken`), проверка и отзывы производятся по хешу (`server/internal/db/db.go`, `server/internal/middleware/auth.go`, `server/internal/handlers/auth.go`, `server/internal/handlers/logout.go`).
- **Утечка бэклога группы при инвайте.** Исправлено. Приглашение участника (`inviteMember`) теперь принудительно выполняет ротацию ключа эпохи (`rotateAndDistribute`), генерируя новую версию ключа для новичка и текущих членов группы. Весь предыдущий бэклог остаётся под старыми версиями ключей, к которым у новичка нет доступа, а явная передача истории выполняется через `shareHistoryWithInvitee` (Variant B).
- **Нет forward secrecy и защиты от подмены ключей сервером.** Ограничение зафиксировано в `README.md:159-167`; компрометация identity key может раскрыть историю, а публичные ключи требуют ручной сверки safety number. Риск: средний/высокий.
- **Нет явного запрета cleartext в Android manifest.** Исправлено. В `AndroidManifest.xml` выставлен атрибут `android:usesCleartextTraffic="false"` и подключён строгий Network Security Config (`android/app/src/main/res/xml/network_security_config.xml`), запрещающий незашифрованный cleartext-трафик.
- **Широкий доверенный диапазон прокси по умолчанию (RFC 1918 spoofing).** Исправлено. Список доверенных прокси по умолчанию (`trustedProxies`) ограничен loopback (`127.0.0.0/8`, `::1/128`). Неавторизованные клиенты не могут подменять `X-Forwarded-For`/`X-Real-IP`. Доверенные CIDR задаются через переменную окружения `TRUSTED_PROXIES` (включена в `docker-compose.yml`). Покрыто тестами `client_ip_test.go` и `devices_test.go`.
- **Отсутствие проверки прав доступа при скачивании вложений.** Исправлено. Добавлена таблица `attachments` с фиксацией `uploader_user_id`, а endpoint `GET /api/v1/attachments/file/{id}` проверяет отношения (`CanAccessAttachment`): скачивание зашифрованного файла разрешено только автору, собеседникам по 1:1 чату или участникам общей группы. Посторонние запросы отклоняются с 403 Forbidden. Покрыто тестами `attachments_test.go`.
- **Утечка Bearer-токена на сторонний URL вложений (Web + Android).** Исправлено. `downloadAndDecryptFile` в Web и `downloadAndDecryptAttachment` в Android строго валидируют URL вложения (`new URL`/`Uri.parse`): разрешены только запросы к локальному хосту API по пути `/api/v1/attachments/file/`, переходы по редиректам отключены. Утечка токена авторизации на внешние серверы исключена (`components.js`, `Components.kt`).
- **Empty-AAD fallback в pairwise E2EE.** Исправлено. Удалён fallback с пустым AAD при расшифровке direct messages (`e2eeDecrypt` в `crypto.js`, `decrypt` в `E2EECrypto.kt`). Попытка переигрывания (replay) шифротекста в другой контекст диалога или с подменой ID/таймстемпа завершается отказом расшифровки.
- **Group AAD v1 fallback без привязки автора.** Исправлено. Удалён legacy fallback на протокол v1 без `sender_user_id` (`groupDecrypt` в `crypto.js`, `decryptMessage` в `GroupCrypto.kt`). Все групповые сообщения валидируют автора кадра на уровне Poly1305 MAC.
- **Вложения-«сироты» без записи в БД.** Исправлено. Если файл присутствует на диске, но отсутствует в таблице `attachments`, сервер возвращает 404 Not Found (`attachments.go`). Покрыто тестом `TestGetAttachment_OrphanRejected`.
- **Fallback по «сырому» токену сессии.** Исправлено. Убраны проверки `OR token=?` из `auth.go` и `logout.go`. Все проверки сессий выполняются строго по SHA-256 хешу (`WHERE token=?`, `tokenHash`).
- **LIKE-wildcard инъекция в поиске пользователей.** Исправлено. Добавлено экранирование спецсимволов `%`, `_`, `\` с помощью `ESCAPE '\'`, а пустые поисковые запросы сразу возвращают пустой список (`users.go`). Покрыто тестами в `users_test.go`.
- **CORS и публичные bundle в стикерах.** Исправлено. Удалены хардкодные заголовки `Access-Control-Allow-Origin: *` из `stickers.go`, а эндпоинт `GET /api/v1/stickers/pack/{id}/bundle.zip` защищён `authMW` (`main.go`, `stickers.js`).

## 5. Рекомендации

1. Исключить `HttpLoggingInterceptor.Level.BODY` из release-сборок Android; оставить минимальное логирование только для debug и маскировать секретные поля.
2. Сделать CORS и WebSocket fail-closed: требовать явный список HTTPS origin в production, запретить `*` вне development и валидировать конфигурацию при старте.
3. Вынести схему WebSocket в конфигурацию (`BuildConfig` или `.env`) вместо определения по порту; запретить cleartext через `usesCleartextTraffic=false` и Network Security Config.
4. Добавить отзыв сессий для устройства и всех устройств, ротацию токенов и более короткий срок жизни access token.
5. Перейти к протоколу с forward secrecy и ratchet, использовать одноразовые prekeys, добавить key transparency или надёжное предупреждение о смене ключа.
6. Добавить в CI проверку зависимостей/SBOM, проверку Android release-конфигурации и тесты CORS/WebSocket origin policy. Secret scanning уже выполняется через `.gitleaks`.

## 6. Заключение

Базовые механизмы защиты реализованы на хорошем уровне: аутентификация, криптография, ограничения запросов, защита WebSocket и SSRF-контроли покрывают значительную часть типовых угроз. Все обнаруженные уязвимости M4–M8, утечки присутствия, декомпрессионные бомбы и небезопасные CSP-директивы успешно устранены и покрыты автоматическими тестами.

