package handlers

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"messenger/server/internal/config"
)

// verifyVKTicket recomputes the MAC exactly the way the ShokLang relay does:
// split the hex ticket, HMAC the first 16 bytes, compare, then check expiry.
func verifyVKTicket(t *testing.T, ticket, secret string) (uid uint64, ok bool) {
	t.Helper()
	raw, err := hex.DecodeString(ticket)
	if err != nil || len(raw) != 48 {
		return 0, false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(raw[:16])
	if !hmac.Equal(mac.Sum(nil), raw[16:]) {
		return 0, false
	}
	exp := binary.BigEndian.Uint64(raw[8:16])
	if uint64(time.Now().Unix()) >= exp {
		return 0, false
	}
	return binary.BigEndian.Uint64(raw[:8]), true
}

func TestIssueVKUploadTicketVerifiableAndBoundToUser(t *testing.T) {
	cfg := &config.Config{RelayTicketSecret: "test-secret"}
	w := httptest.NewRecorder()
	IssueVKUploadTicket(cfg)(w, vkAuthedRequest(t, http.MethodPost, "/api/v1/attachments/upload-ticket", ""))

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	var resp vkTicketResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}

	uid, ok := verifyVKTicket(t, resp.Ticket, "test-secret")
	if !ok {
		t.Fatalf("relay-side verification failed for ticket %q", resp.Ticket)
	}
	if uid != 7 {
		t.Errorf("uid in ticket = %d, want 7", uid)
	}
	if resp.ExpiresAt <= time.Now().Unix() || resp.ExpiresAt > time.Now().Add(vkTicketLifetime+time.Second).Unix() {
		t.Errorf("expires_at = %d, outside expected window", resp.ExpiresAt)
	}

	// A different secret must reject the ticket.
	if _, ok := verifyVKTicket(t, resp.Ticket, "other-secret"); ok {
		t.Error("ticket verified with wrong secret")
	}
}

func TestIssueVKUploadTicketFailsClosedWithoutSecret(t *testing.T) {
	w := httptest.NewRecorder()
	IssueVKUploadTicket(&config.Config{})(w, vkAuthedRequest(t, http.MethodPost, "/api/v1/attachments/upload-ticket", ""))

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", w.Code)
	}
}
