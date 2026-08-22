package ws

import (
	"context"
	"path/filepath"
	"testing"

	"messenger/server/internal/db"
)

// A sender must not be able to smuggle a third party's device into the device
// list: the ciphertext would be stored against this conversation but delivered
// to an account that is not part of it.
func TestMsgSendRejectsForeignDevice(t *testing.T) {
	database, err := db.Open(filepath.Join(t.TempDir(), "foreign-dev.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	aliceID, aliceDev := mkUserDevice(t, database, "sfalice")
	bobID, bobDev := mkUserDevice(t, database, "sfbob")
	_, eveDev := mkUserDevice(t, database, "sfeve")

	sender := newClient(NewHub(), nil, aliceID, aliceDev, database)
	msg := &MsgSendEncrypted{
		ToUserID: bobID,
		MsgID:    "m-foreign",
		Devices: []E2EPayload{
			{DeviceID: bobDev, Ciphertext: []byte("for-bob"), Salt: []byte("s"), Nonce: []byte("n")},
			{DeviceID: eveDev, Ciphertext: []byte("for-eve"), Salt: []byte("s"), Nonce: []byte("n")},
		},
	}
	if err := sender.handleMsgSend(context.Background(), msg); err != nil {
		t.Fatalf("send: %v", err)
	}

	var eveRows int
	if err := database.QueryRow(
		`SELECT COUNT(*) FROM messages WHERE recipient_device_id=?`, eveDev).Scan(&eveRows); err != nil {
		t.Fatal(err)
	}
	if eveRows != 0 {
		t.Errorf("foreign device must be skipped, got %d rows", eveRows)
	}

	var bobRows int
	if err := database.QueryRow(
		`SELECT COUNT(*) FROM messages WHERE recipient_device_id=?`, bobDev).Scan(&bobRows); err != nil {
		t.Fatal(err)
	}
	if bobRows != 1 {
		t.Errorf("recipient device must still receive the message, got %d rows", bobRows)
	}
}

// A second device of the callee dropping its socket must not end a call that is
// already running on the device that answered.
func TestCleanupDeviceCallsKeepsCallOnOtherDevice(t *testing.T) {
	hub := NewHub()
	callsMu.Lock()
	activeCalls = map[string]*activeCall{}
	userCalls = map[int64]string{}
	ac := &activeCall{
		CallID: "c-1", CallerID: 1, CallerDeviceID: 10,
		CalleeID: 2, CalleeDeviceID: 20, Accepted: true,
	}
	activeCalls[ac.CallID] = ac
	userCalls[1] = ac.CallID
	userCalls[2] = ac.CallID
	callsMu.Unlock()

	// Callee's idle second device (21) goes away.
	CleanupDeviceCalls(hub, 2, 21)
	callsMu.RLock()
	_, stillUp := activeCalls["c-1"]
	callsMu.RUnlock()
	if !stillUp {
		t.Fatal("call must survive an unrelated device disconnect")
	}

	// The device actually in the call goes away.
	CleanupDeviceCalls(hub, 2, 20)
	callsMu.RLock()
	_, stillUp = activeCalls["c-1"]
	_, callerBusy := userCalls[1]
	callsMu.RUnlock()
	if stillUp || callerBusy {
		t.Fatal("call must be released when the participating device drops")
	}
}

// While an offer is still ringing the callee has no bound device, so the call is
// released only once the last device of theirs is gone.
func TestCleanupDeviceCallsRingingWaitsForLastDevice(t *testing.T) {
	hub := NewHub()
	other := newClient(hub, nil, 2, 21, nil)
	hub.mu.Lock()
	hub.clients[21] = other
	hub.mu.Unlock()

	callsMu.Lock()
	activeCalls = map[string]*activeCall{}
	userCalls = map[int64]string{}
	ac := &activeCall{CallID: "c-2", CallerID: 1, CallerDeviceID: 10, CalleeID: 2}
	activeCalls[ac.CallID] = ac
	userCalls[1] = ac.CallID
	userCalls[2] = ac.CallID
	callsMu.Unlock()

	CleanupDeviceCalls(hub, 2, 20)
	callsMu.RLock()
	_, stillUp := activeCalls["c-2"]
	callsMu.RUnlock()
	if !stillUp {
		t.Fatal("ringing call must survive while another callee device is online")
	}

	hub.mu.Lock()
	delete(hub.clients, 21)
	hub.mu.Unlock()

	CleanupDeviceCalls(hub, 2, 20)
	callsMu.RLock()
	_, stillUp = activeCalls["c-2"]
	callsMu.RUnlock()
	if stillUp {
		t.Fatal("ringing call must be released once no callee device is left")
	}
}
