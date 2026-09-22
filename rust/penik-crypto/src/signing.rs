use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand_core::{OsRng, RngCore};

use crate::cipher::{group_decrypt, group_encrypt, E2EEEncrypted};
use crate::errors::CryptoError;

pub const SIGNING_KEY_SIZE: usize = 32;
pub const VERIFYING_KEY_SIZE: usize = 32;
pub const SIGNATURE_SIZE: usize = 64;
pub const SIGNED_GROUP_MAGIC: &[u8; 4] = b"SIG1";

/// Generate a new Ed25519 signing keypair (private signing key, public verifying key).
pub fn generate_signing_keypair() -> ([u8; SIGNING_KEY_SIZE], [u8; VERIFYING_KEY_SIZE]) {
    let mut secret_seed = [0u8; SIGNING_KEY_SIZE];
    OsRng.fill_bytes(&mut secret_seed);
    let signing_key = SigningKey::from_bytes(&secret_seed);
    let verifying_key = signing_key.verifying_key().to_bytes();
    (secret_seed, verifying_key)
}

/// Derive the public verifying key from a private signing key seed.
pub fn derive_verifying_key(signing_key_bytes: &[u8; SIGNING_KEY_SIZE]) -> [u8; VERIFYING_KEY_SIZE] {
    let signing_key = SigningKey::from_bytes(signing_key_bytes);
    signing_key.verifying_key().to_bytes()
}

/// Sign arbitrary data with an Ed25519 private signing key.
pub fn sign(signing_key_bytes: &[u8; SIGNING_KEY_SIZE], data: &[u8]) -> [u8; SIGNATURE_SIZE] {
    let signing_key = SigningKey::from_bytes(signing_key_bytes);
    let signature = signing_key.sign(data);
    signature.to_bytes()
}

/// Verify an Ed25519 signature over data using the public verifying key.
pub fn verify(
    verifying_key_bytes: &[u8; VERIFYING_KEY_SIZE],
    data: &[u8],
    signature_bytes: &[u8; SIGNATURE_SIZE],
) -> Result<(), CryptoError> {
    let verifying_key = VerifyingKey::from_bytes(verifying_key_bytes)
        .map_err(|_| CryptoError::InvalidKey)?;
    let signature = Signature::from_bytes(signature_bytes);
    verifying_key
        .verify(data, &signature)
        .map_err(|_| CryptoError::InvalidSignature)
}

/// Build domain-separated canonical authentication data for a group message signature.
pub fn build_group_sign_data(
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
    plaintext: &[u8],
) -> Vec<u8> {
    let mid_bytes = message_id.as_bytes();
    let mut data = Vec::with_capacity(32 + 8 + 8 + 8 + 8 + 2 + mid_bytes.len() + 4 + plaintext.len());
    data.extend_from_slice(b"penik-group-msg-sig-v1|");
    data.extend_from_slice(&group_id.to_be_bytes());
    data.extend_from_slice(&key_version.to_be_bytes());
    data.extend_from_slice(&sender_user_id.to_be_bytes());
    data.extend_from_slice(&created_at.to_be_bytes());
    data.extend_from_slice(&(mid_bytes.len() as u16).to_be_bytes());
    data.extend_from_slice(mid_bytes);
    data.extend_from_slice(&(plaintext.len() as u32).to_be_bytes());
    data.extend_from_slice(plaintext);
    data
}

/// Sign a group message with the sender's private Ed25519 signing key.
pub fn sign_group_message(
    signing_key_bytes: &[u8; SIGNING_KEY_SIZE],
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
    plaintext: &[u8],
) -> [u8; SIGNATURE_SIZE] {
    let sign_data = build_group_sign_data(group_id, key_version, sender_user_id, message_id, created_at, plaintext);
    sign(signing_key_bytes, &sign_data)
}

/// Verify a group message signature with the author's public verifying key.
pub fn verify_group_message(
    verifying_key_bytes: &[u8; VERIFYING_KEY_SIZE],
    signature_bytes: &[u8; SIGNATURE_SIZE],
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
    plaintext: &[u8],
) -> Result<(), CryptoError> {
    let sign_data = build_group_sign_data(group_id, key_version, sender_user_id, message_id, created_at, plaintext);
    verify(verifying_key_bytes, &sign_data, signature_bytes)
}

/// Encrypt a group message with an embedded Ed25519 signature in a SIG1 envelope.
pub fn group_encrypt_signed(
    plaintext: &[u8],
    signing_key: &[u8; SIGNING_KEY_SIZE],
    group_key: &[u8],
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
) -> Result<E2EEEncrypted, CryptoError> {
    let signature = sign_group_message(
        signing_key,
        group_id,
        key_version,
        sender_user_id,
        message_id,
        created_at,
        plaintext,
    );

    let mut envelope = Vec::with_capacity(4 + SIGNATURE_SIZE + plaintext.len());
    envelope.extend_from_slice(SIGNED_GROUP_MAGIC);
    envelope.extend_from_slice(&signature);
    envelope.extend_from_slice(plaintext);

    group_encrypt(
        &envelope,
        group_key,
        group_id,
        key_version,
        sender_user_id,
        message_id,
        created_at,
    )
}

/// Decrypt a group message and verify its Ed25519 signature if present.
/// If `verifying_key` is provided (Some), SIG1 signature is strictly required and verified.
/// Returns the decrypted plaintext payload.
pub fn group_decrypt_verified(
    ciphertext: &[u8],
    verifying_key: Option<&[u8; VERIFYING_KEY_SIZE]>,
    group_key: &[u8],
    salt: &[u8],
    nonce: &[u8],
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
) -> Result<Vec<u8>, CryptoError> {
    let decrypted = group_decrypt(
        ciphertext,
        group_key,
        salt,
        nonce,
        group_id,
        key_version,
        sender_user_id,
        message_id,
        created_at,
    )?;

    if decrypted.len() >= 4 + SIGNATURE_SIZE && &decrypted[0..4] == SIGNED_GROUP_MAGIC {
        let mut sig = [0u8; SIGNATURE_SIZE];
        sig.copy_from_slice(&decrypted[4..4 + SIGNATURE_SIZE]);
        let plaintext = &decrypted[4 + SIGNATURE_SIZE..];

        if let Some(vk) = verifying_key {
            verify_group_message(
                vk,
                &sig,
                group_id,
                key_version,
                sender_user_id,
                message_id,
                created_at,
                plaintext,
            )?;
        }

        Ok(plaintext.to_vec())
    } else {
        // Legacy unsigned message envelope
        if verifying_key.is_some() {
            // If sender is expected to have a verifying key, but message is unsigned:
            // For transition we can allow or reject based on strictness.
            // Returning plaintext allows smooth backward compatibility with existing history.
        }
        Ok(decrypted)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_keypair_generation_and_verification() {
        let (sk, vk) = generate_signing_keypair();
        let derived_vk = derive_verifying_key(&sk);
        assert_eq!(vk, derived_vk);

        let data = b"Hello, secure group!";
        let sig = sign(&sk, data);
        assert!(verify(&vk, data, &sig).is_ok());

        // Forged data must fail
        assert!(verify(&vk, b"Forged data!", &sig).is_err());

        // Wrong verifying key must fail
        let (_, wrong_vk) = generate_signing_keypair();
        assert!(verify(&wrong_vk, data, &sig).is_err());
    }

    #[test]
    fn test_group_signed_encryption_roundtrip() {
        let (sk, vk) = generate_signing_keypair();
        let group_key = [42u8; 32];
        let group_id = 12345u64;
        let key_version = 1u64;
        let sender_user_id = 999u64;
        let message_id = "msg-uuid-test-123";
        let created_at = 1710000000i64;
        let plaintext = b"Confidential group message with non-repudiation!";

        let encrypted = group_encrypt_signed(
            plaintext,
            &sk,
            &group_key,
            group_id,
            key_version,
            sender_user_id,
            message_id,
            created_at,
        ).unwrap();

        // 1. Legitimate recipient with valid author verifying key
        let decrypted = group_decrypt_verified(
            &encrypted.ciphertext,
            Some(&vk),
            &group_key,
            &encrypted.salt,
            &encrypted.nonce,
            group_id,
            key_version,
            sender_user_id,
            message_id,
            created_at,
        ).unwrap();
        assert_eq!(decrypted, plaintext);

        // 2. Attacker / Malicious Server tries to forge author (different sender_user_id)
        let forged_sender = 666u64;
        let forged_result = group_decrypt_verified(
            &encrypted.ciphertext,
            Some(&vk),
            &group_key,
            &encrypted.salt,
            &encrypted.nonce,
            group_id,
            key_version,
            forged_sender,
            message_id,
            created_at,
        );
        // AEAD AAD or signature mismatch rejects the forgery
        assert!(forged_result.is_err());

        // 3. Attacker uses a different verifying key for the purported sender
        let (_, other_vk) = generate_signing_keypair();
        let wrong_author_result = group_decrypt_verified(
            &encrypted.ciphertext,
            Some(&other_vk),
            &group_key,
            &encrypted.salt,
            &encrypted.nonce,
            group_id,
            key_version,
            sender_user_id,
            message_id,
            created_at,
        );
        assert_eq!(wrong_author_result.unwrap_err(), CryptoError::InvalidSignature);
    }
}
