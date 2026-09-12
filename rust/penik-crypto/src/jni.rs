#![cfg(not(target_arch = "wasm32"))]

use jni::objects::{JByteArray, JClass, JIntArray, JLongArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jobjectArray, jstring};
use jni::JNIEnv;
use zeroize::Zeroize;

use crate::{aad, cipher, kdf, keys, safety};

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_generateKeyPair<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jbyteArray {
    let kp = keys::generate_key_pair();
    let mut combined = Vec::with_capacity(64);
    combined.extend_from_slice(&kp.public_key);
    combined.extend_from_slice(&kp.private_key);

    match env.byte_array_from_slice(&combined) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_derivePublicKey<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    priv_key: JByteArray<'local>,
) -> jbyteArray {
    let mut priv_bytes = match env.convert_byte_array(priv_key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let res = match keys::derive_public_key(&priv_bytes) {
        Ok(pub_key) => match env.byte_array_from_slice(&pub_key) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    };
    priv_bytes.zeroize();
    res
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_diffieHellman<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    priv_key: JByteArray<'local>,
    peer_pub_key: JByteArray<'local>,
) -> jbyteArray {
    let mut priv_bytes = match env.convert_byte_array(priv_key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let peer_pub_bytes = match env.convert_byte_array(peer_pub_key) {
        Ok(b) => b,
        Err(_) => {
            priv_bytes.zeroize();
            return std::ptr::null_mut();
        }
    };

    let res = match keys::diffie_hellman(&priv_bytes, &peer_pub_bytes) {
        Ok(shared) => match env.byte_array_from_slice(&shared) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    };
    priv_bytes.zeroize();
    res
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_deriveKeyPbkdf2<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    passphrase: JString<'local>,
    salt: JByteArray<'local>,
    iterations: jint,
    length: jint,
) -> jbyteArray {
    let pass_str: String = if passphrase.is_null() {
        String::new()
    } else {
        env.get_string(&passphrase)
            .map(|s| s.into())
            .unwrap_or_default()
    };
    let salt_bytes = match env.convert_byte_array(salt) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let derived = kdf::pbkdf2_derive(
        pass_str.as_bytes(),
        &salt_bytes,
        iterations as u32,
        length as usize,
    );

    match env.byte_array_from_slice(&derived) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_hkdfDerive<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    salt: JByteArray<'local>,
    ikm: JByteArray<'local>,
    info: JByteArray<'local>,
    length: jint,
) -> jbyteArray {
    let salt_bytes = match env.convert_byte_array(salt) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let ikm_bytes = match env.convert_byte_array(ikm) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let info_bytes = match env.convert_byte_array(info) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    match kdf::hkdf_derive(&salt_bytes, &ikm_bytes, &info_bytes, length as usize) {
        Ok(okm) => match env.byte_array_from_slice(&okm) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_zeroize<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    array: JByteArray<'local>,
) {
    if array.is_null() {
        return;
    }
    if let Ok(len) = env.get_array_length(&array) {
        if len > 0 {
            let zeros = vec![0i8; len as usize];
            let _ = env.set_byte_array_region(&array, 0, &zeros);
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_buildPairwiseAad<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    sender_user_id: jlong,
    recipient_user_id: jlong,
    client_msg_id: JString<'local>,
    timestamp: jlong,
) -> jbyteArray {
    let msg_id_str: String = if client_msg_id.is_null() {
        String::new()
    } else {
        env.get_string(&client_msg_id)
            .map(|s| s.into())
            .unwrap_or_default()
    };

    let aad_bytes = aad::build_pairwise_aad(
        sender_user_id as u64,
        recipient_user_id as u64,
        &msg_id_str,
        timestamp,
    );

    match env.byte_array_from_slice(&aad_bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_buildPairwiseAadV2<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    sender_user_id: jlong,
    recipient_user_id: jlong,
    client_msg_id: JString<'local>,
) -> jbyteArray {
    let msg_id_str: String = if client_msg_id.is_null() {
        String::new()
    } else {
        env.get_string(&client_msg_id)
            .map(|s| s.into())
            .unwrap_or_default()
    };

    let aad_bytes = aad::build_pairwise_aad_v2(
        sender_user_id as u64,
        recipient_user_id as u64,
        &msg_id_str,
    );

    match env.byte_array_from_slice(&aad_bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_encrypt<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    shared_secret: JByteArray<'local>,
    info: JByteArray<'local>,
    aad: JByteArray<'local>,
) -> jbyteArray {
    let pt_bytes = if plaintext.is_null() {
        Vec::new()
    } else {
        env.convert_byte_array(plaintext).unwrap_or_default()
    };
    let secret_bytes = match env.convert_byte_array(shared_secret) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let info_bytes = if info.is_null() {
        Vec::new()
    } else {
        env.convert_byte_array(info).unwrap_or_default()
    };
    let aad_bytes = if aad.is_null() {
        Vec::new()
    } else {
        env.convert_byte_array(aad).unwrap_or_default()
    };

    let info_ref = if info_bytes.is_empty() {
        cipher::DEFAULT_PAIRWISE_INFO
    } else {
        &info_bytes[..]
    };

    match cipher::e2ee_encrypt(&pt_bytes, &secret_bytes, info_ref, &aad_bytes) {
        Ok(enc) => {
            // Packed format: salt (32) + nonce (12) + ciphertext
            let mut out = Vec::with_capacity(32 + 12 + enc.ciphertext.len());
            out.extend_from_slice(&enc.salt);
            out.extend_from_slice(&enc.nonce);
            out.extend_from_slice(&enc.ciphertext);

            match env.byte_array_from_slice(&out) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_decrypt<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    ciphertext: JByteArray<'local>,
    salt: JByteArray<'local>,
    nonce: JByteArray<'local>,
    shared_secret: JByteArray<'local>,
    info: JByteArray<'local>,
    aad: JByteArray<'local>,
) -> jbyteArray {
    let ct_bytes = match env.convert_byte_array(ciphertext) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let salt_bytes = match env.convert_byte_array(salt) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let nonce_bytes = match env.convert_byte_array(nonce) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let secret_bytes = match env.convert_byte_array(shared_secret) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let info_bytes = if info.is_null() {
        Vec::new()
    } else {
        env.convert_byte_array(info).unwrap_or_default()
    };
    let aad_bytes = if aad.is_null() {
        Vec::new()
    } else {
        env.convert_byte_array(aad).unwrap_or_default()
    };

    let info_ref = if info_bytes.is_empty() {
        cipher::DEFAULT_PAIRWISE_INFO
    } else {
        &info_bytes[..]
    };

    match cipher::e2ee_decrypt(&ct_bytes, &secret_bytes, &salt_bytes, &nonce_bytes, info_ref, &aad_bytes) {
        Ok(pt) => match env.byte_array_from_slice(&pt) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_computeSafetyNumber<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    ik_a: JByteArray<'local>,
    ik_b: JByteArray<'local>,
) -> jstring {
    let ik_a_bytes = match env.convert_byte_array(ik_a) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let ik_b_bytes = match env.convert_byte_array(ik_b) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let a_slices: &[&[u8]] = &[&ik_a_bytes];
    let b_slices: &[&[u8]] = &[&ik_b_bytes];

    match safety::compute_safety_number(a_slices, b_slices) {
        Ok(num) => match env.new_string(&num) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_generateSafetyWords<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ik_a: JByteArray<'local>,
    ik_b: JByteArray<'local>,
) -> jobjectArray {
    let ik_a_bytes = match env.convert_byte_array(ik_a) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let ik_b_bytes = match env.convert_byte_array(ik_b) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let a_slices: &[&[u8]] = &[&ik_a_bytes];
    let b_slices: &[&[u8]] = &[&ik_b_bytes];

    match safety::compute_safety_fingerprint(a_slices, b_slices, None) {
        Ok(fp) => {
            let string_class = match env.find_class("java/lang/String") {
                Ok(c) => c,
                Err(_) => return std::ptr::null_mut(),
            };
            let initial_element = match env.new_string("") {
                Ok(s) => s,
                Err(_) => return std::ptr::null_mut(),
            };
            let array = match env.new_object_array(fp.words.len() as i32, string_class, initial_element) {
                Ok(a) => a,
                Err(_) => return std::ptr::null_mut(),
            };

            for (i, word) in fp.words.iter().enumerate() {
                if let Ok(js) = env.new_string(word) {
                    let _ = env.set_object_array_element(&array, i as i32, js);
                }
            }

            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_generateFileKeyAndNonce<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jbyteArray {
    let (key, nonce) = cipher::generate_file_key_and_nonce();
    let mut combined = Vec::with_capacity(32 + 12);
    combined.extend_from_slice(&key);
    combined.extend_from_slice(&nonce);
    match env.byte_array_from_slice(&combined) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_createChunkedFileHeader<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    base_nonce: JByteArray<'local>,
    chunk_size: jint,
) -> jbyteArray {
    let nonce_bytes = match env.convert_byte_array(base_nonce) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    if nonce_bytes.len() != cipher::NONCE_SIZE {
        return std::ptr::null_mut();
    }
    let mut bn = [0u8; 12];
    bn.copy_from_slice(&nonce_bytes);
    let header = cipher::create_chunked_file_header(&bn, chunk_size as u32);
    match env.byte_array_from_slice(&header) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_parseChunkedFileHeader<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    header: JByteArray<'local>,
) -> jbyteArray {
    let header_bytes = match env.convert_byte_array(header) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    match cipher::parse_chunked_file_header(&header_bytes) {
        Ok((base_nonce, chunk_size)) => {
            let mut out = Vec::with_capacity(16);
            out.extend_from_slice(&base_nonce);
            out.extend_from_slice(&chunk_size.to_be_bytes());
            match env.byte_array_from_slice(&out) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_isChunkedFile<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
) -> jboolean {
    let bytes = match env.convert_byte_array(data) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    if cipher::is_chunked_file(&bytes) {
        1
    } else {
        0
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_encryptFileChunk<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    key: JByteArray<'local>,
    base_nonce: JByteArray<'local>,
    chunk_index: jint,
    is_last: jboolean,
    chunk: JByteArray<'local>,
) -> jbyteArray {
    let key_bytes = match env.convert_byte_array(key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let nonce_bytes = match env.convert_byte_array(base_nonce) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let chunk_bytes = match env.convert_byte_array(chunk) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    match cipher::encrypt_file_chunk(
        &key_bytes,
        &nonce_bytes,
        chunk_index as u32,
        is_last != 0,
        &chunk_bytes,
    ) {
        Ok(enc) => match env.byte_array_from_slice(&enc) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_decryptFileChunk<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    key: JByteArray<'local>,
    base_nonce: JByteArray<'local>,
    chunk_index: jint,
    is_last: jboolean,
    encrypted_chunk: JByteArray<'local>,
) -> jbyteArray {
    let key_bytes = match env.convert_byte_array(key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let nonce_bytes = match env.convert_byte_array(base_nonce) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let chunk_bytes = match env.convert_byte_array(encrypted_chunk) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    match cipher::decrypt_file_chunk(
        &key_bytes,
        &nonce_bytes,
        chunk_index as u32,
        is_last != 0,
        &chunk_bytes,
    ) {
        Ok(pt) => match env.byte_array_from_slice(&pt) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_decryptFileChaCha20<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    encrypted_bytes: JByteArray<'local>,
    key: JByteArray<'local>,
) -> jbyteArray {
    let enc = match env.convert_byte_array(encrypted_bytes) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let k = match env.convert_byte_array(key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    match cipher::decrypt_file(&enc, &k) {
        Ok(pt) => match env.byte_array_from_slice(&pt) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_encryptPairwiseBatch<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    sender_priv_key: JByteArray<'local>,
    sender_user_id: jlong,
    recipient_user_id: jlong,
    client_msg_id: JString<'local>,
    timestamp: jlong,
    plaintext: JByteArray<'local>,
    device_ids: JLongArray<'local>,
    device_pub_keys: JByteArray<'local>,
    device_crypto_versions: JIntArray<'local>,
) -> jbyteArray {
    let mut priv_bytes = match env.convert_byte_array(sender_priv_key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let msg_id_str: String = if client_msg_id.is_null() {
        String::new()
    } else {
        env.get_string(&client_msg_id)
            .map(|s| s.into())
            .unwrap_or_default()
    };

    let pt_bytes = if plaintext.is_null() {
        Vec::new()
    } else {
        env.convert_byte_array(plaintext).unwrap_or_default()
    };

    let dev_count = match env.get_array_length(&device_ids) {
        Ok(l) => l as usize,
        Err(_) => {
            priv_bytes.zeroize();
            return std::ptr::null_mut();
        }
    };

    let mut ids = vec![0i64; dev_count];
    if dev_count > 0 {
        if env.get_long_array_region(&device_ids, 0, &mut ids).is_err() {
            priv_bytes.zeroize();
            return std::ptr::null_mut();
        }
    }

    let mut vers = vec![0i32; dev_count];
    if dev_count > 0 {
        if env.get_int_array_region(&device_crypto_versions, 0, &mut vers).is_err() {
            priv_bytes.zeroize();
            return std::ptr::null_mut();
        }
    }

    let all_keys = match env.convert_byte_array(device_pub_keys) {
        Ok(k) => k,
        Err(_) => {
            priv_bytes.zeroize();
            return std::ptr::null_mut();
        }
    };

    if all_keys.len() != dev_count * 32 {
        priv_bytes.zeroize();
        return std::ptr::null_mut();
    }

    let mut recipients = Vec::with_capacity(dev_count);
    for i in 0..dev_count {
        let pk = &all_keys[i * 32..(i + 1) * 32];
        recipients.push(cipher::DeviceRecipient {
            device_id: ids[i],
            public_key: pk,
            crypto_version: vers[i].max(1) as u32,
        });
    }

    let envelopes = match cipher::encrypt_pairwise_fanout(
        &priv_bytes,
        sender_user_id as u64,
        recipient_user_id as u64,
        &msg_id_str,
        timestamp,
        &pt_bytes,
        &recipients,
    ) {
        Ok(e) => e,
        Err(_) => {
            priv_bytes.zeroize();
            return std::ptr::null_mut();
        }
    };
    priv_bytes.zeroize();

    // Packed serialization:
    // count: u32 BE
    // for each envelope:
    //   device_id: i64 BE (8 bytes)
    //   version: u32 BE (4 bytes)
    //   salt: [u8; 32] (32 bytes)
    //   nonce: [u8; 12] (12 bytes)
    //   ciphertext_len: u32 BE (4 bytes)
    //   ciphertext: [u8; ct_len]
    let count = envelopes.len() as u32;
    let mut out = Vec::new();
    out.extend_from_slice(&count.to_be_bytes());
    for envlp in envelopes {
        out.extend_from_slice(&envlp.device_id.to_be_bytes());
        out.extend_from_slice(&envlp.version.to_be_bytes());
        out.extend_from_slice(&envlp.salt);
        out.extend_from_slice(&envlp.nonce);
        let ct_len = envlp.ciphertext.len() as u32;
        out.extend_from_slice(&ct_len.to_be_bytes());
        out.extend_from_slice(&envlp.ciphertext);
    }

    match env.byte_array_from_slice(&out) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_cryptoVersion<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    crate::CRYPTO_CORE_VERSION as jint
}
