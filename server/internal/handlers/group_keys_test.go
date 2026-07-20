package handlers

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"messenger/server/internal/db"
	"messenger/server/internal/ws"
)

func rotate(t *testing.T, database *db.DB, groupID, userID, deviceID int64) (version int64, devices []map[string]any) {
	t.Helper()
	w := httptest.NewRecorder()
	r := as("POST", "/x", userID, deviceID, nil)
	r.SetPathValue("group_id", itoa(groupID))
	RotateGroupKey(database)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("rotate: expected 200 got %d body=%s", w.Code, w.Body.String())
	}
	var resp struct {
		KeyVersion int64            `json:"key_version"`
		Devices    []map[string]any `json:"devices"`
	}
	json.Unmarshal(w.Body.Bytes(), &resp)
	return resp.KeyVersion, resp.Devices
}

func TestRotateAndEnvelopeFlow(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "keys.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")

	// Group with bob active.
	w := httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: "T", MemberUserIDs: []int64{bobID}}))
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	groupID := int64(created["id"].(float64))
	wa := httptest.NewRecorder()
	ra := as("POST", "/a", bobID, bobDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, nil)(wa, ra)

	// Owner rotates: both devices should be in the recipient set.
	version, devices := rotate(t, database, groupID, ownerID, ownerDev)
	if len(devices) != 2 {
		t.Fatalf("expected 2 active devices, got %d", len(devices))
	}

	// Upload envelopes for both devices.
	env := envelopeUploadRequest{Envelopes: []envelopeItem{
		{DeviceID: ownerDev, EncryptedKey: b64("owner-key"), Salt: b64("s1"), Nonce: b64("n1")},
		{DeviceID: bobDev, EncryptedKey: b64("bob-key"), Salt: b64("s2"), Nonce: b64("n2")},
	}}
	w = httptest.NewRecorder()
	r := as("POST", "/e", ownerID, ownerDev, env)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", itoa(version))
	UploadEnvelopes(database, nil)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("upload envelopes: expected 204 got %d body=%s", w.Code, w.Body.String())
	}

	// Bob fetches his own envelope and gets bob-key.
	w = httptest.NewRecorder()
	r = as("GET", "/e", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", itoa(version))
	GetEnvelope(database)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("get envelope: expected 200 got %d", w.Code)
	}
	var got map[string]any
	json.Unmarshal(w.Body.Bytes(), &got)
	if dec, _ := base64.RawURLEncoding.DecodeString(got["encrypted_key"].(string)); string(dec) != "bob-key" {
		t.Fatalf("bob got wrong key: %q", dec)
	}

	// Remove bob, rotate again: bob's device must not be a recipient.
	wr := httptest.NewRecorder()
	rr := as("DELETE", "/m", ownerID, ownerDev, nil)
	rr.SetPathValue("group_id", itoa(groupID))
	rr.SetPathValue("user_id", itoa(bobID))
	RemoveMember(database)(wr, rr)
	if wr.Code != http.StatusNoContent {
		t.Fatalf("remove bob: got %d", wr.Code)
	}
	v2, devices2 := rotate(t, database, groupID, ownerID, ownerDev)
	if len(devices2) != 1 {
		t.Fatalf("after removal expected 1 device, got %d", len(devices2))
	}

	// Uploading an envelope for removed bob must be rejected.
	env2 := envelopeUploadRequest{Envelopes: []envelopeItem{
		{DeviceID: bobDev, EncryptedKey: b64("sneaky"), Salt: b64("s"), Nonce: b64("n")},
	}}
	w = httptest.NewRecorder()
	r = as("POST", "/e", ownerID, ownerDev, env2)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", itoa(v2))
	UploadEnvelopes(database, nil)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("envelope for removed device: expected 403 got %d", w.Code)
	}
}

func b64(s string) string { return base64.RawURLEncoding.EncodeToString([]byte(s)) }

// Regression: key version 1 must exist at group creation so envelopes upload
// without a prior rotation.
func TestKeyVersion1UsableAtCreation(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "kv1.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")

	w := httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: "T"}))
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	groupID := int64(created["id"].(float64))

	// Upload an envelope for version 1 without any rotation.
	env := envelopeUploadRequest{Envelopes: []envelopeItem{
		{DeviceID: ownerDev, EncryptedKey: b64("k"), Salt: b64("s"), Nonce: b64("n")},
	}}
	w = httptest.NewRecorder()
	r := as("POST", "/e", ownerID, ownerDev, env)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "1")
	UploadEnvelopes(database, nil)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("upload v1 envelope: expected 204 got %d body=%s", w.Code, w.Body.String())
	}
}

// Regression: an envelope with an empty salt or nonce must be rejected.
func TestEnvelopeEmptySaltRejected(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "kv2.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	w := httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: "T"}))
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	groupID := int64(created["id"].(float64))

	env := envelopeUploadRequest{Envelopes: []envelopeItem{
		{DeviceID: ownerDev, EncryptedKey: b64("k"), Salt: "", Nonce: b64("n")},
	}}
	w = httptest.NewRecorder()
	r := as("POST", "/e", ownerID, ownerDev, env)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "1")
	UploadEnvelopes(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("empty salt: expected 400 got %d", w.Code)
	}
}

func TestListKeyVersions(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "lkv.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	w := httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: "T"}))
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	groupID := int64(created["id"].(float64))

	// No envelope yet: empty list.
	w = httptest.NewRecorder()
	r := as("GET", "/k", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	ListKeyVersions(database)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("list versions: got %d", w.Code)
	}
	var resp struct {
		Versions []int64 `json:"versions"`
	}
	json.Unmarshal(w.Body.Bytes(), &resp)
	if len(resp.Versions) != 0 {
		t.Fatalf("expected 0 versions, got %d", len(resp.Versions))
	}

	// Upload a v1 envelope for owner's device, then it appears.
	env := envelopeUploadRequest{Envelopes: []envelopeItem{
		{DeviceID: ownerDev, EncryptedKey: b64("k"), Salt: b64("s"), Nonce: b64("n")},
	}}
	wu := httptest.NewRecorder()
	ru := as("POST", "/e", ownerID, ownerDev, env)
	ru.SetPathValue("group_id", itoa(groupID))
	ru.SetPathValue("version", "1")
	UploadEnvelopes(database, nil)(wu, ru)

	w = httptest.NewRecorder()
	r = as("GET", "/k", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	ListKeyVersions(database)(w, r)
	json.Unmarshal(w.Body.Bytes(), &resp)
	if len(resp.Versions) != 1 || resp.Versions[0] != 1 {
		t.Fatalf("expected [1], got %v", resp.Versions)
	}

	// Non-member is forbidden.
	eveID, eveDev := newUser(t, database, "eve")
	w = httptest.NewRecorder()
	r = as("GET", "/k", eveID, eveDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	ListKeyVersions(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("non-member list versions: expected 403 got %d", w.Code)
	}

	// Invalid group id.
	w = httptest.NewRecorder()
	r = as("GET", "/k", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", "bad")
	ListKeyVersions(database)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("invalid group id: expected 400 got %d", w.Code)
	}
}

func TestRotatePermissionsAndErrors(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "rot.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev, bobID)

	// Bob accepts (member, not admin) -> cannot rotate.
	wa := httptest.NewRecorder()
	ra := as("POST", "/a", bobID, bobDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, nil)(wa, ra)

	w := httptest.NewRecorder()
	r := as("POST", "/r", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	RotateGroupKey(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("member rotate: expected 403 got %d", w.Code)
	}

	// Invalid group id.
	w = httptest.NewRecorder()
	r = as("POST", "/r", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", "bad")
	RotateGroupKey(database)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("rotate invalid id: expected 400 got %d", w.Code)
	}

	// Non-member cannot rotate.
	eveID, eveDev := newUser(t, database, "eve")
	w = httptest.NewRecorder()
	r = as("POST", "/r", eveID, eveDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	RotateGroupKey(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("non-member rotate: expected 403 got %d", w.Code)
	}
}

func TestUploadEnvelopesErrors(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "uerr.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev, bobID)

	// Invalid version.
	w := httptest.NewRecorder()
	r := as("POST", "/e", ownerID, ownerDev, envelopeUploadRequest{Envelopes: []envelopeItem{{DeviceID: ownerDev, EncryptedKey: b64("k"), Salt: b64("s"), Nonce: b64("n")}}})
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "0")
	UploadEnvelopes(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("invalid version: expected 400 got %d", w.Code)
	}

	// Empty envelopes list.
	w = httptest.NewRecorder()
	r = as("POST", "/e", ownerID, ownerDev, envelopeUploadRequest{Envelopes: nil})
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "1")
	UploadEnvelopes(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("empty envelopes: expected 400 got %d", w.Code)
	}

	// Unknown key version.
	w = httptest.NewRecorder()
	r = as("POST", "/e", ownerID, ownerDev, envelopeUploadRequest{Envelopes: []envelopeItem{{DeviceID: ownerDev, EncryptedKey: b64("k"), Salt: b64("s"), Nonce: b64("n")}}})
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "999")
	UploadEnvelopes(database, nil)(w, r)
	if w.Code != http.StatusNotFound {
		t.Fatalf("unknown version: expected 404 got %d", w.Code)
	}

	// Member (bob) cannot upload.
	wa := httptest.NewRecorder()
	ra := as("POST", "/a", bobID, bobDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, nil)(wa, ra)
	w = httptest.NewRecorder()
	r = as("POST", "/e", bobID, bobDev, envelopeUploadRequest{Envelopes: []envelopeItem{{DeviceID: bobDev, EncryptedKey: b64("k"), Salt: b64("s"), Nonce: b64("n")}}})
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "1")
	UploadEnvelopes(database, nil)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("member upload: expected 403 got %d", w.Code)
	}

	// Bad base64 encoding.
	w = httptest.NewRecorder()
	r = as("POST", "/e", ownerID, ownerDev, envelopeUploadRequest{Envelopes: []envelopeItem{{DeviceID: ownerDev, EncryptedKey: "!!!notb64!!!", Salt: b64("s"), Nonce: b64("n")}}})
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "1")
	UploadEnvelopes(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("bad base64: expected 400 got %d", w.Code)
	}
}

func TestUploadEnvelopesNotifiesViaHub(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "uhub.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	groupID := mkGroup(t, database, ownerID, ownerDev)

	hub := ws.NewHub()
	env := envelopeUploadRequest{Envelopes: []envelopeItem{
		{DeviceID: ownerDev, EncryptedKey: b64("k"), Salt: b64("s"), Nonce: b64("n")},
	}}
	w := httptest.NewRecorder()
	r := as("POST", "/e", ownerID, ownerDev, env)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "1")
	UploadEnvelopes(database, hub)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("upload with hub: expected 204 got %d body=%s", w.Code, w.Body.String())
	}
}

func TestGetEnvelopeErrors(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "gerr.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	groupID := mkGroup(t, database, ownerID, ownerDev)

	// Invalid version.
	w := httptest.NewRecorder()
	r := as("GET", "/e", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "0")
	GetEnvelope(database)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("invalid version: expected 400 got %d", w.Code)
	}

	// Missing envelope -> 404.
	w = httptest.NewRecorder()
	r = as("GET", "/e", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "1")
	GetEnvelope(database)(w, r)
	if w.Code != http.StatusNotFound {
		t.Fatalf("missing envelope: expected 404 got %d", w.Code)
	}

	// Non-member forbidden.
	eveID, eveDev := newUser(t, database, "eve")
	w = httptest.NewRecorder()
	r = as("GET", "/e", eveID, eveDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("version", "1")
	GetEnvelope(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("non-member envelope: expected 403 got %d", w.Code)
	}
}
