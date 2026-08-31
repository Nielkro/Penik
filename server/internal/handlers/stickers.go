package handlers

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
	"messenger/server/internal/stickers"
)

// HandleGetMyStickerPacks lists all sticker packs installed by the authenticated user.
func HandleGetMyStickerPacks(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}

		rows, err := database.QueryContext(r.Context(), `
			SELECT p.id, p.title, p.author_id, p.cover_sticker_id, p.is_animated, p.is_video, p.created_at
			FROM sticker_packs p
			JOIN user_sticker_packs u ON p.id = u.pack_id
			WHERE u.user_id = ?
			ORDER BY u.sort_order ASC, u.installed_at ASC
		`, userID)
		if err != nil {
			http.Error(w, `{"error":"failed to query sticker packs"}`, http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		var packs []stickers.StickerPack
		for rows.Next() {
			var p stickers.StickerPack
			var isAnim, isVid int
			if err := rows.Scan(&p.ID, &p.Title, &p.AuthorID, &p.CoverStickerID, &isAnim, &isVid, &p.CreatedAt); err != nil {
				continue
			}
			p.IsAnimated = isAnim == 1
			p.IsVideo = isVid == 1
			packs = append(packs, p)
		}
		if packs == nil {
			packs = []stickers.StickerPack{}
		}

		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(packs)
	}
}

// HandleGetStickerPack fetches the full sticker pack metadata including its stickers.
func HandleGetStickerPack(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		packID := r.PathValue("id")
		if packID == "" {
			http.Error(w, `{"error":"missing pack id"}`, http.StatusBadRequest)
			return
		}

		var p stickers.StickerPack
		var isAnim, isVid int
		err := database.QueryRowContext(r.Context(), `
			SELECT id, title, author_id, cover_sticker_id, is_animated, is_video, created_at
			FROM sticker_packs
			WHERE id = ?
		`, packID).Scan(&p.ID, &p.Title, &p.AuthorID, &p.CoverStickerID, &isAnim, &isVid, &p.CreatedAt)
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, `{"error":"sticker pack not found"}`, http.StatusNotFound)
			return
		} else if err != nil {
			http.Error(w, `{"error":"failed to query pack"}`, http.StatusInternalServerError)
			return
		}
		p.IsAnimated = isAnim == 1
		p.IsVideo = isVid == 1

		rows, err := database.QueryContext(r.Context(), `
			SELECT id, emoji, file_name, width, height, sort_order
			FROM stickers
			WHERE pack_id = ?
			ORDER BY sort_order ASC
		`, packID)
		if err == nil {
			defer rows.Close()
			for rows.Next() {
				var s stickers.Sticker
				s.PackID = packID
				if err := rows.Scan(&s.ID, &s.Emoji, &s.FileName, &s.Width, &s.Height, &s.SortOrder); err == nil {
					s.URL = "/api/v1/stickers/file/" + packID + "/" + s.FileName
					p.Stickers = append(p.Stickers, s)
				}
			}
		}
		if p.Stickers == nil {
			p.Stickers = []stickers.Sticker{}
		}

		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(p)
	}
}

// HandleInstallStickerPack adds a sticker pack to the user's collection.
func HandleInstallStickerPack(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}

		packID := r.PathValue("id")
		if packID == "" {
			http.Error(w, `{"error":"missing pack id"}`, http.StatusBadRequest)
			return
		}

		var exists int
		if err := database.QueryRowContext(r.Context(), "SELECT 1 FROM sticker_packs WHERE id = ?", packID).Scan(&exists); err != nil {
			http.Error(w, `{"error":"sticker pack not found"}`, http.StatusNotFound)
			return
		}

		now := time.Now().Unix()
		_, err := database.ExecContext(r.Context(), `
			INSERT OR IGNORE INTO user_sticker_packs (user_id, pack_id, sort_order, installed_at)
			VALUES (?, ?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM user_sticker_packs WHERE user_id = ?), ?)
		`, userID, packID, userID, now)
		if err != nil {
			http.Error(w, `{"error":"failed to install sticker pack"}`, http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "installed"})
	}
}

// HandleUninstallStickerPack removes a sticker pack from the user's collection.
func HandleUninstallStickerPack(database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}

		packID := r.PathValue("id")
		if packID == "" {
			http.Error(w, `{"error":"missing pack id"}`, http.StatusBadRequest)
			return
		}

		_, err := database.ExecContext(r.Context(), "DELETE FROM user_sticker_packs WHERE user_id = ? AND pack_id = ?", userID, packID)
		if err != nil {
			http.Error(w, `{"error":"failed to uninstall sticker pack"}`, http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "uninstalled"})
	}
}

type importTelegramReq struct {
	URL string `json:"url"`
}

// HandleImportTelegramStickerPack downloads and persists a Telegram sticker set.
func HandleImportTelegramStickerPack(cfg *config.Config, database *db.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}

		if cfg.TelegramBotToken == "" {
			http.Error(w, `{"error":"TELEGRAM_BOT_TOKEN is not configured on the server"}`, http.StatusBadRequest)
			return
		}

		var req importTelegramReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || strings.TrimSpace(req.URL) == "" {
			http.Error(w, `{"error":"invalid request body, missing url"}`, http.StatusBadRequest)
			return
		}

		pack, err := stickers.ImportTelegramPack(cfg.TelegramBotToken, cfg.StickersDir, req.URL, userID, database.DB)
		if err != nil {
			http.Error(w, `{"error":`+strconvQuote(err.Error())+`}`, http.StatusBadRequest)
			return
		}

		for i := range pack.Stickers {
			pack.Stickers[i].URL = "/api/v1/stickers/file/" + pack.ID + "/" + pack.Stickers[i].FileName
		}

		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(pack)
	}
}

// HandleServeStickerFile serves static sticker files with aggressive caching.
func HandleServeStickerFile(cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		packID := filepath.Clean(r.PathValue("pack_id"))
		fileName := filepath.Clean(r.PathValue("file_name"))
		if packID == "." || fileName == "." || strings.Contains(packID, "..") || strings.Contains(fileName, "..") {
			http.Error(w, "invalid path", http.StatusBadRequest)
			return
		}

		filePath := filepath.Join(cfg.StickersDir, packID, fileName)
		info, err := os.Stat(filePath)
		if os.IsNotExist(err) || (err == nil && info.IsDir()) {
			base := strings.TrimSuffix(fileName, filepath.Ext(fileName))
			reqExt := strings.ToLower(filepath.Ext(fileName))

			// If requested webp or mp4 but only webm is on disk, transcode on-demand with ffmpeg
			webmCandidate := filepath.Join(cfg.StickersDir, packID, base+".webm")
			if _, webmErr := os.Stat(webmCandidate); webmErr == nil {
				if reqExt == ".webp" {
					outWebp := filepath.Join(cfg.StickersDir, packID, base+".webp")
					_ = exec.Command("ffmpeg", "-y", "-i", webmCandidate, "-vcodec", "libwebp", "-lossless", "0", "-compression_level", "4", "-q:v", "75", "-loop", "0", "-an", "-vsync", "0", outWebp).Run()
					if webpInfo, webpErr := os.Stat(outWebp); webpErr == nil && !webpInfo.IsDir() && webpInfo.Size() > 0 {
						filePath = outWebp
						info = webpInfo
						fileName = base + ".webp"
						err = nil
					}
				} else if reqExt == ".mp4" {
					outMp4 := filepath.Join(cfg.StickersDir, packID, base+".mp4")
					_ = exec.Command("ffmpeg", "-y", "-i", webmCandidate, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart", outMp4).Run()
					if mp4Info, mp4Err := os.Stat(outMp4); mp4Err == nil && !mp4Info.IsDir() && mp4Info.Size() > 0 {
						filePath = outMp4
						info = mp4Info
						fileName = base + ".mp4"
						err = nil
					}
				}
			}

			// If still not found, try other alternative extensions on disk
			if err != nil || (info != nil && info.IsDir()) {
				found := false
				for _, altExt := range []string{".webp", ".mp4", ".webm", ".png", ".tgs", ".json", ""} {
					altPath := filepath.Join(cfg.StickersDir, packID, base+altExt)
					if altInfo, altErr := os.Stat(altPath); altErr == nil && !altInfo.IsDir() {
						filePath = altPath
						info = altInfo
						fileName = base + altExt
						found = true
						break
					}
				}
				if !found {
					http.Error(w, "sticker not found", http.StatusNotFound)
					return
				}
			}
		} else if err != nil {
			http.Error(w, "sticker not found", http.StatusNotFound)
			return
		}

		contentType := detectStickerContentType(filePath, fileName)
		w.Header().Set("Content-Type", contentType)

		etag := `"` + fileName + `"`
		w.Header().Set("ETag", etag)
		w.Header().Set("Last-Modified", info.ModTime().UTC().Format(http.TimeFormat))
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		w.Header().Set("Access-Control-Allow-Origin", "*")

		if match := r.Header.Get("If-None-Match"); match != "" {
			if strings.Contains(match, etag) || match == "*" {
				w.WriteHeader(http.StatusNotModified)
				return
			}
		}
		if ifModSince := r.Header.Get("If-Modified-Since"); ifModSince != "" {
			if t, err := http.ParseTime(ifModSince); err == nil && !info.ModTime().After(t.Add(1*time.Second)) {
				w.WriteHeader(http.StatusNotModified)
				return
			}
		}

		http.ServeFile(w, r, filePath)
	}
}

func detectStickerContentType(filePath, fileName string) string {
	f, err := os.Open(filePath)
	if err == nil {
		defer f.Close()
		buf := make([]byte, 16)
		n, _ := f.Read(buf)
		if n >= 2 && buf[0] == 0x1f && buf[1] == 0x8b {
			return "application/x-tgsticker"
		}
		if n >= 4 && buf[0] == 0x1a && buf[1] == 0x45 && buf[2] == 0xdf && buf[3] == 0xa3 {
			return "video/webm"
		}
		if n >= 12 && string(buf[0:4]) == "RIFF" && string(buf[8:12]) == "WEBP" {
			return "image/webp"
		}
		if n >= 8 && string(buf[1:4]) == "PNG" {
			return "image/png"
		}
		if n >= 1 && (buf[0] == '{' || buf[0] == '[') {
			return "application/json"
		}
	}

	ext := strings.ToLower(filepath.Ext(fileName))
	switch ext {
	case ".webp":
		return "image/webp"
	case ".png":
		return "image/png"
	case ".webm":
		return "video/webm"
	case ".tgs":
		return "application/x-tgsticker"
	case ".json":
		return "application/json"
	default:
		return "application/octet-stream"
	}
}

func strconvQuote(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}
