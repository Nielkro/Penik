package config

import "testing"

// A wildcard origin list disables the CSRF check and the WebSocket origin check,
// so it is refused in every environment, not only in production.
func TestValidate_RejectsWildcardEverywhere(t *testing.T) {
	for _, origins := range []string{"*", "", "   ", "http://localhost:5173,*"} {
		cfg := &Config{Env: "development", AllowedOrigins: origins, LiveKitURL: "wss://livekit.home.penik.ru", LiveKitFallbackURL: "wss://call.api.penik.ru"}
		if err := cfg.Validate(); err == nil {
			t.Errorf("development must reject AllowedOrigins %q", origins)
		}
	}
}

func TestValidate_DevelopmentAllowsPlainHTTPOrigin(t *testing.T) {
	cfg := &Config{Env: "development", AllowedOrigins: "http://localhost:5173", LiveKitURL: "wss://livekit.home.penik.ru", LiveKitFallbackURL: "wss://call.api.penik.ru"}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("development should allow an explicit http origin, got %v", err)
	}
}

func TestValidate_ProductionRejectsWildcard(t *testing.T) {
	for _, origins := range []string{"*", "", "  ", "https://a.com,*"} {
		cfg := &Config{Env: "production", AllowedOrigins: origins, LiveKitURL: "wss://livekit.home.penik.ru", LiveKitFallbackURL: "wss://call.api.penik.ru"}
		if err := cfg.Validate(); err == nil {
			t.Errorf("production must reject AllowedOrigins %q", origins)
		}
	}
}

func TestValidate_ProductionRejectsNonHTTPS(t *testing.T) {
	cfg := &Config{Env: "production", AllowedOrigins: "http://insecure.example", LiveKitURL: "wss://livekit.home.penik.ru", LiveKitFallbackURL: "wss://call.api.penik.ru"}
	if err := cfg.Validate(); err == nil {
		t.Error("production must reject non-https origin")
	}
}

func TestValidate_ProductionAcceptsExplicitHTTPS(t *testing.T) {
	cfg := &Config{Env: "prod", AllowedOrigins: "https://app.example, https://www.example", LiveKitURL: "wss://livekit.home.penik.ru", LiveKitFallbackURL: "wss://call.api.penik.ru", LiveKitAPIKey: "APIrealkey", LiveKitAPISecret: "realsecretvalue"}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("explicit https list should pass, got %v", err)
	}
}

func TestValidate_RejectsEmptyLiveKitURLs(t *testing.T) {
	cfgNoURL := &Config{Env: "development", AllowedOrigins: "http://localhost:5173", LiveKitURL: "", LiveKitFallbackURL: "wss://call.api.penik.ru"}
	if err := cfgNoURL.Validate(); err == nil {
		t.Error("Validate should fail when LiveKitURL is empty")
	}

	cfgNoFallback := &Config{Env: "development", AllowedOrigins: "http://localhost:5173", LiveKitURL: "wss://livekit.home.penik.ru", LiveKitFallbackURL: ""}
	if err := cfgNoFallback.Validate(); err == nil {
		t.Error("Validate should fail when LiveKitFallbackURL is empty")
	}
}

func TestIsProduction(t *testing.T) {
	cases := map[string]bool{
		"production": true, "prod": true, "PRODUCTION": true,
		"development": false, "": false, "staging": false,
	}
	for env, want := range cases {
		if got := (&Config{Env: env}).IsProduction(); got != want {
			t.Errorf("IsProduction(%q) = %v, want %v", env, got, want)
		}
	}
}

func TestValidate_ProductionRejectsDefaultLiveKitCredentials(t *testing.T) {
	base := func() *Config {
		return &Config{
			Env:                "production",
			AllowedOrigins:     "https://app.example",
			LiveKitURL:         "wss://livekit.home.penik.ru",
			LiveKitFallbackURL: "wss://call.api.penik.ru",
			LiveKitAPIKey:      "APIrealkey",
			LiveKitAPISecret:   "realsecretvalue",
		}
	}

	if err := base().Validate(); err != nil {
		t.Fatalf("real credentials should pass, got %v", err)
	}

	for _, mutate := range []func(*Config){
		func(c *Config) { c.LiveKitAPIKey = "devkey" },
		func(c *Config) { c.LiveKitAPISecret = "secret" },
		func(c *Config) { c.LiveKitAPIKey = "DevKey" },
		func(c *Config) { c.LiveKitAPIKey = "" },
		func(c *Config) { c.LiveKitAPISecret = "  " },
	} {
		cfg := base()
		mutate(cfg)
		if err := cfg.Validate(); err == nil {
			t.Errorf("production must reject placeholder credentials (key=%q secret=%q)", cfg.LiveKitAPIKey, cfg.LiveKitAPISecret)
		}
	}
}
