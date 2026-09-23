# Plan: session rebind via IK proof (Idea A)

## Problem

Login binds `sessions.device_id` from public `ik_pub` / `device_name` with no
proof of private-key possession. Any session that knows the account password
plus a public IK (both obtainable via `/keys/bundle` after a normal login)
can become device N and receive:

- device-scoped `GET /messages/history` (metadata always; content if IK or backup keys available)
- WS offline envelopes / group key envelopes for that device
- ability to overwrite `device_public_keys` via `keys/init` / `OpKeyPublish` (no verify today)

R4 only drops third-party `device_id` on send; it does not protect session actorship.

## Non-goals

- Not a fix for stolen bearer tokens (existing R1).
- Not protection of `/keys/backup` blobs (user-scoped; separate item).
- Not replacing E2EE content encryption.

## Design (A2 — X25519 DH-challenge on device IK)

### Endpoints

1. `POST /api/v1/auth/device-challenge`
   - Auth: `Bearer` (existing session)
   - Response: `{ nonce, eph_pub, expires_at }`
   - Nonce TTL ~60s, bound to `token_hash`, single-use

2. `POST /api/v1/auth/device-rebind`
   - Body: `{ device_id, nonce, proof }`
   - Client proof:

     ```
     shared = DH(IK_priv, eph_pub)
     proof  = HKDF-SHA256(
       ikm = shared,
       salt = "",
       info = "penik-device-rebind-v1" || nonce || user_id || device_id,
       length = 32
     )
     ```

   - Server: load `device_public_keys.x25519_pub` for `device_id`,
     `shared = DH(eph_priv, x25519_pub)`, constant-time compare,
     consume nonce once.
   - On success: issue new session row (or `UPDATE sessions SET device_id=?`),
     `hub.CloseSession(oldTokenHash)` so the old WS dies.

### Gates (required so proof is not bypassable)

| Surface | Rule |
|---|---|
| `POST /login` claim existing device via `ik_pub` | With `DEVICE_REBIND_REQUIRED=1`: `403 device_proof_required` unless rebind proof in same flow. Flag `0` keeps legacy path. |
| `POST /keys/init` | Reject `x25519_pub` change when stored key exists and differs, unless caller proved rebind. Allow first insert. |
| WS `OpKeyPublish` | Same as `keys/init`. |
| `device_name` login fallback | Under flag `1`: new device only, never silently claim another device row. |

### Server files

- `server/internal/handlers/auth.go` or new `device_rebind.go` — challenge + rebind
- `server/internal/handlers/keys.go` — gate `UploadIdentityKeys`
- `server/internal/ws/client.go` — gate `handleKeyPublish`
- `server/internal/db/schema.sql` — optional `device_challenges` table (or in-memory TTL store)
- X25519: `golang.org/x/crypto/curve25519` (x/crypto already in module tree)

### Client — web (wave 1)

- `client/js/api.js` — `deviceChallenge()`, `deviceRebind()`
- `client/js/crypto.js` — reuse `deriveSharedSecret`; HKDF via WebCrypto
- `client/js/ui/auth.js` — on `device_proof_required` auto rebind then continue
- `client/js/app.js` — `__penikRebindDevice(deviceId)` debug helper
- `client/js/globals.d.ts` — declare window hooks

### Android (wave 2)

- `AuthRepository.kt` — login/rebind path
- `WebSocketManager.kt` — gated key publish

### Tests

- Unit: valid proof, wrong IK, replay nonce, wrong device, flag off
- e2e: claim device without proof → 403; rebind → history correctly scoped
- Regression: existing R4 spoof tests still pass

### Rollout

1. Ship endpoints + gates behind `DEVICE_REBIND_REQUIRED=0`
2. Ship clients that auto-rebind on 403
3. Flip flag on fork (`127.0.0.1:8143`), verify
4. Flip on prod

### Docs

- `PROJECT_MAP.md`
- `SECURITY_AUDIT.md` (new row next to R4)
- This file under `plan/`
