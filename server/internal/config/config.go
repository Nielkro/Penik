package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config holds all runtime configuration loaded from environment variables.
type Config struct {
	Env            string // "development" (default) or "production"
	Port           string
	DBPath         string
	SessionTTL     time.Duration
	MaxAvatarSize  int64
	MaxBodySize    int64
	AllowedOrigins string // comma-separated list, "*" for any
	UploadDir      string
	VKBotToken     string
	LiveKitURL       string
	LiveKitAPIKey    string
	LiveKitAPISecret string
}

// IsProduction reports whether the server runs with production hardening.
func (c *Config) IsProduction() bool {
	return strings.EqualFold(c.Env, "production") || strings.EqualFold(c.Env, "prod")
}

// Validate enforces fail-closed security invariants. In production the CORS /
// WebSocket origin allowlist must be an explicit, non-wildcard, HTTPS-only list,
// so a misconfigured deployment refuses to start instead of exposing the API to
// any origin.
func (c *Config) Validate() error {
	if !c.IsProduction() {
		return nil
	}
	origins := strings.TrimSpace(c.AllowedOrigins)
	if origins == "" || origins == "*" {
		return fmt.Errorf("ALLOWED_ORIGINS must be an explicit list in production, got %q", c.AllowedOrigins)
	}
	for _, raw := range strings.Split(origins, ",") {
		o := strings.TrimSpace(raw)
		if o == "" {
			continue
		}
		if o == "*" {
			return fmt.Errorf("ALLOWED_ORIGINS must not contain a wildcard in production")
		}
		if !strings.HasPrefix(o, "https://") {
			return fmt.Errorf("ALLOWED_ORIGINS entry %q must use https:// in production", o)
		}
	}
	return nil
}

// Load reads configuration from environment variables (and .env file) with sensible defaults.
func Load() *Config {
	loadDotEnv(".env")
	loadDotEnv("server/.env")

	cfg := &Config{
		Env:            getEnv("ENV", "development"),
		Port:           getEnv("PORT", "8143"),
		DBPath:         getEnv("DB_PATH", "./data/messenger.db"),
		MaxAvatarSize:  getEnvInt64("MAX_AVATAR_SIZE", 5*1024*1024),
		MaxBodySize:    getEnvInt64("MAX_BODY_SIZE", 210*1024*1024), // supports uploads up to ~200MB
		AllowedOrigins: getEnv("ALLOWED_ORIGINS", "*"),
		UploadDir:      getEnv("UPLOAD_DIR", "./data/upload"),
		VKBotToken:     getEnv("VK_BOT_TOKEN", ""),
		LiveKitURL:       getEnv("LIVEKIT_URL", "ws://localhost:7880"),
		LiveKitAPIKey:    getEnv("LIVEKIT_API_KEY", "devkey"),
		LiveKitAPISecret: getEnv("LIVEKIT_API_SECRET", "secret"),
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

func loadDotEnv(filename string) {
	paths := []string{
		filename,
		"server/" + filename,
		"../" + filename,
		"../../" + filename,
	}

	for _, p := range paths {
		data, err := os.ReadFile(p)
		if err != nil {
			continue
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
				v = strings.Trim(v, `"'` + "\r")
				if os.Getenv(k) == "" {
					_ = os.Setenv(k, v)
				}
			}
		}
	}
}
