#!/usr/bin/env python3
"""
Direct Python verification suite for Penik Crypto Core (Rust C-ABI via ctypes).
Compares Rust cryptographic primitives directly against standard Python reference implementations:
  - PBKDF2-HMAC-SHA256 (vs hashlib.pbkdf2_hmac)
  - HKDF-SHA256 (vs cryptography.hazmat.primitives.kdf.hkdf.HKDF)
  - Pairwise AAD v2 (clock-independent format)
  - Memory zeroization
  - X25519 DH + ChaCha20-Poly1305 round trip
  - File encryption / decryption
  - Safety Number fingerprinting
"""

import os
import sys
from pathlib import Path

repo_root = Path(__file__).resolve().parent.parent.parent
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

import hashlib
import ctypes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

from tests.e2e.crypto_utils import (
    generate_key_pair,
    derive_shared_secret,
    build_pairwise_aad,
    build_pairwise_aad_v2,
    derive_key_pbkdf2,
    hkdf_derive,
    zeroize,
    e2ee_encrypt,
    e2ee_decrypt,
    encrypt_file,
    decrypt_file,
    compute_safety_fingerprint,
)

GREEN = "\033[92m"
RED = "\033[91m"
BOLD = "\033[1m"
RESET = "\033[0m"


def test_pbkdf2():
    print("Testing PBKDF2 (Rust vs Python hashlib)...", end=" ")
    passphrase = "super-secret-passphrase"
    salt = b"penik-salt-12345678"
    iterations = 2000

    rust_key = derive_key_pbkdf2(passphrase, salt, iterations, length=32)
    py_key = hashlib.pbkdf2_hmac("sha256", passphrase.encode("utf-8"), salt, iterations, dklen=32)

    assert rust_key == py_key, f"PBKDF2 mismatch: rust={rust_key.hex()} py={py_key.hex()}"
    print(f"{GREEN}{BOLD}OK{RESET}")


def test_hkdf():
    print("Testing HKDF (Rust vs Python cryptography)...", end=" ")
    secret = b"test-shared-secret-32-bytes-long"
    salt = b"test-salt-bytes"
    info = b"penik-test-info"
    length = 32

    rust_derived = hkdf_derive(secret, salt, info, length=length)

    hkdf = HKDF(
        algorithm=hashes.SHA256(),
        length=length,
        salt=salt,
        info=info,
    )
    py_derived = hkdf.derive(secret)

    assert rust_derived == py_derived, f"HKDF mismatch: rust={rust_derived.hex()} py={py_derived.hex()}"
    print(f"{GREEN}{BOLD}OK{RESET}")


def test_aad_v2():
    print("Testing Pairwise AAD v2 (clock-independent)...", end=" ")
    sender_id = 42
    recipient_id = 99
    msg_id = "msg_uuid_abc_123"

    aad_v2 = build_pairwise_aad_v2(sender_id, recipient_id, msg_id)
    # Check that version chunk is "2"
    assert aad_v2.startswith(b"\x00\x00\x00\x012"), "AAD v2 must start with version chunk '2'"
    print(f"{GREEN}{BOLD}OK ({len(aad_v2)} bytes){RESET}")


def test_zeroize():
    print("Testing Memory Zeroize...", end=" ")
    buf = (ctypes.c_uint8 * 32)(*[0xFF] * 32)
    assert any(b != 0 for b in buf)
    zeroize(buf)
    assert all(b == 0 for b in buf), "Buffer was not zeroized"
    print(f"{GREEN}{BOLD}OK{RESET}")


def test_key_pair_and_dh():
    print("Testing Key Generation & Diffie-Hellman...", end=" ")
    alice_priv, alice_pub = generate_key_pair()
    bob_priv, bob_pub = generate_key_pair()

    secret_alice = derive_shared_secret(alice_priv, bob_pub)
    secret_bob = derive_shared_secret(bob_priv, alice_pub)

    assert secret_alice == secret_bob, "DH shared secrets do not match"
    assert len(secret_alice) == 32
    print(f"{GREEN}{BOLD}OK{RESET}")


def test_e2ee_encrypt_decrypt():
    print("Testing E2EE ChaCha20-Poly1305 Roundtrip...", end=" ")
    alice_priv, alice_pub = generate_key_pair()
    bob_priv, bob_pub = generate_key_pair()
    shared_secret = derive_shared_secret(alice_priv, bob_pub)

    plaintext = "Top secret message across platforms via Rust micro-core!"
    aad = build_pairwise_aad_v2(1, 2, "test-msg-id")

    enc = e2ee_encrypt(plaintext, shared_secret, aad=aad)
    decrypted = e2ee_decrypt(
        enc["ciphertext"],
        shared_secret,
        enc["salt"],
        enc["nonce"],
        aad=aad,
    )

    assert decrypted.decode("utf-8") == plaintext, "Decrypted text mismatch"
    print(f"{GREEN}{BOLD}OK{RESET}")


def test_file_crypto():
    print("Testing File Encryption & Decryption...", end=" ")
    data = b"Sample binary file payload with random content \x00\x01\x02\xFF" * 100
    encrypted, key = encrypt_file(data)
    decrypted = decrypt_file(encrypted, key)

    assert decrypted == data, "Decrypted file content mismatch"
    print(f"{GREEN}{BOLD}OK{RESET}")


def test_safety_fingerprint():
    print("Testing Safety Number calculation...", end=" ")
    _, alice_pub = generate_key_pair()
    _, bob_pub = generate_key_pair()

    fp = compute_safety_fingerprint([alice_pub], [bob_pub], user_id=1)
    assert len(fp["number"]) > 0
    assert len(fp["hex"]) == 64
    assert "penik:" in fp["qr_payload"]
    print(f"{GREEN}{BOLD}OK (Number: {fp['number']}){RESET}")


if __name__ == "__main__":
    print(f"\n{BOLD}=== RUNNING PENIK PYTHON CRYPTO VERIFICATION ==={RESET}\n")
    test_pbkdf2()
    test_hkdf()
    test_aad_v2()
    test_zeroize()
    test_key_pair_and_dh()
    test_e2ee_encrypt_decrypt()
    test_file_crypto()
    test_safety_fingerprint()
    print(f"\n{GREEN}{BOLD}ALL PYTHON CRYPTO CORE TESTS PASSED!{RESET}\n")
