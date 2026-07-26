package handlers

import (
	"bytes"
	"crypto/md5"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/draw"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/chai2010/webp"
	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

const (
	roleOwner  = "owner"
	roleAdmin  = "admin"
	roleMember = "member"

	statusActive  = "active"
	statusPending = "pending"
	statusRemoved = "removed"

	maxGroupNameLen = 128
	maxGroupMembers = 50
)

type groupCreateRequest struct {
	Name          string  `json:"name"`
	MemberUserIDs []int64 `json:"member_user_ids"`
}

type groupPatchRequest struct {
	Name string `json:"name"`
}

type memberInviteRequest struct {
	UserID int64 `json:"user_id"`
}

type memberRoleRequest struct {
	Role string `json:"role"`
}

// membership returns the caller's role and status in the group, or an error if
// the group is missing/deleted. sql.ErrNoRows means the caller is not a member.
func membership(database *db.DB, r *http.Request, groupID int64) (role, status string, err error) {
	err = database.QueryRowContext(r.Context(),
		`SELECT gm.role, gm.status FROM group_members gm
		 JOIN groups g ON g.id = gm.group_id
		 WHERE gm.group_id=? AND gm.user_id=? AND g.deleted_at IS NULL`,
		groupID, middleware.UserIDFromCtx(r.Context())).Scan(&role, &status)
	return role, status, err
}

// requireActiveMember writes an HTTP error and returns false if the caller is
// not an active member of the group.
func requireActiveMember(w http.ResponseWriter, database *db.DB, r *http.Request, groupID int64) (role string, ok bool) {
	role, status, err := membership(database, r, groupID)
	if err == sql.ErrNoRows {
		http.Error(w, "not a member", http.StatusForbidden)
		return "", false
	}
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return "", false
	}
	if status != statusActive {
		http.Error(w, "not a member", http.StatusForbidden)
		return "", false
	}
	return role, true
}

func groupIDFromPath(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("group_id"), 10, 64)
	if err != nil || id <= 0 {
		http.Error(w, "invalid group id", http.StatusBadRequest)
		return 0, false
	}
	return id, true
}

// CreateGroup creates a group owned by the caller and adds the listed users as
// pending members. The owner is active immediately; invitees start as pending.
func CreateGroup(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var req groupCreateRequest
		if json.NewDecoder(r.Body).Decode(&req) != nil || req.Name == "" || len(req.Name) > maxGroupNameLen {
			http.Error(w, "name required", http.StatusBadRequest)
			return
		}
		if len(req.MemberUserIDs) > maxGroupMembers {
			http.Error(w, "too many members", http.StatusBadRequest)
			return
		}
		owner := middleware.UserIDFromCtx(r.Context())
		now := time.Now().Unix()

		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		res, err := tx.ExecContext(r.Context(),
			`INSERT INTO groups(name,owner_user_id,created_at,updated_at,membership_version,current_key_version)
			 VALUES(?,?,?,?,1,1)`, req.Name, owner, now, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		groupID, _ := res.LastInsertId()

		if _, err := tx.ExecContext(r.Context(),
			`INSERT INTO group_members(group_id,user_id,role,status,joined_at,membership_version)
			 VALUES(?,?,?,?,?,1)`, groupID, owner, roleOwner, statusActive, now); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		// Register key version 1 so envelopes can be uploaded and messages sent
		// under it immediately, before any rotation.
		if _, err := tx.ExecContext(r.Context(),
			`INSERT INTO group_key_versions(group_id,key_version,created_by_user_id,membership_version,created_at)
			 VALUES(?,1,?,1,?)`, groupID, owner, now); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		seen := map[int64]bool{owner: true}
		for _, uid := range req.MemberUserIDs {
			if uid <= 0 || seen[uid] {
				continue
			}
			seen[uid] = true
			if _, err := tx.ExecContext(r.Context(),
				`INSERT INTO group_members(group_id,user_id,role,status,joined_at,membership_version)
				 VALUES(?,?,?,'pending',?,1)`, groupID, uid, roleMember, now); err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]any{
			"id": groupID, "name": req.Name, "owner_user_id": owner,
			"membership_version": 1, "current_key_version": 1, "created_at": now,
		})
	}
}

// ListGroups returns the groups the caller is an active member of, plus groups
// they have been invited to (status pending) so the client can surface and
// accept invitations.
func ListGroups(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		rows, err := database.QueryContext(r.Context(),
			`SELECT g.id,g.name,g.owner_user_id,g.membership_version,g.current_key_version,g.created_at,gm.role,gm.status
			 FROM groups g JOIN group_members gm ON gm.group_id=g.id
			 WHERE gm.user_id=? AND gm.status IN (?,?) AND g.deleted_at IS NULL
			 ORDER BY g.id`, middleware.UserIDFromCtx(r.Context()), statusActive, "pending")
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()
		out := []map[string]any{}
		for rows.Next() {
			var id, owner, mv, kv, created int64
			var name, role, status string
			if err := rows.Scan(&id, &name, &owner, &mv, &kv, &created, &role, &status); err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			out = append(out, map[string]any{
				"id": id, "name": name, "owner_user_id": owner, "role": role, "status": status,
				"membership_version": mv, "current_key_version": kv, "created_at": created,
			})
		}
		json.NewEncoder(w).Encode(map[string]any{"groups": out})
	}
}

// GetGroup returns metadata for a group the caller belongs to.
func GetGroup(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		if _, ok := requireActiveMember(w, database, r, groupID); !ok {
			return
		}
		var id, owner, mv, kv, created, updated int64
		var name string
		err := database.QueryRowContext(r.Context(),
			`SELECT id,name,owner_user_id,membership_version,current_key_version,created_at,updated_at
			 FROM groups WHERE id=? AND deleted_at IS NULL`, groupID).
			Scan(&id, &name, &owner, &mv, &kv, &created, &updated)
		if err == sql.ErrNoRows {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{
			"id": id, "name": name, "owner_user_id": owner,
			"membership_version": mv, "current_key_version": kv,
			"created_at": created, "updated_at": updated,
		})
	}
}

// PatchGroup renames a group. Owner/admin only. Renaming does not rotate keys.
func PatchGroup(database *db.DB) http.HandlerFunc {
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
		var req groupPatchRequest
		if json.NewDecoder(r.Body).Decode(&req) != nil || req.Name == "" || len(req.Name) > maxGroupNameLen {
			http.Error(w, "name required", http.StatusBadRequest)
			return
		}
		_, err := database.ExecContext(r.Context(),
			`UPDATE groups SET name=?,updated_at=? WHERE id=? AND deleted_at IS NULL`,
			req.Name, time.Now().Unix(), groupID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// DeleteGroup soft-deletes a group. Owner only.
func DeleteGroup(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		role, ok := requireActiveMember(w, database, r, groupID)
		if !ok {
			return
		}
		if role != roleOwner {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		_, err := database.ExecContext(r.Context(),
			`UPDATE groups SET deleted_at=? WHERE id=? AND deleted_at IS NULL`,
			time.Now().Unix(), groupID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// ListMembers returns members of a group the caller belongs to.
func ListMembers(database *db.DB, hub *ws.Hub) http.HandlerFunc {
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
			`SELECT gm.user_id,gm.role,gm.status,gm.joined_at,u.name,u.nickname
			 FROM group_members gm JOIN users u ON u.id = gm.user_id
			 WHERE gm.group_id=? ORDER BY gm.joined_at`, groupID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		type memberRow struct {
			uid, joined              int64
			role, status, name, nick string
		}
		var members []memberRow
		for rows.Next() {
			var m memberRow
			if err := rows.Scan(&m.uid, &m.role, &m.status, &m.joined, &m.name, &m.nick); err != nil {
				rows.Close()
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			members = append(members, m)
		}
		rows.Close()
		// userPresence issues its own query; the DB pool is limited to a single
		// SQLite connection, so it must run after the member rows above are
		// fully read and closed to avoid deadlocking on that one connection.
		out := []map[string]any{}
		for _, m := range members {
			online, lastSeen := userPresence(r.Context(), database, hub, m.uid)
			out = append(out, map[string]any{
				"user_id": m.uid, "role": m.role, "status": m.status, "joined_at": m.joined, "name": m.name, "nickname": m.nick,
				"online": online, "last_seen": lastSeen,
			})
		}
		json.NewEncoder(w).Encode(map[string]any{"members": out})
	}
}

// InviteMember adds a pending member. Owner/admin only. Bumps membership_version.
// On success it pushes GROUP_MEMBER_CHANGED to the invitee's online devices so
// the invitation surfaces without a manual refresh.
func InviteMember(database *db.DB, hub *ws.Hub) http.HandlerFunc {
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
		var req memberInviteRequest
		if json.NewDecoder(r.Body).Decode(&req) != nil || req.UserID <= 0 {
			http.Error(w, "user_id required", http.StatusBadRequest)
			return
		}
		now := time.Now().Unix()
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		// A user who is already active must not be downgraded to pending by a
		// duplicate invite. Only fresh or previously-removed users are invitable.
		var existingStatus string
		err = tx.QueryRowContext(r.Context(),
			`SELECT status FROM group_members WHERE group_id=? AND user_id=?`, groupID, req.UserID).Scan(&existingStatus)
		if err == nil && (existingStatus == statusActive || existingStatus == "pending") {
			http.Error(w, "already a member or invited", http.StatusConflict)
			return
		}
		if err != nil && err != sql.ErrNoRows {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		var mv int64
		if err := tx.QueryRowContext(r.Context(),
			`UPDATE groups SET membership_version=membership_version+1,updated_at=?
			 WHERE id=? AND deleted_at IS NULL RETURNING membership_version`,
			now, groupID).Scan(&mv); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		// Re-invite a previously removed user, or insert a fresh pending row.
		if _, err := tx.ExecContext(r.Context(),
			`INSERT INTO group_members(group_id,user_id,role,status,joined_at,membership_version)
			 VALUES(?,?,?,'pending',?,?)
			 ON CONFLICT(group_id,user_id) DO UPDATE SET role=?,status='pending',removed_at=NULL,membership_version=?`,
			groupID, req.UserID, roleMember, now, mv, roleMember, mv); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		notifyMemberChanged(database, r, hub, groupID, req.UserID, mv)
		json.NewEncoder(w).Encode(map[string]any{"user_id": req.UserID, "status": "pending", "membership_version": mv})
	}
}

// notifyMemberChanged pushes GROUP_MEMBER_CHANGED to every online device of the
// given user. Best-effort: delivery failures and offline devices are ignored,
// since the client also reconciles membership on its next ListGroups.
func notifyMemberChanged(database *db.DB, r *http.Request, hub *ws.Hub, groupID, userID, membershipVersion int64) {
	if hub == nil {
		return
	}
	payload, err := msgpack.Marshal(ws.GroupMemberChanged{GroupID: groupID, MembershipVersion: membershipVersion})
	if err != nil {
		return
	}
	rows, err := database.QueryContext(r.Context(), `SELECT id FROM devices WHERE user_id=?`, userID)
	if err != nil {
		return
	}
	defer rows.Close()
	for rows.Next() {
		var did int64
		if rows.Scan(&did) == nil {
			hub.SendToDeviceFrame(did, ws.OpGroupMemberChanged, payload)
		}
	}
}

// AcceptInvitation lets a pending member become active and advances the
// membership epoch. The inviter already pre-staged a key envelope for the
// invitee's devices at invite time (variant A), so the newly active device can
// fetch the current key version straight away without a rotation.
func AcceptInvitation(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		now := time.Now().Unix()
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		var mv int64
		if err := tx.QueryRowContext(r.Context(),
			`UPDATE groups SET membership_version=membership_version+1,updated_at=?
			 WHERE id=? AND deleted_at IS NULL RETURNING membership_version`,
			now, groupID).Scan(&mv); err == sql.ErrNoRows {
			http.Error(w, "not found", http.StatusNotFound)
			return
		} else if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		res, err := tx.ExecContext(r.Context(),
			`UPDATE group_members SET status=?,membership_version=? WHERE group_id=? AND user_id=? AND status='pending'`,
			statusActive, mv, groupID, middleware.UserIDFromCtx(r.Context()))
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if n, _ := res.RowsAffected(); n != 1 {
			// No pending invitation: roll back the version bump.
			http.Error(w, "no pending invitation", http.StatusConflict)
			return
		}
		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// RemoveMember removes a user from a group. Owner/admin only. Bumps
// membership_version so a key rotation can follow. The owner cannot be removed.
func RemoveMember(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		targetID, err := strconv.ParseInt(r.PathValue("user_id"), 10, 64)
		if err != nil || targetID <= 0 {
			http.Error(w, "invalid user id", http.StatusBadRequest)
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
		var targetRole string
		if err := database.QueryRowContext(r.Context(),
			`SELECT role FROM group_members WHERE group_id=? AND user_id=?`, groupID, targetID).Scan(&targetRole); err == sql.ErrNoRows {
			http.Error(w, "not a member", http.StatusNotFound)
			return
		} else if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if targetRole == roleOwner {
			http.Error(w, "cannot remove owner", http.StatusForbidden)
			return
		}
		now := time.Now().Unix()
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()
		var mv int64
		if err := tx.QueryRowContext(r.Context(),
			`UPDATE groups SET membership_version=membership_version+1,updated_at=?
			 WHERE id=? AND deleted_at IS NULL RETURNING membership_version`, now, groupID).Scan(&mv); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if _, err := tx.ExecContext(r.Context(),
			`UPDATE group_members SET status=?,removed_at=?,membership_version=? WHERE group_id=? AND user_id=?`,
			statusRemoved, now, mv, groupID, targetID); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// ChangeMemberRole updates a member's role. Owner only. The owner role is not
// assignable through this endpoint.
func ChangeMemberRole(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		targetID, err := strconv.ParseInt(r.PathValue("user_id"), 10, 64)
		if err != nil || targetID <= 0 {
			http.Error(w, "invalid user id", http.StatusBadRequest)
			return
		}
		role, ok := requireActiveMember(w, database, r, groupID)
		if !ok {
			return
		}
		if role != roleOwner {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		var req memberRoleRequest
		if json.NewDecoder(r.Body).Decode(&req) != nil || (req.Role != roleAdmin && req.Role != roleMember) {
			http.Error(w, "role must be admin or member", http.StatusBadRequest)
			return
		}
		res, err := database.ExecContext(r.Context(),
			`UPDATE group_members SET role=? WHERE group_id=? AND user_id=? AND role!=?`,
			req.Role, groupID, targetID, roleOwner)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if n, _ := res.RowsAffected(); n != 1 {
			http.Error(w, "not a member", http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// DeclineInvitation lets a pending invitee reject an invitation, marking their
// own membership row removed. Only the invitee themselves may decline (the
// caller's own user id is used), and only a pending row is affected.
func DeclineInvitation(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		now := time.Now().Unix()
		res, err := database.ExecContext(r.Context(),
			`UPDATE group_members SET status=?,removed_at=? WHERE group_id=? AND user_id=? AND status='pending'`,
			statusRemoved, now, groupID, middleware.UserIDFromCtx(r.Context()))
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if n, _ := res.RowsAffected(); n != 1 {
			http.Error(w, "no pending invitation", http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// GetGroupHistory returns a page of ciphertext-only group messages, paginated
// with before_id (descending id). Plaintext is never exposed by the group API.
func GetGroupHistory(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}
		if _, ok := requireActiveMember(w, database, r, groupID); !ok {
			return
		}
		limit := 100
		if l, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && l > 0 && l <= 200 {
			limit = l
		}
		beforeID := int64(1<<62 - 1)
		if b, err := strconv.ParseInt(r.URL.Query().Get("before_id"), 10, 64); err == nil && b > 0 {
			beforeID = b
		}
		rows, err := database.QueryContext(r.Context(),
			`SELECT id,message_id,sender_user_id,sender_device_id,key_version,ciphertext,encryption_salt,encryption_nonce,created_at
			 FROM group_messages WHERE group_id=? AND id<? ORDER BY id DESC LIMIT ?`,
			groupID, beforeID, limit)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()
		out := []map[string]any{}
		var minID int64
		for rows.Next() {
			var id, senderU, senderD, kv, created int64
			var msgID string
			var ct, salt, nonce []byte
			if err := rows.Scan(&id, &msgID, &senderU, &senderD, &kv, &ct, &salt, &nonce, &created); err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			minID = id
			out = append(out, map[string]any{
				"id": id, "message_id": msgID, "sender_user_id": senderU, "sender_device_id": senderD,
				"key_version": kv,
				"ciphertext":  base64.RawURLEncoding.EncodeToString(ct),
				"salt":        base64.RawURLEncoding.EncodeToString(salt),
				"nonce":       base64.RawURLEncoding.EncodeToString(nonce),
				"created_at":  created,
			})
		}
		resp := map[string]any{"messages": out}
		if len(out) == limit {
			resp["next_cursor"] = strconv.FormatInt(minID, 10)
		}
		json.NewEncoder(w).Encode(resp)
	}
}

// UploadGroupAvatar handles PUT /api/v1/groups/{group_id}/avatar — multipart, WebP only, max 100KB (or cfg.MaxAvatarSize).
func UploadGroupAvatar(database *db.DB, cfg *config.Config, hub *ws.Hub) http.HandlerFunc {
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
			http.Error(w, "only owner or admin can edit group", http.StatusForbidden)
			return
		}

		if err := r.ParseMultipartForm(cfg.MaxAvatarSize + 1024); err != nil {
			http.Error(w, "multipart parse error", http.StatusBadRequest)
			return
		}

		file, _, err := r.FormFile("avatar")
		if err != nil {
			http.Error(w, "avatar field required", http.StatusBadRequest)
			return
		}
		defer file.Close()

		data, err := io.ReadAll(io.LimitReader(file, cfg.MaxAvatarSize+1))
		if err != nil {
			http.Error(w, "read error", http.StatusInternalServerError)
			return
		}
		if int64(len(data)) > cfg.MaxAvatarSize {
			http.Error(w, "avatar too large", http.StatusRequestEntityTooLarge)
			return
		}

		img, _, err := image.Decode(bytes.NewReader(data))
		if err != nil {
			var webpErr error
			img, webpErr = webp.Decode(bytes.NewReader(data))
			if webpErr != nil {
				http.Error(w, "invalid image format (PNG, JPEG, WebP accepted)", http.StatusUnsupportedMediaType)
				return
			}
		}

		resized := resizeImage(img, 256, 256)

		bounds := resized.Bounds()
		rgba := image.NewRGBA(bounds)
		draw.Draw(rgba, bounds, resized, bounds.Min, draw.Src)
		var buf bytes.Buffer
		if err := webp.Encode(&buf, rgba, &webp.Options{Quality: 85}); err != nil {
			http.Error(w, "image encoding failed", http.StatusInternalServerError)
			return
		}
		avatarBytes := buf.Bytes()

		if err := os.MkdirAll(cfg.UploadDir, 0755); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		filePath := filepath.Join(cfg.UploadDir, fmt.Sprintf("group_%d.webp", groupID))
		if err := os.WriteFile(filePath, avatarBytes, 0644); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		notifyGroupAvatarUpdate(database, r, hub, groupID)

		w.WriteHeader(http.StatusNoContent)
	}
}

// notifyGroupAvatarUpdate pushes GROUP_AVATAR_UPDATE to every online device of
// every active group member, so clients re-fetch the avatar instead of using
// a cached copy. Best-effort: offline devices simply pick up the new avatar
// next time they load it, since the URL itself is stable.
func notifyGroupAvatarUpdate(database *db.DB, r *http.Request, hub *ws.Hub, groupID int64) {
	if hub == nil {
		return
	}
	payload, err := msgpack.Marshal(ws.GroupAvatarUpdate{GroupID: groupID, TS: time.Now().Unix()})
	if err != nil {
		return
	}
	devices, err := activeDevices(database, r, groupID)
	if err != nil {
		return
	}
	for _, dev := range devices {
		hub.SendToDeviceFrame(dev["device_id"], ws.OpGroupAvatarUpdate, payload)
	}
}

// GetGroupAvatar handles GET /api/v1/groups/{group_id}/avatar.
func GetGroupAvatar(database *db.DB, cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		groupID, ok := groupIDFromPath(w, r)
		if !ok {
			return
		}

		// Check if group exists and not deleted
		var exists int
		err := database.QueryRowContext(r.Context(),
			`SELECT 1 FROM groups WHERE id=? AND deleted_at IS NULL`, groupID).Scan(&exists)
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}

		filePath := filepath.Join(cfg.UploadDir, fmt.Sprintf("group_%d.webp", groupID))
		avatar, err := os.ReadFile(filePath)
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}

		etag := fmt.Sprintf(`"%x"`, md5.Sum(avatar))
		w.Header().Set("ETag", etag)
		w.Header().Set("Cache-Control", "private, no-cache")

		if r.Header.Get("If-None-Match") == etag {
			w.WriteHeader(http.StatusNotModified)
			return
		}

		w.Header().Set("Content-Type", "image/webp")
		w.WriteHeader(http.StatusOK)
		w.Write(avatar)
	}
}

