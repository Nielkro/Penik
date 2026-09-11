package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestGetServerTime(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/v1/time", nil)
	rec := httptest.NewRecorder()

	beforeSec := time.Now().Unix()
	beforeMs := time.Now().UnixMilli()

	handler := GetServerTime()
	handler(rec, req)

	afterSec := time.Now().Unix()
	afterMs := time.Now().UnixMilli()

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var resp ServerTimeResponse
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if resp.ServerTime < beforeSec || resp.ServerTime > afterSec {
		t.Errorf("server_time %d not within [%d, %d]", resp.ServerTime, beforeSec, afterSec)
	}
	if resp.ServerTimeMs < beforeMs || resp.ServerTimeMs > afterMs {
		t.Errorf("server_time_ms %d not within [%d, %d]", resp.ServerTimeMs, beforeMs, afterMs)
	}
}
