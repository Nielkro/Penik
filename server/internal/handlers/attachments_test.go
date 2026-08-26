package handlers

import (
	"bytes"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"messenger/server/internal/config"
)

func TestUploadAndGetAttachment(t *testing.T) {
	tempDir := t.TempDir()
	cfg := &config.Config{
		UploadDir:     tempDir,
		MaxUploadSize: 10 * 1024 * 1024,
	}

	uploadPayload := []byte("encrypted binary payload with chacha20-poly1305 bytes")

	// 1. Test upload
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("file", "encrypted.bin")
	if err != nil {
		t.Fatalf("failed to create form file: %v", err)
	}
	if _, err := part.Write(uploadPayload); err != nil {
		t.Fatalf("failed to write payload: %v", err)
	}
	writer.Close()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/attachments/upload", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	w := httptest.NewRecorder()

	UploadAttachment(cfg)(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d: %s", w.Code, w.Body.String())
	}

	var resp uploadAttachmentResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to parse response JSON: %v", err)
	}

	if resp.ID == "" || !strings.HasPrefix(resp.URL, "/api/v1/attachments/file/") {
		t.Fatalf("invalid response fields: %+v", resp)
	}

	// Verify file was written to disk
	expectedFile := filepath.Join(tempDir, "attachments", resp.ID+".bin")
	data, err := os.ReadFile(expectedFile)
	if err != nil {
		t.Fatalf("file not found on disk: %v", err)
	}
	if !bytes.Equal(data, uploadPayload) {
		t.Fatalf("file content mismatch: expected %d bytes, got %d bytes", len(uploadPayload), len(data))
	}

	// 2. Test GetAttachment
	getReq := httptest.NewRequest(http.MethodGet, "/api/v1/attachments/file/"+resp.ID, nil)
	getReq.SetPathValue("id", resp.ID)
	getW := httptest.NewRecorder()

	GetAttachment(cfg)(getW, getReq)

	if getW.Code != http.StatusOK {
		t.Fatalf("expected GET status 200, got %d: %s", getW.Code, getW.Body.String())
	}

	if !bytes.Equal(getW.Body.Bytes(), uploadPayload) {
		t.Fatalf("GET content mismatch: expected %s, got %s", uploadPayload, getW.Body.String())
	}

	// 3. Test HTTP Range request
	rangeReq := httptest.NewRequest(http.MethodGet, "/api/v1/attachments/file/"+resp.ID, nil)
	rangeReq.SetPathValue("id", resp.ID)
	rangeReq.Header.Set("Range", "bytes=0-8")
	rangeW := httptest.NewRecorder()

	GetAttachment(cfg)(rangeW, rangeReq)

	if rangeW.Code != http.StatusPartialContent {
		t.Fatalf("expected Range status 206, got %d: %s", rangeW.Code, rangeW.Body.String())
	}
	if !bytes.Equal(rangeW.Body.Bytes(), uploadPayload[0:9]) {
		t.Fatalf("Range content mismatch: expected %s, got %s", uploadPayload[0:9], rangeW.Body.String())
	}
}

func TestGetAttachment_NotFoundAndInvalidID(t *testing.T) {
	tempDir := t.TempDir()
	cfg := &config.Config{
		UploadDir:     tempDir,
		MaxUploadSize: 10 * 1024 * 1024,
	}

	// Invalid ID
	badReq := httptest.NewRequest(http.MethodGet, "/api/v1/attachments/file/invalid..id", nil)
	badReq.SetPathValue("id", "invalid..id")
	badW := httptest.NewRecorder()
	GetAttachment(cfg)(badW, badReq)
	if badW.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for bad ID, got %d", badW.Code)
	}

	// Valid format ID but not found
	missingReq := httptest.NewRequest(http.MethodGet, "/api/v1/attachments/file/0123456789abcdef0123456789abcdef", nil)
	missingReq.SetPathValue("id", "0123456789abcdef0123456789abcdef")
	missingW := httptest.NewRecorder()
	GetAttachment(cfg)(missingW, missingReq)
	if missingW.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for missing file, got %d", missingW.Code)
	}
}
