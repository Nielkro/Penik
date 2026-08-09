# АУДИТ БЕЗОПАСНОСТИ: SECURITY_AUDIT.md

## 1. Общая оценка

Проект содержит существенный набор защитных механизмов: Argon2id для паролей, криптографически случайные сессионные токены, E2EE на X25519/HKDF/ChaCha20-Poly1305, авторизацию REST и WebSocket, ограничения размеров запросов и кадров, rate limiting, allowlist для прокси вложений, а также защищённое хранилище и SQLCipher на Android.

При этом до развёртывания в чувствительной production-среде необходимо устранить утечку секрета VK и логирование HTTP-тел в Android release-сборке. Дополнительного внимания требуют небезопасные значения CORS/WebSocket по умолчанию, возможность plaintext WebSocket на Android и архитектурные ограничения E2EE.

## 2. Критические уязвимости

### 2.1 Секрет VK Bot Token хранится в рабочем дереве

- **Описание:** В `server/.env` обнаружена переменная `VK_BOT_TOKEN` со значением секрета. Хранение рабочего токена в файле проекта создаёт риск его попадания в репозиторий, резервные копии или логи.
- **Уровень риска:** Высокий
- **Последствия:** Получивший токен может действовать от имени интеграции VK в пределах её прав, управлять вложениями и нанести репутационный или финансовый ущерб.
- **Статус:** Не исправлено

### 2.2 Android логирует тела всех HTTP-запросов и ответов

- **Описание:** В `android/app/src/main/java/niel/kro/penik/data/di/Modules.kt:107-109` подключён `HttpLoggingInterceptor` с уровнем `BODY` без ограничения debug-сборкой. Этот же клиент добавляет Bearer-токен (`:99-106`).
- **Уровень риска:** Высокий
- **Последствия:** В системные и диагностические логи могут попасть пароли, Bearer-токены, зашифрованные бэкапы, ключевой материал и метаданные сообщений.
- **Статус:** Не исправлено

### 2.3 CORS и WebSocket разрешают любой origin по умолчанию

- **Описание:** `server/internal/config/config.go:32` задаёт `ALLOWED_ORIGINS=*` по умолчанию. В `server/internal/middleware/cors.go:23-27` это приводит к `Access-Control-Allow-Origin: *`, а `server/internal/handlers/ws.go:34-39` отключает проверку origin для WebSocket.
- **Уровень риска:** Высокий
- **Последствия:** При ошибочной production-конфигурации сторонние origin смогут обращаться к API и WebSocket с доступным токеном; CSRF-проверка также не включается для wildcard-конфигурации.
- **Статус:** Не исправлено

### 2.4 Android допускает plaintext WebSocket для портов, отличных от 443

- **Описание:** В `android/app/src/main/java/niel/kro/penik/data/network/websocket/WebSocketManager.kt:271-278` схема `wss` выбирается только для порта `443`; для остальных портов используется `ws`.
- **Уровень риска:** Высокий
- **Последствия:** При подключении к TLS-серверу на нестандартном порту либо к обычному HTTP-порту Bearer-токен из `Sec-WebSocket-Protocol` и передаваемые данные могут быть перехвачены или изменены в сети.
- **Статус:** Не исправлено

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

## 4. Остаточные риски

- **Токен веб-клиента в localStorage.** `client/js/api.js:20-44` хранит `penik_token` в `localStorage`; успешный XSS может привести к захвату сессии. Риск: средний/высокий.
- **Нет отзыва сессий.** TTL по умолчанию составляет 30 дней (`server/internal/config/config.go:37-42`), endpoint для logout/revoke отсутствует. Украденный токен действует до истечения срока. Риск: средний.
- **Нет forward secrecy и защиты от подмены ключей сервером.** Ограничение зафиксировано в `README.md:159-167`; компрометация identity key может раскрыть историю, а публичные ключи требуют ручной сверки safety number. Риск: средний/высокий.
- **Нет явного запрета cleartext в Android manifest.** В `AndroidManifest.xml` отсутствуют `android:usesCleartextTraffic="false"` и Network Security Config. Это усиливает риск plaintext WebSocket. Риск: средний.
- **Вложения читаются целиком в память.** `server/internal/handlers/vkupload.go:197-215` использует `io.ReadAll(file)`, а upload не имеет отдельного rate limit. Аутентифицированный пользователь может создавать давление на память и исходящий трафик. Риск: средний.

## 5. Рекомендации

1. Немедленно отозвать и перевыпустить обнаруженный VK-токен; удалить секрет из рабочего дерева и истории VCS, использовать секрет-хранилище или переменные окружения deployment-среды.
2. Исключить `HttpLoggingInterceptor.Level.BODY` из release-сборок Android; оставить минимальное логирование только для debug и маскировать секретные поля.
3. Сделать CORS и WebSocket fail-closed: требовать явный список HTTPS origin в production, запретить `*` вне development и валидировать конфигурацию при старте.
4. Всегда использовать `wss://` в production, передавать схему в конфигурации явно и запретить cleartext через `usesCleartextTraffic=false` и Network Security Config.
5. Добавить отзыв сессий для устройства и всех устройств, ротацию токенов и более короткий срок жизни access token.
6. Усилить защиту веб-клиента от XSS: строгий CSP, минимизация сторонних скриптов, Trusted Types при применимости; не хранить долгоживущий Bearer-токен в `localStorage`.
7. Перейти к протоколу с forward secrecy и ratchet, использовать одноразовые prekeys, добавить key transparency или надёжное предупреждение о смене ключа.
8. Перевести загрузку вложений на потоковую обработку, ввести per-file лимиты, квоты и отдельные rate limits.
9. Добавить в CI secret scanning, проверку зависимостей/SBOM, проверку Android release-конфигурации и тесты CORS/WebSocket origin policy.

## 6. Заключение

Базовые механизмы защиты реализованы на хорошем уровне: аутентификация, криптография, ограничения запросов, защита WebSocket и SSRF-контроли покрывают значительную часть типовых угроз. Однако текущая конфигурация содержит несколько высокорисковых проблем, прежде всего секрет в рабочем дереве, Android BODY-логирование, wildcard CORS/WebSocket и plaintext WebSocket fallback.

Итоговая оценка: система не готова к использованию в чувствительной production-среде до устранения перечисленных высокорисковых проблем. После их исправления следует отдельно запланировать протокольное усиление E2EE для обеспечения forward secrecy и снижения доверия к серверу при выдаче ключей.
