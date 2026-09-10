use penik_crypto::{
    build_group_aad, build_group_aad_v1, build_pairwise_aad, decrypt_file, diffie_hellman,
    e2ee_decrypt, e2ee_encrypt, encrypt_file, generate_key_pair, group_decrypt, group_encrypt,
    unwrap_group_key, wrap_group_key_for_device, DEFAULT_PAIRWISE_INFO,
};

#[test]
fn test_pairwise_aad_byte_exact() {
    let aad = build_pairwise_aad(10, 20, "msg-dm-1", 1700000000);
    let expected = vec![
        0, 0, 0, 1, 49, // '1'
        0, 0, 0, 2, 49, 48, // '1', '0'
        0, 0, 0, 2, 50, 48, // '2', '0'
        0, 0, 0, 8, 109, 115, 103, 45, 100, 109, 45, 49, // 'msg-dm-1'
        0, 0, 0, 10, 49, 55, 48, 48, 48, 48, 48, 48, 48, 48, // '1700000000'
    ];
    assert_eq!(aad, expected, "Pairwise AAD matches expected byte-for-byte");
}

#[test]
fn test_group_aad_structure() {
    let aad = build_group_aad(100, 1, 55, "group-msg-123", 1700005000);
    assert!(aad.len() > 0);
    let aad_v1 = build_group_aad_v1(100, 1, "group-msg-123", 1700005000);
    assert!(aad_v1.len() > 0);
}

#[test]
fn test_key_generation_and_dh() {
    let alice = generate_key_pair();
    let bob = generate_key_pair();

    let secret_alice = diffie_hellman(&alice.private_key, &bob.public_key).expect("DH alice");
    let secret_bob = diffie_hellman(&bob.private_key, &alice.public_key).expect("DH bob");

    assert_eq!(secret_alice, secret_bob, "Alice and Bob derive identical shared secret");
}

#[test]
fn test_e2ee_encrypt_decrypt_roundtrip() {
    let alice = generate_key_pair();
    let bob = generate_key_pair();
    let shared_secret = diffie_hellman(&alice.private_key, &bob.public_key).unwrap();

    let plaintext = b"Hello from Rust penik-crypto!";
    let aad = build_pairwise_aad(1, 2, "msg-001", 1710000000);

    let enc = e2ee_encrypt(plaintext, &shared_secret, DEFAULT_PAIRWISE_INFO, &aad)
        .expect("encryption succeeds");

    assert_eq!(enc.ciphertext.len(), plaintext.len() + 16);
    assert_eq!(enc.salt.len(), 32);
    assert_eq!(enc.nonce.len(), 12);

    let dec = e2ee_decrypt(
        &enc.ciphertext,
        &shared_secret,
        &enc.salt,
        &enc.nonce,
        DEFAULT_PAIRWISE_INFO,
        &aad,
    )
    .expect("decryption succeeds");

    assert_eq!(dec, plaintext);

    // Tamper test
    let wrong_aad = build_pairwise_aad(999, 2, "msg-001", 1710000000);
    assert!(e2ee_decrypt(
        &enc.ciphertext,
        &shared_secret,
        &enc.salt,
        &enc.nonce,
        DEFAULT_PAIRWISE_INFO,
        &wrong_aad,
    )
    .is_err());
}

#[test]
fn test_file_encrypt_decrypt() {
    let file_data = b"Some simulated image or attachment bytes";
    let (encrypted, key) = encrypt_file(file_data).expect("file encrypt succeeds");

    assert_eq!(encrypted.len(), 12 + file_data.len() + 16);
    let decrypted = decrypt_file(&encrypted, &key).expect("file decrypt succeeds");
    assert_eq!(decrypted, file_data);

    // Corrupt tag
    let mut corrupted = encrypted.clone();
    let last = corrupted.len() - 1;
    corrupted[last] ^= 0x01;
    assert!(decrypt_file(&corrupted, &key).is_err());
}

#[test]
fn test_group_encrypt_decrypt() {
    let group_key = [42u8; 32];
    let plaintext = b"Hello Group!";
    let group_id = 10;
    let key_version = 1;
    let sender_user_id = 7;
    let message_id = "grp-msg-1";
    let created_at = 1710002000;

    let enc = group_encrypt(
        plaintext,
        &group_key,
        group_id,
        key_version,
        sender_user_id,
        message_id,
        created_at,
    )
    .expect("group encrypt");

    let dec = group_decrypt(
        &enc.ciphertext,
        &group_key,
        &enc.salt,
        &enc.nonce,
        group_id,
        key_version,
        sender_user_id,
        message_id,
        created_at,
    )
    .expect("group decrypt");

    assert_eq!(dec, plaintext);
}

#[test]
fn test_group_key_wrap_unwrap() {
    let alice = generate_key_pair();
    let bob = generate_key_pair();
    let shared_secret = diffie_hellman(&alice.private_key, &bob.public_key).unwrap();

    let group_key = [99u8; 32];
    let group_id = 10;
    let key_version = 2;

    let wrapped = wrap_group_key_for_device(&group_key, &shared_secret, group_id, key_version)
        .expect("wrap group key");

    let unwrapped = unwrap_group_key(
        &wrapped.ciphertext,
        &shared_secret,
        &wrapped.salt,
        &wrapped.nonce,
        group_id,
        key_version,
    )
    .expect("unwrap group key");

    assert_eq!(unwrapped, group_key);
}

#[test]
fn test_pairwise_aad_v2_exact() {
    use penik_crypto::build_pairwise_aad_v2;
    let aad = build_pairwise_aad_v2(10, 20, "msg-dm-1");
    let expected = vec![
        0, 0, 0, 1, 50, // '2'
        0, 0, 0, 2, 49, 48, // '1', '0'
        0, 0, 0, 2, 50, 48, // '2', '0'
        0, 0, 0, 8, 109, 115, 103, 45, 100, 109, 45, 49, // 'msg-dm-1'
    ];
    assert_eq!(aad, expected, "Pairwise AAD v2 matches expected byte-for-byte");
}

#[test]
fn test_pbkdf2_derive() {
    use penik_crypto::pbkdf2_derive;
    let salt = b"salt_for_backup";
    let key = pbkdf2_derive(b"super_secret_password", salt, 1000, 32);
    assert_eq!(key.len(), 32);
    // Deterministic output
    let key2 = pbkdf2_derive(b"super_secret_password", salt, 1000, 32);
    assert_eq!(key, key2);
}

#[test]
fn test_hkdf_derive() {
    use penik_crypto::hkdf_derive;
    let ikm = [42u8; 32];
    let salt = [7u8; 32];
    let okm = hkdf_derive(&salt, &ikm, b"test-info", 64).expect("hkdf derive");
    assert_eq!(okm.len(), 64);
}

