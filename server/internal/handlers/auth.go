package handlers

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"time"

	"golang.org/x/crypto/argon2"
	"messenger/server/internal/config"
	"messenger/server/internal/db"
)

var nicknameRe = regexp.MustCompile(`^[a-zA-Z0-9_]{3,32}$`)

type registerRequest struct {
	Name       string   `json:"name"`
	Nickname   string   `json:"nickname"`
	Password   string   `json:"password"`
	DeviceName string   `json:"device_name"`
	IKPub      []byte   `json:"ik_pub"`
	SPKPub     []byte   `json:"spk_pub"`
	SPKSig     []byte   `json:"spk_sig"`
	OPKList    [][]byte `json:"opk_list"`
}

type loginRequest struct {
	Nickname   string `json:"nickname"`
	Password   string `json:"password"`
	DeviceName string `json:"device_name"`
	IKPub      []byte `json:"ik_pub"`
	SPKPub     []byte `json:"spk_pub"`
	SPKSig     []byte `json:"spk_sig"`
}

type loginResponse struct {
	Token    string `json:"token"`
	UserID   int64  `json:"user_id"`
	DeviceID int64  `json:"device_id"`
}

const (
	argon2Time    = 1
	argon2Memory  = 64 * 1024
	argon2Threads = 4
	argon2KeyLen  = 32
	saltLen       = 16
	maxOPKUpload  = 1000
)

// Register handles POST /api/v1/register.
func Register(database *db.DB, cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req registerRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}

		if req.Name == "" || req.Nickname == "" || req.Password == "" || req.DeviceName == "" {
			http.Error(w, "missing required fields", http.StatusBadRequest)
			return
		}
		if !nicknameRe.MatchString(req.Nickname) {
			http.Error(w, "nickname must be 3-32 chars: a-z A-Z 0-9 _", http.StatusBadRequest)
			return
		}
		if len(req.IKPub) == 0 || len(req.SPKPub) == 0 || len(req.SPKSig) == 0 {
			http.Error(w, "identity key fields required", http.StatusBadRequest)
			return
		}
		if len(req.OPKList) > maxOPKUpload {
			http.Error(w, fmt.Sprintf("too many one-time keys (max %d)", maxOPKUpload), http.StatusBadRequest)
			return
		}

		hash, err := hashPassword(req.Password)
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
			`INSERT INTO users(name,nickname,password_hash,created_at) VALUES(?,?,?,?)`,
			req.Name, req.Nickname, hash, now)
		if err != nil {
			http.Error(w, "nickname already taken", http.StatusConflict)
			return
		}
		userID, _ := res.LastInsertId()

		devRes, err := tx.ExecContext(r.Context(),
			`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,?,?,?)`,
			userID, req.DeviceName, now, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		deviceID, _ := devRes.LastInsertId()

		_, err = tx.ExecContext(r.Context(),
			`INSERT INTO identity_keys(device_id,ik_pub,spk_pub,spk_sig,updated_at) VALUES(?,?,?,?,?)`,
			deviceID, req.IKPub, req.SPKPub, req.SPKSig, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		for _, opk := range req.OPKList {
			_, err = tx.ExecContext(r.Context(),
				`INSERT INTO one_time_keys(device_id,opk_pub,used) VALUES(?,?,0)`,
				deviceID, opk)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
		}

		token, err := generateToken()
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		expiresAt := time.Now().Add(cfg.SessionTTL).Unix()
		_, err = tx.ExecContext(r.Context(),
			`INSERT INTO sessions(token,user_id,device_id,created_at,expires_at) VALUES(?,?,?,?,?)`,
			token, userID, deviceID, now, expiresAt)
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
		json.NewEncoder(w).Encode(loginResponse{
			Token:    token,
			UserID:   userID,
			DeviceID: deviceID,
		})
	}
}

// Login handles POST /api/v1/login.
func Login(database *db.DB, cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req loginRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}

		if req.Nickname == "" || req.Password == "" || req.DeviceName == "" {
			http.Error(w, "missing required fields", http.StatusBadRequest)
			return
		}

		var userID int64
		var storedHash string
		err := database.QueryRowContext(r.Context(),
			`SELECT id, password_hash FROM users WHERE nickname=?`, req.Nickname).
			Scan(&userID, &storedHash)
		if err != nil {
			http.Error(w, "invalid credentials", http.StatusUnauthorized)
			return
		}

		if !verifyPassword(req.Password, storedHash) {
			http.Error(w, "invalid credentials", http.StatusUnauthorized)
			return
		}

		now := time.Now().Unix()

		tx, err := database.BeginTx(r.Context(), nil)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer tx.Rollback()

		// Remove all previous devices for this user so we never accumulate
		// stale devices that cause N-duplicate messages per send.
		_, err = tx.ExecContext(r.Context(),
			`DELETE FROM devices WHERE user_id=?`, userID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		devRes, err := tx.ExecContext(r.Context(),
			`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,?,?,?)`,
			userID, req.DeviceName, now, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		deviceID, _ := devRes.LastInsertId()

		if len(req.IKPub) > 0 {
			_, err = tx.ExecContext(r.Context(),
				`INSERT OR REPLACE INTO identity_keys(device_id,ik_pub,spk_pub,spk_sig,updated_at) VALUES(?,?,?,?,?)`,
				deviceID, req.IKPub, req.SPKPub, req.SPKSig, now)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
		}

		token, err := generateToken()
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		expiresAt := time.Now().Add(cfg.SessionTTL).Unix()
		_, err = tx.ExecContext(r.Context(),
			`INSERT INTO sessions(token,user_id,device_id,created_at,expires_at) VALUES(?,?,?,?,?)`,
			token, userID, deviceID, now, expiresAt)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(loginResponse{
			Token:    token,
			UserID:   userID,
			DeviceID: deviceID,
		})
	}
}

// hashPassword hashes a plaintext password using Argon2id and returns a
// hex-encoded "salt$hash" string.
func hashPassword(password string) (string, error) {
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	hash := argon2.IDKey([]byte(password), salt, argon2Time, argon2Memory, argon2Threads, argon2KeyLen)
	return hex.EncodeToString(salt) + "$" + hex.EncodeToString(hash), nil
}

// verifyPassword checks a plaintext password against the stored hash string.
func verifyPassword(password, stored string) bool {
	for i, c := range stored {
		if c == '$' {
			saltHex := stored[:i]
			hashHex := stored[i+1:]
			salt, err := hex.DecodeString(saltHex)
			if err != nil {
				return false
			}
			expected, err := hex.DecodeString(hashHex)
			if err != nil {
				return false
			}
			actual := argon2.IDKey([]byte(password), salt, argon2Time, argon2Memory, argon2Threads, argon2KeyLen)
			// constant-time compare
			if len(actual) != len(expected) {
				return false
			}
			var diff byte
			for j := range actual {
				diff |= actual[j] ^ expected[j]
			}
			return diff == 0
		}
	}
	return false
}

// generateToken returns a 32-byte cryptographically random hex token.
func generateToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}
