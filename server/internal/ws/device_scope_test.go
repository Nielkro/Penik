package ws

import (
	"context"
	"path/filepath"
	"testing"
	"time"

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
// already running on the device that answered. The device that is actually in the
// call only ends it after the reconnect grace period expires.
func TestCleanupDeviceCallsKeepsCallOnOtherDevice(t *testing.T) {
	hub := NewHub()
	restoreGrace := reconnectGrace
	reconnectGrace = 30 * time.Millisecond
	defer func() { reconnectGrace = restoreGrace }()

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

	// The device actually in the call goes away: held open for the grace window.
	CleanupDeviceCalls(hub, 2, 20)
	callsMu.RLock()
	_, stillUp = activeCalls["c-1"]
	callsMu.RUnlock()
	if !stillUp {
		t.Fatal("call must survive the reconnect grace window")
	}

	time.Sleep(120 * time.Millisecond)
	callsMu.RLock()
	_, stillUp = activeCalls["c-1"]
	_, callerBusy := userCalls[1]
	callsMu.RUnlock()
	if stillUp || callerBusy {
		t.Fatal("call must be released once the grace window expires")
	}
}

// A device that comes back inside the grace window keeps its call: this is the
// Wi-Fi to mobile handover case, where the signaling socket dies for a second
// while the media session is fine.
func TestResumeDeviceCallsCancelsGraceDrop(t *testing.T) {
	hub := NewHub()
	restoreGrace := reconnectGrace
	reconnectGrace = 80 * time.Millisecond
	defer func() { reconnectGrace = restoreGrace }()

	callsMu.Lock()
	activeCalls = map[string]*activeCall{}
	userCalls = map[int64]string{}
	answered := time.Now().Add(-30 * time.Second)
	ac := &activeCall{
		CallID: "c-3", CallerID: 1, CallerDeviceID: 10,
		CalleeID: 2, CalleeDeviceID: 20, Accepted: true,
		AnsweredAt: &answered,
	}
	activeCalls[ac.CallID] = ac
	userCalls[1] = ac.CallID
	userCalls[2] = ac.CallID
	callsMu.Unlock()

	CleanupDeviceCalls(hub, 2, 20)

	var replayed []byte
	ResumeDeviceCalls(hub, 2, 20, func(frame []byte) { replayed = frame })
	if len(replayed) == 0 || Opcode(replayed[0]) != OpCallState {
		t.Fatalf("reconnecting device must receive OpCallState, got %v", replayed)
	}

	time.Sleep(160 * time.Millisecond)
	callsMu.RLock()
	_, stillUp := activeCalls["c-3"]
	callsMu.RUnlock()
	if !stillUp {
		t.Fatal("a device that reconnected in time must keep its call")
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
