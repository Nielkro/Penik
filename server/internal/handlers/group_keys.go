package handlers

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

const (
	maxEnvelopeKeyLen = 4096
	maxSaltLen        = 64
	maxNonceLen       = 64
)

type envelopeItem struct {
	DeviceID     int64  `json:"device_id"`
	EncryptedKey string `json:"encrypted_key"`
	Salt         string `json:"salt"`
	Nonce        string `json:"nonce"`
}

type envelopeUploadRequest struct {
	Envelopes []envelopeItem `json:"envelopes"`
}

// activeDevices returns all device IDs belonging to active members of the group.
// This is the recipient set a rotating client must build envelopes for.
func activeDevices(database *db.DB, r *http.Request, groupID int64) ([]map[string]int64, error) {
	rows, err := database.QueryContext(r.Context(),
		`SELECT d.id, d.user_id FROM devices d
		 JOIN group_members gm ON gm.user_id = d.user_id
		 WHERE gm.group_id=? AND gm.status=?`, groupID, statusActive)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []map[string]int64
	for rows.Next() {
		var did, uid int64
		if err := rows.Scan(&did, &uid); err != nil {
			return nil, err
		}
		out = append(out, map[string]int64{"device_id": did, "user_id": uid})
	}
	return out, rows.Err()
}

// RotateGroupKey creates a new key version pinned to the current membership_version.
// Owner/admin only. Returns the new version and the active device recipient set so
// the client can build and upload envelopes. The server never sees the group key.
func RotateGroupKey(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		role, ok := requireActiveMember(w, database, r, groupID)
		if !ok {
			return
		}
		if role != roleOwner && role != roleAdmin {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		now := time.Now().Unix()
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		var mv, newVersion int64
		if err := tx.QueryRowContext(r.Context(),
			`UPDATE groups SET current_key_version=current_key_version+1,updated_at=?
			 WHERE id=? AND deleted_at IS NULL
			 RETURNING current_key_version, membership_version`, now, groupID).Scan(&newVersion, &mv); err == sql.ErrNoRows {
			http.Error(w, "not found", http.StatusNotFound)
			return
		} else if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if _, err := tx.ExecContext(r.Context(),
			`INSERT INTO group_key_versions(group_id,key_version,created_by_user_id,membership_version,created_at)
			 VALUES(?,?,?,?,?)`, groupID, newVersion, middleware.UserIDFromCtx(r.Context()), mv, now); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		devices, err := activeDevices(database, r, groupID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{
			"key_version": newVersion, "membership_version": mv, "devices": devices,
		})
	}
}

// UploadEnvelopes stores per-device encrypted copies of a group key version.
// The uploader must be an active owner/admin; the server rejects envelopes for
// devices that do not belong to an active member.
func UploadEnvelopes(database *db.DB, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		version, err := strconv.ParseInt(r.PathValue("version"), 10, 64)
		if err != nil || version <= 0 {
			http.Error(w, "invalid version", http.StatusBadRequest)
			return
		}
		role, ok := requireActiveMember(w, database, r, groupID)
		if !ok {
			return
		}
		if role != roleOwner && role != roleAdmin {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		var req envelopeUploadRequest
		if json.NewDecoder(r.Body).Decode(&req) != nil || len(req.Envelopes) == 0 {
			http.Error(w, "envelopes required", http.StatusBadRequest)
			return
		}
		// Ensure the key version exists for this group.
		var exists int
		if err := database.QueryRowContext(r.Context(),
			`SELECT 1 FROM group_key_versions WHERE group_id=? AND key_version=?`, groupID, version).Scan(&exists); err == sql.ErrNoRows {
			http.Error(w, "unknown key version", http.StatusNotFound)
			return
		} else if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		// Set of devices allowed to receive this version (active members' devices).
		allowed := map[int64]bool{}
		devices, err := activeDevices(database, r, groupID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		for _, d := range devices {
			allowed[d["device_id"]] = true
		}

		now := time.Now().Unix()
		senderDevice := middleware.DeviceIDFromCtx(r.Context())
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		stored := []int64{}
		for _, e := range req.Envelopes {
			if !allowed[e.DeviceID] {
				http.Error(w, "envelope for inactive device", http.StatusForbidden)
				return
			}
			key, err1 := base64.RawURLEncoding.DecodeString(e.EncryptedKey)
			salt, err2 := base64.RawURLEncoding.DecodeString(e.Salt)
			nonce, err3 := base64.RawURLEncoding.DecodeString(e.Nonce)
			if err1 != nil || err2 != nil || err3 != nil ||
				len(key) == 0 || len(key) > maxEnvelopeKeyLen ||
				len(salt) == 0 || len(salt) > maxSaltLen ||
				len(nonce) == 0 || len(nonce) > maxNonceLen {
				http.Error(w, "invalid envelope encoding", http.StatusBadRequest)
				return
			}
			if _, err := tx.ExecContext(r.Context(),
				`INSERT INTO group_key_envelopes(group_id,key_version,device_id,encrypted_key,encryption_salt,encryption_nonce,sender_device_id,created_at)
				 VALUES(?,?,?,?,?,?,?,?)
				 ON CONFLICT(group_id,key_version,device_id) DO UPDATE SET
				   encrypted_key=excluded.encrypted_key,
				   encryption_salt=excluded.encryption_salt,
				   encryption_nonce=excluded.encryption_nonce,
				   sender_device_id=excluded.sender_device_id,
				   created_at=excluded.created_at`,
				groupID, version, e.DeviceID, key, salt, nonce, senderDevice, now); err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			stored = append(stored, e.DeviceID)
		}
		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		// Notify online recipient devices that a new key version is available.
		if hub != nil {
			if payload, err := msgpack.Marshal(ws.GroupKeyAvailable{GroupID: groupID, KeyVersion: version}); err == nil {
				for _, did := range stored {
					hub.SendToDeviceFrame(did, ws.OpGroupKeyAvailable, payload)
				}
			}
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// ListKeyVersions returns the key versions whose envelope exists for the caller's
// current device, so the client knows which keys it can obtain.
func ListKeyVersions(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		if _, ok := requireActiveMember(w, database, r, groupID); !ok {
			return
		}
		rows, err := database.QueryContext(r.Context(),
			`SELECT key_version FROM group_key_envelopes
			 WHERE group_id=? AND device_id=? ORDER BY key_version`,
			groupID, middleware.DeviceIDFromCtx(r.Context()))
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()
		versions := []int64{}
		for rows.Next() {
			var v int64
			if err := rows.Scan(&v); err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			versions = append(versions, v)
		}
		json.NewEncoder(w).Encode(map[string]any{"versions": versions})
	}
}

// GetEnvelope returns the encrypted group key envelope for the caller's current
// device and the requested version. A device can only fetch its own envelope.
func GetEnvelope(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		version, err := strconv.ParseInt(r.PathValue("version"), 10, 64)
		if err != nil || version <= 0 {
			http.Error(w, "invalid version", http.StatusBadRequest)
			return
		}
		if _, ok := requireActiveMember(w, database, r, groupID); !ok {
			return
		}
		deviceID := middleware.DeviceIDFromCtx(r.Context())
		var key, salt, nonce []byte
		var senderDevice int64
		err = database.QueryRowContext(r.Context(),
			`SELECT encrypted_key,encryption_salt,encryption_nonce,sender_device_id
			 FROM group_key_envelopes WHERE group_id=? AND key_version=? AND device_id=?`,
			groupID, version, deviceID).Scan(&key, &salt, &nonce, &senderDevice)
		if err == sql.ErrNoRows {
			http.Error(w, "envelope not found", http.StatusNotFound)
			return
		}
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		_, _ = database.ExecContext(r.Context(),
			`UPDATE group_key_envelopes SET delivered_at=? WHERE group_id=? AND key_version=? AND device_id=? AND delivered_at IS NULL`,
			time.Now().Unix(), groupID, version, deviceID)
		json.NewEncoder(w).Encode(map[string]any{
			"key_version":      version,
			"encrypted_key":    base64.RawURLEncoding.EncodeToString(key),
			"salt":             base64.RawURLEncoding.EncodeToString(salt),
			"nonce":            base64.RawURLEncoding.EncodeToString(nonce),
			"sender_device_id": senderDevice,
		})
	}
}
