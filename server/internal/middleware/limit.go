package middleware

import (
	"net/http"
	"strings"
)

// MaxBodySize limits the request body to maxBytes. Paths listed in overrides get
// their own (typically larger) allowance, so a single attachment endpoint can
// accept big uploads without letting every other handler buffer that much.
func MaxBodySize(maxBytes int64, overrides map[string]int64) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			limit := maxBytes
			for prefix, override := range overrides {
				if strings.HasPrefix(r.URL.Path, prefix) && override > limit {
					limit = override
					break
				}
			}
			r.Body = http.MaxBytesReader(w, r.Body, limit)
			next.ServeHTTP(w, r)
		})
	}
}
