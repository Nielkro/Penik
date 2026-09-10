use base64::prelude::*;
use rand_core::OsRng;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

use crate::errors::CryptoError;

pub struct KeyPair {
    pub public_key: [u8; 32],
    pub private_key: [u8; 32],
}

impl Drop for KeyPair {
    fn drop(&mut self) {
        self.private_key.zeroize();
    }
}

pub fn generate_key_pair() -> KeyPair {
    let secret = StaticSecret::random_from_rng(OsRng);
    let public = PublicKey::from(&secret);
    KeyPair {
        public_key: public.to_bytes(),
        private_key: secret.to_bytes(),
    }
}

pub fn derive_public_key(private_key: &[u8]) -> Result<[u8; 32], CryptoError> {
    if private_key.len() != 32 {
        return Err(CryptoError::InvalidKeyLength {
            expected: 32,
            actual: private_key.len(),
        });
    }
    let mut priv_arr = [0u8; 32];
    priv_arr.copy_from_slice(private_key);
    let secret = StaticSecret::from(priv_arr);
    priv_arr.zeroize();
    let public = PublicKey::from(&secret);
    Ok(public.to_bytes())
}

pub fn normalize_public_key(key_bytes: &[u8]) -> Result<[u8; 32], CryptoError> {
    if key_bytes.len() == 32 {
        let mut out = [0u8; 32];
        out.copy_from_slice(key_bytes);
        return Ok(out);
    }
    if key_bytes.len() == 33 && key_bytes[0] == 0x05 {
        let mut out = [0u8; 32];
        out.copy_from_slice(&key_bytes[1..33]);
        return Ok(out);
    }
    if key_bytes.len() == 44 {
        if let Ok(s) = std::str::from_utf8(key_bytes) {
            let clean = s.trim().replace(' ', "+");
            let mut padded = clean.clone();
            while padded.len() % 4 != 0 {
                padded.push('=');
            }
            if let Ok(decoded) = BASE64_STANDARD.decode(&padded) {
                if decoded.len() == 32 {
                    let mut out = [0u8; 32];
                    out.copy_from_slice(&decoded);
                    return Ok(out);
                } else if decoded.len() == 33 && decoded[0] == 0x05 {
                    let mut out = [0u8; 32];
                    out.copy_from_slice(&decoded[1..33]);
                    return Ok(out);
                }
            }
        }
    }
    Err(CryptoError::InvalidKeyLength {
        expected: 32,
        actual: key_bytes.len(),
    })
}

pub fn diffie_hellman(private_key: &[u8], peer_public_key: &[u8]) -> Result<[u8; 32], CryptoError> {
    if private_key.len() != 32 {
        return Err(CryptoError::InvalidKeyLength {
            expected: 32,
            actual: private_key.len(),
        });
    }
    let clean_public = normalize_public_key(peer_public_key)?;
    let mut priv_arr = [0u8; 32];
    priv_arr.copy_from_slice(private_key);
    let secret = StaticSecret::from(priv_arr);
    priv_arr.zeroize();
    let public = PublicKey::from(clean_public);
    let shared = secret.diffie_hellman(&public);
    Ok(shared.to_bytes())
}

pub fn encode_key(bytes: &[u8]) -> String {
    BASE64_STANDARD.encode(bytes)
}

pub fn decode_key(b64: &str) -> Result<Vec<u8>, CryptoError> {
    let clean = b64.trim().replace(' ', "+");
    let mut padded = clean.clone();
    while padded.len() % 4 != 0 {
        padded.push('=');
    }
    BASE64_STANDARD
        .decode(&padded)
        .or_else(|_| BASE64_URL_SAFE.decode(&padded))
        .or_else(|_| BASE64_URL_SAFE_NO_PAD.decode(&padded))
        .map_err(|_| CryptoError::Base64DecodeError)
}
