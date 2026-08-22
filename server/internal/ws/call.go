package ws

import (
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/livekit/protocol/auth"
	"github.com/shamaton/msgpack/v2"
)

// ringTimeout bounds how long an unanswered offer may hold both participants in
// the userCalls map. Without it a callee that never answers stays permanently
// "busy" for everyone else.
const ringTimeout = 60 * time.Second

type activeCall struct {
	CallID string
	// Calls belong to a device, not just an account: a user with several
	// connected devices must not lose an in-progress call because an idle
	// device dropped its socket. CalleeDeviceID stays 0 until the callee
	// answers, because the offer rings every device the callee has.
	CallerDeviceID int64
	CalleeDeviceID int64
	CallerID       int64
	CalleeID       int64
	RoomName  string
	IsVideo   bool
	Accepted  bool
	StartedAt time.Time
}

// involves reports whether userID is one of the two participants. Call IDs are
// guessable, so every state transition must confirm the sender is in the call
// rather than trusting the ID alone.
func (a *activeCall) involves(userID int64) bool {
	return a != nil && (a.CallerID == userID || a.CalleeID == userID)
}

// peerOf returns the other participant of the call.
func (a *activeCall) peerOf(userID int64) int64 {
	if a.CallerID == userID {
		return a.CalleeID
	}
	return a.CallerID
}

// dropCall removes a call and both participants' busy markers. Callers must hold
// callsMu.
func dropCall(ac *activeCall) {
	delete(activeCalls, ac.CallID)
	delete(userCalls, ac.CallerID)
	delete(userCalls, ac.CalleeID)
}

var (
	callsMu     sync.RWMutex
	activeCalls = make(map[string]*activeCall) // callID -> activeCall
	userCalls   = make(map[int64]string)       // userID -> callID
)

func generateLiveKitToken(apiKey, apiSecret, roomName, participantIdentity string, ttl time.Duration) (string, error) {
	if apiKey == "" || apiSecret == "" {
		return "", fmt.Errorf("livekit API key or secret not configured")
	}
	if ttl <= 0 {
		ttl = 30 * time.Minute
	}

	at := auth.NewAccessToken(apiKey, apiSecret)
	grant := &auth.VideoGrant{
		RoomJoin: true,
		Room:     roomName,
	}
	grant.SetCanPublish(true)
	grant.SetCanSubscribe(true)
	at.AddGrant(grant).SetIdentity(participantIdentity).SetValidFor(ttl)

	return at.ToJWT()
}

func (c *Client) handleCallOffer(payload []byte) error {
	var offer CallOffer
	if err := msgpack.Unmarshal(payload, &offer); err != nil {
		return fmt.Errorf("unmarshal CallOffer: %w", err)
	}

	if offer.ToUserID == c.userID {
		return fmt.Errorf("cannot call yourself")
	}

	// Confirm the callee is a real account before reserving call state for them.
	// User IDs are sequential, so an unchecked offer lets a caller pin arbitrary
	// ids into userCalls.
	var calleeExists int
	if err := c.db.QueryRow(`SELECT 1 FROM users WHERE id=?`, offer.ToUserID).Scan(&calleeExists); err != nil {
		rejectPayload, _ := msgpack.Marshal(CallReject{
			CallID:   "",
			ToUserID: offer.ToUserID,
			Reason:   "offline",
		})
		c.hub.SendToDeviceFrame(c.deviceID, OpCallReject, rejectPayload)
		return nil
	}

	if !c.hub.IsUserOnline(offer.ToUserID) {
		// Callee has no active connections, so the incoming frame would never
		// be delivered. Reject right away instead of leaving the caller ringing.
		rejectPayload, _ := msgpack.Marshal(CallReject{
			CallID:   "",
			ToUserID: offer.ToUserID,
			Reason:   "offline",
		})
		c.hub.SendToDeviceFrame(c.deviceID, OpCallReject, rejectPayload)
		return nil
	}

	callsMu.Lock()
	if existingCallID, inCall := userCalls[c.userID]; inCall {
		callsMu.Unlock()
		// Caller is already in a call
		rejectPayload, _ := msgpack.Marshal(CallReject{
			CallID:   existingCallID,
			ToUserID: offer.ToUserID,
			Reason:   "busy",
		})
		c.hub.SendToDeviceFrame(c.deviceID, OpCallReject, rejectPayload)
		return nil
	}

	if existingCallID, targetBusy := userCalls[offer.ToUserID]; targetBusy {
		callsMu.Unlock()
		// Target is busy
		rejectPayload, _ := msgpack.Marshal(CallReject{
			CallID:   existingCallID,
			ToUserID: offer.ToUserID,
			Reason:   "busy",
		})
		c.hub.SendToDeviceFrame(c.deviceID, OpCallReject, rejectPayload)
		return nil
	}

	callID := fmt.Sprintf("call_%d_%d_%d", c.userID, offer.ToUserID, time.Now().UnixNano())
	roomName := callID

	ac := &activeCall{
		CallID:         callID,
		CallerDeviceID: c.deviceID,
		CallerID:       c.userID,
		CalleeID:  offer.ToUserID,
		RoomName:  roomName,
		IsVideo:   offer.IsVideo,
		StartedAt: time.Now(),
	}

	activeCalls[callID] = ac
	userCalls[c.userID] = callID
	userCalls[offer.ToUserID] = callID
	callsMu.Unlock()

	// Generate token for Callee (Recipient)
	calleeIdentity := fmt.Sprintf("user_%d", offer.ToUserID)
	token, err := generateLiveKitToken(c.cfg.LiveKitAPIKey, c.cfg.LiveKitAPISecret, roomName, calleeIdentity, c.cfg.LiveKitTokenTTL)
	if err != nil {
		// Without a token the callee cannot join, so ringing them would only
		// produce a call that can never connect. Release the reservation and
		// tell the caller the attempt failed.
		log.Printf("Failed to generate LiveKit token: %v", err)
		callsMu.Lock()
		if current, ok := activeCalls[callID]; ok {
			dropCall(current)
		}
		callsMu.Unlock()
		rejectPayload, _ := msgpack.Marshal(CallReject{
			CallID:   callID,
			ToUserID: offer.ToUserID,
			Reason:   "failed",
		})
		c.hub.SendToDeviceFrame(c.deviceID, OpCallReject, rejectPayload)
		return nil
	}

	expireUnansweredCall(c.hub, callID)

	incomingPayload, _ := msgpack.Marshal(CallIncoming{
		CallID:             callID,
		FromUserID:         c.userID,
		IsVideo:            offer.IsVideo,
		RoomName:           roomName,
		LiveKitURL:         c.cfg.LiveKitURL,
		LiveKitFallbackURL: c.cfg.LiveKitFallbackURL,
		Token:              token,
	})

	c.hub.SendToUser(offer.ToUserID, append([]byte{byte(OpCallIncoming)}, incomingPayload...))
	return nil
}

func (c *Client) handleCallAccept(payload []byte) error {
	var accept CallAccept
	if err := msgpack.Unmarshal(payload, &accept); err != nil {
		return fmt.Errorf("unmarshal CallAccept: %w", err)
	}

	callsMu.RLock()
	ac, ok := activeCalls[accept.CallID]
	callsMu.RUnlock()

	if !ok || ac.CalleeID != c.userID {
		return fmt.Errorf("invalid call or recipient")
	}

	callsMu.Lock()
	ac.Accepted = true
	ac.CalleeDeviceID = c.deviceID
	callsMu.Unlock()

	// Generate token for Caller (Initiator)
	callerIdentity := fmt.Sprintf("user_%d", ac.CallerID)
	token, err := generateLiveKitToken(c.cfg.LiveKitAPIKey, c.cfg.LiveKitAPISecret, ac.RoomName, callerIdentity, c.cfg.LiveKitTokenTTL)
	if err != nil {
		log.Printf("Failed to generate LiveKit token for caller: %v", err)
	}

	acceptedPayload, _ := msgpack.Marshal(CallAccepted{
		CallID:             ac.CallID,
		ToUserID:           c.userID,
		RoomName:           ac.RoomName,
		LiveKitURL:         c.cfg.LiveKitURL,
		LiveKitFallbackURL: c.cfg.LiveKitFallbackURL,
		Token:              token,
	})

	c.hub.SendToUser(ac.CallerID, append([]byte{byte(OpCallAccepted)}, acceptedPayload...))
	return nil
}

func (c *Client) handleCallReject(payload []byte) error {
	var reject CallReject
	if err := msgpack.Unmarshal(payload, &reject); err != nil {
		return fmt.Errorf("unmarshal CallReject: %w", err)
	}

	callsMu.Lock()
	ac, ok := activeCalls[reject.CallID]
	// Only a participant may tear the call down. Call IDs are predictable, so
	// without this check any account could hang up on any other conversation.
	if ok && !ac.involves(c.userID) {
		ac, ok = nil, false
	}
	if !ok {
		// Callers ringing out don't know the call_id yet; fall back to the
		// sender's registered call so cancellation still releases both users.
		if callID, inCall := userCalls[c.userID]; inCall {
			ac, ok = activeCalls[callID]
		}
	}
	if ok {
		dropCall(ac)
	}
	callsMu.Unlock()

	if ok {
		otherUserID := ac.peerOf(c.userID)
		rejectPayload, _ := msgpack.Marshal(CallReject{
			CallID:   ac.CallID,
			ToUserID: c.userID,
			Reason:   reject.Reason,
		})
		c.hub.SendToUser(otherUserID, append([]byte{byte(OpCallReject)}, rejectPayload...))
	}
	return nil
}

func (c *Client) handleCallEnd(payload []byte) error {
	var end CallEnd
	if err := msgpack.Unmarshal(payload, &end); err != nil {
		return fmt.Errorf("unmarshal CallEnd: %w", err)
	}

	callsMu.Lock()
	ac, ok := activeCalls[end.CallID]
	if ok && !ac.involves(c.userID) {
		ac, ok = nil, false
	}
	if !ok {
		if callID, inCall := userCalls[c.userID]; inCall {
			ac, ok = activeCalls[callID]
		}
	}
	if ok {
		dropCall(ac)
	}
	callsMu.Unlock()

	if ok {
		otherUserID := ac.peerOf(c.userID)
		endPayload, _ := msgpack.Marshal(CallEnd{
			CallID:   ac.CallID,
			ToUserID: c.userID,
		})
		c.hub.SendToUser(otherUserID, append([]byte{byte(OpCallEnd)}, endPayload...))
	}
	return nil
}

// CleanupDeviceCalls releases the call a dropping connection was carrying. It is
// keyed on the device, not the account: tearing down by user id alone lets any
// other device of the same user kill a live call just by disconnecting. A callee
// that has not answered yet is only released once no device of theirs is left to
// ring.
func CleanupDeviceCalls(hub *Hub, userID, deviceID int64) {
	callsMu.Lock()
	defer callsMu.Unlock()

	callID, inCall := userCalls[userID]
	if !inCall {
		return
	}
	ac, ok := activeCalls[callID]
	if !ok {
		delete(userCalls, userID)
		return
	}

	switch {
	case ac.CallerID == userID:
		// Legacy state without a bound device falls through to a drop.
		if ac.CallerDeviceID != 0 && ac.CallerDeviceID != deviceID {
			return
		}
	case ac.CalleeID == userID:
		if ac.CalleeDeviceID != 0 {
			if ac.CalleeDeviceID != deviceID {
				return
			}
		} else if hub != nil && hub.IsUserOnlineExcept(userID, deviceID) {
			// Still ringing on another device of the callee.
			return
		}
	default:
		return
	}

	dropCall(ac)
}

// expireUnansweredCall releases a call that is still ringing after ringTimeout
// and notifies both sides, so neither participant is left marked busy by a
// callee that never picked up.
func expireUnansweredCall(hub *Hub, callID string) {
	time.AfterFunc(ringTimeout, func() {
		callsMu.Lock()
		ac, ok := activeCalls[callID]
		if !ok || ac.Accepted {
			callsMu.Unlock()
			return
		}
		dropCall(ac)
		callsMu.Unlock()

		for _, userID := range []int64{ac.CallerID, ac.CalleeID} {
			payload, _ := msgpack.Marshal(CallEnd{
				CallID:   ac.CallID,
				ToUserID: userID,
			})
			hub.SendToUser(userID, append([]byte{byte(OpCallEnd)}, payload...))
		}
	})
}
