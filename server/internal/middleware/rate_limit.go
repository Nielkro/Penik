package middleware

import (
	"net/http"
	"strconv"
	"sync"
	"time"
)

// pruneWindows drops keys whose whole window has expired. Without it the maps
// grow without bound, so a stream of distinct source addresses would slowly
// exhaust memory.
func pruneWindows(m map[string][]time.Time, threshold time.Time) {
	for key, times := range m {
		fresh := false
		for _, t := range times {
			if t.After(threshold) {
				fresh = true
				break
			}
		}
		if !fresh {
			delete(m, key)
		}
	}
}

type IPRateLimiter struct {
	mu  sync.Mutex
	ips map[string][]time.Time
}

func NewIPRateLimiter() *IPRateLimiter {
	return &IPRateLimiter{
		ips: make(map[string][]time.Time),
	}
}

func (l *IPRateLimiter) Limit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := getClientIP(r)

		l.mu.Lock()
		now := time.Now()
		threshold := now.Add(-1 * time.Minute)

		if len(l.ips) > 1024 {
			pruneWindows(l.ips, threshold)
		}

		// Clean up old entries
		reqs := l.ips[ip]
		var active []time.Time
		for _, t := range reqs {
			if t.After(threshold) {
				active = append(active, t)
			}
		}

		// Limit to 10 requests per minute for auth endpoints
		if len(active) >= 10 {
			l.mu.Unlock()
			http.Error(w, "Too many requests. Please try again in a minute.", http.StatusTooManyRequests)
			return
		}

		active = append(active, now)
		l.ips[ip] = active
		l.mu.Unlock()

		next.ServeHTTP(w, r)
	})
}

// UserRateLimiter throttles authenticated requests per user within a sliding
// window. Group mutations (rotate, invite, remove, create, envelope upload) are
// expensive fan-outs, so the plan (§10) calls for bounding their frequency. It
// keys on the authenticated user id, falling back to the client IP when the
// request is unauthenticated, so it must run after the auth middleware.
type UserRateLimiter struct {
	mu     sync.Mutex
	hits   map[string][]time.Time
	max    int
	window time.Duration
}

func NewUserRateLimiter(max int, window time.Duration) *UserRateLimiter {
	return &UserRateLimiter{
		hits:   make(map[string][]time.Time),
		max:    max,
		window: window,
	}
}

func (l *UserRateLimiter) Limit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		key := "ip:" + getClientIP(r)
		if uid := UserIDFromCtx(r.Context()); uid != 0 {
			key = "user:" + strconv.FormatInt(uid, 10)
		}

		l.mu.Lock()
		now := time.Now()
		threshold := now.Add(-l.window)
		if len(l.hits) > 1024 {
			pruneWindows(l.hits, threshold)
		}
		var active []time.Time
		for _, t := range l.hits[key] {
			if t.After(threshold) {
				active = append(active, t)
			}
		}
		if len(active) >= l.max {
			l.mu.Unlock()
			http.Error(w, "Too many requests. Please slow down.", http.StatusTooManyRequests)
			return
		}
		l.hits[key] = append(active, now)
		l.mu.Unlock()

		next.ServeHTTP(w, r)
	})
}
