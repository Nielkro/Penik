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
