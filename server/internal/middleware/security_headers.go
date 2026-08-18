package middleware

import "net/http"

// contentSecurityPolicy locks down resource loading to mitigate XSS.
//
// 'unsafe-inline' remains on script-src/style-src because index.html ships an
// inline import map, an inline bootstrap script and inline style attributes;
// removing it requires per-build nonces/hashes on the static bundle. The policy
// still meaningfully reduces the blast radius: no external code, no framing,
// no base-tag hijack, and connections limited to same-origin (REST + ws/wss).
const contentSecurityPolicy = "default-src 'self'; " +
	"script-src 'self' 'unsafe-inline'; " +
	"style-src 'self' 'unsafe-inline'; " +
	"img-src 'self' data: blob:; " +
	"media-src 'self' blob:; " +
	"font-src 'self' data:; " +
	"connect-src 'self' ws: wss:; " +
	"object-src 'none'; " +
	"base-uri 'self'; " +
	"form-action 'self'; " +
	"frame-ancestors 'none'"

// SecurityHeaders sets defensive HTTP response headers on every request.
func SecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", contentSecurityPolicy)
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}
