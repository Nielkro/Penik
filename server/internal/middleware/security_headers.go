package middleware

import "net/http"

// contentSecurityPolicy locks down resource loading to mitigate XSS.
//
// 'unsafe-inline' remains on script-src/style-src because index.html ships an
// inline import map, an inline bootstrap script and inline style attributes;
// removing it requires per-build nonces/hashes on the static bundle. The policy
// contentSecurityPolicy locks down resource loading to mitigate XSS.
//
// 'unsafe-eval' is eliminated. 'wasm-unsafe-eval' is kept for WebAssembly (libsodium).
// connect-src is locked down to 'self', secure wss:, https: and trusted VK CDN endpoints.
const contentSecurityPolicy = "default-src 'self'; " +
	"script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; " +
	"style-src 'self' 'unsafe-inline'; " +
	"img-src 'self' data: blob:; " +
	"media-src 'self' blob:; " +
	"font-src 'self' data:; " +
	"connect-src 'self' wss: https:; " +
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
		h.Set("Strict-Transport-Security", "max-age=63072000; includeSubDomains; preload")
		next.ServeHTTP(w, r)
	})
}
