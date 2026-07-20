package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

func newUser(t *testing.T, database *db.DB, nick string) (userID, deviceID int64) {
	t.Helper()
	now := time.Now().Unix()
	ur, err := database.Exec(`INSERT INTO users(name,nickname,password_hash,created_at) VALUES(?,?,?,?)`, nick, nick, "x", now)
	if err != nil {
		t.Fatal(err)
	}
	userID, _ = ur.LastInsertId()
	dr, err := database.Exec(`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,?,?,?)`, userID, nick+"-dev", now, now)
	if err != nil {
		t.Fatal(err)
	}
	deviceID, _ = dr.LastInsertId()
	return userID, deviceID
}

// as builds a request carrying the authenticated user/device in context.
func as(method, target string, userID, deviceID int64, body any) *http.Request {
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	r := httptest.NewRequest(method, target, &buf)
	ctx := context.WithValue(r.Context(), middleware.ContextUserID, userID)
	ctx = context.WithValue(ctx, middleware.ContextDeviceID, deviceID)
	return r.WithContext(ctx)
}

func TestGroupCRUDAndMembership(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "groups.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	eveID, eveDev := newUser(t, database, "eve")

	// Create group with bob as pending member.
	w := httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/api/v1/groups", ownerID, ownerDev, groupCreateRequest{Name: "Team", MemberUserIDs: []int64{bobID}}))
	if w.Code != http.StatusCreated {
		t.Fatalf("create group: got %d body=%s", w.Code, w.Body.String())
	}
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	groupID := int64(created["id"].(float64))

	// Eve is not a member: GetGroup must be forbidden.
	w = httptest.NewRecorder()
	r := as("GET", "/api/v1/groups/x", eveID, eveDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	GetGroup(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("eve GetGroup: expected 403 got %d", w.Code)
	}

	// Bob is pending, not active: also forbidden until accept.
	w = httptest.NewRecorder()
	r = as("GET", "/x", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	GetGroup(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("bob pending GetGroup: expected 403 got %d", w.Code)
	}

	// Bob accepts.
	w = httptest.NewRecorder()
	r = as("POST", "/x", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, nil)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("bob accept: expected 204 got %d body=%s", w.Code, w.Body.String())
	}

	// Now bob can read the group.
	w = httptest.NewRecorder()
	r = as("GET", "/x", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	GetGroup(database)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("bob GetGroup after accept: expected 200 got %d", w.Code)
	}

	// Bob (member) cannot rename.
	w = httptest.NewRecorder()
	r = as("PATCH", "/x", bobID, bobDev, groupPatchRequest{Name: "Hacked"})
	r.SetPathValue("group_id", itoa(groupID))
	PatchGroup(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("bob rename: expected 403 got %d", w.Code)
	}

	// Owner promotes bob to admin.
	w = httptest.NewRecorder()
	r = as("PATCH", "/x", ownerID, ownerDev, memberRoleRequest{Role: roleAdmin})
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("user_id", itoa(bobID))
	ChangeMemberRole(database)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("promote bob: expected 204 got %d body=%s", w.Code, w.Body.String())
	}

	// Admin bob can now rename.
	w = httptest.NewRecorder()
	r = as("PATCH", "/x", bobID, bobDev, groupPatchRequest{Name: "Renamed"})
	r.SetPathValue("group_id", itoa(groupID))
	PatchGroup(database)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("admin bob rename: expected 204 got %d", w.Code)
	}

	// Membership version should have bumped from the invite path. Remove bob and
	// confirm the version advances again.
	var mvBefore int64
	database.QueryRow(`SELECT membership_version FROM groups WHERE id=?`, groupID).Scan(&mvBefore)
	w = httptest.NewRecorder()
	r = as("DELETE", "/x", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("user_id", itoa(bobID))
	RemoveMember(database)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("remove bob: expected 204 got %d", w.Code)
	}
	var mvAfter int64
	database.QueryRow(`SELECT membership_version FROM groups WHERE id=?`, groupID).Scan(&mvAfter)
	if mvAfter <= mvBefore {
		t.Fatalf("membership_version did not advance on remove: %d -> %d", mvBefore, mvAfter)
	}

	// Removed bob loses access.
	w = httptest.NewRecorder()
	r = as("GET", "/x", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	GetGroup(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("removed bob GetGroup: expected 403 got %d", w.Code)
	}

	// Owner cannot be removed.
	w = httptest.NewRecorder()
	r = as("DELETE", "/x", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("user_id", itoa(ownerID))
	RemoveMember(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("remove owner: expected 403 got %d", w.Code)
	}
}

func itoa(v int64) string {
	return strconv.FormatInt(v, 10)
}

// Regression: inviting an already-active member must not downgrade them to
// pending; it should be rejected with 409.
func TestInviteActiveMemberRejected(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "invite.db"))
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

	// Bob accepts and becomes active.
	wa := httptest.NewRecorder()
	ra := as("POST", "/a", bobID, bobDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, nil)(wa, ra)
	if wa.Code != http.StatusNoContent {
		t.Fatalf("accept: got %d", wa.Code)
	}

	// A second invite of active bob must be rejected, and bob stays active.
	w = httptest.NewRecorder()
	r := as("POST", "/m", ownerID, ownerDev, memberInviteRequest{UserID: bobID})
	r.SetPathValue("group_id", itoa(groupID))
	InviteMember(database, nil)(w, r)
	if w.Code != http.StatusConflict {
		t.Fatalf("re-invite active member: expected 409 got %d", w.Code)
	}
	var status string
	database.QueryRow(`SELECT status FROM group_members WHERE group_id=? AND user_id=?`, groupID, bobID).Scan(&status)
	if status != "active" {
		t.Fatalf("bob downgraded to %q by duplicate invite", status)
	}
}

// Regression: accepting an invitation advances the membership epoch.
func TestAcceptBumpsMembershipVersion(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "accept.db"))
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

	var before int64
	database.QueryRow(`SELECT membership_version FROM groups WHERE id=?`, groupID).Scan(&before)

	wa := httptest.NewRecorder()
	ra := as("POST", "/a", bobID, bobDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, nil)(wa, ra)
	if wa.Code != http.StatusNoContent {
		t.Fatalf("accept: got %d", wa.Code)
	}
	var after int64
	database.QueryRow(`SELECT membership_version FROM groups WHERE id=?`, groupID).Scan(&after)
	if after <= before {
		t.Fatalf("membership_version did not advance on accept: %d -> %d", before, after)
	}
}

// mkGroup creates a group with the owner active and returns its id.
func mkGroup(t *testing.T, database *db.DB, ownerID, ownerDev int64, members ...int64) int64 {
	t.Helper()
	w := httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: "T", MemberUserIDs: members}))
	if w.Code != http.StatusCreated {
		t.Fatalf("mkGroup: got %d body=%s", w.Code, w.Body.String())
	}
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	return int64(created["id"].(float64))
}

func TestCreateGroupBadInput(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "cbad.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")

	w := httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: ""}))
	if w.Code != http.StatusBadRequest {
		t.Fatalf("empty name: expected 400 got %d", w.Code)
	}

	long := make([]byte, maxGroupNameLen+1)
	for i := range long {
		long[i] = 'a'
	}
	w = httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: string(long)}))
	if w.Code != http.StatusBadRequest {
		t.Fatalf("long name: expected 400 got %d", w.Code)
	}

	many := make([]int64, maxGroupMembers+1)
	for i := range many {
		many[i] = int64(i + 100)
	}
	w = httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: "ok", MemberUserIDs: many}))
	if w.Code != http.StatusBadRequest {
		t.Fatalf("too many members: expected 400 got %d", w.Code)
	}

	w = httptest.NewRecorder()
	r := httptest.NewRequest("POST", "/g", bytes.NewBufferString("{bad"))
	r = r.WithContext(context.WithValue(r.Context(), middleware.ContextUserID, ownerID))
	CreateGroup(database)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("bad json: expected 400 got %d", w.Code)
	}
}

// TestCreateGroupSkipsDuplicateAndInvalidInvitees covers the loop branch that
// skips a zero/negative uid and a uid duplicated in the invite list (and one
// equal to the owner), so exactly one pending row is created.
func TestCreateGroupSkipsDuplicateAndInvalidInvitees(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "cdup.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, _ := newUser(t, database, "bob")

	w := httptest.NewRecorder()
	CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev,
		groupCreateRequest{Name: "T", MemberUserIDs: []int64{0, -5, bobID, bobID, ownerID}}))
	if w.Code != http.StatusCreated {
		t.Fatalf("create: got %d body=%s", w.Code, w.Body.String())
	}
	var created map[string]any
	json.Unmarshal(w.Body.Bytes(), &created)
	groupID := int64(created["id"].(float64))

	var pending int
	database.QueryRow(`SELECT COUNT(*) FROM group_members WHERE group_id=? AND status='pending'`, groupID).Scan(&pending)
	if pending != 1 {
		t.Fatalf("expected exactly 1 pending invitee, got %d", pending)
	}
}

func TestListGroupsAndMembers(t *testing.T) {	database, _ := db.Open(filepath.Join(t.TempDir(), "list.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev, bobID)

	w := httptest.NewRecorder()
	ListGroups(database)(w, as("GET", "/g", ownerID, ownerDev, nil))
	if w.Code != http.StatusOK {
		t.Fatalf("list groups: got %d", w.Code)
	}
	var lr struct {
		Groups []map[string]any `json:"groups"`
	}
	json.Unmarshal(w.Body.Bytes(), &lr)
	if len(lr.Groups) != 1 {
		t.Fatalf("expected 1 group, got %d", len(lr.Groups))
	}

	w = httptest.NewRecorder()
	r := as("GET", "/g", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	ListMembers(database)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("list members: got %d", w.Code)
	}
	var mr struct {
		Members []map[string]any `json:"members"`
	}
	json.Unmarshal(w.Body.Bytes(), &mr)
	if len(mr.Members) != 2 {
		t.Fatalf("expected 2 members, got %d", len(mr.Members))
	}

	w = httptest.NewRecorder()
	r = as("GET", "/g", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	ListMembers(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("pending member ListMembers: expected 403 got %d", w.Code)
	}
}

func TestGroupInvalidPathValues(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "path.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")

	for _, h := range []http.HandlerFunc{GetGroup(database), PatchGroup(database), DeleteGroup(database), ListMembers(database), InviteMember(database, nil), GetGroupHistory(database)} {
		w := httptest.NewRecorder()
		r := as("GET", "/g", ownerID, ownerDev, nil)
		r.SetPathValue("group_id", "notanumber")
		h(w, r)
		if w.Code != http.StatusBadRequest {
			t.Fatalf("invalid group id: expected 400 got %d", w.Code)
		}
	}

	groupID := mkGroup(t, database, ownerID, ownerDev)
	for _, h := range []http.HandlerFunc{RemoveMember(database), ChangeMemberRole(database)} {
		w := httptest.NewRecorder()
		r := as("DELETE", "/g", ownerID, ownerDev, nil)
		r.SetPathValue("group_id", itoa(groupID))
		r.SetPathValue("user_id", "xyz")
		h(w, r)
		if w.Code != http.StatusBadRequest {
			t.Fatalf("invalid user id: expected 400 got %d", w.Code)
		}
	}
}

// TestDeclineInvitation confirms a pending invitee can decline, that the row is
// then no longer listed, and that declining without a pending invite is a 409.
func TestDeclineInvitation(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "decline.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev)

	// Owner invites bob.
	w := httptest.NewRecorder()
	r := as("POST", "/m", ownerID, ownerDev, memberInviteRequest{UserID: bobID})
	r.SetPathValue("group_id", itoa(groupID))
	InviteMember(database, nil)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("invite: expected 200 got %d", w.Code)
	}

	// Bob declines.
	w = httptest.NewRecorder()
	r = as("POST", "/d", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	DeclineInvitation(database)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("decline: expected 204 got %d body=%s", w.Code, w.Body.String())
	}

	// Group no longer appears for bob (removed, not pending/active).
	w = httptest.NewRecorder()
	ListGroups(database)(w, as("GET", "/g", bobID, bobDev, nil))
	var lr struct {
		Groups []map[string]any `json:"groups"`
	}
	json.Unmarshal(w.Body.Bytes(), &lr)
	if len(lr.Groups) != 0 {
		t.Fatalf("declined group should not be listed, got %v", lr.Groups)
	}

	// Declining again (no pending invite) is a conflict.
	w = httptest.NewRecorder()
	r = as("POST", "/d", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	DeclineInvitation(database)(w, r)
	if w.Code != http.StatusConflict {
		t.Fatalf("re-decline: expected 409 got %d", w.Code)
	}
}

// TestInviteNotifiesAndPendingListed covers the invite → GROUP_MEMBER_CHANGED
// push path (hub non-nil) and confirms the invitee sees the group as pending in
// ListGroups so the client can offer an accept action.
func TestInviteNotifiesAndPendingListed(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "invnotify.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev)

	// Owner invites bob; a real hub is passed so notifyMemberChanged runs its
	// device-lookup + best-effort send path (bob is offline, frame is dropped).
	hub := ws.NewHub()
	w := httptest.NewRecorder()
	r := as("POST", "/m", ownerID, ownerDev, memberInviteRequest{UserID: bobID})
	r.SetPathValue("group_id", itoa(groupID))
	InviteMember(database, hub)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("invite: expected 200 got %d body=%s", w.Code, w.Body.String())
	}

	// Bob now sees the group as pending via ListGroups.
	w = httptest.NewRecorder()
	ListGroups(database)(w, as("GET", "/g", bobID, bobDev, nil))
	var lr struct {
		Groups []map[string]any `json:"groups"`
	}
	json.Unmarshal(w.Body.Bytes(), &lr)
	if len(lr.Groups) != 1 || lr.Groups[0]["status"] != "pending" {
		t.Fatalf("bob should see 1 pending group, got %v", lr.Groups)
	}
}

// TestAcceptNotifiesAdmins covers the accept → GROUP_MEMBER_CHANGED push to the
// group's active owner/admins (hub non-nil), which is what triggers the owner to
// rotate and distribute a fresh key to the newly active device.
func TestAcceptNotifiesAdmins(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "accnotify.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev)

	// Owner invites bob.
	w := httptest.NewRecorder()
	r := as("POST", "/m", ownerID, ownerDev, memberInviteRequest{UserID: bobID})
	r.SetPathValue("group_id", itoa(groupID))
	InviteMember(database, nil)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("invite: expected 200 got %d body=%s", w.Code, w.Body.String())
	}

	// Bob accepts with a real hub so notifyGroupAdmins runs its owner/admin
	// device-lookup + best-effort send path (owner is offline, frame is dropped).
	hub := ws.NewHub()
	w = httptest.NewRecorder()
	r = as("POST", "/a", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, hub)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("accept: expected 204 got %d body=%s", w.Code, w.Body.String())
	}

	// Bob is now active: he can read the group.
	w = httptest.NewRecorder()
	r = as("GET", "/g", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	GetGroup(database)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("bob active GetGroup: expected 200 got %d", w.Code)
	}
}

func TestInviteAndRoleErrorPaths(t *testing.T) {	database, _ := db.Open(filepath.Join(t.TempDir(), "invrole.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev)

	w := httptest.NewRecorder()
	r := as("POST", "/g", ownerID, ownerDev, memberInviteRequest{UserID: 0})
	r.SetPathValue("group_id", itoa(groupID))
	InviteMember(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("invite missing user_id: expected 400 got %d", w.Code)
	}

	w = httptest.NewRecorder()
	r = as("POST", "/g", bobID, bobDev, memberInviteRequest{UserID: 999})
	r.SetPathValue("group_id", itoa(groupID))
	InviteMember(database, nil)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("non-member invite: expected 403 got %d", w.Code)
	}

	w = httptest.NewRecorder()
	r = as("POST", "/g", ownerID, ownerDev, memberInviteRequest{UserID: bobID})
	r.SetPathValue("group_id", itoa(groupID))
	InviteMember(database, nil)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("invite bob: got %d body=%s", w.Code, w.Body.String())
	}

	w = httptest.NewRecorder()
	r = as("PATCH", "/g", ownerID, ownerDev, memberRoleRequest{Role: "superuser"})
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("user_id", itoa(bobID))
	ChangeMemberRole(database)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("invalid role: expected 400 got %d", w.Code)
	}

	w = httptest.NewRecorder()
	r = as("PATCH", "/g", bobID, bobDev, memberRoleRequest{Role: roleAdmin})
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("user_id", itoa(ownerID))
	ChangeMemberRole(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("non-owner role change: expected 403 got %d", w.Code)
	}

	other, _ := newUser(t, database, "carol")
	w = httptest.NewRecorder()
	r = as("PATCH", "/g", ownerID, ownerDev, memberRoleRequest{Role: roleAdmin})
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("user_id", itoa(other))
	ChangeMemberRole(database)(w, r)
	if w.Code != http.StatusNotFound {
		t.Fatalf("role change non-member: expected 404 got %d", w.Code)
	}

	w = httptest.NewRecorder()
	r = as("DELETE", "/g", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("user_id", itoa(other))
	RemoveMember(database)(w, r)
	if w.Code != http.StatusNotFound {
		t.Fatalf("remove non-member: expected 404 got %d", w.Code)
	}
}

func TestAcceptWithoutInvitationConflicts(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "acc2.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	eveID, eveDev := newUser(t, database, "eve")
	groupID := mkGroup(t, database, ownerID, ownerDev)

	w := httptest.NewRecorder()
	r := as("POST", "/g", eveID, eveDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, nil)(w, r)
	if w.Code != http.StatusConflict {
		t.Fatalf("accept without invite: expected 409 got %d", w.Code)
	}

	w = httptest.NewRecorder()
	r = as("POST", "/g", eveID, eveDev, nil)
	r.SetPathValue("group_id", "999999")
	AcceptInvitation(database, nil)(w, r)
	if w.Code != http.StatusNotFound {
		t.Fatalf("accept missing group: expected 404 got %d", w.Code)
	}
}

func TestDeleteGroupOwnerOnly(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "del.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev, bobID)

	wa := httptest.NewRecorder()
	ra := as("POST", "/g", bobID, bobDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, nil)(wa, ra)

	w := httptest.NewRecorder()
	r := as("DELETE", "/g", bobID, bobDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	DeleteGroup(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("member delete: expected 403 got %d", w.Code)
	}

	w = httptest.NewRecorder()
	r = as("DELETE", "/g", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	DeleteGroup(database)(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("owner delete: expected 204 got %d", w.Code)
	}

	w = httptest.NewRecorder()
	r = as("GET", "/g", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	GetGroup(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("deleted group access: expected 403 got %d", w.Code)
	}
}

func TestGetGroupHistoryPagination(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "hist.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	groupID := mkGroup(t, database, ownerID, ownerDev)

	now := time.Now().Unix()
	for i := range 3 {
		database.Exec(`INSERT INTO group_messages(group_id,message_id,sender_user_id,sender_device_id,key_version,ciphertext,encryption_salt,encryption_nonce,created_at)
			VALUES(?,?,?,?,1,?,?,?,?)`, groupID, "m"+itoa(int64(i)), ownerID, ownerDev, []byte{byte(i)}, []byte{1}, []byte{2}, now+int64(i))
	}

	w := httptest.NewRecorder()
	r := as("GET", "/g?limit=2", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	GetGroupHistory(database)(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("history: got %d", w.Code)
	}
	var page struct {
		Messages   []map[string]any `json:"messages"`
		NextCursor string           `json:"next_cursor"`
	}
	json.Unmarshal(w.Body.Bytes(), &page)
	if len(page.Messages) != 2 || page.NextCursor == "" {
		t.Fatalf("expected 2 messages + cursor, got %d cursor=%q", len(page.Messages), page.NextCursor)
	}

	w = httptest.NewRecorder()
	r = as("GET", "/g?limit=2&before_id="+page.NextCursor, ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	GetGroupHistory(database)(w, r)
	json.Unmarshal(w.Body.Bytes(), &page)
	if len(page.Messages) != 1 {
		t.Fatalf("final page: expected 1 message, got %d", len(page.Messages))
	}
}

// TestHandlersDBErrorPaths drives every group handler against a closed database
// so the first DB call fails, covering the internal-error branches.
func TestHandlersDBErrorPaths(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "dberr.db"))
	ownerID, ownerDev := newUser(t, database, "owner")
	// Seed a real group so path parsing / membership can be exercised before we
	// break things for the handlers that need a valid pre-close row.
	groupID := mkGroup(t, database, ownerID, ownerDev)
	database.Close() // every subsequent query now errors

	call := func(h http.HandlerFunc, method string, setUser bool, body any) int {
		w := httptest.NewRecorder()
		var r *http.Request
		if setUser {
			r = as(method, "/x", ownerID, ownerDev, body)
		} else {
			r = httptest.NewRequest(method, "/x", nil)
		}
		r.SetPathValue("group_id", itoa(groupID))
		r.SetPathValue("user_id", itoa(ownerID))
		r.SetPathValue("version", "1")
		h(w, r)
		return w.Code
	}

	cases := []struct {
		name string
		code int
	}{
		{"create", call(CreateGroup(database), "POST", true, groupCreateRequest{Name: "X"})},
		{"list", call(ListGroups(database), "GET", true, nil)},
		{"get", call(GetGroup(database), "GET", true, nil)},
		{"patch", call(PatchGroup(database), "PATCH", true, groupPatchRequest{Name: "Y"})},
		{"delete", call(DeleteGroup(database), "DELETE", true, nil)},
		{"members", call(ListMembers(database), "GET", true, nil)},
		{"invite", call(InviteMember(database, nil), "POST", true, memberInviteRequest{UserID: 42})},
		{"accept", call(AcceptInvitation(database, nil), "POST", true, nil)},
		{"remove", call(RemoveMember(database), "DELETE", true, nil)},
		{"role", call(ChangeMemberRole(database), "PATCH", true, memberRoleRequest{Role: roleAdmin})},
		{"history", call(GetGroupHistory(database), "GET", true, nil)},
		{"rotate", call(RotateGroupKey(database), "POST", true, nil)},
		{"upload", call(UploadEnvelopes(database, nil), "POST", true, envelopeUploadRequest{Envelopes: []envelopeItem{{DeviceID: ownerDev, EncryptedKey: b64("k"), Salt: b64("s"), Nonce: b64("n")}}})},
		{"versions", call(ListKeyVersions(database), "GET", true, nil)},
		{"envelope", call(GetEnvelope(database), "GET", true, nil)},
	}
	for _, c := range cases {
		if c.code != http.StatusInternalServerError {
			t.Errorf("%s on closed db: expected 500 got %d", c.name, c.code)
		}
	}
}

// TestReachableValidationBranches covers logic branches (not DB failures) that
// the broader flow tests miss: input validation, path parsing on the member
// endpoints, and the active-non-owner permission check.
func TestReachableValidationBranches(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "reach.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	bobID, bobDev := newUser(t, database, "bob")
	groupID := mkGroup(t, database, ownerID, ownerDev, bobID)

	// Bob accepts and is an active plain member.
	wa := httptest.NewRecorder()
	ra := as("POST", "/a", bobID, bobDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, nil)(wa, ra)

	// PatchGroup with an empty name -> 400.
	w := httptest.NewRecorder()
	r := as("PATCH", "/x", ownerID, ownerDev, groupPatchRequest{Name: ""})
	r.SetPathValue("group_id", itoa(groupID))
	PatchGroup(database)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("patch empty name: expected 400 got %d", w.Code)
	}

	// RemoveMember with an invalid group_id path -> 400.
	w = httptest.NewRecorder()
	r = as("DELETE", "/x", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", "notnum")
	r.SetPathValue("user_id", itoa(bobID))
	RemoveMember(database)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("remove invalid group id: expected 400 got %d", w.Code)
	}

	// ChangeMemberRole with an invalid group_id path -> 400.
	w = httptest.NewRecorder()
	r = as("PATCH", "/x", ownerID, ownerDev, memberRoleRequest{Role: roleAdmin})
	r.SetPathValue("group_id", "notnum")
	r.SetPathValue("user_id", itoa(bobID))
	ChangeMemberRole(database)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("role invalid group id: expected 400 got %d", w.Code)
	}

	// ChangeMemberRole by an active non-owner member -> 403 (the role!=owner
	// branch, distinct from the not-a-member 403).
	w = httptest.NewRecorder()
	r = as("PATCH", "/x", bobID, bobDev, memberRoleRequest{Role: roleAdmin})
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("user_id", itoa(ownerID))
	ChangeMemberRole(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("active member role change: expected 403 got %d", w.Code)
	}
}

// TestRotateKeyVersionInsertError covers RotateGroupKey's key-version insert
// failure: membership and the groups UPDATE succeed, then the
// group_key_versions INSERT errors because the table is gone.
func TestRotateKeyVersionInsertError(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "roterr.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	groupID := mkGroup(t, database, ownerID, ownerDev)
	database.Exec(`DROP TABLE group_key_versions`)

	w := httptest.NewRecorder()
	r := as("POST", "/r", ownerID, ownerDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	RotateGroupKey(database)(w, r)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("rotate with dropped key_versions: expected 500 got %d", w.Code)
	}
}

// TestGroupWriteFaultBranches covers the mid-transaction write-error returns
// (the INSERT/UPDATE failure paths after membership and earlier statements
// succeed). A SQLite trigger is armed to raise on the specific write so reads
// used by the permission checks still work.
func TestGroupWriteFaultBranches(t *testing.T) {
	// InviteMember: the membership-version bump UPDATE on groups fails.
	t.Run("invite_groups_update", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "wf1.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		bobID, _ := newUser(t, database, "bob")
		groupID := mkGroup(t, database, ownerID, ownerDev)
		database.Exec(`CREATE TRIGGER t_grp BEFORE UPDATE OF membership_version ON groups
			BEGIN SELECT RAISE(ABORT,'boom'); END`)
		w := httptest.NewRecorder()
		r := as("POST", "/m", ownerID, ownerDev, memberInviteRequest{UserID: bobID})
		r.SetPathValue("group_id", itoa(groupID))
		InviteMember(database, nil)(w, r)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("invite update fault: expected 500 got %d", w.Code)
		}
	})

	// InviteMember: the member upsert fails after the version bump succeeds.
	t.Run("invite_member_upsert", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "wf2.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		bobID, _ := newUser(t, database, "bob")
		groupID := mkGroup(t, database, ownerID, ownerDev)
		database.Exec(`CREATE TRIGGER t_ins BEFORE INSERT ON group_members
			WHEN NEW.status='pending' BEGIN SELECT RAISE(ABORT,'boom'); END`)
		w := httptest.NewRecorder()
		r := as("POST", "/m", ownerID, ownerDev, memberInviteRequest{UserID: bobID})
		r.SetPathValue("group_id", itoa(groupID))
		InviteMember(database, nil)(w, r)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("invite upsert fault: expected 500 got %d", w.Code)
		}
	})

	// RemoveMember: the member status UPDATE fails after the version bump.
	t.Run("remove_member_update", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "wf3.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		bobID, bobDev := newUser(t, database, "bob")
		groupID := mkGroup(t, database, ownerID, ownerDev, bobID)
		wa := httptest.NewRecorder()
		ra := as("POST", "/a", bobID, bobDev, nil)
		ra.SetPathValue("group_id", itoa(groupID))
		AcceptInvitation(database, nil)(wa, ra)
		database.Exec(`CREATE TRIGGER t_rm BEFORE UPDATE OF removed_at ON group_members
			BEGIN SELECT RAISE(ABORT,'boom'); END`)
		w := httptest.NewRecorder()
		r := as("DELETE", "/m", ownerID, ownerDev, nil)
		r.SetPathValue("group_id", itoa(groupID))
		r.SetPathValue("user_id", itoa(bobID))
		RemoveMember(database)(w, r)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("remove update fault: expected 500 got %d", w.Code)
		}
	})

	// ChangeMemberRole: the role UPDATE fails.
	t.Run("role_update", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "wf4.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		bobID, bobDev := newUser(t, database, "bob")
		groupID := mkGroup(t, database, ownerID, ownerDev, bobID)
		wa := httptest.NewRecorder()
		ra := as("POST", "/a", bobID, bobDev, nil)
		ra.SetPathValue("group_id", itoa(groupID))
		AcceptInvitation(database, nil)(wa, ra)
		database.Exec(`CREATE TRIGGER t_role BEFORE UPDATE OF role ON group_members
			BEGIN SELECT RAISE(ABORT,'boom'); END`)
		w := httptest.NewRecorder()
		r := as("PATCH", "/m", ownerID, ownerDev, memberRoleRequest{Role: roleAdmin})
		r.SetPathValue("group_id", itoa(groupID))
		r.SetPathValue("user_id", itoa(bobID))
		ChangeMemberRole(database)(w, r)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("role update fault: expected 500 got %d", w.Code)
		}
	})

	// PatchGroup: the rename UPDATE fails.
	t.Run("patch_update", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "wf5.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		groupID := mkGroup(t, database, ownerID, ownerDev)
		database.Exec(`CREATE TRIGGER t_name BEFORE UPDATE OF name ON groups
			BEGIN SELECT RAISE(ABORT,'boom'); END`)
		w := httptest.NewRecorder()
		r := as("PATCH", "/x", ownerID, ownerDev, groupPatchRequest{Name: "New"})
		r.SetPathValue("group_id", itoa(groupID))
		PatchGroup(database)(w, r)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("patch update fault: expected 500 got %d", w.Code)
		}
	})

	// DeleteGroup: the soft-delete UPDATE fails.
	t.Run("delete_update", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "wf6.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		groupID := mkGroup(t, database, ownerID, ownerDev)
		database.Exec(`CREATE TRIGGER t_del BEFORE UPDATE OF deleted_at ON groups
			BEGIN SELECT RAISE(ABORT,'boom'); END`)
		w := httptest.NewRecorder()
		r := as("DELETE", "/x", ownerID, ownerDev, nil)
		r.SetPathValue("group_id", itoa(groupID))
		DeleteGroup(database)(w, r)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("delete update fault: expected 500 got %d", w.Code)
		}
	})

	// UploadEnvelopes: the envelope upsert fails after validation passes.
	t.Run("envelope_insert", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "wf7.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		groupID := mkGroup(t, database, ownerID, ownerDev)
		database.Exec(`CREATE TRIGGER t_env BEFORE INSERT ON group_key_envelopes
			BEGIN SELECT RAISE(ABORT,'boom'); END`)
		env := envelopeUploadRequest{Envelopes: []envelopeItem{
			{DeviceID: ownerDev, EncryptedKey: b64("k"), Salt: b64("s"), Nonce: b64("n")},
		}}
		w := httptest.NewRecorder()
		r := as("POST", "/e", ownerID, ownerDev, env)
		r.SetPathValue("group_id", itoa(groupID))
		r.SetPathValue("version", "1")
		UploadEnvelopes(database, nil)(w, r)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("envelope insert fault: expected 500 got %d", w.Code)
		}
	})

	// activeDevices query fault: dropping the devices table makes the recipient
	// lookup error. The membership check (group_members + groups) still succeeds,
	// so both RotateGroupKey and UploadEnvelopes reach and surface the failure.
	t.Run("rotate_active_devices", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "wf8.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		groupID := mkGroup(t, database, ownerID, ownerDev)
		database.Exec(`DROP TABLE devices`)
		w := httptest.NewRecorder()
		r := as("POST", "/r", ownerID, ownerDev, nil)
		r.SetPathValue("group_id", itoa(groupID))
		RotateGroupKey(database)(w, r)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("rotate active-devices fault: expected 500 got %d", w.Code)
		}
	})

	t.Run("upload_active_devices", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "wf9.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		groupID := mkGroup(t, database, ownerID, ownerDev)
		database.Exec(`DROP TABLE devices`)
		env := envelopeUploadRequest{Envelopes: []envelopeItem{
			{DeviceID: ownerDev, EncryptedKey: b64("k"), Salt: b64("s"), Nonce: b64("n")},
		}}
		w := httptest.NewRecorder()
		r := as("POST", "/e", ownerID, ownerDev, env)
		r.SetPathValue("group_id", itoa(groupID))
		r.SetPathValue("version", "1")
		UploadEnvelopes(database, nil)(w, r)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("upload active-devices fault: expected 500 got %d", w.Code)
		}
	})
}

// Cover the member-permission and path-parse branches not hit elsewhere.
func TestMemberPermissionBranches(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "perm.db"))
	defer database.Close()
	ownerID, ownerDev := newUser(t, database, "owner")
	memID, memDev := newUser(t, database, "mem")
	groupID := mkGroup(t, database, ownerID, ownerDev, memID)
	wa := httptest.NewRecorder()
	ra := as("POST", "/a", memID, memDev, nil)
	ra.SetPathValue("group_id", itoa(groupID))
	AcceptInvitation(database, nil)(wa, ra) // mem is now an active plain member

	// Plain member cannot invite (role branch).
	w := httptest.NewRecorder()
	r := as("POST", "/g", memID, memDev, memberInviteRequest{UserID: 777})
	r.SetPathValue("group_id", itoa(groupID))
	InviteMember(database, nil)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("member invite: expected 403 got %d", w.Code)
	}

	// Plain member cannot remove.
	w = httptest.NewRecorder()
	r = as("DELETE", "/g", memID, memDev, nil)
	r.SetPathValue("group_id", itoa(groupID))
	r.SetPathValue("user_id", itoa(ownerID))
	RemoveMember(database)(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("member remove: expected 403 got %d", w.Code)
	}

	// AcceptInvitation with an invalid group id path.
	w = httptest.NewRecorder()
	r = as("POST", "/g", memID, memDev, nil)
	r.SetPathValue("group_id", "bad")
	AcceptInvitation(database, nil)(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("accept invalid path: expected 400 got %d", w.Code)
	}
}

// Cover in-transaction DB error branches by dropping a table so the second
// statement fails while membership/earlier statements still succeed.
func TestTxErrorBranches(t *testing.T) {
	// CreateGroup: owner-member insert fails.
	t.Run("create_member_insert", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "tx1.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		database.Exec(`DROP TABLE group_members`)
		w := httptest.NewRecorder()
		CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: "T"}))
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("expected 500 got %d", w.Code)
		}
	})

	// CreateGroup: key-version insert fails.
	t.Run("create_keyversion_insert", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "tx2.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		database.Exec(`DROP TABLE group_key_versions`)
		w := httptest.NewRecorder()
		CreateGroup(database)(w, as("POST", "/g", ownerID, ownerDev, groupCreateRequest{Name: "T"}))
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("expected 500 got %d", w.Code)
		}
	})

	// AcceptInvitation: member-status update fails after the version bump.
	t.Run("accept_member_update", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "tx3.db"))
		defer database.Close()
		ownerID, ownerDev := newUser(t, database, "owner")
		bobID, bobDev := newUser(t, database, "bob")
		groupID := mkGroup(t, database, ownerID, ownerDev, bobID)
		// Recreate group_members without the columns AcceptInvitation writes? Simpler:
		// drop it entirely so the UPDATE group_members statement errors. The groups
		// UPDATE...RETURNING runs first and succeeds.
		database.Exec(`DROP TABLE group_members`)
		w := httptest.NewRecorder()
		r := as("POST", "/a", bobID, bobDev, nil)
		r.SetPathValue("group_id", itoa(groupID))
		AcceptInvitation(database, nil)(w, r)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("expected 500 got %d", w.Code)
		}
	})
}
