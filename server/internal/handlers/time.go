package handlers

import (
	"encoding/json"
	"net/http"
	"time"
)

// ServerTimeResponse returns current server time in seconds and milliseconds.
type ServerTimeResponse struct {
	ServerTime   int64 `json:"server_time"`
	ServerTimeMs int64 `json:"server_time_ms"`
}

// GetServerTime returns the current server time for client clock synchronization.
func GetServerTime() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		now := time.Now()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(ServerTimeResponse{
			ServerTime:   now.Unix(),
			ServerTimeMs: now.UnixMilli(),
		})
	}
}
