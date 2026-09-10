pub mod aad;
pub mod c_abi;
pub mod cipher;
pub mod errors;
pub mod kdf;
pub mod keys;
pub mod safety;

#[cfg(target_arch = "wasm32")]
pub mod wasm;

// Re-export common types and functions for easy use
pub use aad::{build_group_aad, build_group_aad_v1, build_pairwise_aad, GROUP_PROTOCOL_VERSION, PAIRWISE_PROTOCOL_VERSION};
pub use cipher::{
    chacha20poly1305_decrypt, chacha20poly1305_encrypt, decrypt_file, e2ee_decrypt,
    e2ee_encrypt, encrypt_file, group_decrypt, group_encrypt, unwrap_group_key,
    wrap_group_key_for_device, E2EEEncrypted, DEFAULT_GROUP_INFO, DEFAULT_PAIRWISE_INFO,
    GROUP_WRAP_INFO, KEY_SIZE, NONCE_SIZE, TAG_SIZE,
};
pub use errors::CryptoError;
pub use kdf::hkdf_derive;
pub use keys::{
    decode_key, derive_public_key, diffie_hellman, encode_key, generate_key_pair,
    normalize_public_key, KeyPair,
};
pub use safety::{
    compute_safety_fingerprint, compute_safety_hash, compute_safety_number, SafetyFingerprint,
    RUSSIAN_WORDS, SAFETY_NUMBER_BLOCKS,
};
