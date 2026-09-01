package handlers

import (
	"archive/zip"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
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
					baseID := strings.TrimSuffix(s.ID, filepath.Ext(s.ID))
					if p.IsVideo || p.IsAnimated || strings.HasSuffix(s.FileName, ".webm") || strings.HasSuffix(s.FileName, ".tgs") {
						s.URL = "/api/v1/stickers/file/" + packID + "/" + baseID + ".webp"
					} else {
						s.URL = "/api/v1/stickers/file/" + packID + "/" + s.FileName
					}
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

var (
	packBuildMu      sync.Mutex
	activePackBuilds = make(map[string]bool)
)

func triggerBackgroundPackOptimization(packDir string) {
	packBuildMu.Lock()
	if activePackBuilds[packDir] {
		packBuildMu.Unlock()
		return
	}
	activePackBuilds[packDir] = true
	packBuildMu.Unlock()

	go func() {
		defer func() {
			packBuildMu.Lock()
			delete(activePackBuilds, packDir)
			packBuildMu.Unlock()
		}()

		entries, err := os.ReadDir(packDir)
		if err != nil {
			return
		}

		hasChanges := false
		for _, entry := range entries {
			if entry.IsDir() || strings.HasPrefix(entry.Name(), ".") {
				continue
			}
			ext := strings.ToLower(filepath.Ext(entry.Name()))
			base := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))

			if ext == ".webm" {
				webpPath := filepath.Join(packDir, base+".webp")
				wInfo, wErr := os.Stat(webpPath)
				if wErr != nil || wInfo.Size() == 0 || wInfo.Size() > 150*1024 {
					srcWebm := filepath.Join(packDir, entry.Name())
					if transcodeOnDemand(srcWebm, webpPath, "webp") {
						hasChanges = true
					}
				}
			} else if ext == ".webp" {
				if eInfo, statErr := entry.Info(); statErr == nil && eInfo.Size() > 150*1024 {
					srcWebm := filepath.Join(packDir, base+".webm")
					if _, webmErr := os.Stat(srcWebm); webmErr == nil {
						if transcodeOnDemand(srcWebm, filepath.Join(packDir, entry.Name()), "webp") {
							hasChanges = true
						}
					}
				}
			}
		}

		if hasChanges {
			zipPath := filepath.Join(packDir, "bundle.zip")
			_ = buildStickerPackZip(packDir, zipPath)
		}
	}()
}

// HandleServeStickerPackZip packages and serves all stickers of a pack as a single ZIP bundle.
func HandleServeStickerPackZip(cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		packID := filepath.Clean(r.PathValue("id"))
		if packID == "." || strings.Contains(packID, "..") {
			http.Error(w, "invalid pack id", http.StatusBadRequest)
			return
		}

		packDir := filepath.Join(cfg.StickersDir, packID)
		if packDirInfo, statErr := os.Stat(packDir); statErr != nil || !packDirInfo.IsDir() {
			if entries, dirErr := os.ReadDir(cfg.StickersDir); dirErr == nil {
				for _, entry := range entries {
					if entry.IsDir() && strings.EqualFold(entry.Name(), packID) {
						packID = entry.Name()
						packDir = filepath.Join(cfg.StickersDir, packID)
						break
					}
				}
			}
		}

		if info, err := os.Stat(packDir); err != nil || !info.IsDir() {
			http.Error(w, "sticker pack not found", http.StatusNotFound)
			return
		}

		zipPath := filepath.Join(packDir, "bundle.zip")
		zipInfo, zipErr := os.Stat(zipPath)
		if zipErr != nil || zipInfo.Size() == 0 {
			if zipErr == nil && zipInfo.Size() == 0 {
				_ = os.Remove(zipPath)
			}
			if err := buildStickerPackZip(packDir, zipPath); err != nil {
				http.Error(w, "failed to build sticker bundle", http.StatusInternalServerError)
				return
			}
			zipInfo, _ = os.Stat(zipPath)
		}

		if zipInfo == nil || zipInfo.Size() == 0 {
			http.Error(w, "empty sticker bundle", http.StatusInternalServerError)
			return
		}

		// Trigger non-blocking background optimization for missing/bloated stickers
		triggerBackgroundPackOptimization(packDir)

		w.Header().Set("Content-Type", "application/zip")
		etag := fmt.Sprintf(`"%x-%x"`, zipInfo.Size(), zipInfo.ModTime().UnixNano())
		w.Header().Set("ETag", etag)
		w.Header().Set("Last-Modified", zipInfo.ModTime().UTC().Format(http.TimeFormat))
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.Header().Set("Access-Control-Allow-Origin", "*")

		if match := r.Header.Get("If-None-Match"); match != "" {
			if match == etag || match == "*" {
				w.WriteHeader(http.StatusNotModified)
				return
			}
		}

		http.ServeFile(w, r, zipPath)
	}
}

func buildStickerPackZip(packDir, zipPath string) error {
	tmpZipPath := zipPath + fmt.Sprintf(".tmp_%d.zip", time.Now().UnixNano())
	defer os.Remove(tmpZipPath)

	entries, err := os.ReadDir(packDir)
	if err != nil {
		return err
	}

	zipFile, err := os.Create(tmpZipPath)
	if err != nil {
		return err
	}
	defer zipFile.Close()

	w := zip.NewWriter(zipFile)

	for _, entry := range entries {
		if entry.IsDir() || entry.Name() == "bundle.zip" || strings.HasPrefix(entry.Name(), ".tmp_") || strings.Contains(entry.Name(), ".tmp_") {
			continue
		}
		ext := strings.ToLower(filepath.Ext(entry.Name()))

		// Only include lightweight displayable formats in bundle.zip
		if ext != ".webp" && ext != ".tgs" && ext != ".png" {
			continue
		}

		filePath := filepath.Join(packDir, entry.Name())
		fileBytes, err := os.ReadFile(filePath)
		if err != nil || len(fileBytes) == 0 {
			continue
		}

		fh := &zip.FileHeader{
			Name:   entry.Name(),
			Method: zip.Store, // Stored without recompression for fast packaging & extraction
		}
		if ext == ".tgs" || ext == ".json" {
			fh.Method = zip.Deflate
		}

		f, err := w.CreateHeader(fh)
		if err != nil {
			continue
		}
		_, _ = f.Write(fileBytes)
	}

	if err := w.Close(); err != nil {
		return err
	}

	if err := zipFile.Close(); err != nil {
		return err
	}

	return os.Rename(tmpZipPath, zipPath)
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

		packDir := filepath.Join(cfg.StickersDir, packID)
		if packDirInfo, statErr := os.Stat(packDir); statErr != nil || !packDirInfo.IsDir() {
			if entries, dirErr := os.ReadDir(cfg.StickersDir); dirErr == nil {
				for _, entry := range entries {
					if entry.IsDir() && strings.EqualFold(entry.Name(), packID) {
						packID = entry.Name()
						packDir = filepath.Join(cfg.StickersDir, packID)
						break
					}
				}
			}
		}

		filePath := filepath.Join(packDir, fileName)
		info, err := os.Stat(filePath)
		if os.IsNotExist(err) || (err == nil && (info.IsDir() || info.Size() == 0)) {
			if err == nil && info.Size() == 0 {
				_ = os.Remove(filePath)
			}
			base := strings.TrimSuffix(fileName, filepath.Ext(fileName))
			reqExt := strings.ToLower(filepath.Ext(fileName))

			// If requested webp or mp4 but only webm is on disk, transcode safely with concurrency limits
			webmCandidate := filepath.Join(packDir, base+".webm")
			if _, webmErr := os.Stat(webmCandidate); webmErr == nil {
				if reqExt == ".webp" {
					outWebp := filepath.Join(packDir, base+".webp")
					if transcodeOnDemand(webmCandidate, outWebp, "webp") {
						if webpInfo, webpErr := os.Stat(outWebp); webpErr == nil && !webpInfo.IsDir() && webpInfo.Size() > 0 {
							filePath = outWebp
							info = webpInfo
							fileName = base + ".webp"
							err = nil
						}
					}
				} else if reqExt == ".mp4" {
					outMp4 := filepath.Join(packDir, base+".mp4")
					if transcodeOnDemand(webmCandidate, outMp4, "mp4") {
						if mp4Info, mp4Err := os.Stat(outMp4); mp4Err == nil && !mp4Info.IsDir() && mp4Info.Size() > 0 {
							filePath = outMp4
							info = mp4Info
							fileName = base + ".mp4"
							err = nil
						}
					}
				}
			}

			// If still not found, try other alternative extensions on disk
			if err != nil || (info != nil && info.IsDir()) {
				found := false
				for _, altExt := range []string{".webp", ".mp4", ".webm", ".png", ".tgs", ".json", ""} {
					altPath := filepath.Join(packDir, base+altExt)
					if altInfo, altErr := os.Stat(altPath); altErr == nil && !altInfo.IsDir() {
						filePath = altPath
						info = altInfo
						fileName = base + altExt
						found = true
						err = nil
						break
					}
				}
				// If still not found, scan pack directory for case-insensitive match on baseID
				if !found {
					if entries, dirErr := os.ReadDir(packDir); dirErr == nil {
						for _, entry := range entries {
							if !entry.IsDir() {
								eBase := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))
								if strings.EqualFold(eBase, base) {
									filePath = filepath.Join(packDir, entry.Name())
									if altInfo, altErr := entry.Info(); altErr == nil {
										info = altInfo
										fileName = entry.Name()
										found = true
										err = nil
										break
									}
								}
							}
						}
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

		// If WebM requested by Firefox or image-accepting client, try serving/transcoding to universally compatible WebP
		if strings.HasSuffix(fileName, ".webm") && (strings.Contains(r.Header.Get("User-Agent"), "Firefox") || strings.Contains(r.Header.Get("Accept"), "image/webp")) {
			base := strings.TrimSuffix(fileName, filepath.Ext(fileName))
			outWebp := filepath.Join(packDir, base+".webp")
			if _, webpErr := os.Stat(outWebp); webpErr == nil {
				filePath = outWebp
				if altInfo, statErr := os.Stat(outWebp); statErr == nil {
					info = altInfo
				}
				fileName = base + ".webp"
			} else if transcodeOnDemand(filePath, outWebp, "webp") {
				if webpInfo, webpErr := os.Stat(outWebp); webpErr == nil && !webpInfo.IsDir() && webpInfo.Size() > 0 {
					filePath = outWebp
					info = webpInfo
					fileName = base + ".webp"
				}
			}
		}

		// If WebP is requested or found but bloated (> 350KB), re-transcode with optimized lossy compression if source .webm exists
		if strings.HasSuffix(fileName, ".webp") && info != nil && info.Size() > 350*1024 {
			base := strings.TrimSuffix(fileName, filepath.Ext(fileName))
			srcWebm := filepath.Join(packDir, base+".webm")
			if _, webmErr := os.Stat(srcWebm); webmErr == nil {
				if transcodeOnDemand(srcWebm, filePath, "webp") {
					if newInfo, statErr := os.Stat(filePath); statErr == nil {
						info = newInfo
					}
				}
			}
		}

		contentType := detectStickerContentType(filePath, fileName)
		w.Header().Set("Content-Type", contentType)

		etag := fmt.Sprintf(`"%x-%x"`, info.Size(), info.ModTime().UnixNano())
		w.Header().Set("ETag", etag)
		w.Header().Set("Last-Modified", info.ModTime().UTC().Format(http.TimeFormat))
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.Header().Set("Access-Control-Allow-Origin", "*")

		if match := r.Header.Get("If-None-Match"); match != "" {
			if match == etag || match == "*" {
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

var (
	transcodeSem     = make(chan struct{}, 2)
	transcodeMu      sync.Mutex
	activeTranscodes = make(map[string]chan struct{})
)

func transcodeOnDemand(srcPath, dstPath, format string) bool {
	transcodeMu.Lock()
	if ch, active := activeTranscodes[dstPath]; active {
		transcodeMu.Unlock()
		<-ch // Wait for ongoing transcode
		if info, err := os.Stat(dstPath); err == nil && !info.IsDir() && info.Size() > 0 {
			return true
		}
		return false
	}
	done := make(chan struct{})
	activeTranscodes[dstPath] = done
	transcodeMu.Unlock()

	defer func() {
		transcodeMu.Lock()
		delete(activeTranscodes, dstPath)
		close(done)
		transcodeMu.Unlock()
	}()

	// Acquire semaphore slot (max 2 concurrent ffmpeg processes server-wide)
	select {
	case transcodeSem <- struct{}{}:
		defer func() { <-transcodeSem }()
	case <-time.After(3 * time.Second):
		return false
	}

	tmpPath := dstPath + fmt.Sprintf(".tmp_%d.%s", time.Now().UnixNano(), format)
	defer os.Remove(tmpPath)

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	var cmd *exec.Cmd
	if format == "webp" {
		// Stage 1: Optimized lossy animated WebP with 256px scaling & 15fps (~60-100KB per sticker)
		cmd = exec.CommandContext(ctx, "ffmpeg", "-y", "-i", srcPath, "-vf", "scale=256:256:force_original_aspect_ratio=decrease,fps=15,format=rgba", "-c:v", "libwebp", "-lossless", "0", "-q:v", "35", "-compression_level", "6", "-loop", "0", "-an", "-f", "webp", tmpPath)
		if _, err := cmd.CombinedOutput(); err != nil || !validNonEmptyFile(tmpPath) {
			_ = os.Remove(tmpPath)
			// Stage 2: Direct lossy animated WebP
			cmd = exec.CommandContext(ctx, "ffmpeg", "-y", "-i", srcPath, "-c:v", "libwebp", "-lossless", "0", "-q:v", "35", "-compression_level", "6", "-loop", "0", "-an", "-f", "webp", tmpPath)
			if _, err2 := cmd.CombinedOutput(); err2 != nil || !validNonEmptyFile(tmpPath) {
				_ = os.Remove(tmpPath)
				// Stage 3: Guaranteed single-frame snapshot
				cmd = exec.CommandContext(ctx, "ffmpeg", "-y", "-i", srcPath, "-vframes", "1", "-c:v", "libwebp", "-q:v", "60", "-f", "webp", tmpPath)
				if out3, err3 := cmd.CombinedOutput(); err3 != nil || !validNonEmptyFile(tmpPath) {
					_ = os.Remove(tmpPath)
					log.Printf("[Stickers] ffmpeg webp transcode failed for %s -> %s: %v, out: %s", srcPath, dstPath, err3, string(out3))
					return false
				}
			}
		}
	} else if format == "mp4" {
		cmd = exec.CommandContext(ctx, "ffmpeg", "-y", "-i", srcPath, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart", "-f", "mp4", tmpPath)
		if out, err := cmd.CombinedOutput(); err != nil || !validNonEmptyFile(tmpPath) {
			_ = os.Remove(tmpPath)
			log.Printf("[Stickers] ffmpeg mp4 transcode failed for %s -> %s: %v, out: %s", srcPath, dstPath, err, string(out))
			return false
		}
	} else {
		return false
	}

	if info, err := os.Stat(tmpPath); err == nil && info.Size() > 0 {
		if err := os.Rename(tmpPath, dstPath); err == nil {
			return true
		}
	}
	return false
}

func validNonEmptyFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir() && info.Size() > 0
}

func strconvQuote(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}
