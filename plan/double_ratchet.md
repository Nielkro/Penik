# RFC и План внедрения протокола Double Ratchet в Penik Messenger (Версия 1.2)

Этот документ представляет собой технический RFC (Request for Comments) и пошаговый план перехода от текущей статической схемы шифрования сессий (где общий секрет, полученный из X3DH, повторно используется для всех последующих сообщений) к полноценному динамическому протоколу **Double Ratchet (Двойной Хлыст)**. Это обеспечит приложению свойства **Forward Secrecy (прямая секретность)** и **Post-Compromise Security (пост-компромиссная безопасность)**.

---

## 1. Архитектура и транспортный формат (Вариант Б)

Для сохранения совместимости с сервером мы выбираем **Вариант Б**: вся метаинформация Double Ratchet инкапсулируется внутри поля `cipher_bytes`. Таким образом, структуры данных на сервере Go (`server/internal/ws/protocol.go`) остаются без изменений.

Криптографический заголовок сообщения имеет различный формат в зависимости от того, является ли сообщение первым в сессии (Bootstrap) или последующим.

### А. Формат первого сообщения (Bootstrap-пакет)
Отправляется инициатором один раз при создании новой сессии, чтобы получатель мог произвести X3DH-согласование:
```
+---------------------+-------------------+---------------+---------------+---------------+-----------------------+
| session_init_ek(32B)|   dh_pub (32 B)   |  n (4 B, BE)  |  pn (4 B, BE) |   iv (12 B)   |  ciphertext (AES-GCM) |
+---------------------+-------------------+---------------+---------------+---------------+-----------------------+
| <--                                  Header / AAD (72 Bytes)                             --> |
```
* `session_init_ek` (32 байта) — эфемерный ключ инициатора для X3DH (`EK_A`). Передается только в первом сообщении сессии.

### Б. Формат последующих сообщений (Стандартный пакет)
Используется для всех сообщений после успешной инициализации сессии. Позволяет экономить 32 байта трафика на каждом сообщении:
```
+-------------------+---------------+---------------+---------------+-----------------------+
|   dh_pub (32 B)   |  n (4 B, BE)  |  pn (4 B, BE) |   iv (12 B)   |  ciphertext (AES-GCM) |
+-------------------+---------------+---------------+---------------+-----------------------+
| <--                         Header / AAD (40 Bytes)                           --> |
```

* `dh_pub` (32 байта) — текущий публичный ключ DH-хлыста отправителя.
* `n` (4 байта, Big-Endian) — порядковый номер сообщения в текущей цепочке отправки.
* `pn` (4 байта, Big-Endian) — количество сообщений в предыдущей цепочке отправки.

### Защита заголовка через Associated Data (AAD)
Для предотвращения атак типа десинхронизации (tampering заголовка с целью вызова сбоя состояния KDF), заголовок сообщения (первые 72 байта для Bootstrap-сообщения или 40 байт для стандартного сообщения) в обязательном порядке используется в качестве **Associated Data (AAD)** при шифровании и расшифровке AES-256-GCM.

---

## 2. Схема хранения данных в IndexedDB (`storage.js`)

Мы обновляем схему базы данных. При обновлении `DB_VERSION` в `openDB()` создается дополнительное хранилище для пропущенных ключей.

### Обновленная таблица `sessions`
Вместо одного поля `sharedSecret` хранится состояние Double Ratchet:
* `user_id` (PK, number)
* `device_id` (PK, number)
* `root_key` (Uint8Array, 32 байта)
* `send_chain_key` (Uint8Array, 32 байта / null)
* `recv_chain_key` (Uint8Array, 32 байта / null)
* `our_dh_private_jwk` (Object, private key в JWK формате. *Примечание:* Хранение в открытом виде — компромисс веб-средств; для защиты можно шифровать локальную БД с помощью ключа на базе пароля пользователя).
* `our_dh_public_raw` (Uint8Array, 32 байта)
* `their_dh_public_raw` (Uint8Array, 32 байта / null)
* `n_send` (number)
* `n_recv` (number)
* `pn` (number, длина предыдущей отправляющей цепочки)
* `created_at` (number)

### Новое хранилище `skipped_keys`
Для масштабируемости и чистоты структуры пропущенные ключи выносятся в отдельный Object Store:
* **Название store:** `skipped_keys`
* **KeyPath:** `["user_id", "device_id", "dh_pub_hex", "n"]` (составной PK для быстрого точечного поиска)
* **Поля записи:**
  * `user_id` (number)
  * `device_id` (number)
  * `dh_pub_hex` (string, шестнадцатеричное представление публичного ключа отправителя)
  * `n` (number, порядковый номер сообщения в цепочке)
  * `key_bytes` (Uint8Array, 32 байта — Message Key)
  * `created_at` (number, timestamp вставки для FIFO вытеснения)
* **Индекс:** `session_time_idx` по полям `["user_id", "device_id", "created_at"]` для эффективной выборки и очистки старых записей конкретной сессии.

### Алгоритм транзакционного FIFO вытеснения для `skipped_keys`:
Чтобы избежать race conditions при одновременной обработке сообщений, вставка нового ключа выполняется в рамках единой `readwrite` транзакции:
1. Запускается `readwrite` транзакция по таблице `skipped_keys`.
2. Запрашивается количество записей для сессии `(user_id, device_id)` через индекс `session_time_idx`.
3. Если count >= 1000:
   * Открывается курсор по `session_time_idx` в диапазоне сессии.
   * Первая (самая старая по времени) запись удаляется.
4. Выполняется запись (`put`) новой записи: `{ user_id, device_id, dh_pub_hex, n, key_bytes, created_at: Date.now() }`.

---

## 3. Начальное состояние Ratchet и Bootstrap сессии

### Алиса (Инициатор сессии)
Алиса знает публичный ключ Боба (`their_SPK_Pub`) и генерирует свою первую эфемерную пару `our_dh`.
* `root_key` = `SK` (X3DH Shared Secret)
* `their_dh_public_raw` = `their_SPK_Pub`
* `our_dh_private_jwk` = `our_dh.privJwk`, `our_dh_public_raw` = `our_dh.pubRaw`
* Вычисляет `shared_secret = DH(our_dh, their_SPK_Pub)`
* `(new_root_key, send_chain_key) = KDF_RK(root_key, shared_secret, "DoubleRatchetRoot")`
* `recv_chain_key` = `null` (инициализируется при первом ответе от Боба)
* `n_send` = `0`, `n_recv` = `0`, `pn` = `0`

### Боб (Получатель первого сообщения) — Bootstrap первой принятой сессии

#### Сигнатура `getOrEstablishReceiverSession`:
```javascript
export async function getOrEstablishReceiverSession(fromUserId, fromDeviceId, sessionInitEk, initialDHPub)
```

#### Алгоритм Bootstrap на стороне Боба:
1. Если сессия уже существует в БД — возвращаем её.
2. Иначе, выполняем X3DH responder: `SK = x3dhRespond(ourIKPriv, ourSPKPriv, null, theirIKPub, sessionInitEk)`.
3. Создаем объект сессии в начальном состоянии Боба:
   * `root_key` = `SK`
   * `our_dh_private_jwk` = Bob_SPK_private_jwk (наш Signed Prekey используется как начальный DH-ключ)
   * `our_dh_public_raw` = Bob_SPK_pubRaw
   * `their_dh_public_raw` = `null`
   * `send_chain_key` = `null`, `recv_chain_key` = `null`
   * `n_send` = `0`, `n_recv` = `0`, `pn` = `0`
4. Немедленно выполняем **первый DH-ratchet step** на базе полученного из заголовка первого сообщения `initialDHPub` (эфемера Алисы):
   * Вычисляем `shared_secret_1 = DH(our_SPK_private, initialDHPub)`.
   * Выводим `(root_key, recv_chain_key) = KDF_RK(root_key, shared_secret_1, "DoubleRatchetRoot")`.
   * Генерируем новую DH-пару Боба `new_our_dh`.
   * Вычисляем `shared_secret_2 = DH(new_our_dh_private, initialDHPub)`.
   * Выводим `(root_key, send_chain_key) = KDF_RK(root_key, shared_secret_2, "DoubleRatchetRoot")`.
   * Обновляем состояние сессии:
     * `their_dh_public_raw` = `initialDHPub`
     * `our_dh_private_jwk` = `new_our_dh.privJwk`, `our_dh_public_raw` = `new_our_dh.pubRaw`
     * `root_key` = `root_key`, `recv_chain_key` = `recv_chain_key`, `send_chain_key` = `send_chain_key`
     * Сбрасываем счетчики: `pn = 0`, `n_send = 0`, `n_recv = 0`.
5. Сохраняем готовую сессию в IndexedDB и возвращаем её.

> [!IMPORTANT]
> **Атомарность Bootstrap:**
> Процедура Bootstrap-инициализации сессии получателя и расшифровка самого первого входящего сообщения должны выполняться **строго атомарно** в рамках одного и того же синхронного/асинхронного потока выполнения (в одном обработчике `onMsgRecvGlobal`). Разделение этих шагов на независимые события или прерывания не допускается, чтобы избежать гонки состояний KDF.

---

## 4. Спецификация логики шифрования и дешифрования

### Шаг симметричного хлыста (`KDF_CK`)
Для WebCrypto:
1. Импортируем `chain_key` как HMAC-ключ:
   ```javascript
   const key = await subtle.importKey(
     "raw", chainKeyBytes,
     { name: "HMAC", hash: "SHA-256" },
     false, ["sign"]
   );
   ```
2. Вычисляем HMAC:
   * `message_key = await subtle.sign("HMAC", key, new Uint8Array([0x01]))` (берутся первые 32 байта)
   * `new_chain_key = await subtle.sign("HMAC", key, new Uint8Array([0x02]))`

### Отправка сообщения
1. `(new_send_chain_key, message_key) = KDF_CK(send_chain_key)`
2. `send_chain_key = new_send_chain_key`
3. Вычисляем `ciphertext` с помощью `message_key` и AAD.
   * Для первого сообщения AAD: `[session_init_ek || our_dh_public_raw || n_send || pn]` (72 байта)
   * Для последующих сообщений AAD: `[our_dh_public_raw || n_send || pn]` (40 байт)
4. Формируем итоговый `cipher_bytes`.
5. `n_send = n_send + 1`
6. Сохраняем сессию.

### Прием сообщения
При получении `cipher_bytes`:
1. Проверяем наличие установленной сессии с отправителем:
   * **Если сессия отсутствует:** парсим входящий пакет как **Bootstrap-пакет (72 байта префикса)**.
     Извлекаем: `session_init_ek` (32 B), `dh_pub` (32 B), `n` (4 B), `pn` (4 B), `iv` (12 B), `ciphertext`.
     Атомарно запускаем Bootstrap (`getOrEstablishReceiverSession`), получая готовую сессию, после чего переходим к пункту 5 (так как DH-хлыст уже продвинут в Bootstrap).
   * **Если сессия существует:** парсим входящий пакет как **Стандартный пакет (40 байт префикса)**.
     Извлекаем: `dh_pub` (32 B), `n` (4 B), `pn` (4 B), `iv` (12 B), `ciphertext`.
2. Проверяем наличие ключа в `skipped_keys` по `(user_id, device_id, dh_pub_hex, n)`:
   * Если найден:
     * Расшифровываем `ciphertext` с помощью сохраненного `key_bytes` и соответствующего AAD.
     * Удаляем ключ из `skipped_keys`.
     * Возвращаем текст.
   * Если не найден, продолжаем.
3. **Обработка старых/дублированных сообщений (Правило n < n_recv):**
   * Если `dh_pub === their_dh_public_raw` и при этом `n < n_recv`:
     * > [!WARNING]
     * > Поскольку ключа в `skipped_keys` нет, это означает, что сообщение уже было успешно обработано ранее (дубликат) или ключ был вытеснен из-за превышения лимита. **Сообщение отбрасывается (discard/ignore)**, выбрасывается исключение дешифрования.
4. Проверяем смену DH-ключа (`dh_pub !== their_dh_public_raw`):
   * Если сменился (выполняем DH Ratchet):
     * **`SkipMessageKeys(pn)`**: для каждого `i` от `n_recv` до `pn - 1`:
       * `(recv_chain_key, skipped_key) = KDF_CK(recv_chain_key)`
       * Сохраняем `skipped_key` в `skipped_keys` (с FIFO вытеснением).
     * **Первый DH:** `shared_secret_1 = DH(our_dh_private, dh_pub)`
     * `(new_root_key, new_recv_chain_key) = KDF_RK(root_key, shared_secret_1, "DoubleRatchetRoot")`
     * **Генерация нового ключа:** `new_our_dh = GENERATE_DH()`
     * **Второй DH:** `shared_secret_2 = DH(new_our_dh_private, dh_pub)`
     * `(new_root_key, new_send_chain_key) = KDF_RK(new_root_key, shared_secret_2, "DoubleRatchetRoot")`
     * Обновляем состояние сессии: `root_key = new_root_key`, `recv_chain_key = new_recv_chain_key`, `send_chain_key = new_send_chain_key`, `our_dh_private_jwk = new_our_dh.privJwk`, `our_dh_public_raw = new_our_dh.pubRaw`, `their_dh_public_raw = dh_pub`.
     * Сбрасываем: `pn = n_send`, `n_send = 0`, `n_recv = 0`.
5. **`SkipMessageKeys(n)`**: для каждого `i` от `n_recv` до `n - 1`:
   * `(recv_chain_key, skipped_key) = KDF_CK(recv_chain_key)`
   * Сохраняем `skipped_key` в `skipped_keys` (с FIFO вытеснением).
6. **Вычисление текущего ключа сообщения:**
   * `(new_recv_chain_key, message_key) = KDF_CK(recv_chain_key)`
   * `recv_chain_key = new_recv_chain_key`
   * `n_recv = n + 1`
7. Расшифровываем `ciphertext` с помощью `message_key` и AAD.
8. Сохраняем обновленную сессию в IndexedDB.

---

## 5. Миграция легаси-сессий

Так как старые сессии хранят только статический `sharedSecret` без необходимых счетчиков и эфемерных DH-ключей, автоматическая миграция криптографического состояния невозможна.

### Стратегия миграции: Автоматический сброс (One-time Reset)
1. При чтении сессии из базы данных (`getSession`), если у нее отсутствует поле `root_key` (или заполнено старое поле `sharedSecret`):
   * Сессия удаляется из IndexedDB.
   * Метод возвращает `null`.
2. Это прозрачно инициирует стандартный флоу:
   * **При отправке:** `ensureSession` не находит сессию, запрашивает новый ключевой бандл получателя через WebSocket (0x10) и инициализирует полноценное состояние Double Ratchet.
   * **При получении:** получатель не находит сессию, запрашивает ключевой бандл отправителя через WebSocket (0x10) и производит инициализацию со своей стороны.
3. Сообщения, пришедшие в переходный период со старыми ключами, могут не расшифроваться, поэтому обновление должно сопровождаться очисткой оффлайн-очереди на сервере при обновлении схемы.

---

## 6. Пошаговый план изменений по файлам

### 1. `client/js/crypto.js`
* Добавить экспорт функций `kdf_rk(rootKey, dhSharedSecret, info)` и `kdf_ck(chainKey)`.
* Реализовать правильный импорт ключей HMAC и deriveBits для KDF.
* Обновить функции `encryptMessage` и `decryptMessage` для поддержки Associated Data (AAD) в качестве третьего параметра.

### 2. `client/js/storage.js`
* Обновить функцию инициализации БД `openDB()`: добавить создание objectStore `"skipped_keys"` с PK `["user_id", "device_id", "dh_pub_hex", "n"]` (где `user_id` и `device_id` — типы `number`) и индексом по `created_at`.
* Обновить `getSession(userId, deviceId)`: добавить логику детектирования старых легаси-сессий (содержащих `sharedSecret`) с их удалением и возвратом `null` для перезапуска сессии.
* Добавить экспортируемые функции для управления `skipped_keys`:
  * `saveSkippedKey(userId, deviceId, dhPubHex, n, keyBytes)` с лимитом в 1000 записей и транзакционным FIFO-вытеснением старых по индексу `session_time_idx`.
  * `getAndRemoveSkippedKey(userId, deviceId, dhPubHex, n)`.
* Обновить `getOrEstablishReceiverSession` для поддержки передачи `sessionInitEk` и выполнения первого DH-шага в процессе Bootstrap сессии Боба.

### 3. `client/js/app.js`
* Переписать глобальный обработчик входящих сообщений `onMsgRecvGlobal`:
  1. Проверить наличие сессии.
  2. Если сессия отсутствует, извлечь 72 байта заголовка (`session_init_ek`, `dh_pub`, `n`, `pn`, `iv`). Атомарно выполнить Bootstrap сессии Боба и расшифровку.
  3. Если сессия существует, извлечь 40 байт заголовка (`dh_pub`, `n`, `pn`, `iv`).
  4. Выполнить логику приема Double Ratchet (поиск в `skipped_keys`, проверка правила `n < n_recv` для отброса устаревших дубликатов, проверка поворота DH, вызовы `SkipMessageKeys`, вычисление `message_key`).
  5. Вызвать `decryptMessage` с полученным `message_key`, используя заголовок в качестве AAD.
  6. Сохранить обновленную сессию.

### 4. `client/js/ui/chat.js`
* Обновить функцию отправки сообщений `sendMessage`:
  1. Вызвать `ensureSession` для получения состояния Double Ratchet.
  2. Выполнить симметричный шаг отправки (`KDF_CK`), получить `message_key`.
  3. Зашифровать сообщение через `encryptMessage(message_key, text, headerBytes)`.
  4. Сформировать результирующий `cipher_bytes` в виде `[session_init_ek || dh_pub || n || pn || iv || ciphertext]` (если это первое отправляемое сообщение в сессии) или `[dh_pub || n || pn || iv || ciphertext]` (для последующих сообщений).
  5. Обновить состояние сессии и сохранить ее.
* Обновить `ensureSession` для правильной инициализации Алисы при установке новой сессии.
