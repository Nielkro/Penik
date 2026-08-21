package ws

import (
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/livekit/protocol/auth"
	"github.com/shamaton/msgpack/v2"
)

type activeCall struct {
	CallID    string
	CallerID  int64
	CalleeID  int64
	RoomName  string
	IsVideo   bool
	StartedAt time.Time
}

var (
	callsMu     sync.RWMutex
	activeCalls = make(map[string]*activeCall) // callID -> activeCall
	userCalls   = make(map[int64]string)       // userID -> callID
)

func generateLiveKitToken(apiKey, apiSecret, roomName, participantIdentity string) (string, error) {
	if apiKey == "" || apiSecret == "" {
		return "", fmt.Errorf("livekit API key or secret not configured")
	}

	at := auth.NewAccessToken(apiKey, apiSecret)
	grant := &auth.VideoGrant{
		RoomJoin: true,
		Room:     roomName,
	}
	grant.SetCanPublish(true)
	grant.SetCanSubscribe(true)
	at.AddGrant(grant).SetIdentity(participantIdentity).SetValidFor(24 * time.Hour)

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
		CallID:    callID,
		CallerID:  c.userID,
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
	token, err := generateLiveKitToken(c.cfg.LiveKitAPIKey, c.cfg.LiveKitAPISecret, roomName, calleeIdentity)
	if err != nil {
		log.Printf("Failed to generate LiveKit token: %v", err)
	}

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

	// Generate token for Caller (Initiator)
	callerIdentity := fmt.Sprintf("user_%d", ac.CallerID)
	token, err := generateLiveKitToken(c.cfg.LiveKitAPIKey, c.cfg.LiveKitAPISecret, ac.RoomName, callerIdentity)
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
	if ok {
		delete(activeCalls, reject.CallID)
		delete(userCalls, ac.CallerID)
		delete(userCalls, ac.CalleeID)
	}
	callsMu.Unlock()

	if ok {
		otherUserID := ac.CallerID
		if c.userID == ac.CallerID {
			otherUserID = ac.CalleeID
		}
		rejectPayload, _ := msgpack.Marshal(CallReject{
			CallID:   reject.CallID,
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
	if ok {
		delete(activeCalls, end.CallID)
		delete(userCalls, ac.CallerID)
		delete(userCalls, ac.CalleeID)
	}
	callsMu.Unlock()

	if ok {
		otherUserID := ac.CallerID
		if c.userID == ac.CallerID {
			otherUserID = ac.CalleeID
		}
		endPayload, _ := msgpack.Marshal(CallEnd{
			CallID:   end.CallID,
			ToUserID: c.userID,
		})
		c.hub.SendToUser(otherUserID, append([]byte{byte(OpCallEnd)}, endPayload...))
	}
	return nil
}

// CleanupUserCalls is called when a WebSocket connection drops to clean up any active calls.
func CleanupUserCalls(userID int64) {
	callsMu.Lock()
	defer callsMu.Unlock()

	callID, inCall := userCalls[userID]
	if !inCall {
		return
	}

	ac, ok := activeCalls[callID]
	if ok {
		delete(activeCalls, callID)
		delete(userCalls, ac.CallerID)
		delete(userCalls, ac.CalleeID)
	}
}
