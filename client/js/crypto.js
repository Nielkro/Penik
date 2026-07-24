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
  const bin = atob(b64);
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

export async function computeSafetyNumber(ikPubA, ikPubB) {
  const cleanA = ikPubA.length === 33 && ikPubA[0] === 5 ? ikPubA.slice(1) : ikPubA;
  const cleanB = ikPubB.length === 33 && ikPubB[0] === 5 ? ikPubB.slice(1) : ikPubB;

  const keys = [cleanA, cleanB].sort((a, b) => {
    for (let i = 0; i < 32; i++) {
      if (a[i] !== b[i]) return a[i] - b[i];
    }
    return 0;
  });

  const concatenated = new Uint8Array(64);
  concatenated.set(keys[0], 0);
  concatenated.set(keys[1], 32);

  const hashBuffer = await crypto.subtle.digest("SHA-512", concatenated);
  const hash = new Uint8Array(hashBuffer);

  const groups = [];
  const view = new DataView(hash.buffer);
  for (let i = 0; i < 12; i++) {
    const val = view.getUint32(i * 4, false);
    const num = String(val % 100000).padStart(5, "0");
    groups.push(num);
  }
  return groups.join(" ");
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

export async function generateKeyPair() {
  const keyPair = await subtle.generateKey(
    { name: "X25519" },
    true,
    ["deriveBits"]
  );
  
  const pubRaw = new Uint8Array(await subtle.exportKey("raw", keyPair.publicKey));
  const privPkcs8 = new Uint8Array(await subtle.exportKey("pkcs8", keyPair.privateKey));
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

export async function e2eeEncrypt(plaintext, sharedSecret, info = "penik-pairwise-message-v1") {
  const salt = crypto.getRandomValues(new Uint8Array(32));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  
  const derivedKey = await hkdfDerive(salt, sharedSecret, info, 32);
  const plaintextBytes = typeof plaintext === "string" ? new TextEncoder().encode(plaintext) : plaintext;
  
  const ciphertext = await chacha20Poly1305Encrypt(derivedKey, nonce, plaintextBytes);
  
  return { ciphertext, salt, nonce };
}

export async function e2eeDecrypt(ciphertext, sharedSecret, salt, nonce, info = "penik-pairwise-message-v1") {
  const derivedKey = await hkdfDerive(salt, sharedSecret, info, 32);
  
  return await chacha20Poly1305Decrypt(derivedKey, nonce, ciphertext);
}

export async function encryptPairingHistory(data, sharedSecret) {
  return e2eeEncrypt(JSON.stringify({ version: 1, ...data }), sharedSecret, "penik-pairing-history-v1");
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
}

// ── Group E2EE ──
//
// A group uses a shared 32-byte group key per epoch (key_version). Each message
// derives its own message key via HKDF with a random 32-byte salt, so message
// keys never repeat even if a nonce collides. The group key itself is delivered
// to each device wrapped (encrypted) with that device's pairwise shared secret.

export const GROUP_PROTOCOL_VERSION = 1;

// buildGroupAAD binds the immutable message header into the AEAD tag using length-prefixed encoding.
// sender_user_id is intentionally NOT included: the server assigns sender
// authoritatively, so a client-supplied sender cannot be verified.
export function buildGroupAAD(groupId, keyVersion, messageId, createdAt) {
  const fields = [
    GROUP_PROTOCOL_VERSION,
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
// epoch. Returns { ciphertext, salt, nonce } — all Uint8Array. createdAt must be
// the same value stored on the message so the AAD verifies on decrypt.
export async function groupEncrypt(plaintext, groupKey, groupId, keyVersion, messageId, createdAt) {
  const salt = crypto.getRandomValues(new Uint8Array(32));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const messageKey = await hkdfDerive(salt, groupKey, "penik-group-message-v1", 32);
  const plaintextBytes = typeof plaintext === "string" ? new TextEncoder().encode(plaintext) : plaintext;
  const aad = buildGroupAAD(groupId, keyVersion, messageId, createdAt);
  const ciphertext = await chacha20Poly1305Encrypt(messageKey, nonce, plaintextBytes, aad);
  return { ciphertext, salt, nonce };
}

// groupDecrypt reverses groupEncrypt. Throws if the AAD or tag does not verify.
export async function groupDecrypt(ciphertext, groupKey, salt, nonce, groupId, keyVersion, messageId, createdAt) {
  const messageKey = await hkdfDerive(salt, groupKey, "penik-group-message-v1", 32);
  const aad = buildGroupAAD(groupId, keyVersion, messageId, createdAt);
  return chacha20Poly1305Decrypt(messageKey, nonce, ciphertext, aad);
}

// wrapGroupKeyForDevice encrypts a group key for one recipient device using the
// pairwise X25519 shared secret. Returns { encryptedKey, salt, nonce }.
export async function wrapGroupKeyForDevice(groupKey, sharedSecret) {
  const { ciphertext, salt, nonce } = await e2eeEncrypt(groupKey, sharedSecret, "penik-group-key-wrap-v1");
  return { encryptedKey: ciphertext, salt, nonce };
}

// unwrapGroupKey decrypts a group key envelope with the pairwise shared secret.
export async function unwrapGroupKey(encryptedKey, sharedSecret, salt, nonce) {
  return e2eeDecrypt(encryptedKey, sharedSecret, salt, nonce, "penik-group-key-wrap-v1");
}

export async function derivePublicKey(privateKey) {
  const sodium = await getSodium();
  return sodium.crypto_scalarmult_base(privateKey);
}


