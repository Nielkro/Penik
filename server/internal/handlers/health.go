package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/ws"
)

// ReadinessResponse reports the operational readiness status of core subsystems.
type ReadinessResponse struct {
	Status  string `json:"status"`
	DB      string `json:"db"`
	Storage string `json:"storage"`
	WS      string `json:"ws"`
}

// ReadinessCheck handles GET /api/v1/health.
// Verifies only the critical dependencies required for chat operation:
// 1. SQLite responsiveness via SELECT 1 with 1s timeout.
// 2. Upload storage directory stat and writeability (without du or recursion).
// 3. WebSocket hub non-nil pointer.
// External dependencies (LiveKit, FCM, Telegram, GeoIP) are intentionally excluded
// so third-party downtime does not trigger false negative rollbacks.
func ReadinessCheck(database *db.DB, cfg *config.Config, hub *ws.Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		resp := ReadinessResponse{
			Status:  "ok",
			DB:      "ok",
			Storage: "ok",
			WS:      "ok",
		}

		// 1. Check database with 1s timeout
		ctx, cancel := context.WithTimeout(r.Context(), 1*time.Second)
		defer cancel()

		var one int
		if err := database.QueryRowContext(ctx, "SELECT 1").Scan(&one); err != nil {
			resp.DB = "fail"
			resp.Status = "fail"
		}

		// 2. Check storage directory existence and writeability
		if stat, err := os.Stat(cfg.UploadDir); err != nil || !stat.IsDir() {
			resp.Storage = "fail"
			resp.Status = "fail"
		} else {
			testFile := filepath.Join(cfg.UploadDir, fmt.Sprintf(".health_%d.tmp", time.Now().UnixNano()))
			if f, err := os.OpenFile(testFile, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600); err != nil {
				resp.Storage = "fail"
				resp.Status = "fail"
			} else {
				_ = f.Close()
				_ = os.Remove(testFile)
			}
		}

		// 3. Check WebSocket hub instance
		if hub == nil {
			resp.WS = "fail"
			resp.Status = "fail"
		}

		w.Header().Set("Content-Type", "application/json")
		if resp.Status != "ok" {
			w.WriteHeader(http.StatusServiceUnavailable)
		} else {
			w.WriteHeader(http.StatusOK)
		}
		_ = json.NewEncoder(w).Encode(resp)
	}
}
