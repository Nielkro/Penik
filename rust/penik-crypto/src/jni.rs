#![cfg(not(target_arch = "wasm32"))]

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jlong, jobjectArray, jstring};
use jni::JNIEnv;

use crate::{aad, cipher, keys, safety};

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
    let priv_bytes = match env.convert_byte_array(priv_key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    match keys::derive_public_key(&priv_bytes) {
        Ok(pub_key) => match env.byte_array_from_slice(&pub_key) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_niel_kro_penik_data_crypto_RustCryptoCore_diffieHellman<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    priv_key: JByteArray<'local>,
    peer_pub_key: JByteArray<'local>,
) -> jbyteArray {
    let priv_bytes = match env.convert_byte_array(priv_key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let peer_pub_bytes = match env.convert_byte_array(peer_pub_key) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    match keys::diffie_hellman(&priv_bytes, &peer_pub_bytes) {
        Ok(shared) => match env.byte_array_from_slice(&shared) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
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

    match cipher::e2ee_decrypt(&ct_bytes, &salt_bytes, &nonce_bytes, &secret_bytes, info_ref, &aad_bytes) {
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
