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
	"path/filepath"
	"strings"
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

	if err := migrateAvatarsToDisk(database, cfg.UploadDir); err != nil {
		log.Printf("avatar migration error: %v", err)
	}

	go func() {
		ticker := time.NewTicker(1 * time.Minute)
		defer ticker.Stop()
		for range ticker.C {
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

	// Key-bundle fetches happen per new conversation/device, so a moderate
	// per-user cap stops mass harvesting of public keys and user existence
	// without impeding normal session setup.
	keyBundleLimiter := middleware.NewUserRateLimiter(60, time.Minute)

	// Public routes (no auth, but rate limited).
	mux.Handle("POST /api/v1/register", authRateLimiter.Limit(http.HandlerFunc(handlers.Register(database, cfg))))
	mux.Handle("POST /api/v1/login", authRateLimiter.Limit(http.HandlerFunc(handlers.Login(database, cfg))))
	mux.Handle("GET /api/v1/users/check", authRateLimiter.Limit(http.HandlerFunc(handlers.CheckNickname(database))))
	mux.Handle("GET /api/v1/users/{nickname}/profile", authRateLimiter.Limit(http.HandlerFunc(handlers.GetUserByNicknameProfile(database))))

	// Avatar (GET is public, PUT requires auth).
	mux.HandleFunc("GET /api/v1/avatar/{user_id}", handlers.GetAvatar(database, cfg))

	// Authenticated routes — wrap each with the auth middleware.
	authMW := middleware.Auth(database)

	mux.Handle("GET /api/v1/users/search",
		authMW(http.HandlerFunc(handlers.SearchUsers(database))))
	mux.Handle("GET /api/v1/users/{id}",
		authMW(http.HandlerFunc(handlers.GetUser(database, hub))))
	mux.Handle("PUT /api/v1/users/me/name",
		authMW(http.HandlerFunc(handlers.UpdateName(database))))
	mux.Handle("PUT /api/v1/users/me/nickname",
		authMW(http.HandlerFunc(handlers.UpdateNickname(database))))
	mux.Handle("PATCH /api/v1/users/me/password",
		authMW(http.HandlerFunc(handlers.UpdatePassword(database))))
	mux.Handle("PUT /api/v1/avatar",
		authMW(http.HandlerFunc(handlers.UploadAvatar(database, cfg, hub))))
	mux.Handle("POST /api/v1/attachments/vk-upload",
		authMW(http.HandlerFunc(handlers.UploadVKAttachment(cfg))))
	mux.Handle("GET /api/v1/attachments/proxy",
		authMW(http.HandlerFunc(handlers.ProxyVKAttachment())))

	mux.Handle("POST /api/v1/keys/init",
		authMW(http.HandlerFunc(handlers.UploadIdentityKeys(database))))
	mux.Handle("GET /api/v1/keys/bundle/{user_id}",
		authMW(keyBundleLimiter.Limit(http.HandlerFunc(handlers.GetKeyBundle(database)))))
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
	mux.HandleFunc("GET /api/v1/groups/{group_id}/avatar", handlers.GetGroupAvatar(database, cfg))
	mux.Handle("PUT /api/v1/groups/{group_id}/avatar",
		authMW(http.HandlerFunc(handlers.UploadGroupAvatar(database, cfg, hub))))
	mux.Handle("GET /api/v1/groups/{group_id}/members",
		authMW(http.HandlerFunc(handlers.ListMembers(database, hub))))
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
	mux.Handle("GET /api/v1/groups/{group_id}/keys/{version}/devices",
		authMW(http.HandlerFunc(handlers.ListEnvelopeDevices(database))))
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

	// Serve static files from embedded FS with Cache-Control for assets.
	distFS, err := fs.Sub(frontendFS, "dist")
	if err != nil {
		log.Fatalf("sub fs: %v", err)
	}
	fileServer := http.FileServer(http.FS(distFS))
	mux.Handle("GET /", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Vite assets are hashed (e.g. /assets/libsodium-wrappers-XXX.js), cache forever (1 year)
		if strings.HasPrefix(r.URL.Path, "/assets/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else if r.URL.Path == "/" || strings.HasSuffix(r.URL.Path, ".html") || r.URL.Path == "/sw.js" {
			if r.URL.Path == "/sw.js" {
				w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
				w.Header().Set("Service-Worker-Allowed", "/")
			}
			// Always validate index.html and sw.js so updates load instantly
			w.Header().Set("Cache-Control", "no-cache")
		}
		fileServer.ServeHTTP(w, r)
	}))

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

	log.Println("notifying clients of shutdown…")
	hub.BroadcastServerShutdown()
	time.Sleep(100 * time.Millisecond) // Give clients a moment to receive the notification

	log.Println("shutting down…")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("server shutdown: %v", err)
	}
}

func migrateAvatarsToDisk(database *db.DB, uploadDir string) error {
	rows, err := database.Query("SELECT id, avatar FROM users WHERE avatar IS NOT NULL AND length(avatar) > 0")
	if err != nil {
		// Table doesn't exist, schema is not loaded, or avatar column doesn't exist (should not happen after db.Open)
		return nil
	}
	defer rows.Close()

	if err := os.MkdirAll(uploadDir, 0755); err != nil {
		return err
	}

	type avatarRecord struct {
		id   int64
		data []byte
	}
	var records []avatarRecord

	for rows.Next() {
		var id int64
		var data []byte
		if err := rows.Scan(&id, &data); err != nil {
			return err
		}
		records = append(records, avatarRecord{id: id, data: data})
	}

	if len(records) == 0 {
		return nil
	}

	log.Printf("Migrating %d avatars from database to %s...", len(records), uploadDir)

	for _, rec := range records {
		filePath := filepath.Join(uploadDir, fmt.Sprintf("%d.webp", rec.id))
		if err := os.WriteFile(filePath, rec.data, 0644); err != nil {
			return fmt.Errorf("write avatar for user %d: %w", rec.id, err)
		}
	}

	// Clear the avatar column in db to reclaim space
	_, err = database.Exec("UPDATE users SET avatar = NULL")
	if err != nil {
		return fmt.Errorf("clear users avatar column: %w", err)
	}

	// Run vacuum to shrink database file size
	_, _ = database.Exec("VACUUM")

	log.Printf("Successfully migrated %d avatars to disk and cleared database columns.", len(records))
	return nil
}
