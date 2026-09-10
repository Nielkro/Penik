"""
Cryptographic utilities for Penik E2E testing.
Mirrors client/js/crypto.js and android SafetyNumber.kt.
"""

import os
import struct
import hashlib
from typing import Tuple, Dict, Any, List
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat


PAIRWISE_PROTOCOL_VERSION = 1
PAIRWISE_INFO = b"penik-pairwise-message-v1"


def generate_key_pair() -> Tuple[X25519PrivateKey, bytes]:
    """Generates an X25519 private key and its raw 32-byte public key."""
    private_key = X25519PrivateKey.generate()
    public_bytes = private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return private_key, public_bytes


def derive_shared_secret(private_key: X25519PrivateKey, peer_public_key_bytes: bytes) -> bytes:
    """Computes the 32-byte Diffie-Hellman shared secret with peer."""
    if len(peer_public_key_bytes) == 33 and peer_public_key_bytes[0] == 5:
        peer_public_key_bytes = peer_public_key_bytes[1:]
    peer_pub = X25519PublicKey.from_public_bytes(peer_public_key_bytes)
    return private_key.exchange(peer_pub)


def build_pairwise_aad(sender_user_id: int, recipient_user_id: int, client_msg_id: str = "", timestamp: int = 0) -> bytes:
    """
    Constructs the binary Authenticated Additional Data (AAD) for pairwise messages.
    Binds version, sender, recipient, message ID and timestamp.
    """
    fields = [
        PAIRWISE_PROTOCOL_VERSION,
        str(sender_user_id),
        str(recipient_user_id),
        str(client_msg_id),
        str(timestamp),
    ]
    chunks = []
    for f in fields:
        b = str(f).encode("utf-8")
        chunks.append(struct.pack(">I", len(b)) + b)
    return b"".join(chunks)


def e2ee_encrypt(
    plaintext: str | bytes,
    shared_secret: bytes,
    info: bytes = PAIRWISE_INFO,
    aad: bytes = b""
) -> Dict[str, bytes]:
    """
    Encrypts plaintext using HKDF-derived ChaCha20-Poly1305 key with random salt & nonce.
    Returns dict with ciphertext, salt, and nonce.
    """
    if isinstance(plaintext, str):
        plaintext = plaintext.encode("utf-8")

    salt = os.urandom(32)
    nonce = os.urandom(12)

    derived_key = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        info=info
    ).derive(shared_secret)

    cipher = ChaCha20Poly1305(derived_key)
    ciphertext = cipher.encrypt(nonce, plaintext, aad)

    return {
        "ciphertext": ciphertext,
        "salt": salt,
        "nonce": nonce,
    }


def e2ee_decrypt(
    ciphertext: bytes,
    shared_secret: bytes,
    salt: bytes,
    nonce: bytes,
    info: bytes = PAIRWISE_INFO,
    aad: bytes = b""
) -> bytes:
    """
    Decrypts ChaCha20-Poly1305 ciphertext using HKDF-derived key with salt & nonce.
    Verifies authentication tag and AAD.
    """
    derived_key = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        info=info
    ).derive(shared_secret)

    cipher = ChaCha20Poly1305(derived_key)
    return cipher.decrypt(nonce, ciphertext, aad)


def encrypt_file(file_bytes: bytes) -> Tuple[bytes, bytes]:
    """
    Encrypts file with a random 256-bit ChaCha20-Poly1305 key.
    Prepend 12-byte nonce to the ciphertext.
    Returns (combined_encrypted_bytes, 32_byte_key).
    """
    key = os.urandom(32)
    nonce = os.urandom(12)
    cipher = ChaCha20Poly1305(key)
    ciphertext = cipher.encrypt(nonce, file_bytes, None)
    combined = nonce + ciphertext
    return combined, key


def decrypt_file(combined_encrypted_bytes: bytes, key: bytes) -> bytes:
    """
    Decrypts file using combined (12-byte nonce + ciphertext).
    """
    if len(combined_encrypted_bytes) < 12 + 16:
        raise ValueError("Invalid encrypted file payload: too short")
    nonce = combined_encrypted_bytes[:12]
    ciphertext = combined_encrypted_bytes[12:]
    cipher = ChaCha20Poly1305(key)
    return cipher.decrypt(nonce, ciphertext, None)


def compute_safety_fingerprint(keys_a: List[bytes], keys_b: List[bytes], user_id: int | None = None) -> Dict[str, Any]:
    """
    Computes Safety Number fingerprint from two sets of identity keys.
    Returns number, hex fingerprint, and qrPayload.
    """
    all_keys = sorted(keys_a + keys_b)
    concatenated = b"".join(all_keys)
    digest = hashlib.sha256(concatenated).digest()

    hex_fp = digest.hex()
    qr_payload = f"penik://safety?fp={hex_fp}"
    if user_id:
        qr_payload += f"&uid={user_id}"

    digits = ""
    for i in range(0, len(digest) - 1, 2):
        if len(digits) >= 25:
            break
        val = (digest[i] << 8) | digest[i + 1]
        digits += f"{val:05d}"[:5]

    blocks = [digits[i:i + 5] for i in range(0, min(len(digits), 25), 5)]
    number = " ".join(blocks)

    return {
        "number": number,
        "hex": hex_fp,
        "qr_payload": qr_payload,
    }
