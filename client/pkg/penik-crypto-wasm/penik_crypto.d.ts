/* tslint:disable */
/* eslint-disable */

export class JsChunkedHeader {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    readonly baseNonce: Uint8Array;
    readonly chunkSize: number;
}

export class JsChunkedKeyNonce {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    readonly baseNonce: Uint8Array;
    readonly key: Uint8Array;
}

export class JsE2EEEncrypted {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    readonly ciphertext: Uint8Array;
    readonly nonce: Uint8Array;
    readonly salt: Uint8Array;
}

export class JsEncryptedFile {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    readonly encryptedBytes: Uint8Array;
    readonly key: Uint8Array;
}

export class JsGroupKeyWrapped {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    readonly encryptedKey: Uint8Array;
    readonly nonce: Uint8Array;
    readonly salt: Uint8Array;
}

export class JsKeyPair {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    readonly privateKey: Uint8Array;
    readonly publicKey: Uint8Array;
}

export class JsSafetyFingerprint {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    readonly hex: string;
    readonly number: string;
    readonly qrPayload: string;
    readonly words: Array<any>;
}

export function buildGroupAAD(group_id: bigint, key_version: bigint, sender_user_id: bigint, message_id: string, created_at: bigint): Uint8Array;

export function buildGroupAADv1(group_id: bigint, key_version: bigint, message_id: string, created_at: bigint): Uint8Array;

export function buildPairwiseAAD(sender_user_id: bigint, recipient_user_id: bigint, client_msg_id?: string | null, timestamp?: bigint | null): Uint8Array;

export function buildPairwiseAADV2(sender_user_id: bigint, recipient_user_id: bigint, client_msg_id?: string | null): Uint8Array;

export function chacha20Poly1305Decrypt(key: Uint8Array, nonce: Uint8Array, ciphertext_and_tag: Uint8Array, aad?: Uint8Array | null): Uint8Array;

export function chacha20Poly1305Encrypt(key: Uint8Array, nonce: Uint8Array, plaintext: Uint8Array, aad?: Uint8Array | null): Uint8Array;

export function computeSafetyFingerprint(keys_a: any, keys_b: any, user_id?: string | null): JsSafetyFingerprint;

export function computeSafetyNumber(keys_a: any, keys_b: any): string;

export function createChunkedFileHeader(base_nonce: Uint8Array, chunk_size: number): Uint8Array;

export function decodeKey(b64: string): Uint8Array;

export function decryptFileChaCha20(encrypted_bytes: Uint8Array, key: Uint8Array): Uint8Array;

export function decryptFileChunk(key: Uint8Array, base_nonce: Uint8Array, chunk_index: number, is_last: boolean, encrypted_chunk: Uint8Array): Uint8Array;

export function derivePublicKey(private_key: Uint8Array): Uint8Array;

export function deriveSharedSecret(private_key: Uint8Array, peer_public_key: Uint8Array): Uint8Array;

export function deriveVerifyingKey(signing_key: Uint8Array): Uint8Array;

export function e2eeDecrypt(ciphertext: Uint8Array, shared_secret: Uint8Array, salt: Uint8Array, nonce: Uint8Array, info?: string | null, aad?: Uint8Array | null): Uint8Array;

export function e2eeEncrypt(plaintext: Uint8Array, shared_secret: Uint8Array, info?: string | null, aad?: Uint8Array | null): JsE2EEEncrypted;

export function encodeKey(bytes: Uint8Array): string;

export function encryptFileChaCha20(file_bytes: Uint8Array): JsEncryptedFile;

export function encryptFileChunk(key: Uint8Array, base_nonce: Uint8Array, chunk_index: number, is_last: boolean, chunk: Uint8Array): Uint8Array;

export function encryptFileChunked(file_bytes: Uint8Array): JsEncryptedFile;

export function encryptPairwiseBatch(sender_priv_key: Uint8Array, sender_user_id: bigint, recipient_user_id: bigint, client_msg_id: string, timestamp: bigint, plaintext: Uint8Array, devices: any): Array<any>;

export function generateFileKeyAndNonce(): JsChunkedKeyNonce;

export function generateKeyPair(): JsKeyPair;

export function generateSigningKeyPair(): JsKeyPair;

export function groupDecrypt(ciphertext: Uint8Array, group_key: Uint8Array, salt: Uint8Array, nonce: Uint8Array, group_id: bigint, key_version: bigint, sender_user_id: bigint, message_id: string, created_at: bigint): Uint8Array;

export function groupDecryptVerified(ciphertext: Uint8Array, verifying_key: Uint8Array | null | undefined, group_key: Uint8Array, salt: Uint8Array, nonce: Uint8Array, group_id: bigint, key_version: bigint, sender_user_id: bigint, message_id: string, created_at: bigint): Uint8Array;

export function groupEncrypt(plaintext: Uint8Array, group_key: Uint8Array, group_id: bigint, key_version: bigint, sender_user_id: bigint, message_id: string, created_at: bigint): JsE2EEEncrypted;

export function groupEncryptSigned(plaintext: Uint8Array, signing_key: Uint8Array, group_key: Uint8Array, group_id: bigint, key_version: bigint, sender_user_id: bigint, message_id: string, created_at: bigint): JsE2EEEncrypted;

export function isChunkedFile(data: Uint8Array): boolean;

export function parseChunkedFileHeader(header: Uint8Array): JsChunkedHeader;

export function penikCryptoVersion(): number;

export function signGroupMessage(signing_key: Uint8Array, group_id: bigint, key_version: bigint, sender_user_id: bigint, message_id: string, created_at: bigint, plaintext: Uint8Array): Uint8Array;

export function unwrapGroupKey(encrypted_key: Uint8Array, shared_secret: Uint8Array, salt: Uint8Array, nonce: Uint8Array, group_id: bigint, key_version: bigint): Uint8Array;

export function verifyGroupMessage(verifying_key: Uint8Array, signature: Uint8Array, group_id: bigint, key_version: bigint, sender_user_id: bigint, message_id: string, created_at: bigint, plaintext: Uint8Array): void;

export function wrapGroupKeyForDevice(group_key: Uint8Array, shared_secret: Uint8Array, group_id: bigint, key_version: bigint): JsGroupKeyWrapped;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly __wbg_jschunkedheader_free: (a: number, b: number) => void;
    readonly __wbg_jschunkedkeynonce_free: (a: number, b: number) => void;
    readonly __wbg_jse2eeencrypted_free: (a: number, b: number) => void;
    readonly __wbg_jsencryptedfile_free: (a: number, b: number) => void;
    readonly __wbg_jsgroupkeywrapped_free: (a: number, b: number) => void;
    readonly __wbg_jskeypair_free: (a: number, b: number) => void;
    readonly __wbg_jssafetyfingerprint_free: (a: number, b: number) => void;
    readonly buildGroupAAD: (a: bigint, b: bigint, c: bigint, d: number, e: number, f: bigint) => any;
    readonly buildGroupAADv1: (a: bigint, b: bigint, c: number, d: number, e: bigint) => any;
    readonly buildPairwiseAAD: (a: bigint, b: bigint, c: number, d: number, e: number, f: bigint) => any;
    readonly buildPairwiseAADV2: (a: bigint, b: bigint, c: number, d: number) => any;
    readonly chacha20Poly1305Decrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number) => [number, number, number];
    readonly chacha20Poly1305Encrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number) => [number, number, number];
    readonly computeSafetyFingerprint: (a: any, b: any, c: number, d: number) => [number, number, number];
    readonly computeSafetyNumber: (a: any, b: any) => [number, number, number, number];
    readonly createChunkedFileHeader: (a: number, b: number, c: number) => [number, number, number];
    readonly decodeKey: (a: number, b: number) => [number, number, number];
    readonly decryptFileChaCha20: (a: number, b: number, c: number, d: number) => [number, number, number];
    readonly decryptFileChunk: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number) => [number, number, number];
    readonly derivePublicKey: (a: number, b: number) => [number, number, number];
    readonly deriveSharedSecret: (a: number, b: number, c: number, d: number) => [number, number, number];
    readonly deriveVerifyingKey: (a: number, b: number) => [number, number, number];
    readonly e2eeDecrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number, j: number, k: number, l: number) => [number, number, number];
    readonly e2eeEncrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number) => [number, number, number];
    readonly encodeKey: (a: number, b: number) => [number, number];
    readonly encryptFileChaCha20: (a: number, b: number) => [number, number, number];
    readonly encryptFileChunk: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number) => [number, number, number];
    readonly encryptFileChunked: (a: number, b: number) => [number, number, number];
    readonly encryptPairwiseBatch: (a: number, b: number, c: bigint, d: bigint, e: number, f: number, g: bigint, h: number, i: number, j: any) => [number, number, number];
    readonly generateFileKeyAndNonce: () => number;
    readonly generateKeyPair: () => number;
    readonly generateSigningKeyPair: () => number;
    readonly groupDecrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: bigint, j: bigint, k: bigint, l: number, m: number, n: bigint) => [number, number, number];
    readonly groupDecryptVerified: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number, j: number, k: bigint, l: bigint, m: bigint, n: number, o: number, p: bigint) => [number, number, number];
    readonly groupEncrypt: (a: number, b: number, c: number, d: number, e: bigint, f: bigint, g: bigint, h: number, i: number, j: bigint) => [number, number, number];
    readonly groupEncryptSigned: (a: number, b: number, c: number, d: number, e: number, f: number, g: bigint, h: bigint, i: bigint, j: number, k: number, l: bigint) => [number, number, number];
    readonly isChunkedFile: (a: number, b: number) => number;
    readonly jschunkedheader_baseNonce: (a: number) => any;
    readonly jschunkedheader_chunkSize: (a: number) => number;
    readonly jschunkedkeynonce_baseNonce: (a: number) => any;
    readonly jschunkedkeynonce_key: (a: number) => any;
    readonly jse2eeencrypted_ciphertext: (a: number) => any;
    readonly jse2eeencrypted_nonce: (a: number) => any;
    readonly jse2eeencrypted_salt: (a: number) => any;
    readonly jsencryptedfile_encryptedBytes: (a: number) => any;
    readonly jsencryptedfile_key: (a: number) => any;
    readonly jsgroupkeywrapped_encryptedKey: (a: number) => any;
    readonly jsgroupkeywrapped_nonce: (a: number) => any;
    readonly jsgroupkeywrapped_salt: (a: number) => any;
    readonly jskeypair_privateKey: (a: number) => any;
    readonly jskeypair_publicKey: (a: number) => any;
    readonly jssafetyfingerprint_hex: (a: number) => [number, number];
    readonly jssafetyfingerprint_number: (a: number) => [number, number];
    readonly jssafetyfingerprint_qrPayload: (a: number) => [number, number];
    readonly jssafetyfingerprint_words: (a: number) => any;
    readonly parseChunkedFileHeader: (a: number, b: number) => [number, number, number];
    readonly penikCryptoVersion: () => number;
    readonly signGroupMessage: (a: number, b: number, c: bigint, d: bigint, e: bigint, f: number, g: number, h: bigint, i: number, j: number) => [number, number, number];
    readonly unwrapGroupKey: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: bigint, j: bigint) => [number, number, number];
    readonly verifyGroupMessage: (a: number, b: number, c: number, d: number, e: bigint, f: bigint, g: bigint, h: number, i: number, j: bigint, k: number, l: number) => [number, number];
    readonly wrapGroupKeyForDevice: (a: number, b: number, c: number, d: number, e: bigint, f: bigint) => [number, number, number];
    readonly penik_build_pairwise_aad: (a: bigint, b: bigint, c: number, d: bigint, e: number, f: number) => number;
    readonly penik_build_pairwise_aad_v2: (a: bigint, b: bigint, c: number, d: number, e: number) => number;
    readonly penik_compute_safety_fingerprint: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number, j: number, k: number, l: number, m: number) => number;
    readonly penik_crypto_version: () => number;
    readonly penik_decrypt_file: (a: number, b: number, c: number, d: number, e: number) => number;
    readonly penik_derive_public_key: (a: number, b: number) => number;
    readonly penik_derive_shared_secret: (a: number, b: number, c: number, d: number) => number;
    readonly penik_derive_verifying_key: (a: number, b: number) => number;
    readonly penik_e2ee_decrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number, j: number, k: number) => number;
    readonly penik_e2ee_encrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number, j: number) => number;
    readonly penik_encrypt_file: (a: number, b: number, c: number, d: number) => number;
    readonly penik_generate_key_pair: (a: number, b: number) => number;
    readonly penik_generate_signing_key_pair: (a: number, b: number) => number;
    readonly penik_group_decrypt_verified: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: bigint, i: bigint, j: bigint, k: number, l: bigint, m: number, n: number) => number;
    readonly penik_group_encrypt_signed: (a: number, b: number, c: number, d: number, e: number, f: bigint, g: bigint, h: bigint, i: number, j: bigint, k: number, l: number, m: number) => number;
    readonly penik_hkdf_derive: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number) => number;
    readonly penik_pbkdf2_derive: (a: number, b: number, c: number, d: number, e: number, f: number, g: number) => number;
    readonly penik_zeroize: (a: number, b: number) => number;
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
    readonly __wbindgen_exn_store: (a: number) => void;
    readonly __externref_table_alloc: () => number;
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __externref_table_dealloc: (a: number) => void;
    readonly __wbindgen_free: (a: number, b: number, c: number) => void;
    readonly __wbindgen_start: () => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
