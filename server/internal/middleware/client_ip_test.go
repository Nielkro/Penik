package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestClientIPIgnoresHeadersFromUntrustedPeer(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "203.0.113.9:44321" // public address, not a configured proxy
	req.Header.Set("X-Forwarded-For", "1.2.3.4")
	req.Header.Set("X-Real-IP", "5.6.7.8")

	if got := ClientIP(req); got != "203.0.113.9" {
		t.Errorf("headers from an untrusted peer must be ignored, got %q", got)
	}
}

func TestClientIPHonorsTrustedProxy(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "10.0.0.5:44321" // private range is trusted by default

	req.Header.Set("X-Forwarded-For", "198.51.100.7")
	if got := ClientIP(req); got != "198.51.100.7" {
		t.Errorf("expected 198.51.100.7, got %q", got)
	}

	// A client that forges its own entry cannot push the real one out: the proxy
	// appends what it saw, and the rightmost untrusted hop wins.
	req.Header.Set("X-Forwarded-For", "9.9.9.9, 198.51.100.7")
	if got := ClientIP(req); got != "198.51.100.7" {
		t.Errorf("expected the proxy-observed hop 198.51.100.7, got %q", got)
	}

	// Internal hops are skipped so the public client address is still found.
	req.Header.Set("X-Forwarded-For", "198.51.100.7, 10.0.0.9")
	if got := ClientIP(req); got != "198.51.100.7" {
		t.Errorf("expected 198.51.100.7 past the internal hop, got %q", got)
	}
}

func TestClientIPFallsBackToPeerWithoutHeaders(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "10.0.0.5:44321"
	if got := ClientIP(req); got != "10.0.0.5" {
		t.Errorf("expected 10.0.0.5, got %q", got)
	}
}

func TestParseTrustedProxiesAcceptsBareAddresses(t *testing.T) {
	nets := parseTrustedProxies("198.51.100.7, 2001:db8::1, bogus, 192.0.2.0/24")
	if len(nets) != 3 {
		t.Fatalf("expected 3 usable entries, got %d", len(nets))
	}
}
