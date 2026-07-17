package handlers

import (
	"database/sql"
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

		now := time.Now().Unix()
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
				`INSERT OR IGNORE INTO one_time_prekeys(device_id, key_id, public_key, used, reserved_at, created_at)
				 VALUES(?, ?, ?, 0, NULL, ?)`,
				deviceID, keyID, pubKey, now)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
		}

		w.WriteHeader(http.StatusNoContent)
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
			_, err = tx.ExecContext(r.Context(), `DELETE FROM one_time_prekeys WHERE device_id=?`, deviceID)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
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
				_, err = tx.ExecContext(r.Context(),
					`INSERT OR IGNORE INTO one_time_prekeys(device_id, key_id, public_key, used, reserved_at, created_at)
					 VALUES(?, ?, ?, 0, NULL, ?)`,
					deviceID, keyID, pubKey, now)
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

type DeviceBundle struct {
	DeviceID    int64   `json:"device_id"`
	IdentityKey []byte  `json:"identity_key"`
	OneTimeKey  []byte  `json:"one_time_key"`
	KeyID       *int64  `json:"key_id"`
}

type KeyBundleResponse struct {
	Devices []DeviceBundle `json:"devices"`
}

// GetKeyBundle handles GET /api/v1/keys/bundle/{user_id}.
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

			var keyID int64
			var opkPub []byte
			var reservedKeyID *int64

			err = tx.QueryRowContext(r.Context(),
				`SELECT key_id, public_key FROM one_time_prekeys
				 WHERE device_id=? AND used=0
				 ORDER BY id LIMIT 1`,
				deviceID).Scan(&keyID, &opkPub)

			if err == nil {
				now := time.Now().Unix()
				_, err = tx.ExecContext(r.Context(),
					`UPDATE one_time_prekeys SET used=1, reserved_at=?
					 WHERE device_id=? AND key_id=?`,
					now, deviceID, keyID)
				if err != nil {
					http.Error(w, "internal error", http.StatusInternalServerError)
					return
				}
				reservedKeyID = &keyID
			} else if err != sql.ErrNoRows {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}

			devices = append(devices, DeviceBundle{
				DeviceID:    deviceID,
				IdentityKey: x25519Pub,
				OneTimeKey:  opkPub,
				KeyID:       reservedKeyID,
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

type prekeyUploadItem struct {
	KeyID     int64  `json:"key_id"`
	PublicKey []byte `json:"public_key"`
}

type prekeyUploadRequest struct {
	Prekeys []prekeyUploadItem `json:"prekeys"`
}

// UploadPreKeys handles POST /api/v1/keys/prekeys.
func UploadPreKeys(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := middleware.DeviceIDFromCtx(r.Context())
		if deviceID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		var req prekeyUploadRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}

		if len(req.Prekeys) == 0 {
			http.Error(w, "prekeys required", http.StatusBadRequest)
			return
		}

		now := time.Now().Unix()
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		for _, pk := range req.Prekeys {
			if len(pk.PublicKey) != 32 {
				http.Error(w, "invalid key length", http.StatusBadRequest)
				return
			}
			_, err = tx.ExecContext(r.Context(),
				`INSERT OR REPLACE INTO one_time_prekeys(device_id, key_id, public_key, used, reserved_at, created_at)
				 VALUES(?, ?, ?, 0, NULL, ?)`,
				deviceID, pk.KeyID, pk.PublicKey, now)
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

type PreKeysStatusResponse struct {
	Available int `json:"available"`
	Total     int `json:"total"`
}

// GetPreKeysStatus handles GET /api/v1/keys/prekeys/status.
func GetPreKeysStatus(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := middleware.DeviceIDFromCtx(r.Context())
		if deviceID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		var available int
		err := database.QueryRowContext(r.Context(),
			`SELECT COUNT(*) FROM one_time_prekeys WHERE device_id=? AND used=0`, deviceID).Scan(&available)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		var total int
		err = database.QueryRowContext(r.Context(),
			`SELECT COUNT(*) FROM one_time_prekeys WHERE device_id=?`, deviceID).Scan(&total)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(PreKeysStatusResponse{
			Available: available,
			Total:     total,
		})
	}
}
