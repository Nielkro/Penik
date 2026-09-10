"""
Cryptographic utilities for Penik E2E testing.
Powered by the unified Penik Rust micro-core (penik-crypto) via ctypes C-ABI.
"""

import ctypes
import os
import subprocess
from pathlib import Path
from typing import Tuple, Dict, Any, List

PAIRWISE_PROTOCOL_VERSION = 1
PAIRWISE_INFO = b"penik-pairwise-message-v1"


def _load_rust_crypto_lib() -> ctypes.CDLL:
    repo_root = Path(__file__).resolve().parent.parent.parent
    lib_path = repo_root / "rust" / "penik-crypto" / "target" / "release" / "libpenik_crypto.so"
    if not lib_path.exists():
        debug_lib = repo_root / "rust" / "penik-crypto" / "target" / "debug" / "libpenik_crypto.so"
        if debug_lib.exists():
            lib_path = debug_lib
        else:
            # Build release library on demand
            subprocess.run(
                ["cargo", "build", "--release"],
                cwd=str(repo_root / "rust" / "penik-crypto"),
                check=True,
                capture_output=True,
            )
    lib = ctypes.CDLL(str(lib_path))

    # Setup argtypes and restypes
    lib.penik_generate_key_pair.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    lib.penik_generate_key_pair.restype = ctypes.c_int32

    lib.penik_derive_public_key.argtypes = [ctypes.c_char_p, ctypes.c_void_p]
    lib.penik_derive_public_key.restype = ctypes.c_int32

    lib.penik_derive_shared_secret.argtypes = [
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_void_p,
    ]
    lib.penik_derive_shared_secret.restype = ctypes.c_int32

    lib.penik_build_pairwise_aad.argtypes = [
        ctypes.c_uint64,
        ctypes.c_uint64,
        ctypes.c_char_p,
        ctypes.c_int64,
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_size_t),
    ]
    lib.penik_build_pairwise_aad.restype = ctypes.c_int32

    lib.penik_e2ee_encrypt.argtypes = [
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_void_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]
    lib.penik_e2ee_encrypt.restype = ctypes.c_int32

    lib.penik_e2ee_decrypt.argtypes = [
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_size_t),
    ]
    lib.penik_e2ee_decrypt.restype = ctypes.c_int32

    lib.penik_encrypt_file.argtypes = [
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]
    lib.penik_encrypt_file.restype = ctypes.c_int32

    lib.penik_decrypt_file.argtypes = [
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_size_t),
    ]
    lib.penik_decrypt_file.restype = ctypes.c_int32

    lib.penik_compute_safety_fingerprint.argtypes = [
        ctypes.POINTER(ctypes.c_char_p),
        ctypes.POINTER(ctypes.c_size_t),
        ctypes.c_size_t,
        ctypes.POINTER(ctypes.c_char_p),
        ctypes.POINTER(ctypes.c_size_t),
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_size_t,
    ]
    lib.penik_compute_safety_fingerprint.restype = ctypes.c_int32

    return lib


_lib = _load_rust_crypto_lib()


def _to_bytes(priv: Any) -> bytes:
    if isinstance(priv, (bytes, bytearray)):
        return bytes(priv)
    if hasattr(priv, "private_bytes"):
        from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, NoEncryption
        return priv.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    raise TypeError(f"Unsupported private key type: {type(priv)}")


def generate_key_pair() -> Tuple[bytes, bytes]:
    """Generates an X25519 private key (32 bytes) and its raw 32-byte public key."""
    out_pub = (ctypes.c_uint8 * 32)()
    out_priv = (ctypes.c_uint8 * 32)()
    res = _lib.penik_generate_key_pair(out_pub, out_priv)
    if res != 0:
        raise RuntimeError("penik_generate_key_pair failed in Rust core")
    return bytes(out_priv), bytes(out_pub)


def derive_shared_secret(private_key: Any, peer_public_key_bytes: bytes) -> bytes:
    """Computes the 32-byte Diffie-Hellman shared secret with peer."""
    priv_bytes = _to_bytes(private_key)
    out_secret = (ctypes.c_uint8 * 32)()
    res = _lib.penik_derive_shared_secret(
        priv_bytes,
        peer_public_key_bytes,
        len(peer_public_key_bytes),
        out_secret,
    )
    if res != 0:
        raise ValueError("penik_derive_shared_secret failed in Rust core")
    return bytes(out_secret)


def build_pairwise_aad(
    sender_user_id: int,
    recipient_user_id: int,
    client_msg_id: str = "",
    timestamp: int = 0
) -> bytes:
    """
    Constructs the binary Authenticated Additional Data (AAD) for pairwise messages.
    Binds version, sender, recipient, message ID and timestamp using Rust core.
    """
    msg_id_bytes = client_msg_id.encode("utf-8")
    out_len = ctypes.c_size_t(0)
    _lib.penik_build_pairwise_aad(
        ctypes.c_uint64(sender_user_id),
        ctypes.c_uint64(recipient_user_id),
        msg_id_bytes,
        ctypes.c_int64(timestamp),
        None,
        ctypes.byref(out_len),
    )
    buf = (ctypes.c_uint8 * out_len.value)()
    res = _lib.penik_build_pairwise_aad(
        ctypes.c_uint64(sender_user_id),
        ctypes.c_uint64(recipient_user_id),
        msg_id_bytes,
        ctypes.c_int64(timestamp),
        buf,
        ctypes.byref(out_len),
    )
    if res != 0:
        raise RuntimeError("penik_build_pairwise_aad failed in Rust core")
    return bytes(buf)


def e2ee_encrypt(
    plaintext: str | bytes,
    shared_secret: bytes,
    info: bytes = PAIRWISE_INFO,
    aad: bytes = b""
) -> Dict[str, bytes]:
    """
    Encrypts plaintext using HKDF-derived ChaCha20-Poly1305 key with random salt & nonce in Rust.
    Returns dict with ciphertext, salt, and nonce.
    """
    if isinstance(plaintext, str):
        plaintext = plaintext.encode("utf-8")

    pt_len = len(plaintext)
    ct_buf = (ctypes.c_uint8 * (pt_len + 16))()
    salt_buf = (ctypes.c_uint8 * 32)()
    nonce_buf = (ctypes.c_uint8 * 12)()

    res = _lib.penik_e2ee_encrypt(
        plaintext,
        pt_len,
        shared_secret,
        info,
        len(info),
        aad,
        len(aad),
        ct_buf,
        salt_buf,
        nonce_buf,
    )
    if res != 0:
        raise RuntimeError("penik_e2ee_encrypt failed in Rust core")

    return {
        "ciphertext": bytes(ct_buf),
        "salt": bytes(salt_buf),
        "nonce": bytes(nonce_buf),
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
    Decrypts ChaCha20-Poly1305 ciphertext using HKDF-derived key with salt & nonce in Rust.
    Verifies authentication tag and AAD.
    """
    if len(ciphertext) < 16:
        raise ValueError("Ciphertext too short (minimum 16 bytes for Poly1305 tag)")

    out_pt_len = ctypes.c_size_t(len(ciphertext) - 16)
    out_pt = (ctypes.c_uint8 * out_pt_len.value)()

    res = _lib.penik_e2ee_decrypt(
        ciphertext,
        len(ciphertext),
        shared_secret,
        salt,
        nonce,
        info,
        len(info),
        aad,
        len(aad),
        out_pt,
        ctypes.byref(out_pt_len),
    )
    if res != 0:
        raise ValueError("penik_e2ee_decrypt failed in Rust core: tag or AAD mismatch")

    return bytes(out_pt[:out_pt_len.value])


def encrypt_file(file_bytes: bytes) -> Tuple[bytes, bytes]:
    """
    Encrypts file with a random 256-bit ChaCha20-Poly1305 key in Rust.
    Prepends 12-byte nonce to the ciphertext.
    Returns (combined_encrypted_bytes, 32_byte_key).
    """
    file_len = len(file_bytes)
    out_encrypted = (ctypes.c_uint8 * (12 + file_len + 16))()
    out_key = (ctypes.c_uint8 * 32)()

    res = _lib.penik_encrypt_file(file_bytes, file_len, out_encrypted, out_key)
    if res != 0:
        raise RuntimeError("penik_encrypt_file failed in Rust core")

    return bytes(out_encrypted), bytes(out_key)


def decrypt_file(combined_encrypted_bytes: bytes, key: bytes) -> bytes:
    """
    Decrypts file using combined (12-byte nonce + ciphertext + 16-byte tag) in Rust.
    """
    if len(combined_encrypted_bytes) < 12 + 16:
        raise ValueError("Invalid encrypted file payload: too short")

    out_len = ctypes.c_size_t(len(combined_encrypted_bytes) - 28)
    out_decrypted = (ctypes.c_uint8 * out_len.value)()

    res = _lib.penik_decrypt_file(
        combined_encrypted_bytes,
        len(combined_encrypted_bytes),
        key,
        out_decrypted,
        ctypes.byref(out_len),
    )
    if res != 0:
        raise ValueError("penik_decrypt_file failed in Rust core: tag mismatch")

    return bytes(out_decrypted[:out_len.value])


def compute_safety_fingerprint(
    keys_a: List[bytes],
    keys_b: List[bytes],
    user_id: int | None = None
) -> Dict[str, Any]:
    """
    Computes Safety Number fingerprint from two sets of identity keys using Rust core.
    Returns number, hex fingerprint, and qr_payload.
    """
    a_ptrs = (ctypes.c_char_p * len(keys_a))(*keys_a)
    a_lens = (ctypes.c_size_t * len(keys_a))(*[len(k) for k in keys_a])
    b_ptrs = (ctypes.c_char_p * len(keys_b))(*keys_b)
    b_lens = (ctypes.c_size_t * len(keys_b))(*[len(k) for k in keys_b])

    uid_bytes = str(user_id).encode("utf-8") if user_id is not None else None

    out_number = ctypes.create_string_buffer(64)
    out_hex = ctypes.create_string_buffer(128)
    out_qr = ctypes.create_string_buffer(256)

    res = _lib.penik_compute_safety_fingerprint(
        a_ptrs,
        a_lens,
        len(keys_a),
        b_ptrs,
        b_lens,
        len(keys_b),
        uid_bytes,
        out_number,
        64,
        out_hex,
        128,
        out_qr,
        256,
    )
    if res != 0:
        raise RuntimeError("penik_compute_safety_fingerprint failed in Rust core")

    return {
        "number": out_number.value.decode("utf-8"),
        "hex": out_hex.value.decode("utf-8"),
        "qr_payload": out_qr.value.decode("utf-8"),
    }
