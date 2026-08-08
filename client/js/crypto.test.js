import {
  generateKeyPair,
  deriveSharedSecret,
  e2eeEncrypt,
  e2eeDecrypt
} from './crypto.js';

// Setup global crypto for older Node versions if needed
if (typeof window === 'undefined' && typeof globalThis.crypto === 'undefined') {
  const { webcrypto } = await import('node:crypto');
  globalThis.crypto = /** @type {Crypto} */ (/** @type {unknown} */ (webcrypto));
}

async function runTests() {
  console.log("Starting JS Crypto tests...");
  let passed = 0;
  let failed = 0;

  function assert(condition, message) {
    if (!condition) {
      console.error("FAIL:", message);
      failed++;
    } else {
      passed++;
    }
  }

  function assertArrayEquals(a, b, message) {
    if (a.length !== b.length) {
      assert(false, `${message}: lengths differ (${a.length} vs ${b.length})`);
      return;
    }
    for (let i = 0; i < a.length; i++) {
      if (a[i] !== b[i]) {
        assert(false, `${message}: mismatch at index ${i} (${a[i]} vs ${b[i]})`);
        return;
      }
    }
    assert(true, message);
  }

  // Test 1: Key Generation
  try {
    const keys = await generateKeyPair();
    assert(keys.publicKey instanceof Uint8Array, "Public key is Uint8Array");
    assert(keys.privateKey instanceof Uint8Array, "Private key is Uint8Array");
    assert(keys.publicKey.length === 32, "Public key is 32 bytes");
    assert(keys.privateKey.length === 32, "Private key is 32 bytes");
    console.log("Test 1: Key generation passed.");
  } catch (e) {
    console.error("Test 1: Key generation failed:", e);
    failed++;
  }

  // Test 2: Shared Secret Derivation
  try {
    const alice = await generateKeyPair();
    const bob = await generateKeyPair();

    const secretAlice = await deriveSharedSecret(alice.privateKey, bob.publicKey);
    const secretBob = await deriveSharedSecret(bob.privateKey, alice.publicKey);

    assert(secretAlice instanceof Uint8Array, "Secret is Uint8Array");
    assert(secretAlice.length === 32, "Secret is 32 bytes");
    assertArrayEquals(secretAlice, secretBob, "Shared secrets match");
    console.log("Test 2: Shared secret derivation passed.");
  } catch (e) {
    console.error("Test 2: Shared secret derivation failed:", e);
    failed++;
  }

  // Test 3: Encryption / Decryption Round-trip
  try {
    const alice = await generateKeyPair();
    const bob = await generateKeyPair();

    const sharedSecret = await deriveSharedSecret(alice.privateKey, bob.publicKey);

    const plaintext = new TextEncoder().encode("Hello Penik E2EE from JS!");
    const { ciphertext, salt, nonce } = await e2eeEncrypt(plaintext, sharedSecret);

    assert(ciphertext instanceof Uint8Array, "Ciphertext is Uint8Array");
    assert(salt.length === 32, "Salt is 32 bytes");
    assert(nonce.length === 12, "Nonce is 12 bytes");
    assert(ciphertext.length === plaintext.length + 16, "Ciphertext includes 16-byte Poly1305 tag");

    const decrypted = await e2eeDecrypt(ciphertext, sharedSecret, salt, nonce);
    assertArrayEquals(plaintext, decrypted, "Decrypted matches plaintext");
    assert(new TextDecoder().decode(decrypted) === "Hello Penik E2EE from JS!", "Decrypted text matches");
    console.log("Test 3: Encryption/decryption round-trip passed.");
  } catch (e) {
    console.error("Test 3: Encryption/decryption round-trip failed:", e);
    failed++;
  }

  // Test 4: Ciphertext randomness
  try {
    const alice = await generateKeyPair();
    const bob = await generateKeyPair();
    const sharedSecret = await deriveSharedSecret(alice.privateKey, bob.publicKey);
    const plaintext = new TextEncoder().encode("Identical message");

    const enc1 = await e2eeEncrypt(plaintext, sharedSecret);
    const enc2 = await e2eeEncrypt(plaintext, sharedSecret);

    let same = true;
    if (enc1.ciphertext.length === enc2.ciphertext.length) {
      for (let i = 0; i < enc1.ciphertext.length; i++) {
        if (enc1.ciphertext[i] !== enc2.ciphertext[i]) {
          same = false;
          break;
        }
      }
    } else {
      same = false;
    }
    assert(!same, "Ciphertexts are different for identical messages");
    console.log("Test 4: Randomness test passed.");
  } catch (e) {
    console.error("Test 4: Randomness test failed:", e);
    failed++;
  }

  console.log(`\nJS Crypto Test Summary: ${passed} passed, ${failed} failed`);
  if (failed > 0) {
    throw new Error(`${failed} tests failed`);
  }
}

runTests().catch(e => {
  console.error("Test execution failed:", e);
  if (typeof process !== 'undefined') process.exit(1);
});
