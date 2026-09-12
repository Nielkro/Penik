import { defaultWordCoder } from "./wordcoder.js";
import initWasm, * as wasmCrypto from "../pkg/penik-crypto-wasm/penik_crypto.js";

const subtle = crypto.subtle;

export const MIN_CRYPTO_CORE_VERSION = 1;
export let cryptoCoreVersion = 0;

let _wasmReady = null;

export async function getWasm() {
  if (!_wasmReady) {
    _wasmReady = (async () => {
      if (typeof window === "undefined" && typeof process !== "undefined") {
        // Node.js environment
        const fs = await import(/* @vite-ignore */ "node:fs");
        const wasmPath = new URL("../pkg/penik-crypto-wasm/penik_crypto_bg.wasm", import.meta.url);
        const bytes = fs.readFileSync(wasmPath);
        await initWasm({ module_or_path: bytes });
      } else {
        // Browser / Vite environment
        await initWasm();
      }
      try {
        const getVer = wasmCrypto["penikCryptoVersion"];
        if (typeof getVer === "function") {
          cryptoCoreVersion = getVer();
          console.info(`[crypto] penik-crypto WASM core initialized (version: ${cryptoCoreVersion})`);
          if (cryptoCoreVersion < MIN_CRYPTO_CORE_VERSION) {
            console.warn(`[crypto] Outdated penik-crypto WASM core: version ${cryptoCoreVersion}, expected >= ${MIN_CRYPTO_CORE_VERSION}`);
          }
        } else {
          cryptoCoreVersion = 0;
          console.warn("[crypto] penik-crypto WASM core initialized (legacy / unversioned)");
        }
      } catch (e) {
        cryptoCoreVersion = 0;
      }
      return wasmCrypto;
    })();
  }
  return _wasmReady;
}

// Preload WASM core
getWasm().catch((err) => {
  console.error("Failed to initialize penik-crypto WASM:", err);
});

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

export const SAFETY_NUMBER_BLOCKS = 5;

export async function computeSafetyNumber(identityKeysA, identityKeysB) {
  const wasm = await getWasm();
  return wasm.computeSafetyNumber(identityKeysA, identityKeysB);
}

export async function computeSafetyFingerprint(identityKeysA, identityKeysB, userId = null) {
  const wasm = await getWasm();
  const fp = wasm.computeSafetyFingerprint(identityKeysA, identityKeysB, userId ? String(userId) : null);
  return {
    number: fp.number,
    words: fp.words,
    hex: fp.hex,
    qrPayload: fp.qrPayload
  };
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

// ── Penik E2EE via penik-crypto (WebAssembly micro-core) ──

export async function chacha20Poly1305Encrypt(keyBytes, nonceBytes, plaintextBytes, aadBytes = new Uint8Array(0)) {
  const wasm = await getWasm();
  return wasm.chacha20Poly1305Encrypt(
    requireBytes(keyBytes, 32, "keyBytes"),
    requireBytes(nonceBytes, 12, "nonceBytes"),
    plaintextBytes,
    aadBytes
  );
}

export async function chacha20Poly1305Decrypt(keyBytes, nonceBytes, ciphertextAndTag, aadBytes = new Uint8Array(0)) {
  const wasm = await getWasm();
  if (ciphertextAndTag.length < 16) {
    throw new Error("Ciphertext too short (must contain 16-byte tag)");
  }
  return wasm.chacha20Poly1305Decrypt(
    requireBytes(keyBytes, 32, "keyBytes"),
    requireBytes(nonceBytes, 12, "nonceBytes"),
    ciphertextAndTag,
    aadBytes
  );
}

export async function encryptFileChaCha20(fileBytes) {
  const wasm = await getWasm();
  const res = wasm.encryptFileChaCha20(fileBytes);
  return {
    encryptedBytes: res.encryptedBytes,
    key: res.key
  };
}

export async function decryptFileChaCha20(encryptedBytes, keyBytes) {
  const wasm = await getWasm();
  if (encryptedBytes.length < 12 + 16) {
    throw new Error("Invalid encrypted file format: missing nonce or auth tag");
  }
  return wasm.decryptFileChaCha20(encryptedBytes, requireBytes(keyBytes, 32, "keyBytes"));
}

export async function generateKeyPair() {
  const wasm = await getWasm();
  const kp = wasm.generateKeyPair();
  return {
    publicKey: kp.publicKey,
    privateKey: kp.privateKey
  };
}

export async function derivePublicKey(privateKey) {
  const wasm = await getWasm();
  return wasm.derivePublicKey(requireBytes(privateKey, 32, "privateKey"));
}

export async function deriveSharedSecret(privateKey, publicKey) {
  const wasm = await getWasm();
  const priv = requireBytes(privateKey, 32, "privateKey");
  const pub = requireBytes(publicKey, null, "publicKey");
  return wasm.deriveSharedSecret(priv, pub);
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
export const PAIRWISE_PROTOCOL_VERSION_V2 = 2;

// buildPairwiseAADV2 binds message context (sender, recipient, clientMsgId) into the AEAD tag without timestamp.
export function buildPairwiseAADV2(senderUserId, recipientUserId, clientMsgId = "") {
  const fields = [
    PAIRWISE_PROTOCOL_VERSION_V2,
    String(senderUserId || 0),
    String(recipientUserId || 0),
    String(clientMsgId || ""),
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

// buildPairwiseAAD binds message context. If timestamp is null or undefined, V2 is used.
export function buildPairwiseAAD(senderUserId, recipientUserId, clientMsgId = "", timestamp = null) {
  if (timestamp === null || timestamp === undefined) {
    return buildPairwiseAADV2(senderUserId, recipientUserId, clientMsgId);
  }
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
  const wasm = await getWasm();
  const plaintextBytes = typeof plaintext === "string" ? new TextEncoder().encode(plaintext) : plaintext;
  const res = wasm.e2eeEncrypt(plaintextBytes, sharedSecret, info, aad);
  return {
    ciphertext: res.ciphertext,
    salt: res.salt,
    nonce: res.nonce
  };
}

export async function e2eeDecrypt(ciphertext, sharedSecret, salt, nonce, info = "penik-pairwise-message-v1", aad = new Uint8Array(0)) {
  const wasm = await getWasm();
  return wasm.e2eeDecrypt(ciphertext, sharedSecret, salt, nonce, info, aad);
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
  throw new Error("Не удалось расшифровать резервную копию ключа");
}

// ── Group E2EE ──

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

export async function groupEncrypt(plaintext, groupKey, groupId, keyVersion, senderUserId, messageId, createdAt) {
  const wasm = await getWasm();
  const plaintextBytes = typeof plaintext === "string" ? new TextEncoder().encode(plaintext) : plaintext;
  const res = wasm.groupEncrypt(
    plaintextBytes,
    groupKey,
    BigInt(groupId),
    BigInt(keyVersion),
    BigInt(senderUserId || 0),
    String(messageId),
    BigInt(createdAt)
  );
  return {
    ciphertext: res.ciphertext,
    salt: res.salt,
    nonce: res.nonce
  };
}

export async function groupDecrypt(ciphertext, groupKey, salt, nonce, groupId, keyVersion, senderUserId, messageId, createdAt) {
  const wasm = await getWasm();
  return wasm.groupDecrypt(
    ciphertext,
    groupKey,
    salt,
    nonce,
    BigInt(groupId),
    BigInt(keyVersion),
    BigInt(senderUserId || 0),
    String(messageId),
    BigInt(createdAt)
  );
}

export async function wrapGroupKeyForDevice(groupKey, sharedSecret, groupId, keyVersion) {
  const wasm = await getWasm();
  const res = wasm.wrapGroupKeyForDevice(
    groupKey,
    sharedSecret,
    BigInt(groupId),
    BigInt(keyVersion)
  );
  return {
    encryptedKey: res.encryptedKey,
    salt: res.salt,
    nonce: res.nonce
  };
}

export async function unwrapGroupKey(encryptedKey, sharedSecret, salt, nonce, groupId, keyVersion) {
  const wasm = await getWasm();
  return wasm.unwrapGroupKey(
    encryptedKey,
    sharedSecret,
    salt,
    nonce,
    BigInt(groupId),
    BigInt(keyVersion)
  );
}
