use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::slice;

use crate::aad;
use crate::cipher;
use crate::kdf;
use crate::keys;
use crate::safety;

#[no_mangle]
pub unsafe extern "C" fn penik_generate_key_pair(
    out_pub: *mut u8,
    out_priv: *mut u8,
) -> i32 {
    if out_pub.is_null() || out_priv.is_null() {
        return -1;
    }
    let kp = keys::generate_key_pair();
    std::ptr::copy_nonoverlapping(kp.public_key.as_ptr(), out_pub, 32);
    std::ptr::copy_nonoverlapping(kp.private_key.as_ptr(), out_priv, 32);
    0
}

#[no_mangle]
pub unsafe extern "C" fn penik_derive_public_key(
    in_priv: *const u8,
    out_pub: *mut u8,
) -> i32 {
    if in_priv.is_null() || out_pub.is_null() {
        return -1;
    }
    let priv_slice = slice::from_raw_parts(in_priv, 32);
    match keys::derive_public_key(priv_slice) {
        Ok(pub_key) => {
            std::ptr::copy_nonoverlapping(pub_key.as_ptr(), out_pub, 32);
            0
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub unsafe extern "C" fn penik_derive_shared_secret(
    in_priv: *const u8,
    in_peer_pub: *const u8,
    peer_pub_len: usize,
    out_secret: *mut u8,
) -> i32 {
    if in_priv.is_null() || in_peer_pub.is_null() || out_secret.is_null() {
        return -1;
    }
    let priv_slice = slice::from_raw_parts(in_priv, 32);
    let peer_pub_slice = slice::from_raw_parts(in_peer_pub, peer_pub_len);
    match keys::diffie_hellman(priv_slice, peer_pub_slice) {
        Ok(shared) => {
            std::ptr::copy_nonoverlapping(shared.as_ptr(), out_secret, 32);
            0
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub unsafe extern "C" fn penik_build_pairwise_aad(
    sender_user_id: u64,
    recipient_user_id: u64,
    client_msg_id: *const c_char,
    timestamp: i64,
    out_buf: *mut u8,
    out_len: *mut usize,
) -> i32 {
    if out_len.is_null() {
        return -1;
    }
    let msg_id = if client_msg_id.is_null() {
        ""
    } else {
        match CStr::from_ptr(client_msg_id).to_str() {
            Ok(s) => s,
            Err(_) => return -1,
        }
    };

    let aad = aad::build_pairwise_aad(sender_user_id, recipient_user_id, msg_id, timestamp);
    if out_buf.is_null() {
        *out_len = aad.len();
        return 0;
    }

    if *out_len < aad.len() {
        *out_len = aad.len();
        return -2; // Buffer too small
    }

    std::ptr::copy_nonoverlapping(aad.as_ptr(), out_buf, aad.len());
    *out_len = aad.len();
    0
}

#[no_mangle]
pub unsafe extern "C" fn penik_build_pairwise_aad_v2(
    sender_user_id: u64,
    recipient_user_id: u64,
    client_msg_id: *const c_char,
    out_buf: *mut u8,
    out_len: *mut usize,
) -> i32 {
    if out_len.is_null() {
        return -1;
    }
    let msg_id = if client_msg_id.is_null() {
        ""
    } else {
        match CStr::from_ptr(client_msg_id).to_str() {
            Ok(s) => s,
            Err(_) => return -1,
        }
    };

    let aad = aad::build_pairwise_aad_v2(sender_user_id, recipient_user_id, msg_id);
    if out_buf.is_null() {
        *out_len = aad.len();
        return 0;
    }

    if *out_len < aad.len() {
        *out_len = aad.len();
        return -2; // Buffer too small
    }

    std::ptr::copy_nonoverlapping(aad.as_ptr(), out_buf, aad.len());
    *out_len = aad.len();
    0
}

#[no_mangle]
pub unsafe extern "C" fn penik_e2ee_encrypt(
    plaintext: *const u8,
    plaintext_len: usize,
    shared_secret: *const u8,
    info: *const u8,
    info_len: usize,
    aad: *const u8,
    aad_len: usize,
    out_ct: *mut u8,
    out_salt: *mut u8,
    out_nonce: *mut u8,
) -> i32 {
    if plaintext.is_null() && plaintext_len > 0 {
        return -1;
    }
    if shared_secret.is_null() || out_ct.is_null() || out_salt.is_null() || out_nonce.is_null() {
        return -1;
    }

    let pt_slice = if plaintext_len > 0 {
        slice::from_raw_parts(plaintext, plaintext_len)
    } else {
        &[]
    };
    let secret_slice = slice::from_raw_parts(shared_secret, 32);
    let info_slice = if !info.is_null() && info_len > 0 {
        slice::from_raw_parts(info, info_len)
    } else {
        cipher::DEFAULT_PAIRWISE_INFO
    };
    let aad_slice = if !aad.is_null() && aad_len > 0 {
        slice::from_raw_parts(aad, aad_len)
    } else {
        &[]
    };

    match cipher::e2ee_encrypt(pt_slice, secret_slice, info_slice, aad_slice) {
        Ok(enc) => {
            std::ptr::copy_nonoverlapping(enc.ciphertext.as_ptr(), out_ct, enc.ciphertext.len());
            std::ptr::copy_nonoverlapping(enc.salt.as_ptr(), out_salt, 32);
            std::ptr::copy_nonoverlapping(enc.nonce.as_ptr(), out_nonce, 12);
            0
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub unsafe extern "C" fn penik_e2ee_decrypt(
    ciphertext: *const u8,
    ciphertext_len: usize,
    shared_secret: *const u8,
    salt: *const u8,
    nonce: *const u8,
    info: *const u8,
    info_len: usize,
    aad: *const u8,
    aad_len: usize,
    out_pt: *mut u8,
    out_pt_len: *mut usize,
) -> i32 {
    if ciphertext.is_null() || shared_secret.is_null() || salt.is_null() || nonce.is_null() || out_pt_len.is_null() {
        return -1;
    }

    let ct_slice = slice::from_raw_parts(ciphertext, ciphertext_len);
    let secret_slice = slice::from_raw_parts(shared_secret, 32);
    let salt_slice = slice::from_raw_parts(salt, 32);
    let nonce_slice = slice::from_raw_parts(nonce, 12);
    let info_slice = if !info.is_null() && info_len > 0 {
        slice::from_raw_parts(info, info_len)
    } else {
        cipher::DEFAULT_PAIRWISE_INFO
    };
    let aad_slice = if !aad.is_null() && aad_len > 0 {
        slice::from_raw_parts(aad, aad_len)
    } else {
        &[]
    };

    match cipher::e2ee_decrypt(ct_slice, secret_slice, salt_slice, nonce_slice, info_slice, aad_slice) {
        Ok(pt) => {
            if out_pt.is_null() {
                *out_pt_len = pt.len();
                return 0;
            }
            if *out_pt_len < pt.len() {
                *out_pt_len = pt.len();
                return -2;
            }
            std::ptr::copy_nonoverlapping(pt.as_ptr(), out_pt, pt.len());
            *out_pt_len = pt.len();
            0
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub unsafe extern "C" fn penik_encrypt_file(
    file_bytes: *const u8,
    file_len: usize,
    out_encrypted: *mut u8,
    out_key: *mut u8,
) -> i32 {
    if (file_bytes.is_null() && file_len > 0) || out_encrypted.is_null() || out_key.is_null() {
        return -1;
    }
    let file_slice = if file_len > 0 {
        slice::from_raw_parts(file_bytes, file_len)
    } else {
        &[]
    };

    match cipher::encrypt_file(file_slice) {
        Ok((encrypted, key)) => {
            std::ptr::copy_nonoverlapping(encrypted.as_ptr(), out_encrypted, encrypted.len());
            std::ptr::copy_nonoverlapping(key.as_ptr(), out_key, 32);
            0
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub unsafe extern "C" fn penik_decrypt_file(
    encrypted_bytes: *const u8,
    encrypted_len: usize,
    key: *const u8,
    out_decrypted: *mut u8,
    out_decrypted_len: *mut usize,
) -> i32 {
    if encrypted_bytes.is_null() || key.is_null() || out_decrypted_len.is_null() {
        return -1;
    }
    let enc_slice = slice::from_raw_parts(encrypted_bytes, encrypted_len);
    let key_slice = slice::from_raw_parts(key, 32);

    match cipher::decrypt_file(enc_slice, key_slice) {
        Ok(decrypted) => {
            if out_decrypted.is_null() {
                *out_decrypted_len = decrypted.len();
                return 0;
            }
            if *out_decrypted_len < decrypted.len() {
                *out_decrypted_len = decrypted.len();
                return -2;
            }
            std::ptr::copy_nonoverlapping(decrypted.as_ptr(), out_decrypted, decrypted.len());
            *out_decrypted_len = decrypted.len();
            0
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub unsafe extern "C" fn penik_compute_safety_fingerprint(
    keys_a: *const *const u8,
    keys_a_lens: *const usize,
    keys_a_count: usize,
    keys_b: *const *const u8,
    keys_b_lens: *const usize,
    keys_b_count: usize,
    user_id: *const c_char,
    out_number: *mut c_char,
    max_number: usize,
    out_hex: *mut c_char,
    max_hex: usize,
    out_qr: *mut c_char,
    max_qr: usize,
) -> i32 {
    let mut a_slices = Vec::new();
    if !keys_a.is_null() && !keys_a_lens.is_null() {
        let ptrs = slice::from_raw_parts(keys_a, keys_a_count);
        let lens = slice::from_raw_parts(keys_a_lens, keys_a_count);
        for i in 0..keys_a_count {
            a_slices.push(slice::from_raw_parts(ptrs[i], lens[i]));
        }
    }

    let mut b_slices = Vec::new();
    if !keys_b.is_null() && !keys_b_lens.is_null() {
        let ptrs = slice::from_raw_parts(keys_b, keys_b_count);
        let lens = slice::from_raw_parts(keys_b_lens, keys_b_count);
        for i in 0..keys_b_count {
            b_slices.push(slice::from_raw_parts(ptrs[i], lens[i]));
        }
    }

    let uid = if !user_id.is_null() {
        CStr::from_ptr(user_id).to_str().ok()
    } else {
        None
    };

    match safety::compute_safety_fingerprint(&a_slices, &b_slices, uid) {
        Ok(fp) => {
            if !out_number.is_null() && max_number > fp.number.len() {
                let c_num = CString::new(fp.number).unwrap();
                std::ptr::copy_nonoverlapping(c_num.as_ptr(), out_number, c_num.as_bytes_with_nul().len());
            }
            if !out_hex.is_null() && max_hex > fp.hex.len() {
                let c_hex = CString::new(fp.hex).unwrap();
                std::ptr::copy_nonoverlapping(c_hex.as_ptr(), out_hex, c_hex.as_bytes_with_nul().len());
            }
            if !out_qr.is_null() && max_qr > fp.qr_payload.len() {
                let c_qr = CString::new(fp.qr_payload).unwrap();
                std::ptr::copy_nonoverlapping(c_qr.as_ptr(), out_qr, c_qr.as_bytes_with_nul().len());
            }
            0
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub unsafe extern "C" fn penik_pbkdf2_derive(
    passphrase: *const u8,
    passphrase_len: usize,
    salt: *const u8,
    salt_len: usize,
    iterations: u32,
    out_key: *mut u8,
    out_len: usize,
) -> i32 {
    if passphrase.is_null() || salt.is_null() || out_key.is_null() || out_len == 0 {
        return -1;
    }
    let pass_slice = slice::from_raw_parts(passphrase, passphrase_len);
    let salt_slice = slice::from_raw_parts(salt, salt_len);
    let derived = kdf::pbkdf2_derive(pass_slice, salt_slice, iterations, out_len);
    std::ptr::copy_nonoverlapping(derived.as_ptr(), out_key, out_len);
    0
}

#[no_mangle]
pub unsafe extern "C" fn penik_hkdf_derive(
    secret: *const u8,
    secret_len: usize,
    salt: *const u8,
    salt_len: usize,
    info: *const u8,
    info_len: usize,
    out_key: *mut u8,
    out_len: usize,
) -> i32 {
    if secret.is_null() || out_key.is_null() || out_len == 0 {
        return -1;
    }
    let secret_slice = slice::from_raw_parts(secret, secret_len);
    let salt_slice = if !salt.is_null() && salt_len > 0 {
        slice::from_raw_parts(salt, salt_len)
    } else {
        &[]
    };
    let info_slice = if !info.is_null() && info_len > 0 {
        slice::from_raw_parts(info, info_len)
    } else {
        &[]
    };

    match kdf::hkdf_derive(salt_slice, secret_slice, info_slice, out_len) {
        Ok(derived) => {
            std::ptr::copy_nonoverlapping(derived.as_ptr(), out_key, out_len);
            0
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub unsafe extern "C" fn penik_zeroize(
    buf: *mut u8,
    len: usize,
) -> i32 {
    if buf.is_null() || len == 0 {
        return 0;
    }
    let s = slice::from_raw_parts_mut(buf, len);
    zeroize::Zeroize::zeroize(s);
    0
}
