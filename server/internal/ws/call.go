package ws

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/livekit/protocol/auth"
	"github.com/shamaton/msgpack/v2"
	"messenger/server/internal/push"
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
	// RingingDevices holds the callee devices that were rung and have neither
	// answered nor declined yet. A decline from one device only ends the call
	// once this set is empty, so hanging up on the phone cannot cancel a call
	// the user already picked up in the browser.
	RingingDevices map[int64]bool
	RoomName       string
	IsVideo        bool
	Accepted       bool
	StartedAt      time.Time
}

// involves reports whether userID is one of the two participants. Call IDs are
// guessable, so every state transition must confirm the sender is in the call
// rather than trusting the ID alone.
func (a *activeCall) involves(userID int64) bool {
	return a != nil && (a.CallerID == userID || a.CalleeID == userID)
}

// ownedByDevice reports whether the given connection currently speaks for its
// side of the call. A callee device that neither answered nor is still ringing
// (for example one that already declined) must not be able to tear the call
// down for the device that did answer.
func (a *activeCall) ownedByDevice(userID, deviceID int64) bool {
	if a == nil {
		return false
	}
	switch userID {
	case a.CallerID:
		return a.CallerDeviceID == 0 || a.CallerDeviceID == deviceID
	case a.CalleeID:
		if a.CalleeDeviceID != 0 {
			return a.CalleeDeviceID == deviceID
		}
		return a.RingingDevices[deviceID]
	}
	return false
}

// peerOf returns the other participant of the call.
func (a *activeCall) peerOf(userID int64) int64 {
	if a.CallerID == userID {
		return a.CalleeID
	}
	return a.CallerID
}

// otherRingingDevices lists the callee devices that are still ringing, excluding
// the given one.
func (a *activeCall) otherRingingDevices(deviceID int64) []int64 {
	if a == nil || len(a.RingingDevices) == 0 {
		return nil
	}
	devices := make([]int64, 0, len(a.RingingDevices))
	for devID := range a.RingingDevices {
		if devID == deviceID {
			continue
		}
		devices = append(devices, devID)
	}
	return devices
}

// dropCall removes a call and both participants' busy markers. Callers must hold
// callsMu.
func dropCall(ac *activeCall) {
	delete(activeCalls, ac.CallID)
	delete(userCalls, ac.CallerID)
	delete(userCalls, ac.CalleeID)
}

// notifyOtherCalleeDevices tells the callee's remaining devices to stop ringing
// because another of their devices settled the call.
func notifyOtherCalleeDevices(hub *Hub, deviceIDs []int64, callID, reason string) {
	if hub == nil || len(deviceIDs) == 0 {
		return
	}
	payload, _ := msgpack.Marshal(CallTaken{CallID: callID, Reason: reason})
	for _, devID := range deviceIDs {
		hub.SendToDeviceFrame(devID, OpCallTaken, payload)
	}
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

var (
	callLimitMu  sync.Mutex
	callAttempts = make(map[int64][]time.Time)
)

func allowCallAttempt(callerID int64) bool {
	callLimitMu.Lock()
	defer callLimitMu.Unlock()

	now := time.Now()
	cutoff := now.Add(-1 * time.Minute)

	var valid []time.Time
	for _, t := range callAttempts[callerID] {
		if t.After(cutoff) {
			valid = append(valid, t)
		}
	}
	// Max 5 call offers per minute per caller account
	if len(valid) >= 5 {
		callAttempts[callerID] = valid
		return false
	}
	valid = append(valid, now)
	callAttempts[callerID] = valid
	return true
}

func (c *Client) handleCallOffer(payload []byte) error {
	var offer CallOffer
	if err := msgpack.Unmarshal(payload, &offer); err != nil {
		return fmt.Errorf("unmarshal CallOffer: %w", err)
	}

	if offer.ToUserID == c.userID {
		return fmt.Errorf("cannot call yourself")
	}

	if !allowCallAttempt(c.userID) {
		rejectPayload, _ := msgpack.Marshal(CallReject{
			CallID:   "",
			ToUserID: offer.ToUserID,
			Reason:   "busy",
		})
		c.hub.SendToDeviceFrame(c.deviceID, OpCallReject, rejectPayload)
		return nil
	}

	// Confirm the callee is a real account and shares a relationship with caller
	// before reserving call state or waking up devices via FCM pushes.
	related, err := c.db.UsersShareChat(context.Background(), c.userID, offer.ToUserID)
	if err != nil || !related {
		rejectPayload, _ := msgpack.Marshal(CallReject{
			CallID:   "",
			ToUserID: offer.ToUserID,
			Reason:   "declined",
		})
		c.hub.SendToDeviceFrame(c.deviceID, OpCallReject, rejectPayload)
		return nil
	}

	rows, err := c.db.Query("SELECT id, fcm_token FROM devices WHERE user_id=?", offer.ToUserID)
	if err != nil {
		return err
	}
	defer rows.Close()

	var calleeDevices []int64
	var fcmTokens []string
	for rows.Next() {
		var devID int64
		var fcmToken sql.NullString
		if err := rows.Scan(&devID, &fcmToken); err == nil {
			calleeDevices = append(calleeDevices, devID)
			if fcmToken.Valid && fcmToken.String != "" {
				fcmTokens = append(fcmTokens, fcmToken.String)
			}
		} else {
			log.Printf("[ws] Scan callee device error: %v", err)
		}
	}

	isOnline := c.hub.IsUserOnline(offer.ToUserID)
	if len(calleeDevices) == 0 || (!isOnline && len(fcmTokens) == 0) {
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
		CalleeID:       offer.ToUserID,
		RingingDevices: make(map[int64]bool, len(calleeDevices)),
		RoomName:       roomName,
		IsVideo:        offer.IsVideo,
		StartedAt:      time.Now(),
	}
	for _, devID := range calleeDevices {
		ac.RingingDevices[devID] = true
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

	// Ring exactly the devices recorded in RingingDevices, so the tracked ring
	// set cannot drift from what was actually delivered.
	for _, devID := range calleeDevices {
		c.hub.SendToDeviceFrame(devID, OpCallIncoming, incomingPayload)
	}

	var senderName string
	_ = c.db.QueryRow("SELECT name FROM users WHERE id = ?", c.userID).Scan(&senderName)
	if senderName == "" {
		senderName = "Пользователь"
	}

	for _, devID := range calleeDevices {
		var fcmToken sql.NullString
		_ = c.db.QueryRow("SELECT fcm_token FROM devices WHERE id=?", devID).Scan(&fcmToken)
		if fcmToken.Valid && fcmToken.String != "" {
			push.SendDevicePush(fcmToken.String, map[string]string{
				"type":                 "call",
				"call_id":              callID,
				"from_user_id":         fmt.Sprintf("%d", c.userID),
				"sender_name":          senderName,
				"is_video":             fmt.Sprintf("%v", offer.IsVideo),
				"room_name":            roomName,
				"livekit_url":          c.cfg.LiveKitURL,
				"livekit_fallback_url": c.cfg.LiveKitFallbackURL,
				"token":     token,
				"timestamp": fmt.Sprintf("%d", time.Now().Unix()*1000),
			})
		}
	}

	return nil
}

func (c *Client) handleCallAccept(payload []byte) error {
	var accept CallAccept
	if err := msgpack.Unmarshal(payload, &accept); err != nil {
		return fmt.Errorf("unmarshal CallAccept: %w", err)
	}

	callsMu.Lock()
	ac, ok := activeCalls[accept.CallID]
	if !ok || ac.CalleeID != c.userID {
		callsMu.Unlock()
		return fmt.Errorf("invalid call or recipient")
	}
	// Another device of the same user already answered: tell this one to stop
	// ringing instead of hijacking the established call.
	if ac.CalleeDeviceID != 0 && ac.CalleeDeviceID != c.deviceID {
		callID := ac.CallID
		callsMu.Unlock()
		takenPayload, _ := msgpack.Marshal(CallTaken{CallID: callID, Reason: "accepted"})
		c.hub.SendToDeviceFrame(c.deviceID, OpCallTaken, takenPayload)
		return nil
	}
	ac.Accepted = true
	ac.CalleeDeviceID = c.deviceID
	// Every other device of the callee stops ringing; the answering device now
	// solely owns the callee side of the call.
	otherDevices := ac.otherRingingDevices(c.deviceID)
	ac.RingingDevices = nil
	callerID, roomName, callID := ac.CallerID, ac.RoomName, ac.CallID
	callsMu.Unlock()

	notifyOtherCalleeDevices(c.hub, otherDevices, callID, "accepted")

	// Generate token for Caller (Initiator)
	callerIdentity := fmt.Sprintf("user_%d", callerID)
	token, err := generateLiveKitToken(c.cfg.LiveKitAPIKey, c.cfg.LiveKitAPISecret, roomName, callerIdentity, c.cfg.LiveKitTokenTTL)
	if err != nil {
		log.Printf("Failed to generate LiveKit token for caller: %v", err)
	}

	acceptedPayload, _ := msgpack.Marshal(CallAccepted{
		CallID:             callID,
		ToUserID:           c.userID,
		RoomName:           roomName,
		LiveKitURL:         c.cfg.LiveKitURL,
		LiveKitFallbackURL: c.cfg.LiveKitFallbackURL,
		Token:              token,
	})

	c.hub.SendToUser(callerID, append([]byte{byte(OpCallAccepted)}, acceptedPayload...))
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
	if !ok {
		callsMu.Unlock()
		return nil
	}

	// A callee device that no longer owns its side of the call (another device
	// already answered) must not tear it down; just tell it to stop ringing.
	if ac.CalleeID == c.userID && !ac.ownedByDevice(c.userID, c.deviceID) {
		callID := ac.CallID
		callsMu.Unlock()
		takenPayload, _ := msgpack.Marshal(CallTaken{CallID: callID, Reason: "accepted"})
		c.hub.SendToDeviceFrame(c.deviceID, OpCallTaken, takenPayload)
		return nil
	}
	// Declining on one device while others are still ringing only silences that
	// device: the user may still pick up elsewhere.
	if ac.CalleeID == c.userID && !ac.Accepted && len(ac.RingingDevices) > 1 {
		delete(ac.RingingDevices, c.deviceID)
		callID := ac.CallID
		callsMu.Unlock()
		takenPayload, _ := msgpack.Marshal(CallTaken{CallID: callID, Reason: "declined"})
		c.hub.SendToDeviceFrame(c.deviceID, OpCallTaken, takenPayload)
		return nil
	}

	dropCall(ac)
	callID := ac.CallID
	otherUserID := ac.peerOf(c.userID)
	callsMu.Unlock()

	rejectPayload, _ := msgpack.Marshal(CallReject{
		CallID:   callID,
		ToUserID: c.userID,
		Reason:   reject.Reason,
	})
	// Sent to every device of the peer, which also stops the remaining devices
	// of a callee ringing when it is the caller who cancels.
	c.hub.SendToUser(otherUserID, append([]byte{byte(OpCallReject)}, rejectPayload...))
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
	// A device that neither answered nor is ringing does not own this side of
	// the call and cannot hang it up for the device that did answer.
	if ok && !ac.ownedByDevice(c.userID, c.deviceID) {
		ac, ok = nil, false
	}
	if !ok {
		callsMu.Unlock()
		return nil
	}

	dropCall(ac)
	callID := ac.CallID
	otherUserID := ac.peerOf(c.userID)
	callsMu.Unlock()

	endPayload, _ := msgpack.Marshal(CallEnd{
		CallID:   callID,
		ToUserID: c.userID,
	})
	c.hub.SendToUser(otherUserID, append([]byte{byte(OpCallEnd)}, endPayload...))
	return nil
}

// CleanupDeviceCalls releases the call a dropping connection was carrying. It is
// keyed on the device, not the account: tearing down by user id alone lets any
// other device of the same user kill a live call just by disconnecting. A callee
// that has not answered yet is only released once no device of theirs is left to
// ring.
func CleanupDeviceCalls(hub *Hub, userID, deviceID int64) {
	callsMu.Lock()

	callID, inCall := userCalls[userID]
	if !inCall {
		callsMu.Unlock()
		return
	}
	ac, ok := activeCalls[callID]
	if !ok {
		delete(userCalls, userID)
		callsMu.Unlock()
		return
	}

	switch {
	case ac.CallerID == userID:
		// Legacy state without a bound device falls through to a drop.
		if ac.CallerDeviceID != 0 && ac.CallerDeviceID != deviceID {
			callsMu.Unlock()
			return
		}
	case ac.CalleeID == userID:
		if ac.CalleeDeviceID != 0 {
			if ac.CalleeDeviceID != deviceID {
				callsMu.Unlock()
				return
			}
		} else {
			// Only the dropping device stops ringing; the call survives as long
			// as any other device of the callee can still answer.
			delete(ac.RingingDevices, deviceID)
			if len(ac.RingingDevices) > 0 {
				callsMu.Unlock()
				return
			}
			if hub != nil && hub.IsUserOnlineExcept(userID, deviceID) {
				// Legacy state without a tracked ring set.
				callsMu.Unlock()
				return
			}
		}
	default:
		callsMu.Unlock()
		return
	}

	dropCall(ac)
	peerID, endedCallID := ac.peerOf(userID), ac.CallID
	callsMu.Unlock()

	if hub == nil {
		return
	}
	// Telling the peer the call is over also stops the ringing on every device
	// of a callee whose caller just vanished.
	payload, _ := msgpack.Marshal(CallEnd{CallID: endedCallID, ToUserID: userID})
	hub.SendToUser(peerID, append([]byte{byte(OpCallEnd)}, payload...))
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
