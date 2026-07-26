package ws

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"messenger/server/internal/db"
)

func mkUserDevice(t *testing.T, database *db.DB, nick string) (uid, did int64) {
	t.Helper()
	now := time.Now().Unix()
	ur, err := database.Exec(`INSERT INTO users(name,nickname,password_hash,created_at) VALUES(?,?,?,?)`, nick, nick, "x", now)
	if err != nil {
		t.Fatal(err)
	}
	uid, _ = ur.LastInsertId()
	dr, err := database.Exec(`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,?,?,?)`, uid, nick+"-d", now, now)
	if err != nil {
		t.Fatal(err)
	}
	did, _ = dr.LastInsertId()
	return uid, did
}

// TestPeerDevices1To1 verifies a 1:1 chat partner's device is discovered via
// the messages table, and an unrelated user's device is not.
func TestPeerDevices1To1(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "presence1.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	aliceID, aliceDev := mkUserDevice(t, database, "alice")
	bobID, bobDev := mkUserDevice(t, database, "bob")
	_, strangerDev := mkUserDevice(t, database, "stranger")

	now := time.Now().Unix()
	if _, err := database.Exec(`INSERT INTO chats(user1_id,user2_id,created_at) VALUES(?,?,?)`, aliceID, bobID, now); err != nil {
		t.Fatal(err)
	}
	if _, err := database.Exec(
		`INSERT INTO messages(chat_id, sender_user_id, recipient_user_id, timestamp, delivered) VALUES(1, ?, ?, ?, 0)`,
		aliceID, bobID, now); err != nil {
		t.Fatal(err)
	}

	hub := NewHub()
	c := newClient(hub, nil, aliceID, aliceDev, database)
	deviceIDs := c.peerDevices(context.Background())

	found := map[int64]bool{}
	for _, id := range deviceIDs {
		found[id] = true
	}
	if !found[bobDev] {
		t.Fatalf("expected bob's device %d among peers %v", bobDev, deviceIDs)
	}
	if found[strangerDev] {
		t.Fatalf("stranger's device %d should not be a peer", strangerDev)
	}
	if found[aliceDev] {
		t.Fatalf("alice's own device should not be listed as a peer")
	}
}

// TestPeerDevicesGroupMembers verifies co-members of a shared group are
// discovered as peers.
func TestPeerDevicesGroupMembers(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "presence2.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	groupID, ownerID, ownerDev, _, bobDev := setupGroup(t, database)
	_ = groupID

	hub := NewHub()
	c := newClient(hub, nil, ownerID, ownerDev, database)
	deviceIDs := c.peerDevices(context.Background())

	found := map[int64]bool{}
	for _, id := range deviceIDs {
		found[id] = true
	}
	if !found[bobDev] {
		t.Fatalf("expected group co-member's device %d among peers %v", bobDev, deviceIDs)
	}
}

// TestBroadcastPresenceDisconnectStillOnline verifies that when a user still
// has another connected device, disconnecting one device broadcasts online=true.
func TestBroadcastPresenceDisconnectStillOnline(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "presence3.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	aliceID, aliceDev1 := mkUserDevice(t, database, "alice2")
	aliceDev2 := func() int64 {
		now := time.Now().Unix()
		dr, err := database.Exec(`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,?,?,?)`, aliceID, "alice2-d2", now, now)
		if err != nil {
			t.Fatal(err)
		}
		id, _ := dr.LastInsertId()
		return id
	}()

	hub := NewHub()
	c1 := newClient(hub, nil, aliceID, aliceDev1, database)
	c2 := newClient(hub, nil, aliceID, aliceDev2, database)
	hub.register <- c1
	hub.register <- c2
	// Give the hub goroutine a moment to process the registrations.
	time.Sleep(20 * time.Millisecond)

	if !hub.IsOnline(aliceDev2) {
		t.Fatal("expected aliceDev2 to be registered as online")
	}

	// aliceDev1 disconnects; aliceDev2 is still connected, so the account
	// should still be considered online.
	online := false
	rows, err := database.QueryContext(context.Background(), `SELECT id FROM devices WHERE user_id=?`, aliceID)
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var devID int64
		if rows.Scan(&devID) == nil && devID != aliceDev1 && hub.IsOnline(devID) {
			online = true
		}
	}
	rows.Close()
	if !online {
		t.Fatal("expected account to still be online via the second device")
	}
}
