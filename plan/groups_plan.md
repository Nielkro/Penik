# Группы с E2EE: технический план

## 1. Цель и выбранная модель

Добавить групповые чаты для небольших групп: ориентир — до 10 пользователей и до 2 устройств на пользователя, то есть примерно до 20 устройств на группу.

Для первой реализации использовать **общий симметричный ключ группы с версиями и ротацией**. MLS на первом этапе не внедрять: при таком размере группы fan-out нового ключа максимум на 20 устройств прост, а текущая система уже содержит pairwise X25519-каналы для доставки зашифрованных данных конкретному устройству.

Модель доверия:

- сервер знает состав группы, права участников и маршрутизацию;
- сервер не знает групповой ключ и plaintext;
- сервер доставляет ciphertext только активным участникам;
- компрометация устройства раскрывает данные, доступные этому устройству;
- после удаления участник не получает новые ciphertext и новые версии ключа;
- сообщения, которые уже были доставлены участнику, отозвать невозможно.

## 2. Термины

- **Group** — логическая группа/чат.
- **Membership** — членство пользователя в группе.
- **Device membership** — конкретное устройство пользователя, которому доставляется ключ.
- **Group key** — случайный 32-байтный симметричный ключ эпохи группы.
- **Key version / epoch** — монотонная версия ключа группы.
- **Key envelope** — групповой ключ, зашифрованный отдельно для одного устройства через существующий pairwise E2EE-механизм.
- **Admin/owner** — пользователь с правом управлять составом группы.

## 3. Криптографическая схема

### 3.1 Ключ группы

Для каждой версии группы генерируется:

```text
GroupKey = random 32 bytes
key_version = 1, 2, 3, ...
```

Ключ группы создаётся доверенным клиентом при создании группы или ротации. Сервер хранит только зашифрованные envelopes, но не plaintext-ключ.

Ключ группы не использовать напрямую как ключ сообщения. Для каждого сообщения:

```text
message_key = HKDF-SHA256(
    ikm = group_key,
    salt = random 32 bytes,
    info = "penik-group-message-v1" || group_id || key_version || message_id,
    length = 32
)
```

Шифрование выполняется ChaCha20-Poly1305 с 12-байтным случайным nonce.

AAD должна включать неизменяемый заголовок:

```text
protocol_version || group_id || key_version || message_id || sender_user_id || created_at
```

Клиент проверяет AAD до показа расшифрованного текста.

### 3.2 Формат группового сообщения

```json
{
  "protocol_version": 1,
  "group_id": 123,
  "message_id": "uuid",
  "key_version": 4,
  "sender_user_id": 10,
  "sender_device_id": 55,
  "created_at": 1784268005000,
  "ciphertext": "base64url",
  "salt": "base64url",
  "nonce": "base64url"
}
```

Сервер игнорирует переданные клиентом поля отправителя и назначает `sender_user_id`/`sender_device_id` из authenticated-сессии. Подпись сообщения для первой версии не обязательна, поскольку текущая модель доверяет серверу в определении отправителя.

### 3.3 Версии ключа

Клиент:

1. ищет локальный ключ нужной версии;
2. при отсутствии запрашивает envelope этой версии;
3. принимает ключ только если устройство состояло в группе при выпуске версии;
4. не заменяет ключ под уже существующим номером без проверки fingerprint;
5. отклоняет повреждённые или неизвестные версии.

Старые ключи сохраняются для чтения истории. Удалять их можно только при удалении локальной истории или по политике retention.

## 4. Жизненный цикл группы

### 4.1 Создание

1. Клиент создаёт группу через REST API.
2. Сервер создаёт группу и membership владельца, остальные приглашения переводит в `pending`.
3. Создатель получает список устройств активных участников.
4. Создатель генерирует `GroupKey(v1)`.
5. Ключ шифруется отдельно для каждого подтверждённого устройства.
6. Envelopes загружаются на сервер.
7. Группа становится готовой после наличия envelopes для обязательных устройств.

Офлайн-устройство получает сохранённый envelope при следующем подключении.

### 4.2 Добавление участника

1. Admin создаёт invitation.
2. Пользователь принимает приглашение.
3. Сервер добавляет его в membership и увеличивает `membership_version`.
4. Активный клиент создаёт новую версию группового ключа.
5. Новый участник получает новую версию, но не старые ключи, если история отдельно не разрешена.
6. Новые сообщения используют новую версию.

### 4.3 Удаление участника

1. Admin удаляет пользователя или устройство.
2. Сервер немедленно блокирует доступ к новым сообщениям и envelopes.
3. Увеличивается `membership_version`.
4. Оставшийся участник генерирует новый ключ.
5. Ключ шифруется только для оставшихся устройств.
6. Новые сообщения используют новую `key_version`.
7. Старые сообщения не перешифровываются.

### 4.4 Изменение устройств

Добавление или удаление устройства также вызывает ротацию:

- новое устройство получает новую версию ключа;
- удалённое устройство не получает новую версию;
- каждое устройство является отдельным recipient;
- при 10 пользователях и 2 устройствах максимум создаётся около 20 envelopes на ротацию.

## 5. Серверная модель данных

Добавить миграции в `server/internal/db/schema.sql` и `server/internal/db/db.go`.

### 5.1 Группы

```sql
CREATE TABLE groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    owner_user_id INTEGER NOT NULL REFERENCES users(id),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    membership_version INTEGER NOT NULL DEFAULT 1,
    current_key_version INTEGER NOT NULL DEFAULT 1,
    deleted_at INTEGER DEFAULT NULL
);
```

### 5.2 Участники

```sql
CREATE TABLE group_members (
    group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL DEFAULT 'member',
    status TEXT NOT NULL DEFAULT 'active',
    joined_at INTEGER NOT NULL,
    removed_at INTEGER DEFAULT NULL,
    membership_version INTEGER NOT NULL,
    PRIMARY KEY(group_id, user_id)
);

CREATE INDEX idx_group_members_user ON group_members(user_id, status);
```

Роли: `owner`, `admin`, `member`.

### 5.3 Версии ключей

```sql
CREATE TABLE group_key_versions (
    group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    key_version INTEGER NOT NULL,
    created_by_user_id INTEGER NOT NULL REFERENCES users(id),
    membership_version INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    revoked_at INTEGER DEFAULT NULL,
    PRIMARY KEY(group_id, key_version)
);
```

Сервер хранит только metadata версии.

### 5.4 Envelopes устройств

```sql
CREATE TABLE group_key_envelopes (
    group_id INTEGER NOT NULL,
    key_version INTEGER NOT NULL,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    encrypted_key BLOB NOT NULL,
    encryption_salt BLOB NOT NULL,
    encryption_nonce BLOB NOT NULL,
    sender_device_id INTEGER NOT NULL REFERENCES devices(id),
    created_at INTEGER NOT NULL,
    delivered_at INTEGER DEFAULT NULL,
    PRIMARY KEY(group_id, key_version, device_id),
    FOREIGN KEY(group_id, key_version)
        REFERENCES group_key_versions(group_id, key_version)
        ON DELETE CASCADE
);
```

`encrypted_key` шифруется pairwise-механизмом для конкретного `device_id`. Сервер проверяет, что устройство принадлежит активному участнику на момент выпуска версии.

### 5.5 Групповые сообщения

На первом этапе предпочтительно отдельная таблица, чтобы не смешивать 1:1 и group-модель:

```sql
CREATE TABLE group_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL,
    sender_user_id INTEGER NOT NULL REFERENCES users(id),
    sender_device_id INTEGER NOT NULL REFERENCES devices(id),
    key_version INTEGER NOT NULL,
    ciphertext BLOB NOT NULL,
    encryption_salt BLOB NOT NULL,
    encryption_nonce BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(group_id, key_version)
        REFERENCES group_key_versions(group_id, key_version),
    UNIQUE(sender_user_id, message_id)
);
```

Статусы по устройствам:

```sql
CREATE TABLE group_message_devices (
    message_id INTEGER NOT NULL REFERENCES group_messages(id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    delivered_at INTEGER DEFAULT NULL,
    read_at INTEGER DEFAULT NULL,
    PRIMARY KEY(message_id, device_id)
);
```

## 6. REST API

Все endpoints требуют Bearer authorization и проверки membership/role.

### 6.1 Группы

| Метод | Endpoint | Назначение |
|---|---|---|
| `POST` | `/api/v1/groups` | создать группу |
| `GET` | `/api/v1/groups` | список групп пользователя |
| `GET` | `/api/v1/groups/{group_id}` | метаданные группы |
| `PATCH` | `/api/v1/groups/{group_id}` | изменить название |
| `DELETE` | `/api/v1/groups/{group_id}` | закрыть группу |

Создание:

```json
{
  "name": "Команда",
  "member_user_ids": [2, 3, 4]
}
```

### 6.2 Участники

| Метод | Endpoint | Назначение |
|---|---|---|
| `GET` | `/api/v1/groups/{group_id}/members` | список участников |
| `POST` | `/api/v1/groups/{group_id}/members` | приглашение |
| `DELETE` | `/api/v1/groups/{group_id}/members/{user_id}` | удаление |
| `PATCH` | `/api/v1/groups/{group_id}/members/{user_id}` | изменение роли |
| `POST` | `/api/v1/groups/{group_id}/invitations/{id}/accept` | принятие |

### 6.3 Версии и envelopes

| Метод | Endpoint | Назначение |
|---|---|---|
| `GET` | `/api/v1/groups/{group_id}/keys` | доступные клиенту версии |
| `GET` | `/api/v1/groups/{group_id}/keys/{version}` | envelope текущего устройства |
| `POST` | `/api/v1/groups/{group_id}/keys/{version}/envelopes` | загрузка envelopes |
| `POST` | `/api/v1/groups/{group_id}/keys/rotate` | новая версия |

Получение версии возвращает envelope только для `device_id` текущей сессии.

Пример загрузки:

```json
{
  "membership_version": 5,
  "envelopes": [
    {
      "device_id": 101,
      "encrypted_key": "base64url",
      "salt": "base64url",
      "nonce": "base64url"
    }
  ]
}
```

Сервер не принимает envelope для неактивных устройств.

### 6.4 История

`GET /api/v1/groups/{group_id}/messages/history?limit=100&before_id=...` возвращает только ciphertext:

```json
{
  "messages": [
    {
      "message_id": "uuid",
      "sender_user_id": 10,
      "sender_device_id": 55,
      "key_version": 4,
      "ciphertext": "base64url",
      "salt": "base64url",
      "nonce": "base64url",
      "created_at": 1784268005000
    }
  ],
  "next_cursor": "..."
}
```

Plaintext в API групп не допускается.

## 7. WebSocket протокол

Сохранить текущий формат: первый байт — opcode, затем MessagePack.

| Opcode | Название | Направление | Назначение |
|---|---|---|---|
| `0x20` | `GROUP_MESSAGE_SEND` | Client→Server | отправить ciphertext |
| `0x21` | `GROUP_MESSAGE_RECV` | Server→Client | доставить сообщение |
| `0x22` | `GROUP_MESSAGE_ACK` | Server→Client | подтверждение сохранения |
| `0x23` | `GROUP_KEY_AVAILABLE` | Server→Client | новая версия/envelope |
| `0x24` | `GROUP_MEMBER_CHANGED` | Server→Client | изменение состава |
| `0x25` | `GROUP_MESSAGE_DELIVERED` | Client→Server | доставка |
| `0x26` | `GROUP_MESSAGE_READ` | Client→Server | прочтение |

`GROUP_MESSAGE_SEND`:

```json
{
  "group_id": 123,
  "message_id": "uuid",
  "key_version": 4,
  "ciphertext": "base64url",
  "salt": "base64url",
  "nonce": "base64url"
}
```

Сервер обязан:

1. определить user/device из authenticated connection;
2. проверить активное членство устройства в группе;
3. проверить, что версия ключа совместима с membership;
4. игнорировать поля отправителя из payload;
5. проверить размеры ciphertext/salt/nonce;
6. применить идемпотентность `message_id`;
7. создать fan-out для активных устройств;
8. не расшифровывать payload.

## 8. Клиентское хранение

### 8.1 Web

В IndexedDB добавить stores:

- `groups`;
- `group_members`;
- `group_keys`, ключ `group_id:key_version`;
- `group_messages`;
- `pending_group_key_envelopes`;
- `last_group_message_cursor`.

GroupKey не хранить в обычном `localStorage`. Использовать существующий локальный механизм защиты ключей или device secret.

### 8.2 Android

В Room добавить `GroupEntity`, `GroupMemberEntity`, `GroupKeyEntity`, `GroupMessageEntity`, `GroupSyncCursorEntity` и соответствующие DAO.

GroupKey хранить в Android Keystore/защищённом хранилище. Не логировать ключи, envelopes, plaintext или полные ciphertext.

## 9. Клиентские сценарии

### 9.1 Отправка

1. Проверить active membership.
2. Получить ключ текущей версии.
3. Создать UUID сообщения.
4. Сгенерировать salt и nonce.
5. Получить message key через HKDF.
6. Зашифровать plaintext с AAD.
7. Отправить `GROUP_MESSAGE_SEND`.
8. Обновить статус после ACK.

### 9.2 Получение

1. Проверить group_id, key_version и лимиты полей.
2. Найти локальный GroupKey.
3. При отсутствии получить и расшифровать envelope pairwise-ключом.
4. Проверить AAD.
5. Расшифровать ciphertext.
6. Проверить duplicate по `message_id`.
7. Сохранить сообщение и отправить delivered ACK.

### 9.3 Offline-синхронизация

1. Получить membership snapshot.
2. Получить список доступных версий.
3. Загрузить отсутствующие envelopes.
4. Постранично синхронизировать ciphertext истории.
5. Расшифровать сообщения в порядке `created_at`.
6. Отсутствие старого ключа не должно блокировать синхронизацию остальных сообщений.

## 10. Безопасность API

- Все операции проверяют membership.
- Изменение участников и ротация доступны owner/admin.
- Нельзя получить envelope для чужого устройства.
- Нельзя отправить сообщение в чужую группу.
- Нельзя указать чужого отправителя.
- Нельзя повторно использовать `message_id`.
- Установить лимиты размера группы, ciphertext, envelopes и частоты ротаций.
- Не логировать ключи, plaintext и полные ciphertext.

## 11. Восстановление и потеря устройства

Групповые ключи в backup хранятся только зашифрованными.

При добавлении нового устройства:

1. зарегистрировать устройство;
2. получить pairwise key bundle;
3. создать envelope текущей версии;
4. при необходимости отдельно выдать разрешённые старые версии;
5. не выдавать всю историю автоматически без подтверждения пользователя.

При потере устройства его отозвать и ротировать ключ группы.

## 12. Дедупликация

Сервер:

- уникальный индекс `(sender_user_id, message_id)`;
- повторная отправка возвращает прежний ACK;
- fan-out не создаёт второе logical message.

Клиент:

- primary key — `group_id + message_id`;
- повторный offline batch не создаёт дубликаты;
- envelope той же версии не заменяет существующий ключ без проверки fingerprint.

## 13. Миграция и совместимость

Группы добавляются отдельно от 1:1 чатов.

- Старые личные сообщения не мигрировать в group_messages.
- Старый plaintext API не использовать для групп.
- Передавать `protocol_version` явно.
- Клиенты без поддержки групп получают контролируемую ошибку.
- При изменении формата увеличивать protocol version.

## 14. Тестирование

### Unit-тесты

- генерация GroupKey;
- HKDF, AAD и ChaCha20-Poly1305;
- неверные nonce/salt/ciphertext;
- неверная key version и group_id;
- отсутствие ключа;
- duplicate message/envelope;
- два устройства одного пользователя.

### Серверные тесты

- CRUD группы и роли;
- запрет доступа к чужой группе;
- запрет отправки после удаления;
- запрет envelope для удалённого устройства;
- ротация add/remove;
- fan-out на 20 устройств;
- offline-доставка;
- идемпотентный message_id;
- rate limit и лимиты размера.

### Интеграционные сценарии

1. Группа из 10 пользователей и 20 устройств.
2. Сообщения с разных устройств и расшифровка на активных устройствах.
3. Добавление участника и доступ только к новой версии.
4. Удаление участника и отсутствие новых ciphertext/ключей.
5. Отключённое устройство, ротация, последующая доставка envelope.
6. Повторная синхронизация без дубликатов.
7. Потеря старого envelope без блокировки новых версий.

## 15. Порядок реализации

### Этап 1 — протокол и миграции

- [ ] Утвердить поля, версии и opcode.
- [ ] Добавить groups/members/key_versions/envelopes/group_messages.
- [ ] Добавить foreign keys, индексы, уникальность и миграции.
- [ ] Добавить серверные модели и валидацию.

### Этап 2 — серверный CRUD

- [ ] Создание и получение групп.
- [ ] Invitations и membership.
- [ ] Роли owner/admin/member.
- [ ] Проверки membership во всех endpoints.

### Этап 3 — envelopes и ротация

- [ ] Создание версии ключа без хранения plaintext.
- [ ] Загрузка/получение envelope текущего устройства.
- [ ] Автоматическая ротация при изменении состава/устройств.
- [ ] WebSocket notification новой версии.

### Этап 4 — групповые сообщения

- [ ] GROUP_MESSAGE_SEND/RECV/ACK.
- [ ] Fan-out на устройства.
- [ ] История ciphertext и offline batch.
- [ ] Delivered/read статусы.

### Этап 5 — Web-клиент

- [ ] IndexedDB stores.
- [ ] Локальное хранение версий GroupKey.
- [ ] Encrypt/decrypt с AAD.
- [ ] UI группы и участников.
- [ ] Синхронизация и дедупликация.

### Этап 6 — Android-клиент

- [ ] Room entities/DAO.
- [ ] Защищённое хранение GroupKey.
- [ ] Retrofit DTO/API.
- [ ] WebSocket events.
- [ ] Экраны группы и сообщений.

### Этап 7 — тестирование и релиз

- [ ] Unit/server/integration тесты.
- [ ] Проверка 10 пользователей и 20 устройств.
- [ ] Offline, add/remove, потеря устройства и восстановление.
- [ ] Feature flag.
- [ ] Не включать группы до готовности membership и ротации.

## 16. Решения до начала реализации

1. Новый участник не читает старую историю по умолчанию.
2. Добавлять/удалять участников могут owner и admins.
3. Подписи сообщений для первой версии не нужны, если sender определяется сервером.
4. Определить, нужны ли статусы по каждому устройству или агрегированные по пользователю.
5. Определить срок хранения старых key versions.
6. Определить поведение при удалении owner.
7. Установить серверный лимит устройств пользователя.
8. Изменение названия группы не требует ротации ключа.
