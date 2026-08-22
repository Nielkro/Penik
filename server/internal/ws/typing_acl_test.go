package ws

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"messenger/server/internal/db"
)

// Typing state says when someone is at their keyboard, so it may only reach a
// peer that already shares a chat or a group with the sender.
func TestHandleTypingRequiresRelationship(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "typing.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	now := time.Now().Unix()
	mk := func(nick string) (int64, int64) {
		ur, err := database.Exec(`INSERT INTO users(name,nickname,password_hash,created_at) VALUES(?,?,?,?)`, nick, nick, "x", now)
		if err != nil {
			t.Fatal(err)
		}
		uid, _ := ur.LastInsertId()
		dr, err := database.Exec(`INSERT INTO devices(user_id,device_name,created_at,last_seen) VALUES(?,?,?,?)`, uid, nick+"-d", now, now)
		if err != nil {
			t.Fatal(err)
		}
		did, _ := dr.LastInsertId()
		return uid, did
	}
	aliceID, aliceDev := mk("typalice")
	bobID, bobDev := mk("typbob")

	hub := NewHub()
	alice := newClient(hub, nil, aliceID, aliceDev, database)
	bob := newClient(hub, nil, bobID, bobDev, database)
	hub.register <- bob
	// Wait for the hub loop to pick the registration up.
	for i := 0; i < 100 && !hub.IsOnline(bobDev); i++ {
		time.Sleep(time.Millisecond)
	}

	req := &TypingNotify{ToUserID: bobID, IsTyping: true}
	if err := alice.handleTyping(context.Background(), req); err != nil {
		t.Fatalf("unrelated: %v", err)
	}
	if len(bob.send) != 0 {
		t.Fatal("typing leaked to a stranger")
	}

	if _, err := database.Exec(`INSERT INTO chats(user1_id,user2_id,created_at) VALUES(?,?,?)`, aliceID, bobID, now); err != nil {
		t.Fatal(err)
	}
	if err := alice.handleTyping(context.Background(), req); err != nil {
		t.Fatalf("related: %v", err)
	}
	select {
	case frame := <-bob.send:
		if len(frame) == 0 || Opcode(frame[0]) != OpTyping {
			t.Fatalf("unexpected frame %v", frame)
		}
	default:
		t.Fatal("chat partner did not receive the typing frame")
	}

	// A missing or non-positive target is ignored rather than queried.
	if err := alice.handleTyping(context.Background(), &TypingNotify{ToUserID: 0}); err != nil {
		t.Fatalf("zero target: %v", err)
	}
}
