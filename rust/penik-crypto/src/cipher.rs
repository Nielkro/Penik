use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Nonce,
};
use rand_core::{OsRng, RngCore};
use zeroize::Zeroize;

use crate::aad::build_group_aad;
use crate::errors::CryptoError;
use crate::kdf::hkdf_derive;

pub const NONCE_SIZE: usize = 12;
pub const TAG_SIZE: usize = 16;
pub const KEY_SIZE: usize = 32;

pub const DEFAULT_PAIRWISE_INFO: &[u8] = b"penik-pairwise-message-v1";
pub const DEFAULT_GROUP_INFO: &[u8] = b"penik-group-message-v1";
pub const GROUP_WRAP_INFO: &[u8] = b"penik-group-key-wrap-v1";

#[derive(Clone, Debug)]
pub struct E2EEEncrypted {
    pub ciphertext: Vec<u8>,
    pub salt: [u8; 32],
    pub nonce: [u8; 12],
}

pub fn chacha20poly1305_encrypt(
    key: &[u8],
    nonce: &[u8],
    plaintext: &[u8],
    aad: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    if key.len() != KEY_SIZE {
        return Err(CryptoError::InvalidKeyLength {
            expected: KEY_SIZE,
            actual: key.len(),
        });
    }
    if nonce.len() != NONCE_SIZE {
        return Err(CryptoError::InvalidKeyLength {
            expected: NONCE_SIZE,
            actual: nonce.len(),
        });
    }
    let cipher = ChaCha20Poly1305::new_from_slice(key)
        .map_err(|_| CryptoError::EncryptionError)?;
    let nonce = Nonce::from_slice(nonce);
    let payload = Payload {
        msg: plaintext,
        aad,
    };
    cipher.encrypt(nonce, payload).map_err(|_| CryptoError::EncryptionError)
}

pub fn chacha20poly1305_decrypt(
    key: &[u8],
    nonce: &[u8],
    ciphertext_and_tag: &[u8],
    aad: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    if key.len() != KEY_SIZE {
        return Err(CryptoError::InvalidKeyLength {
            expected: KEY_SIZE,
            actual: key.len(),
        });
    }
    if nonce.len() != NONCE_SIZE {
        return Err(CryptoError::InvalidKeyLength {
            expected: NONCE_SIZE,
            actual: nonce.len(),
        });
    }
    if ciphertext_and_tag.len() < TAG_SIZE {
        return Err(CryptoError::PayloadTooShort {
            min_length: TAG_SIZE,
            actual: ciphertext_and_tag.len(),
        });
    }
    let cipher = ChaCha20Poly1305::new_from_slice(key)
        .map_err(|_| CryptoError::DecryptionError)?;
    let nonce = Nonce::from_slice(nonce);
    let payload = Payload {
        msg: ciphertext_and_tag,
        aad,
    };
    cipher.decrypt(nonce, payload).map_err(|_| CryptoError::DecryptionError)
}

pub fn e2ee_encrypt(
    plaintext: &[u8],
    shared_secret: &[u8],
    info: &[u8],
    aad: &[u8],
) -> Result<E2EEEncrypted, CryptoError> {
    let mut salt = [0u8; 32];
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut salt);
    OsRng.fill_bytes(&mut nonce);

    let mut derived_key = hkdf_derive(&salt, shared_secret, info, KEY_SIZE)?;
    let ciphertext = chacha20poly1305_encrypt(&derived_key, &nonce, plaintext, aad);
    derived_key.zeroize();

    let ciphertext = ciphertext?;
    Ok(E2EEEncrypted {
        ciphertext,
        salt,
        nonce,
    })
}

pub fn e2ee_decrypt(
    ciphertext: &[u8],
    shared_secret: &[u8],
    salt: &[u8],
    nonce: &[u8],
    info: &[u8],
    aad: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let mut derived_key = hkdf_derive(salt, shared_secret, info, KEY_SIZE)?;
    let plaintext = chacha20poly1305_decrypt(&derived_key, nonce, ciphertext, aad);
    derived_key.zeroize();
    plaintext
}

pub fn encrypt_file(file_bytes: &[u8]) -> Result<(Vec<u8>, [u8; 32]), CryptoError> {
    let mut key = [0u8; 32];
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut key);
    OsRng.fill_bytes(&mut nonce);

    let ciphertext = chacha20poly1305_encrypt(&key, &nonce, file_bytes, &[])?;
    let mut combined = Vec::with_capacity(NONCE_SIZE + ciphertext.len());
    combined.extend_from_slice(&nonce);
    combined.extend_from_slice(&ciphertext);
    Ok((combined, key))
}

pub fn decrypt_file(encrypted_bytes: &[u8], key: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if encrypted_bytes.len() < NONCE_SIZE + TAG_SIZE {
        return Err(CryptoError::PayloadTooShort {
            min_length: NONCE_SIZE + TAG_SIZE,
            actual: encrypted_bytes.len(),
        });
    }
    let nonce = &encrypted_bytes[..NONCE_SIZE];
    let ciphertext_and_tag = &encrypted_bytes[NONCE_SIZE..];
    chacha20poly1305_decrypt(key, nonce, ciphertext_and_tag, &[])
}

pub fn group_encrypt(
    plaintext: &[u8],
    group_key: &[u8],
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
) -> Result<E2EEEncrypted, CryptoError> {
    let mut salt = [0u8; 32];
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut salt);
    OsRng.fill_bytes(&mut nonce);

    let mut message_key = hkdf_derive(&salt, group_key, DEFAULT_GROUP_INFO, KEY_SIZE)?;
    let aad = build_group_aad(group_id, key_version, sender_user_id, message_id, created_at);
    let ciphertext = chacha20poly1305_encrypt(&message_key, &nonce, plaintext, &aad);
    message_key.zeroize();

    let ciphertext = ciphertext?;
    Ok(E2EEEncrypted {
        ciphertext,
        salt,
        nonce,
    })
}

pub fn group_decrypt(
    ciphertext: &[u8],
    group_key: &[u8],
    salt: &[u8],
    nonce: &[u8],
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
) -> Result<Vec<u8>, CryptoError> {
    let mut message_key = hkdf_derive(salt, group_key, DEFAULT_GROUP_INFO, KEY_SIZE)?;
    let aad = build_group_aad(group_id, key_version, sender_user_id, message_id, created_at);
    let plaintext = chacha20poly1305_decrypt(&message_key, nonce, ciphertext, &aad);
    message_key.zeroize();
    plaintext
}

pub fn wrap_group_key_for_device(
    group_key: &[u8],
    shared_secret: &[u8],
    group_id: u64,
    key_version: u64,
) -> Result<E2EEEncrypted, CryptoError> {
    let aad = format!("penik-group-key-wrap-v1|{group_id}|{key_version}").into_bytes();
    e2ee_encrypt(group_key, shared_secret, GROUP_WRAP_INFO, &aad)
}

pub fn unwrap_group_key(
    encrypted_key: &[u8],
    shared_secret: &[u8],
    salt: &[u8],
    nonce: &[u8],
    group_id: u64,
    key_version: u64,
) -> Result<Vec<u8>, CryptoError> {
    let aad = format!("penik-group-key-wrap-v1|{group_id}|{key_version}").into_bytes();
    e2ee_decrypt(encrypted_key, shared_secret, salt, nonce, GROUP_WRAP_INFO, &aad)
}
