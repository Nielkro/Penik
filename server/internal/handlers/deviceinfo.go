package handlers

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

var (
	geoClient = &http.Client{
		Timeout: 2 * time.Second,
	}

	geoCacheMu sync.RWMutex
	geoCache   = make(map[string]geoCacheEntry)

	reservedCIDRs []*net.IPNet
)

type geoCacheEntry struct {
	location string
	expires  time.Time
}

func init() {
	cidrStrings := []string{
		"0.0.0.0/8",
		"10.0.0.0/8",
		"100.64.0.0/10",
		"127.0.0.0/8",
		"169.254.0.0/16",
		"172.16.0.0/12",
		"192.0.0.0/24",
		"192.0.2.0/24",
		"192.88.99.0/24",
		"192.168.0.0/16",
		"198.18.0.0/15",
		"198.51.100.0/24",
		"203.0.113.0/24",
		"224.0.0.0/4",
		"240.0.0.0/4",
		"255.255.255.255/32",
		"::/128",
		"::1/128",
		"fc00::/7",
		"fe80::/10",
	}
	for _, s := range cidrStrings {
		_, netCIDR, err := net.ParseCIDR(s)
		if err == nil {
			reservedCIDRs = append(reservedCIDRs, netCIDR)
		}
	}
}

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

// isPrivateOrLocal reports whether an IP is loopback, private, link-local, or reserved.
func isPrivateOrLocal(ipStr string) bool {
	ip := net.ParseIP(ipStr)
	if ip == nil {
		return true
	}
	if ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
		return true
	}
	for _, cidr := range reservedCIDRs {
		if cidr.Contains(ip) {
			return true
		}
	}
	return false
}

type ipAPIResponse struct {
	Status      string `json:"status"`
	Country     string `json:"country"`
	CountryCode string `json:"countryCode"`
	RegionName  string `json:"regionName"`
	City        string `json:"city"`
}

// locationFromIP resolves an IP address to a human-readable location string,
// caching results in memory to avoid repeated lookups.
func locationFromIP(ip string) string {
	ip = strings.TrimSpace(ip)
	if ip == "" {
		return ""
	}
	if isPrivateOrLocal(ip) {
		return "Локальная сеть"
	}

	geoCacheMu.RLock()
	entry, found := geoCache[ip]
	geoCacheMu.RUnlock()

	if found && time.Now().Before(entry.expires) {
		return entry.location
	}

	loc := fetchLocation(ip)

	geoCacheMu.Lock()
	if len(geoCache) > 10000 {
		geoCache = make(map[string]geoCacheEntry)
	}
	geoCache[ip] = geoCacheEntry{
		location: loc,
		expires:  time.Now().Add(24 * time.Hour),
	}
	geoCacheMu.Unlock()

	return loc
}

func fetchLocation(ip string) string {
	url := fmt.Sprintf("http://ip-api.com/json/%s?lang=ru", ip)
	resp, err := geoClient.Get(url)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return ""
	}

	var data ipAPIResponse
	if err := json.NewDecoder(resp.Body).Decode(&data); err != nil {
		return ""
	}

	if data.Status != "success" {
		return ""
	}

	city := strings.TrimSpace(data.City)
	country := strings.TrimSpace(data.Country)

	if city != "" && country != "" {
		if strings.EqualFold(city, country) || strings.Contains(strings.ToLower(city), strings.ToLower(country)) {
			return city
		}
		return city + ", " + country
	}
	if city != "" {
		return city
	}
	if country != "" {
		return country
	}
	return ""
}

// resolveLocation prefers location derived from request IP, falling back to
// client-provided location if IP resolution is unavailable or empty.
func resolveLocation(clientLocation string, r *http.Request) string {
	ip := clientIP(r)
	if ip != "" {
		loc := locationFromIP(ip)
		if loc != "" {
			return loc
		}
	}
	return strings.TrimSpace(clientLocation)
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
