package config

import (
	"os"
	"strconv"
	"time"
)

// Config holds all runtime configuration loaded from environment variables.
type Config struct {
	Port           string
	DBPath         string
	SessionTTL     time.Duration
	MaxAvatarSize  int64
	MaxBodySize    int64
	AllowedOrigins string // comma-separated list, "*" for any
	UploadDir      string
}

// Load reads configuration from environment variables with sensible defaults.
func Load() *Config {
	cfg := &Config{
		Port:           getEnv("PORT", "8143"),
		DBPath:         getEnv("DB_PATH", "./data/messenger.db"),
		MaxAvatarSize:  getEnvInt64("MAX_AVATAR_SIZE", 5*1024*1024),
		MaxBodySize:    getEnvInt64("MAX_BODY_SIZE", 1<<20), // 1 MB
		AllowedOrigins: getEnv("ALLOWED_ORIGINS", "*"),
		UploadDir:      getEnv("UPLOAD_DIR", "./data/upload"),
	}

	ttlStr := getEnv("SESSION_TTL", "720h")
	d, err := time.ParseDuration(ttlStr)
	if err != nil {
		d = 720 * time.Hour
	}
	cfg.SessionTTL = d

	return cfg
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getEnvInt64(key string, fallback int64) int64 {
	if v := os.Getenv(key); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err == nil {
			return n
		}
	}
	return fallback
}
