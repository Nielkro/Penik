pub const PAIRWISE_PROTOCOL_VERSION: u32 = 1;
pub const GROUP_PROTOCOL_VERSION: u32 = 2;

fn encode_chunk(val: &str, buf: &mut Vec<u8>) {
    let bytes = val.as_bytes();
    let len = bytes.len() as u32;
    buf.extend_from_slice(&len.to_be_bytes());
    buf.extend_from_slice(bytes);
}

pub fn build_pairwise_aad(
    sender_user_id: u64,
    recipient_user_id: u64,
    client_msg_id: &str,
    timestamp: i64,
) -> Vec<u8> {
    let mut out = Vec::with_capacity(64);
    encode_chunk(&PAIRWISE_PROTOCOL_VERSION.to_string(), &mut out);
    encode_chunk(&sender_user_id.to_string(), &mut out);
    encode_chunk(&recipient_user_id.to_string(), &mut out);
    encode_chunk(client_msg_id, &mut out);
    encode_chunk(&timestamp.to_string(), &mut out);
    out
}

pub fn build_group_aad(
    group_id: u64,
    key_version: u64,
    sender_user_id: u64,
    message_id: &str,
    created_at: i64,
) -> Vec<u8> {
    let mut out = Vec::with_capacity(80);
    encode_chunk(&GROUP_PROTOCOL_VERSION.to_string(), &mut out);
    encode_chunk(&group_id.to_string(), &mut out);
    encode_chunk(&key_version.to_string(), &mut out);
    encode_chunk(&sender_user_id.to_string(), &mut out);
    encode_chunk(message_id, &mut out);
    encode_chunk(&created_at.to_string(), &mut out);
    out
}

pub fn build_group_aad_v1(
    group_id: u64,
    key_version: u64,
    message_id: &str,
    created_at: i64,
) -> Vec<u8> {
    let mut out = Vec::with_capacity(64);
    encode_chunk("1", &mut out);
    encode_chunk(&group_id.to_string(), &mut out);
    encode_chunk(&key_version.to_string(), &mut out);
    encode_chunk(message_id, &mut out);
    encode_chunk(&created_at.to_string(), &mut out);
    out
}
