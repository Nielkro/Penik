package ws

import (
	"testing"

	"github.com/shamaton/msgpack/v2"

	"messenger/server/internal/config"
)

// callTestHub wires clients into a hub so signaling frames can be observed on
// their send channels without running the write pump.
func callTestHub(t *testing.T, devices map[int64]int64) (*Hub, map[int64]*Client) {
	t.Helper()
	hub := NewHub()
	cfg := &config.Config{
		LiveKitURL:         "wss://primary.invalid",
		LiveKitFallbackURL: "wss://fallback.invalid",
		LiveKitAPIKey:      "test-key",
		LiveKitAPISecret:   "test-secret-value-long-enough",
	}
	clients := make(map[int64]*Client, len(devices))
	hub.mu.Lock()
	for deviceID, userID := range devices {
		c := newClient(hub, nil, userID, deviceID, nil, cfg)
		hub.clients[deviceID] = c
		clients[deviceID] = c
	}
	hub.mu.Unlock()
	return hub, clients
}

// resetCallState clears the package-level call registries shared by all tests.
func resetCallState(ac *activeCall) {
	callsMu.Lock()
	activeCalls = map[string]*activeCall{}
	userCalls = map[int64]string{}
	if ac != nil {
		activeCalls[ac.CallID] = ac
		userCalls[ac.CallerID] = ac.CallID
		userCalls[ac.CalleeID] = ac.CallID
	}
	callsMu.Unlock()
}

// drainOpcodes reports the opcodes queued for a client, so a test can assert what
// the device was told without depending on payload details.
func drainOpcodes(c *Client) []Opcode {
	var ops []Opcode
	for {
		select {
		case frame := <-c.send:
			if len(frame) > 0 {
				ops = append(ops, Opcode(frame[0]))
			}
		default:
			return ops
		}
	}
}

func hasOpcode(ops []Opcode, want Opcode) bool {
	for _, op := range ops {
		if op == want {
			return true
		}
	}
	return false
}

func callExists(callID string) bool {
	callsMu.RLock()
	defer callsMu.RUnlock()
	_, ok := activeCalls[callID]
	return ok
}

// Answering on one device must silence the callee's other devices instead of
// leaving them ringing, and must bind the call to the device that answered.
func TestCallAcceptSilencesOtherCalleeDevices(t *testing.T) {
	_, clients := callTestHub(t, map[int64]int64{10: 1, 20: 2, 21: 2})
	resetCallState(&activeCall{
		CallID: "c-accept", CallerID: 1, CallerDeviceID: 10, CalleeID: 2,
		RingingDevices: map[int64]bool{20: true, 21: true},
		RoomName:       "c-accept",
	})
	defer resetCallState(nil)

	accept, _ := msgpack.Marshal(CallAccept{CallID: "c-accept"})
	if err := clients[20].handleCallAccept(accept); err != nil {
		t.Fatalf("accept: %v", err)
	}

	if ops := drainOpcodes(clients[21]); !hasOpcode(ops, OpCallTaken) {
		t.Errorf("other callee device must be told the call was taken, got %v", ops)
	}
	if ops := drainOpcodes(clients[10]); !hasOpcode(ops, OpCallAccepted) {
		t.Errorf("caller must receive CallAccepted, got %v", ops)
	}

	callsMu.RLock()
	ac := activeCalls["c-accept"]
	callsMu.RUnlock()
	if ac == nil || ac.CalleeDeviceID != 20 || !ac.Accepted {
		t.Fatalf("call must be bound to the answering device, got %+v", ac)
	}
}

// A device that answered after another one already did must be told the call is
// taken rather than hijacking the established session.
func TestCallAcceptLosingRaceDoesNotRebind(t *testing.T) {
	_, clients := callTestHub(t, map[int64]int64{10: 1, 20: 2, 21: 2})
	resetCallState(&activeCall{
		CallID: "c-race", CallerID: 1, CallerDeviceID: 10, CalleeID: 2,
		CalleeDeviceID: 20, Accepted: true, RoomName: "c-race",
	})
	defer resetCallState(nil)

	accept, _ := msgpack.Marshal(CallAccept{CallID: "c-race"})
	if err := clients[21].handleCallAccept(accept); err != nil {
		t.Fatalf("accept: %v", err)
	}

	if ops := drainOpcodes(clients[21]); !hasOpcode(ops, OpCallTaken) {
		t.Errorf("late device must be told the call was taken, got %v", ops)
	}
	callsMu.RLock()
	ac := activeCalls["c-race"]
	callsMu.RUnlock()
	if ac.CalleeDeviceID != 20 {
		t.Fatalf("answering device must keep the call, got device %d", ac.CalleeDeviceID)
	}
}

// Declining on the phone while the browser is still ringing must only silence the
// phone: the user can still pick up elsewhere.
func TestCallRejectOnOneRingingDeviceKeepsCallAlive(t *testing.T) {
	_, clients := callTestHub(t, map[int64]int64{10: 1, 20: 2, 21: 2})
	resetCallState(&activeCall{
		CallID: "c-partial", CallerID: 1, CallerDeviceID: 10, CalleeID: 2,
		RingingDevices: map[int64]bool{20: true, 21: true},
		RoomName:       "c-partial",
	})
	defer resetCallState(nil)

	reject, _ := msgpack.Marshal(CallReject{CallID: "c-partial", ToUserID: 1, Reason: "declined"})
	if err := clients[21].handleCallReject(reject); err != nil {
		t.Fatalf("reject: %v", err)
	}

	if !callExists("c-partial") {
		t.Fatal("call must survive a decline while another device is ringing")
	}
	if ops := drainOpcodes(clients[10]); hasOpcode(ops, OpCallReject) {
		t.Errorf("caller must not be told the call was declined yet, got %v", ops)
	}
	if ops := drainOpcodes(clients[21]); !hasOpcode(ops, OpCallTaken) {
		t.Errorf("declining device must be silenced, got %v", ops)
	}

	// The last ringing device declining ends the call for both sides.
	if err := clients[20].handleCallReject(reject); err != nil {
		t.Fatalf("reject: %v", err)
	}
	if callExists("c-partial") {
		t.Fatal("call must end once the last ringing device declines")
	}
	if ops := drainOpcodes(clients[10]); !hasOpcode(ops, OpCallReject) {
		t.Errorf("caller must be told the call was declined, got %v", ops)
	}
}

// Once a device answered, a decline arriving from another device of the same user
// (a stale ring UI, a notification action) must not hang up the live call.
func TestCallRejectFromNonOwningDeviceKeepsAcceptedCall(t *testing.T) {
	_, clients := callTestHub(t, map[int64]int64{10: 1, 20: 2, 21: 2})
	resetCallState(&activeCall{
		CallID: "c-owned", CallerID: 1, CallerDeviceID: 10, CalleeID: 2,
		CalleeDeviceID: 20, Accepted: true, RoomName: "c-owned",
	})
	defer resetCallState(nil)

	reject, _ := msgpack.Marshal(CallReject{CallID: "c-owned", ToUserID: 1, Reason: "declined"})
	if err := clients[21].handleCallReject(reject); err != nil {
		t.Fatalf("reject: %v", err)
	}
	if !callExists("c-owned") {
		t.Fatal("accepted call must survive a decline from another device")
	}
	if ops := drainOpcodes(clients[10]); hasOpcode(ops, OpCallReject) {
		t.Errorf("caller must not be told the accepted call was declined, got %v", ops)
	}

	end, _ := msgpack.Marshal(CallEnd{CallID: "c-owned", ToUserID: 1})
	if err := clients[21].handleCallEnd(end); err != nil {
		t.Fatalf("end: %v", err)
	}
	if !callExists("c-owned") {
		t.Fatal("accepted call must survive a hangup from a non-participating device")
	}

	// The device actually in the call can end it.
	if err := clients[20].handleCallEnd(end); err != nil {
		t.Fatalf("end: %v", err)
	}
	if callExists("c-owned") {
		t.Fatal("participating device must be able to end the call")
	}
	if ops := drainOpcodes(clients[10]); !hasOpcode(ops, OpCallEnd) {
		t.Errorf("caller must be told the call ended, got %v", ops)
	}
}

// The caller cancelling must reach every device of the callee that is ringing.
func TestCallerCancelReachesAllRingingDevices(t *testing.T) {
	_, clients := callTestHub(t, map[int64]int64{10: 1, 20: 2, 21: 2})
	resetCallState(&activeCall{
		CallID: "c-cancel", CallerID: 1, CallerDeviceID: 10, CalleeID: 2,
		RingingDevices: map[int64]bool{20: true, 21: true},
		RoomName:       "c-cancel",
	})
	defer resetCallState(nil)

	reject, _ := msgpack.Marshal(CallReject{CallID: "c-cancel", ToUserID: 2, Reason: "declined"})
	if err := clients[10].handleCallReject(reject); err != nil {
		t.Fatalf("reject: %v", err)
	}
	if callExists("c-cancel") {
		t.Fatal("caller cancelling must release the call")
	}
	for _, devID := range []int64{20, 21} {
		if ops := drainOpcodes(clients[devID]); !hasOpcode(ops, OpCallReject) {
			t.Errorf("device %d must stop ringing, got %v", devID, ops)
		}
	}
}

// A ringing device dropping its socket must not cancel the offer for the callee's
// remaining devices, but the caller is released once none are left.
func TestCleanupDeviceCallsTracksRingingSet(t *testing.T) {
	hub, clients := callTestHub(t, map[int64]int64{10: 1, 20: 2, 21: 2})
	resetCallState(&activeCall{
		CallID: "c-drop", CallerID: 1, CallerDeviceID: 10, CalleeID: 2,
		RingingDevices: map[int64]bool{20: true, 21: true},
		RoomName:       "c-drop",
	})
	defer resetCallState(nil)

	hub.mu.Lock()
	delete(hub.clients, 21)
	hub.mu.Unlock()
	CleanupDeviceCalls(hub, 2, 21)
	if !callExists("c-drop") {
		t.Fatal("offer must keep ringing on the callee's remaining device")
	}

	hub.mu.Lock()
	delete(hub.clients, 20)
	hub.mu.Unlock()
	CleanupDeviceCalls(hub, 2, 20)
	if callExists("c-drop") {
		t.Fatal("offer must be released once no callee device is left")
	}
	if ops := drainOpcodes(clients[10]); !hasOpcode(ops, OpCallEnd) {
		t.Errorf("caller must be told the call ended, got %v", ops)
	}
}
