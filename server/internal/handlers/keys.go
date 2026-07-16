package handlers

import (
	"encoding/binary"
	"encoding/json"
	"net/http"
	"time"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

type otkUploadRequest struct {
	OPKList [][]byte `json:"opk_list"`
}

// UploadOTK handles POST /api/v1/keys/otk — upload new one-time pre-keys.
func UploadOTK(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := middleware.DeviceIDFromCtx(r.Context())

		var req otkUploadRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		if len(req.OPKList) == 0 {
			http.Error(w, "opk_list required", http.StatusBadRequest)
			return
		}
		if len(req.OPKList) > maxOPKUpload {
			http.Error(w, "too many one-time keys", http.StatusBadRequest)
			return
		}

		for _, opkRaw := range req.OPKList {
			var keyID int64
			var pubKey []byte
			if len(opkRaw) == 37 {
				keyID = int64(binary.BigEndian.Uint32(opkRaw[:4]))
				pubKey = opkRaw[4:]
			} else {
				pubKey = opkRaw
			}
			_, err := database.ExecContext(r.Context(),
				`INSERT OR IGNORE INTO one_time_keys(device_id,key_id,opk_pub,used) VALUES(?,?,?,0)`,
				deviceID, keyID, pubKey)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
		}

		w.WriteHeader(http.StatusNoContent)
	}
}

type keyBackupRequest struct {
	EncryptedBlob []byte `json:"encrypted_blob"`
	KDFSalt       []byte `json:"kdf_salt"`
}

type keyBackupResponse struct {
	DeviceID      int64  `json:"device_id"`
	EncryptedBlob []byte `json:"encrypted_blob"`
	KDFSalt       []byte `json:"kdf_salt"`
	CreatedAt     int64  `json:"created_at"`
}

// UploadKeyBackup handles POST /api/v1/keys/backup.
func UploadKeyBackup(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())

		var req keyBackupRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		if len(req.EncryptedBlob) == 0 || len(req.KDFSalt) == 0 {
			http.Error(w, "encrypted_blob and kdf_salt required", http.StatusBadRequest)
			return
		}

		now := time.Now().Unix()
		_, err := database.ExecContext(r.Context(),
			`INSERT OR REPLACE INTO key_backups(user_id,encrypted_blob,kdf_salt,created_at)
			 VALUES(?,?,?,?)`,
			userID, req.EncryptedBlob, req.KDFSalt, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}

// GetKeyBackup handles GET /api/v1/keys/backup.
func GetKeyBackup(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())

		var resp keyBackupResponse
		err := database.QueryRowContext(r.Context(),
			`SELECT encrypted_blob, kdf_salt, created_at 
			 FROM key_backups 
			 WHERE user_id=?`,
			userID).Scan(&resp.EncryptedBlob, &resp.KDFSalt, &resp.CreatedAt)
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	}
}

type keysInitRequest struct {
	IKPub          []byte   `json:"ik_pub"`
	SPKPub         []byte   `json:"spk_pub"`
	SPKSig         []byte   `json:"spk_sig"`
	RegistrationID int64    `json:"registration_id"`
	OPKList        [][]byte `json:"opk_list"`
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

		if len(req.OPKList) > 0 {
			for _, opkRaw := range req.OPKList {
				var keyID int64
				var pubKey []byte
				if len(opkRaw) == 37 {
					keyID = int64(binary.BigEndian.Uint32(opkRaw[:4]))
					pubKey = opkRaw[4:]
				} else {
					pubKey = opkRaw
				}
				_, err = tx.ExecContext(r.Context(),
					`INSERT OR IGNORE INTO one_time_keys(device_id,key_id,opk_pub,used) VALUES(?,?,?,0)`,
					deviceID, keyID, pubKey)
				if err != nil {
					http.Error(w, "internal error", http.StatusInternalServerError)
					return
				}
			}
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}
