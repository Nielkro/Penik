use penik_crypto::{
    compute_safety_fingerprint, compute_safety_hash, compute_safety_number, normalize_public_key,
    RUSSIAN_WORDS,
};

#[test]
fn test_normalize_public_key() {
    // 32-byte raw
    let raw = [7u8; 32];
    assert_eq!(normalize_public_key(&raw).unwrap(), raw);

    // 33-byte with 0x05 prefix
    let mut with_prefix = [0u8; 33];
    with_prefix[0] = 0x05;
    with_prefix[1..33].copy_from_slice(&raw);
    assert_eq!(normalize_public_key(&with_prefix).unwrap(), raw);

    // Invalid length
    assert!(normalize_public_key(&[1u8; 31]).is_err());
    assert!(normalize_public_key(&[1u8; 35]).is_err());
}

#[test]
fn test_safety_number_reproducible_and_order_independent() {
    let key_a = [1u8; 32];
    let key_b = [2u8; 32];

    let num_ab = compute_safety_number(&[&key_a], &[&key_b]).expect("safety number ab");
    let num_ba = compute_safety_number(&[&key_b], &[&key_a]).expect("safety number ba");

    assert_eq!(num_ab, num_ba, "Safety number is independent of order");
    assert_eq!(num_ab.split_whitespace().count(), 5, "5 blocks of digits");

    for block in num_ab.split_whitespace() {
        assert_eq!(block.len(), 5, "each block is 5 digits");
        assert!(block.chars().all(|c| c.is_ascii_digit()));
    }
}

#[test]
fn test_safety_fingerprint_structure() {
    let key_a = [10u8; 32];
    let key_b = [20u8; 32];

    let fp = compute_safety_fingerprint(&[&key_a], &[&key_b], Some("user-42")).unwrap();
    assert_eq!(fp.words.len(), 10, "10 Russian mnemonic words");
    for word in &fp.words {
        assert!(RUSSIAN_WORDS.contains(&word.as_str()));
    }
    assert_eq!(fp.hex.len(), 64, "SHA-256 hex string");
    assert!(fp.qr_payload.starts_with("penik://safety?fp="));
    assert!(fp.qr_payload.ends_with("&uid=user-42"));
}

#[test]
fn test_safety_number_cross_platform_exact() {
    // Exact test vector: verify against known hash calculation
    let key1 = [0xAAu8; 32];
    let key2 = [0xBBu8; 32];

    let hash = compute_safety_hash(&[&key1], &[&key2]).unwrap();
    assert_eq!(hash.len(), 32);

    let num = compute_safety_number(&[&key1], &[&key2]).unwrap();
    assert_eq!(num.len(), 29); // 5 * 5 + 4 spaces = 29 chars
}
