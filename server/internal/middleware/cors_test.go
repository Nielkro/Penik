package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"messenger/server/internal/config"
)

func corsHandler(origins string) http.Handler {
	return CORS(&config.Config{AllowedOrigins: origins})(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
}

// An empty or wildcard allow-list must mean deny-all, not allow-all: startup
// validation already rejects it, and a bypass here would reopen the CSRF hole.
func TestCORSWildcardListIsNotHonoured(t *testing.T) {
	for _, origins := range []string{"", "*", " * , ", "http://a.example,*"} {
		h := corsHandler(origins)

		rec := httptest.NewRecorder()
		r := httptest.NewRequest(http.MethodGet, "/api/v1/devices", nil)
		r.Header.Set("Origin", "https://evil.example")
		h.ServeHTTP(rec, r)
		if rec.Code != http.StatusForbidden {
			t.Errorf("origins=%q GET from evil: got %d, want 403", origins, rec.Code)
		}
		if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
			t.Errorf("origins=%q leaked Allow-Origin %q", origins, got)
		}

		// A mutating request whose Referer points elsewhere is a CSRF attempt.
		rec = httptest.NewRecorder()
		r = httptest.NewRequest(http.MethodPost, "/api/v1/messages/send", nil)
		r.Header.Set("Referer", "https://evil.example/attack")
		h.ServeHTTP(rec, r)
		if rec.Code != http.StatusForbidden {
			t.Errorf("origins=%q foreign referer POST: got %d, want 403", origins, rec.Code)
		}
	}
}

func TestCORSAllowsConfiguredOrigin(t *testing.T) {
	h := corsHandler("https://penik.example,https://web.penik.example")

	rec := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodPost, "/api/v1/messages/send", nil)
	r.Header.Set("Origin", "https://web.penik.example")
	h.ServeHTTP(rec, r)
	if rec.Code != http.StatusOK {
		t.Fatalf("allowed origin: got %d, want 200", rec.Code)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "https://web.penik.example" {
		t.Fatalf("Allow-Origin = %q", got)
	}
	if rec.Header().Get("Access-Control-Allow-Credentials") != "true" {
		t.Fatal("credentials not allowed for a configured origin")
	}

	// Preflight from a configured origin short-circuits with 204.
	rec = httptest.NewRecorder()
	r = httptest.NewRequest(http.MethodOptions, "/api/v1/messages/send", nil)
	r.Header.Set("Origin", "https://penik.example")
	h.ServeHTTP(rec, r)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("preflight: got %d, want 204", rec.Code)
	}

	// Referer with allowed origin as prefix must be rejected (M8 bypass prevention)
	rec = httptest.NewRecorder()
	r = httptest.NewRequest(http.MethodPost, "/api/v1/messages/send", nil)
	r.Header.Set("Referer", "https://penik.example.attacker.com/csrf")
	h.ServeHTTP(rec, r)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("prefix bypass referer: got %d, want 403", rec.Code)
	}
}
