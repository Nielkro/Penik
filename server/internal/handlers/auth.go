package handlers

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"regexp"
	"time"

	"golang.org/x/crypto/argon2"
	"messenger/server/internal/config"
	"messenger/server/internal/db"
)

var nicknameRe = regexp.MustCompile(`^[a-zA-Z0-9_]{3,32}$`)

type registerRequest struct {
	Name           string   `json:"name"`
	Nickname       string   `json:"nickname"`
	Password       string   `json:"password"`
	DeviceName     string   `json:"device_name"`
	RegistrationID int64    `json:"registration_id"`
	IKPub          []byte   `json:"ik_pub"`
	SPKPub         []byte   `json:"spk_pub"`
	SPKSig         []byte   `json:"spk_sig"`
}

type loginRequest struct {
	Nickname       string   `json:"nickname"`
	Password       string   `json:"password"`
	DeviceName     string   `json:"device_name"`
	RegistrationID int64    `json:"registration_id"`
	IKPub          []byte   `json:"ik_pub"`
	SPKPub         []byte   `json:"spk_pub"`
	SPKSig         []byte   `json:"spk_sig"`
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
	maxOPKUpload  = 1000
)

// validCurveKey reports whether b is a well-formed curve25519 public key:
// 32 raw bytes, or 33 bytes with a 0x05 version prefix.
func validCurveKey(b []byte) bool {
	return len(b) == 32 || (len(b) == 33 && b[0] == 0x05)
}

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
		if req.IKPub != nil {
			if !validCurveKey(req.IKPub) {
				http.Error(w, "malformed identity key material", http.StatusBadRequest)
				return
			}
			if req.SPKPub != nil {
				if !validCurveKey(req.SPKPub) || len(req.SPKSig) != 64 {
					http.Error(w, "malformed identity key material", http.StatusBadRequest)
					return
				}
			}
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

		devRes, err := tx.ExecContext(r.Context(),
			`INSERT INTO devices(user_id,device_name,registration_id,created_at,last_seen) VALUES(?,?,?,?,?)`,
			userID, req.DeviceName, req.RegistrationID, now, now)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		deviceID, _ := devRes.LastInsertId()

		if len(req.IKPub) > 0 {
			_, err = tx.ExecContext(r.Context(),
				`INSERT OR REPLACE INTO device_public_keys(device_id,x25519_pub,created_at,updated_at) VALUES(?,?,?,?)`,
				deviceID, req.IKPub, now, now)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			if len(req.SPKSig) > 0 {
				_, err = tx.ExecContext(r.Context(),
					`INSERT INTO identity_keys(device_id,ik_pub,spk_pub,spk_sig,updated_at) VALUES(?,?,?,?,?)`,
					deviceID, req.IKPub, req.SPKPub, req.SPKSig, now)
				if err != nil {
					http.Error(w, "internal error", http.StatusInternalServerError)
					return
				}
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

		// A device ID is part of message ownership. Deleting and recreating the
		// device here would cascade-delete every offline message addressed to it.
		//
		// Match by identity key first: the client's IK is stable per install, so
		// the same crypto identity should always map to the same device row. This
		// prevents device proliferation when device_name is volatile (e.g. the web
		// client stores it in localStorage, so clearing site data would otherwise
		// mint a new device on every login). Fall back to (user_id, device_name)
		// for older clients that send no IK.
		var deviceID int64
		var lookupErr error
		if len(req.IKPub) > 0 {
			lookupErr = tx.QueryRowContext(r.Context(),
				`SELECT d.id FROM devices d
				 JOIN device_public_keys dpk ON dpk.device_id = d.id
				 WHERE d.user_id=? AND dpk.x25519_pub=?
				 ORDER BY d.id DESC
				 LIMIT 1`,
				userID, req.IKPub).Scan(&deviceID)
		} else {
			lookupErr = sql.ErrNoRows
		}
		if lookupErr == sql.ErrNoRows {
			lookupErr = tx.QueryRowContext(r.Context(),
				`SELECT id FROM devices
				 WHERE user_id=? AND device_name=?
				 ORDER BY id DESC
				 LIMIT 1`,
				userID, req.DeviceName).Scan(&deviceID)
		}
		err = lookupErr
		if err == sql.ErrNoRows {
			devRes, insertErr := tx.ExecContext(r.Context(),
				`INSERT INTO devices(user_id,device_name,registration_id,created_at,last_seen) VALUES(?,?,?,?,?)`,
				userID, req.DeviceName, req.RegistrationID, now, now)
			if insertErr != nil {
				loginInternalError(w, "insert device", insertErr)
				return
			}
			deviceID, err = devRes.LastInsertId()
			if err != nil {
				loginInternalError(w, "get device id", err)
				return
			}
		} else if err != nil {
			loginInternalError(w, "lookup device", err)
			return
		} else {
			_, err = tx.ExecContext(r.Context(),
				`UPDATE devices
				 SET registration_id=?, last_seen=?
				 WHERE id=?`,
				req.RegistrationID, now, deviceID)
			if err != nil {
				loginInternalError(w, "update device", err)
				return
			}
		}

		if len(req.IKPub) > 0 {
			if !validCurveKey(req.IKPub) {
				http.Error(w, "malformed identity key material", http.StatusBadRequest)
				return
			}
			_, err = tx.ExecContext(r.Context(),
				`INSERT OR REPLACE INTO device_public_keys(device_id,x25519_pub,created_at,updated_at) VALUES(?,?,?,?)`,
				deviceID, req.IKPub, now, now)
			if err != nil {
				loginInternalError(w, "insert device public keys", err)
				return
			}
			if len(req.SPKSig) > 0 {
				if !validCurveKey(req.SPKPub) || len(req.SPKSig) != 64 {
					http.Error(w, "malformed identity key material", http.StatusBadRequest)
					return
				}
				_, err = tx.ExecContext(r.Context(),
					`INSERT OR REPLACE INTO identity_keys(device_id,ik_pub,spk_pub,spk_sig,updated_at) VALUES(?,?,?,?,?)`,
					deviceID, req.IKPub, req.SPKPub, req.SPKSig, now)
				if err != nil {
					loginInternalError(w, "insert identity keys", err)
					return
				}
			}
		}



		token, err := generateToken()
		if err != nil {
			loginInternalError(w, "generate session token", err)
			return
		}
		expiresAt := time.Now().Add(cfg.SessionTTL).Unix()
		_, err = tx.ExecContext(r.Context(),
			`INSERT INTO sessions(token,user_id,device_id,created_at,expires_at) VALUES(?,?,?,?,?)`,
			token, userID, deviceID, now, expiresAt)
		if err != nil {
			loginInternalError(w, "insert session", err)
			return
		}

		if err := tx.Commit(); err != nil {
			loginInternalError(w, "commit transaction", err)
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
