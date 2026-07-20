package main

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/handlers"
	"messenger/server/internal/middleware"
	"messenger/server/internal/ws"
)

//go:generate sh -c "npm --prefix ../../../client run build && rm -rf dist && cp -r ../../../client/dist ./dist"

//go:embed all:dist
var frontendFS embed.FS

func main() {
	cfg := config.Load()

	database, err := db.Open(cfg.DBPath)
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	defer database.Close()

	// Start background worker to clean up reserved prekeys after 5 minutes (300 seconds)
	go func() {
		ticker := time.NewTicker(1 * time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			_, err := database.Exec(`UPDATE one_time_prekeys
				SET used=0, reserved_at=NULL
				WHERE used=1 AND reserved_at IS NOT NULL
				  AND reserved_at < ?`, time.Now().Unix()-300)
			if err != nil {
				log.Printf("db: error releasing reserved prekeys: %v", err)
			}
			if _, err := database.Exec(
				`DELETE FROM group_history_packets WHERE expires_at < ?`,
				time.Now().Unix()); err != nil {
				log.Printf("db: error sweeping expired history packets: %v", err)
			}
		}
	}()

	hub := ws.NewHub()

	mux := http.NewServeMux()

	authRateLimiter := middleware.NewIPRateLimiter()

	// Per-user throttles for group mutations (plan §10). Rotation triggers a
	// per-device envelope fan-out, so it is bounded more tightly than ordinary
	// group writes. Both run inside authMW so they key on the authenticated user.
	groupWriteLimiter := middleware.NewUserRateLimiter(30, time.Minute)
	groupRotateLimiter := middleware.NewUserRateLimiter(10, time.Minute)

	// Public routes (no auth, but rate limited).
	mux.Handle("POST /api/v1/register", authRateLimiter.Limit(http.HandlerFunc(handlers.Register(database, cfg))))
	mux.Handle("POST /api/v1/login", authRateLimiter.Limit(http.HandlerFunc(handlers.Login(database, cfg))))

	// Avatar (GET is public, PUT requires auth).
	mux.HandleFunc("GET /api/v1/avatar/{user_id}", handlers.GetAvatar(database))

	// Authenticated routes — wrap each with the auth middleware.
	authMW := middleware.Auth(database)

	mux.Handle("GET /api/v1/users/search",
		authMW(http.HandlerFunc(handlers.SearchUsers(database))))
	mux.Handle("GET /api/v1/users/{id}",
		authMW(http.HandlerFunc(handlers.GetUser(database))))
	mux.Handle("PUT /api/v1/users/me/name",
		authMW(http.HandlerFunc(handlers.UpdateName(database))))
	mux.Handle("PUT /api/v1/users/me/nickname",
		authMW(http.HandlerFunc(handlers.UpdateNickname(database))))
	mux.Handle("PATCH /api/v1/users/me/password",
		authMW(http.HandlerFunc(handlers.UpdatePassword(database))))
	mux.Handle("PUT /api/v1/avatar",
		authMW(http.HandlerFunc(handlers.UploadAvatar(database, cfg))))

	mux.Handle("POST /api/v1/keys/init",
		authMW(http.HandlerFunc(handlers.UploadIdentityKeys(database))))
	mux.Handle("POST /api/v1/keys/otk",
		authMW(http.HandlerFunc(handlers.UploadOTK(database))))
	mux.Handle("GET /api/v1/keys/bundle/{user_id}",
		authMW(http.HandlerFunc(handlers.GetKeyBundle(database))))
	mux.Handle("POST /api/v1/keys/prekeys",
		authMW(http.HandlerFunc(handlers.UploadPreKeys(database))))
	mux.Handle("GET /api/v1/keys/prekeys/status",
		authMW(http.HandlerFunc(handlers.GetPreKeysStatus(database))))
	mux.Handle("POST /api/v1/keys/backup",
		authMW(http.HandlerFunc(handlers.UploadKeyBackup(database))))
	mux.Handle("GET /api/v1/keys/backup",
		authMW(http.HandlerFunc(handlers.DownloadKeyBackup(database))))
	mux.Handle("POST /api/v1/pairing/sessions",
		authMW(http.HandlerFunc(handlers.CreatePairingSession(database))))
	mux.Handle("POST /api/v1/pairing/sessions/claim",
		authMW(http.HandlerFunc(handlers.ClaimPairingSession(database, hub))))
	mux.Handle("GET /api/v1/pairing/sessions/{id}", authMW(http.HandlerFunc(handlers.GetPairingClaim(database))))
	mux.Handle("PUT /api/v1/pairing/sessions/{id}/history", authMW(http.HandlerFunc(handlers.UploadPairingHistory(database, hub))))
	mux.Handle("POST /api/v1/groups",
		authMW(groupWriteLimiter.Limit(http.HandlerFunc(handlers.CreateGroup(database)))))
	mux.Handle("GET /api/v1/groups",
		authMW(http.HandlerFunc(handlers.ListGroups(database))))
	mux.Handle("GET /api/v1/groups/{group_id}",
		authMW(http.HandlerFunc(handlers.GetGroup(database))))
	mux.Handle("PATCH /api/v1/groups/{group_id}",
		authMW(groupWriteLimiter.Limit(http.HandlerFunc(handlers.PatchGroup(database)))))
	mux.Handle("DELETE /api/v1/groups/{group_id}",
		authMW(groupWriteLimiter.Limit(http.HandlerFunc(handlers.DeleteGroup(database)))))
	mux.Handle("GET /api/v1/groups/{group_id}/members",
		authMW(http.HandlerFunc(handlers.ListMembers(database))))
	mux.Handle("POST /api/v1/groups/{group_id}/members",
		authMW(groupWriteLimiter.Limit(http.HandlerFunc(handlers.InviteMember(database, hub)))))
	mux.Handle("DELETE /api/v1/groups/{group_id}/members/{user_id}",
		authMW(groupWriteLimiter.Limit(http.HandlerFunc(handlers.RemoveMember(database)))))
	mux.Handle("PATCH /api/v1/groups/{group_id}/members/{user_id}",
		authMW(groupWriteLimiter.Limit(http.HandlerFunc(handlers.ChangeMemberRole(database)))))
	mux.Handle("POST /api/v1/groups/{group_id}/accept",
		authMW(groupWriteLimiter.Limit(http.HandlerFunc(handlers.AcceptInvitation(database)))))
	mux.Handle("POST /api/v1/groups/{group_id}/decline",
		authMW(groupWriteLimiter.Limit(http.HandlerFunc(handlers.DeclineInvitation(database)))))
	mux.Handle("GET /api/v1/groups/{group_id}/keys",
		authMW(http.HandlerFunc(handlers.ListKeyVersions(database))))
	mux.Handle("GET /api/v1/groups/{group_id}/keys/{version}",
		authMW(http.HandlerFunc(handlers.GetEnvelope(database))))
	mux.Handle("POST /api/v1/groups/{group_id}/keys/{version}/envelopes",
		authMW(groupWriteLimiter.Limit(http.HandlerFunc(handlers.UploadEnvelopes(database, hub)))))
	mux.Handle("POST /api/v1/groups/{group_id}/keys/rotate",
		authMW(groupRotateLimiter.Limit(http.HandlerFunc(handlers.RotateGroupKey(database)))))
	mux.Handle("GET /api/v1/groups/{group_id}/messages/history",
		authMW(http.HandlerFunc(handlers.GetGroupHistory(database))))
	mux.Handle("POST /api/v1/groups/{group_id}/history-packets",
		authMW(groupWriteLimiter.Limit(http.HandlerFunc(handlers.UploadGroupHistoryPackets(database, hub)))))
	mux.Handle("GET /api/v1/groups/{group_id}/history-packets",
		authMW(http.HandlerFunc(handlers.GetGroupHistoryPacket(database))))
	mux.Handle("GET /api/v1/messages/history",
		authMW(http.HandlerFunc(handlers.GetMessageHistory(database))))
	mux.Handle("GET /api/v1/messages/{user_id}/status",
		authMW(http.HandlerFunc(handlers.GetMessageStatuses(database))))
	mux.Handle("DELETE /api/v1/chats/{peer_id}",
		authMW(http.HandlerFunc(handlers.DeleteChat(database, hub))))
	mux.Handle("GET /api/v1/ws",
		authMW(http.HandlerFunc(handlers.WebSocketHandler(hub, database, cfg))))

	// Serve static files from embedded FS.
	distFS, err := fs.Sub(frontendFS, "dist")
	if err != nil {
		log.Fatalf("sub fs: %v", err)
	}
	mux.Handle("GET /", http.FileServer(http.FS(distFS)))

	// Wrap mux with global middleware (max body, CORS).
	var handler http.Handler = mux
	handler = middleware.MaxBodySize(cfg.MaxBodySize)(handler)
	handler = middleware.CORS(cfg)(handler)
	handler = middleware.RequestLogger(handler)

	addr := fmt.Sprintf(":%s", cfg.Port)
	srv := &http.Server{
		Addr:         addr,
		Handler:      handler,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 0, // WebSocket connections must not timeout during upgrade
		IdleTimeout:  120 * time.Second,
	}

	go func() {
		log.Printf("messenger server listening on %s", addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("shutting down…")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("server shutdown: %v", err)
	}
}
