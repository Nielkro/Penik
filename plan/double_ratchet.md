# План внедрения протокола Double Ratchet в Penik Messenger (Скорректированный)

Этот документ описывает скорректированный пошаговый план перехода от текущей статической схемы шифрования сессий (где общий секрет, полученный из X3DH, повторно используется для всех последующих сообщений) к полноценному динамическому протоколу **Double Ratchet (Двойной Хлыст)**. Это обеспечит приложению свойства **Forward Secrecy (прямая секретность)** и **Post-Compromise Security (пост-компромиссная безопасность)**.

---

## Архитектура Double Ratchet

Протокол состоит из двух типов хлыстов (цепочек KDF):
1. **Symmetric Ratchet (Симметричный хлыст)**: продвигается вперед на каждое отправленное или полученное сообщение. Шаг хлыста выдает новый одноразовый ключ сообщения (`Message Key`) и обновляет ключ цепочки (`Chain Key`).
2. **Diffie-Hellman Ratchet (DH-хлыст)**: продвигается вперед при получении ответа от собеседника с новым DH-ключом. Обновляет корневой ключ (`Root Key`) и инициализирует новые симметричные цепочки отправки/приема.

```mermaid
graph TD
    X3DH[X3DH Shared Secret] --> RK[Root Key]
    RK -->|DH Step 1: DH(our_old_private, their_new_public)| RK_New1[New Root Key 1]
    RK_New1 -->|KDF_RK| CK_Recv[Recv Chain Key]
    RK_New1 -->|DH Step 2: DH(our_new_private, their_new_public)| RK_New2[New Root Key 2]
    RK_New2 -->|KDF_RK| CK_Send[Send Chain Key]
    
    CK_Send -->|Symmetric Step| MK_Send[Message Key 1]
    CK_Send -->|Symmetric Step| CK_Send_Next[Next Send Chain Key]
    
    CK_Recv -->|Symmetric Step| MK_Recv[Message Key 1]
    CK_Recv -->|Symmetric Step| CK_Recv_Next[Next Recv Chain Key]
```

---

## Шаг 1: Обновление схемы данных в IndexedDB

В таблице `sessions` вместо хранения одного ключа `sharedSecret` необходимо хранить полное криптографическое состояние сессии для каждого контакта и устройства.

### Поля для таблицы `sessions` в `storage.js`:
* `user_id` (PK)
* `device_id` (PK)
* `root_key` (32 байта, ArrayBuffer/Uint8Array)
* `send_chain_key` (32 байта, Uint8Array / null)
* `recv_chain_key` (32 байта, Uint8Array / null)
* `our_dh_private_jwk` (JWK-формат нашего текущего DH-ключа. **Примечание:** Хранение в открытом виде — компромисс веб-средств; для защиты можно шифровать локальную БД с помощью ключа на базе пароля пользователя).
* `our_dh_public_raw` (Raw-байты нашего текущего DH-ключа)
* `their_dh_public_raw` (Raw-байты последнего полученного DH-ключа собеседника)
* `n_send` (integer, счетчик отправленных сообщений в текущей цепочке)
* `n_recv` (integer, счетчик полученных сообщений в текущей цепочке)
* `pn` (integer, количество сообщений в предыдущей отправленной цепочке. **Коррекция:** переименовано из `pn_send` во избежание путаницы, это классический параметр `PN` из спецификации).
* `skipped_keys` (Object/Map, хранит пропущенные ключи сообщений `{ [their_dh_pub_hex + "-" + sequence_number]: message_key_b64 }` для расшифровки пришедших с опозданием сообщений).
  * > [!IMPORTANT]
  * > **Лимит пропущенных ключей (DoS Protection):** Для предотвращения исчерпания памяти (Storage Exhaustion DoS) необходимо установить жесткий лимит на количество хранимых ключей в `skipped_keys` (например, максимум 1000 ключей на сессию). При превышении лимита старые ключи должны вытесняться по принципу FIFO (First In, First Out).

---

## Шаг 2: Математика KDF и криптографические примитивы (`crypto.js`)

Используем стандартные примитивы WebCrypto: `HKDF-SHA256` для корневой цепочки и `HMAC-SHA256` для симметричных цепочек.

### Функции KDF в `crypto.js`:

1. **`kdf_rk(rootKeyBytes, dhSharedSecretBytes)`**:
   * Принимает текущий 32-байтный `root_key` (в качестве salt) и вычисленный общий секрет DH (IKM).
   * Вызывает HKDF-Extract: `PRK = HKDF-Extract(salt=rootKeyBytes, IKM=dhSharedSecretBytes)`.
   * Выводит два 32-байтных ключа (через HKDF-Expand): `new_root_key` и `new_chain_key`.
   * **Коррекция:** Параметр `info` должен быть явно задан как пустая строка `""` или `"DoubleRatchetRoot"`. Ни в коем случае нельзя использовать/переиспользовать `info="X3DH"`, чтобы исключить пересечение доменов ключей.
   
2. **`kdf_ck(chainKeyBytes)`**:
   * Принимает текущий `chain_key`.
   * Вычисляет два значения с помощью HMAC-SHA256:
     * `message_key = HMAC(chainKeyBytes, 0x01)`
     * `new_chain_key = HMAC(chainKeyBytes, 0x02)`
   * **Коррекция:** Для WebCrypto API `chainKeyBytes` должен быть импортирован как объект `CryptoKey` с параметрами `{ name: "HMAC", hash: "SHA-256" }` и правами `["sign"]`. Вычисление HMAC производится через `subtle.sign("HMAC", keyObj, dataBytes)`, где `dataBytes` — это `0x01` или `0x02` в виде Uint8Array.

---

## Шаг 3: Формат пакетов и транспорт (С обратной совместимостью)

Для работы Double Ratchet сообщения должны переносить заголовок `{ dh_pub, n, pn }`. Существует два варианта реализации:

### Вариант А: Поля на уровне WebSocket (Изменение структуры протокола)
Поля добавляются напрямую в структуры `MsgSend` и `MsgRecv`.
* **MsgSend** = `{ to_user_id, cipher_bytes, msg_id, dh_pub, n, pn }`
* **MsgRecv** = `{ from_user_id, from_device_id, cipher_bytes, msg_id, ts, dh_pub, n, pn }`
* *Плюсы:* Прозрачная структура пакетов.
* *Минусы:* Немедленно ломает обратную совместимость. Требует изменения `protocol.go` и `client.go` на Go-сервере, а также одновременного обновления всех клиентов.

### Вариант Б: Инкапсуляция в `cipher_bytes` (Упаковка на стороне клиента) — РЕКОМЕНДУЕТСЯ
Заголовок упаковывается клиентом внутрь поля `cipher_bytes`. Сервер по-прежнему пересылает `cipher_bytes` как непрозрачный массив байтов, ничего не зная о внутренностях Double Ratchet.
* *Схема упаковки (Wire Format):*
  `[dh_pub (32 байта) || n (4 байта, Big-Endian) || pn (4 байта, Big-Endian) || iv (12 байт) || aes_ciphertext]`
* *Плюсы:* **Полная обратная совместимость на сервере.** Серверный код вообще не меняется. Клиенты могут обновляться независимо.
* *Минусы:* Клиент должен самостоятельно парсить префикс `cipher_bytes`.

---

## Шаг 4: Реализация логики отправки и приема

### А. Логика отправки сообщения
1. Считываем сессию из IndexedDB.
2. Делаем шаг симметричного хлыста для отправки: `(new_send_chain_key, message_key) = kdf_ck(send_chain_key)`.
3. Сохраняем `send_chain_key = new_send_chain_key`.
4. Шифруем сообщение с помощью `message_key` (`AES-256-GCM`).
5. Формируем заголовок сообщения: `dh_pub = our_dh_public_raw`, `n = n_send`, `pn = pn`.
6. Увеличиваем счетчик отправленных сообщений `n_send` на 1.
7. Сохраняем обновленную сессию в IndexedDB.
8. Отправляем сообщение получателю (используя выбранный формат из Шага 3).

### Б. Логика приема сообщения
При получении сообщения с заголовком `{ dh_pub, n, pn, ciphertext }`:
1. Считываем сессию из IndexedDB.
2. **Проверка пропущенных ключей (Out-of-order)**:
   * Проверяем, нет ли ключа для `dh_pub` и `n` в `skipped_keys`.
   * Если есть: расшифровываем сообщение с его помощью, удаляем ключ из `skipped_keys` в БД, сохраняем сообщение. Выходим.
3. **Проверка нового DH-ключа (DH Ratchet Step)**:
   * Если полученный `dh_pub` отличается от `their_dh_public_raw`, значит, отправитель сделал шаг DH-хлыста.
   * **Сохраняем пропущенные ключи старой принимающей цепочки**:
     * Вызываем `SkipMessageKeys(pn)`: проходим в цикле от текущего `n_recv` до `pn - 1`.
     * На каждом шаге `i` вычисляем `(recv_chain_key, message_key) = kdf_ck(recv_chain_key)`.
     * Сохраняем `message_key` в `skipped_keys` под ключом `their_dh_public_raw + "-" + i` (с контролем лимита 1000 ключей).
   * **Выполняем шаги DH-хлыста (Два шага DH)**:
     * **Первый DH (вычисление принимающей цепочки)**:
       * Вычисляем `shared_secret_1 = DH(our_dh_private, dh_pub)` (используем текущий/старый приватный ключ и новый публичный ключ отправителя).
       * `(new_root_key, new_recv_chain_key) = kdf_rk(root_key, shared_secret_1)`.
     * **Генерация нового ключа**:
       * Генерируем нашу новую локальную DH-пару (`new_our_dh`).
     * **Второй DH (вычисление отправляющей цепочки)**:
       * Вычисляем `shared_secret_2 = DH(new_our_dh_private, dh_pub)` (используем наш новый приватный ключ и тот же публичный ключ отправителя).
       * `(new_root_key, new_send_chain_key) = kdf_rk(new_root_key, shared_secret_2)`.
     * **Обновление состояния**:
       * `their_dh_public_raw = dh_pub`
       * `our_dh_private_jwk = new_our_dh.privJwk`, `our_dh_public_raw = new_our_dh.pubRaw`
       * `root_key = new_root_key`
       * `recv_chain_key = new_recv_chain_key`
       * `send_chain_key = new_send_chain_key`
       * Сбрасываем счетчики: `pn = n_send`, `n_send = 0`, `n_recv = 0`.
4. **Сохраняем пропущенные сообщения в текущей цепочке**:
   * Если `n > n_recv`, вызываем `SkipMessageKeys(n)`: проходим циклом от `n_recv` до `n - 1`, вычисляя ключи сообщений и складывая их в `skipped_keys`.
5. **Вычисляем ключ текущего сообщения**:
   * `(new_recv_chain_key, message_key) = kdf_ck(recv_chain_key)`.
   * Обновляем `recv_chain_key = new_recv_chain_key`.
   * Устанавливаем `n_recv = n + 1`.
6. Расшифровываем `ciphertext` с помощью `message_key`.
7. Сохраняем обновленное состояние сессии в IndexedDB.

---

## Шаг 5: Инициализация сессии на базе X3DH

Начальные ключи для Double Ratchet выводятся из общего секрета X3DH:
* `sharedSecret` из X3DH становится начальным `root_key` (`SK`).
* **Инициатор (Алиса)**:
  * Инициализирует `root_key = SK`.
  * `their_dh_public_raw = their_SPK_Pub`.
  * Генерирует эфемерный DH-ключ `our_dh`.
  * Вычисляет `shared_secret = DH(our_dh_private, their_dh_public_raw)`.
  * `(new_root_key, send_chain_key) = kdf_rk(root_key, shared_secret)`.
  * `recv_chain_key = null`.
  * `n_send = 0`, `n_recv = 0`, `pn = 0`.
  * Алиса может сразу отправлять сообщения, используя `send_chain_key`.
* **Получатель (Боб)**:
  * Инициализирует `root_key = SK`.
  * Наш текущий DH-ключ равен `SPK` (поскольку именно его публичную часть Алиса использовала для первого DH-согласования).
  * `their_dh_public_raw = null` (будет инициализирован при получении первого сообщения от Алисы).
  * `send_chain_key = null`, `recv_chain_key = null`.
  * `n_send = 0`, `n_recv = 0`, `pn = 0`.
  * При получении первого сообщения от Алисы, ее эфемерный ключ `dh_pub` (отличающийся от `null`) автоматически триггерит стандартный шаг DH-хлыста:
    * `shared_secret_1 = DH(SPK_private, dh_pub)` -> `recv_chain_key`
    * Генерируется новая DH-пара Боба `new_our_dh`.
    * `shared_secret_2 = DH(new_our_dh_private, dh_pub)` -> `send_chain_key`

---

## Резюме изменений по файлам

1. **`client/js/crypto.js`**:
   * Добавить функции `kdf_rk`, `kdf_ck` с правильным WebCrypto импортом и параметрами.
   * Добавить вспомогательные методы для работы с DH-хлыстом.
2. **`client/js/storage.js`**:
   * Обновить логику `saveSession`, `getSession` под новую структуру данных.
   * Реализовать логику `SkipMessageKeys` с лимитом 1000 ключей и FIFO вытеснением.
3. **`client/js/ui/chat.js`**:
   * Обновить `ensureSession` и логику отправки/приема для упаковки/распаковки заголовка Double Ratchet (по Варианту Б).
4. **`server/internal/ws/protocol.go` и `server/internal/ws/client.go`**:
   * В случае выбора Варианта Б изменения на сервере **не требуются**.
   * В случае выбора Варианта А — расширить структуры MsgSend/MsgRecv и поддержать проброс новых полей в `client.go`.
