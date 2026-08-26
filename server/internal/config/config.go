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
	Env                string // "development" (default) or "production"
	Port               string
	DBPath             string
	SessionTTL         time.Duration
	MaxAvatarSize      int64
	MaxBodySize        int64
	MaxUploadSize      int64
	LiveKitTokenTTL    time.Duration
	AllowedOrigins     string // comma-separated list, "*" for any
	UploadDir          string
	GeoIPPath          string // Path to GeoLite2-City.mmdb / dbip-city-lite.mmdb (optional)
	TelegramBotToken   string
	StickersDir        string
	LiveKitURL         string
	LiveKitFallbackURL string
	LiveKitAPIKey      string
	LiveKitAPISecret   string
}

// IsProduction reports whether the server runs with production hardening.
func (c *Config) IsProduction() bool {
	return strings.EqualFold(c.Env, "production") || strings.EqualFold(c.Env, "prod")
}

// Validate enforces fail-closed security invariants and required configuration.
func (c *Config) Validate() error {
	if strings.TrimSpace(c.LiveKitURL) == "" {
		return fmt.Errorf("LIVEKIT_URL is required and cannot be empty")
	}
	if strings.TrimSpace(c.LiveKitFallbackURL) == "" {
		return fmt.Errorf("LIVEKIT_FALLBACK_URL is required and cannot be empty")
	}

	// ALLOWED_ORIGINS is required in every environment. A wildcard disabled the
	// CSRF check and the WebSocket origin check at once, so "unset" used to mean
	// "no origin enforcement at all" — the server refuses to start instead.
	origins := strings.TrimSpace(c.AllowedOrigins)
	if origins == "" || origins == "*" {
		return fmt.Errorf("ALLOWED_ORIGINS must be an explicit comma-separated list, got %q", c.AllowedOrigins)
	}
	for _, raw := range strings.Split(origins, ",") {
		if strings.TrimSpace(raw) == "*" {
			return fmt.Errorf("ALLOWED_ORIGINS must not contain a wildcard entry")
		}
	}

	if !c.IsProduction() {
		return nil
	}
	if isDefaultLiveKitCredential(c.LiveKitAPIKey, defaultLiveKitAPIKey) ||
		isDefaultLiveKitCredential(c.LiveKitAPISecret, defaultLiveKitAPISecret) {
		return fmt.Errorf("LIVEKIT_API_KEY and LIVEKIT_API_SECRET must be set to real credentials in production")
	}
	for _, raw := range strings.Split(origins, ",") {
		o := strings.TrimSpace(raw)
		if o == "" {
			continue
		}
		if !strings.HasPrefix(o, "https://") {
			return fmt.Errorf("ALLOWED_ORIGINS entry %q must use https:// in production", o)
		}
	}
	return nil
}

const (
	defaultLiveKitAPIKey    = "devkey"
	defaultLiveKitAPISecret = "secret"
)

// isDefaultLiveKitCredential reports whether a credential is still the built-in
// development placeholder. Those values ship in the LiveKit docs, so anyone can
// mint a join token for any room if they survive into production.
func isDefaultLiveKitCredential(value, placeholder string) bool {
	return strings.TrimSpace(value) == "" || strings.EqualFold(strings.TrimSpace(value), placeholder)
}

// Load reads configuration from environment variables (and .env file) with sensible defaults.
func Load() *Config {
	loadDotEnv(".env")
	loadDotEnv("server/.env")

	cfg := &Config{
		Env:                getEnv("ENV", "development"),
		Port:               getEnv("PORT", "8143"),
		DBPath:             getEnv("DB_PATH", "./data/messenger.db"),
		MaxAvatarSize:      getEnvInt64("MAX_AVATAR_SIZE", 5*1024*1024),
		MaxBodySize:        getEnvInt64("MAX_BODY_SIZE", 12*1024*1024),        // ordinary JSON/form requests
		MaxUploadSize:      getEnvInt64("MAX_UPLOAD_SIZE", 210*1024*1024),   // attachment endpoint only, ~200MB payloads
		AllowedOrigins:     getEnv("ALLOWED_ORIGINS", ""),
		UploadDir:          getEnv("UPLOAD_DIR", "./data/upload"),
		GeoIPPath:          getEnv("GEOIP_PATH", "./data/GeoLite2-City.mmdb"),
		TelegramBotToken:   getEnv("TELEGRAM_BOT_TOKEN", ""),
		StickersDir:        getEnv("STICKERS_DIR", "./data/stickers"),
		LiveKitURL:         getEnv("LIVEKIT_URL", ""),
		LiveKitFallbackURL: getEnv("LIVEKIT_FALLBACK_URL", ""),
		LiveKitAPIKey:      getEnv("LIVEKIT_API_KEY", defaultLiveKitAPIKey),
		LiveKitAPISecret:   getEnv("LIVEKIT_API_SECRET", defaultLiveKitAPISecret),
	}

	cfg.SessionTTL = getEnvDuration("SESSION_TTL", 720*time.Hour)

	// A call token only has to survive from the offer until the callee joins the
	// room, so it needs nothing like the session lifetime.
	cfg.LiveKitTokenTTL = getEnvDuration("LIVEKIT_TOKEN_TTL", 30*time.Minute)

	return cfg
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getEnvDuration(key string, fallback time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil && d > 0 {
			return d
		}
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
