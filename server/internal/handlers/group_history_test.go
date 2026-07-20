package handlers

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

// uploadHistory posts a history packet for one device and returns the recorder.
func uploadHistory(database *db.DB, groupID, userID, deviceID, targetDevice int64, blob string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	req := historyPacketUploadRequest{Packets: []historyPacketItem{
		{DeviceID: targetDevice, EncryptedHistory: b64(blob), Salt: b64("s"), Nonce: b64("n")},
	}}
	r := as("POST", "/h", userID, deviceID, req)
	r.SetPathValue("group_id", itoa(groupID))
	UploadGroupHistoryPackets(database, nil)(w, r)
	return w
}

func fetchHistory(database *db.DB, groupID, userID, deviceID int64) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	r := as("GET", "/h", userID, deviceID, nil)
	r.SetPathValue("group_id", itoa(groupID))
	GetGroupHistoryPacket(database)(w, r)
	return w
}

// TestHistoryPacketUploadFetchDeleteOnFetch covers the happy path: an admin
// stages a packet for a pending invitee's device, the invitee accepts, fetches
// it once (200 with the ciphertext), and a second fetch is 404 (delete-on-fetch).
func TestHistoryPacketUploadFetchDeleteOnFetch(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "hist.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")

	w := httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: "T", MemberUserIDs: []int64{bobID}}))
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	groupID := int64(created["id"].(float64))

	// Owner stages a packet for bob's pending device.
	if w := uploadHistory(database, groupID, ownerID, ownerDev, bobDev, "backlog"); w.Code != http.StatusNoContent {
		t.Fatalf("upload: expected 204 got %d body=%s", w.Code, w.Body.String())
	}

	// Bob still pending: fetch gated behind active membership.
	if w := fetchHistory(database, groupID, bobID, bobDev); w.Code != http.StatusForbidden {
		t.Fatalf("pending fetch: expected 403 got %d", w.Code)
	}

	// Bob accepts, then fetches his packet.
	wa := httptest.NewRecorder()
	ra := as("POST", "/a", bobID, bobDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database)(wa, ra)

	w = fetchHistory(database, groupID, bobID, bobDev)
	if w.Code != http.StatusOK {
		t.Fatalf("fetch: expected 200 got %d body=%s", w.Code, w.Body.String())
	}
	var got map[string]any
	json.Unmarshal(w.Body.Bytes(), &got)
	if dec, _ := base64.RawURLEncoding.DecodeString(got["encrypted_history"].(string)); string(dec) != "backlog" {
		t.Fatalf("wrong history: %q", dec)
	}
	if int64(got["sender_device_id"].(float64)) != ownerDev {
		t.Fatalf("wrong sender device: %v", got["sender_device_id"])
	}

	// Second fetch: nothing left (delete-on-fetch).
	if w := fetchHistory(database, groupID, bobID, bobDev); w.Code != http.StatusNotFound {
		t.Fatalf("second fetch: expected 404 got %d", w.Code)
	}
}

// TestHistoryPacketExpired verifies an expired packet is treated as absent.
func TestHistoryPacketExpired(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "histexp.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev, bobID)

	// Bob accepts so the fetch gate is open.
	wa := httptest.NewRecorder()
	ra := as("POST", "/a", bobID, bobDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database)(wa, ra)

	// Insert a packet that already expired.
	past := time.Now().Add(-time.Hour).Unix()
	if _, err := database.Exec(
		`INSERT INTO group_history_packets(group_id,device_id,for_user_id,encrypted_history,encryption_salt,encryption_nonce,sender_device_id,created_at,expires_at)
		 VALUES(?,?,?,?,?,?,?,?,?)`,
		groupID, bobDev, bobID, []byte("old"), []byte("s"), []byte("n"), ownerDev, past, past); err != nil {
		t.Fatal(err)
	}

	if w := fetchHistory(database, groupID, bobID, bobDev); w.Code != http.StatusNotFound {
		t.Fatalf("expired fetch: expected 404 got %d", w.Code)
	}
}

// TestHistoryPacketAuthz covers the access rules: a plain member cannot upload,
// and a packet targeting a non-member device is rejected.
func TestHistoryPacketAuthz(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "histauthz.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev, bobID)

	// Bob accepts to become an active plain member.
	wa := httptest.NewRecorder()
	ra := as("POST", "/a", bobID, bobDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database)(wa, ra)

	// Member (bob) cannot upload history packets.
	if w := uploadHistory(database, groupID, bobID, bobDev, ownerDev, "x"); w.Code != http.StatusForbidden {
		t.Fatalf("member upload: expected 403 got %d", w.Code)
	}

	// Owner uploading for a stranger's device is rejected.
	eveID, eveDev := newUser(t, database, "eve")
	if w := uploadHistory(database, groupID, ownerID, ownerDev, eveDev, "x"); w.Code != http.StatusForbidden {
		t.Fatalf("packet for non-member device: expected 403 got %d", w.Code)
	}

	// Non-member cannot upload at all (fails the active-member gate).
	if w := uploadHistory(database, groupID, eveID, eveDev, ownerDev, "x"); w.Code != http.StatusForbidden {
		t.Fatalf("non-member upload: expected 403 got %d", w.Code)
	}

	// Non-member cannot fetch.
	eve2ID, eve2Dev := newUser(t, database, "eve2")
	if w := fetchHistory(database, groupID, eve2ID, eve2Dev); w.Code != http.StatusForbidden {
		t.Fatalf("non-member fetch: expected 403 got %d", w.Code)
	}
}

// TestHistoryPacketBadRequests covers input-validation branches on both
// endpoints: invalid group id, empty packet list, and malformed encoding.
func TestHistoryPacketBadRequests(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "histbad.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	ownerID, ownerDev := newUser(t, database, "owner")
	groupID := mkGroup(t, database, ownerID, ownerDev)

	// Upload with invalid group id.
	w := httptest.NewRecorder()
	r := as("POST", "/h", ownerID, ownerDev, historyPacketUploadRequest{Packets: []historyPacketItem{{DeviceID: ownerDev, EncryptedHistory: b64("x"), Salt: b64("s"), Nonce: b64("n")}}})
	r.SetPathValue("group_id", "bad")
	UploadGroupHistoryPackets(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("bad group id: expected 400 got %d", w.Code)
	}

	// Empty packet list.
	w = httptest.NewRecorder()
	r = as("POST", "/h", ownerID, ownerDev, historyPacketUploadRequest{Packets: nil})
	r.SetPathValue("group_id", itoa(groupID))
	UploadGroupHistoryPackets(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("empty packets: expected 400 got %d", w.Code)
	}

	// Malformed base64 in the history blob.
	w = httptest.NewRecorder()
	r = as("POST", "/h", ownerID, ownerDev, historyPacketUploadRequest{Packets: []historyPacketItem{{DeviceID: ownerDev, EncryptedHistory: "!!!", Salt: b64("s"), Nonce: b64("n")}}})
	r.SetPathValue("group_id", itoa(groupID))
	UploadGroupHistoryPackets(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("bad encoding: expected 400 got %d", w.Code)
	}

	// Empty history blob (decodes to zero bytes) is rejected.
	w = httptest.NewRecorder()
	r = as("POST", "/h", ownerID, ownerDev, historyPacketUploadRequest{Packets: []historyPacketItem{{DeviceID: ownerDev, EncryptedHistory: "", Salt: b64("s"), Nonce: b64("n")}}})
	r.SetPathValue("group_id", itoa(groupID))
	UploadGroupHistoryPackets(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("empty history: expected 400 got %d", w.Code)
	}

	// Oversized salt is rejected.
	bigSalt := base64.RawURLEncoding.EncodeToString(make([]byte, maxSaltLen+1))
	w = httptest.NewRecorder()
	r = as("POST", "/h", ownerID, ownerDev, historyPacketUploadRequest{Packets: []historyPacketItem{{DeviceID: ownerDev, EncryptedHistory: b64("x"), Salt: bigSalt, Nonce: b64("n")}}})
	r.SetPathValue("group_id", itoa(groupID))
	UploadGroupHistoryPackets(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("oversized salt: expected 400 got %d", w.Code)
	}

	// Malformed request body (not JSON).
	w = httptest.NewRecorder()
	r = httptest.NewRequest("POST", "/h", strings.NewReader("not json"))
	ctx := context.WithValue(r.Context(), middleware.ContextUserID, ownerID)
	ctx = context.WithValue(ctx, middleware.ContextDeviceID, ownerDev)
	r = r.WithContext(ctx)
	r.SetPathValue("group_id", itoa(groupID))
	UploadGroupHistoryPackets(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("bad body: expected 400 got %d", w.Code)
	}

	// Fetch with invalid group id.
	w = httptest.NewRecorder()
	r = as("GET", "/h", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", "bad")
	GetGroupHistoryPacket(database)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("fetch bad group id: expected 400 got %d", w.Code)
	}
}

// TestHistoryPacketNotifiesViaHub exercises the hub fan-out branch on upload.
func TestHistoryPacketNotifiesViaHub(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "histhub.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	ownerID, ownerDev := newUser(t, database, "owner")
	groupID := mkGroup(t, database, ownerID, ownerDev)

	hub := ws.NewHub()
	w := httptest.NewRecorder()
	req := historyPacketUploadRequest{Packets: []historyPacketItem{
		{DeviceID: ownerDev, EncryptedHistory: b64("blob"), Salt: b64("s"), Nonce: b64("n")},
	}}
	r := as("POST", "/h", ownerID, ownerDev, req)
	r.SetPathValue("group_id", itoa(groupID))
	UploadGroupHistoryPackets(database, hub)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("upload with hub: expected 204 got %d body=%s", w.Code, w.Body.String())
	}
}
