const subtle = crypto.subtle;

let _sodium = null;
const _sodiumReady = import("libsodium-wrappers").then(async (mod) => {
  _sodium = mod.default;
  await _sodium.ready;
  return _sodium;
});

async function getSodium() {
  return _sodiumReady;
}

/* Encode public key bytes to base64 for API transmission */
export function encodeKey(bytes) {
  return btoa(String.fromCharCode(...bytes));
}

export function decodeKey(b64) {
  let cleanB64 = String(b64 || "").trim().replace(/ /g, "+");
  while (cleanB64.length % 4 !== 0) cleanB64 += "=";
  const bin = atob(cleanB64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export async function encryptIdentityEnvelope(identityData, passphrase) {
  const dek = crypto.getRandomValues(new Uint8Array(32));
  const ivDek = crypto.getRandomValues(new Uint8Array(12));
  const enc = new TextEncoder();
  const plaintextBytes = enc.encode(JSON.stringify(identityData, replacer));
  const dekKeyObj = await subtle.importKey('raw', dek, 'AES-GCM', false, ['encrypt']);
  const encryptedKeys = await subtle.encrypt(
    { name: 'AES-GCM', iv: ivDek },
    dekKeyObj,
    plaintextBytes
  );

  const saltKek = crypto.getRandomValues(new Uint8Array(16));
  const passphraseKey = await subtle.importKey(
    'raw',
    enc.encode(passphrase),
    'PBKDF2',
    false,
    ['deriveKey']
  );
  const kek = await subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: saltKek,
      iterations: 600000,
      hash: 'SHA-256'
    },
    passphraseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt']
  );

  const ivKek = crypto.getRandomValues(new Uint8Array(12));
  const encryptedDek = await subtle.encrypt(
    { name: 'AES-GCM', iv: ivKek },
    kek,
    dek
  );

  return {
    encrypted_dek: new Uint8Array(encryptedDek),
    iv_kek: ivKek,
    salt_kek: saltKek,
    encrypted_keys: new Uint8Array(encryptedKeys),
    iv_dek: ivDek
  };
}

export async function decryptIdentityEnvelope(envelope, passphrase) {
  const enc = new TextEncoder();
  const passphraseKey = await subtle.importKey(
    'raw',
    enc.encode(passphrase),
    'PBKDF2',
    false,
    ['deriveKey']
  );
  const kek = await subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: envelope.salt_kek,
      iterations: 600000,
      hash: 'SHA-256'
    },
    passphraseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['decrypt']
  );

  const dek = new Uint8Array(await subtle.decrypt(
    { name: 'AES-GCM', iv: envelope.iv_kek },
    kek,
    envelope.encrypted_dek
  ));

  const dekKeyObj = await subtle.importKey('raw', dek, 'AES-GCM', false, ['decrypt']);
  const decryptedBytes = await subtle.decrypt(
    { name: 'AES-GCM', iv: envelope.iv_dek },
    dekKeyObj,
    envelope.encrypted_keys
  );

  return JSON.parse(new TextDecoder().decode(decryptedBytes), reviver);
}

export async function rewrapEnvelope(envelope, oldPassphrase, newPassphrase) {
  const enc = new TextEncoder();
  const oldPassphraseKey = await subtle.importKey(
    'raw',
    enc.encode(oldPassphrase),
    'PBKDF2',
    false,
    ['deriveKey']
  );
  const oldKek = await subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: envelope.salt_kek,
      iterations: 600000,
      hash: 'SHA-256'
    },
    oldPassphraseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['decrypt']
  );

  const dek = new Uint8Array(await subtle.decrypt(
    { name: 'AES-GCM', iv: envelope.iv_kek },
    oldKek,
    envelope.encrypted_dek
  ));

  const newSaltKek = crypto.getRandomValues(new Uint8Array(16));
  const newPassphraseKey = await subtle.importKey(
    'raw',
    enc.encode(newPassphrase),
    'PBKDF2',
    false,
    ['deriveKey']
  );
  const newKek = await subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: newSaltKek,
      iterations: 600000,
      hash: 'SHA-256'
    },
    newPassphraseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt']
  );

  const newIvKek = crypto.getRandomValues(new Uint8Array(12));
  const newEncryptedDek = await subtle.encrypt(
    { name: 'AES-GCM', iv: newIvKek },
    newKek,
    dek
  );

  return {
    ...envelope,
    encrypted_dek: new Uint8Array(newEncryptedDek),
    iv_kek: newIvKek,
    salt_kek: newSaltKek
  };
}

export async function verifySignature(publicKeyBytes, signatureBytes, dataBytes) {
  try {
    const pubKey = await subtle.importKey(
      'raw',
      publicKeyBytes,
      { name: 'Ed25519' },
      true,
      ['verify']
    );
    return await subtle.verify(
      { name: 'Ed25519' },
      pubKey,
      signatureBytes,
      dataBytes
    );
  } catch (err) {
    console.error("Error verifying Ed25519 signature:", err);
    return false;
  }
}

// computeSafetyNumber derives the human-comparable fingerprint of a conversation
// from the two identity keys.
//
// There used to be three implementations of this — a dead SHA-512×12 variant here
// and two SHA-256×25 copies in the web and Android UIs. A fingerprint that differs
// between platforms is worse than none: the users compare, see different numbers,
// and conclude they are being intercepted. This is now the single web definition,
// byte-for-byte identical to the Android one.
//
// Shape: strip the legacy 0x05 type prefix, order the two keys by unsigned byte
// value (so the result does not depend on who is looking), SHA-256 over the 64
// concatenated bytes, then 5 groups of 5 digits.
export const SAFETY_NUMBER_BLOCKS = 5;

function normalizeIdentityKey(key) {
  const bytes = key instanceof Uint8Array ? key : new Uint8Array(key);
  const clean = bytes.length === 33 && bytes[0] === 5 ? bytes.subarray(1) : bytes;
  if (clean.length !== 32) {
    throw new Error(`safety number: expected a 32-byte identity key, got ${clean.length}`);
  }
  return clean;
}

// compareUnsigned orders keys by unsigned byte value. Uint8Array elements are
// already unsigned in JS; the helper exists so the ordering rule is stated in one
// place and stays aligned with the Kotlin side, where bytes are signed.
function compareUnsigned(a, b) {
  for (let i = 0; i < 32; i++) {
    if (a[i] !== b[i]) return a[i] - b[i];
  }
  return 0;
}

export async function computeSafetyNumber(identityKeysA, identityKeysB) {
  const listA = Array.isArray(identityKeysA) ? identityKeysA : [identityKeysA];
  const listB = Array.isArray(identityKeysB) ? identityKeysB : [identityKeysB];

  const allKeys = [...listA, ...listB]
    .filter(k => k != null)
    .map(normalizeIdentityKey)
    .sort(compareUnsigned);

  if (allKeys.length === 0) {
    throw new Error("safety number: no identity keys provided");
  }

  const concatenated = new Uint8Array(allKeys.length * 32);
  for (let i = 0; i < allKeys.length; i++) {
    concatenated.set(allKeys[i], i * 32);
  }

  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", concatenated));

  let digits = "";
  for (let i = 0; i + 1 < hash.length && digits.length < SAFETY_NUMBER_BLOCKS * 5; i += 2) {
    const val = (hash[i] << 8) | hash[i + 1];
    digits += String(val).padStart(5, "0").substring(0, 5);
  }

  const blocks = [];
  for (let i = 0; i < digits.length; i += 5) {
    blocks.push(digits.substring(i, i + 5));
  }
  return blocks.join(" ");
}

export function replacer(key, value) {
  if (value instanceof ArrayBuffer) {
    return {
      __type: 'ArrayBuffer',
      data: Array.from(new Uint8Array(value))
    };
  }
  if (value instanceof Uint8Array) {
    return {
      __type: 'Uint8Array',
      data: Array.from(value)
    };
  }
  return value;
}

export function reviver(key, value) {
  if (value && value.__type === 'ArrayBuffer') {
    return new Uint8Array(value.data).buffer;
  }
  if (value && value.__type === 'Uint8Array') {
    return new Uint8Array(value.data);
  }
  return value;
}

// E2EE Cryptography implementation (X25519, HKDF, ChaCha20-Poly1305 via libsodium)

export async function chacha20Poly1305Encrypt(keyBytes, nonceBytes, plaintextBytes, aadBytes = new Uint8Array(0)) {
  const sodium = await getSodium();
  const result = sodium.crypto_aead_chacha20poly1305_ietf_encrypt(
    plaintextBytes, aadBytes, null, nonceBytes, keyBytes
  );
  return result;
}

export async function chacha20Poly1305Decrypt(keyBytes, nonceBytes, ciphertextAndTag, aadBytes = new Uint8Array(0)) {
  const sodium = await getSodium();
  if (ciphertextAndTag.length < 16) {
    throw new Error("Ciphertext too short (must contain 16-byte tag)");
  }
  return sodium.crypto_aead_chacha20poly1305_ietf_decrypt(
    null, ciphertextAndTag, aadBytes, nonceBytes, keyBytes
  );
}

export async function encryptFileChaCha20(fileBytes) {
  const key = crypto.getRandomValues(new Uint8Array(32));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await chacha20Poly1305Encrypt(key, nonce, fileBytes);
  // Prepend nonce to ciphertext so file format is [12 bytes nonce][ciphertext+tag]
  const combined = new Uint8Array(nonce.length + ciphertext.length);
  combined.set(nonce, 0);
  combined.set(ciphertext, nonce.length);
  return { encryptedBytes: combined, key };
}

export async function decryptFileChaCha20(encryptedBytes, keyBytes) {
  if (encryptedBytes.length < 12 + 16) {
    throw new Error("Invalid encrypted file format: missing nonce or auth tag");
  }
  const nonce = encryptedBytes.slice(0, 12);
  const ciphertextAndTag = encryptedBytes.slice(12);
  return chacha20Poly1305Decrypt(keyBytes, nonce, ciphertextAndTag);
}

export async function generateKeyPair() {
  const keyPair = await subtle.generateKey(
    { name: "X25519" },
    true,
    ["deriveBits"]
  );
  
  const pubRaw = new Uint8Array(await subtle.exportKey("raw", /** @type {CryptoKeyPair} */ (keyPair).publicKey));
  const privPkcs8 = new Uint8Array(await subtle.exportKey("pkcs8", /** @type {CryptoKeyPair} */ (keyPair).privateKey));
  const privRaw = privPkcs8.slice(privPkcs8.length - 32);
  
  return { publicKey: pubRaw, privateKey: privRaw };
}

export async function deriveSharedSecret(privateKey, publicKey) {
  let cleanPublic = publicKey;
  if (cleanPublic.length === 44) {
    try {
      const asciiStr = String.fromCharCode(...cleanPublic);
      const decoded = new Uint8Array(atob(asciiStr).split("").map(c => c.charCodeAt(0)));
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

  const pkcs8 = new Uint8Array(48);
  pkcs8.set([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20], 0);
  pkcs8.set(privateKey, 16);

  const privKeyObj = await subtle.importKey(
    "publicKey" in {} ? "pkcs8" : "pkcs8",
    pkcs8,
    { name: "X25519" },
    false,
    ["deriveBits"]
  );

  const pubKeyObj = await subtle.importKey(
    "raw",
    cleanPublic,
    { name: "X25519" },
    false,
    []
  );

  const sharedSecret = await subtle.deriveBits(
    { name: "X25519", public: pubKeyObj },
    privKeyObj,
    256
  );

  const sharedSecretArr = new Uint8Array(sharedSecret);
  return sharedSecretArr;
}

export async function hkdfDerive(salt, ikm, info, length) {
  const keyMaterial = await subtle.importKey(
    "raw",
    ikm,
    "HKDF",
    false,
    ["deriveBits"]
  );
  const infoBytes = typeof info === "string" ? new TextEncoder().encode(info) : info;
  const derivedBits = await subtle.deriveBits(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: salt,
      info: infoBytes
    },
    keyMaterial,
    length * 8
  );
  return new Uint8Array(derivedBits);
}

export const PAIRWISE_PROTOCOL_VERSION = 1;

// buildPairwiseAAD binds message context (sender, recipient, clientMsgId, timestamp) into the AEAD tag.
export function buildPairwiseAAD(senderUserId, recipientUserId, clientMsgId = "", timestamp = 0) {
  const fields = [
    PAIRWISE_PROTOCOL_VERSION,
    String(senderUserId || 0),
    String(recipientUserId || 0),
    String(clientMsgId || ""),
    String(timestamp || 0),
  ];

  const chunks = [];
  for (const field of fields) {
    const bytes = new TextEncoder().encode(String(field));
    const len = new Uint8Array(4);
    new DataView(len.buffer).setUint32(0, bytes.length, false);
    chunks.push(len, bytes);
  }

  const totalLen = chunks.reduce((sum, c) => sum + c.length, 0);
  const out = new Uint8Array(totalLen);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

export async function e2eeEncrypt(plaintext, sharedSecret, info = "penik-pairwise-message-v1", aad = new Uint8Array(0)) {
  const salt = crypto.getRandomValues(new Uint8Array(32));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  
  const derivedKey = await hkdfDerive(salt, sharedSecret, info, 32);
  const plaintextBytes = typeof plaintext === "string" ? new TextEncoder().encode(plaintext) : plaintext;
  
  const ciphertext = await chacha20Poly1305Encrypt(derivedKey, nonce, plaintextBytes, aad);
  
  return { ciphertext, salt, nonce };
}

export async function e2eeDecrypt(ciphertext, sharedSecret, salt, nonce, info = "penik-pairwise-message-v1", aad = new Uint8Array(0)) {
  const derivedKey = await hkdfDerive(salt, sharedSecret, info, 32);
  return await chacha20Poly1305Decrypt(derivedKey, nonce, ciphertext, aad);
}

export async function encryptPairingHistory(data, sharedSecret) {
  return e2eeEncrypt(JSON.stringify({ version: 1, ...data }), sharedSecret, "penik-pairing-history-v1");
}

export async function decryptPairingHistory(envelope, sharedSecret) {
  const decodeUrl = value => {
    const normalized = String(value).replaceAll('-', '+').replaceAll('_', '/');
    const padded = normalized + '='.repeat((4 - normalized.length % 4) % 4);
    const binary = atob(padded);
    return Uint8Array.from(binary, char => char.charCodeAt(0));
  };
  const plaintext = await e2eeDecrypt(
    decodeUrl(envelope.ciphertext),
    sharedSecret,
    decodeUrl(envelope.salt),
    decodeUrl(envelope.nonce),
    "penik-pairing-history-v1"
  );
  return JSON.parse(new TextDecoder().decode(plaintext));
}

// Current PBKDF2 work factor for passphrase-derived backup keys. Kept in sync
// with encryptIdentityEnvelope (600k). LEGACY_KDF_ITERATIONS is only used to
// open backups written before this was raised from 100k.
export const KDF_ITERATIONS = 600000;
const LEGACY_KDF_ITERATIONS = 100000;

export async function deriveKeyFromPassphrase(passphrase, salt, iterations = KDF_ITERATIONS) {
  const enc = new TextEncoder();
  const baseKey = await subtle.importKey(
    "raw",
    enc.encode(passphrase),
    "PBKDF2",
    false,
    ["deriveKey"]
  );

  return subtle.deriveKey(
    {
      name: "PBKDF2",
      salt: salt,
      iterations: iterations,
      hash: "SHA-256"
    },
    baseKey,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

export async function encryptKeyBackup(privateKeyBytes, passphrase) {
  const salt = window.crypto.getRandomValues(new Uint8Array(16));
  const iv = window.crypto.getRandomValues(new Uint8Array(12));
  
  const aesKey = await deriveKeyFromPassphrase(passphrase, salt);
  
  const encrypted = await subtle.encrypt(
    { name: "AES-GCM", iv: iv },
    aesKey,
    privateKeyBytes
  );

  return {
    encryptedBlob: new Uint8Array(encrypted),
    salt: salt,
    iv: iv
  };
}

export async function decryptKeyBackup(encryptedBlob, salt, iv, passphrase) {
  // Backup envelopes do not record the iteration count, so try the current
  // work factor first and fall back to the legacy one for older backups.
  for (const iterations of [KDF_ITERATIONS, LEGACY_KDF_ITERATIONS]) {
    try {
      const aesKey = await deriveKeyFromPassphrase(passphrase, salt, iterations);
      const decrypted = await subtle.decrypt(
        { name: "AES-GCM", iv: iv },
        aesKey,
        encryptedBlob
      );
      return new Uint8Array(decrypted);
    } catch (e) {
      if (iterations === LEGACY_KDF_ITERATIONS) throw e;
    }
  }
  // Unreachable while the loop ends with LEGACY_KDF_ITERATIONS, but an explicit
  // throw keeps the contract "returns bytes or throws" from silently degrading
  // to undefined if the work-factor list changes.
  throw new Error("Не удалось расшифровать резервную копию ключа");
}

// ── Group E2EE ──
//
// A group uses a shared 32-byte group key per epoch (key_version). Each message
// derives its own message key via HKDF with a random 32-byte salt, so message
// keys never repeat even if a nonce collides. The group key itself is delivered
// to each device wrapped (encrypted) with that device's pairwise shared secret.

export const GROUP_PROTOCOL_VERSION = 2;

// buildGroupAAD binds the immutable message header (including sender_user_id) into the AEAD tag.
export function buildGroupAAD(groupId, keyVersion, senderUserId, messageId, createdAt) {
  const fields = [
    GROUP_PROTOCOL_VERSION,
    String(groupId),
    String(keyVersion),
    String(senderUserId || 0),
    String(messageId),
    String(createdAt),
  ];

  const chunks = [];
  for (const field of fields) {
    const bytes = new TextEncoder().encode(String(field));
    const len = new Uint8Array(4);
    new DataView(len.buffer).setUint32(0, bytes.length, false);
    chunks.push(len, bytes);
  }

  const totalLen = chunks.reduce((sum, c) => sum + c.length, 0);
  const out = new Uint8Array(totalLen);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

// buildGroupAADv1 is kept for backward compatibility with v1 group messages.
export function buildGroupAADv1(groupId, keyVersion, messageId, createdAt) {
  const fields = [
    1,
    String(groupId),
    String(keyVersion),
    String(messageId),
    String(createdAt),
  ];

  const chunks = [];
  for (const field of fields) {
    const bytes = new TextEncoder().encode(String(field));
    const len = new Uint8Array(4);
    new DataView(len.buffer).setUint32(0, bytes.length, false);
    chunks.push(len, bytes);
  }

  const totalLen = chunks.reduce((sum, c) => sum + c.length, 0);
  const out = new Uint8Array(totalLen);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

export function requireBytes(val, expectedLength = null, paramName = "field") {
  let bytes;
  if (val instanceof Uint8Array) {
    bytes = val;
  } else if (typeof val === "string") {
    bytes = decodeKey(val);
  } else if (val instanceof ArrayBuffer) {
    bytes = new Uint8Array(val);
  } else if (Array.isArray(val)) {
    bytes = new Uint8Array(val);
  } else {
    throw new Error(`Invalid type for ${paramName}: expected Uint8Array or Base64 string`);
  }

  if (expectedLength !== null && bytes.length !== expectedLength) {
    throw new Error(`Invalid byte length for ${paramName}: expected ${expectedLength}, got ${bytes.length}`);
  }

  return bytes;
}

export function generateGroupKey() {
  return crypto.getRandomValues(new Uint8Array(32));
}

// groupEncrypt encrypts a plaintext message under the group key for the given
// epoch. Returns { ciphertext, salt, nonce } — all Uint8Array.
export async function groupEncrypt(plaintext, groupKey, groupId, keyVersion, senderUserId, messageId, createdAt) {
  const salt = crypto.getRandomValues(new Uint8Array(32));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const messageKey = await hkdfDerive(salt, groupKey, "penik-group-message-v1", 32);
  const plaintextBytes = typeof plaintext === "string" ? new TextEncoder().encode(plaintext) : plaintext;
  const aad = buildGroupAAD(groupId, keyVersion, senderUserId, messageId, createdAt);
  const ciphertext = await chacha20Poly1305Encrypt(messageKey, nonce, plaintextBytes, aad);
  return { ciphertext, salt, nonce };
}

// groupDecrypt reverses groupEncrypt. Throws if the AAD or tag does not verify.
export async function groupDecrypt(ciphertext, groupKey, salt, nonce, groupId, keyVersion, senderUserId, messageId, createdAt) {
  const messageKey = await hkdfDerive(salt, groupKey, "penik-group-message-v1", 32);
  const aad = buildGroupAAD(groupId, keyVersion, senderUserId, messageId, createdAt);
  return await chacha20Poly1305Decrypt(messageKey, nonce, ciphertext, aad);
}

// wrapGroupKeyForDevice encrypts a group key for one recipient device using the
// pairwise X25519 shared secret. Returns { encryptedKey, salt, nonce }.
export async function wrapGroupKeyForDevice(groupKey, sharedSecret, groupId, keyVersion) {
  const aad = new TextEncoder().encode(`penik-group-key-wrap-v1|${groupId}|${keyVersion}`);
  const { ciphertext, salt, nonce } = await e2eeEncrypt(groupKey, sharedSecret, "penik-group-key-wrap-v1", aad);
  return { encryptedKey: ciphertext, salt, nonce };
}

// unwrapGroupKey decrypts a group key envelope with the pairwise shared secret.
export async function unwrapGroupKey(encryptedKey, sharedSecret, salt, nonce, groupId, keyVersion) {
  const aad = new TextEncoder().encode(`penik-group-key-wrap-v1|${groupId}|${keyVersion}`);
  return e2eeDecrypt(encryptedKey, sharedSecret, salt, nonce, "penik-group-key-wrap-v1", aad);
}

export async function derivePublicKey(privateKey) {
  const sodium = await getSodium();
  return sodium.crypto_scalarmult_base(privateKey);
}
