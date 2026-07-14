package middleware

import (
	"net/http"
	"strings"

	"messenger/server/internal/config"
)

// CORS returns a middleware that sets CORS headers and handles preflight.
func CORS(cfg *config.Config) func(http.Handler) http.Handler {
	origins := cfg.AllowedOrigins
	if origins == "" {
		origins = "*"
	}

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			if origins == "*" {
				w.Header().Set("Access-Control-Allow-Origin", "*")
			} else {
				allowed := strings.Split(origins, ",")
				for _, o := range allowed {
					if strings.TrimSpace(o) == origin {
						w.Header().Set("Access-Control-Allow-Origin", origin)
						break
					}
				}
			}
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
			w.Header().Set("Access-Control-Max-Age", "86400")

			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}

			next.ServeHTTP(w, r)
		})
	}
}

func CORSPreflightMiddleware(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
}
