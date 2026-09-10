use thiserror::Error;

#[derive(Error, Debug, Clone, PartialEq, Eq)]
pub enum CryptoError {
    #[error("Invalid key length: expected {expected}, got {actual}")]
    InvalidKeyLength { expected: usize, actual: usize },

    #[error("Failed to decode base64 key")]
    Base64DecodeError,

    #[error("HKDF expansion failed: {0}")]
    HkdfError(String),

    #[error("Encryption failed")]
    EncryptionError,

    #[error("Decryption failed: authentication tag or ciphertext mismatch")]
    DecryptionError,

    #[error("Payload too short: expected at least {min_length} bytes, got {actual}")]
    PayloadTooShort { min_length: usize, actual: usize },

    #[error("Safety number: no identity keys provided")]
    NoIdentityKeysProvided,

    #[error("Random number generation failed: {0}")]
    RngError(String),
}

#[cfg(target_arch = "wasm32")]
impl From<CryptoError> for wasm_bindgen::JsValue {
    fn from(err: CryptoError) -> Self {
        wasm_bindgen::JsValue::from_str(&err.to_string())
    }
}
