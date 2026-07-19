package handlers

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

const pairingLifetime = 5 * time.Minute

type pairingCreateRequest struct {
	EphemeralPublicKey string `json:"ephemeral_public_key"`
	EncryptedHistory   string `json:"encrypted_history,omitempty"`
}
type pairingClaimRequest struct {
	SessionID string `json:"session_id"`
	Token     string `json:"token"`
	PublicKey string `json:"public_key"`
}

type pairingClaimedNotification struct {
	SessionID string `msgpack:"session_id"`
	PublicKey string `msgpack:"public_key"`
}

func randomPairingValue(n int) ([]byte, error) {
	b := make([]byte, n)
	_, err := rand.Read(b)
	return b, err
}
func hashPairingToken(v string) []byte { h := sha256.Sum256([]byte(v)); return h[:] }

// CreatePairingSession creates a short-lived, one-use rendezvous. The QR only
// carries the opaque token and public key; it never carries keys or plaintext.
func CreatePairingSession(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var req pairingCreateRequest
		if json.NewDecoder(r.Body).Decode(&req) != nil || req.EphemeralPublicKey == "" {
			http.Error(w, "ephemeral_public_key required", 400)
			return
		}
		pub, err := base64.RawURLEncoding.DecodeString(req.EphemeralPublicKey)
		if err != nil || len(pub) < 16 || len(pub) > 128 {
			http.Error(w, "invalid public key", 400)
			return
		}
		rawID, err := randomPairingValue(16)
		if err != nil {
			http.Error(w, "internal error", 500)
			return
		}
		rawToken, err := randomPairingValue(32)
		if err != nil {
			http.Error(w, "internal error", 500)
			return
		}
		id := base64.RawURLEncoding.EncodeToString(rawID)
		token := base64.RawURLEncoding.EncodeToString(rawToken)
		now, exp := time.Now().Unix(), time.Now().Add(pairingLifetime).Unix()
		var history []byte
		if req.EncryptedHistory != "" {
			history, err = base64.RawURLEncoding.DecodeString(req.EncryptedHistory)
			if err != nil || len(history) > 2<<20 {
				http.Error(w, "invalid encrypted history", 400)
				return
			}
		}
		_, err = database.ExecContext(r.Context(), `INSERT INTO pairing_sessions(id,owner_user_id,owner_device_id,ephemeral_public_key,encrypted_history,expires_at,created_at) VALUES(?,?,?,?,?,?,?)`, id, middleware.UserIDFromCtx(r.Context()), middleware.DeviceIDFromCtx(r.Context()), pub, history, exp, now)
		if err != nil {
			http.Error(w, "internal error", 500)
			return
		}
		_, err = database.ExecContext(r.Context(), `INSERT INTO pairing_tokens(session_id,token_hash) VALUES(?,?)`, id, hashPairingToken(token))
		if err != nil {
			http.Error(w, "internal error", 500)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"session_id": id, "token": token, "ephemeral_public_key": req.EphemeralPublicKey, "expires_at": exp})
	}
}

func ClaimPairingSession(database *db.DB, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var req pairingClaimRequest
		if json.NewDecoder(r.Body).Decode(&req) != nil || req.SessionID == "" || req.Token == "" {
			http.Error(w, "session_id and token required", 400)
			return
		}
		now := time.Now().Unix()
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", 500)
			return
		}
		defer tx.Rollback()
		var owner, ownerDevice, exp, claimed int64
		var pub, history []byte
		err = tx.QueryRowContext(r.Context(), `SELECT owner_user_id,owner_device_id,ephemeral_public_key,encrypted_history,expires_at,COALESCE(claimed_at,0) FROM pairing_sessions WHERE id=?`, req.SessionID).Scan(&owner, &ownerDevice, &pub, &history, &exp, &claimed)
		if err == sql.ErrNoRows {
			http.Error(w, "pairing session not found", 404)
			return
		}
		if err != nil {
			http.Error(w, "internal error", 500)
			return
		}
		if exp <= now {
			http.Error(w, "pairing session expired or already claimed", 410)
			return
		}
		// Claim is idempotent for the same authenticated device and public key.
		// Mobile clients may retry the request after a network timeout.
		if claimed != 0 {
			var claimedByDevice int64
			var claimedByPub []byte
			err = tx.QueryRowContext(r.Context(), `SELECT COALESCE(claimed_by_device_id,0),COALESCE(claimed_by_public_key,X'') FROM pairing_sessions WHERE id=?`, req.SessionID).Scan(&claimedByDevice, &claimedByPub)
			if err != nil {
				log.Printf("pairing claim state failed: session=%q err=%v", req.SessionID, err)
				http.Error(w, "internal error", 500)
				return
			}
			claimPub, decErr := base64.RawURLEncoding.DecodeString(req.PublicKey)
			if claimedByDevice != middleware.DeviceIDFromCtx(r.Context()) || decErr != nil || subtle.ConstantTimeCompare(claimedByPub, claimPub) != 1 {
				http.Error(w, "pairing session expired or already claimed", 410)
				return
			}
			json.NewEncoder(w).Encode(map[string]any{"session_id": req.SessionID, "ephemeral_public_key": base64.RawURLEncoding.EncodeToString(pub), "encrypted_history": base64.RawURLEncoding.EncodeToString(history), "owner_user_id": owner, "expires_at": exp})
			return
		}
		// Compare the hash in constant time through SQLite blob equality; token is never stored.
		_ = owner
		_ = ownerDevice
		// Token hash is encoded in the session id suffix-independent companion row.
		var expected []byte
		err = tx.QueryRowContext(r.Context(), `SELECT token_hash FROM pairing_tokens WHERE session_id=?`, req.SessionID).Scan(&expected)
		if err != nil || len(expected) != 32 || subtle.ConstantTimeCompare(expected, hashPairingToken(req.Token)) != 1 {
			http.Error(w, "invalid pairing token", 403)
			return
		}
		claimPub, decErr := base64.RawURLEncoding.DecodeString(req.PublicKey)
		if decErr != nil || len(claimPub) != 32 {
			http.Error(w, "public_key required", 400)
			return
		}
		_, err = tx.ExecContext(r.Context(), `UPDATE pairing_sessions SET claimed_at=?,claimed_by_device_id=?,claimed_by_public_key=? WHERE id=? AND claimed_at IS NULL AND expires_at>?`, now, middleware.DeviceIDFromCtx(r.Context()), claimPub, req.SessionID, now)
		if err != nil {
			log.Printf("pairing claim update failed: session=%q err=%v", req.SessionID, err)
			http.Error(w, "internal error", 500)
			return
		}
		if tx.Commit() != nil {
			http.Error(w, "internal error", 500)
			return
		}
		if payload, marshalErr := msgpack.Marshal(pairingClaimedNotification{SessionID: req.SessionID, PublicKey: req.PublicKey}); marshalErr == nil {
			if hub != nil {
				hub.SendToDeviceFrame(ownerDevice, ws.OpPairingClaimed, payload)
			}
		}
		json.NewEncoder(w).Encode(map[string]any{"session_id": req.SessionID, "ephemeral_public_key": base64.RawURLEncoding.EncodeToString(pub), "encrypted_history": base64.RawURLEncoding.EncodeToString(history), "owner_user_id": owner, "expires_at": exp})
	}
}

// GetPairingClaim exposes only the newly paired device public key to the web owner.
func GetPairingClaim(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		id := r.PathValue("id")
		var pub []byte
		var claimed, exp int64
		var history []byte
		err := database.QueryRowContext(r.Context(), `SELECT claimed_at,expires_at,claimed_by_public_key,encrypted_history FROM pairing_sessions WHERE id=? AND owner_user_id=?`, id, middleware.UserIDFromCtx(r.Context())).Scan(&claimed, &exp, &pub, &history)
		if err == sql.ErrNoRows {
			http.Error(w, "not found", 404)
			return
		}
		if err != nil {
			http.Error(w, "internal error", 500)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"claimed": claimed != 0, "expires_at": exp, "public_key": base64.RawURLEncoding.EncodeToString(pub), "encrypted_history": base64.RawURLEncoding.EncodeToString(history)})
	}
}

func UploadPairingHistory(database *db.DB, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var req struct {
			EncryptedHistory string `json:"encrypted_history"`
		}
		if json.NewDecoder(r.Body).Decode(&req) != nil {
			http.Error(w, "invalid body", 400)
			return
		}
		b, err := base64.RawURLEncoding.DecodeString(req.EncryptedHistory)
		if err != nil || len(b) > 2<<20 {
			http.Error(w, "invalid encrypted history", 400)
			return
		}
		id := r.PathValue("id")
		res, err := database.ExecContext(r.Context(), `UPDATE pairing_sessions SET encrypted_history=? WHERE id=? AND owner_user_id=? AND claimed_at IS NOT NULL AND expires_at>?`, b, id, middleware.UserIDFromCtx(r.Context()), time.Now().Unix())
		if err != nil {
			http.Error(w, "internal error", 500)
			return
		}
		n, _ := res.RowsAffected()
		if n != 1 {
			http.Error(w, "not available", 409)
			return
		}
		var deviceID int64
		if err := database.QueryRowContext(r.Context(), `SELECT claimed_by_device_id FROM pairing_sessions WHERE id=?`, id).Scan(&deviceID); err == nil {
			if payload, err := msgpack.Marshal(ws.PairingHistoryReady{SessionID: id}); err == nil {
				hub.SendToDeviceFrame(deviceID, ws.OpPairingHistoryReady, payload)
			}
		}
		w.WriteHeader(204)
	}
}
