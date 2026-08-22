package middleware

import (
	"net/http"
	"strings"

	"messenger/server/internal/config"
)

// CORS returns a middleware that enforces a strict same-origin CORS policy.
// ALLOWED_ORIGINS is validated at startup, so an empty allow-list here means a
// misconfiguration: every cross-origin request is rejected rather than waved
// through, and the CSRF check always runs.
func CORS(cfg *config.Config) func(http.Handler) http.Handler {
	allowedList := parseOrigins(cfg.AllowedOrigins)

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")

			// Always vary on Origin so caches don't serve wrong CORS headers.
			w.Header().Add("Vary", "Origin")

			if origin != "" {
				if isAllowedOrigin(allowedList, origin) {
					w.Header().Set("Access-Control-Allow-Origin", origin)
					w.Header().Set("Access-Control-Allow-Credentials", "true")
				} else {
					// Unknown origin — reject immediately.
					http.Error(w, "CORS: origin not allowed", http.StatusForbidden)
					return
				}
			}

			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
			w.Header().Set("Access-Control-Max-Age", "86400")

			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}

			// CSRF check: for state-mutating requests that carry cookies or are
			// non-idempotent, verify the Origin/Referer header matches allowed list.
			if isMutating(r.Method) {
				if !csrfOK(r, allowedList) {
					http.Error(w, "CSRF: invalid origin", http.StatusForbidden)
					return
				}
			}

			next.ServeHTTP(w, r)
		})
	}
}

// parseOrigins splits the configured list. A "*" entry is dropped rather than
// honoured: config validation rejects it, and silently treating it as a wildcard
// would reopen the hole this list exists to close.
func parseOrigins(raw string) []string {
	var out []string
	for _, o := range strings.Split(raw, ",") {
		if s := strings.TrimSpace(o); s != "" && s != "*" {
			out = append(out, s)
		}
	}
	return out
}

func isAllowedOrigin(allowed []string, origin string) bool {
	for _, a := range allowed {
		if a == origin {
			return true
		}
	}
	return false
}

func isMutating(method string) bool {
	switch method {
	case http.MethodGet, http.MethodHead, http.MethodOptions:
		return false
	}
	return true
}

func csrfOK(r *http.Request, allowed []string) bool {
	if origin := r.Header.Get("Origin"); origin != "" {
		return isAllowedOrigin(allowed, origin)
	}
	if referer := r.Header.Get("Referer"); referer != "" {
		for _, a := range allowed {
			if strings.HasPrefix(referer, a) {
				return true
			}
		}
		return false
	}
	// No Origin/Referer — allow (same-origin curl/mobile clients).
	return true
}

func CORSPreflightMiddleware(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
}

