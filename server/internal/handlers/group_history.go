package handlers

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"time"

	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

const (
	// A history packet may be a sizeable chunk of the backlog, so it gets a
	// larger ceiling than a single key envelope but is still bounded.
	maxHistoryPacketLen = 8 << 20 // 8 MiB of ciphertext
	// How long an unclaimed packet lingers before the sweep drops it. Long
	// enough for an invitee to accept and come online, short enough that a
	// declined/abandoned invite does not pin the backlog indefinitely.
	historyPacketTTL = 7 * 24 * time.Hour
)

type historyPacketItem struct {
	DeviceID         int64  `json:"device_id"`
	EncryptedHistory string `json:"encrypted_history"`
	Salt             string `json:"salt"`
	Nonce            string `json:"nonce"`
}

type historyPacketUploadRequest struct {
	Packets []historyPacketItem `json:"packets"`
}

// UploadGroupHistoryPackets stores one-shot, per-device history bundles for
// newly invited members (variant B). The uploader must be an active owner/admin.
// Each packet is encrypted client-side under the pairwise secret with the target
// device; the server only relays opaque ciphertext. Packets may target devices
// of pending or active members — the fetch endpoint still gates on active
// membership, matching how key envelopes are pre-staged at invite time.
func UploadGroupHistoryPackets(database *db.DB, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
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
		var req historyPacketUploadRequest
		if json.NewDecoder(r.Body).Decode(&req) != nil || len(req.Packets) == 0 {
			http.Error(w, "packets required", http.StatusBadRequest)
			return
		}

		// Devices allowed to receive a packet: those of pending or active members.
		allowed := map[int64]int64{} // device_id -> user_id
		arows, err := database.QueryContext(r.Context(),
			`SELECT d.id, d.user_id FROM devices d
			 JOIN group_members gm ON gm.user_id = d.user_id
			 WHERE gm.group_id=? AND gm.status IN (?,?)`, groupID, statusActive, statusPending)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		for arows.Next() {
			var did, uid int64
			if err := arows.Scan(&did, &uid); err != nil {
				arows.Close()
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			allowed[did] = uid
		}
		arows.Close()
		if err := arows.Err(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		now := time.Now().Unix()
		exp := time.Now().Add(historyPacketTTL).Unix()
		senderDevice := middleware.DeviceIDFromCtx(r.Context())
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		stored := []int64{}
		for _, p := range req.Packets {
			forUser, permitted := allowed[p.DeviceID]
			if !permitted {
				http.Error(w, "packet for inactive device", http.StatusForbidden)
				return
			}
			hist, err1 := base64.RawURLEncoding.DecodeString(p.EncryptedHistory)
			salt, err2 := base64.RawURLEncoding.DecodeString(p.Salt)
			nonce, err3 := base64.RawURLEncoding.DecodeString(p.Nonce)
			if err1 != nil || err2 != nil || err3 != nil ||
				len(hist) == 0 || len(hist) > maxHistoryPacketLen ||
				len(salt) == 0 || len(salt) > maxSaltLen ||
				len(nonce) == 0 || len(nonce) > maxNonceLen {
				http.Error(w, "invalid packet encoding", http.StatusBadRequest)
				return
			}
			if _, err := tx.ExecContext(r.Context(),
				`INSERT INTO group_history_packets(group_id,device_id,for_user_id,encrypted_history,encryption_salt,encryption_nonce,sender_device_id,created_at,expires_at)
				 VALUES(?,?,?,?,?,?,?,?,?)
				 ON CONFLICT(group_id,device_id) DO UPDATE SET
				   for_user_id=excluded.for_user_id,
				   encrypted_history=excluded.encrypted_history,
				   encryption_salt=excluded.encryption_salt,
				   encryption_nonce=excluded.encryption_nonce,
				   sender_device_id=excluded.sender_device_id,
				   created_at=excluded.created_at,
				   expires_at=excluded.expires_at`,
				groupID, p.DeviceID, forUser, hist, salt, nonce, senderDevice, now, exp); err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			stored = append(stored, p.DeviceID)
		}
		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		// Nudge online recipient devices to pull their packet.
		if hub != nil {
			if payload, err := msgpack.Marshal(ws.GroupHistoryReady{GroupID: groupID}); err == nil {
				for _, did := range stored {
					hub.SendToDeviceFrame(did, ws.OpGroupHistoryReady, payload)
				}
			}
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// GetGroupHistoryPacket returns and immediately deletes the caller device's
// pending history packet for a group (delete-on-fetch). The caller must be an
// active member — a pending invitee has no packet to read until they accept.
// 404 means there is nothing staged (never was, already fetched, or expired).
func GetGroupHistoryPacket(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		if _, ok := requireActiveMember(w, database, r, groupID); !ok {
			return
		}
		deviceID := middleware.DeviceIDFromCtx(r.Context())
		now := time.Now().Unix()

		// Delete-on-fetch: the RETURNING row is the only copy the device gets, so
		// a successful read also removes the packet in the same statement. An
		// expired packet is treated as absent.
		var hist, salt, nonce []byte
		var senderDevice int64
		err := database.QueryRowContext(r.Context(),
			`DELETE FROM group_history_packets
			 WHERE group_id=? AND device_id=? AND expires_at>?
			 RETURNING encrypted_history,encryption_salt,encryption_nonce,sender_device_id`,
			groupID, deviceID, now).Scan(&hist, &salt, &nonce, &senderDevice)
		if err == sql.ErrNoRows {
			http.Error(w, "no history packet", http.StatusNotFound)
			return
		}
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{
			"encrypted_history": base64.RawURLEncoding.EncodeToString(hist),
			"salt":              base64.RawURLEncoding.EncodeToString(salt),
			"nonce":             base64.RawURLEncoding.EncodeToString(nonce),
			"sender_device_id":  senderDevice,
		})
	}
}
