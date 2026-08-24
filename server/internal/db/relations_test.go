package db

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

func mkUser(t *testing.T, d *DB, nick string) int64 {
	t.Helper()
	res, err := d.Exec(`INSERT INTO users(name,nickname,password_hash,created_at) VALUES(?,?,?,?)`,
		nick, nick, "x", time.Now().Unix())
	if err != nil {
		t.Fatal(err)
	}
	id, _ := res.LastInsertId()
	return id
}

// UsersShareChat gates presence and typing, so an unrelated pair must read as
// false regardless of column order in the chats row.
func TestUsersShareChat(t *testing.T) {
	database, err := Open(filepath.Join(t.TempDir(), "rel.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	alice := mkUser(t, database, "relalice")
	bob := mkUser(t, database, "relbob")
	carol := mkUser(t, database, "relcarol")
	ctx := context.Background()
	now := time.Now().Unix()

	check := func(a, b int64, want bool, label string) {
		t.Helper()
		got, err := database.UsersShareChat(ctx, a, b)
		if err != nil {
			t.Fatalf("%s: %v", label, err)
		}
		if got != want {
			t.Fatalf("%s: got %v, want %v", label, got, want)
		}
	}

	check(alice, bob, false, "strangers")
	check(alice, alice, true, "self")
	check(0, bob, false, "unauthenticated viewer")

	cr, err := database.Exec(`INSERT INTO chats(user1_id,user2_id,created_at) VALUES(?,?,?)`, alice, bob, now)
	if err != nil {
		t.Fatal(err)
	}
	chatID, _ := cr.LastInsertId()

	// One-way message: Alice writes to Bob, but Bob hasn't replied yet.
	if _, err := database.Exec(`INSERT INTO messages(chat_id,sender_user_id,recipient_user_id,timestamp) VALUES(?,?,?,?)`, chatID, alice, bob, now); err != nil {
		t.Fatal(err)
	}
	check(alice, bob, false, "one-way message: not mutual yet")
	check(bob, alice, false, "one-way message reversed: not mutual yet")

	// Bob replies to Alice -> now it is mutual!
	if _, err := database.Exec(`INSERT INTO messages(chat_id,sender_user_id,recipient_user_id,timestamp) VALUES(?,?,?,?)`, chatID, bob, alice, now); err != nil {
		t.Fatal(err)
	}
	check(alice, bob, true, "mutual messages (alice -> bob & bob -> alice)")
	check(bob, alice, true, "mutual messages reversed")
	check(alice, carol, false, "still a stranger")

	// A shared group is the second relationship kind.
	gr, err := database.Exec(`INSERT INTO groups(name,owner_user_id,created_at,updated_at,membership_version,current_key_version) VALUES(?,?,?,?,1,1)`,
		"G", alice, now, now)
	if err != nil {
		t.Fatal(err)
	}
	groupID, _ := gr.LastInsertId()
	for _, uid := range []int64{alice, carol} {
		if _, err := database.Exec(`INSERT INTO group_members(group_id,user_id,role,status,joined_at,membership_version) VALUES(?,?,'member','active',?,1)`,
			groupID, uid, now); err != nil {
			t.Fatal(err)
		}
	}
	check(alice, carol, true, "shared group")
	check(bob, carol, false, "no common group or chat")
}
