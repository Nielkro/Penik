/* tslint:disable */
/* eslint-disable */

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

export function chacha20Poly1305Decrypt(key: Uint8Array, nonce: Uint8Array, ciphertext_and_tag: Uint8Array, aad?: Uint8Array | null): Uint8Array;

export function chacha20Poly1305Encrypt(key: Uint8Array, nonce: Uint8Array, plaintext: Uint8Array, aad?: Uint8Array | null): Uint8Array;

export function computeSafetyFingerprint(keys_a: any, keys_b: any, user_id?: string | null): JsSafetyFingerprint;

export function computeSafetyNumber(keys_a: any, keys_b: any): string;

export function decodeKey(b64: string): Uint8Array;

export function decryptFileChaCha20(encrypted_bytes: Uint8Array, key: Uint8Array): Uint8Array;

export function derivePublicKey(private_key: Uint8Array): Uint8Array;

export function deriveSharedSecret(private_key: Uint8Array, peer_public_key: Uint8Array): Uint8Array;

export function e2eeDecrypt(ciphertext: Uint8Array, shared_secret: Uint8Array, salt: Uint8Array, nonce: Uint8Array, info?: string | null, aad?: Uint8Array | null): Uint8Array;

export function e2eeEncrypt(plaintext: Uint8Array, shared_secret: Uint8Array, info?: string | null, aad?: Uint8Array | null): JsE2EEEncrypted;

export function encodeKey(bytes: Uint8Array): string;

export function encryptFileChaCha20(file_bytes: Uint8Array): JsEncryptedFile;

export function generateKeyPair(): JsKeyPair;

export function groupDecrypt(ciphertext: Uint8Array, group_key: Uint8Array, salt: Uint8Array, nonce: Uint8Array, group_id: bigint, key_version: bigint, sender_user_id: bigint, message_id: string, created_at: bigint): Uint8Array;

export function groupEncrypt(plaintext: Uint8Array, group_key: Uint8Array, group_id: bigint, key_version: bigint, sender_user_id: bigint, message_id: string, created_at: bigint): JsE2EEEncrypted;

export function unwrapGroupKey(encrypted_key: Uint8Array, shared_secret: Uint8Array, salt: Uint8Array, nonce: Uint8Array, group_id: bigint, key_version: bigint): Uint8Array;

export function wrapGroupKeyForDevice(group_key: Uint8Array, shared_secret: Uint8Array, group_id: bigint, key_version: bigint): JsGroupKeyWrapped;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly __wbg_jse2eeencrypted_free: (a: number, b: number) => void;
    readonly __wbg_jsencryptedfile_free: (a: number, b: number) => void;
    readonly __wbg_jsgroupkeywrapped_free: (a: number, b: number) => void;
    readonly __wbg_jskeypair_free: (a: number, b: number) => void;
    readonly __wbg_jssafetyfingerprint_free: (a: number, b: number) => void;
    readonly buildGroupAAD: (a: bigint, b: bigint, c: bigint, d: number, e: number, f: bigint) => any;
    readonly buildGroupAADv1: (a: bigint, b: bigint, c: number, d: number, e: bigint) => any;
    readonly buildPairwiseAAD: (a: bigint, b: bigint, c: number, d: number, e: number, f: bigint) => any;
    readonly chacha20Poly1305Decrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number) => [number, number, number];
    readonly chacha20Poly1305Encrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number) => [number, number, number];
    readonly computeSafetyFingerprint: (a: any, b: any, c: number, d: number) => [number, number, number];
    readonly computeSafetyNumber: (a: any, b: any) => [number, number, number, number];
    readonly decodeKey: (a: number, b: number) => [number, number, number];
    readonly decryptFileChaCha20: (a: number, b: number, c: number, d: number) => [number, number, number];
    readonly derivePublicKey: (a: number, b: number) => [number, number, number];
    readonly deriveSharedSecret: (a: number, b: number, c: number, d: number) => [number, number, number];
    readonly e2eeDecrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: number, j: number, k: number, l: number) => [number, number, number];
    readonly e2eeEncrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number) => [number, number, number];
    readonly encodeKey: (a: number, b: number) => [number, number];
    readonly encryptFileChaCha20: (a: number, b: number) => [number, number, number];
    readonly generateKeyPair: () => number;
    readonly groupDecrypt: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: bigint, j: bigint, k: bigint, l: number, m: number, n: bigint) => [number, number, number];
    readonly groupEncrypt: (a: number, b: number, c: number, d: number, e: bigint, f: bigint, g: bigint, h: number, i: number, j: bigint) => [number, number, number];
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
    readonly unwrapGroupKey: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number, i: bigint, j: bigint) => [number, number, number];
    readonly wrapGroupKeyForDevice: (a: number, b: number, c: number, d: number, e: bigint, f: bigint) => [number, number, number];
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
    readonly __wbindgen_exn_store: (a: number) => void;
    readonly __externref_table_alloc: () => number;
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __externref_table_dealloc: (a: number) => void;
    readonly __wbindgen_free: (a: number, b: number, c: number) => void;
    readonly __wbindgen_start: () => void;
}

export function penikCryptoVersion(): number;

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
