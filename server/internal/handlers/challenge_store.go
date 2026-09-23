package handlers

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
	"time"

	"golang.org/x/crypto/curve25519"
)

type RebindChallenge struct {
	Nonce          [32]byte
	EphPriv        [32]byte
	EphPub         [32]byte
	UserID         int64
	TargetDeviceID int64
	ExpiresAt      time.Time
}

type DeviceChallengeStore struct {
	mu         sync.Mutex
	challenges map[string]*RebindChallenge // keyed by hex(Nonce[:])
}

var defaultChallengeStore = NewDeviceChallengeStore()

func NewDeviceChallengeStore() *DeviceChallengeStore {
	store := &DeviceChallengeStore{
		challenges: make(map[string]*RebindChallenge),
	}
	go store.cleanupLoop()
	return store
}

func (s *DeviceChallengeStore) cleanupLoop() {
	ticker := time.NewTicker(30 * time.Second)
	for range ticker.C {
		s.cleanupExpired()
	}
}

func (s *DeviceChallengeStore) cleanupExpired() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	for k, c := range s.challenges {
		if now.After(c.ExpiresAt) {
			delete(s.challenges, k)
		}
	}
}

func (s *DeviceChallengeStore) CreateChallenge(userID, targetDeviceID int64) (nonce []byte, ephPub []byte, expiresAt time.Time, err error) {
	var ephPriv [32]byte
	if _, err := rand.Read(ephPriv[:]); err != nil {
		return nil, nil, time.Time{}, fmt.Errorf("generate eph priv: %w", err)
	}
	var pub [32]byte
	curve25519.ScalarBaseMult(&pub, &ephPriv)

	var nonceArr [32]byte
	if _, err := rand.Read(nonceArr[:]); err != nil {
		return nil, nil, time.Time{}, fmt.Errorf("generate nonce: %w", err)
	}

	exp := time.Now().Add(60 * time.Second)
	challenge := &RebindChallenge{
		Nonce:          nonceArr,
		EphPriv:        ephPriv,
		EphPub:         pub,
		UserID:         userID,
		TargetDeviceID: targetDeviceID,
		ExpiresAt:      exp,
	}

	hexKey := hex.EncodeToString(nonceArr[:])

	s.mu.Lock()
	s.challenges[hexKey] = challenge
	s.mu.Unlock()

	return nonceArr[:], pub[:], exp, nil
}

// ConsumeChallenge atomically retrieves and removes the challenge by nonce bytes.
// Single-use: once consumed (or attempted), it is deleted immediately.
func (s *DeviceChallengeStore) ConsumeChallenge(nonce []byte) (*RebindChallenge, bool) {
	if len(nonce) != 32 {
		return nil, false
	}
	hexKey := hex.EncodeToString(nonce)

	s.mu.Lock()
	defer s.mu.Unlock()

	c, ok := s.challenges[hexKey]
	if !ok {
		return nil, false
	}
	delete(s.challenges, hexKey)

	if time.Now().After(c.ExpiresAt) {
		return nil, false
	}
	return c, true
}
