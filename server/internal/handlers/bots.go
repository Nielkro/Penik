package handlers

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

type createBotRequest struct {
	Name     string `json:"name"`
	Nickname string `json:"nickname"`
	IKPub    []byte `json:"ik_pub,omitempty"`
}

type createBotResponse struct {
	BotID    int64  `json:"bot_id"`
	Name     string `json:"name"`
	Nickname string `json:"nickname"`
	DeviceID int64  `json:"device_id"`
	Token    string `json:"token"`
}

type botInfo struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	Nickname  string `json:"nickname"`
	CreatedAt int64  `json:"created_at"`
}

// generateBotToken returns a secret token prefixed with "bot_".
func generateBotToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "bot_" + hex.EncodeToString(b), nil
}

// CreateBot handles POST /api/v1/bots (creates a new bot owned by authenticated user).
func CreateBot(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ownerID := middleware.UserIDFromCtx(r.Context())
		if ownerID <= 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		var req createBotRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}

		if req.Name == "" || req.Nickname == "" {
			http.Error(w, "name and nickname required", http.StatusBadRequest)
			return
		}
		if !nicknameRe.MatchString(req.Nickname) {
			http.Error(w, "invalid nickname format (3-32 chars: a-z A-Z 0-9 _)", http.StatusBadRequest)
			return
		}

		if len(req.IKPub) > 0 && !validCurveKey(req.IKPub) {
			http.Error(w, "malformed identity key material", http.StatusBadRequest)
			return
		}

		token, err := generateBotToken()
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		// Dummy password hash since bot authenticates via token
		dummyHash, err := hashPassword(token)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		now := time.Now().Unix()
		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		res, err := tx.ExecContext(r.Context(),
			`INSERT INTO users (name, nickname, password_hash, is_bot, created_at) VALUES (?, ?, ?, 1, ?)`,
			req.Name, req.Nickname, dummyHash, now)
		if err != nil {
			http.Error(w, "nickname already taken", http.StatusConflict)
			return
		}

		botUserID, err := res.LastInsertId()
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		_, err = tx.ExecContext(r.Context(),
			`INSERT INTO bots (user_id, owner_user_id, created_at) VALUES (?, ?, ?)`,
			botUserID, ownerID, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		devRes, err := tx.ExecContext(r.Context(),
			`INSERT INTO devices (user_id, device_name, platform, location, created_at, last_seen, crypto_version)
			 VALUES (?, 'Bot Worker', 'bot', 'Server', ?, ?, 2)`,
			botUserID, now, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		deviceID, err := devRes.LastInsertId()
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if len(req.IKPub) > 0 {
			_, err = tx.ExecContext(r.Context(),
				`INSERT INTO device_public_keys (device_id, x25519_pub, created_at, updated_at) VALUES (?, ?, ?, ?)`,
				deviceID, req.IKPub, now, now)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
		}

		// Permanent session for the bot (100 years)
		tokenHash := db.HashSessionToken(token)
		expiresAt := now + 100*365*86400
		_, err = tx.ExecContext(r.Context(),
			`INSERT INTO sessions (token, user_id, device_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)`,
			tokenHash, botUserID, deviceID, now, expiresAt)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(createBotResponse{
			BotID:    botUserID,
			Name:     req.Name,
			Nickname: req.Nickname,
			DeviceID: deviceID,
			Token:    token,
		})
	}
}

// ListMyBots handles GET /api/v1/bots (returns all bots owned by the caller).
func ListMyBots(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ownerID := middleware.UserIDFromCtx(r.Context())
		if ownerID <= 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		rows, err := database.QueryContext(r.Context(),
			`SELECT u.id, u.name, u.nickname, b.created_at
			 FROM bots b
			 JOIN users u ON u.id = b.user_id
			 WHERE b.owner_user_id = ?
			 ORDER BY b.id DESC`, ownerID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		var results []botInfo
		for rows.Next() {
			var b botInfo
			if err := rows.Scan(&b.ID, &b.Name, &b.Nickname, &b.CreatedAt); err == nil {
				results = append(results, b)
			}
		}
		if results == nil {
			results = []botInfo{}
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(results)
	}
}

// RegenerateBotToken handles POST /api/v1/bots/{id}/token/regenerate.
func RegenerateBotToken(database *db.DB, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ownerID := middleware.UserIDFromCtx(r.Context())
		if ownerID <= 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		botIDStr := r.PathValue("id")
		botID, err := strconv.ParseInt(botIDStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		var count int
		err = database.QueryRowContext(r.Context(),
			`SELECT COUNT(1) FROM bots WHERE user_id=? AND owner_user_id=?`, botID, ownerID).Scan(&count)
		if err != nil || count == 0 {
			http.Error(w, "bot not found or forbidden", http.StatusForbidden)
			return
		}

		var deviceID int64
		err = database.QueryRowContext(r.Context(),
			`SELECT id FROM devices WHERE user_id=? ORDER BY id ASC LIMIT 1`, botID).Scan(&deviceID)
		if err != nil {
			http.Error(w, "device not found", http.StatusNotFound)
			return
		}

		newToken, err := generateBotToken()
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		now := time.Now().Unix()
		tokenHash := db.HashSessionToken(newToken)
		expiresAt := now + 100*365*86400

		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		_, _ = tx.ExecContext(r.Context(), `DELETE FROM sessions WHERE user_id=?`, botID)
		_, err = tx.ExecContext(r.Context(),
			`INSERT INTO sessions (token, user_id, device_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)`,
			tokenHash, botID, deviceID, now, expiresAt)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if hub != nil {
			hub.CloseUserSessionsExcept(botID, tokenHash)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"bot_id": botID,
			"token":  newToken,
		})
	}
}

// DeleteBot handles DELETE /api/v1/bots/{id}.
func DeleteBot(database *db.DB, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ownerID := middleware.UserIDFromCtx(r.Context())
		if ownerID <= 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		botIDStr := r.PathValue("id")
		botID, err := strconv.ParseInt(botIDStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		var count int
		err = database.QueryRowContext(r.Context(),
			`SELECT COUNT(1) FROM bots WHERE user_id=? AND owner_user_id=?`, botID, ownerID).Scan(&count)
		if err != nil || count == 0 {
			http.Error(w, "bot not found or forbidden", http.StatusForbidden)
			return
		}

		if hub != nil {
			hub.CloseUserSessionsExcept(botID, "")
		}

		_, err = database.ExecContext(r.Context(), `DELETE FROM users WHERE id=?`, botID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusNoContent)
	}
}
