package handlers

import (
	"net"
	"net/http"
	"strings"
)

// clientIP extracts the best-guess originating IP for a request, honoring a
// single X-Forwarded-For hop from a trusted reverse proxy before falling back
// to the raw connection address.
func clientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		if ip := strings.TrimSpace(parts[0]); ip != "" {
			return ip
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// resolvePlatform prefers the client-supplied platform string and falls back to
// parsing the request User-Agent when the client sent none.
func resolvePlatform(clientPlatform string, r *http.Request) string {
	if strings.TrimSpace(clientPlatform) != "" {
		return clientPlatform
	}
	return platformFromUserAgent(r.UserAgent())
}

// platformFromUserAgent derives a coarse, human-readable platform label from a
// browser User-Agent when the client did not send an explicit platform string.
func platformFromUserAgent(ua string) string {
	if ua == "" {
		return ""
	}
	lower := strings.ToLower(ua)

	var os string
	switch {
	case strings.Contains(lower, "android"):
		os = "Android"
	case strings.Contains(lower, "windows"):
		os = "Windows"
	case strings.Contains(lower, "iphone"), strings.Contains(lower, "ipad"):
		os = "iOS"
	case strings.Contains(lower, "mac os"), strings.Contains(lower, "macintosh"):
		os = "macOS"
	case strings.Contains(lower, "linux"):
		os = "Linux"
	}

	var browser string
	switch {
	case strings.Contains(lower, "firefox"):
		browser = "Firefox"
	case strings.Contains(lower, "edg"):
		browser = "Edge"
	case strings.Contains(lower, "chrome"):
		browser = "Chrome"
	case strings.Contains(lower, "safari"):
		browser = "Safari"
	}

	switch {
	case os != "" && browser != "":
		return os + " · " + browser
	case os != "":
		return os
	case browser != "":
		return browser
	default:
		return "Веб-клиент"
	}
}
