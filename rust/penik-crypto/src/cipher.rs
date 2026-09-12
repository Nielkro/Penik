use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Nonce,
};
use rand_core::{OsRng, RngCore};
use zeroize::Zeroize;

use crate::aad::build_group_aad;
use crate::errors::CryptoError;
use crate::kdf::hkdf_derive;
use crate::{aad, keys};

pub const NONCE_SIZE: usize = 12;
pub const TAG_SIZE: usize = 16;
pub const KEY_SIZE: usize = 32;

pub const DEFAULT_PAIRWISE_INFO: &[u8] = b"penik-pairwise-message-v1";
pub const DEFAULT_GROUP_INFO: &[u8] = b"penik-group-message-v1";
pub const GROUP_WRAP_INFO: &[u8] = b"penik-group-key-wrap-v1";

pub const CHUNK_MAGIC: &[u8; 4] = b"PCK1";
pub const DEFAULT_CHUNK_SIZE: usize = 64 * 1024; // 64 KB
pub const CHUNK_HEADER_SIZE: usize = 20;

#[derive(Clone, Debug)]
pub struct E2EEEncrypted {
    pub ciphertext: Vec<u8>,
    pub salt: [u8; 32],
    pub nonce: [u8; 12],
}

#[derive(Clone, Debug)]
pub struct DeviceRecipient<'a> {
    pub device_id: i64,
    pub public_key: &'a [u8],
    pub crypto_version: u32,
}

#[derive(Clone, Debug)]
pub struct DeviceCiphertextEnvelope {
    pub device_id: i64,
    pub ciphertext: Vec<u8>,
    pub salt: [u8; 32],
    pub nonce: [u8; 12],
    pub version: u32,
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

pub fn derive_chunk_nonce(base_nonce: &[u8; 12], chunk_index: u32) -> [u8; 12] {
    let mut nonce = *base_nonce;
    let idx_bytes = chunk_index.to_be_bytes();
    nonce[8] ^= idx_bytes[0];
    nonce[9] ^= idx_bytes[1];
    nonce[10] ^= idx_bytes[2];
    nonce[11] ^= idx_bytes[3];
    nonce
}

pub fn build_chunk_aad(chunk_index: u32, is_last: bool) -> [u8; 5] {
    let idx_bytes = chunk_index.to_be_bytes();
    [
        idx_bytes[0],
        idx_bytes[1],
        idx_bytes[2],
        idx_bytes[3],
        if is_last { 1 } else { 0 },
    ]
}

pub fn generate_file_key_and_nonce() -> ([u8; 32], [u8; 12]) {
    let mut key = [0u8; 32];
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut key);
    OsRng.fill_bytes(&mut nonce);
    (key, nonce)
}

pub fn create_chunked_file_header(base_nonce: &[u8; 12], chunk_size: u32) -> [u8; CHUNK_HEADER_SIZE] {
    let mut header = [0u8; CHUNK_HEADER_SIZE];
    header[0..4].copy_from_slice(CHUNK_MAGIC);
    header[4..16].copy_from_slice(base_nonce);
    header[16..20].copy_from_slice(&chunk_size.to_be_bytes());
    header
}

pub fn parse_chunked_file_header(header: &[u8]) -> Result<([u8; 12], u32), CryptoError> {
    if header.len() < CHUNK_HEADER_SIZE {
        return Err(CryptoError::PayloadTooShort {
            min_length: CHUNK_HEADER_SIZE,
            actual: header.len(),
        });
    }
    if &header[0..4] != CHUNK_MAGIC {
        return Err(CryptoError::InvalidPayload("invalid chunked file magic"));
    }
    let mut base_nonce = [0u8; 12];
    base_nonce.copy_from_slice(&header[4..16]);
    let chunk_size = u32::from_be_bytes(header[16..20].try_into().unwrap());
    if chunk_size == 0 || chunk_size > 16 * 1024 * 1024 {
        return Err(CryptoError::InvalidPayload("invalid chunk size in header"));
    }
    Ok((base_nonce, chunk_size))
}

pub fn is_chunked_file(data: &[u8]) -> bool {
    data.len() >= CHUNK_HEADER_SIZE && &data[0..4] == CHUNK_MAGIC
}

pub fn encrypt_file_chunk(
    key: &[u8],
    base_nonce: &[u8],
    chunk_index: u32,
    is_last: bool,
    chunk_plaintext: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    if key.len() != KEY_SIZE {
        return Err(CryptoError::InvalidKeyLength {
            expected: KEY_SIZE,
            actual: key.len(),
        });
    }
    if base_nonce.len() != NONCE_SIZE {
        return Err(CryptoError::InvalidKeyLength {
            expected: NONCE_SIZE,
            actual: base_nonce.len(),
        });
    }
    let mut bn = [0u8; 12];
    bn.copy_from_slice(base_nonce);
    let chunk_nonce = derive_chunk_nonce(&bn, chunk_index);
    let aad = build_chunk_aad(chunk_index, is_last);
    chacha20poly1305_encrypt(key, &chunk_nonce, chunk_plaintext, &aad)
}

pub fn decrypt_file_chunk(
    key: &[u8],
    base_nonce: &[u8],
    chunk_index: u32,
    is_last: bool,
    encrypted_chunk: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    if key.len() != KEY_SIZE {
        return Err(CryptoError::InvalidKeyLength {
            expected: KEY_SIZE,
            actual: key.len(),
        });
    }
    if base_nonce.len() != NONCE_SIZE {
        return Err(CryptoError::InvalidKeyLength {
            expected: NONCE_SIZE,
            actual: base_nonce.len(),
        });
    }
    let mut bn = [0u8; 12];
    bn.copy_from_slice(base_nonce);
    let chunk_nonce = derive_chunk_nonce(&bn, chunk_index);
    let aad = build_chunk_aad(chunk_index, is_last);
    chacha20poly1305_decrypt(key, &chunk_nonce, encrypted_chunk, &aad)
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

pub fn encrypt_file_chunked(file_bytes: &[u8]) -> Result<(Vec<u8>, [u8; 32]), CryptoError> {
    let (key, base_nonce) = generate_file_key_and_nonce();
    let chunk_size = DEFAULT_CHUNK_SIZE as u32;
    let header = create_chunked_file_header(&base_nonce, chunk_size);
    let mut out = Vec::with_capacity(header.len() + file_bytes.len() + (file_bytes.len() / DEFAULT_CHUNK_SIZE + 1) * TAG_SIZE);
    out.extend_from_slice(&header);

    if file_bytes.is_empty() {
        let enc_chunk = encrypt_file_chunk(&key, &base_nonce, 0, true, &[])?;
        out.extend_from_slice(&enc_chunk);
        return Ok((out, key));
    }

    let chunks: Vec<&[u8]> = file_bytes.chunks(DEFAULT_CHUNK_SIZE).collect();
    let num_chunks = chunks.len();
    for (i, chunk) in chunks.into_iter().enumerate() {
        let is_last = i == num_chunks - 1;
        let enc_chunk = encrypt_file_chunk(&key, &base_nonce, i as u32, is_last, chunk)?;
        out.extend_from_slice(&enc_chunk);
    }

    Ok((out, key))
}

pub fn decrypt_file(encrypted_bytes: &[u8], key: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if is_chunked_file(encrypted_bytes) {
        let (base_nonce, chunk_size) = parse_chunked_file_header(encrypted_bytes)?;
        let chunk_payload_max = chunk_size as usize + TAG_SIZE;
        let mut cur = CHUNK_HEADER_SIZE;
        let mut chunk_index = 0u32;
        let mut plaintext = Vec::new();

        if encrypted_bytes.len() == CHUNK_HEADER_SIZE {
            return Err(CryptoError::PayloadTooShort {
                min_length: CHUNK_HEADER_SIZE + TAG_SIZE,
                actual: encrypted_bytes.len(),
            });
        }

        while cur < encrypted_bytes.len() {
            let remaining = encrypted_bytes.len() - cur;
            let current_chunk_len = std::cmp::min(remaining, chunk_payload_max);
            let is_last = cur + current_chunk_len == encrypted_bytes.len();
            let chunk_data = &encrypted_bytes[cur..cur + current_chunk_len];
            let pt = decrypt_file_chunk(key, &base_nonce, chunk_index, is_last, chunk_data)?;
            plaintext.extend_from_slice(&pt);
            cur += current_chunk_len;
            chunk_index += 1;
        }

        Ok(plaintext)
    } else {
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

pub fn encrypt_pairwise_fanout(
    sender_priv_key: &[u8],
    sender_user_id: u64,
    recipient_user_id: u64,
    client_msg_id: &str,
    timestamp: i64,
    plaintext: &[u8],
    devices: &[DeviceRecipient],
) -> Result<Vec<DeviceCiphertextEnvelope>, CryptoError> {
    if sender_priv_key.len() != KEY_SIZE {
        return Err(CryptoError::InvalidKeyLength {
            expected: KEY_SIZE,
            actual: sender_priv_key.len(),
        });
    }

    let mut envelopes = Vec::with_capacity(devices.len());

    for device in devices {
        if device.public_key.len() != KEY_SIZE {
            return Err(CryptoError::InvalidKeyLength {
                expected: KEY_SIZE,
                actual: device.public_key.len(),
            });
        }

        let mut shared_secret = keys::diffie_hellman(sender_priv_key, device.public_key)?;

        let is_v2 = device.crypto_version >= 2;
        let aad = if is_v2 {
            aad::build_pairwise_aad_v2(sender_user_id, recipient_user_id, client_msg_id)
        } else {
            aad::build_pairwise_aad(sender_user_id, recipient_user_id, client_msg_id, timestamp)
        };

        let enc = e2ee_encrypt(plaintext, &shared_secret, DEFAULT_PAIRWISE_INFO, &aad);
        shared_secret.zeroize();
        let enc = enc?;

        envelopes.push(DeviceCiphertextEnvelope {
            device_id: device.device_id,
            ciphertext: enc.ciphertext,
            salt: enc.salt,
            nonce: enc.nonce,
            version: if is_v2 { 2 } else { 1 },
        });
    }

    Ok(envelopes)
}

