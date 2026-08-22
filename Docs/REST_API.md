# REST API Documentation — Penik Messenger

Penik Messenger REST API предоставляет эндпоинты для аутентификации, управления профилем, обмена ключами E2EE, управления устройствами, пейринга, групповых чатов и загрузки вложений.

 Базовый URL: `http://<host>:<port>/api/v1`

---

## Таблица эндпоинтов

| Категория | Метод | Эндпоинт | Аутентификация | Описание |
|-----------|-------|----------|----------------|----------|
| **Auth** | `POST` | `/api/v1/register` | Нет | Регистрация нового пользователя |
| | `POST` | `/api/v1/login` | Нет | Вход и привязка/создание сессии устройства |
| | `GET` | `/api/v1/users/check` | Нет | Проверка доступности никнейма |
| **Profile** | `GET` | `/api/v1/users/search` | Bearer Token | Поиск пользователей по имени/никнейму |
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
| **Attachments** | `POST` | `/api/v1/attachments/vk-upload` | Bearer Token | Загрузка вложения в VK CDN силами сервера (веб) |
| | `GET` | `/api/v1/attachments/vk-upload-url` | Bearer Token | Выдача одноразовой ссылки для загрузки клиентом (Android) |
| | `POST` | `/api/v1/attachments/vk-save` | Bearer Token | Коммит клиентской загрузки, возвращает ссылку на файл |
| | `GET` | `/api/v1/attachments/proxy` | Bearer Token | Проксирование скачивания вложения из VK |
| **Messages & Chats** | `GET` | `/api/v1/messages/history` | Bearer Token | История личных сообщений с пользователем |
| | `GET` | `/api/v1/messages/:user_id/status` | Bearer Token | Статусы доставки/прочтения сообщений |
| | `DELETE` | `/api/v1/chats/:peer_id` | Bearer Token | Удаление чата |

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
    "ik_pub": "<base64>",
    "spk_pub": "<base64>",
    "spk_sig": "<base64>",
    "opk_list": ["<base64>", ...]
  }
  ```
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
    "ik_pub": "<base64>",
    "spk_pub": "<base64>",
    "spk_sig": "<base64>"
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
Обновление имени. Rate limit: 10 раз в час.
- **Request Body (JSON):** `{ "name": "Новое Имя" }`

#### `PUT /api/v1/users/me/nickname`
Обновление никнейма. Кулдаун: 1 раз в 7 дней.
- **Request Body (JSON):** `{ "nickname": "new_nick" }`

#### `PATCH /api/v1/users/me/password`
Изменение пароля.
- **Request Body (JSON):** `{ "old_password": "...", "new_password": "..." }`

#### `PUT /api/v1/avatar`
Загрузка аватара. Файл передается через `multipart/form-data` (ключ `avatar`, формат WebP/PNG/JPEG, до 100 КБ).

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

### 4. Вложения (Attachments via VK CDN)

Есть два способа загрузки — выбор зависит от платформы.

#### `POST /api/v1/attachments/vk-upload` (веб)
Сервер сам заливает файл в VK CDN и возвращает только ссылку. Браузер не может
постить напрямую на upload-сервер VK (там нет CORS-заголовков), поэтому байты
проходят транзитом через сервер.
- **Form Data:** `file` — зашифрованные байты
- **Response (200 OK):**
  ```json
  {
    "url": "https://vk.com/doc..."
  }
  ```

#### `GET /api/v1/attachments/vk-upload-url` (Android, шаг 1)
Сервер отдаёт одноразовый upload-эндпоинт VK; сами байты через сервер не идут.
- **Response (200 OK):**
  ```json
  {
    "upload_url": "https://pu.vk.com/c123/upload_doc?..."
  }
  ```
- Далее клиент сам делает `POST multipart/form-data` (поле `file`) на
  `upload_url`. VK отвечает JSON с непрозрачным полем `file` — это токен, который
  бесполезен без токена бота.

#### `POST /api/v1/attachments/vk-save` (Android, шаг 2)
Сервер коммитит загрузку (`docs.save`) и возвращает прямую ссылку на CDN.
- **Request Body (JSON):**
  ```json
  {
    "file": "<token из ответа upload-сервера VK>",
    "name": "photo.enc"
  }
  ```
- **Response (200 OK):**
  ```json
  {
    "url": "https://vk.com/doc..."
  }
  ```

#### `GET /api/v1/attachments/proxy`
Проксирование скачивания: `?url=<ссылка VK>`. Хост проверяется по allowlist,
страница предпросмотра документа разворачивается в прямую ссылку.

---

## Лимиты (Rate Limits)

- **Регистрация / Вход**: 10 запросов / мин на IP.
- **Смена имени**: 10 раз / час на пользователя.
- **Смена никнейма**: 1 раз / 7 дней.
- **Загрузка аватара**: 5 раз / час.
- **Поиск пользователей**: 30 запросов / мин.
- **Запросы связок ключей (`/api/v1/keys/bundle/*`)**: 60 запросов / мин.
- **Модификации групп (`POST/PATCH/DELETE /api/v1/groups/*`)**: 30 запросов / мин.
- **Ротация ключей группы (`POST /api/v1/groups/*/rotate`)**: 10 запросов / мин.
- **Клиентская загрузка вложений (`vk-upload-url`, `vk-save`)**: 60 запросов / мин на пользователя.

