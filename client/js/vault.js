// Local secret vault.
//
// The private Identity Key, the group message keys and the session bearer token
// all used to sit in IndexedDB as raw bytes, so anything able to read the origin's
// database — a stolen browser profile, a devtools export, a shared machine — got
// usable key material by copying it out.
//
// Every such value is now sealed with an AES-GCM key that is generated inside the
// browser as `extractable: false` and stored as a live CryptoKey handle. The raw
// wrapping key never has a JavaScript representation: WebCrypto refuses to export
// it, so a database dump yields ciphertext that cannot be opened anywhere else.
//
// This does not stop an XSS that stays in the page and awaits `openBytes` — no
// client-side storage can, short of a passphrase prompt on every read. What it
// removes is the offline copy: the attacker must keep executing in this origin
// instead of walking away with the keys.

const VAULT_KEY_ID = "vault_wrapping_key";
const SEALED_VERSION = 1;

let _pending = null;

// getWrappingKey returns the origin's non-extractable AES-GCM key, creating and
// persisting it on first use. Concurrent callers share one in-flight promise so a
// cold start cannot generate two competing keys.
export function getWrappingKey(store) {
  if (!_pending) {
    _pending = load(store).catch((err) => {
      _pending = null;
      throw err;
    });
  }
  return _pending;
}

async function load(store) {
  const existing = await store.read(VAULT_KEY_ID);
  if (existing && existing.key instanceof CryptoKey) return existing.key;

  const key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, [
    "encrypt",
    "decrypt",
  ]);
  await store.write({ id: VAULT_KEY_ID, key });
  return key;
}

// resetWrappingKey drops the cached handle; used by logout, which clears the
// whole database underneath us.
export function resetWrappingKey() {
  _pending = null;
}

/**
 * Normalizes binary input to a view WebCrypto accepts. The declared buffer type
 * matters: `BufferSource` excludes views over a SharedArrayBuffer, so one is
 * copied into a private buffer instead of being handed over as-is.
 * @param {unknown} value
 * @returns {Uint8Array<ArrayBuffer> | null}
 */
function toBytes(value) {
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (!ArrayBuffer.isView(value)) return null;
  const view = value instanceof Uint8Array
    ? value
    : new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  return view.buffer instanceof ArrayBuffer
    ? /** @type {Uint8Array<ArrayBuffer>} */ (view)
    : new Uint8Array(view);
}

// sealBytes wraps raw key material. The record is self-describing so a future
// format change can be detected instead of guessed.
export async function sealBytes(store, bytes) {
  const plain = toBytes(bytes);
  if (!plain) throw new Error("vault: sealBytes expects binary input");
  const key = await getWrappingKey(store);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plain);
  return { v: SEALED_VERSION, iv, ct: new Uint8Array(ct) };
}

// openBytes reverses sealBytes. It returns null rather than throwing when the
// record is missing or unreadable: a caller that cannot decrypt a group key must
// fall back to re-fetching the envelope, not crash the chat.
export async function openBytes(store, sealed) {
  if (!sealed || sealed.v !== SEALED_VERSION) return null;
  const iv = toBytes(sealed.iv);
  const ct = toBytes(sealed.ct);
  if (!iv || !ct) return null;
  try {
    const key = await getWrappingKey(store);
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, ct);
    return new Uint8Array(plain);
  } catch {
    return null;
  }
}

export async function sealString(store, text) {
  return sealBytes(store, new TextEncoder().encode(String(text)));
}

export async function openString(store, sealed) {
  const bytes = await openBytes(store, sealed);
  return bytes ? new TextDecoder().decode(bytes) : null;
}

// isSealed distinguishes a wrapped record from a legacy plaintext one, so reads
// can migrate old values in place instead of losing the user's session.
export function isSealed(value) {
  return !!value && typeof value === "object" && value.v === SEALED_VERSION && "ct" in value;
}
