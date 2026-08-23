package handlers

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"time"

	"messenger/server/internal/config"
	"messenger/server/internal/middleware"
)

// vkTicketLifetime bounds how long a web client may hold an upload ticket
// before presenting it to the VK relay. It only has to cover the stretch from
// "ask this server for a ticket" to "start streaming the file", so minutes are
// plenty and a stolen ticket dies fast.
const vkTicketLifetime = 5 * time.Minute

type vkTicketResponse struct {
	Ticket    string `json:"ticket"`
	ExpiresAt int64  `json:"expires_at"`
}

// IssueVKUploadTicket hands an authenticated client a stateless bearer value it
// can present to the VK relay instead of the shared relay master token.
//
// Format: hex(uid_be(8) || exp_unix_be(8) || HMAC_SHA256(secret, uid||exp)).
// The relay recomputes the MAC over the first 16 bytes with the same shared
// secret (RELAY_TICKET_SECRET / SHK_TICKET_SECRET) and checks the expiry, so
// verification is local: no introspection round trip, no database row. The
// trade-off of the stateless form is that a leaked ticket replays until exp.
func IssueVKUploadTicket(cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if cfg.RelayTicketSecret == "" {
			http.Error(w, `{"error":"upload ticket service not configured"}`, http.StatusServiceUnavailable)
			return
		}

		uid := middleware.UserIDFromCtx(r.Context())
		exp := time.Now().Add(vkTicketLifetime)

		payload := make([]byte, 16)
		binary.BigEndian.PutUint64(payload[:8], uint64(uid))
		binary.BigEndian.PutUint64(payload[8:], uint64(exp.Unix()))

		mac := hmac.New(sha256.New, []byte(cfg.RelayTicketSecret))
		mac.Write(payload)

		ticket := hex.EncodeToString(payload) + hex.EncodeToString(mac.Sum(nil))

		w.Header().Set("Cache-Control", "no-store")
		json.NewEncoder(w).Encode(vkTicketResponse{Ticket: ticket, ExpiresAt: exp.Unix()})
	}
}
