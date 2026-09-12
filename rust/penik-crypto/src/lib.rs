pub mod aad;
pub mod c_abi;
pub mod cipher;
pub mod errors;
pub mod kdf;
pub mod keys;
pub mod safety;

#[cfg(target_arch = "wasm32")]
pub mod wasm;

#[cfg(not(target_arch = "wasm32"))]
pub mod jni;

/// Current internal crypto core build/ABI version.
/// Bump this integer when adding new exports, modifying signatures, or changing ABI.
pub const CRYPTO_CORE_VERSION: u32 = 2;

#[inline]
pub fn crypto_core_version() -> u32 {
    CRYPTO_CORE_VERSION
}

// Re-export common types and functions for easy use
pub use aad::{
    build_group_aad, build_group_aad_v1, build_pairwise_aad, build_pairwise_aad_v2,
    GROUP_PROTOCOL_VERSION, PAIRWISE_PROTOCOL_VERSION, PAIRWISE_PROTOCOL_VERSION_V2,
};
pub use cipher::{
    build_chunk_aad, chacha20poly1305_decrypt, chacha20poly1305_encrypt,
    create_chunked_file_header, decrypt_file, decrypt_file_chunk, derive_chunk_nonce,
    e2ee_decrypt, e2ee_encrypt, encrypt_file, encrypt_file_chunk, encrypt_file_chunked,
    encrypt_pairwise_fanout, generate_file_key_and_nonce, group_decrypt, group_encrypt,
    is_chunked_file, parse_chunked_file_header, unwrap_group_key, wrap_group_key_for_device,
    DeviceCiphertextEnvelope, DeviceRecipient, E2EEEncrypted, CHUNK_HEADER_SIZE, CHUNK_MAGIC,
    DEFAULT_CHUNK_SIZE, DEFAULT_GROUP_INFO, DEFAULT_PAIRWISE_INFO, GROUP_WRAP_INFO, KEY_SIZE,
    NONCE_SIZE, TAG_SIZE,
};
pub use errors::CryptoError;
pub use kdf::{hkdf_derive, pbkdf2_derive};
pub use keys::{
    decode_key, derive_public_key, diffie_hellman, encode_key, generate_key_pair,
    normalize_public_key, KeyPair,
};
pub use safety::{
    compute_safety_fingerprint, compute_safety_hash, compute_safety_number, SafetyFingerprint,
    RUSSIAN_WORDS, SAFETY_NUMBER_BLOCKS,
};
