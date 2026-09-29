package handlers

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"regexp"
	"sync"
	"time"

	"golang.org/x/crypto/argon2"
	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/ws"
)

var nicknameRe = regexp.MustCompile(`^[a-zA-Z0-9_]{3,32}$`)

type registerRequest struct {
	Name           string `json:"name"`
	Nickname       string `json:"nickname"`
	Password       string `json:"password"`
	DeviceName     string `json:"device_name"`
	Platform       string `json:"platform"`
	Location       string `json:"location"`
	RegistrationID int64  `json:"registration_id"`
}

type loginRequest struct {
	Nickname       string `json:"nickname"`
	Password       string `json:"password"`
	DeviceName     string `json:"device_name"`
	Platform       string `json:"platform"`
	Location       string `json:"location"`
	RegistrationID int64  `json:"registration_id"`
}

type loginResponse struct {
	Token    string `json:"token"`
	UserID   int64  `json:"user_id"`
	DeviceID int64  `json:"device_id"`
}

const (
	argon2Time    = 3
	argon2Memory  = 64 * 1024
	argon2Threads = 4
	argon2KeyLen  = 32
	saltLen       = 16
)

// Register handles POST /api/v1/register.
func Register(database *db.DB, cfg *config.Config, hubs ...*ws.Hub) http.HandlerFunc {
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
		// device_name lands in a list rendered by the user's other clients.
		req.DeviceName = sanitizeDeviceField(req.DeviceName, maxDeviceFieldRunes)
		if req.DeviceName == "" {
			http.Error(w, "device_name is invalid", http.StatusBadRequest)
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
			log.Printf("register: begin transaction: %v", err)
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

		loc := resolveLocation(req.Location, r)
		devRes, err := tx.ExecContext(r.Context(),
			`INSERT INTO devices(user_id,device_name,platform,location,registration_id,created_at,last_seen,crypto_version) VALUES(?,?,?,?,?,?,?,1)`,
			userID, req.DeviceName, resolvePlatform(req.Platform, r), loc, req.RegistrationID, now, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		deviceID, _ := devRes.LastInsertId()

		token, err := generateToken()
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		expiresAt := time.Now().Add(cfg.SessionTTL).Unix()
		tokenHash := db.HashSessionToken(token)
		_, err = tx.ExecContext(r.Context(),
			`INSERT INTO sessions(token,user_id,device_id,created_at,expires_at) VALUES(?,?,?,?,?)`,
			tokenHash, userID, deviceID, now, expiresAt)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if err := tx.Commit(); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		if len(hubs) > 0 && hubs[0] != nil {
			go hubs[0].NotifyUserDevicesChanged(context.Background(), database, userID)
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
func Login(database *db.DB, cfg *config.Config, hubs ...*ws.Hub) http.HandlerFunc {
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
		// Same normalisation as registration: the value is stored verbatim and
		// shown in the device list of every one of the user's clients.
		req.DeviceName = sanitizeDeviceField(req.DeviceName, maxDeviceFieldRunes)
		if req.DeviceName == "" {
			http.Error(w, "device_name is invalid", http.StatusBadRequest)
			return
		}

		var userID int64
		var storedHash string
		err := database.QueryRowContext(r.Context(),
			`SELECT id, password_hash FROM users WHERE nickname=?`, req.Nickname).
			Scan(&userID, &storedHash)
		// An unknown nickname must cost the same as a wrong password: returning
		// early here skips Argon2 entirely, and the ~100ms gap is enough to
		// enumerate which accounts exist. Verify against a decoy hash instead.
		if err != nil {
			verifyPassword(req.Password, decoyPasswordHash())
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

		// A device ID is part of message ownership. Deleting and recreating the
		loc := resolveLocation(req.Location, r)
		devRes, insertErr := tx.ExecContext(r.Context(),
			`INSERT INTO devices(user_id,device_name,platform,location,registration_id,created_at,last_seen,crypto_version) VALUES(?,?,?,?,?,?,?,1)`,
			userID, req.DeviceName, resolvePlatform(req.Platform, r), loc, req.RegistrationID, now, now)
		if insertErr != nil {
			loginInternalError(w, "insert device", insertErr)
			return
		}
		deviceID, err := devRes.LastInsertId()
		if err != nil {
			loginInternalError(w, "get device id", err)
			return
		}



		token, err := generateToken()
		if err != nil {
			loginInternalError(w, "generate session token", err)
			return
		}
		expiresAt := time.Now().Add(cfg.SessionTTL).Unix()
		tokenHash := db.HashSessionToken(token)
		_, err = tx.ExecContext(r.Context(),
			`INSERT INTO sessions(token,user_id,device_id,created_at,expires_at) VALUES(?,?,?,?,?)`,
			tokenHash, userID, deviceID, now, expiresAt)
		if err != nil {
			loginInternalError(w, "insert session", err)
			return
		}

		if err := tx.Commit(); err != nil {
			loginInternalError(w, "commit transaction", err)
			return
		}

		if len(hubs) > 0 && hubs[0] != nil {
			go hubs[0].NotifyUserDevicesChanged(context.Background(), database, userID)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(loginResponse{
			Token:    token,
			UserID:   userID,
			DeviceID: deviceID,
		})
	}
}

func loginInternalError(w http.ResponseWriter, operation string, err error) {
	log.Printf("login: %s: %v", operation, err)
	http.Error(w, "internal error", http.StatusInternalServerError)
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

var (
	decoyHashOnce sync.Once
	decoyHash     string
)

// decoyPasswordHash returns a stored-format hash of a random password. It exists
// only so the login path can spend the same Argon2 work on a nickname that does
// not exist as on one that does, closing the timing oracle. Built lazily and
// cached: the derivation itself is the expensive part we want to reuse.
func decoyPasswordHash() string {
	decoyHashOnce.Do(func() {
		filler := make([]byte, 32)
		if _, err := rand.Read(filler); err != nil {
			return
		}
		if h, err := hashPassword(hex.EncodeToString(filler)); err == nil {
			decoyHash = h
		}
	})
	return decoyHash
}
