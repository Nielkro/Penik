/* E2EE: X25519 key agreement + AES-256-GCM + Double Ratchet protocol */

const subtle = crypto.subtle;

/* ── Key generation ── */

export async function generateIdentityKeyPair() {
  const dh = await subtle.generateKey({ name: 'X25519' }, true, ['deriveKey', 'deriveBits']);
  const sig = await subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);

  const dhPubRaw = await subtle.exportKey('raw', dh.publicKey);
  const dhPrivJwk = await subtle.exportKey('jwk', dh.privateKey);

  const sigPubRaw = await subtle.exportKey('raw', sig.publicKey);
  const sigPrivJwk = await subtle.exportKey('jwk', sig.privateKey);

  return {
    privateKey: dh.privateKey,
    publicKey: dh.publicKey,
    pubRaw: new Uint8Array(dhPubRaw),
    privJwk: dhPrivJwk,

    sigPrivateKey: sig.privateKey,
    sigPublicKey: sig.publicKey,
    sigPubRaw: new Uint8Array(sigPubRaw),
    sigPrivJwk
  };
}

export async function generateSignedPreKey(sigPrivateKey) {
  const kp = await subtle.generateKey({ name: 'X25519' }, true, ['deriveKey', 'deriveBits']);
  const pubRaw = new Uint8Array(await subtle.exportKey('raw', kp.publicKey));
  const privJwk = await subtle.exportKey('jwk', kp.privateKey);

  const sig = await subtle.sign(
    { name: 'Ed25519' },
    sigPrivateKey,
    pubRaw
  );

  return {
    privateKey: kp.privateKey,
    publicKey: kp.publicKey,
    pubRaw,
    privJwk,
    sig: new Uint8Array(sig),
  };
}

export async function generateOneTimeKeys(count) {
  const keys = [];
  for (let i = 0; i < count; i++) {
    const kp = await subtle.generateKey({ name: 'X25519' }, true, ['deriveKey', 'deriveBits']);
    const pubRaw = new Uint8Array(await subtle.exportKey('raw', kp.publicKey));
    const privJwk = await subtle.exportKey('jwk', kp.privateKey);
    keys.push({ privateKey: kp.privateKey, publicKey: kp.publicKey, pubRaw, privJwk });
  }
  return keys;
}



/* ── X3DH key agreement (initiator) ── */

/* x3dhInitiate: produce a shared secret bytes (32 bytes)
   ourIK      — our identity CryptoKey (X25519 private)
   ourEK      — ephemeral key pair we generate per session
   theirIK    — their identity pub bytes
   theirSPK   — their signed prekey pub bytes
   theirOPK   — their one-time prekey pub bytes (optional) */
export async function x3dhInitiate(ourIKPriv, theirIKPub, theirSPKPub, theirOPKPub) {
  /* Generate ephemeral key pair */
  const EK = await subtle.generateKey({ name: 'X25519' }, true, ['deriveKey', 'deriveBits']);

  const [theirIK, theirSPK] = await Promise.all([
    importX25519Pub(theirIKPub),
    importX25519Pub(theirSPKPub),
  ]);

  /* DH1 = DH(IK_A, SPK_B) */
  const dh1 = await diffieHellman(ourIKPriv, theirSPK);
  /* DH2 = DH(EK_A, IK_B) */
  const dh2 = await diffieHellman(EK.privateKey, theirIK);
  /* DH3 = DH(EK_A, SPK_B) */
  const dh3 = await diffieHellman(EK.privateKey, theirSPK);

  const parts = [dh1, dh2, dh3];

  if (theirOPKPub) {
    const theirOPK = await importX25519Pub(theirOPKPub);
    /* DH4 = DH(EK_A, OPK_B) */
    const dh4 = await diffieHellman(EK.privateKey, theirOPK);
    parts.push(dh4);
  }

  /* Concatenate all DH outputs and run through HKDF */
  const ikm = concatU8(...parts);
  const sharedSecret = await hkdf(ikm, 32, 'X3DH');

  const ekPubRaw = new Uint8Array(await subtle.exportKey('raw', EK.publicKey));

  return { sharedSecret, ekPubRaw };
}

/* X3DH responder: reconstruct shared secret from incoming message */
export async function x3dhRespond(ourIKPriv, ourSPKPriv, ourOPKPriv, theirIKPub, theirEKPub) {
  const [theirIK, theirEK] = await Promise.all([
    importX25519Pub(theirIKPub),
    importX25519Pub(theirEKPub),
  ]);

  const dh1 = await diffieHellman(ourSPKPriv, theirIK);
  const dh2 = await diffieHellman(ourIKPriv, theirEK);
  const dh3 = await diffieHellman(ourSPKPriv, theirEK);

  const parts = [dh1, dh2, dh3];

  if (ourOPKPriv) {
    const dh4 = await diffieHellman(ourOPKPriv, theirEK);
    parts.push(dh4);
  }

  const ikm = concatU8(...parts);
  return hkdf(ikm, 32, 'X3DH');
}

/* ── AES-256-GCM encrypt/decrypt ── */

export async function encryptMessage(sharedSecretBytes, plaintext, aadBytes = null) {
  const aesKey = await importAESKey(sharedSecretBytes);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const enc = new TextEncoder();
  const params = { name: 'AES-GCM', iv };
  if (aadBytes) {
    params.additionalData = aadBytes;
  }
  const ciphertext = new Uint8Array(
    await subtle.encrypt(params, aesKey, enc.encode(plaintext))
  );
  /* Pack iv (12 bytes) + ciphertext together */
  const out = new Uint8Array(12 + ciphertext.length);
  out.set(iv);
  out.set(ciphertext, 12);
  return out;
}

export async function decryptMessage(sharedSecretBytes, cipherBytes, aadBytes = null) {
  if (cipherBytes.length < 13) throw new Error('cipher_bytes too short');
  const iv = cipherBytes.slice(0, 12);
  const ciphertext = cipherBytes.slice(12);
  const aesKey = await importAESKey(sharedSecretBytes);
  const params = { name: 'AES-GCM', iv };
  if (aadBytes) {
    params.additionalData = aadBytes;
  }
  const plain = await subtle.decrypt(params, aesKey, ciphertext);
  return new TextDecoder().decode(plain);
}

/* ── Double Ratchet KDF Functions ── */

export async function kdf_rk(rootKeyBytes, dhSharedSecretBytes, info = "DoubleRatchetRoot") {
  const ikmKey = await subtle.importKey('raw', dhSharedSecretBytes, 'HKDF', false, ['deriveBits']);
  const bits = await subtle.deriveBits(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: rootKeyBytes,
      info: new TextEncoder().encode(info),
    },
    ikmKey,
    64 * 8
  );
  const derived = new Uint8Array(bits);
  return {
    newRootKey: derived.slice(0, 32),
    chainKey: derived.slice(32, 64)
  };
}

export async function kdf_ck(chainKeyBytes) {
  const key = await subtle.importKey(
    "raw", chainKeyBytes,
    { name: "HMAC", hash: "SHA-256" },
    false, ["sign"]
  );
  const messageKey = new Uint8Array(
    await subtle.sign("HMAC", key, new Uint8Array([0x01]))
  );
  const newChainKey = new Uint8Array(
    await subtle.sign("HMAC", key, new Uint8Array([0x02]))
  );
  return { newChainKey, messageKey };
}


/* ── Key import/export helpers ── */

export async function importX25519Priv(jwk) {
  return subtle.importKey('jwk', jwk, { name: 'X25519' }, false, ['deriveKey', 'deriveBits']);
}

export async function importX25519Pub(rawBytes) {
  return subtle.importKey('raw', rawBytes, { name: 'X25519' }, false, []);
}

export async function exportPubRaw(cryptoKey) {
  return new Uint8Array(await subtle.exportKey('raw', cryptoKey));
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

/* ── Internal helpers ── */

export async function diffieHellman(privKey, pubKey) {
  const bits = await subtle.deriveBits(
    { name: 'X25519', public: pubKey },
    privKey,
    256
  );
  return new Uint8Array(bits);
}

export async function generateDH() {
  const kp = await subtle.generateKey({ name: 'X25519' }, true, ['deriveKey', 'deriveBits']);
  const pubRaw = new Uint8Array(await subtle.exportKey('raw', kp.publicKey));
  const privJwk = await subtle.exportKey('jwk', kp.privateKey);
  return {
    privateKey: kp.privateKey,
    publicKey: kp.publicKey,
    pubRaw,
    privJwk
  };
}


async function hkdf(ikm, length, info) {
  const salt = new Uint8Array(32);
  const ikmKey = await subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  const bits = await subtle.deriveBits(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt,
      info: new TextEncoder().encode(info),
    },
    ikmKey,
    length * 8
  );
  return new Uint8Array(bits);
}

async function importAESKey(rawBytes) {
  return subtle.importKey('raw', rawBytes, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

function u8(buf) { return new Uint8Array(buf); }

function concatU8(...arrays) {
  const total = arrays.reduce((n, a) => n + a.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const a of arrays) { out.set(a, off); off += a.length; }
  return out;
}

export async function encryptIdentityWithPassphrase(identityData, passphrase) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const enc = new TextEncoder();
  const passphraseKey = await subtle.importKey(
    'raw',
    enc.encode(passphrase),
    'PBKDF2',
    false,
    ['deriveBits', 'deriveKey']
  );
  
  const aesKey = await subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: salt,
      iterations: 600000,
      hash: 'SHA-256'
    },
    passphraseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
  
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plaintextBytes = enc.encode(JSON.stringify(identityData));
  const ciphertext = await subtle.encrypt(
    { name: 'AES-GCM', iv: iv },
    aesKey,
    plaintextBytes
  );
  
  return {
    ciphertext: new Uint8Array(ciphertext),
    iv,
    salt
  };
}

export async function decryptIdentityWithPassphrase(encryptedBlob, iv, salt, passphrase) {
  const enc = new TextEncoder();
  const passphraseKey = await subtle.importKey(
    'raw',
    enc.encode(passphrase),
    'PBKDF2',
    false,
    ['deriveBits', 'deriveKey']
  );
  
  const aesKey = await subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: salt,
      iterations: 600000,
      hash: 'SHA-256'
    },
    passphraseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
  
  const decryptedBytes = await subtle.decrypt(
    { name: 'AES-GCM', iv: iv },
    aesKey,
    encryptedBlob
  );
  
  return JSON.parse(new TextDecoder().decode(decryptedBytes));
}

export async function encryptIdentityEnvelope(identityData, passphrase) {
  const dek = crypto.getRandomValues(new Uint8Array(32));
  const ivDek = crypto.getRandomValues(new Uint8Array(12));
  const enc = new TextEncoder();
  const plaintextBytes = enc.encode(JSON.stringify(identityData));
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

  return JSON.parse(new TextDecoder().decode(decryptedBytes));
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
