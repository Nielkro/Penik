package handlers

import (
	"encoding/json"
	"net/http"
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

// GetVersion returns the current version policy for client updates.
func GetVersion() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		info := VersionInfo{
			MinAndroidVersionCode:    1,
			LatestAndroidVersionCode: 2,
			LatestAndroidVersionName: "1.2.0",
			MinCryptoVersion:         1,
			ApkURL:                   "https://penik.ru/download",
			ReleaseNotes:             "Поддержка улучшенного шифрования AAD v2 и исправления стабильности.",
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-cache")
		_ = json.NewEncoder(w).Encode(info)
	}
}
