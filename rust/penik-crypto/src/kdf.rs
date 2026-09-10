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
