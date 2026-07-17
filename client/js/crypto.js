const subtle = crypto.subtle;

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

// E2EE Cryptography implementation (X25519, HKDF, ChaCha20-Poly1305)

function rotateLeft(v, n) {
  return (v << n) | (v >>> (32 - n));
}

function chachaQuarterRound(state, a, b, c, d) {
  state[a] = (state[a] + state[b]) | 0;
  state[d] ^= state[a];
  state[d] = rotateLeft(state[d], 16);

  state[c] = (state[c] + state[d]) | 0;
  state[b] ^= state[c];
  state[b] = rotateLeft(state[b], 12);

  state[a] = (state[a] + state[b]) | 0;
  state[d] ^= state[a];
  state[d] = rotateLeft(state[d], 8);

  state[c] = (state[c] + state[d]) | 0;
  state[b] ^= state[c];
  state[b] = rotateLeft(state[b], 7);
}

function chachaBlock(key, nonce, counter) {
  const state = new Int32Array(16);
  state[0] = 0x61707865;
  state[1] = 0x3320646e;
  state[2] = 0x79622d32;
  state[3] = 0x6b206574;
  
  for (let i = 0; i < 8; i++) {
    state[4 + i] = key[i];
  }
  
  state[12] = counter;
  
  state[13] = nonce[0];
  state[14] = nonce[1];
  state[15] = nonce[2];

  const initial = new Int32Array(state);

  for (let i = 0; i < 10; i++) {
    chachaQuarterRound(state, 0, 4, 8, 12);
    chachaQuarterRound(state, 1, 5, 9, 13);
    chachaQuarterRound(state, 2, 6, 10, 14);
    chachaQuarterRound(state, 3, 7, 11, 15);
    
    chachaQuarterRound(state, 0, 5, 10, 15);
    chachaQuarterRound(state, 1, 6, 11, 12);
    chachaQuarterRound(state, 2, 7, 8, 13);
    chachaQuarterRound(state, 3, 4, 9, 14);
  }

  const out = new Uint8Array(64);
  const outView = new DataView(out.buffer);
  for (let i = 0; i < 16; i++) {
    outView.setInt32(i * 4, (state[i] + initial[i]) | 0, true);
  }
  return out;
}

class Poly1305 {
  constructor(key) {
    this.r = new Uint8Array(16);
    this.s = new Uint8Array(16);
    this.r.set(key.subarray(0, 16));
    this.s.set(key.subarray(16, 32));

    this.r[3] &= 15;
    this.r[7] &= 15;
    this.r[11] &= 15;
    this.r[15] &= 15;
    this.r[4] &= 252;
    this.r[8] &= 252;
    this.r[12] &= 252;

    this.r_big = 0n;
    for (let i = 0; i < 16; i++) {
      this.r_big |= BigInt(this.r[i]) << BigInt(8 * i);
    }

    this.s_big = 0n;
    for (let i = 0; i < 16; i++) {
      this.s_big |= BigInt(this.s[i]) << BigInt(8 * i);
    }

    this.h = 0n;
    this.p = (1n << 130n) - 5n;
  }

  update(message) {
    const len = message.length;
    let offset = 0;
    while (offset < len) {
      const chunkLen = Math.min(16, len - offset);
      let m = 0n;
      for (let i = 0; i < chunkLen; i++) {
        m |= BigInt(message[offset + i]) << BigInt(8 * i);
      }
      m |= 1n << BigInt(8 * chunkLen);

      this.h = (this.h + m) % this.p;
      this.h = (this.h * this.r_big) % this.p;

      offset += chunkLen;
    }
  }

  digest() {
    let result = (this.h + this.s_big) % (1n << 128n);
    const tag = new Uint8Array(16);
    for (let i = 0; i < 16; i++) {
      tag[i] = Number((result >> BigInt(8 * i)) & 0xffn);
    }
    return tag;
  }
}

function chacha20EncryptDecrypt(keyBytes, nonceBytes, dataBytes, startCounter = 1) {
  const key = new Int32Array(8);
  const keyView = new DataView(keyBytes.buffer, keyBytes.byteOffset, keyBytes.byteLength);
  for (let i = 0; i < 8; i++) key[i] = keyView.getInt32(i * 4, true);

  const nonce = new Int32Array(3);
  const nonceView = new DataView(nonceBytes.buffer, nonceBytes.byteOffset, nonceBytes.byteLength);
  for (let i = 0; i < 3; i++) nonce[i] = nonceView.getInt32(i * 4, true);

  const out = new Uint8Array(dataBytes.length);
  let blockIndex = 0;
  let counter = startCounter;

  while (blockIndex < dataBytes.length) {
    const keystream = chachaBlock(key, nonce, counter);
    const chunkLen = Math.min(64, dataBytes.length - blockIndex);
    for (let i = 0; i < chunkLen; i++) {
      out[blockIndex + i] = dataBytes[blockIndex + i] ^ keystream[i];
    }
    blockIndex += chunkLen;
    counter++;
  }
  return out;
}

export function chacha20Poly1305Encrypt(keyBytes, nonceBytes, plaintextBytes, aadBytes = new Uint8Array(0)) {
  const polyKeyBlock = chacha20EncryptDecrypt(keyBytes, nonceBytes, new Uint8Array(64), 0);
  const polyKey = polyKeyBlock.subarray(0, 32);

  const ciphertext = chacha20EncryptDecrypt(keyBytes, nonceBytes, plaintextBytes, 1);

  const poly = new Poly1305(polyKey);
  
  if (aadBytes.length > 0) {
    poly.update(aadBytes);
    if (aadBytes.length % 16 !== 0) {
      poly.update(new Uint8Array(16 - (aadBytes.length % 16)));
    }
  }

  if (ciphertext.length > 0) {
    poly.update(ciphertext);
    if (ciphertext.length % 16 !== 0) {
      poly.update(new Uint8Array(16 - (ciphertext.length % 16)));
    }
  }

  const lenBuf = new Uint8Array(16);
  const lenView = new DataView(lenBuf.buffer);
  lenView.setBigUint64(0, BigInt(aadBytes.length), true);
  lenView.setBigUint64(8, BigInt(ciphertext.length), true);
  poly.update(lenBuf);

  const tag = poly.digest();

  const result = new Uint8Array(ciphertext.length + 16);
  result.set(ciphertext, 0);
  result.set(tag, ciphertext.length);
  return result;
}

export function chacha20Poly1305Decrypt(keyBytes, nonceBytes, ciphertextAndTag, aadBytes = new Uint8Array(0)) {
  if (ciphertextAndTag.length < 16) {
    throw new Error("Ciphertext too short (must contain 16-byte tag)");
  }
  const ciphertext = ciphertextAndTag.subarray(0, ciphertextAndTag.length - 16);
  const tag = ciphertextAndTag.subarray(ciphertextAndTag.length - 16);

  const polyKeyBlock = chacha20EncryptDecrypt(keyBytes, nonceBytes, new Uint8Array(64), 0);
  const polyKey = polyKeyBlock.subarray(0, 32);

  const poly = new Poly1305(polyKey);
  
  if (aadBytes.length > 0) {
    poly.update(aadBytes);
    if (aadBytes.length % 16 !== 0) {
      poly.update(new Uint8Array(16 - (aadBytes.length % 16)));
    }
  }

  if (ciphertext.length > 0) {
    poly.update(ciphertext);
    if (ciphertext.length % 16 !== 0) {
      poly.update(new Uint8Array(16 - (ciphertext.length % 16)));
    }
  }

  const lenBuf = new Uint8Array(16);
  const lenView = new DataView(lenBuf.buffer);
  lenView.setBigUint64(0, BigInt(aadBytes.length), true);
  lenView.setBigUint64(8, BigInt(ciphertext.length), true);
  poly.update(lenBuf);

  const computedTag = poly.digest();

  let diff = 0;
  for (let i = 0; i < 16; i++) {
    diff |= tag[i] ^ computedTag[i];
  }
  if (diff !== 0) {
    throw new Error("Invalid MAC: Decryption failed");
  }

  return chacha20EncryptDecrypt(keyBytes, nonceBytes, ciphertext, 1);
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
  const cleanPublic = publicKey.length === 33 && publicKey[0] === 5 ? publicKey.slice(1) : publicKey;

  const pkcs8 = new Uint8Array(48);
  pkcs8.set([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20], 0);
  pkcs8.set(privateKey, 16);

  const privKeyObj = await subtle.importKey(
    "pkcs8",
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

  return new Uint8Array(sharedSecret);
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

export async function e2eeEncrypt(plaintext, sharedSecret) {
  const salt = crypto.getRandomValues(new Uint8Array(32));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  
  const derivedKey = await hkdfDerive(salt, sharedSecret, "penik-e2ee-v1", 32);
  const plaintextBytes = typeof plaintext === "string" ? new TextEncoder().encode(plaintext) : plaintext;
  
  const ciphertext = chacha20Poly1305Encrypt(derivedKey, nonce, plaintextBytes);
  
  return { ciphertext, salt, nonce };
}

export async function e2eeDecrypt(ciphertext, sharedSecret, salt, nonce) {
  const derivedKey = await hkdfDerive(salt, sharedSecret, "penik-e2ee-v1", 32);
  
  return chacha20Poly1305Decrypt(derivedKey, nonce, ciphertext);
}


