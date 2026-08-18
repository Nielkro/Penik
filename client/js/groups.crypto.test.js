import {
  generateKeyPair,
  deriveSharedSecret,
  generateGroupKey,
  buildGroupAAD,
  groupEncrypt,
  groupDecrypt,
  wrapGroupKeyForDevice,
  unwrapGroupKey
} from './crypto.js';

// Setup global crypto for older Node versions if needed
if (typeof window === 'undefined' && typeof globalThis.crypto === 'undefined') {
  const { webcrypto } = await import('node:crypto');
  globalThis.crypto = /** @type {Crypto} */ (/** @type {unknown} */ (webcrypto));
}

let passed = 0;
let failed = 0;

function assert(condition, message) {
  if (!condition) {
    console.error('FAIL:', message);
    failed++;
  } else {
    passed++;
  }
}

function bytesEqual(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

async function assertThrows(fn, message) {
  try {
    await fn();
    assert(false, `${message} (expected throw)`);
  } catch (e) {
    assert(true, message);
  }
}

async function run() {
  console.log('Starting group crypto tests...');

  const k1 = generateGroupKey();
  const k2 = generateGroupKey();
  assert(k1.length === 32 && k2.length === 32, 'group key is 32 bytes');
  assert(!bytesEqual(k1, k2), 'group keys do not collide');

  const key = generateGroupKey();
  const plaintext = new TextEncoder().encode('привет группа');
  const enc = await groupEncrypt(plaintext, key, 7, 2, 'msg-1', 1700000000);
  assert(enc.salt.length === 32, 'salt is 32 bytes');
  assert(enc.nonce.length === 12, 'nonce is 12 bytes');
  assert(!bytesEqual(enc.ciphertext, plaintext), 'ciphertext differs from plaintext');
  const dec = await groupDecrypt(enc.ciphertext, key, enc.salt, enc.nonce, 7, 2, 'msg-1', 1700000000);
  assert(bytesEqual(dec, plaintext), 'round trip recovers plaintext');

  const a = await groupEncrypt(plaintext, key, 1, 1, 'm', 1);
  const b = await groupEncrypt(plaintext, key, 1, 1, 'm', 1);
  assert(!bytesEqual(a.salt, b.salt) && !bytesEqual(a.nonce, b.nonce), 'fresh salt and nonce');
  assert(!bytesEqual(a.ciphertext, b.ciphertext), 'identical messages produce different ciphertext');

  const other = generateGroupKey();
  await assertThrows(
    () => groupDecrypt(enc.ciphertext, other, enc.salt, enc.nonce, 7, 2, 'msg-1', 1700000000),
    'wrong group key fails to decrypt'
  );

  await assertThrows(
    () => groupDecrypt(enc.ciphertext, key, enc.salt, enc.nonce, 7, 2, 'msg-evil', 1700000000),
    'tampered AAD fails authentication'
  );

  const tampered = Uint8Array.from(enc.ciphertext);
  tampered[0] ^= 0xff;
  await assertThrows(
    () => groupDecrypt(tampered, key, enc.salt, enc.nonce, 7, 2, 'msg-1', 1700000000),
    'tampered ciphertext fails authentication'
  );

  const base = buildGroupAAD(1, 1, 'm', 1);
  assert(bytesEqual(base, buildGroupAAD(1, 1, 'm', 1)), 'AAD is deterministic');
  assert(!bytesEqual(base, buildGroupAAD(2, 1, 'm', 1)), 'AAD sensitive to groupId');
  assert(!bytesEqual(base, buildGroupAAD(1, 2, 'm', 1)), 'AAD sensitive to keyVersion');
  assert(!bytesEqual(base, buildGroupAAD(1, 1, 'm2', 1)), 'AAD sensitive to messageId');
  assert(!bytesEqual(base, buildGroupAAD(1, 1, 'm', 2)), 'AAD sensitive to createdAt');

  const kpA = await generateKeyPair();
  const kpB = await generateKeyPair();
  const secretA = await deriveSharedSecret(kpA.privateKey, kpB.publicKey);
  const secretB = await deriveSharedSecret(kpB.privateKey, kpA.publicKey);
  const groupKey = generateGroupKey();
  const wrapped = await wrapGroupKeyForDevice(groupKey, secretA, 3, 5);
  const unwrapped = await unwrapGroupKey(wrapped.encryptedKey, secretB, wrapped.salt, wrapped.nonce, 3, 5);
  assert(bytesEqual(unwrapped, groupKey), 'wrap/unwrap round trip');

  await assertThrows(
    () => unwrapGroupKey(wrapped.encryptedKey, secretB, wrapped.salt, wrapped.nonce, 3, 99),
    'unwrap with wrong version fails'
  );

  console.log(`\nGroup Crypto Test Summary: ${passed} passed, ${failed} failed`);
  if (failed > 0) throw new Error(`${failed} tests failed`);
}

run().catch((e) => {
  console.error('Test execution failed:', e);
  if (typeof process !== 'undefined') process.exit(1);
});
