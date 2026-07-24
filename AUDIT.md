Да. В предоставленных файлах есть как отдельные реализационные проблемы, так и более серьёзные архитектурные/протокольные уязвимости — особенно если приложение позиционируется как E2EE-мессенджер, где сервер не должен иметь возможность читать или подделывать сообщения.

    Ниже — разбор только по `crypto.js`, `app.js`, `ws.js`, `crypto.test.js`. Серверная часть и UI-рендеринг сообщений не видны, поэтому часть выводов помечена как «зависит от сервера/UI».

---

## Коротко: главное

Сами криптографические примитивы в `crypto.js` в целом нормальные:

- AES-GCM используется правильно по длине IV.
- ChaCha20-Poly1305 через libsodium — ок.
- HKDF, PBKDF2, случайные salt/nonce — ок.
- PBKDF2 600 000 итераций — приемлемо, хотя Argon2id был бы лучше.
- В групповых сообщениях есть AAD через `buildGroupAAD` — это хорошо.

Но есть серьёзные проблемы в протоколе и интеграции:

1. **Нет криптографической аутентификации отправителя.**
2. **Клиент доверяет серверным ключам без подписи/проверки.**
3. **Нет forward secrecy для личных сообщений.**
4. **Нет AAD / контекстной привязки для pairwise-сообщений.**
5. **Возможны replay / reflection / cross-context атаки.**
6. **Приватные ключи хранятся в браузере в открытом виде, а после logout не очищаются из памяти.**
7. **WebSocket-менеджер имеет проблемы с корреляцией запросов и очисткой состояния.**
8. **Есть потенциальные DOM XSS / injection-риски.**
9. **Есть проблемы с валидацией ключей, ID и кодировок.**

---

# 1. Критичные и high-уровневые проблемы

---

## 1.1. Нет аутентификации отправителя сообщения

В `app.js`:

```js
async function onMsgRecvGlobal(payload) {
  ...
  const fromUserId = Number(payload.from_user_id);
  ...
  if (payload.plaintext) {
    plaintext = payload.plaintext;
  } else if (payload.ciphertext) {
    ...
    const result = await decryptMessagePayload(payload);
    plaintext = result.text;
  }
```

И далее:

```js
const inMsg = {
  msg_id: payload.msg_id,
  chat_id: String(chatPartnerId),
  sender_id: fromUserId,
  plaintext,
  created_at: payload.ts ? payload.ts * 1000 : Date.now(),
  delivered: 1,
  client_msg_id: payload.client_msg_id,
};
```

Проблема:

- `from_user_id` берётся из payload.
- `chat_user_id` берётся из payload.
- `from_identity_key` также приходит в payload.
- Сообщение не подписано отправителем.
- Функция `verifySignature()` есть в `crypto.js`, но нигде не используется в `app.js`.

В `decryptMessagePayload()`:

```js
const fromIdentityKey = toUint8Array(payload.from_identity_key);
const myPrivateIK = await loadPrivateIK();
const secret = await deriveSharedSecret(myPrivateIK, fromIdentityKey);
```

То есть клиент просто берёт публичный ключ из сообщения и делает DH с ним. Нигде не проверяется, что этот ключ действительно принадлежит `payload.from_user_id`.

###Impact

Если сервер скомпрометирован, вредоносен или может подставлять данные в WS-кадр, он может:

- показать сообщение якобы от любого пользователя;
- подставить чужой `from_user_id`;
- подставить attacker-controlled `from_identity_key`;
- заставить клиента расшифровать сообщение ключом, который контролирует атакующий.

Упрощённый сценарий:

1. Атакующий генерирует свою X25519-пару.
2. Знает публичный identity-ключ жертвы.
3. Шифрует сообщение своим приватным ключом и публичным ключом жертвы.
4. Отправляет WS-кадр с:
   ```json
   {
     "from_user_id": 123,
     "from_identity_key": "attacker_public_key",
     "ciphertext": "...",
     "salt": "...",
     "nonce": "..."
   }
   ```
5. Клиент жертвы расшифровывает это и показывает как сообщение от пользователя 123.

Для E2EE это критично.

### Fix

Нужна криптографическая привязка отправителя:

- каждый пользователь/устройство должен иметь Ed25519 signing key;
- каждое сообщение должно подписываться отправителем;
- подпись должна покрывать:
  - `sender_user_id`;
  - `sender_device_id`;
  - `recipient_user_id`;
  - `recipient_device_id`;
  - `client_msg_id` / `msg_id`;
  - `ciphertext`;
  - `salt`;
  - `nonce`;
  - timestamp или server sequence.

Пример логики:

```js
const valid = await verifySignature(
  senderSigningPublicKey,
  signature,
  canonicalMessageBytes
);

if (!valid) {
  throw new Error("Invalid message signature");
}
```

Также нельзя принимать `payload.plaintext` как доверенное сообщение, если включён E2EE-режим.

---

## 1.2. Клиент принимает plaintext без проверки

В `app.js`:

```js
if (payload.plaintext) {
  plaintext = payload.plaintext;
}
```

Это означает, что любой WS-кадр с полем `plaintext` будет показан как сообщение.

Если это осознанный fallback для нешифрованных сообщений — ок, но тогда:

- такие сообщения должны явно помечаться как незащищённые;
- сервер должен авторизовать их;
- UI не должен выдавать их за E2EE.

Если же приложение заявляет E2EE, то это опасно.

### Fix

Для E2EE:

```js
if (payload.plaintext) {
  throw new Error("Plaintext messages are not allowed in E2EE mode");
}
```

Или, если plaintext разрешён как исключение:

```js
if (payload.plaintext) {
  plaintext = payload.plaintext;
  securityLevel = "plaintext";
}
```

И явно показывать это в UI.

---

## 1.3. Клиент полностью доверяет `/keys/bundle` без подписи

В `encryptMessagePayload()`:

```js
const recipientBundle = await apiGet(bundleUrl);
const senderBundle = await apiGet(senderBundleUrl);

const recipientDevices = recipientBundle?.devices || [];
const senderDevices = senderBundle?.devices || [];
...
for (const device of allDevices) {
  const recipientIKPub = new Uint8Array(
    atob(device.identity_key).split("").map(c => c.charCodeAt(0))
  );
  const secret = await deriveSharedSecret(myPrivateIK, recipientIKPub);
  ...
}
```

Проблема:

- клиент получает публичные ключи с сервера;
- нет подписи сервера под бандлом;
- нет подписи владельца ключа;
- нет key transparency;
- нет обязательного safety number verification;
- `computeSafetyNumber()` есть, но не видно, чтобы он обязательно применялся.

### Impact

Если сервер вредоносный или скомпрометированный, он может:

- подменить публичный ключ получателя;
- добавить своё устройство в бандл получателя;
- добавить своё устройство в ваш собственный бандл;
- получать копии сообщений.

Например:

1. Alice пишет Bob.
2. Сервер отдаёт Alice бандл Bob, где один из ключей — ключ атакующего.
3. Alice шифрует сообщение и для настоящего Bob, и для атакующего.
4. Атакующий читает копию.

Это классическая проблема key distribution без аутентификации.

### Fix

Минимум:

- сервер должен подписывать key bundles offline Ed25519-ключом;
- клиент должен проверять подпись сервера;
- клиент должен проверять подпись владельца ключа;
- safety number должен показываться пользователю;
- при первом использовании ключа нужен TOFU + предупреждение при изменении.

Лучше:

- key transparency log;
- auditable key history;
- device verification.

---

## 1.4. Нет forward secrecy для личных сообщений

В `encryptMessagePayload()` и `decryptMessagePayload()` используется долгосрочный identity key:

```js
const myPrivateIK = await loadPrivateIK();
const secret = await deriveSharedSecret(myPrivateIK, recipientIKPub);
```

Для каждого сообщения:

```js
const salt = window.crypto.getRandomValues(new Uint8Array(32));
const nonce = window.crypto.getRandomValues(new Uint8Array(12));
const derivedKey = await hkdfDerive(salt, secret, info, 32);
```

То есть секрет зависит от долгосрочного X25519 identity key.

### Impact

Если приватный identity key будет украден:

- через XSS;
- через бэкап;
- через компрометацию устройства;
- через вредоносное расширение;
- через физический доступ;

то атакующий сможет расшифровать всю прошлую переписку, если у него есть ciphertexts.

Это означает отсутствие forward secrecy.

В коде есть комментарии про OTPK:

```js
// OTPKs are one-time keys, so never decrypt the same message twice
```

Но в показанном коде реальное использование one-time prekeys не видно.

### Fix

Для настоящего E2EE нужен хотя бы один из вариантов:

1. Signal-style X3DH + Double Ratchet.
2. Эфемерные ключи на сессию/сообщение.
3. Signed prekeys + one-time prekeys.
4. Регуляральная ротация ключей и ratchet.

Простая схема «long-term X25519 + HKDF per message» не даёт forward secrecy.

---

## 1.5. Приватный ключ хранится в браузере в открытом виде и не очищается после logout

В `app.js`:

```js
export const state = {
  currentUser: null,
  privateIK: null,
  retryCounters: new Map(),
};
```

И:

```js
export async function loadPrivateIK() {
  if (state.privateIK) return state.privateIK;

  const stored = await getIKPrivate();
  if (stored) {
    state.privateIK = stored instanceof Uint8Array ? stored : new Uint8Array(stored);
    return state.privateIK;
  }
  ...
}
```

Приватный ключ:

- читается из IndexedDB;
- кэшируется в JS-памяти;
- ранее мигрировался из localStorage;
- вероятно хранится в IndexedDB в сыром виде.

В `logout()`:

```js
export async function logout() {
  ws.disconnect();
  setToken(null);
  localStorage.removeItem("user_id");
  localStorage.removeItem("device_id");
  localStorage.removeItem("penik_sign_jwk");
  state.currentUser = null;
  _mainLayout = null;
  _chatListRendered = false;
  setActiveChatCallback(null);
  setChatListUpdateCallback(null);

  try {
    await clearIndexedDB();
  } catch (error) {
    console.error("Failed to clear local data on logout:", error);
  }

  navigate('#login');
}
```

Но здесь **не очищается**:

```js
state.privateIK = null;
```

Это серьёзная проблема для SPA.

### Impact

Если пользователь вышел из аккаунта, но страница не перезагрузилась, старый приватный ключ остаётся в памяти:

```js
if (state.privateIK) return state.privateIK;
```

Если затем в этой же вкладке войдёт другой пользователь, `loadPrivateIK()` может вернуть старый ключ.

Это может привести к:

- использованию чужого ключа;
- шифрованию сообщений неправильным ключом;
- утечке старого ключа в контексте новой сессии;
- проблемам на общем устройстве.

Кроме того, если есть XSS, атакующий может:

- прочитать `state.privateIK`;
- прочитать IndexedDB;
- прочитать token из localStorage;
- отправлять сообщения от имени пользователя.

### Fix

Минимум:

```js
export async function logout() {
  ws.disconnect();
  setToken(null);

  localStorage.removeItem("user_id");
  localStorage.removeItem("device_id");
  localStorage.removeItem("penik_sign_jwk");

  state.currentUser = null;
  state.privateIK = null;
  state.retryCounters.clear();

  _mainLayout = null;
  _chatListRendered = false;

  setActiveChatCallback(null);
  setChatListUpdateCallback(null);

  try {
    await clearIndexedDB();
  } catch (error) {
    console.error("Failed to clear local data on logout:", error);
  }

  navigate('#login');
}
```

Лучше:

- использовать non-extractable WebCrypto keys, где возможно;
- не хранить raw private key в IndexedDB без шифрования;
- использовать auth token в httpOnly cookie, а не localStorage;
- применять CSP против XSS;
- очищать память при logout и смене аккаунта.

---

## 1.6. Нет AAD / контекстной привязки для pairwise-сообщений

В `crypto.js`:

```js
export async function e2eeEncrypt(plaintext, sharedSecret) {
  const salt = crypto.getRandomValues(new Uint8Array(32));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const derivedKey = await hkdfDerive(salt, sharedSecret, "PenikE2EE", 32);
  const plaintextBytes = typeof plaintext === "string"
    ? new TextEncoder().encode(plaintext)
    : plaintext;

  const ciphertext = await chacha20Poly1305Encrypt(
    derivedKey,
    nonce,
    plaintextBytes
  );

  return { ciphertext, salt, nonce };
}
```

Функция `chacha20Poly1305Encrypt()` поддерживает AAD:

```js
export async function chacha20Poly1305Encrypt(
  keyBytes,
  nonceBytes,
  plaintextBytes,
  aadBytes = new Uint8Array(0)
)
```

Но `e2eeEncrypt()` не передаёт AAD.

То есть ciphertext не привязан к:

- sender;
- recipient;
- chat;
- device;
- message id;
- timestamp;
- типу сообщения.

### Impact

Возможны:

1. **Reflection attack**  
   Alice и Bob имеют общий shared secret. Bob может отправить Alice её же ciphertext, и он расшифруется.

2. **Cross-chat replay**  
   Если один и тот же shared secret используется в разных контекстах, ciphertext может быть перенесён.

3. **Cross-protocol replay**  
   У вас один и тот же `e2eeEncrypt()` используется для:
   - личных сообщений;
   - pairing history;
   - wrapping group keys.

```js
export async function encryptPairingHistory(data, sharedSecret) {
  return e2eeEncrypt(JSON.stringify({ version: 1, ...data }), sharedSecret);
}
```

```js
export async function wrapGroupKeyForDevice(groupKey, sharedSecret) {
  const { ciphertext, salt, nonce } = await e2eeEncrypt(groupKey, sharedSecret);
  return { encryptedKey: ciphertext, salt, nonce };
}
```

Это опасно, потому что один и тот же ключ и один и тот же info `"PenikE2EE"` используются для разных сущностей.

Например, ciphertext, созданный как chat message, может быть подставлен как wrapped group key, если длина и формат подходят. И наоборот.

### Fix

Нужно:

1. Добавить AAD.
2. Разделять домены через разные `info` строки.
3. Использовать канонический формат AAD.

Пример:

```js
export function buildPairwiseMessageAAD(params) {
  const {
    senderUserId,
    senderDeviceId,
    recipientUserId,
    recipientDeviceId,
    clientMsgId,
    timestamp,
  } = params;

  const parts = [
    "penik-pairwise-message-v1",
    String(senderUserId),
    String(senderDeviceId),
    String(recipientUserId),
    String(recipientDeviceId),
    String(clientMsgId),
    String(timestamp),
  ];

  return new TextEncoder().encode(parts.join("|"));
}
```

Но лучше использовать length-prefixed encoding, чтобы избежать delimiter-коллизий:

```js
function encodeStringForAAD(value) {
  const bytes = new TextEncoder().encode(String(value));
  const len = new Uint8Array(4);
  new DataView(len.buffer).setUint32(0, bytes.length, false);
  return [len, bytes];
}
```

И использовать разные info:

```js
"penik-pairwise-message-v1"
"penik-group-message-v1"
"penik-group-key-wrap-v1"
"penik-pairing-history-v1"
```

---

## 1.7. Group key wrap не имеет AAD

В `crypto.js`:

```js
export async function wrapGroupKeyForDevice(groupKey, sharedSecret) {
  const { ciphertext, salt, nonce } = await e2eeEncrypt(groupKey, sharedSecret);
  return { encryptedKey: ciphertext, salt, nonce };
}
```

```js
export async function unwrapGroupKey(encryptedKey, sharedSecret, salt, nonce) {
  return e2eeDecrypt(encryptedKey, sharedSecret, salt, nonce);
}
```

Проблема:

- wrapped group key не привязан к group id;
- не привязан к epoch / key_version;
- не привязан к recipient device;
- не привязан к sender/admin device;
- не имеет отдельного info-домена.

### Impact

Если один и тот же pairwise shared secret используется в разных группах или разных epoch, сервер или участник может попытаться:

- replay старого group key;
- подменить group key envelope;
- использовать wrapped key в другой группе;
- понизить epoch.

### Fix

Сделать отдельную функцию:

```js
export async function wrapGroupKeyForDevice(
  groupKey,
  sharedSecret,
  groupId,
  keyVersion,
  recipientDeviceId,
  senderDeviceId
) {
  const aad = buildGroupKeyWrapAAD(
    groupId,
    keyVersion,
    recipientDeviceId,
    senderDeviceId
  );

  const salt = crypto.getRandomValues(new Uint8Array(32));
  const nonce = crypto.getRandomValues(new Uint8Array(12));

  const messageKey = await hkdfDerive(
    salt,
    sharedSecret,
    "penik-group-key-wrap-v1",
    32
  );

  const ciphertext = await chacha20Poly1305Encrypt(
    messageKey,
    nonce,
    groupKey,
    aad
  );

  return { encryptedKey: ciphertext, salt, nonce };
}
```

И аналогично проверять AAD при `unwrapGroupKey()`.

---

## 1.8. `buildGroupAAD()` использует delimiter `|`

```js
export function buildGroupAAD(groupId, keyVersion, messageId, createdAt) {
  const header = [
    GROUP_PROTOCOL_VERSION,
    String(groupId),
    String(keyVersion),
    String(messageId),
    String(createdAt),
  ].join("|");

  return new TextEncoder().encode(header);
}
```

Если `groupId` может быть строкой, содержащей `|`, возможны коллизии.

Пример:

```text
groupId = "a|1"
keyVersion = "2"
```

может дать тот же AAD, что и:

```text
groupId = "a"
keyVersion = "1|2"
```

Если все поля строго числовые, риск ниже. Но лучше не полагаться на это.

### Fix

Использовать length-prefixed encoding:

```js
function buildAAD(fields) {
  const chunks = [];

  for (const field of fields) {
    const bytes = new TextEncoder().encode(String(field));
    const len = new Uint8Array(4);
    new DataView(len.buffer).setUint32(0, bytes.length, false);
    chunks.push(len, bytes);
  }

  const total = chunks.reduce((sum, c) => sum + c.length, 0);
  const out = new Uint8Array(total);

  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }

  return out;
}
```

---

## 1.9. WebSocket request/reply коррелирует только по opcode

В `ws.js`:

```js
request(sendOp, sendPayload, replyOp, timeoutMs = 10_000) {
  const next = () => new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      this._pendingReplies.delete(replyOp);
      reject(new Error(`WS request timeout op=${sendOp}`));
    }, timeoutMs);

    this._pendingReplies.set(replyOp, (data) => {
      clearTimeout(timer);
      resolve(data);
    });
    ...
  });
}
```

И:

```js
if (this._pendingReplies.has(opcode)) {
  const cb = this._pendingReplies.get(opcode);
  this._pendingReplies.delete(opcode);
  cb(payload);
  return;
}
```

Проблемы:

1. Если два разных запроса ждут один и тот же `replyOp`, второй перезапишет первый.
2. Любой unsolicited кадр с нужным opcode может закрыть pending request.
3. Нет `request_id` / `correlation_id`.

### Impact

Возможны:

- race conditions;
- подмена ответа внутри протокола;
- UI-состояния, основанные на чужом ответе;
- denial of service через зависшие запросы.

### Fix

Добавить `req_id`:

```js
const reqId = crypto.randomUUID();

this._pendingReplies.set(reqId, cb);

this.send(sendOp, {
  ...sendPayload,
  req_id: reqId,
});
```

И сервер должен возвращать:

```json
{
  "req_id": "...",
  ...
}
```

Клиент:

```js
if (payload.req_id && this._pendingReplies.has(payload.req_id)) {
  ...
}
```

---

## 1.10. `ws.disconnect()` не полностью очищает состояние

В `ws.js`:

```js
disconnect() {
  this._manualClose = true;
  this._clearTimers();

  if (this._ws) {
    this._ws.close();
    this._ws = null;
  }

  this._connected = false;
}
```

Не очищаются явно:

```js
this._queue = [];
this._pendingReplies.clear();
```

Также не вызываются немедленно disconnect-listeners.

### Impact

Если во время disconnect в очереди остались отложенные `send()`, они могут быть отправлены позже, уже в новой сессии.

Это особенно опасно в SPA при logout/login без перезагрузки страницы.

### Fix

```js
disconnect() {
  this._manualClose = true;
  this._clearTimers();

  this._queue = [];
  this._pendingReplies.clear();

  if (this._ws) {
    this._ws.close();
    this._ws = null;
  }

  this._connected = false;

  this._disconnectListeners.forEach(fn => {
    try {
      fn();
    } catch (e) {
      console.error("[ws] Disconnect listener error", e);
    }
  });
}
```

---

# 2. Проблемы среднего уровня

---

## 2.1. `deriveSharedSecret()` принимает неоднозначные форматы ключей

```js
let cleanPublic = publicKey;

if (cleanPublic.length === 44) {
  try {
    const asciiStr = String.fromCharCode(...cleanPublic);
    const decoded = new Uint8Array(
      atob(asciiStr).split("").map(c => c.charCodeAt(0))
    );

    if (decoded.length === 32) {
      cleanPublic = decoded;
    }
  } catch (e) {
    console.error("Failed to self-heal 44-byte public key:", e);
  }
}

if (cleanPublic.length === 33 && cleanPublic[0] === 5) {
  cleanPublic = cleanPublic.slice(1);
}
```

Проблемы:

- ключ может быть 32-byte raw;
- ключ может быть 44-byte base64 внутри байтов;
- ключ может быть 33-byte prefixed.

Это создаёт неоднозначность кодирования.

### Risk

- key canonicalization issues;
- обход проверок, если где-то проверяется одна форма, а используется другая;
- сложные для отладки уязвимости совместимости.

### Fix

Жёстко требовать канонический формат:

```js
if (publicKey.length !== 32) {
  throw new Error("Invalid X25519 public key length");
}
```

Если нужны префиксы или base64 — декодировать на уровне API, а не внутри криптофункции.

Также желательно проверять shared secret:

```js
if (sharedSecretArr.every(b => b === 0)) {
  throw new Error("Weak X25519 shared secret");
}
```

---

## 2.2. Нет проверки low-order X25519 points

X25519 может вернуть all-zero shared secret для некоторых malicious public keys, если реализация не отбрасывает их автоматически.

WebCrypto часто обрабатывает это нормально, но лучше защищаться явно.

### Fix

```js
const sharedSecretArr = new Uint8Array(sharedSecret);

if (sharedSecretArr.length !== 32) {
  throw new Error("Invalid shared secret length");
}

if (sharedSecretArr.every(b => b === 0)) {
  throw new Error("Invalid or weak X25519 public key");
}

return sharedSecretArr;
```

---

## 2.3. `recipient_device_id` может отсутствовать

В `onMsgRecvGlobal()`:

```js
if (
  payload.recipient_device_id != null &&
  Number(payload.recipient_device_id) !== currentDeviceId
) {
  console.warn("Ignoring message addressed to another device", ...);
  return;
}
```

Если `recipient_device_id == null`, сообщение принимается.

Для multi-device E2EE лучше требовать явный `recipient_device_id`.

### Fix

```js
if (payload.recipient_device_id == null) {
  console.warn("Missing recipient_device_id, ignoring message");
  return;
}

if (Number(payload.recipient_device_id) !== currentDeviceId) {
  return;
}
```

---

## 2.4. `state.retryCounters` может расти бесконечно

```js
export const state = {
  currentUser: null,
  privateIK: null,
  retryCounters: new Map(),
};
```

Использование:

```js
const msgKey = String(payload.msg_id);
const attempts = state.retryCounters.get(msgKey) || 0;

if (attempts < 2) {
  state.retryCounters.set(msgKey, attempts + 1);
}
```

Удаление только при успешной расшифровке:

```js
state.retryCounters.delete(payload.msg_id);
```

Если атакующий или неисправный сервер будет присылать много сообщений с уникальными `msg_id`, которые не расшифровываются, карта будет расти.

### Fix

Добавить TTL или максимальный размер:

```js
state.retryCounters.set(msgKey, {
  attempts: attempts + 1,
  ts: Date.now(),
});
```

И периодически чистить.

---

## 2.5. `Number()` для ID может ломать 64-bit identifiers

Во многих местах:

```js
Number(payload.msg_id)
Number(payload.from_user_id)
Number(msg.chat_id)
Number(localStorage.getItem("device_id"))
```

Если сервер использует 64-bit integer ID, JavaScript может терять точность после `2^53 - 1`.

### Impact

- collision message IDs;
- неправильные status updates;
- неправильная маршрутизация сообщений;
- проблемы с ACK.

### Fix

Использовать строки для ID:

```js
String(payload.msg_id)
```

А `Number()` применять только к действительно числовым внутренним полям, если уверены в диапазоне.

Для msgpack можно настроить декодирование больших integer как BigInt или string.

---

## 2.6. Потенциальный DOM XSS через `err.message`

В `boot()`:

```js
boot().catch(err => {
  console.error('Boot error:', err);
  document.getElementById('app').innerHTML =
    `<div style="color:#e05252;padding:24px;text-align:center">Не удалось запустить: ${err.message}</div>`;
});
```

Если `err.message` содержит HTML/JS, это может стать DOM XSS.

Например, если ошибка формируется из серверного ответа или URL-параметров.

### Fix

Использовать `textContent`:

```js
boot().catch(err => {
  console.error('Boot error:', err);

  const app = document.getElementById('app');
  app.innerHTML = "";

  const div = document.createElement('div');
  div.style.cssText = "color:#e05252;padding:24px;text-align:center";
  div.textContent = `Не удалось запустить: ${err.message}`;

  app.appendChild(div);
});
```

---

## 2.7. `querySelector()` с динамическим ID

```js
const bubble = document.querySelector(`[data-msg-id="${pending.tempId}"]`);
```

Если `tempId` содержит кавычки или спецсимволы, это может привести к:

- ошибке селектора;
- неожиданному выбору другого элемента;
- injection в selector.

### Fix

Использовать `CSS.escape()`:

```js
const bubble = document.querySelector(
  `[data-msg-id="${CSS.escape(String(pending.tempId))}"]`
);
```

Или лучше хранить элементы в Map:

```js
const bubbles = new Map();
bubbles.set(clientMsgId, bubbleElement);
```

---

## 2.8. `userId` из URL hash не валидируется

```js
function parseHash() {
  const hash = location.hash || '';

  if (hash.startsWith('#chat/')) {
    return { screen: 'chat', userId: hash.slice(6) };
  }

  if (hash.startsWith('#group/')) {
    return { screen: 'group', userId: hash.slice(7) };
  }

  return { screen: hash || '#chats' };
}
```

`userId` может быть любой строкой.

Если `renderChat()` или `renderGroup()` подставляют это в DOM или API URL без проверки, возможны:

- DOM XSS;
- path injection;
- unexpected UI behavior.

### Fix

Валидировать формат:

```js
if (!/^\d+$/.test(userId)) {
  navigate('#chats');
  return;
}
```

И использовать `encodeURIComponent()` в API:

```js
await apiGet(`/keys/bundle/${encodeURIComponent(senderId)}`);
```

---

## 2.9. `localStorage` используется как источник identity

В `encryptMessagePayload()`:

```js
const myId = Number(localStorage.getItem("user_id"));
const myDeviceId = Number(localStorage.getItem("device_id"));
```

Это небезопасно, потому что localStorage может быть изменён:

- через XSS;
- через вредоносное расширение;
- через shared device;
- через отладку.

Клиент должен определять текущего пользователя из серверной сессии, например `/me`, а не из localStorage.

### Fix

Использовать:

```js
const me = getCurrentUser();
const myId = String(me.id);
const myDeviceId = String(me.device_id);
```

А `localStorage` использовать только как кэш, который сверяется с сервером.

---

## 2.10. `boot()` доверяет `localStorage.user_id`

```js
let localUserId = localStorage.getItem("user_id");

if (localUserId) {
  const user = await getUserById(localUserId);
  if (user) {
    user.user_id = user.id;
    user.username = user.nickname;
    setCurrentUser(user);
  }
}
```

Если API `/users/:id` публичный, то при наличии валидного token пользователь A может подменить `user_id` в localStorage на B, и UI будет считать текущим пользователем B.

Сервер, вероятно, будет авторизовывать действия по token, но клиентское состояние может стать несогласованным.

### Fix

Использовать endpoint типа:

```js
const user = await apiGet("/users/me");
```

который возвращает пользователя по текущему token.

---

# 3. Проблемы с key backup и passphrase

---

## 3.1. Backup envelope не содержит версию и итерации KDF

```js
export async function encryptKeyBackup(privateKeyBytes, passphrase) {
  const salt = window.crypto.getRandomValues(new Uint8Array(16));
  const iv = window.crypto.getRandomValues(new Uint8Array(12));
  const aesKey = await deriveKeyFromPassphrase(passphrase, salt);
  ...
}
```

```js
export async function decryptKeyBackup(encryptedBlob, salt, iv, passphrase) {
  for (const iterations of [KDF_ITERATIONS, LEGACY_KDF_ITERATIONS]) {
    ...
  }
}
```

Проблема:

- envelope не знает, какие итерации использовались;
- приходится пробовать 600k и 100k;
- нет AAD;
- нет версии формата;
- нет привязки к user/device/purpose.

### Risk

- downgrade ambiguity;
- лишняя CPU-работа;
- сложность миграции;
- отсутствие purpose separation.

### Fix

Хранить envelope:

```json
{
  "version": 2,
  "kdf": "PBKDF2-SHA256",
  "iterations": 600000,
  "salt": "...",
  "iv": "...",
  "ciphertext": "...",
  "aad": "..."
}
```

И проверять `iterations` из envelope, а не перебирать.

---

## 3.2. Fallback на 100 000 итераций

```js
const LEGACY_KDF_ITERATIONS = 100000;
```

Это нужно для старых бэкапов, но если fallback не ограничен, он может использоваться как downgrade path.

### Fix

- разрешать legacy только если envelope явно помечен как legacy;
- показывать пользователю warning;
- мигрировать старые бэкапы на 600k;
- удалить fallback после миграции.

---

## 3.3. `rewrapEnvelope()` не ротирует DEK

```js
export async function rewrapEnvelope(envelope, oldPassphrase, newPassphrase) {
  ...
  const dek = new Uint8Array(await subtle.decrypt(...));
  ...
  const newEncryptedDek = await subtle.encrypt(...);

  return {
    ...envelope,
    encrypted_dek: new Uint8Array(newEncryptedDek),
    iv_kek: newIvKek,
    salt_kek: newSaltKek
  };
}
```

Меняется только KEK, но `encrypted_keys` остаётся зашифрованным старым DEK.

### Risk

Если старый passphrase был скомпрометирован и атакующий получил старый envelope, он может восстановить DEK. После смены passphrase `encrypted_keys` всё ещё расшифровывается тем же DEK.

### Fix

При смене passphrase генерировать новый DEK и перешифровывать `encrypted_keys`:

```js
const newDek = crypto.getRandomValues(new Uint8Array(32));
const newIvDek = crypto.getRandomValues(new Uint8Array(12));

const plaintextKeys = await decryptWithOldDek(...);
const newEncryptedKeys = await encryptWithNewDek(...);
```

---

## 3.4. PBKDF2-SHA256 — нормально, но Argon2id лучше

600 000 итераций PBKDF2-HMAC-SHA256 — приемлемо, но для passphrase-защиты в 2026 лучше использовать memory-hard KDF:

- Argon2id;
- scrypt.

WebCrypto не имеет Argon2id нативно, но можно использовать libsodium:

```js
sodium.crypto_pwhash(...)
```

---

# 4. Проблемы с base64 и бинарными данными

---

## 4.1. `btoa(String.fromCharCode(...bytes))` может упасть для больших массивов

```js
export function encodeKey(bytes) {
  return btoa(String.fromCharCode(...bytes));
}
```

И в `backupE2EEKeys()`:

```js
encrypted_blob: btoa(String.fromCharCode(...backup.encryptedBlob)),
salt: btoa(String.fromCharCode(...backup.salt)),
iv: btoa(String.fromCharCode(...backup.iv))
```

Для больших `Uint8Array` spread может вызвать `RangeError: Maximum call stack size exceeded`.

Для ключей размеры маленькие, но лучше исправить.

### Fix

```js
export function encodeKey(bytes) {
  let binary = "";
  const chunkSize = 0x8000;

  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }

  return btoa(binary);
}
```

Или использовать современный вариант, если доступен:

```js
Uint8Array.prototype.toBase64
```

---

## 4.2. `toUint8Array()` слишком всеядный

В `decryptMessagePayload()`:

```js
const toUint8Array = (val) => {
  if (!val) return new Uint8Array(0);
  if (val instanceof Uint8Array) return val;
  if (val instanceof ArrayBuffer) return new Uint8Array(val);
  if (Array.isArray(val)) return new Uint8Array(val);
  if (typeof val === "string") {
    const bin = atob(val);
    ...
  }
  throw new Error(...);
};
```

Проблемы:

- пустое значение превращается в пустой ключ;
- строка всегда считается base64;
- массив может содержать некорректные числа;
- нет валидации длины.

### Fix

Для криптополей лучше требовать точный тип:

```js
function requireBytes(val, expectedLength = null) {
  let bytes;

  if (val instanceof Uint8Array) {
    bytes = val;
  } else if (typeof val === "string") {
    bytes = base64ToBytes(val);
  } else {
    throw new Error("Expected bytes or base64 string");
  }

  if (expectedLength !== null && bytes.length !== expectedLength) {
    throw new Error(`Expected ${expectedLength} bytes`);
  }

  return bytes;
}
```

---

# 5. WebSocket: дополнительные риски

---

## 5.1. `decode()` без try/catch

```js
_handleFrame(buffer) {
  const bytes = new Uint8Array(buffer);
  if (bytes.length < 1) return;

  const opcode = bytes[0];
  const payload = bytes.length > 1 ? decode(bytes.slice(1)) : {};
  ...
}
```

Если придёт битый msgpack, `decode()` может выбросить исключение.

### Fix

```js
_handleFrame(buffer) {
  try {
    const bytes = new Uint8Array(buffer);
    if (bytes.length < 1) return;

    const opcode = bytes[0];
    const payload = bytes.length > 1 ? decode(bytes.slice(1)) : {};

    ...
  } catch (err) {
    console.error("[ws] Failed to decode frame", err);
  }
}
```

---

## 5.2. Нет ограничения размера сообщений

Если сервер или атакующий может отправить огромный WS-кадр, клиент может упасть или зависнуть.

### Fix

На уровне браузера сложно задать лимит для WebSocket, но можно:

- проверять `buffer.byteLength` до decode;
- отбрасывать кадры больше N MB;
- закрывать соединение при превышении.

```js
if (buffer.byteLength > MAX_WS_FRAME_SIZE) {
  console.warn("[ws] Frame too large");
  this._ws.close();
  return;
}
```

---

## 5.3. Token передаётся через WebSocket subprotocol

```js
this._ws = new WebSocket(url, ["access_token", token]);
```

Это лучше, чем token в URL, но subprotocol может логироваться:

- proxy;
- server access logs;
- browser diagnostics.

### Fix

Лучше использовать short-lived WS ticket:

1. Клиент делает HTTP-запрос с httpOnly cookie / auth header.
2. Сервер выдаёт одноразовый `ws_ticket`.
3. WebSocket подключается с ticket:

```js
new WebSocket(`${WS_URL}?ticket=${ticket}`);
```

Ticket живёт несколько секунд и используется один раз.

---

## 5.4. `ws://` допускается при HTTP

```js
const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
```

Если приложение открыто по HTTP, WS будет незашифрованным.

### Fix

Для production требовать HTTPS и WSS:

```js
if (window.location.protocol !== "https:" && !isLocalhost()) {
  throw new Error("Secure context required");
}
```

---

## 5.5. Нет PONG timeout

Есть PING:

```js
this._pingTimer = setInterval(() => {
  if (this._ws?.readyState === WebSocket.OPEN) {
    this.send(OP.PING, {});
  }
}, PING_INTERVAL);
```

Но нет проверки, что PONG пришёл.

### Risk

При black-hole network соединение может казаться живым.

### Fix

```js
this._lastPong = Date.now();
```

И закрывать соединение, если PONG не пришёл за 2×PING_INTERVAL.

---

# 6. Проблемы UI / routing / storage

---

## 6.1. Возможный stored XSS через сообщения

Код рендеринга чата не показан, но если `renderChat()` вставляет `plaintext` через `innerHTML`, это критично.

Например:

```js
el.innerHTML = message.plaintext;
```

Тогда сообщение:

```html
<img src=x onerror=alert(1)>
```

выполнит JS.

### Fix

По умолчанию:

```js
el.textContent = message.plaintext;
```

Если нужен rich text:

- разрешить ограниченный markdown;
- использовать DOMPurify;
- никогда не вставлять raw HTML.

---

## 6.2. `routes` объявлен, но не используется

```js
const routes = {
  '#login':    () => showAuth('login'),
  '#register': () => showAuth('register'),
  ...
};
```

Это не уязвимость, но мёртвый код может запутать аудит.

---

## 6.3. В `app.js` есть подозрительные артефакты форматирования

Например:

```js
if (screen === 'chat'  & & userId) {
```

```js
rend erSearch(layout.searchScreen);
```

```js
renderProfile(layout.profileScr een);
```

Если это реальный код, то это синтаксические ошибки. Если это артефакты копирования — игнорируйте. Но перед аудитом нужно привести файл в актуальное состояние.

---

# 7. Криптографические мелочи

---

## 7.1. `encryptPairingHistory()` может перезаписать `version`

```js
export async function encryptPairingHistory(data, sharedSecret) {
  return e2eeEncrypt(JSON.stringify({ version: 1, ...data }), sharedSecret);
}
```

Если `data` содержит собственное поле `version`, оно перезапишет `version: 1`.

Если версия должна быть фиксированной:

```js
JSON.stringify({ ...data, version: 1 })
```

---

## 7.2. `KDF_ITERATIONS` не используется в `encryptIdentityEnvelope()`

В `encryptIdentityEnvelope()`:

```js
iterations: 600000,
```

А ниже:

```js
export const KDF_ITERATIONS = 600000;
```

Но `encryptIdentityEnvelope()` и `decryptIdentityEnvelope()` используют хардкод, а не константу.

Это не уязвимость, но риск рассинхронизации.

### Fix

```js
iterations: KDF_ITERATIONS,
```

---

## 7.3. `computeSafetyNumber()` имеет небольшой modulo bias

```js
const num = String(val % 100000).padStart(5, "0");
```

`2^32` не делится на 100000, поэтому есть минимальный bias.

Для safety number это не критично, но можно использовать rejection sampling, если хочется идеальной равномерности.

---

## 7.4. `verifySignature()` делает public key extractable

```js
const pubKey = await subtle.importKey(
  'raw',
  publicKeyBytes,
  { name: 'Ed25519' },
  true,
  ['verify']
);
```

Для публичного ключа `extractable: true` не является проблемой. Но для единообразия можно ставить `false`.

---

## 7.5. `generateKeyPair()` делает private key extractable

```js
const keyPair = await subtle.generateKey(
  { name: "X25519" },
  true,
  ["deriveBits"]
);
```

Это нужно, потому что код экспортирует приватный ключ:

```js
const privPkcs8 = new Uint8Array(await subtle.exportKey("pkcs8", keyPair.privateKey));
const privRaw = privPkcs8.slice(privPkcs8.length - 32);
```

Но это увеличивает поверхность утечки.

### Fix

Если возможно, хранить приватный ключ как non-extractable `CryptoKey`, а экспортировать только при явном user-initiated backup через secure wrapping.

---

# 8. Что сделано хорошо

Чтобы картина была объективной:

1. Используются современные AEAD-алгоритмы:
   - AES-GCM;
   - ChaCha20-Poly1305.

2. Есть HKDF с salt.

3. PBKDF2 имеет 600 000 итераций.

4. Для групповых сообщений используется AAD.

5. Есть защита от повторной расшифровки уже сохранённого plaintext:

```js
const existing = payload.msg_id ? await getMessage(payload.msg_id) : null;

if (
  existing?.plaintext &&
  !existing.plaintext.startsWith('[Ошибка расшифрования')
) {
  plaintext = existing.plaintext;
}
```

6. Есть TTL для `pendingAcks`.

7. Есть очистка IndexedDB при logout.

8. Есть миграция старого ключа из localStorage с удалением старой копии.

9. Есть retry-логика для нерасшифрованных сообщений.

10. Есть offline batch sync.

Но эти плюсы не отменяют протокольных проблем.

---

# 9. Приоритетный план исправлений

## Очередь 1 — критично

### 1. Убрать доверие к `plaintext`

```js
if (payload.plaintext) {
  throw new Error("Plaintext is not allowed");
}
```

### 2. Добавить подписи сообщений

Каждое сообщение должно иметь:

```json
{
  "sender_user_id": "...",
  "sender_device_id": "...",
  "recipient_user_id": "...",
  "recipient_device_id": "...",
  "client_msg_id": "...",
  "ciphertext": "...",
  "salt": "...",
  "nonce": "...",
  "signature": "..."
}
```

И подпись должна проверяться до расшифровки и отображения.

### 3. Подписывать key bundles

Сервер и/или владелец ключа должны подписывать:

```text
user_id | device_id | identity_key | signing_key | timestamp
```

Клиент должен проверять подписи.

### 4. Очищать `state.privateIK` при logout

```js
state.privateIK = null;
state.retryCounters.clear();
```

---

## Очередь 2 — high

### 5. Добавить AAD для pairwise-сообщений

Привязывать ciphertext к:

- sender;
- recipient;
- device;
- chat;
- message id;
- timestamp;
- protocol version.

### 6. Разделить криптодомены

Разные `info`:

```text
penik-pairwise-message-v1
penik-group-message-v1
penik-group-key-wrap-v1
penik-pairing-history-v1
penik-key-backup-v1
```

### 7. Добавить AAD для group key wrap

```js
wrapGroupKeyForDevice(
  groupKey,
  sharedSecret,
  groupId,
  keyVersion,
  recipientDeviceId,
  senderDeviceId
)
```

### 8. Добавить request_id в WS

Не коррелировать ответы только по opcode.

### 9. Очищать WS queue и pending replies при disconnect

```js
this._queue = [];
this._pendingReplies.clear();
```

---

## Очередь 3 — medium

### 10. Валидировать публичные ключи

```js
if (publicKey.length !== 32) {
  throw new Error("Invalid public key");
}
```

Убрать автоматический self-heal 44-byte base64-in-bytes.

### 11. Проверять all-zero shared secret

```js
if (sharedSecret.every(b => b === 0)) {
  throw new Error("Weak shared secret");
}
```

### 12. Использовать строковые ID

Не применять `Number()` к серверным ID, если они могут быть 64-bit.

### 13. Защитить DOM

- `textContent` вместо `innerHTML`;
- `CSS.escape()` для динамических селекторов;
- валидация `userId` из hash;
- sanitize rich text.

### 14. Требовать `recipient_device_id`

Не принимать сообщения без явного device routing.

### 15. Ограничить рост `retryCounters`

Добавить TTL и max size.

---

## Очередь 4 — архитектурно

### 16. Добавить forward secrecy

Минимально:

- ephemeral keys per session;
- signed prekeys;
- one-time prekeys.

Лучше:

- Signal-style Double Ratchet.

### 17. Перейти на Argon2id для passphrase backup

Если возможно через libsodium.

### 18. Хранить backup envelope с метаданными

```json
{
  "version": 2,
  "kdf": "argon2id",
  "memlimit": ...,
  "opslimit": ...,
  "salt": "...",
  "nonce": "...",
  "ciphertext": "...",
  "aad": "..."
}
```

### 19. Не использовать localStorage для секретов и identity

Лучше:

- httpOnly cookies для auth;
- серверный `/me`;
- минимальное доверие localStorage.

---

# 10. Итоговая оценка

Если отвечать коротко:

**Да, уязвимости есть.**

Самые серьёзные — не в отдельных строках шифрования, а в протоколе:

- нет криптографической аутентификации отправителя;
- нет подписанных ключей;
- нет forward secrecy;
- нет AAD для личных сообщений;
- есть доверие к серверу в вопросах, где E2EE не должен доверять серверу;
- приватный ключ может оставаться в памяти после logout;
- WebSocket-состояние очищается не полностью;
- есть потенциальные XSS и injection-риски.

Если модель угроз такая:

> сервер полностью доверенный, а защищаемся только от пассивного сетевого наблюдателя,

то часть проблем становится менее критичной.

Но если модель такая:

> сервер не должен читать или подделывать сообщения,

то текущая реализация не обеспечивает полноценный E2EE.
