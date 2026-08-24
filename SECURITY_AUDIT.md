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
- Глобально ограничен размер HTTP body: `server/cmd/server/main.go:185-189`.
- VK proxy использует allowlist хостов, проверяет схему и redirect, а размер HTML preview ограничен: `server/internal/handlers/vkupload.go:36-66,91-123,149-170`.
- Веб-клиент использует X25519, HKDF-SHA-256, ChaCha20-Poly1305 и случайные salt/nonce: `client/js/crypto.js:254-393`.
- Бэкапы ключей защищены AES-GCM и PBKDF2-SHA-256 с 600 000 итераций: `client/js/crypto.js:28-113,416-480`.
- Android отключает backup, хранит токены и ключи в EncryptedSharedPreferences с MasterKey и использует SQLCipher: `android/app/src/main/AndroidManifest.xml:9-19`, `SecureTokenStorage.kt:15-99`, `Modules.kt:39-66`.

## 4. Остаточные риски и устранённые уязвимости

- **Токен веб-клиента в localStorage.** Исправлено. Токен перенесён в IndexedDB (`e2ee_keys`/`session_token`), в памяти кэшируется через `primeToken()`, устаревшая копия из `localStorage` мигрируется и удаляется (`client/js/api.js`, `client/js/storage.js`). Добавлен строгий CSP (удалён `unsafe-eval`, `connect-src` ограничен `self`, `wss:` и доверенными CDN) и защитные заголовки с `Strict-Transport-Security` (`server/internal/middleware/security_headers.go`).
- **Отзыв сессий.** Исправлено. Добавлены endpoint `POST /api/v1/logout` (отзыв текущего токена) и `POST /api/v1/logout/all` (отзыв всех сессий пользователя); веб-клиент вызывает logout на сервере при выходе (`server/internal/handlers/logout.go`, `client/js/app.js`). Покрыто тестами `logout_test.go`.
- **Утечка Presence/Typing в односторонних чатах.** Исправлено. `UsersShareChat` и `peerDevices` теперь требуют взаимного диалога (оба пользователя отправляли сообщения друг другу) либо общего группового чата (`server/internal/db/relations.go`, `server/internal/ws/client.go`). Незнакомец или спамер больше не может отслеживать статус присутствия.
- **M4 Ring-spam / FCM flooding.** Исправлено. Вызов `OpCallOffer` ограничен проверкой взаимного контакта `UsersShareChat` и отдельным in-memory rate limiter'ом (макс. 5 вызовов в минуту на аккаунт звонящего) (`server/internal/ws/call.go`).
- **M5 Storage-DoS.** Исправлено. Лимит входящего WebSocket-кадра снижен с 5 МБ до 512 КБ (`SetReadLimit(512*1024)`), ограничено количество целевых устройств в пакете `Devices` (макс. 50) и размер ciphertext (макс. 128 КБ) (`server/internal/ws/client.go`, `server/internal/ws/group.go`).
- **M6 Декомпрессионная бомба аватаров.** Исправлено. Проверка габаритов через `image.DecodeConfig` до вызова `image.Decode` с ограничением макс. 4096×4096 px / 16 MP (`server/internal/handlers/users.go`).
- **M7 Буферизация вложений в памяти.** Исправлено. `UploadVKAttachment` переведён на потоковую передачу через `io.Pipe()` и `multipart.Writer` со сбросом на временный диск при > 32 МБ вместо троекратного выделения 200 МБ в RAM (`server/internal/handlers/vkupload.go`).
- **M8 CSRF-check обход префиксным Referer.** Исправлено. Referer парсится через `url.Parse` и сопоставляется строго по полному `scheme://host` из списка разрешённых origins (`server/internal/middleware/cors.go`).
- **Нет forward secrecy и защиты от подмены ключей сервером.** Ограничение зафиксировано в `README.md:159-167`; компрометация identity key может раскрыть историю, а публичные ключи требуют ручной сверки safety number. Риск: средний/высокий.
- **Нет явного запрета cleartext в Android manifest.** В `AndroidManifest.xml` отсутствуют `android:usesCleartextTraffic="false"` и Network Security Config. Это усиливает риск ошибочной конфигурации WebSocket. Риск: средний.

## 5. Рекомендации

1. Исключить `HttpLoggingInterceptor.Level.BODY` из release-сборок Android; оставить минимальное логирование только для debug и маскировать секретные поля.
2. Сделать CORS и WebSocket fail-closed: требовать явный список HTTPS origin в production, запретить `*` вне development и валидировать конфигурацию при старте.
3. Вынести схему WebSocket в конфигурацию (`BuildConfig` или `.env`) вместо определения по порту; запретить cleartext через `usesCleartextTraffic=false` и Network Security Config.
4. Добавить отзыв сессий для устройства и всех устройств, ротацию токенов и более короткий срок жизни access token.
5. Перейти к протоколу с forward secrecy и ratchet, использовать одноразовые prekeys, добавить key transparency или надёжное предупреждение о смене ключа.
6. Добавить в CI проверку зависимостей/SBOM, проверку Android release-конфигурации и тесты CORS/WebSocket origin policy. Secret scanning уже выполняется через `.gitleaks`.

## 6. Заключение

Базовые механизмы защиты реализованы на хорошем уровне: аутентификация, криптография, ограничения запросов, защита WebSocket и SSRF-контроли покрывают значительную часть типовых угроз. Все обнаруженные уязвимости M4–M8, утечки присутствия, декомпрессионные бомбы и небезопасные CSP-директивы успешно устранены и покрыты автоматическими тестами.

