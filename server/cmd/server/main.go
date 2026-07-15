package main

import (
	"context"
	"fmt"
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

func main() {
	cfg := config.Load()

	database, err := db.Open(cfg.DBPath)
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	defer database.Close()

	hub := ws.NewHub()

	mux := http.NewServeMux()

	// Public routes (no auth).
	mux.HandleFunc("POST /api/v1/register", handlers.Register(database, cfg))
	mux.HandleFunc("POST /api/v1/login", handlers.Login(database, cfg))

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
	mux.Handle("POST /api/v1/keys/backup",
		authMW(http.HandlerFunc(handlers.UploadKeyBackup(database))))
	mux.Handle("GET /api/v1/keys/backup",
		authMW(http.HandlerFunc(handlers.GetKeyBackup(database))))
	mux.Handle("POST /api/v1/keys/init",
		authMW(http.HandlerFunc(handlers.UploadIdentityKeys(database))))
	mux.Handle("POST /api/v1/keys/otk",
		authMW(http.HandlerFunc(handlers.UploadOTK(database))))
	mux.Handle("GET /api/v1/messages/history",
		authMW(http.HandlerFunc(handlers.GetMessageHistory(database))))
	mux.Handle("GET /api/v1/ws",
		authMW(http.HandlerFunc(handlers.WebSocketHandler(hub, database))))

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
