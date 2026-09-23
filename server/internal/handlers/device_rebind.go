package handlers

import (
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"

	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

type deviceChallengeRequest struct {
	TargetDeviceID int64 `json:"target_device_id"`
}

type deviceChallengeResponse struct {
	Nonce     string `json:"nonce"`
	EphPub    string `json:"eph_pub"`
	ExpiresAt int64  `json:"expires_at"`
}

type deviceRebindRequest struct {
	DeviceID int64  `json:"device_id"`
	Nonce    string `json:"nonce"`
	Proof    string `json:"proof"`
}

type deviceRebindResponse struct {
	Success  bool  `json:"success"`
	DeviceID int64 `json:"device_id"`
}

func decodeBase64Flexible(s string) ([]byte, error) {
	clean := strings.TrimSpace(s)
	if b, err := base64.StdEncoding.DecodeString(clean); err == nil {
		return b, nil
	}
	if b, err := base64.RawStdEncoding.DecodeString(clean); err == nil {
		return b, nil
	}
	if b, err := base64.URLEncoding.DecodeString(clean); err == nil {
		return b, nil
	}
	return base64.RawURLEncoding.DecodeString(clean)
}

// DeviceChallenge handles POST /api/v1/auth/device-challenge.
func DeviceChallenge(database *db.DB, cfg *config.Config, store *DeviceChallengeStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		var req deviceChallengeRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}

		if req.TargetDeviceID <= 0 {
			http.Error(w, "invalid target_device_id", http.StatusBadRequest)
			return
		}

		// Verify target device belongs to the authenticated user
		var exists int
		err := database.QueryRowContext(r.Context(),
			`SELECT 1 FROM devices WHERE id = ? AND user_id = ?`,
			req.TargetDeviceID, userID).Scan(&exists)
		if err != nil {
			if err == sql.ErrNoRows {
				http.Error(w, "device not found", http.StatusNotFound)
			} else {
				http.Error(w, "internal error", http.StatusInternalServerError)
			}
			return
		}

		// Verify target device has public key registered
		var pubKey []byte
		err = database.QueryRowContext(r.Context(),
			`SELECT x25519_pub FROM device_public_keys WHERE device_id = ?`,
			req.TargetDeviceID).Scan(&pubKey)
		if err != nil || len(pubKey) < 32 {
			http.Error(w, "target device has no identity key", http.StatusBadRequest)
			return
		}

		nonce, ephPub, expiresAt, err := store.CreateChallenge(userID, req.TargetDeviceID)
		if err != nil {
			log.Printf("device challenge: failed to generate challenge: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(deviceChallengeResponse{
			Nonce:     base64.StdEncoding.EncodeToString(nonce),
			EphPub:    base64.StdEncoding.EncodeToString(ephPub),
			ExpiresAt: expiresAt.Unix(),
		})
	}
}

// DeviceRebind handles POST /api/v1/auth/device-rebind.
func DeviceRebind(database *db.DB, cfg *config.Config, store *DeviceChallengeStore, hubs ...*ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		tempDeviceID := middleware.DeviceIDFromCtx(r.Context())
		token := middleware.TokenFromCtx(r.Context())
		if userID == 0 || token == "" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		var req deviceRebindRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}

		if req.DeviceID <= 0 || req.Nonce == "" || req.Proof == "" {
			http.Error(w, "missing required fields", http.StatusBadRequest)
			return
		}

		nonceBytes, err := decodeBase64Flexible(req.Nonce)
		if err != nil || len(nonceBytes) != 32 {
			http.Error(w, "invalid nonce", http.StatusBadRequest)
			return
		}

		proofBytes, err := decodeBase64Flexible(req.Proof)
		if err != nil || len(proofBytes) != 32 {
			http.Error(w, "invalid proof", http.StatusBadRequest)
			return
		}

		// Single-use challenge: atomically pop and delete challenge immediately
		challenge, ok := store.ConsumeChallenge(nonceBytes)
		if !ok {
			http.Error(w, `{"error":"invalid_or_expired_challenge"}`, http.StatusForbidden)
			return
		}

		if challenge.UserID != userID || challenge.TargetDeviceID != req.DeviceID {
			http.Error(w, `{"error":"challenge_mismatch"}`, http.StatusForbidden)
			return
		}

		// Load target device public key
		var targetPubBytes []byte
		err = database.QueryRowContext(r.Context(),
			`SELECT x25519_pub FROM device_public_keys WHERE device_id = ?`,
			req.DeviceID).Scan(&targetPubBytes)
		if err != nil || len(targetPubBytes) < 32 {
			http.Error(w, "target device key not found", http.StatusNotFound)
			return
		}

		// Normalize to 32 bytes (strip optional 0x05 prefix if 33 bytes)
		var targetPub [32]byte
		copy(targetPub[:], targetPubBytes[len(targetPubBytes)-32:])

		// Check non-contributory point and compute shared secret
		shared, err := curve25519.X25519(challenge.EphPriv[:], targetPub[:])
		if err != nil {
			http.Error(w, `{"error":"invalid_key"}`, http.StatusForbidden)
			return
		}
		var allZeros [32]byte
		if subtle.ConstantTimeCompare(shared, allZeros[:]) == 1 {
			http.Error(w, `{"error":"non_contributory_key"}`, http.StatusForbidden)
			return
		}

		// Compute expected proof via HKDF-SHA256 with 70-byte deterministic wire framing
		info := make([]byte, 70)
		copy(info[0:22], []byte("penik-device-rebind-v1"))
		copy(info[22:54], challenge.Nonce[:])
		binary.BigEndian.PutUint64(info[54:62], uint64(userID))
		binary.BigEndian.PutUint64(info[62:70], uint64(req.DeviceID))

		hkdfReader := hkdf.New(sha256.New, shared, nil, info)
		var expectedProof [32]byte
		if _, err := io.ReadFull(hkdfReader, expectedProof[:]); err != nil {
			log.Printf("device rebind: hkdf error: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if subtle.ConstantTimeCompare(proofBytes, expectedProof[:]) != 1 {
			http.Error(w, `{"error":"invalid_proof"}`, http.StatusForbidden)
			return
		}

		// Proof is valid! Atomically upgrade session to target device and clean up provisional device
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		tokenHash := db.HashSessionToken(token)
		_, err = tx.ExecContext(r.Context(),
			`UPDATE sessions SET device_id = ? WHERE token = ?`,
			req.DeviceID, tokenHash)
		if err != nil {
			log.Printf("device rebind: update session failed: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		// If current session was on a provisional device, delete it
		if tempDeviceID != 0 && tempDeviceID != req.DeviceID {
			_, _ = tx.ExecContext(r.Context(), `DELETE FROM device_public_keys WHERE device_id = ?`, tempDeviceID)
			_, _ = tx.ExecContext(r.Context(), `DELETE FROM identity_keys WHERE device_id = ?`, tempDeviceID)
			_, _ = tx.ExecContext(r.Context(), `DELETE FROM devices WHERE id = ? AND user_id = ?`, tempDeviceID, userID)
		}

		now := time.Now().Unix()
		_, _ = tx.ExecContext(r.Context(),
			`UPDATE devices SET last_seen = ? WHERE id = ?`,
			now, req.DeviceID)

		if err := tx.Commit(); err != nil {
			log.Printf("device rebind: commit error: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		// Drop stale sockets for target device and notify device change
		if len(hubs) > 0 && hubs[0] != nil {
			hubs[0].CloseDeviceConnections(req.DeviceID)
			go hubs[0].NotifyUserDevicesChanged(r.Context(), database, userID)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(deviceRebindResponse{
			Success:  true,
			DeviceID: req.DeviceID,
		})
	}
}
