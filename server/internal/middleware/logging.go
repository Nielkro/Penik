package middleware

import (
	"bufio"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
)

type responseRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (r *responseRecorder) WriteHeader(status int) {
	if r.status != 0 {
		return
	}
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

func (r *responseRecorder) Write(body []byte) (int, error) {
	if r.status == 0 {
		r.WriteHeader(http.StatusOK)
	}
	n, err := r.ResponseWriter.Write(body)
	r.bytes += n
	return n, err
}

func (r *responseRecorder) Unwrap() http.ResponseWriter {
	return r.ResponseWriter
}

func (r *responseRecorder) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	hijacker, ok := r.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, fmt.Errorf("response writer does not support hijacking")
	}
	return hijacker.Hijack()
}

func (r *responseRecorder) Flush() {
	if flusher, ok := r.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

// trustedProxies holds the networks whose X-Forwarded-For / X-Real-IP headers
// are believed. Anything else can set those headers freely, so trusting them
// unconditionally would let a client pick its own rate-limit bucket and forge
// the IP recorded for a device. Configured via TRUSTED_PROXIES (comma-separated
// CIDRs or bare IPs); defaults to loopback and RFC1918 ranges, which covers a
// reverse proxy running on the same host or in the same container network.
var trustedProxies = parseTrustedProxies(os.Getenv("TRUSTED_PROXIES"))

func parseTrustedProxies(raw string) []*net.IPNet {
	entries := strings.Split(raw, ",")
	if strings.TrimSpace(raw) == "" {
		entries = []string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"}
	}

	var nets []*net.IPNet
	for _, entry := range entries {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		if !strings.Contains(entry, "/") {
			// Bare address: treat as a single-host network.
			if ip := net.ParseIP(entry); ip != nil {
				bits := 32
				if ip.To4() == nil {
					bits = 128
				}
				nets = append(nets, &net.IPNet{IP: ip, Mask: net.CIDRMask(bits, bits)})
			}
			continue
		}
		if _, n, err := net.ParseCIDR(entry); err == nil {
			nets = append(nets, n)
		}
	}
	return nets
}

// remoteAddrIP returns the peer address of the TCP connection itself, which no
// client can spoof.
func remoteAddrIP(r *http.Request) string {
	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return ip
}

func isTrustedProxy(addr string) bool {
	ip := net.ParseIP(addr)
	if ip == nil {
		return false
	}
	for _, n := range trustedProxies {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

// ClientIP resolves the originating client IP. Forwarding headers are consulted
// only when the immediate peer is a trusted proxy; otherwise the connection
// address is authoritative.
func ClientIP(r *http.Request) string {
	peer := remoteAddrIP(r)
	if !isTrustedProxy(peer) {
		return peer
	}

	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		// Walk from the right, skipping our own proxy hops. The first address
		// that is not a trusted proxy is the furthest one we can attribute;
		// anything to the left of it was supplied by the client and may be
		// fabricated.
		ips := strings.Split(xff, ",")
		for i := len(ips) - 1; i >= 0; i-- {
			ip := strings.TrimSpace(ips[i])
			if ip == "" || net.ParseIP(ip) == nil {
				continue
			}
			if isTrustedProxy(ip) {
				continue
			}
			return ip
		}
	}
	if xrip := strings.TrimSpace(r.Header.Get("X-Real-IP")); xrip != "" && net.ParseIP(xrip) != nil {
		return xrip
	}
	return peer
}

func getClientIP(r *http.Request) string {
	return ClientIP(r)
}

// RequestLogger logs request metadata without query parameters or bodies.
func RequestLogger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		recorder := &responseRecorder{ResponseWriter: w}

		next.ServeHTTP(recorder, r)

		status := recorder.status
		if status == 0 {
			status = http.StatusOK
		}
		log.Printf(
			"%s %s, %d, %s",
			r.Method,
			r.URL.Path,
			status,
			getClientIP(r),
		)
	})
}
