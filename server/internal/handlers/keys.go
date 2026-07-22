package handlers

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"time"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)





type keysInitRequest struct {
	IKPub          []byte   `json:"ik_pub"`
	SPKPub         []byte   `json:"spk_pub"`
	SPKSig         []byte   `json:"spk_sig"`
	RegistrationID int64    `json:"registration_id"`
}

// UploadIdentityKeys handles POST /api/v1/keys/init — upload new identity key and signed pre-key.
func UploadIdentityKeys(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := middleware.DeviceIDFromCtx(r.Context())
		now := time.Now().Unix()

		var req keysInitRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		if len(req.IKPub) == 0 || len(req.SPKPub) == 0 || len(req.SPKSig) == 0 {
			http.Error(w, "missing fields", http.StatusBadRequest)
			return
		}
		if !validCurveKey(req.IKPub) || !validCurveKey(req.SPKPub) || len(req.SPKSig) != 64 {
			http.Error(w, "malformed identity key material", http.StatusBadRequest)
			return
		}

		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		_, err = tx.ExecContext(r.Context(),
			`INSERT OR REPLACE INTO identity_keys(device_id,ik_pub,spk_pub,spk_sig,updated_at) VALUES(?,?,?,?,?)`,
			deviceID, req.IKPub, req.SPKPub, req.SPKSig, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if req.RegistrationID > 0 {
			_, err = tx.ExecContext(r.Context(),
				`UPDATE devices SET registration_id=? WHERE id=?`,
				req.RegistrationID, deviceID)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}

type DeviceBundle struct {
	DeviceID    int64   `json:"device_id"`
	IdentityKey []byte  `json:"identity_key"`
}

type KeyBundleResponse struct {
	Devices []DeviceBundle `json:"devices"`
}

// GetKeyBundle handles GET /api/v1/keys/bundle/{user_id}.
// Pass ?skip_otk=true to skip one-time pre-key reservation (used for self-chat).
func GetKeyBundle(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userIDStr := r.PathValue("user_id")
		if userIDStr == "" {
			http.Error(w, "user_id required", http.StatusBadRequest)
			return
		}

		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		rows, err := tx.QueryContext(r.Context(), `SELECT id FROM devices WHERE user_id=?`, userIDStr)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		var devices []DeviceBundle
		for rows.Next() {
			var deviceID int64
			if err := rows.Scan(&deviceID); err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}

			var x25519Pub []byte
			err := tx.QueryRowContext(r.Context(), `SELECT x25519_pub FROM device_public_keys WHERE device_id=?`, deviceID).Scan(&x25519Pub)
			if err == sql.ErrNoRows {
				continue
			} else if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}

			devices = append(devices, DeviceBundle{
				DeviceID:    deviceID,
				IdentityKey: x25519Pub,
			})
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(KeyBundleResponse{Devices: devices})
	}
}

type KeyBackupRequest struct {
	EncryptedBlob []byte `json:"encrypted_blob"`
	Salt          []byte `json:"salt"`
	IV            []byte `json:"iv"`
}

type KeyBackupResponse struct {
	EncryptedBlob []byte `json:"encrypted_blob"`
	Salt          []byte `json:"salt"`
	IV            []byte `json:"iv"`
	CreatedAt     int64  `json:"created_at"`
}

// UploadKeyBackup handles POST /api/v1/keys/backup.
func UploadKeyBackup(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		var req KeyBackupRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}

		if len(req.EncryptedBlob) == 0 || len(req.Salt) == 0 || len(req.IV) == 0 {
			http.Error(w, "missing backup parameters", http.StatusBadRequest)
			return
		}

		now := time.Now().Unix()
		_, err := database.ExecContext(r.Context(),
			`INSERT INTO key_backups(user_id, encrypted_blob, salt, iv, created_at)
			 VALUES(?, ?, ?, ?, ?)
			 ON CONFLICT(user_id) DO UPDATE SET
				encrypted_blob=excluded.encrypted_blob,
				salt=excluded.salt,
				iv=excluded.iv,
				created_at=excluded.created_at`,
			userID, req.EncryptedBlob, req.Salt, req.IV, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}

// DownloadKeyBackup handles GET /api/v1/keys/backup.
func DownloadKeyBackup(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		var resp KeyBackupResponse
		err := database.QueryRowContext(r.Context(),
			`SELECT encrypted_blob, salt, iv, created_at FROM key_backups WHERE user_id=?`, userID).
			Scan(&resp.EncryptedBlob, &resp.Salt, &resp.IV, &resp.CreatedAt)
		if err == sql.ErrNoRows {
			http.Error(w, "backup not found", http.StatusNotFound)
			return
		} else if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	}
}

