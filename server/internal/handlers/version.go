package handlers

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"
)

// VersionInfo holds client update and crypto compatibility requirements.
type VersionInfo struct {
	MinAndroidVersionCode    int    `json:"min_android_version_code"`
	LatestAndroidVersionCode int    `json:"latest_android_version_code"`
	LatestAndroidVersionName string `json:"latest_android_version_name"`
	MinCryptoVersion         int    `json:"min_crypto_version"`
	ApkURL                   string `json:"apk_url"`
	ReleaseNotes             string `json:"release_notes"`
}

type versionCacheState struct {
	sync.RWMutex
	info        VersionInfo
	lastUpdated time.Time
}

var defaultVersionInfo = VersionInfo{
	MinAndroidVersionCode:    1,
	LatestAndroidVersionCode: 1,
	LatestAndroidVersionName: "1.0.0",
	MinCryptoVersion:         1,
	ApkURL:                   "https://github.com/Nielkro/Penik/releases/download/android-v1.0.0/app-debug.apk",
	ReleaseNotes:             "Релиз Penik Messenger с поддержкой E2EE (X25519 + ***REDACTED-BY-FILTER-REPO***) и звонков.",
}

// GetVersion returns an http.HandlerFunc that serves client update policy.
// It caches remote version info for 5 minutes and falls back to cached/default values on network errors.
func GetVersion(sourceURLs ...string) http.HandlerFunc {
	sourceURL := ""
	if len(sourceURLs) > 0 {
		sourceURL = sourceURLs[0]
	}

	cache := &versionCacheState{
		info: defaultVersionInfo,
	}

	httpClient := &http.Client{
		Timeout: 4 * time.Second,
	}

	fetchRemote := func() {
		if sourceURL == "" {
			return
		}
		req, err := http.NewRequest(http.MethodGet, sourceURL, nil)
		if err != nil {
			return
		}
		req.Header.Set("User-Agent", "PenikServer/1.0")

		resp, err := httpClient.Do(req)
		if err != nil {
			log.Printf("[version] failed to fetch remote version from %s: %v", sourceURL, err)
			return
		}
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			log.Printf("[version] remote version server returned status %d", resp.StatusCode)
			return
		}

		var fresh VersionInfo
		if err := json.NewDecoder(resp.Body).Decode(&fresh); err != nil {
			log.Printf("[version] failed to decode remote version json: %v", err)
			return
		}

		cache.Lock()
		cache.info = fresh
		cache.lastUpdated = time.Now()
		cache.Unlock()
	}

	// Attempt initial fetch synchronously if sourceURL is set
	if sourceURL != "" {
		fetchRemote()
	}

	return func(w http.ResponseWriter, r *http.Request) {
		cache.RLock()
		isStale := time.Since(cache.lastUpdated) > 5*time.Minute
		current := cache.info
		cache.RUnlock()

		if isStale && sourceURL != "" {
			go fetchRemote()
		}

		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		_ = json.NewEncoder(w).Encode(current)
	}
}
