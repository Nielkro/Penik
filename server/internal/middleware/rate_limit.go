package middleware

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type IPRateLimiter struct {
	mu  sync.Mutex
	ips map[string][]time.Time
}

func NewIPRateLimiter() *IPRateLimiter {
	return &IPRateLimiter{
		ips: make(map[string][]time.Time),
	}
}

func getClientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		ips := strings.Split(xff, ",")
		if len(ips) > 0 {
			ip := strings.TrimSpace(ips[0])
			if ip != "" {
				return ip
			}
		}
	}
	if xrip := r.Header.Get("X-Real-IP"); xrip != "" {
		return strings.TrimSpace(xrip)
	}
	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return ip
}

func (l *IPRateLimiter) Limit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := getClientIP(r)

		l.mu.Lock()
		now := time.Now()
		threshold := now.Add(-1 * time.Minute)

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
