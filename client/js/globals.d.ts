// Ambient declarations for globals the app attaches to `window` at runtime.
// Type-checking only — this file produces no JavaScript.

interface Window {
  // Maps an attachment URL to an object URL for its decrypted blob. The service
  // worker asks the page for these over MessageChannel to serve range requests;
  // it never sees ciphertext or keys.
  _streamMediaCache?: Map<string, string>;
}
