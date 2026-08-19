package handlers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/livekit/protocol/auth"
	"github.com/shamaton/msgpack/v2"

	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

type InitiateCallRequest struct {
	CalleeUserID int64  `json:"callee_user_id"`
	CallType     string `json:"call_type"` // "audio" or "video"
}

type InitiateCallResponse struct {
	CallID     int64  `json:"call_id"`
	RoomName   string `json:"room_name"`
	Token      string `json:"token"`
	LiveKitURL string `json:"livekit_url"`
}

type RespondCallRequest struct {
	Action string `json:"action"` // "accept", "reject", "busy", "end"
}

type CallLog struct {
	ID           int64  `json:"id"`
	RoomName     string `json:"room_name"`
	CallerUserID int64  `json:"caller_user_id"`
	CallerName   string `json:"caller_name"`
	CalleeUserID int64  `json:"callee_user_id"`
	CalleeName   string `json:"callee_name"`
	CallType     string `json:"call_type"`
	Status       string `json:"status"`
	StartedAt    int64  `json:"started_at"`
	AnsweredAt   *int64 `json:"answered_at"`
	EndedAt      *int64 `json:"ended_at"`
	Duration     int64  `json:"duration"` // in seconds
}

// GenerateLiveKitToken generates a join token for a given user and room.
func GenerateLiveKitToken(apiKey, apiSecret, roomName, identity string) (string, error) {
	at := auth.NewAccessToken(apiKey, apiSecret)
	grant := &auth.VideoGrant{
		RoomJoin: true,
		Room:     roomName,
	}
	at.AddGrant(grant).SetIdentity(identity).SetValidFor(24 * time.Hour)
	return at.ToJWT()
}

// InitiateCall creates a new call record in DB, generates a LiveKit token, and notifies the callee via WebSocket.
func InitiateCall(database *db.DB, cfg *config.Config, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		callerID := middleware.UserIDFromCtx(r.Context())
		if callerID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		var req struct {
			CalleeUserID interface{} `json:"callee_user_id"`
			CallType     string      `json:"call_type"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}

		var calleeID int64
		switch v := req.CalleeUserID.(type) {
		case float64:
			calleeID = int64(v)
		case string:
			calleeID, _ = strconv.ParseInt(v, 10, 64)
		}

		if calleeID <= 0 || calleeID == callerID {
			http.Error(w, "invalid callee user id", http.StatusBadRequest)
			return
		}

		if req.CallType != "video" {
			req.CallType = "audio"
		}

		// Check if callee exists
		var calleeName string
		err := database.QueryRow("SELECT name FROM users WHERE id = ?", calleeID).Scan(&calleeName)
		if err == sql.ErrNoRows {
			http.Error(w, "callee not found", http.StatusNotFound)
			return
		} else if err != nil {
			http.Error(w, "database error", http.StatusInternalServerError)
			return
		}

		var callerName string
		_ = database.QueryRow("SELECT name FROM users WHERE id = ?", callerID).Scan(&callerName)

		now := time.Now().Unix()
		roomName := fmt.Sprintf("call_%d_%d_%d", callerID, calleeID, now)

		res, err := database.Exec(
			`INSERT INTO calls (room_name, caller_user_id, callee_user_id, call_type, status, started_at) VALUES (?, ?, ?, ?, 'missed', ?)`,
			roomName, callerID, calleeID, req.CallType, now,
		)
		if err != nil {
			http.Error(w, "failed to record call", http.StatusInternalServerError)
			return
		}

		callID, _ := res.LastInsertId()

		token, err := GenerateLiveKitToken(cfg.LiveKitAPIKey, cfg.LiveKitSecret, roomName, fmt.Sprintf("%d", callerID))
		if err != nil {
			http.Error(w, "failed to generate call token", http.StatusInternalServerError)
			return
		}

		// Send real-time call invitation via WebSocket to callee
		signalPayload, _ := msgpack.Marshal(map[string]interface{}{
			"type":           "incoming_call",
			"call_id":        callID,
			"room_name":      roomName,
			"caller_user_id": callerID,
			"caller_name":    callerName,
			"call_type":      req.CallType,
			"started_at":     now,
		})
		hub.SendBinaryToUser(calleeID, ws.OpCallSignalResp, signalPayload)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(InitiateCallResponse{
			CallID:     callID,
			RoomName:   roomName,
			Token:      token,
			LiveKitURL: cfg.LiveKitURL,
		})
	}
}

// RespondCall allows the callee to accept/reject or either party to end/mark busy a call.
func RespondCall(database *db.DB, cfg *config.Config, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		callIDStr := r.PathValue("id")
		callID, err := strconv.ParseInt(callIDStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid call id", http.StatusBadRequest)
			return
		}

		var req RespondCallRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}

		var callerID, calleeID int64
		var roomName, status string
		err = database.QueryRow("SELECT room_name, caller_user_id, callee_user_id, status FROM calls WHERE id = ?", callID).
			Scan(&roomName, &callerID, &calleeID, &status)
		if err == sql.ErrNoRows {
			http.Error(w, "call not found", http.StatusNotFound)
			return
		} else if err != nil {
			http.Error(w, "database error", http.StatusInternalServerError)
			return
		}

		if userID != callerID && userID != calleeID {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}

		now := time.Now().Unix()
		var token string

		switch req.Action {
		case "accept":
			if userID != calleeID {
				http.Error(w, "only callee can accept call", http.StatusBadRequest)
				return
			}
			_, _ = database.Exec("UPDATE calls SET status = 'accepted', answered_at = ? WHERE id = ?", now, callID)
			token, _ = GenerateLiveKitToken(cfg.LiveKitAPIKey, cfg.LiveKitSecret, roomName, fmt.Sprintf("%d", calleeID))

			// Notify caller that call was accepted
			signalPayload, _ := msgpack.Marshal(map[string]interface{}{
				"type":    "call_accepted",
				"call_id": callID,
			})
			hub.SendBinaryToUser(callerID, ws.OpCallSignalResp, signalPayload)

		case "reject":
			_, _ = database.Exec("UPDATE calls SET status = 'rejected', ended_at = ? WHERE id = ?", now, callID)
			otherID := callerID
			if userID == callerID {
				otherID = calleeID
			}
			signalPayload, _ := msgpack.Marshal(map[string]interface{}{
				"type":    "call_rejected",
				"call_id": callID,
			})
			hub.SendBinaryToUser(otherID, ws.OpCallSignalResp, signalPayload)

		case "busy":
			_, _ = database.Exec("UPDATE calls SET status = 'busy', ended_at = ? WHERE id = ?", now, callID)
			signalPayload, _ := msgpack.Marshal(map[string]interface{}{
				"type":    "call_busy",
				"call_id": callID,
			})
			hub.SendBinaryToUser(callerID, ws.OpCallSignalResp, signalPayload)

		case "end":
			_, _ = database.Exec("UPDATE calls SET ended_at = ? WHERE id = ? AND ended_at IS NULL", now, callID)
			if status != "accepted" && status != "rejected" && status != "busy" {
				_, _ = database.Exec("UPDATE calls SET status = 'ended' WHERE id = ?", callID)
			}
			otherID := callerID
			if userID == callerID {
				otherID = calleeID
			}
			signalPayload, _ := msgpack.Marshal(map[string]interface{}{
				"type":    "call_ended",
				"call_id": callID,
			})
			hub.SendBinaryToUser(otherID, ws.OpCallSignalResp, signalPayload)

		default:
			http.Error(w, "unsupported action", http.StatusBadRequest)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":      "ok",
			"token":       token,
			"livekit_url": cfg.LiveKitURL,
		})
	}
}

// GetCallLogs returns all calls where the user was caller or callee.
func GetCallLogs(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		rows, err := database.Query(`
			SELECT 
				c.id, c.room_name, c.caller_user_id, u1.name, c.callee_user_id, u2.name,
				c.call_type, c.status, c.started_at, c.answered_at, c.ended_at
			FROM calls c
			JOIN users u1 ON u1.id = c.caller_user_id
			JOIN users u2 ON u2.id = c.callee_user_id
			WHERE c.caller_user_id = ? OR c.callee_user_id = ?
			ORDER BY c.started_at DESC
			LIMIT 100
		`, userID, userID)
		if err != nil {
			http.Error(w, "failed to query call logs", http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		logs := make([]CallLog, 0)
		for rows.Next() {
			var l CallLog
			var answeredAt, endedAt sql.NullInt64
			if err := rows.Scan(
				&l.ID, &l.RoomName, &l.CallerUserID, &l.CallerName, &l.CalleeUserID, &l.CalleeName,
				&l.CallType, &l.Status, &l.StartedAt, &answeredAt, &endedAt,
			); err != nil {
				continue
			}

			if answeredAt.Valid {
				l.AnsweredAt = &answeredAt.Int64
			}
			if endedAt.Valid {
				l.EndedAt = &endedAt.Int64
			}

			if l.AnsweredAt != nil && l.EndedAt != nil {
				l.Duration = *l.EndedAt - *l.AnsweredAt
				if l.Duration < 0 {
					l.Duration = 0
				}
			}

			logs = append(logs, l)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(logs)
	}
}
