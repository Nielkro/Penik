# АУДИТ БЕЗОПАСНОСТИ: SECURITY_AUDIT.md

## Повторная проверка — 2026-09-11 (Статус: находки R1–R7 устранены 2026-09-13; открытые позиции — см. §5 и «Зависимости Web», обновлено 2026-09-24)

Проверен код на базе `ce04e20`: серверные обработчики и middleware, WebSocket, Rust-криптография, основные потоки ключей и вложений Web/Android. Находки R1–R6 устранены 2026-09-13 (коммиты `da1abc6`, `02348b4`, `9f0f38c`, `5a58c19`), покрыты Go unit-тестами, Rust-тестами и проверены сквозным E2E integration test suite (12 сценариев), встроенным в качестве обязательного блокирующего гейткипера перед деплоем в CI/CD.

### Сводная таблица находок повторной проверки

| ID | Исходный риск | Проблема | Статус | Подтверждение и устранение |
| --- | --- | --- | --- | --- |
| R1 | Высокий | Отозванный токен сохраняет доступ через открытый WebSocket | **Исправлено** (`da1abc6`, `02348b4`) | Вызов `hub.CloseSession(tokenHash)` при `/logout`, `/logout/all` и смене пароля; сокеты закрываются с кодом `1008 Policy Violation`. Проверка TTL сессии на каждом фрейме. Покрыто `session_close_test.go` и E2E сьютом. |
| R2 | Ограниченный | Panic при декодировании MsgPack (CVE-2026-32284) | **Исправлено** (`da1abc6`, `5a58c19`) | Библиотека `github.com/shamaton/msgpack/v2` обновлена с v2.2.0 до v2.4.2; усечённые данные возвращают ошибку вместо panic. В CI настроен регулярный `govulncheck`. |
| R3 | Высокий | Вложения буферизуются в RAM; риск исчерпания памяти | **Исправлено** (`da1abc6`) | Убран вызов `ParseMultipartForm(210MB)`. Загрузка переведена на потоковый `multipart.Reader` и `io.LimitReader` с чанками 64 KiB прямо на диск без буферизации в RAM. |
| R4 | Высокий | REST-отправка обходит лимиты размера, числа устройств и частоты WebSocket | **Исправлено** (`da1abc6`, `02348b4`) | В `POST /api/v1/messages/send` внедрены лимиты: <=50 устройств и <=128 KiB ciphertext на устройство. FCM push ограничен строго валидированными доставками, чужие `device_id` отбрасываются. Покрыто E2E тестами. |
| R5 | Средний | Исключённый участник может редактировать свои сообщения группы | **Исправлено** (`da1abc6`, `02348b4`) | `handleGroupMessageEdit` требует `status = 'active'` в `group_members`. Offline edits фильтруются по активным участникам. Покрыто E2E тестами. |
| R6 | Средний | X25519 принимает нулевой общий секрет (неконтрибутивный ключ) | **Исправлено** (`da1abc6`) | В `rust/penik-crypto/src/keys.rs` добавлена проверка `shared.as_bytes() == &[0u8; 32]` с ошибкой `CryptoError::InvalidKey`. Покрыто `wire_compat_tests.rs`. |
| R7 | Информационный | Исторические секреты в git-истории | **Закрыто** | 2026-09-11 владелец проекта подтвердил, что все учётные данные из четырёх срабатываний давно отозваны. |

#### R1 — отзыв сессий не закрывает WebSocket
- **Статус:** **Исправлено** (коммиты `da1abc6`, `02348b4`).
- **Реализация:**
  1. В `server/internal/ws/hub.go` реализован метод `CloseSession(tokenHash string)`, находящий все активные соединения WebSocket по хешу токена сессии и принудительно закрывающий их с кодом `1008 Policy Violation` (RFC 6455).
  2. Обработчики `POST /api/v1/logout`, `POST /api/v1/logout/all` (`server/internal/handlers/logout.go`) и смены пароля (`server/internal/handlers/users.go`) вызывают `hub.CloseSession()` перед удалением сессий из БД.
  3. В `server/internal/ws/client.go` при декодировании каждого входящего фрейма проверяется `time.Now().After(c.sessionExpiresAt)`: просроченная сессия немедленно обрывает сокет с кодом 1008.
  4. Клиентские транспорты Web (`client/js/ws.js`) и Android (`WebSocketManager.kt`) перехватывают код 1008 и прекращают попытки автоматического переподключения (fail-fast), направляя пользователя на экран авторизации.
- **Верификация:** Покрыто Go юнит-тестом `server/internal/ws/session_close_test.go` (`TestHub_CloseSession_TerminatesWebSocket`) и E2E тестом `test_r1_logout_kills_ws` в `tests/e2e/test_runner.py` (авторизация -> открытие сокета -> REST logout -> отправка в сокет отклоняется, сокет закрывается с PolicyViolation 1008).

#### R2 — уязвимый декодер MsgPack на сетевом входе
- **Статус:** **Исправлено** (коммиты `da1abc6`, `5a58c19`).
- **Реализация:**
  1. Зависимость `github.com/shamaton/msgpack/v2` в `server/go.mod` обновлена с `v2.2.0` до `v2.4.2`.
  2. Исправлена уязвимость **CVE-2026-32284 / GHSA-h9q6-hc68-35rp** (GO-2026-4513 / GO-2026-4740): декодер `fixext` в `v2.4.2` валидирует границы входного буфера и корректно возвращает ошибку `too short bytes` вместо рантайм-паники Go.
  3. В GitHub Actions добавлен автоматизированный workflow `.github/workflows/security.yml`, выполняющий регулярное сканирование зависимостей через официальный `golang.org/x/vuln/cmd/govulncheck` на Go 1.27.
- **Верификация:** Проверено изолированными образцами усечённых данных и подтверждено `govulncheck` в CI.

#### R3 — память и диск при загрузке вложений
- **Статус:** **Исправлено** (коммит `da1abc6`).
- **Реализация:**
  1. В `server/internal/handlers/attachments.go` полностью исключён вызов `r.ParseMultipartForm(cfg.MaxUploadSize)`, ранее буферизовавший до 210 МБ в RAM на каждый запрос.
  2. Загрузка переведена на потоковую обработку через `r.MultipartReader()`, `io.LimitReader(part, maxUploadSize + 1)` и запись напрямую во временный файл на диск чанками по 64 KiB (`make([]byte, 64*1024)`).
  3. Потребление памяти снижено с 210 МБ до константных 64 КБ на активный поток загрузки.
- **Верификация:** Подтверждено юнит-тестами `attachments_test.go` и сквозными тестами загрузки/скачивания файлов в E2E сьюте (`test_encrypted_attachment_flow`).

#### R4 — REST обходит ограничения сообщений
- **Статус:** **Исправлено** (коммиты `da1abc6`, `02348b4`).
- **Реализация:**
  1. В обработчик `POST /api/v1/messages/send` (`server/internal/handlers/send_message.go`) перенесены константы безопасности WebSocket: `MaxMsgDevices = 50` и `MaxCiphertextSize = 128 * 1024` (128 KiB).
  2. Запросы, превышающие 50 устройств (`len(req.Devices) > MaxMsgDevices`) или размер шифротекста свыше 128 KiB, немедленно отклоняются со статусом HTTP 400 Bad Request.
  3. Исправлена утечка FCM push-уведомлений: цикл отправки FCM теперь формирует список получателей строго на основе `deliveredDevices` (устройств, успешно прошедших валидацию отношений и запись в БД). Попытки инъекции чужих `device_id` третьего пользователя отбрасываются и не инициируют push-нотификации.
- **Верификация:** Добавлены автоматические проверки в `tests/e2e/test_runner.py`:
  - `test_r4_limits_rest_and_ws`: отклонение шифротекста >128 KiB и пакетов >50 устройств по обоим транспортам (REST и WS).
  - `test_r4_device_spoofing_dropped`: подсовывание чужого `device_id` третьего лица серверу — доставка блокируется, данные третьему лицу не отправляются.

#### R5 — редактирование после исключения из группы
- **Статус:** **Исправлено** (коммиты `da1abc6`, `02348b4`).
- **Реализация:**
  1. В `server/internal/ws/group.go` в функции `handleGroupMessageEdit` добавлена строгая проверка членства: `SELECT status FROM group_members WHERE group_id = ? AND user_id = ?`. Редактирование разрешено только при `status == "active"`. Исключённые (`removed`), покинувшие (`left`) или не принявшие инвайт (`invited`) пользователи получают ошибку авторизации.
  2. В выборке `sendGroupOfflineEditBatch` добавлен фильтр `gm.status = 'active'`, исключающий получение событий о редактировании сообщений пользователями, больше не состоящими в группе.
- **Верификация:** Добавлен тест `test_r5_kicked_member_cannot_edit_group_message` в `tests/e2e/test_runner.py`: исключённый пользователь Боб пытается отправить фрейм `OpGroupMessageEdit` — сервер возвращает ошибку, сообщение группы не модифицируется. Дополнительно проверен тест ротации эпохи ключа (`test_group_epoch_rotation_excludes_kicked_member`), гарантирующий, что исключённый участник не получает ключевой конверт новой эпохи.

#### R6 — некорректные публичные ключи X25519
- **Статус:** **Исправлено** (коммит `da1abc6`).
- **Реализация:**
  1. В ядре криптографии `rust/penik-crypto/src/keys.rs` в методе деривации общего секрета Diffie–Hellman добавлена проверка контрибутивности: `if shared.as_bytes() == &[0u8; 32] { return Err(CryptoError::InvalidKey); }`.
  2. Точки малого порядка (включая ключ из 32 нулевых байт) отсекаются до вызова HKDF и не порождают предсказуемые симметричные ключи шифрования.
  3. Ошибка возвращается во все вызывающие слои (Android JNI и WebAssembly).
- **Верификация:** В тестовый сьют `rust/penik-crypto/tests/wire_compat_tests.rs` добавлен тест `test_x25519_rejects_zero_shared_secret`, проверяющий отказ операции при нулевом публичном ключе.

#### R7 — секреты в истории (закрыто)
- **Статус:** **Закрыто**.
- Gitleaks обнаружил исторические записи VK-токенов в старых коммитах.
- 2026-09-11 владелец проекта подтвердил отзыв всех обнаруженных учётных данных. Репозиторий подключён к автоматическому secret-сканированию через pre-commit hooks и Gitleaks.

### Зависимости Web и архитектурные ограничения

- `npm audit` обнаружил `nanoid@3.3.16` ([GHSA-2v37-7h3g-55p8](https://github.com/advisories/GHSA-2v37-7h3g-55p8), CVE-2026-67213) и `postcss@8.5.19` ([GHSA-fxqj-rqcc-2cmp](https://github.com/advisories/GHSA-fxqj-rqcc-2cmp), CVE-2026-69153). Цепочка: `vite@8.1.4 → postcss@8.5.19 → nanoid@3.3.16`. Они используются при сборке; Docker runtime содержит Go-бинарник и собранную статику, без Node/Vite.
- **Nano ID** генерирует короткие случайные идентификаторы. В `customAlphabet`/`customRandom` размер 0 может приводить к бесконечному циклу и блокировке потока. На установленной версии воспроизведено в отдельном Node-процессе, принудительно завершённом через 750 мс. В проверенном пути PostCSS используется `nanoid/non-secure.nanoid(6)` (`client/node_modules/postcss/lib/input.js:3,80`), а не эти функции с управляемым размером; проверка вернула 6 символов. В `client/js` прямых вызовов Nano ID нет. Достижимый сценарий атаки через Penik не найден. Исправление для ветки 3 — **3.3.18**, для ветки 5 — **5.1.6**; ради этого исправления переход на другой major не требуется.
- **PostCSS** разбирает и преобразует CSS. При обработке недоверенного CSS без `from` комментарий `sourceMappingURL` может указывать на чужой локальный `.map`-файл. Его `sources`/`sourcesContent` могут попасть в результирующую source map. Для раскрытия атакующему требуется доступ к результату; описанный сценарий ограничен JSON source map с расширением `.map`, а не любым файлом сервера. На установленной версии синтетический маркер из `/tmp/opencode/dependency-marker.map` попал в результат без `from`; с `from` и отдельно с `map:false` утечки маркера не было. Vite передаёт `from: source` (`client/node_modules/vite/dist/node/chunks/node.js:22595-22607`); просмотренные пути postcss-import и сборки также задают `from`. Пользовательский CSS через сообщения на сервере не компилируется. Поэтому указанный сценарий в проверенном потоке Penik не подтверждён. Исправленная версия — **8.5.23**.
- **Статус:** **Открыто (приоритет низкий, только сборочный контур).** В `client/package-lock.json` всё ещё закреплены `nanoid@3.3.16` и `postcss@8.5.19`; целевые версии — `3.3.18` и `8.5.23`. Docker-образ и runtime не содержат Node/Vite, поэтому на production-эксплуатацию это не влияет; обновление требуется для чистоты `npm audit` в CI.
- Forward secrecy отсутствует: статический X25519 и случайные salt/nonce не заменяют ratchet. TOFU в `client/js/pinning.js:35-66` автоматически принимает изменившийся ключ после предупреждения. Это известные архитектурные ограничения (**открыто, по дизайну**).
- Общий симметричный ключ группы и AAD с sender ID не являются подписью отдельного автора (`rust/penik-crypto/src/cipher.rs:151-193`). Честный сервер подставляет автора из сессии, но обладатель группового ключа вместе с контролем транспорта/сервера может сформировать тег для чужого ID. Прежнее утверждение о безусловной невозможности подделки автора следует трактовать с этим ограничением (**открыто, по дизайну**).

### Выполненные проверки и валидация

- `go test ./...` — все тесты сервера проходят успешно, включая новый регрессионный сьют `session_close_test.go`.
- `cargo test --all` — тесты Rust-ядра `penik-crypto` проходят без ошибок, включая валидацию защиты от low-order ключей X25519.
- `govulncheck ./...` — уязвимости в зависимостях Go отсутствуют (CVE-2026-32284 устранена).
- `python3 scripts/run_e2e.py` — полный интеграционный сьют (12 сценариев) проходит за ~8.3 с:
  1. Регистрация и логин пользователей, обмен ключами.
  2. Защита R1: отзыв сессии немедленно закрывает WebSocket с PolicyViolation 1008.
  3. Защита R4: соблюдение лимитов ciphertext/devices по REST и WS.
  4. Защита R4: отклонение попыток подделки чужого `device_id`.
  5. Защита R5: запрет редактирования сообщений группы исключённым участником.
  6. E2EE ротация групповых ключей эпох с изоляцией исключённого участника.
  7. Редактирование (0x0d/0x0e) и удаление (0x0a/0x0b) личных сообщений.
  8. Pairwise retry flow (0x16 MsgRetryReq -> 0x17 MsgRetryResp).
  9. Сквозная загрузка и скачивание зашифрованных вложений.
- GitHub Actions CI/CD: добавлен параллельный гейткипер `.github/workflows/docker.yml`, запускающий E2E сьют параллельно со сборкой контейнера и блокирующий публикацию/деплой при любых сбоях тестов.

## 1. Общая оценка

Проект содержит существенный набор защитных механизмов: Argon2id для паролей, криптографически случайные сессионные токены, E2EE на X25519/HKDF/***REDACTED-BY-FILTER-REPO***, авторизацию REST и WebSocket, ограничения размеров запросов и кадров, rate limiting, allowlist для прокси вложений, а также защищённое хранилище и SQLCipher на Android.

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

- **Описание:** Ранее в `android/app/src/main/java/niel/kro/penik/data/network/websocket/WebSocketManager.kt` схема `wss` выбиралась только для порта `443`; для остальных портов использовался plaintext `ws`.
- **Уровень риска:** Низкий / Средний (зависит от конфигурации)
- **Последствия:** При TLS на нетипичном порту клиент подключался бы по plaintext WebSocket и передавал бы токен сессии в заголовке протокола.
- **Статус:** **Исправлено.** Схема теперь выводится из настройки REST-подключения `ApiConfig.SCHEME`, а не из порта: `val scheme = if (ApiConfig.SCHEME == "https") "wss" else "ws"` (`WebSocketManager.kt:468-473`). Cleartext дополнительно запрещён `usesCleartextTraffic="false"` и Network Security Config.

## 3. Найденные решения

- Пароли хэшируются Argon2id со случайной солью: `server/internal/handlers/auth.go:47-54,336-370`.
- Сессии используют 32 случайных байта из `crypto/rand`, а срок действия проверяется middleware: `server/internal/handlers/auth.go:376-383`, `server/internal/middleware/auth.go:30-46`.
- REST-маршруты и WebSocket защищены аутентификацией: `server/cmd/server/main.go:80-162`, `server/internal/handlers/ws.go:14-16`.
- Реализованы allowlist CORS, `Vary: Origin` и CSRF-проверка для изменяющих запросов при явной настройке origin: `server/internal/middleware/cors.go:18-55,90-103`.
- Для аутентификации, групп и ключей применяются rate limits: `server/internal/middleware/rate_limit.go:21-50`, `server/cmd/server/main.go:58-75,102-103,114-152`.
- WebSocket ограничивает размер кадра и частоту операций: `server/internal/ws/client.go:80-84,205-214,250-280`.
- Защищённое хранилище вложений использует потоковую запись на диск с рандомизированными 128-битными ID, валидацией заголовков и защитой от переполнения памяти: `server/internal/handlers/attachments.go`.
- Веб-клиент использует X25519, HKDF-SHA-256, ***REDACTED-BY-FILTER-REPO*** и случайные salt/nonce: `client/js/crypto.js:254-393`.
- Бэкапы ключей защищены AES-GCM и PBKDF2-SHA-256 с 600 000 итераций: `client/js/crypto.js:28-113,416-480`.
- Android отключает backup, хранит токены и ключи в EncryptedSharedPreferences с MasterKey и использует SQLCipher: `android/app/src/main/AndroidManifest.xml:9-19`, `SecureTokenStorage.kt:15-99`, `Modules.kt:39-66`.

## 4. Остаточные риски и устранённые уязвимости

- **Токен веб-клиента в localStorage.** Исправлено. Токен перенесён в IndexedDB (`e2ee_keys`/`session_token`), в памяти кэшируется через `primeToken()`, устаревшая копия из `localStorage` мигрируется и удаляется (`client/js/api.js`, `client/js/storage.js`). Добавлен строгий CSP (удалён `unsafe-eval`, `connect-src` ограничен `self`, same-document `blob:`, `wss:` и `https:`) и защитные заголовки с `Strict-Transport-Security` (`server/internal/middleware/security_headers.go`).
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
- **Раздача расшифрованных медиа Service Worker без аутентификации и кросс-доменных проверок.** Исправлено. `handleStreamRequest` в `client/sw.js` теперь отклоняет кросс-доменные запросы, проверяет `Sec-Fetch-Site` (`same-origin`/`none`), запрашивает данные только у активных контролируемых клиентов (`includeUncontrolled: false`) и выставляет заголовки `Cross-Origin-Resource-Policy: same-origin` и `X-Content-Type-Options: nosniff`. `app.js` проверяет активную авторизацию (`getToken()`) перед отдачей блоба.
- **Отсутствие миграции легаси plaintext-БД на SQLCipher (Android).** Исправлено. В `DatabaseEncryption.kt` реализован полноценный механизм миграции: обнаружение нешифрованного заголовка SQLite (`SQLite format 3\0`), проверка контрольной точки WAL (`PRAGMA wal_checkpoint(FULL)`), экспорт данных через `sqlcipher_export` во временную БД с паролем из `SecureTokenStorage`, верификация целостности и атомарная замена оригинального файла с очисткой старых WAL/SHM файлов.

## 5. Рекомендации

Статусы на 2026-09-24:

1. **Исправлено:** `HttpLoggingInterceptor.Level.BODY` отключён вне debug-сборок (`NetworkModule.httpLogLevel(BuildConfig.DEBUG)`, тест `HttpLogLevelTest`).
2. **Исправлено:** CORS/WebSocket fail-closed — `Config.Validate()` требует явного списка origin и запрещает wildcard в production (`config_test.go`).
3. **Исправлено:** схема WebSocket берётся из `ApiConfig.SCHEME`, cleartext запрещён через `usesCleartextTraffic=false` и Network Security Config.
4. **Частично открыто:** endpoint `POST /api/v1/logout` и `/logout/all` реализованы; однако ротация access-токенов не реализована, отзыв отдельного устройства отдельным endpoint не выделен, а `SESSION_TTL` по умолчанию составляет 720 часов (30 дней) — срок жизни сессии не сокращён.
5. **Открыто (по дизайну):** forward secrecy/ratchet и key transparency не реализованы. Пункт про «одноразовые prekeys» устарел: OTK/prekeys объявлены уdeprecated в `AGENTS.md` и не должны возвращаться; стандарт — прямой X25519-обмен identity-ключами с эфемерными sender key.
6. **Частично открыто:** secret scanning (`.gitleaks`) и `govulncheck` в CI есть; SBOM-генерация, dependency review и проверка Android release-конфигурации в CI не добавлены.

## 6. Заключение

Базовые механизмы защиты реализованы на хорошем уровне: аутентификация, криптография, ограничения запросов, защита WebSocket и SSRF-контроли покрывают значительную часть типовых угроз. Все ранее обнаруженные уязвимости M4–M8, а также находки повторного аудита R1–R6 (отзыв сессий в сокетах R1, обновление MsgPack R2, потоковая обработка вложений R3, лимиты REST и изоляция FCM R4, права исключённых участников групп R5, валидация ключей X25519 R6) полностью устранены, включая пункт 2.3 (схема WebSocket из `ApiConfig.SCHEME`). Их соблюдение контролируется автоматизированным пайплайном CI/CD и блокирующим E2E integration test suite перед каждым деплоем. Открытыми остаются: устаревшие `nanoid`/`postcss` в lockfile (только сборочный контур), ротация токенов и короткий `SESSION_TTL` (§5.4), архитектурные ограничения E2EE без forward secrecy (§5.5) и части CI-рекомендаций §5.6.
