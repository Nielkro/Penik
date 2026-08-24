import {
  generateKeyPair,
  deriveSharedSecret,
  e2eeEncrypt,
  e2eeDecrypt,
  buildPairwiseAAD
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

  // Test 5: Pairwise AAD and Cross-platform byte-exact vector
  try {
    const alice = await generateKeyPair();
    const bob = await generateKeyPair();
    const sharedSecret = await deriveSharedSecret(alice.privateKey, bob.publicKey);
    const plaintext = new TextEncoder().encode("Hello Penik DM!");
    const aad = buildPairwiseAAD(10, 20, "msg-dm-1", 1700000000);

    const expectedPairwiseAad = new Uint8Array([
      0, 0, 0, 1, 49, // '1'
      0, 0, 0, 2, 49, 48, // '1', '0'
      0, 0, 0, 2, 50, 48, // '2', '0'
      0, 0, 0, 8, 109, 115, 103, 45, 100, 109, 45, 49, // 'msg-dm-1'
      0, 0, 0, 10, 49, 55, 48, 48, 48, 48, 48, 48, 48, 48 // '1700000000'
    ]);
    assertArrayEquals(aad, expectedPairwiseAad, "Pairwise AAD matches Android byte-for-byte");

    const enc = await e2eeEncrypt(plaintext, sharedSecret, "penik-pairwise-message-v1", aad);
    const dec = await e2eeDecrypt(enc.ciphertext, sharedSecret, enc.salt, enc.nonce, "penik-pairwise-message-v1", aad);
    assertArrayEquals(plaintext, dec, "Decrypted with AAD matches plaintext");

    let failedTamper = false;
    try {
      const wrongAad = buildPairwiseAAD(999, 20, "msg-dm-1", 1700000000);
      await e2eeDecrypt(enc.ciphertext, sharedSecret, enc.salt, enc.nonce, "penik-pairwise-message-v1", wrongAad);
    } catch {
      failedTamper = true;
    }
    assert(failedTamper, "Tampered pairwise AAD fails decryption");

    console.log("Test 5: Pairwise AAD and cross-platform vectors passed.");
  } catch (e) {
    console.error("Test 5: Pairwise AAD test failed:", e);
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
