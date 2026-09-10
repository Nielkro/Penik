use hkdf::Hkdf;
use sha2::Sha256;

use crate::errors::CryptoError;

pub fn hkdf_derive(salt: &[u8], ikm: &[u8], info: &[u8], length: usize) -> Result<Vec<u8>, CryptoError> {
    let salt_opt = if salt.is_empty() { None } else { Some(salt) };
    let hk = Hkdf::<Sha256>::new(salt_opt, ikm);
    let mut okm = vec![0u8; length];
    hk.expand(info, &mut okm)
        .map_err(|e| CryptoError::HkdfError(e.to_string()))?;
    Ok(okm)
}

pub fn pbkdf2_derive(passphrase: &[u8], salt: &[u8], iterations: u32, length: usize) -> Vec<u8> {
    let mut okm = vec![0u8; length];
    pbkdf2::pbkdf2_hmac::<Sha256>(passphrase, salt, iterations, &mut okm);
    okm
}
