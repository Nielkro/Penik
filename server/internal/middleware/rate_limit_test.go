package middleware

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func serve(h http.Handler, uid int64, ip string) int {
	r := httptest.NewRequest("POST", "/x", nil)
	if ip != "" {
		r.RemoteAddr = ip + ":12345"
	}
	if uid != 0 {
		r = r.WithContext(context.WithValue(r.Context(), ContextUserID, uid))
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w.Code
}

func TestUserRateLimiterBlocksOverLimit(t *testing.T) {
	ok := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	l := NewUserRateLimiter(3, time.Minute)
	h := l.Limit(ok)

	for i := range 3 {
		if code := serve(h, 7, "1.1.1.1"); code != http.StatusOK {
			t.Fatalf("request %d: expected 200 got %d", i, code)
		}
	}
	if code := serve(h, 7, "1.1.1.1"); code != http.StatusTooManyRequests {
		t.Fatalf("4th request: expected 429 got %d", code)
	}
}

func TestUserRateLimiterIsolatesUsers(t *testing.T) {
	ok := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	l := NewUserRateLimiter(2, time.Minute)
	h := l.Limit(ok)

	// User 1 exhausts its budget.
	serve(h, 1, "1.1.1.1")
	serve(h, 1, "1.1.1.1")
	if code := serve(h, 1, "1.1.1.1"); code != http.StatusTooManyRequests {
		t.Fatalf("user1 over limit: expected 429 got %d", code)
	}
	// User 2, same IP, still has a fresh budget (keyed on user id).
	if code := serve(h, 2, "1.1.1.1"); code != http.StatusOK {
		t.Fatalf("user2 first request: expected 200 got %d", code)
	}
}

func TestUserRateLimiterFallsBackToIP(t *testing.T) {
	ok := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	l := NewUserRateLimiter(1, time.Minute)
	h := l.Limit(ok)

	// Unauthenticated (uid==0): keyed on IP.
	if code := serve(h, 0, "9.9.9.9"); code != http.StatusOK {
		t.Fatalf("first anon: expected 200 got %d", code)
	}
	if code := serve(h, 0, "9.9.9.9"); code != http.StatusTooManyRequests {
		t.Fatalf("second anon same IP: expected 429 got %d", code)
	}
	// Different IP is independent.
	if code := serve(h, 0, "8.8.8.8"); code != http.StatusOK {
		t.Fatalf("different anon IP: expected 200 got %d", code)
	}
}

func TestUserRateLimiterWindowExpiry(t *testing.T) {
	ok := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	// Tiny window so old hits fall out immediately.
	l := NewUserRateLimiter(1, 10*time.Millisecond)
	h := l.Limit(ok)

	if code := serve(h, 5, "1.1.1.1"); code != http.StatusOK {
		t.Fatalf("first: expected 200 got %d", code)
	}
	if code := serve(h, 5, "1.1.1.1"); code != http.StatusTooManyRequests {
		t.Fatalf("second within window: expected 429 got %d", code)
	}
	time.Sleep(15 * time.Millisecond)
	if code := serve(h, 5, "1.1.1.1"); code != http.StatusOK {
		t.Fatalf("after window expiry: expected 200 got %d", code)
	}
}
