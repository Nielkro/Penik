package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/ws"
)

func TestReadinessCheckSuccess(t *testing.T) {
	tempDir := t.TempDir()
	dbPath := filepath.Join(tempDir, "health.db")
	database, err := db.Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	uploadDir := filepath.Join(tempDir, "upload")
	if err := os.MkdirAll(uploadDir, 0755); err != nil {
		t.Fatal(err)
	}

	cfg := &config.Config{
		UploadDir: uploadDir,
	}
	hub := ws.NewHub()

	handler := ReadinessCheck(database, cfg, hub)
	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	rr := httptest.NewRecorder()

	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rr.Code, rr.Body.String())
	}

	var resp ReadinessResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.Status != "ok" || resp.DB != "ok" || resp.Storage != "ok" || resp.WS != "ok" {
		t.Fatalf("expected all ok, got %+v", resp)
	}
}

func TestReadinessCheckNilHub(t *testing.T) {
	tempDir := t.TempDir()
	database, err := db.Open(filepath.Join(tempDir, "health.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	uploadDir := filepath.Join(tempDir, "upload")
	_ = os.MkdirAll(uploadDir, 0755)

	cfg := &config.Config{UploadDir: uploadDir}

	handler := ReadinessCheck(database, cfg, nil)
	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	rr := httptest.NewRecorder()

	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d", rr.Code)
	}

	var resp ReadinessResponse
	_ = json.Unmarshal(rr.Body.Bytes(), &resp)
	if resp.Status != "fail" || resp.WS != "fail" {
		t.Fatalf("expected ws fail, got %+v", resp)
	}
}

func TestReadinessCheckNonWritableStorage(t *testing.T) {
	tempDir := t.TempDir()
	database, err := db.Open(filepath.Join(tempDir, "health.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	uploadDir := filepath.Join(tempDir, "non_existent_subdir", "upload")
	cfg := &config.Config{UploadDir: uploadDir}
	hub := ws.NewHub()

	handler := ReadinessCheck(database, cfg, hub)
	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	rr := httptest.NewRecorder()

	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d", rr.Code)
	}

	var resp ReadinessResponse
	_ = json.Unmarshal(rr.Body.Bytes(), &resp)
	if resp.Status != "fail" || resp.Storage != "fail" {
		t.Fatalf("expected storage fail, got %+v", resp)
	}
}
