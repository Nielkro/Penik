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

#[test]
fn test_chunked_file_encrypt_decrypt_roundtrip() {
    use penik_crypto::{
        decrypt_file, decrypt_file_chunk, encrypt_file, encrypt_file_chunk, encrypt_file_chunked,
        generate_file_key_and_nonce, is_chunked_file, parse_chunked_file_header,
        DEFAULT_CHUNK_SIZE,
    };

    // 1. Empty file
    let empty_data = b"";
    let (enc_empty, key_empty) = encrypt_file_chunked(empty_data).expect("encrypt empty");
    assert!(is_chunked_file(&enc_empty));
    let dec_empty = decrypt_file(&enc_empty, &key_empty).expect("decrypt empty");
    assert_eq!(dec_empty, empty_data);

    // 2. Small file (< 1 chunk)
    let small_data = b"Streaming video chunk test in penik-crypto!";
    let (enc_small, key_small) = encrypt_file_chunked(small_data).expect("encrypt small");
    assert!(is_chunked_file(&enc_small));
    let dec_small = decrypt_file(&enc_small, &key_small).expect("decrypt small");
    assert_eq!(dec_small, small_data);

    // 3. Multi-chunk file (150 KB > 2 chunks of 64 KB)
    let mut large_data = vec![0u8; 150 * 1024];
    for (i, b) in large_data.iter_mut().enumerate() {
        *b = (i % 251) as u8;
    }
    let (enc_large, key_large) = encrypt_file_chunked(&large_data).expect("encrypt multi-chunk");
    assert!(is_chunked_file(&enc_large));
    let dec_large = decrypt_file(&enc_large, &key_large).expect("decrypt multi-chunk");
    assert_eq!(dec_large, large_data);

    // 4. Backward compatibility: legacy monolithic file decrypted by decrypt_file
    let (enc_legacy, key_legacy) = encrypt_file(small_data).expect("encrypt legacy");
    assert!(!is_chunked_file(&enc_legacy));
    let dec_legacy = decrypt_file(&enc_legacy, &key_legacy).expect("decrypt legacy");
    assert_eq!(dec_legacy, small_data);

    // 5. Tampering: corrupting ciphertext in a chunk causes decryption failure
    let mut tampered = enc_small.clone();
    tampered[25] ^= 0xff;
    assert!(decrypt_file(&tampered, &key_small).is_err(), "Tampered chunk must fail auth check");

    // 6. Truncation: cutting off the last chunk must fail auth check
    let (base_nonce, chunk_size) = parse_chunked_file_header(&enc_large).unwrap();
    assert_eq!(chunk_size as usize, DEFAULT_CHUNK_SIZE);
    assert_eq!(base_nonce.len(), 12);
    let truncated = &enc_large[..enc_large.len() - 100];
    assert!(decrypt_file(truncated, &key_large).is_err(), "Truncated file must fail auth check");

    // 7. Direct low-level chunk API
    let (key, nonce) = generate_file_key_and_nonce();
    let chunk_pt = b"Low-level chunk 0 payload";
    let enc_c0 = encrypt_file_chunk(&key, &nonce, 0, false, chunk_pt).unwrap();
    let dec_c0 = decrypt_file_chunk(&key, &nonce, 0, false, &enc_c0).unwrap();
    assert_eq!(dec_c0, chunk_pt);
    // Wrong index fails
    assert!(decrypt_file_chunk(&key, &nonce, 1, false, &enc_c0).is_err());
    // Wrong is_last flag fails
    assert!(decrypt_file_chunk(&key, &nonce, 0, true, &enc_c0).is_err());
}

#[test]
fn test_pairwise_fanout_encryption() {
    use penik_crypto::{
        diffie_hellman, e2ee_decrypt, encrypt_pairwise_fanout, generate_key_pair,
        DeviceRecipient, DEFAULT_PAIRWISE_INFO,
    };

    let sender = generate_key_pair();
    let dev1 = generate_key_pair();
    let dev2 = generate_key_pair();
    let dev3 = generate_key_pair();

    let recipients = vec![
        DeviceRecipient {
            device_id: 101,
            public_key: &dev1.public_key,
            crypto_version: 1, // Legacy AAD v1
        },
        DeviceRecipient {
            device_id: 102,
            public_key: &dev2.public_key,
            crypto_version: 2, // Modern AAD v2
        },
        DeviceRecipient {
            device_id: 103,
            public_key: &dev3.public_key,
            crypto_version: 3, // Future AAD v2+
        },
    ];

    let plaintext = b"Pairwise fan-out batch message across multiple devices!";
    let envelopes = encrypt_pairwise_fanout(
        &sender.private_key,
        1,
        2,
        "client-msg-batch-42",
        1720000000,
        plaintext,
        &recipients,
    )
    .expect("batch fanout encryption succeeds");

    assert_eq!(envelopes.len(), 3);
    assert_eq!(envelopes[0].device_id, 101);
    assert_eq!(envelopes[0].version, 1);
    assert_eq!(envelopes[1].device_id, 102);
    assert_eq!(envelopes[1].version, 2);
    assert_eq!(envelopes[2].device_id, 103);
    assert_eq!(envelopes[2].version, 2);

    // Verify dev1 (v1) decrypts correctly
    let shared1 = diffie_hellman(&dev1.private_key, &sender.public_key).unwrap();
    let aad1 = penik_crypto::build_pairwise_aad(1, 2, "client-msg-batch-42", 1720000000);
    let pt1 = e2ee_decrypt(
        &envelopes[0].ciphertext,
        &shared1,
        &envelopes[0].salt,
        &envelopes[0].nonce,
        DEFAULT_PAIRWISE_INFO,
        &aad1,
    )
    .expect("dev1 decrypt");
    assert_eq!(pt1, plaintext);

    // Verify dev2 (v2) decrypts correctly
    let shared2 = diffie_hellman(&dev2.private_key, &sender.public_key).unwrap();
    let aad2 = penik_crypto::build_pairwise_aad_v2(1, 2, "client-msg-batch-42");
    let pt2 = e2ee_decrypt(
        &envelopes[1].ciphertext,
        &shared2,
        &envelopes[1].salt,
        &envelopes[1].nonce,
        DEFAULT_PAIRWISE_INFO,
        &aad2,
    )
    .expect("dev2 decrypt");
    assert_eq!(pt2, plaintext);

    // Verify dev3 (v2) decrypts correctly
    let shared3 = diffie_hellman(&dev3.private_key, &sender.public_key).unwrap();
    let aad3 = penik_crypto::build_pairwise_aad_v2(1, 2, "client-msg-batch-42");
    let pt3 = e2ee_decrypt(
        &envelopes[2].ciphertext,
        &shared3,
        &envelopes[2].salt,
        &envelopes[2].nonce,
        DEFAULT_PAIRWISE_INFO,
        &aad3,
    )
    .expect("dev3 decrypt");
    assert_eq!(pt3, plaintext);
}

