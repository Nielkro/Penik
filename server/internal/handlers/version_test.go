package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGetVersion(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/v1/version", nil)
	rec := httptest.NewRecorder()

	handler := GetVersion()
	handler(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var resp VersionInfo
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if resp.LatestAndroidVersionCode < 1 {
		t.Errorf("expected LatestAndroidVersionCode >= 1, got %d", resp.LatestAndroidVersionCode)
	}
	if resp.MinCryptoVersion < 1 {
		t.Errorf("expected MinCryptoVersion >= 1, got %d", resp.MinCryptoVersion)
	}
}

func TestGetVersion_RemoteAndFallback(t *testing.T) {
	mockRemote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(VersionInfo{
			MinAndroidVersionCode:    2,
			LatestAndroidVersionCode: 5,
			LatestAndroidVersionName: "2.0.0",
			MinCryptoVersion:         1,
			ApkURL:                   "https://example.com/test.apk",
			ReleaseNotes:             "Mock update",
		})
	}))
	defer mockRemote.Close()

	handler := GetVersion(mockRemote.URL)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/version", nil)
	rec := httptest.NewRecorder()
	handler(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var resp VersionInfo
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if resp.LatestAndroidVersionCode != 5 {
		t.Errorf("expected LatestAndroidVersionCode 5, got %d", resp.LatestAndroidVersionCode)
	}
	if resp.LatestAndroidVersionName != "2.0.0" {
		t.Errorf("expected LatestAndroidVersionName 2.0.0, got %q", resp.LatestAndroidVersionName)
	}
	if resp.ApkURL != "https://example.com/test.apk" {
		t.Errorf("expected ApkURL https://example.com/test.apk, got %q", resp.ApkURL)
	}
}
