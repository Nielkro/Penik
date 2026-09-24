# Plan note: delete E2EE? — lose vs gain

**Status: active** (reference decision note)

## Lose

- Confidentiality from server/host: today history is ciphertext; without E2EE
  every message and attachment is plaintext on disk and in backups.
- Product position: Penik is built around E2EE (Rust core, WASM, JNI, safety
  numbers, key backup, group epochs, call insertable streams).
- Attachment encryption before upload.
- Safety numbers / verified chats (no object left to verify).
- Group epoch keys / pairwise envelopes become meaningless (server-side keys
  = server can read everything).
- Call E2EE layer (if also removed).

## Gain

- Entire bug class disappears: AAD/timestamp skew, own vs peer IK resolve,
  history decrypt failures, fan-out collapse, TOFU pin mismatches.
- No E2EE passphrase / mnemonic / key backup UX on login.
- No IK rotation / pinning / device public-key overwrite concerns.
- Idea A rebind becomes unnecessary for *content* (device_id still needed if
  history stays device-scoped).
- Smaller client surface (less WASM/JNI wiring, simpler first-run).

## Do NOT gain

- Does not fix password theft, session theft (R1), spam, or "wrote as you".
- E2EE never protected against stolen account password — only against a
  malicious/compromised server reading content.
- Removing it is a downgrade to "TLS + trust the operator".

## Effort

1. Stop encrypt/decrypt on send/history/WS; store plaintext (or drop crypto fields).
2. Strip pin/backup/safety UI — several days of UI work.
3. Groups, attachments, calls each need their own path (attachments especially).
4. Old ciphertext rows: keep decrypt-only legacy (hybrid) or leave unreadable.
5. Rust/WASM/JNI can stay in repo unused.

## Recommendation

- **Prod:** do not delete E2EE; fix device trust (Idea A) instead.
- **Optional:** feature flag `E2EE_ENABLED=0` on fork only for experiments.
- If goal is "fewer bugs, faster iteration" without weakening prod threat
  model: ship Idea A + history watermark fixes (already done), not content decrypt removal.
