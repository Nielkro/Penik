package ws

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/db"
)

func setupGroup(t *testing.T, database *db.DB) (groupID, ownerID, ownerDev, bobID, bobDev int64) {
	t.Helper()
	now := time.Now().Unix()
	mk := func(nick string) (uid, did int64) {
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
	ownerID, ownerDev = mk("gowner")
	bobID, bobDev = mk("gbob")

	gr, err := database.Exec(`INSERT INTO groups(name,owner_user_id,created_at,updated_at,membership_version,current_key_version) VALUES(?,?,?,?,1,1)`, "G", ownerID, now, now)
	if err != nil {
		t.Fatal(err)
	}
	groupID, _ = gr.LastInsertId()
	database.Exec(`INSERT INTO group_members(group_id,user_id,role,status,joined_at,membership_version) VALUES(?,?,'owner','active',?,1)`, groupID, ownerID, now)
	database.Exec(`INSERT INTO group_members(group_id,user_id,role,status,joined_at,membership_version) VALUES(?,?,'member','active',?,1)`, groupID, bobID, now)
	database.Exec(`INSERT INTO group_key_versions(group_id,key_version,created_by_user_id,membership_version,created_at) VALUES(?,1,?,1,?)`, groupID, ownerID, now)
	return
}

func TestGroupMessageSendFanoutAndIdempotency(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "gmsg.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	groupID, ownerID, ownerDev, _, bobDev := setupGroup(t, database)
	hub := NewHub()
	sender := newClient(hub, nil, ownerID, ownerDev, database)

	msg := &GroupMessageSend{
		GroupID: groupID, MessageID: "m-1", KeyVersion: 1,
		Ciphertext: []byte("hello"), Salt: []byte("s"), Nonce: []byte("n"),
		CreatedAt: time.Now().Unix(),
	}
	if err := sender.handleGroupMessageSend(context.Background(), msg); err != nil {
		t.Fatalf("send: %v", err)
	}

	// One message row persisted.
	var count int
	database.QueryRow(`SELECT COUNT(*) FROM group_messages WHERE group_id=?`, groupID).Scan(&count)
	if count != 1 {
		t.Fatalf("expected 1 message, got %d", count)
	}

	// Bob's device is tracked as a recipient; sender's own device is not.
	var bobTracked, ownerTracked int
	database.QueryRow(`SELECT COUNT(*) FROM group_message_devices gmd JOIN group_messages gm ON gm.id=gmd.message_id WHERE gm.group_id=? AND gmd.device_id=?`, groupID, bobDev).Scan(&bobTracked)
	database.QueryRow(`SELECT COUNT(*) FROM group_message_devices gmd JOIN group_messages gm ON gm.id=gmd.message_id WHERE gm.group_id=? AND gmd.device_id=?`, groupID, ownerDev).Scan(&ownerTracked)
	if bobTracked != 1 {
		t.Fatalf("bob should be tracked once, got %d", bobTracked)
	}
	if ownerTracked != 0 {
		t.Fatalf("sender device should not be a recipient, got %d", ownerTracked)
	}

	// Resend same message_id: idempotent, no second row.
	if err := sender.handleGroupMessageSend(context.Background(), msg); err != nil {
		t.Fatalf("resend: %v", err)
	}
	database.QueryRow(`SELECT COUNT(*) FROM group_messages WHERE group_id=?`, groupID).Scan(&count)
	if count != 1 {
		t.Fatalf("idempotency broken: %d rows", count)
	}
}

func TestGroupMessageNonMemberRejected(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "gmsg2.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	groupID, _, _, _, _ := setupGroup(t, database)
	now := time.Now().Unix()
	// An outsider with a device but no membership.
	ur, _ := database.Exec(`INSERT INTO users(name,nickname,password_hash,created_at) VALUES('eve','eve','x',?)`, now)
	eveID, _ := ur.LastInsertId()
	dr, _ := database.Exec(`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,'eve-d',?,?)`, eveID, now, now)
	eveDev, _ := dr.LastInsertId()

	hub := NewHub()
	eve := newClient(hub, nil, eveID, eveDev, database)
	err = eve.handleGroupMessageSend(context.Background(), &GroupMessageSend{
		GroupID: groupID, MessageID: "x", KeyVersion: 1, Ciphertext: []byte("hi"),
		CreatedAt: time.Now().Unix(),
	})
	if err == nil {
		t.Fatal("expected non-member send to be rejected")
	}
}

// Regression: the same sender may reuse a message_id in a different group.
func TestGroupMessageIdempotencyIsGroupScoped(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "gmsg3.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	g1, ownerID, ownerDev, _, _ := setupGroup(t, database)

	// A second group owned by the same user with the same active membership.
	now := time.Now().Unix()
	gr, _ := database.Exec(`INSERT INTO groups(name,owner_user_id,created_at,updated_at,membership_version,current_key_version) VALUES('G2',?,?,?,1,1)`, ownerID, now, now)
	g2, _ := gr.LastInsertId()
	database.Exec(`INSERT INTO group_members(group_id,user_id,role,status,joined_at,membership_version) VALUES(?,?,'owner','active',?,1)`, g2, ownerID, now)
	database.Exec(`INSERT INTO group_key_versions(group_id,key_version,created_by_user_id,membership_version,created_at) VALUES(?,1,?,1,?)`, g2, ownerID, now)

	hub := NewHub()
	sender := newClient(hub, nil, ownerID, ownerDev, database)

	send := func(gid int64) error {
		return sender.handleGroupMessageSend(context.Background(), &GroupMessageSend{
			GroupID: gid, MessageID: "shared-id", KeyVersion: 1,
			Ciphertext: []byte("hi"), Salt: []byte("s"), Nonce: []byte("n"),
			CreatedAt: time.Now().Unix(),
		})
	}
	if err := send(g1); err != nil {
		t.Fatalf("send to g1: %v", err)
	}
	if err := send(g2); err != nil {
		t.Fatalf("send to g2 with same message_id: %v", err)
	}
	var c1, c2 int
	database.QueryRow(`SELECT COUNT(*) FROM group_messages WHERE group_id=?`, g1).Scan(&c1)
	database.QueryRow(`SELECT COUNT(*) FROM group_messages WHERE group_id=?`, g2).Scan(&c2)
	if c1 != 1 || c2 != 1 {
		t.Fatalf("expected one message per group, got g1=%d g2=%d", c1, c2)
	}
}

// Regression: a revoked key version must be rejected for new messages.
func TestGroupMessageRevokedVersionRejected(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "gmsg4.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	groupID, ownerID, ownerDev, _, _ := setupGroup(t, database)
	database.Exec(`UPDATE group_key_versions SET revoked_at=? WHERE group_id=? AND key_version=1`, time.Now().Unix(), groupID)

	hub := NewHub()
	sender := newClient(hub, nil, ownerID, ownerDev, database)
	err = sender.handleGroupMessageSend(context.Background(), &GroupMessageSend{
		GroupID: groupID, MessageID: "m", KeyVersion: 1, Ciphertext: []byte("hi"),
		CreatedAt: time.Now().Unix(),
	})
	if err == nil {
		t.Fatal("expected revoked key version to be rejected")
	}
}

// readFrames drains all frames currently queued on a client's send channel.
func readFrames(c *Client) [][]byte {
	var out [][]byte
	for {
		select {
		case f := <-c.send:
			out = append(out, f)
		default:
			return out
		}
	}
}

// Regression: the server must persist and relay the client's created_at verbatim
// (it is bound into the AAD). Rewriting it with server time would make every
// recipient's decryption fail.
func TestGroupMessagePreservesClientTimestamp(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "gts.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	groupID, ownerID, ownerDev, bobID, bobDev := setupGroup(t, database)
	hub := NewHub()
	sender := newClient(hub, nil, ownerID, ownerDev, database)
	// A connected recipient so we can inspect the relayed GroupMessageRecv frame.
	bob := newClient(hub, nil, bobID, bobDev, database)
	hub.register <- bob
	time.Sleep(20 * time.Millisecond)

	clientTS := time.Now().Unix() - 120 // deliberately not "now"
	msg := &GroupMessageSend{
		GroupID: groupID, MessageID: "ts-1", KeyVersion: 1,
		Ciphertext: []byte("ct"), Salt: []byte("s"), Nonce: []byte("n"),
		CreatedAt: clientTS,
	}
	if err := sender.handleGroupMessageSend(context.Background(), msg); err != nil {
		t.Fatalf("send: %v", err)
	}

	// Stored timestamp must equal the client's, not server time.
	var stored int64
	database.QueryRow(`SELECT created_at FROM group_messages WHERE group_id=? AND message_id=?`, groupID, "ts-1").Scan(&stored)
	if stored != clientTS {
		t.Fatalf("stored created_at = %d, want client value %d", stored, clientTS)
	}

	// Relayed recv frame must carry the same timestamp.
	time.Sleep(20 * time.Millisecond)
	var recvTS int64
	for _, f := range readFrames(bob) {
		if Opcode(f[0]) != OpGroupMessageRecv {
			continue
		}
		var recv GroupMessageRecv
		if err := msgpack.Unmarshal(f[1:], &recv); err != nil {
			t.Fatalf("unmarshal recv: %v", err)
		}
		recvTS = recv.CreatedAt
	}
	if recvTS != clientTS {
		t.Fatalf("relayed created_at = %d, want %d", recvTS, clientTS)
	}
}

func TestGroupMessageRejectsMissingOrSkewedTimestamp(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "gts2.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	groupID, ownerID, ownerDev, _, _ := setupGroup(t, database)
	hub := NewHub()
	sender := newClient(hub, nil, ownerID, ownerDev, database)

	base := &GroupMessageSend{
		GroupID: groupID, KeyVersion: 1,
		Ciphertext: []byte("ct"), Salt: []byte("s"), Nonce: []byte("n"),
	}
	cases := []struct {
		name string
		ts   int64
	}{
		{"missing", 0},
		{"negative", -1},
		{"far future", time.Now().Unix() + maxGroupClockSkew + 60},
		{"too old", time.Now().Unix() - maxGroupTimestampAge - 60},
	}
	for i, tc := range cases {
		m := *base
		m.MessageID = tc.name
		m.CreatedAt = tc.ts
		if err := sender.handleGroupMessageSend(context.Background(), &m); err == nil {
			t.Fatalf("case %d (%s): expected rejection, got nil", i, tc.name)
		}
	}

	// A timestamp within skew is accepted.
	ok := *base
	ok.MessageID = "ok"
	ok.CreatedAt = time.Now().Unix() + 1
	if err := sender.handleGroupMessageSend(context.Background(), &ok); err != nil {
		t.Fatalf("in-window timestamp rejected: %v", err)
	}
}

func TestGroupMessageUnknownKeyVersionRejected(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "gkv.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	groupID, ownerID, ownerDev, _, _ := setupGroup(t, database)
	hub := NewHub()
	sender := newClient(hub, nil, ownerID, ownerDev, database)
	err = sender.handleGroupMessageSend(context.Background(), &GroupMessageSend{
		GroupID: groupID, MessageID: "m", KeyVersion: 99,
		Ciphertext: []byte("hi"), Salt: []byte("s"), Nonce: []byte("n"),
		CreatedAt: time.Now().Unix(),
	})
	if err == nil {
		t.Fatal("expected unknown key version to be rejected")
	}
}

func TestGroupMessageInvalidFieldSizes(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "gfs.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	groupID, ownerID, ownerDev, _, _ := setupGroup(t, database)
	hub := NewHub()
	sender := newClient(hub, nil, ownerID, ownerDev, database)
	now := time.Now().Unix()

	bad := []*GroupMessageSend{
		{GroupID: groupID, MessageID: "", KeyVersion: 1, Ciphertext: []byte("x"), CreatedAt: now},
		{GroupID: groupID, MessageID: "a", KeyVersion: 1, Ciphertext: nil, CreatedAt: now},
		{GroupID: groupID, MessageID: "b", KeyVersion: 1, Ciphertext: []byte("x"), Salt: make([]byte, maxGroupSalt+1), CreatedAt: now},
		{GroupID: groupID, MessageID: "c", KeyVersion: 1, Ciphertext: []byte("x"), Nonce: make([]byte, maxGroupNonce+1), CreatedAt: now},
	}
	for i, m := range bad {
		if err := sender.handleGroupMessageSend(context.Background(), m); err == nil {
			t.Fatalf("case %d: expected rejection", i)
		}
	}
}

func TestGroupMessageReceiptDeliveredThenRead(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "grcpt.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	groupID, ownerID, ownerDev, bobID, bobDev := setupGroup(t, database)
	hub := NewHub()
	sender := newClient(hub, nil, ownerID, ownerDev, database)
	if err := sender.handleGroupMessageSend(context.Background(), &GroupMessageSend{
		GroupID: groupID, MessageID: "r-1", KeyVersion: 1,
		Ciphertext: []byte("hi"), Salt: []byte("s"), Nonce: []byte("n"),
		CreatedAt: time.Now().Unix(),
	}); err != nil {
		t.Fatalf("send: %v", err)
	}
	var msgRowID int64
	database.QueryRow(`SELECT id FROM group_messages WHERE group_id=?`, groupID).Scan(&msgRowID)

	bob := newClient(hub, nil, bobID, bobDev, database)
	if err := bob.handleGroupMessageReceipt(context.Background(), msgRowID, false); err != nil {
		t.Fatalf("delivered: %v", err)
	}
	var deliveredAt, readAt any
	database.QueryRow(`SELECT delivered_at, read_at FROM group_message_devices WHERE message_id=? AND device_id=?`, msgRowID, bobDev).Scan(&deliveredAt, &readAt)
	if deliveredAt == nil {
		t.Fatal("delivered_at should be set")
	}
	if readAt != nil {
		t.Fatal("read_at should still be null")
	}

	if err := bob.handleGroupMessageReceipt(context.Background(), msgRowID, true); err != nil {
		t.Fatalf("read: %v", err)
	}
	database.QueryRow(`SELECT read_at FROM group_message_devices WHERE message_id=? AND device_id=?`, msgRowID, bobDev).Scan(&readAt)
	if readAt == nil {
		t.Fatal("read_at should be set after read receipt")
	}
}

// TestGroupSendDBErrorBranches drives the internal DB-error paths of
// handleGroupMessageSend that are unreachable through normal input validation.
func TestGroupSendDBErrorBranches(t *testing.T) {
	valid := func(gid int64) *GroupMessageSend {
		return &GroupMessageSend{
			GroupID: gid, MessageID: "m", KeyVersion: 1,
			Ciphertext: []byte("hi"), Salt: []byte("s"), Nonce: []byte("n"),
			CreatedAt: time.Now().Unix(),
		}
	}

	// Membership lookup fails: closed DB makes the very first query error with a
	// non-ErrNoRows error.
	t.Run("membership_lookup", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "e1.db"))
		groupID, ownerID, ownerDev, _, _ := setupGroup(t, database)
		database.Close()
		c := newClient(NewHub(), nil, ownerID, ownerDev, database)
		if err := c.handleGroupMessageSend(context.Background(), valid(groupID)); err == nil {
			t.Fatal("expected membership lookup error")
		}
	})

	// Key-version lookup fails after membership succeeds.
	t.Run("keyversion_lookup", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "e2.db"))
		defer database.Close()
		groupID, ownerID, ownerDev, _, _ := setupGroup(t, database)
		database.Exec(`DROP TABLE group_key_versions`)
		c := newClient(NewHub(), nil, ownerID, ownerDev, database)
		if err := c.handleGroupMessageSend(context.Background(), valid(groupID)); err == nil {
			t.Fatal("expected key version lookup error")
		}
	})

	// Idempotency check fails: dropping group_messages makes the SELECT error with
	// a non-ErrNoRows error after membership and key-version succeed.
	t.Run("idempotency_check", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "e3.db"))
		defer database.Close()
		groupID, ownerID, ownerDev, _, _ := setupGroup(t, database)
		database.Exec(`DROP TABLE group_messages`)
		c := newClient(NewHub(), nil, ownerID, ownerDev, database)
		if err := c.handleGroupMessageSend(context.Background(), valid(groupID)); err == nil {
			t.Fatal("expected idempotency check error")
		}
	})

	// Per-device tracking insert fails: drop group_message_devices so the message
	// row inserts and the recipient lookup succeeds, then the tracking insert errors.
	t.Run("track_device", func(t *testing.T) {
		database, _ := db.Open(filepath.Join(t.TempDir(), "e4.db"))
		defer database.Close()
		groupID, ownerID, ownerDev, _, _ := setupGroup(t, database)
		database.Exec(`DROP TABLE group_message_devices`)
		c := newClient(NewHub(), nil, ownerID, ownerDev, database)
		if err := c.handleGroupMessageSend(context.Background(), valid(groupID)); err == nil {
			t.Fatal("expected track-device error")
		}
	})
}

// TestGroupReceiptAndOfflineDBErrors covers the DB-error returns of the receipt
// and offline-batch handlers.
func TestGroupReceiptAndOfflineDBErrors(t *testing.T) {
	database, _ := db.Open(filepath.Join(t.TempDir(), "e5.db"))
	_, _, _, bobID, bobDev := setupGroup(t, database)
	database.Close()
	c := newClient(NewHub(), nil, bobID, bobDev, database)

	if err := c.handleGroupMessageReceipt(context.Background(), 1, false); err == nil {
		t.Fatal("expected delivered receipt error on closed db")
	}
	if err := c.handleGroupMessageReceipt(context.Background(), 1, true); err == nil {
		t.Fatal("expected read receipt error on closed db")
	}
	if err := c.sendGroupOfflineBatch(context.Background()); err == nil {
		t.Fatal("expected offline batch error on closed db")
	}
}

func TestGroupOfflineBatchDeliversUndelivered(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "goff.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	groupID, ownerID, ownerDev, bobID, bobDev := setupGroup(t, database)
	hub := NewHub()
	sender := newClient(hub, nil, ownerID, ownerDev, database)
	// Bob is offline: message is persisted and tracked, not delivered.
	if err := sender.handleGroupMessageSend(context.Background(), &GroupMessageSend{
		GroupID: groupID, MessageID: "off-1", KeyVersion: 1,
		Ciphertext: []byte("hi"), Salt: []byte("s"), Nonce: []byte("n"),
		CreatedAt: time.Now().Unix(),
	}); err != nil {
		t.Fatalf("send: %v", err)
	}

	bob := newClient(hub, nil, bobID, bobDev, database)
	if err := bob.sendGroupOfflineBatch(context.Background()); err != nil {
		t.Fatalf("offline batch: %v", err)
	}
	var got int
	for _, f := range readFrames(bob) {
		if Opcode(f[0]) == OpGroupMessageRecv {
			got++
		}
	}
	if got != 1 {
		t.Fatalf("expected 1 offline group message frame, got %d", got)
	}
}
