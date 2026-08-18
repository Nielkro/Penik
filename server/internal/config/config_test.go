package config

import "testing"

func TestValidate_DevelopmentAllowsWildcard(t *testing.T) {
	cfg := &Config{Env: "development", AllowedOrigins: "*"}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("development should allow wildcard, got %v", err)
	}
}

func TestValidate_ProductionRejectsWildcard(t *testing.T) {
	for _, origins := range []string{"*", "", "  ", "https://a.com,*"} {
		cfg := &Config{Env: "production", AllowedOrigins: origins}
		if err := cfg.Validate(); err == nil {
			t.Errorf("production must reject AllowedOrigins %q", origins)
		}
	}
}

func TestValidate_ProductionRejectsNonHTTPS(t *testing.T) {
	cfg := &Config{Env: "production", AllowedOrigins: "http://insecure.example"}
	if err := cfg.Validate(); err == nil {
		t.Error("production must reject non-https origin")
	}
}

func TestValidate_ProductionAcceptsExplicitHTTPS(t *testing.T) {
	cfg := &Config{Env: "prod", AllowedOrigins: "https://app.example, https://www.example"}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("explicit https list should pass, got %v", err)
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
