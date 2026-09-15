package handlers

import (
	"crypto/md5"
	"database/sql"
	"encoding/json"
	"fmt"
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
	CryptoVersion  int      `json:"crypto_version"`
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
		if len(req.IKPub) == 0 {
			http.Error(w, "ik_pub required", http.StatusBadRequest)
			return
		}
		if !validCurveKey(req.IKPub) {
			http.Error(w, "malformed identity key material", http.StatusBadRequest)
			return
		}
		if len(req.SPKPub) > 0 || len(req.SPKSig) > 0 {
			if !validCurveKey(req.SPKPub) || len(req.SPKSig) != 64 {
				http.Error(w, "malformed identity key material", http.StatusBadRequest)
				return
			}
		}

		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		_, err = tx.ExecContext(r.Context(),
			`INSERT OR REPLACE INTO device_public_keys(device_id,x25519_pub,created_at,updated_at) VALUES(?,?,?,?)`,
			deviceID, req.IKPub, now, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if len(req.SPKSig) > 0 {
			_, err = tx.ExecContext(r.Context(),
				`INSERT OR REPLACE INTO identity_keys(device_id,ik_pub,spk_pub,spk_sig,updated_at) VALUES(?,?,?,?,?)`,
				deviceID, req.IKPub, req.SPKPub, req.SPKSig, now)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
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

		if req.CryptoVersion > 0 {
			_, err = tx.ExecContext(r.Context(),
				`UPDATE devices SET crypto_version=? WHERE id=?`,
				req.CryptoVersion, deviceID)
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
	DeviceID      int64   `json:"device_id"`
	IdentityKey   []byte  `json:"identity_key"`
	CryptoVersion int     `json:"crypto_version"`
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

		rows, err := tx.QueryContext(r.Context(), `SELECT id, crypto_version FROM devices WHERE user_id=?`, userIDStr)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		var devices []DeviceBundle
		for rows.Next() {
			var deviceID int64
			var cryptoVersion int
			if err := rows.Scan(&deviceID, &cryptoVersion); err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			if cryptoVersion <= 0 {
				cryptoVersion = 1
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
				DeviceID:      deviceID,
				IdentityKey:   x25519Pub,
				CryptoVersion: cryptoVersion,
			})
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		resp := KeyBundleResponse{Devices: devices}
		bodyBytes, err := json.Marshal(resp)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		etag := fmt.Sprintf(`"%x"`, md5.Sum(bodyBytes))
		w.Header().Set("ETag", etag)
		w.Header().Set("Cache-Control", "private, no-cache")

		if r.Header.Get("If-None-Match") == etag {
			w.WriteHeader(http.StatusNotModified)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write(bodyBytes)
	}
}

type KeyBackupRequest struct {
	EncryptedBlob []byte `json:"encrypted_blob"`
	Salt          []byte `json:"salt"`
	IV            []byte `json:"iv"`
	DeviceName    string `json:"device_name,omitempty"`
	Platform      string `json:"platform,omitempty"`
}

type KeyBackupSummary struct {
	ID         int64  `json:"id"`
	DeviceID   *int64 `json:"device_id,omitempty"`
	DeviceName string `json:"device_name"`
	Platform   string `json:"platform"`
	CreatedAt  int64  `json:"created_at"`
	UpdatedAt  int64  `json:"updated_at"`
}

type KeyBackupResponse struct {
	ID            int64  `json:"id,omitempty"`
	DeviceID      *int64 `json:"device_id,omitempty"`
	DeviceName    string `json:"device_name,omitempty"`
	Platform      string `json:"platform,omitempty"`
	EncryptedBlob []byte `json:"encrypted_blob"`
	Salt          []byte `json:"salt"`
	IV            []byte `json:"iv"`
	CreatedAt     int64  `json:"created_at"`
	UpdatedAt     int64  `json:"updated_at,omitempty"`
}

// UploadKeyBackup handles POST /api/v1/keys/backup.
func UploadKeyBackup(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		deviceID := middleware.DeviceIDFromCtx(r.Context())
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

		deviceName := sanitizeDeviceField(req.DeviceName, maxDeviceFieldRunes)
		platform := sanitizeDeviceField(req.Platform, maxDeviceFieldRunes)

		// If device metadata was not provided in the request body, look up from devices table
		if deviceID > 0 && (deviceName == "" || platform == "") {
			var dName, dPlat string
			_ = database.QueryRowContext(r.Context(),
				`SELECT device_name, platform FROM devices WHERE id = ? AND user_id = ?`,
				deviceID, userID).Scan(&dName, &dPlat)
			if deviceName == "" {
				deviceName = dName
			}
			if platform == "" {
				platform = dPlat
			}
		}
		if deviceName == "" {
			deviceName = "Устройство"
		}
		if platform == "" {
			platform = resolvePlatform("", r)
		}

		now := time.Now().Unix()
		var err error
		if deviceID > 0 {
			_, err = database.ExecContext(r.Context(),
				`INSERT INTO key_backups(user_id, device_id, device_name, platform, encrypted_blob, salt, iv, created_at, updated_at)
				 VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
				 ON CONFLICT(user_id, device_id) DO UPDATE SET
					device_name=excluded.device_name,
					platform=excluded.platform,
					encrypted_blob=excluded.encrypted_blob,
					salt=excluded.salt,
					iv=excluded.iv,
					updated_at=excluded.updated_at`,
				userID, deviceID, deviceName, platform, req.EncryptedBlob, req.Salt, req.IV, now, now)
		} else {
			_, err = database.ExecContext(r.Context(),
				`INSERT INTO key_backups(user_id, device_id, device_name, platform, encrypted_blob, salt, iv, created_at, updated_at)
				 VALUES(?, NULL, ?, ?, ?, ?, ?, ?, ?)`,
				userID, deviceName, platform, req.EncryptedBlob, req.Salt, req.IV, now, now)
		}
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}

// ListKeyBackups handles GET /api/v1/keys/backups.
func ListKeyBackups(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		rows, err := database.QueryContext(r.Context(),
			`SELECT id, device_id, device_name, platform, created_at, updated_at
			   FROM key_backups
			  WHERE user_id = ?
			  ORDER BY updated_at DESC, id DESC`, userID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		list := make([]KeyBackupSummary, 0)
		for rows.Next() {
			var item KeyBackupSummary
			if err := rows.Scan(&item.ID, &item.DeviceID, &item.DeviceName, &item.Platform, &item.CreatedAt, &item.UpdatedAt); err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			list = append(list, item)
		}
		if err := rows.Err(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(list)
	}
}

// DownloadKeyBackup handles GET /api/v1/keys/backup.
// Supports optional query parameters: ?id=123 or ?device_id=456.
func DownloadKeyBackup(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		idParam := r.URL.Query().Get("id")
		deviceIDParam := r.URL.Query().Get("device_id")

		var query string
		var args []any

		if idParam != "" {
			query = `SELECT id, device_id, device_name, platform, encrypted_blob, salt, iv, created_at, updated_at
			           FROM key_backups WHERE user_id = ? AND id = ?`
			args = []any{userID, idParam}
		} else if deviceIDParam != "" {
			query = `SELECT id, device_id, device_name, platform, encrypted_blob, salt, iv, created_at, updated_at
			           FROM key_backups WHERE user_id = ? AND device_id = ?`
			args = []any{userID, deviceIDParam}
		} else {
			query = `SELECT id, device_id, device_name, platform, encrypted_blob, salt, iv, created_at, updated_at
			           FROM key_backups WHERE user_id = ?
			          ORDER BY updated_at DESC, id DESC LIMIT 1`
			args = []any{userID}
		}

		var resp KeyBackupResponse
		err := database.QueryRowContext(r.Context(), query, args...).
			Scan(&resp.ID, &resp.DeviceID, &resp.DeviceName, &resp.Platform, &resp.EncryptedBlob, &resp.Salt, &resp.IV, &resp.CreatedAt, &resp.UpdatedAt)
		if err == sql.ErrNoRows {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(map[string]string{
				"error": "backup_not_found",
			})
			return
		} else if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	}
}

// DeleteKeyBackup handles DELETE /api/v1/keys/backups/{id}.
func DeleteKeyBackup(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		id := r.PathValue("id")
		if id == "" {
			http.Error(w, "id required", http.StatusBadRequest)
			return
		}

		res, err := database.ExecContext(r.Context(),
			`DELETE FROM key_backups WHERE id = ? AND user_id = ?`, id, userID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		rowsAffected, _ := res.RowsAffected()
		if rowsAffected == 0 {
			http.Error(w, "backup not found", http.StatusNotFound)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}

