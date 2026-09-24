# REST API Documentation — Penik Messenger

Penik Messenger REST API предоставляет эндпоинты для аутентификации, управления профилем, обмена ключами E2EE, управления устройствами, пейринга, групповых чатов и загрузки вложений.

 Базовый URL: `http://<host>:<port>/api/v1`

---

## Таблица эндпоинтов

| Категория | Метод | Эндпоинт | Аутентификация | Описание |
|-----------|-------|----------|----------------|----------|
| **Auth** | `POST` | `/api/v1/register` | Нет | Регистрация нового пользователя |
| | `POST` | `/api/v1/login` | Нет | Вход и привязка/создание сессии устройства |
| | `POST` | `/api/v1/logout` | Bearer Token | Отзыв текущей сессии (закрывает её WebSocket) |
| | `POST` | `/api/v1/logout/all` | Bearer Token | Отзыв всех остальных сессий пользователя |
| | `GET` | `/api/v1/users/check` | Нет | Проверка доступности никнейма |
| **Devices & Rebind** | `GET` | `/api/v1/devices` | Bearer Token | Список устройств пользователя |
| | `PUT` | `/api/v1/devices/me/fcm` | Bearer Token | Обновление FCM-токена устройства |
| | `POST` | `/api/v1/auth/device-challenge` | Bearer Token | Выпуск challenge'а для rebind устройства (DH-proof) |
| | `POST` | `/api/v1/auth/device-rebind` | Bearer Token | Подтверждение владения IK и привязка сессии к устройству |
| **Server** | `GET` | `/api/v1/time` | Нет | Текущее время сервера (калибровка часов клиента) |
| | `GET` | `/api/v1/version` | Нет | Политика обновлений клиентов |
| | `GET` | `/api/v1/health` | Нет | Readiness-проверка сервера |
| **Profile** | `GET` | `/api/v1/users/me` | Bearer Token | Текущий профиль пользователя |
| | `GET` | `/api/v1/users/search` | Bearer Token | Поиск пользователей по имени/никнейму (`online`/`last_seen` — только при взаимном контакте) |
| | `GET` | `/api/v1/users/:id` | Bearer Token | Получение профиля пользователя |
| | `GET` | `/api/v1/users/:nickname/profile` | Нет | Публичный профиль по никнейму |
| | `PUT` | `/api/v1/users/me/name` | Bearer Token | Изменение отображаемого имени |
| | `PUT` | `/api/v1/users/me/nickname` | Bearer Token | Изменение никнейма |
| | `PATCH` | `/api/v1/users/me/password` | Bearer Token | Изменение пароля аккаунта |
| | `GET` | `/api/v1/avatar/:user_id` | Нет | Получение аватара пользователя (WebP) |
| | `PUT` | `/api/v1/avatar` | Bearer Token | Загрузка/обновление аватара |
| **Keys & Backup** | `POST` | `/api/v1/keys/init` | Bearer Token | Загрузка публичных ключей устройства |
| | `GET` | `/api/v1/keys/bundle/:user_id` | Bearer Token | Получение связки ключей девайсов пользователя |
| | `POST` | `/api/v1/keys/backup` | Bearer Token | Сохранение зашифрованного бэкапа приватных ключей |
| | `GET` | `/api/v1/keys/backup` | Bearer Token | Скачивание зашифрованного бэкапа приватных ключей |
| | `GET` | `/api/v1/keys/backups` | Bearer Token | Список бэкапов ключей пользователя |
| | `DELETE` | `/api/v1/keys/backups/:id` | Bearer Token | Удаление конкретного бэкапа ключей |
| **Pairing** | `POST` | `/api/v1/pairing/sessions` | Bearer Token | Создание сессии связывания устройств |
| | `POST` | `/api/v1/pairing/sessions/claim` | Bearer Token | Подтверждение сессии новым устройством |
| | `GET` | `/api/v1/pairing/sessions/:id` | Bearer Token | Проверка статуса claim сессии |
| | `PUT` | `/api/v1/pairing/sessions/:id/history` | Bearer Token | Передача зашифрованной истории на новое устройство |
| **Groups** | `POST` | `/api/v1/groups` | Bearer Token | Создание группового чата |
| | `GET` | `/api/v1/groups` | Bearer Token | Список групп пользователя |
| | `GET` | `/api/v1/groups/:group_id` | Bearer Token | Информация о группе |
| | `PATCH` | `/api/v1/groups/:group_id` | Bearer Token | Изменение названия группы |
| | `DELETE` | `/api/v1/groups/:group_id` | Bearer Token | Удаление группы / Выход из группы |
| | `GET` | `/api/v1/groups/:group_id/avatar` | Нет | Получение аватара группы |
| | `PUT` | `/api/v1/groups/:group_id/avatar` | Bearer Token | Загрузка аватара группы |
| | `GET` | `/api/v1/groups/:group_id/members` | Bearer Token | Список участников группы |
| | `POST` | `/api/v1/groups/:group_id/members` | Bearer Token | Приглашение пользователя в группу |
| | `DELETE` | `/api/v1/groups/:group_id/members/:user_id` | Bearer Token | Исключение участника из группы |
| | `PATCH` | `/api/v1/groups/:group_id/members/:user_id` | Bearer Token | Изменение роли участника (`admin`/`member`) |
| | `POST` | `/api/v1/groups/:group_id/accept` | Bearer Token | Принятие приглашения в группу |
| | `POST` | `/api/v1/groups/:group_id/decline` | Bearer Token | Отклонение приглашения в группу |
| | `GET` | `/api/v1/groups/:group_id/keys` | Bearer Token | Список версий ключей группы |
| | `GET` | `/api/v1/groups/:group_id/keys/:version` | Bearer Token | Получение зашифрованного конверта ключа группы |
| | `GET` | `/api/v1/groups/:group_id/keys/:version/devices` | Bearer Token | Список устройств, получивших конверт |
| | `POST` | `/api/v1/groups/:group_id/keys/:version/envelopes` | Bearer Token | Загрузка зашифрованных конвертов для устройств |
| | `POST` | `/api/v1/groups/:group_id/keys/rotate` | Bearer Token | Запрос/выполнение ротации ключей группы |
| | `GET` | `/api/v1/groups/:group_id/messages/history` | Bearer Token | История сообщений группы |
| | `POST` | `/api/v1/groups/:group_id/history-packets` | Bearer Token | Загрузка одноразового пакета истории для нового участника |
| | `GET` | `/api/v1/groups/:group_id/history-packets` | Bearer Token | Скачивание и удаление пакета истории |
| **Attachments** | `POST` | `/api/v1/attachments/upload` | Bearer Token | Загрузка зашифрованного вложения на сервер |
| | `GET` | `/api/v1/attachments/file/:id` | Bearer Token | Скачивание/стриминг вложения с поддержкой HTTP Range |
| **Messages & Chats** | `GET` | `/api/v1/messages/history` | Bearer Token | История личных сообщений с пользователем |
| | `POST` | `/api/v1/messages/send` | Bearer Token | REST-отправка сообщения (лимиты те же, что у WS: ≤50 устройств, ≤128 KiB ciphertext) |
| | `POST` | `/api/v1/messages/:user_id/read` | Bearer Token | Пометить сообщения пользователя прочитанными |
| | `GET` | `/api/v1/messages/:id/envelope` | Bearer Token | Один конверт сообщения по id (push-нотификации несут только id) |
| | `GET` | `/api/v1/messages/:user_id/status` | Bearer Token | Статусы доставки/прочтения сообщений |
| | `DELETE` | `/api/v1/chats/:peer_id` | Bearer Token | Удаление чата |
| **Calls** | `GET` | `/api/v1/calls` | Bearer Token | История звонков пользователя |
| | `GET` | `/api/v1/calls/peer/:user_id` | Bearer Token | История звонков с конкретным пользователем |
| **Stickers** | `GET` | `/api/v1/stickers/my` | Bearer Token | Установленные стикерпаки |
| | `GET` | `/api/v1/stickers/pack/:id` | Bearer Token | Метаданные стикерпака |
| | `POST` | `/api/v1/stickers/pack/:id/install` | Bearer Token | Установка стикерпака |
| | `DELETE` | `/api/v1/stickers/pack/:id/install` | Bearer Token | Удаление стикерпака |
| | `POST` | `/api/v1/stickers/import/telegram` | Bearer Token | Импорт стикерпака из Telegram |
| | `GET` | `/api/v1/stickers/pack/:id/bundle.zip` | Bearer Token | Скачивание пака архивом |
| | `GET` | `/api/v1/stickers/file/:pack_id/:file_name` | Bearer Token | Отдача файла стикера |
| **Bots** | `POST` | `/api/v1/bots` | Bearer Token | Создание бота |
| | `GET` | `/api/v1/bots` | Bearer Token | Список ботов пользователя |
| | `POST` | `/api/v1/bots/:id/token/regenerate` | Bearer Token | Ротация токена бота |
| | `DELETE` | `/api/v1/bots/:id` | Bearer Token | Удаление бота |

---

## Детальное описание эндпоинтов

### 1. Аутентификация (Auth)

#### `POST /api/v1/register`
Регистрация нового учетного аккаунта и первичного устройства.
- **Request Body (JSON):**
  ```json
  {
    "name": "Иван Петров",
    "nickname": "ivan_petrov",
    "password": "Password123!",
    "device_name": "Pixel 8 Pro",
    "platform": "Android 14",
    "location": "Moscow",
    "crypto_version": 2,
    "ik_pub": "<base64>",
    "signing_key": "<base64>"
  }
  ```
- **Опциональные легаси-поля:** `spk_pub`, `spk_sig` — сохраняются в `identity_keys` для обратной совместимости. Поле `opk_list` и одноразовые prekeys (OTK) **не принимаются**: OTK объявлены устаревшими (см. `AGENTS.md`, раздел «One-Time Keys Policy»); стандарт — прямой X25519-обмен identity-ключами.
- **Response (200 OK):**
  ```json
  {
    "token": "bearer_session_token_here",
    "user_id": 1,
    "device_id": 1
  }
  ```

#### `POST /api/v1/login`
Авторизация в существующем аккаунте.
- **Request Body (JSON):**
  ```json
  {
    "nickname": "ivan_petrov",
    "password": "Password123!",
    "device_name": "Pixel 8 Pro",
    "platform": "Android 14",
    "location": "Moscow",
    "crypto_version": 2,
    "ik_pub": "<base64>",
    "signing_key": "<base64>"
  }
  ```
- **Response (200 OK):**
  ```json
  {
    "token": "bearer_session_token_here",
    "user_id": 1,
    "device_id": 2
  }
  ```
- **Response с rebind:** при временной сессии устройства ответ содержит `rebind_required: true` и `target_device_id` — клиент должен подтвердить владение identity-ключом через `POST /api/v1/auth/device-challenge` → `POST /api/v1/auth/device-rebind`.

#### `GET /api/v1/users/check?nickname=ivan_petrov`
Проверка доступности никнейма.
- **Response (200 OK):**
  ```json
  {
    "available": true,
    "reason": ""
  }
  ```

---

### 2. Профиль и пользователи (Profile)

#### `GET /api/v1/users/search?q=query&limit=20`
Поиск пользователей по никнейму или имени.
- **Headers:** `Authorization: Bearer <token>`
- **Presence в ответе:** поля `online` / `last_seen` отдаются **только при взаимном контакте** (общий 1:1-чат с двусторонней перепиской или общая группа) — см. «Утечка Presence/Typing в односторонних чатах» в `SECURITY_AUDIT.md`. Для посторонних пользователей всегда `online: false`, `last_seen: 0`.
- **Response (200 OK):**
  ```json
  [
    {
      "id": 2,
      "name": "Анна Смирнова",
      "nickname": "anna_s",
      "has_avatar": true,
      "avatar_url": "/api/v1/avatar/2",
      "online": true,
      "last_seen": 1770550000
    }
  ]
  ```

#### `PUT /api/v1/users/me/name`
Обновление имени.
- **Request Body (JSON):** `{ "name": "Новое Имя" }`

#### `PUT /api/v1/users/me/nickname`
Обновление никнейма. Кулдаун: 1 раз в 7 дней.
- **Request Body (JSON):** `{ "nickname": "new_nick" }`

#### `PATCH /api/v1/users/me/password`
Изменение пароля.
- **Request Body (JSON):** `{ "old_password": "...", "new_password": "..." }`

#### `PUT /api/v1/avatar`
Загрузка аватара. Файл передается через `multipart/form-data` (ключ `avatar`, формат WebP/PNG/JPEG, размер — до `MAX_AVATAR_SIZE`, по умолчанию **5 МиБ / 5242880 байт**).

---

### 3. Групповые чаты (Groups)

#### `POST /api/v1/groups`
Создание группы.
- **Request Body (JSON):**
  ```json
  {
    "name": "Разработчики"
  }
  ```

#### `POST /api/v1/groups/:group_id/keys/:version/envelopes`
Загрузка зашифрованных ключей эпохи (конвертов) для устройств участников.
- **Request Body (JSON):**
  ```json
  {
    "envelopes": [
      {
        "device_id": 10,
        "encrypted_key": "<base64>"
      }
    ]
  }
  ```

---

### 4. Вложения (Encrypted Attachments)

Все файлы предварительно шифруются на стороне клиента с помощью *****REDACTED-BY-FILTER-REPO*****. Сервер хранит только зашифрованные бинарные блобы и не имеет доступа к ключам шифрования.

#### `POST /api/v1/attachments/upload`
Загрузка зашифрованного файла на сервер.
- **Form Data (multipart):** `file` — зашифрованные байты
- **Response (200 OK):**
  ```json
  {
    "id": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    "url": "/api/v1/attachments/file/a1b2c3d4e5f60718293a4b5c6d7e8f90"
  }
  ```

#### `GET /api/v1/attachments/file/:id`
Скачивание зашифрованного файла с сервера.
- **Headers:** `Authorization: Bearer <token>`, опционально `Range: bytes=0-1048575`
- **Response:**
  - `200 OK` (полный файл) или `206 Partial Content` (при наличии заголовка `Range`)
  - `Content-Type: application/octet-stream`
  - `Accept-Ranges: bytes`
  - `ETag: "<id>"`
  - `Cache-Control: private, max-age=31536000, immutable`

---

## Лимиты (Rate Limits)

Единый список (синхронизирован с таблицей «Лимиты и защита» в `README.md`):

- **Регистрация / Вход**: 10 запросов / мин на IP.
- **Проверка никнейма и публичный профиль**: 20 запросов / 10 мин на IP.
- **Смена никнейма**: не чаще 1 раза в 7 дней.
- **Запрос связок ключей (`/api/v1/keys/bundle/*`)**: 60 запросов / мин на пользователя.
- **Модификации групп (`POST/PATCH/DELETE /api/v1/groups/*`)**: 30 запросов / мин на пользователя.
- **Ротация ключей группы (`POST /api/v1/groups/*/rotate`)**: 10 запросов / мин на пользователя.
- **Загрузка зашифрованных вложений (`POST /api/v1/attachments/upload`)**: 60 запросов / мин на пользователя.
- **Device challenge (`POST /api/v1/auth/device-challenge`)**: 5 запросов / мин на пользователя.
- **Исходящие звонки (`OpCallOffer`, WebSocket)**: 5 предложений / мин на аккаунт.
- **Кадры WebSocket**: не более 10 кадров / 2 с на один опкод (далее drop, при повторных нарушениях — close 1008).

