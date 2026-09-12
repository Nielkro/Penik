#![cfg(target_arch = "wasm32")]

use wasm_bindgen::prelude::*;

use crate::aad;
use crate::cipher;
use crate::keys;
use crate::safety;

#[wasm_bindgen]
pub struct JsKeyPair {
    public_key: Vec<u8>,
    private_key: Vec<u8>,
}

#[wasm_bindgen]
impl JsKeyPair {
    #[wasm_bindgen(getter, js_name = publicKey)]
    pub fn public_key(&self) -> js_sys::Uint8Array {
        js_sys::Uint8Array::from(&self.public_key[..])
    }

    #[wasm_bindgen(getter, js_name = privateKey)]
    pub fn private_key(&self) -> js_sys::Uint8Array {
        js_sys::Uint8Array::from(&self.private_key[..])
    }
}

#[wasm_bindgen]
pub struct JsE2EEEncrypted {
    ciphertext: Vec<u8>,
    salt: Vec<u8>,
    nonce: Vec<u8>,
}

#[wasm_bindgen]
impl JsE2EEEncrypted {
    #[wasm_bindgen(getter)]
    pub fn ciphertext(&self) -> js_sys::Uint8Array {
        js_sys::Uint8Array::from(&self.ciphertext[..])
    }

    #[wasm_bindgen(getter)]
    pub fn salt(&self) -> js_sys::Uint8Array {
        js_sys::Uint8Array::from(&self.salt[..])
    }

    #[wasm_bindgen(getter)]
    pub fn nonce(&self) -> js_sys::Uint8Array {
        js_sys::Uint8Array::from(&self.nonce[..])
    }
}

#[wasm_bindgen]
pub struct JsGroupKeyWrapped {
    encrypted_key: Vec<u8>,
    salt: Vec<u8>,
    nonce: Vec<u8>,
}

#[wasm_bindgen]
impl JsGroupKeyWrapped {
    #[wasm_bindgen(getter, js_name = encryptedKey)]
    pub fn encrypted_key(&self) -> js_sys::Uint8Array {
        js_sys::Uint8Array::from(&self.encrypted_key[..])
    }

    #[wasm_bindgen(getter)]
    pub fn salt(&self) -> js_sys::Uint8Array {
        js_sys::Uint8Array::from(&self.salt[..])
    }

    #[wasm_bindgen(getter)]
    pub fn nonce(&self) -> js_sys::Uint8Array {
        js_sys::Uint8Array::from(&self.nonce[..])
    }
}

#[wasm_bindgen]
pub struct JsEncryptedFile {
    encrypted_bytes: Vec<u8>,
    key: Vec<u8>,
}

#[wasm_bindgen]
impl JsEncryptedFile {
    #[wasm_bindgen(getter, js_name = encryptedBytes)]
    pub fn encrypted_bytes(&self) -> js_sys::Uint8Array {
        js_sys::Uint8Array::from(&self.encrypted_bytes[..])
    }

    #[wasm_bindgen(getter)]
    pub fn key(&self) -> js_sys::Uint8Array {
        js_sys::Uint8Array::from(&self.key[..])
    }
}

#[wasm_bindgen]
pub struct JsSafetyFingerprint {
    number: String,
    words: Vec<String>,
    hex: String,
    qr_payload: String,
}

#[wasm_bindgen]
impl JsSafetyFingerprint {
    #[wasm_bindgen(getter)]
    pub fn number(&self) -> String {
        self.number.clone()
    }

    #[wasm_bindgen(getter)]
    pub fn words(&self) -> js_sys::Array {
        let arr = js_sys::Array::new();
        for w in &self.words {
            arr.push(&JsValue::from_str(w));
        }
        arr
    }

    #[wasm_bindgen(getter)]
    pub fn hex(&self) -> String {
        self.hex.clone()
    }

    #[wasm_bindgen(getter, js_name = qrPayload)]
    pub fn qr_payload(&self) -> String {
        self.qr_payload.clone()
    }
}

#[wasm_bindgen(js_name = generateKeyPair)]
pub fn wasm_generate_key_pair() -> JsKeyPair {
    let kp = keys::generate_key_pair();
    JsKeyPair {
        public_key: kp.public_key.to_vec(),
        private_key: kp.private_key.to_vec(),
    }
}

#[wasm_bindgen(js_name = derivePublicKey)]
pub fn wasm_derive_public_key(private_key: &[u8]) -> Result<js_sys::Uint8Array, JsValue> {
    let pub_key = keys::derive_public_key(private_key)?;
    Ok(js_sys::Uint8Array::from(&pub_key[..]))
}

#[wasm_bindgen(js_name = deriveSharedSecret)]
pub fn wasm_derive_shared_secret(
    private_key: &[u8],
    peer_public_key: &[u8],
) -> Result<js_sys::Uint8Array, JsValue> {
    let shared = keys::diffie_hellman(private_key, peer_public_key)?;
    Ok(js_sys::Uint8Array::from(&shared[..]))
}

#[wasm_bindgen(js_name = chacha20Poly1305Encrypt)]
pub fn wasm_chacha20poly1305_encrypt(
    key: &[u8],
    nonce: &[u8],
    plaintext: &[u8],
    aad: Option<Vec<u8>>,
) -> Result<js_sys::Uint8Array, JsValue> {
    let aad_ref = aad.as_deref().unwrap_or(&[]);
    let ct = cipher::chacha20poly1305_encrypt(key, nonce, plaintext, aad_ref)?;
    Ok(js_sys::Uint8Array::from(&ct[..]))
}

#[wasm_bindgen(js_name = chacha20Poly1305Decrypt)]
pub fn wasm_chacha20poly1305_decrypt(
    key: &[u8],
    nonce: &[u8],
    ciphertext_and_tag: &[u8],
    aad: Option<Vec<u8>>,
) -> Result<js_sys::Uint8Array, JsValue> {
    let aad_ref = aad.as_deref().unwrap_or(&[]);
    let pt = cipher::chacha20poly1305_decrypt(key, nonce, ciphertext_and_tag, aad_ref)?;
    Ok(js_sys::Uint8Array::from(&pt[..]))
}

#[wasm_bindgen(js_name = encryptFileChaCha20)]
pub fn wasm_encrypt_file(file_bytes: &[u8]) -> Result<JsEncryptedFile, JsValue> {
    let (encrypted_bytes, key) = cipher::encrypt_file(file_bytes)?;
    Ok(JsEncryptedFile {
        encrypted_bytes,
        key: key.to_vec(),
    })
}

#[wasm_bindgen(js_name = decryptFileChaCha20)]
pub fn wasm_decrypt_file(
    encrypted_bytes: &[u8],
    key: &[u8],
) -> Result<js_sys::Uint8Array, JsValue> {
    let pt = cipher::decrypt_file(encrypted_bytes, key)?;
    Ok(js_sys::Uint8Array::from(&pt[..]))
}

#[wasm_bindgen(js_name = buildPairwiseAAD)]
pub fn wasm_build_pairwise_aad(
    sender_user_id: u64,
    recipient_user_id: u64,
    client_msg_id: Option<String>,
    timestamp: Option<i64>,
) -> js_sys::Uint8Array {
    let msg_id = client_msg_id.as_deref().unwrap_or("");
    let aad_bytes = match timestamp {
        Some(ts) => aad::build_pairwise_aad(sender_user_id, recipient_user_id, msg_id, ts),
        None => aad::build_pairwise_aad_v2(sender_user_id, recipient_user_id, msg_id),
    };
    js_sys::Uint8Array::from(&aad_bytes[..])
}

#[wasm_bindgen(js_name = buildPairwiseAADV2)]
pub fn wasm_build_pairwise_aad_v2(
    sender_user_id: u64,
    recipient_user_id: u64,
    client_msg_id: Option<String>,
) -> js_sys::Uint8Array {
    let msg_id = client_msg_id.as_deref().unwrap_or("");
    let aad_bytes = aad::build_pairwise_aad_v2(sender_user_id, recipient_user_id, msg_id);
    js_sys::Uint8Array::from(&aad_bytes[..])
}

#[wasm_bindgen(js_name = buildGroupAAD)]
pub fn wasm_build_group_aad(
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
) -> js_sys::Uint8Array {
    let aad_bytes = aad::build_group_aad(group_id, key_version, sender_user_id, message_id, created_at);
    js_sys::Uint8Array::from(&aad_bytes[..])
}

#[wasm_bindgen(js_name = buildGroupAADv1)]
pub fn wasm_build_group_aad_v1(
    group_id: u64,
    key_version: u64,
    message_id: &str,
    created_at: i64,
) -> js_sys::Uint8Array {
    let aad_bytes = aad::build_group_aad_v1(group_id, key_version, message_id, created_at);
    js_sys::Uint8Array::from(&aad_bytes[..])
}

#[wasm_bindgen(js_name = e2eeEncrypt)]
pub fn wasm_e2ee_encrypt(
    plaintext: &[u8],
    shared_secret: &[u8],
    info: Option<String>,
    aad: Option<Vec<u8>>,
) -> Result<JsE2EEEncrypted, JsValue> {
    let info_bytes = info
        .as_deref()
        .map(|s| s.as_bytes())
        .unwrap_or(cipher::DEFAULT_PAIRWISE_INFO);
    let aad_ref = aad.as_deref().unwrap_or(&[]);
    let enc = cipher::e2ee_encrypt(plaintext, shared_secret, info_bytes, aad_ref)?;
    Ok(JsE2EEEncrypted {
        ciphertext: enc.ciphertext,
        salt: enc.salt.to_vec(),
        nonce: enc.nonce.to_vec(),
    })
}

#[wasm_bindgen(js_name = e2eeDecrypt)]
pub fn wasm_e2ee_decrypt(
    ciphertext: &[u8],
    shared_secret: &[u8],
    salt: &[u8],
    nonce: &[u8],
    info: Option<String>,
    aad: Option<Vec<u8>>,
) -> Result<js_sys::Uint8Array, JsValue> {
    let info_bytes = info
        .as_deref()
        .map(|s| s.as_bytes())
        .unwrap_or(cipher::DEFAULT_PAIRWISE_INFO);
    let aad_ref = aad.as_deref().unwrap_or(&[]);
    let pt = cipher::e2ee_decrypt(ciphertext, shared_secret, salt, nonce, info_bytes, aad_ref)?;
    Ok(js_sys::Uint8Array::from(&pt[..]))
}

#[wasm_bindgen(js_name = groupEncrypt)]
pub fn wasm_group_encrypt(
    plaintext: &[u8],
    group_key: &[u8],
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
) -> Result<JsE2EEEncrypted, JsValue> {
    let enc = cipher::group_encrypt(
        plaintext,
        group_key,
        group_id,
        key_version,
        sender_user_id,
        message_id,
        created_at,
    )?;
    Ok(JsE2EEEncrypted {
        ciphertext: enc.ciphertext,
        salt: enc.salt.to_vec(),
        nonce: enc.nonce.to_vec(),
    })
}

#[wasm_bindgen(js_name = groupDecrypt)]
pub fn wasm_group_decrypt(
    ciphertext: &[u8],
    group_key: &[u8],
    salt: &[u8],
    nonce: &[u8],
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
) -> Result<js_sys::Uint8Array, JsValue> {
    let pt = cipher::group_decrypt(
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
    Ok(js_sys::Uint8Array::from(&pt[..]))
}

#[wasm_bindgen(js_name = wrapGroupKeyForDevice)]
pub fn wasm_wrap_group_key(
    group_key: &[u8],
    shared_secret: &[u8],
    group_id: u64,
    key_version: u64,
) -> Result<JsGroupKeyWrapped, JsValue> {
    let enc = cipher::wrap_group_key_for_device(group_key, shared_secret, group_id, key_version)?;
    Ok(JsGroupKeyWrapped {
        encrypted_key: enc.ciphertext,
        salt: enc.salt.to_vec(),
        nonce: enc.nonce.to_vec(),
    })
}

#[wasm_bindgen(js_name = unwrapGroupKey)]
pub fn wasm_unwrap_group_key(
    encrypted_key: &[u8],
    shared_secret: &[u8],
    salt: &[u8],
    nonce: &[u8],
    group_id: u64,
    key_version: u64,
) -> Result<js_sys::Uint8Array, JsValue> {
    let pt = cipher::unwrap_group_key(
        encrypted_key,
        shared_secret,
        salt,
        nonce,
        group_id,
        key_version,
    )?;
    Ok(js_sys::Uint8Array::from(&pt[..]))
}

fn extract_single_key(val: &JsValue) -> Option<Vec<u8>> {
    if val.is_null() || val.is_undefined() {
        return None;
    }
    if let Some(s) = val.as_string() {
        return keys::decode_key(&s).ok();
    }
    if let Ok(u8_arr) = val.clone().dyn_into::<js_sys::Uint8Array>() {
        return Some(u8_arr.to_vec());
    }
    if let Ok(arr_buf) = val.clone().dyn_into::<js_sys::ArrayBuffer>() {
        let u8_arr = js_sys::Uint8Array::new(&arr_buf);
        return Some(u8_arr.to_vec());
    }
    None
}

fn extract_keys_list(val: &JsValue) -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    if js_sys::Array::is_array(val) {
        let arr = js_sys::Array::from(val);
        for i in 0..arr.length() {
            let item = arr.get(i);
            if let Some(k) = extract_single_key(&item) {
                out.push(k);
            }
        }
    } else if let Some(k) = extract_single_key(val) {
        out.push(k);
    }
    out
}

#[wasm_bindgen(js_name = computeSafetyNumber)]
pub fn wasm_compute_safety_number(keys_a: &JsValue, keys_b: &JsValue) -> Result<String, JsValue> {
    let list_a = extract_keys_list(keys_a);
    let list_b = extract_keys_list(keys_b);
    let slice_a: Vec<&[u8]> = list_a.iter().map(|v| v.as_slice()).collect();
    let slice_b: Vec<&[u8]> = list_b.iter().map(|v| v.as_slice()).collect();

    let num = safety::compute_safety_number(&slice_a, &slice_b)?;
    Ok(num)
}

#[wasm_bindgen(js_name = computeSafetyFingerprint)]
pub fn wasm_compute_safety_fingerprint(
    keys_a: &JsValue,
    keys_b: &JsValue,
    user_id: Option<String>,
) -> Result<JsSafetyFingerprint, JsValue> {
    let list_a = extract_keys_list(keys_a);
    let list_b = extract_keys_list(keys_b);
    let slice_a: Vec<&[u8]> = list_a.iter().map(|v| v.as_slice()).collect();
    let slice_b: Vec<&[u8]> = list_b.iter().map(|v| v.as_slice()).collect();

    let fp = safety::compute_safety_fingerprint(&slice_a, &slice_b, user_id.as_deref())?;
    Ok(JsSafetyFingerprint {
        number: fp.number,
        words: fp.words,
        hex: fp.hex,
        qr_payload: fp.qr_payload,
    })
}

#[wasm_bindgen(js_name = encodeKey)]
pub fn wasm_encode_key(bytes: &[u8]) -> String {
    keys::encode_key(bytes)
}

#[wasm_bindgen(js_name = decodeKey)]
pub fn wasm_decode_key(b64: &str) -> Result<js_sys::Uint8Array, JsValue> {
    let bytes = keys::decode_key(b64)?;
    Ok(js_sys::Uint8Array::from(&bytes[..]))
}

#[wasm_bindgen(js_name = penikCryptoVersion)]
pub fn wasm_penik_crypto_version() -> u32 {
    crate::CRYPTO_CORE_VERSION
}
