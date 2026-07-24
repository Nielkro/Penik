package handlers

import (
	"bytes"
	"context"
	"crypto/md5"
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
	"regexp"
	"strconv"
	"time"

	"github.com/chai2010/webp"
	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)



var nicknamePattern = regexp.MustCompile(`^[a-z0-9_]{3,32}$`)

const nicknameCooldown = 7 * 24 * time.Hour

// SearchUsers handles GET /api/v1/users/search?q=&limit=20.
func SearchUsers(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query().Get("q")
		limit := 20
		if l := r.URL.Query().Get("limit"); l != "" {
			if n, err := strconv.Atoi(l); err == nil && n > 0 && n <= 100 {
				limit = n
			}
		}

		pattern := "%" + q + "%"
		rows, err := database.QueryContext(r.Context(),
			`SELECT id, name, nickname FROM users
			 WHERE nickname LIKE ? OR name LIKE ?
			 LIMIT ?`, pattern, pattern, limit)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		type result struct {
			ID       int64  `json:"id"`
			Name     string `json:"name"`
			Nickname string `json:"nickname"`
		}
		var results []result
		for rows.Next() {
			var res result
			if err := rows.Scan(&res.ID, &res.Name, &res.Nickname); err == nil {
				results = append(results, res)
			}
		}
		if results == nil {
			results = []result{}
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(results)
	}
}

// GetUser handles GET /api/v1/users/:id.
func GetUser(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		idStr := r.PathValue("id")
		id, err := strconv.ParseInt(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		var name, nickname string
		err = database.QueryRowContext(r.Context(),
			`SELECT name, nickname FROM users WHERE id=?`, id).Scan(&name, &nickname)
		if err != nil {
			http.Error(w, "user not found", http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"id":       id,
			"name":     name,
			"nickname": nickname,
		})
	}
}

// UpdateName handles PUT /api/v1/users/me/name.
func UpdateName(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())

		var body struct {
			Name string `json:"name"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Name == "" {
			http.Error(w, "name required", http.StatusBadRequest)
			return
		}
		if len(body.Name) > 64 {
			http.Error(w, "name too long (max 64 chars)", http.StatusBadRequest)
			return
		}

		_, err := database.ExecContext(r.Context(),
			`UPDATE users SET name=? WHERE id=?`, body.Name, userID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}

// UpdateNickname handles PUT /api/v1/users/me/nickname with a 7-day cooldown.
func UpdateNickname(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())

		var body struct {
			Nickname string `json:"nickname"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Nickname == "" {
			http.Error(w, "nickname required", http.StatusBadRequest)
			return
		}
		if !nicknamePattern.MatchString(body.Nickname) {
			http.Error(w, "invalid nickname format", http.StatusBadRequest)
			return
		}

		var changedAt int64
		_ = database.QueryRowContext(r.Context(),
			`SELECT nickname_changed_at FROM users WHERE id=?`, userID).Scan(&changedAt)

		if time.Since(time.Unix(changedAt, 0)) < nicknameCooldown {
			available := time.Unix(changedAt, 0).Add(nicknameCooldown)
			http.Error(w, fmt.Sprintf("nickname can only be changed every 7 days; available at %s", available.UTC().Format(time.RFC3339)), http.StatusTooManyRequests)
			return
		}

		_, err := database.ExecContext(r.Context(),
			`UPDATE users SET nickname=?, nickname_changed_at=? WHERE id=?`,
			body.Nickname, time.Now().Unix(), userID)
		if err != nil {
			http.Error(w, "nickname already taken", http.StatusConflict)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}

// UploadAvatar handles PUT /api/v1/avatar — multipart, WebP only, max 100KB.
func UploadAvatar(database *db.DB, cfg *config.Config, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())

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
			http.Error(w, "avatar too large (max 5MB)", http.StatusRequestEntityTooLarge)
			return
		}

		// Validate and decode image (PNG, JPEG, GIF, WebP).
		img, _, err := image.Decode(bytes.NewReader(data))
		if err != nil {
			var webpErr error
			img, webpErr = webp.Decode(bytes.NewReader(data))
			if webpErr != nil {
				http.Error(w, "invalid image format (PNG, JPEG, WebP accepted)", http.StatusUnsupportedMediaType)
				return
			}
		}

		// Resize to 128×128.
		resized := resizeImage(img, 128, 128)

		// Encode as WebP for compact storage.
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

		filePath := filepath.Join(cfg.UploadDir, fmt.Sprintf("%d.webp", userID))
		if err := os.WriteFile(filePath, avatarBytes, 0644); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		// Notify contacts and group peers via WS
		if hub != nil {
			go notifyAvatarUpdatePeers(context.Background(), database, hub, userID)
		}

		w.WriteHeader(http.StatusNoContent)
	}
}

func notifyAvatarUpdatePeers(ctx context.Context, database *db.DB, hub *ws.Hub, userID int64) {
	// Find all 1:1 chat partners and group members sharing chats with userID
	query := `
		SELECT DISTINCT d.id FROM devices d WHERE d.user_id IN (
			SELECT sender_user_id FROM messages WHERE recipient_user_id = ?
			UNION
			SELECT recipient_user_id FROM messages WHERE sender_user_id = ?
			UNION
			SELECT user_id FROM group_members WHERE group_id IN (
				SELECT group_id FROM group_members WHERE user_id = ?
			)
			UNION
			SELECT ?
		)
	`
	rows, err := database.QueryContext(ctx, query, userID, userID, userID, userID)
	if err != nil {
		return
	}
	defer rows.Close()

	var deviceIDs []int64
	for rows.Next() {
		var devID int64
		if err := rows.Scan(&devID); err == nil {
			deviceIDs = append(deviceIDs, devID)
		}
	}

	if len(deviceIDs) == 0 {
		return
	}

	payload, err := msgpack.Marshal(map[string]any{
		"user_id": userID,
		"ts":      time.Now().Unix(),
	})
	if err != nil {
		return
	}

	hub.BroadcastAvatarUpdate(deviceIDs, payload)
}

// GetAvatar handles GET /api/v1/avatar/:user_id.
func GetAvatar(database *db.DB, cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		idStr := r.PathValue("user_id")
		id, err := strconv.ParseInt(idStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		// Check if user exists first to return proper 404
		var exists int
		err = database.QueryRowContext(r.Context(),
			`SELECT 1 FROM users WHERE id=?`, id).Scan(&exists)
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}

		filePath := filepath.Join(cfg.UploadDir, fmt.Sprintf("%d.webp", id))
		avatar, err := os.ReadFile(filePath)
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}

		// Calculate ETag as MD5 hash of image bytes
		etag := fmt.Sprintf(`"%x"`, md5.Sum(avatar))
		w.Header().Set("ETag", etag)
		w.Header().Set("Cache-Control", "private, no-cache")

		if r.Header.Get("If-None-Match") == etag {
			w.WriteHeader(http.StatusNotModified)
			return
		}

		// Stored as WebP; content-type reflects that.
		w.Header().Set("Content-Type", "image/webp")
		w.WriteHeader(http.StatusOK)
		w.Write(avatar)
	}
}

// resizeImage scales src to fit within dstW×dstH using nearest-neighbour sampling.
func resizeImage(src image.Image, dstW, dstH int) image.Image {
	srcBounds := src.Bounds()
	srcW := srcBounds.Dx()
	srcH := srcBounds.Dy()

	dst := image.NewRGBA(image.Rect(0, 0, dstW, dstH))
	for y := 0; y < dstH; y++ {
		for x := 0; x < dstW; x++ {
			srcX := x * srcW / dstW
			srcY := y * srcH / dstH
			dst.Set(x, y, src.At(srcBounds.Min.X+srcX, srcBounds.Min.Y+srcY))
		}
	}
	return dst
}

type updatePasswordRequest struct {
	OldPassword string `json:"old_password"`
	NewPassword string `json:"new_password"`
}

// UpdatePassword handles PUT /api/v1/users/me/password.
func UpdatePassword(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())

		var req updatePasswordRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}

		if req.OldPassword == "" || req.NewPassword == "" {
			http.Error(w, "missing required fields", http.StatusBadRequest)
			return
		}

		var storedHash string
		err := database.QueryRowContext(r.Context(),
			`SELECT password_hash FROM users WHERE id=?`, userID).Scan(&storedHash)
		if err != nil {
			http.Error(w, "user not found", http.StatusNotFound)
			return
		}

		if !verifyPassword(req.OldPassword, storedHash) {
			http.Error(w, "invalid old password", http.StatusUnauthorized)
			return
		}

		newHash, err := hashPassword(req.NewPassword)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		_, err = database.ExecContext(r.Context(),
			`UPDATE users SET password_hash=? WHERE id=?`, newHash, userID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}

// CheckNickname handles GET /api/v1/users/check?nickname=...
func CheckNickname(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		nickname := r.URL.Query().Get("nickname")
		if nickname == "" {
			http.Error(w, "nickname query param required", http.StatusBadRequest)
			return
		}

		var count int
		err := database.QueryRowContext(r.Context(),
			`SELECT COUNT(1) FROM users WHERE nickname=?`, nickname).Scan(&count)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]bool{
			"available": count == 0,
		})
	}
}

// GetUserByNicknameProfile handles GET /api/v1/users/{nickname}/profile
func GetUserByNicknameProfile(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		nickname := r.PathValue("nickname")
		if nickname == "" {
			http.Error(w, "nickname path param required", http.StatusBadRequest)
			return
		}

		var id int64
		var name, dbNickname string
		err := database.QueryRowContext(r.Context(),
			`SELECT id, name, nickname FROM users WHERE nickname=?`, nickname).Scan(&id, &name, &dbNickname)
		if err != nil {
			http.Error(w, "user not found", http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"id":       id,
			"name":     name,
			"nickname": dbNickname,
		})
	}
}
