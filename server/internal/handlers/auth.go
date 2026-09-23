package handlers

import (
	"context"
	"crypto/rand"
	"database/sql"
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
	Name           string   `json:"name"`
	Nickname       string   `json:"nickname"`
	Password       string   `json:"password"`
	DeviceName     string   `json:"device_name"`
	Platform       string   `json:"platform"`
	Location       string   `json:"location"`
	RegistrationID int64    `json:"registration_id"`
	CryptoVersion  int      `json:"crypto_version"`
	IKPub          []byte   `json:"ik_pub"`
	SigningKey     []byte   `json:"signing_key"`
	SPKPub         []byte   `json:"spk_pub"`
	SPKSig         []byte   `json:"spk_sig"`
}

type loginRequest struct {
	Nickname       string   `json:"nickname"`
	Password       string   `json:"password"`
	DeviceName     string   `json:"device_name"`
	Platform       string   `json:"platform"`
	Location       string   `json:"location"`
	RegistrationID int64    `json:"registration_id"`
	CryptoVersion  int      `json:"crypto_version"`
	IKPub          []byte   `json:"ik_pub"`
	SigningKey     []byte   `json:"signing_key"`
	SPKPub         []byte   `json:"spk_pub"`
	SPKSig         []byte   `json:"spk_sig"`
}

type loginResponse struct {
	Token          string `json:"token"`
	UserID         int64  `json:"user_id"`
	DeviceID       int64  `json:"device_id"`
	RebindRequired bool   `json:"rebind_required,omitempty"`
	TargetDeviceID int64  `json:"target_device_id,omitempty"`
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


		if len(req.SigningKey) > 0 && len(req.SigningKey) != 32 {
			http.Error(w, "malformed signing key material", http.StatusBadRequest)
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
		cryptoVer := req.CryptoVersion
		if cryptoVer <= 0 {
			cryptoVer = 1
		}
		devRes, err := tx.ExecContext(r.Context(),
			`INSERT INTO devices(user_id,device_name,platform,location,registration_id,created_at,last_seen,crypto_version) VALUES(?,?,?,?,?,?,?,?)`,
			userID, req.DeviceName, resolvePlatform(req.Platform, r), loc, req.RegistrationID, now, now, cryptoVer)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		deviceID, _ := devRes.LastInsertId()

		if len(req.IKPub) > 0 {
			var signingKey any
			if len(req.SigningKey) == 32 {
				signingKey = req.SigningKey
			}
			_, err = tx.ExecContext(r.Context(),
				`INSERT INTO device_public_keys(device_id,x25519_pub,ed25519_pub,created_at,updated_at)
				 VALUES(?,?,?,?,?)
				 ON CONFLICT(device_id) DO UPDATE SET
				   x25519_pub=excluded.x25519_pub,
				   ed25519_pub=excluded.ed25519_pub,
				   updated_at=excluded.updated_at`,
				deviceID, req.IKPub, signingKey, now, now)
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
		// device here would cascade-delete every offline message addressed to it.
		//
		// Match by identity key: the client's IK is stable per install.
		// Never fall back to device_name, to prevent device claiming attacks.
		var matchedDeviceID int64
		var lookupErr error
		if len(req.IKPub) > 0 {
			lookupErr = tx.QueryRowContext(r.Context(),
				`SELECT d.id FROM devices d
				 JOIN device_public_keys dpk ON dpk.device_id = d.id
				 WHERE d.user_id=? AND dpk.x25519_pub=?
				 ORDER BY d.id DESC
				 LIMIT 1`,
				userID, req.IKPub).Scan(&matchedDeviceID)
		} else {
			lookupErr = sql.ErrNoRows
		}

		loc := resolveLocation(req.Location, r)
		cryptoVer := req.CryptoVersion
		if cryptoVer <= 0 {
			cryptoVer = 1
		}

		var deviceID int64
		var targetDeviceID int64
		if lookupErr == nil && matchedDeviceID > 0 && cfg.DeviceRebindRequired {
			// Matched an existing device key, but require proof-of-possession challenge.
			// Issue provisional temporary device; caller must rebind to targetDeviceID.
			targetDeviceID = matchedDeviceID
			devRes, insertErr := tx.ExecContext(r.Context(),
				`INSERT INTO devices(user_id,device_name,platform,location,registration_id,created_at,last_seen,crypto_version) VALUES(?,?,?,?,?,?,?,?)`,
				userID, req.DeviceName, resolvePlatform(req.Platform, r), loc, req.RegistrationID, now, now, cryptoVer)
			if insertErr != nil {
				loginInternalError(w, "insert provisional device", insertErr)
				return
			}
			deviceID, err = devRes.LastInsertId()
			if err != nil {
				loginInternalError(w, "get provisional device id", err)
				return
			}
		} else if lookupErr == nil && matchedDeviceID > 0 {
			// Legacy path when rebind challenge is disabled
			deviceID = matchedDeviceID
			_, err = tx.ExecContext(r.Context(),
				`UPDATE devices
				 SET registration_id=?, last_seen=?,
				     platform=CASE WHEN ?<>'' THEN ? ELSE platform END,
				     location=CASE WHEN ?<>'' THEN ? ELSE location END,
				     crypto_version=CASE WHEN ? > 0 THEN ? ELSE crypto_version END
				 WHERE id=?`,
				req.RegistrationID, now,
				resolvePlatform(req.Platform, r), resolvePlatform(req.Platform, r),
				loc, loc,
				req.CryptoVersion, req.CryptoVersion,
				deviceID)
			if err != nil {
				loginInternalError(w, "update device", err)
				return
			}
		} else if lookupErr == sql.ErrNoRows {
			devRes, insertErr := tx.ExecContext(r.Context(),
				`INSERT INTO devices(user_id,device_name,platform,location,registration_id,created_at,last_seen,crypto_version) VALUES(?,?,?,?,?,?,?,?)`,
				userID, req.DeviceName, resolvePlatform(req.Platform, r), loc, req.RegistrationID, now, now, cryptoVer)
			if insertErr != nil {
				loginInternalError(w, "insert device", insertErr)
				return
			}
			deviceID, err = devRes.LastInsertId()
			if err != nil {
				loginInternalError(w, "get device id", err)
				return
			}
		} else {
			loginInternalError(w, "lookup device", lookupErr)
			return
		}

		if len(req.SigningKey) > 0 && len(req.SigningKey) != 32 {
			http.Error(w, "malformed signing key material", http.StatusBadRequest)
			return
		}

		if len(req.IKPub) > 0 {
			if !validCurveKey(req.IKPub) {
				http.Error(w, "malformed identity key material", http.StatusBadRequest)
				return
			}
			var signingKey any
			if len(req.SigningKey) == 32 {
				signingKey = req.SigningKey
			}
			_, err = tx.ExecContext(r.Context(),
				`INSERT INTO device_public_keys(device_id,x25519_pub,ed25519_pub,created_at,updated_at)
				 VALUES(?,?,?,?,?)
				 ON CONFLICT(device_id) DO UPDATE SET
				   x25519_pub=excluded.x25519_pub,
				   ed25519_pub=COALESCE(excluded.ed25519_pub, device_public_keys.ed25519_pub),
				   updated_at=excluded.updated_at`,
				deviceID, req.IKPub, signingKey, now, now)
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
			Token:          token,
			UserID:         userID,
			DeviceID:       deviceID,
			RebindRequired: targetDeviceID > 0,
			TargetDeviceID: targetDeviceID,
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
