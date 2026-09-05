package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

func TestUploadAndGetAttachment(t *testing.T) {
	tempDir := t.TempDir()
	cfg := &config.Config{
		UploadDir:     tempDir,
		MaxUploadSize: 10 * 1024 * 1024,
	}
	database, err := db.Open(filepath.Join(tempDir, "test.db"))
	if err != nil {
		t.Fatalf("failed to open test db: %v", err)
	}
	defer database.Close()

	now := time.Now().Unix()
	res, err := database.Exec(`INSERT INTO users(name, nickname, password_hash, created_at) VALUES('Alice', 'alice', 'hash', ?)`, now)
	if err != nil {
		t.Fatalf("failed to create user: %v", err)
	}
	aliceID, _ := res.LastInsertId()

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
	ctx := context.WithValue(req.Context(), middleware.ContextUserID, aliceID)
	req = req.WithContext(ctx)
	w := httptest.NewRecorder()

	UploadAttachment(database, cfg)(w, req)

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

	// 2. Test GetAttachment as uploader (Alice)
	getReq := httptest.NewRequest(http.MethodGet, "/api/v1/attachments/file/"+resp.ID, nil)
	getReq.SetPathValue("id", resp.ID)
	getReq = getReq.WithContext(context.WithValue(getReq.Context(), middleware.ContextUserID, aliceID))
	getW := httptest.NewRecorder()

	GetAttachment(database, cfg)(getW, getReq)

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
	rangeReq = rangeReq.WithContext(context.WithValue(rangeReq.Context(), middleware.ContextUserID, aliceID))
	rangeW := httptest.NewRecorder()

	GetAttachment(database, cfg)(rangeW, rangeReq)

	if rangeW.Code != http.StatusPartialContent {
		t.Fatalf("expected Range status 206, got %d: %s", rangeW.Code, rangeW.Body.String())
	}
	if !bytes.Equal(rangeW.Body.Bytes(), uploadPayload[0:9]) {
		t.Fatalf("Range content mismatch: expected %s, got %s", uploadPayload[0:9], rangeW.Body.String())
	}
}

func TestAttachmentAccessControl(t *testing.T) {
	tempDir := t.TempDir()
	cfg := &config.Config{
		UploadDir:     tempDir,
		MaxUploadSize: 10 * 1024 * 1024,
	}
	database, err := db.Open(filepath.Join(tempDir, "acl_test.db"))
	if err != nil {
		t.Fatalf("failed to open test db: %v", err)
	}
	defer database.Close()

	now := time.Now().Unix()
	resA, _ := database.Exec(`INSERT INTO users(name, nickname, password_hash, created_at) VALUES('Alice', 'alice', 'h', ?)`, now)
	aliceID, _ := resA.LastInsertId()
	resB, _ := database.Exec(`INSERT INTO users(name, nickname, password_hash, created_at) VALUES('Bob', 'bob', 'h', ?)`, now)
	bobID, _ := resB.LastInsertId()
	resC, _ := database.Exec(`INSERT INTO users(name, nickname, password_hash, created_at) VALUES('Charlie', 'charlie', 'h', ?)`, now)
	charlieID, _ := resC.LastInsertId()
	resM, _ := database.Exec(`INSERT INTO users(name, nickname, password_hash, created_at) VALUES('Mallory', 'mallory', 'h', ?)`, now)
	malloryID, _ := resM.LastInsertId()

	// Alice and Bob share a chat
	_, err = database.Exec(`INSERT INTO chats(user1_id, user2_id, created_at) VALUES(?, ?, ?)`, aliceID, bobID, now)
	if err != nil {
		t.Fatalf("failed to create chat: %v", err)
	}

	// Alice and Charlie share a group
	resG, err := database.Exec(`INSERT INTO groups(name, owner_user_id, created_at, updated_at) VALUES('Devs', ?, ?, ?)`, aliceID, now, now)
	if err != nil {
		t.Fatalf("failed to create group: %v", err)
	}
	groupID, _ := resG.LastInsertId()
	_, err = database.Exec(`INSERT INTO group_members(group_id, user_id, role, status, joined_at, membership_version) VALUES(?, ?, 'owner', 'active', ?, 1)`, groupID, aliceID, now)
	if err != nil {
		t.Fatalf("failed to insert alice into group: %v", err)
	}
	_, err = database.Exec(`INSERT INTO group_members(group_id, user_id, role, status, joined_at, membership_version) VALUES(?, ?, 'member', 'active', ?, 1)`, groupID, charlieID, now)
	if err != nil {
		t.Fatalf("failed to insert charlie into group: %v", err)
	}

	// Alice uploads attachment
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, _ := writer.CreateFormFile("file", "secret.bin")
	_, _ = part.Write([]byte("classified contents"))
	writer.Close()

	uploadReq := httptest.NewRequest(http.MethodPost, "/api/v1/attachments/upload", &body)
	uploadReq.Header.Set("Content-Type", writer.FormDataContentType())
	uploadReq = uploadReq.WithContext(context.WithValue(uploadReq.Context(), middleware.ContextUserID, aliceID))
	uploadRec := httptest.NewRecorder()
	UploadAttachment(database, cfg)(uploadRec, uploadReq)
	if uploadRec.Code != http.StatusOK {
		t.Fatalf("upload failed: %d %s", uploadRec.Code, uploadRec.Body.String())
	}
	var resp uploadAttachmentResponse
	_ = json.Unmarshal(uploadRec.Body.Bytes(), &resp)

	// 1. Bob (chat peer) can access
	reqBob := httptest.NewRequest(http.MethodGet, "/api/v1/attachments/file/"+resp.ID, nil)
	reqBob.SetPathValue("id", resp.ID)
	reqBob = reqBob.WithContext(context.WithValue(reqBob.Context(), middleware.ContextUserID, bobID))
	wBob := httptest.NewRecorder()
	GetAttachment(database, cfg)(wBob, reqBob)
	if wBob.Code != http.StatusOK {
		t.Errorf("expected Bob (chat peer) to get 200, got %d", wBob.Code)
	}

	// 2. Charlie (group peer) can access
	reqCharlie := httptest.NewRequest(http.MethodGet, "/api/v1/attachments/file/"+resp.ID, nil)
	reqCharlie.SetPathValue("id", resp.ID)
	reqCharlie = reqCharlie.WithContext(context.WithValue(reqCharlie.Context(), middleware.ContextUserID, charlieID))
	wCharlie := httptest.NewRecorder()
	GetAttachment(database, cfg)(wCharlie, reqCharlie)
	if wCharlie.Code != http.StatusOK {
		t.Errorf("expected Charlie (group peer) to get 200, got %d", wCharlie.Code)
	}

	// 3. Mallory (stranger) is rejected with 403 Forbidden
	reqMallory := httptest.NewRequest(http.MethodGet, "/api/v1/attachments/file/"+resp.ID, nil)
	reqMallory.SetPathValue("id", resp.ID)
	reqMallory = reqMallory.WithContext(context.WithValue(reqMallory.Context(), middleware.ContextUserID, malloryID))
	wMallory := httptest.NewRecorder()
	GetAttachment(database, cfg)(wMallory, reqMallory)
	if wMallory.Code != http.StatusForbidden {
		t.Errorf("expected Mallory (stranger) to get 403 Forbidden, got %d", wMallory.Code)
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
	GetAttachment(nil, cfg)(badW, badReq)
	if badW.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for bad ID, got %d", badW.Code)
	}

	// Valid format ID but not found
	missingReq := httptest.NewRequest(http.MethodGet, "/api/v1/attachments/file/0123456789abcdef0123456789abcdef", nil)
	missingReq.SetPathValue("id", "0123456789abcdef0123456789abcdef")
	missingW := httptest.NewRecorder()
	GetAttachment(nil, cfg)(missingW, missingReq)
	if missingW.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for missing file, got %d", missingW.Code)
	}
}

func TestGetAttachment_OrphanRejected(t *testing.T) {
	tempDir := t.TempDir()
	cfg := &config.Config{
		UploadDir:     tempDir,
		MaxUploadSize: 10 * 1024 * 1024,
	}
	dbPath := filepath.Join(tempDir, "test.db")
	database, err := db.Open(dbPath)
	if err != nil {
		t.Fatalf("failed to open test db: %v", err)
	}
	defer database.Close()

	// Write an orphan file directly to disk without database entry
	attDir := filepath.Join(tempDir, "attachments")
	_ = os.MkdirAll(attDir, 0700)
	orphanID := "fedcba9876543210fedcba9876543210"
	_ = os.WriteFile(filepath.Join(attDir, orphanID+".bin"), []byte("orphan content"), 0600)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/attachments/file/"+orphanID, nil)
	req.SetPathValue("id", orphanID)
	req = req.WithContext(context.WithValue(req.Context(), middleware.ContextUserID, int64(42)))
	rec := httptest.NewRecorder()
	GetAttachment(database, cfg)(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for orphan attachment on disk without DB record, got %d", rec.Code)
	}
}


