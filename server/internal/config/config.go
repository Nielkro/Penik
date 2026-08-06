package config

import (
	"os"
	"strconv"
	"strings"
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
	VKBotToken     string
}

// Load reads configuration from environment variables (and .env file) with sensible defaults.
func Load() *Config {
	loadDotEnv(".env")
	loadDotEnv("server/.env")

	cfg := &Config{
		Port:           getEnv("PORT", "8143"),
		DBPath:         getEnv("DB_PATH", "./data/messenger.db"),
		MaxAvatarSize:  getEnvInt64("MAX_AVATAR_SIZE", 5*1024*1024),
		MaxBodySize:    getEnvInt64("MAX_BODY_SIZE", 210*1024*1024), // supports uploads up to ~200MB
		AllowedOrigins: getEnv("ALLOWED_ORIGINS", "*"),
		UploadDir:      getEnv("UPLOAD_DIR", "./data/upload"),
		VKBotToken:     getEnv("VK_BOT_TOKEN", ""),
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

func loadDotEnv(filepath string) {
	data, err := os.ReadFile(filepath)
	if err != nil {
		return
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) == 2 {
			k := strings.TrimSpace(parts[0])
			v := strings.TrimSpace(parts[1])
			v = strings.Trim(v, `"'`)
			if os.Getenv(k) == "" {
				_ = os.Setenv(k, v)
			}
		}
	}
}
