package stickers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

type tgResponseParameters struct {
	RetryAfter int `json:"retry_after,omitempty"`
}

type tgStickerSetResp struct {
	OK          bool                  `json:"ok"`
	Description string                `json:"description,omitempty"`
	Result      *tgStickerSet         `json:"result,omitempty"`
	Parameters  *tgResponseParameters `json:"parameters,omitempty"`
}

type tgStickerSet struct {
	Name       string      `json:"name"`
	Title      string      `json:"title"`
	IsAnimated bool        `json:"is_animated"`
	IsVideo    bool        `json:"is_video"`
	Stickers   []tgSticker `json:"stickers"`
}

type tgPhotoSize struct {
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	Width        int    `json:"width"`
	Height       int    `json:"height"`
	FileSize     int    `json:"file_size,omitempty"`
}

type tgSticker struct {
	FileID       string       `json:"file_id"`
	FileUniqueID string       `json:"file_unique_id"`
	Width        int          `json:"width"`
	Height       int          `json:"height"`
	IsAnimated   bool         `json:"is_animated"`
	IsVideo      bool         `json:"is_video"`
	Emoji        string       `json:"emoji"`
	Thumbnail    *tgPhotoSize `json:"thumbnail,omitempty"`
	Thumb        *tgPhotoSize `json:"thumb,omitempty"`
}

type tgFileResp struct {
	OK          bool                  `json:"ok"`
	Description string                `json:"description,omitempty"`
	Result      *tgFile               `json:"result,omitempty"`
	Parameters  *tgResponseParameters `json:"parameters,omitempty"`
}

type tgFile struct {
	FileID   string `json:"file_id"`
	FilePath string `json:"file_path"`
}

// CleanTelegramPackName extracts the plain sticker pack identifier from URLs or raw names.
func CleanTelegramPackName(raw string) string {
	raw = strings.TrimSpace(raw)
	if strings.HasPrefix(raw, "http://") || strings.HasPrefix(raw, "https://") {
		if u, err := url.Parse(raw); err == nil {
			parts := strings.Split(strings.Trim(u.Path, "/"), "/")
			if len(parts) >= 2 && (parts[0] == "addstickers" || parts[0] == "stickers") {
				return parts[1]
			}
			if len(parts) >= 1 && parts[0] != "" {
				return parts[len(parts)-1]
			}
		}
	}
	if strings.HasPrefix(raw, "tg://addstickers?set=") {
		return strings.TrimPrefix(raw, "tg://addstickers?set=")
	}
	return raw
}

func fetchTelegramFileWithRetry(client *http.Client, botToken string, fileID string) (*tgFile, error) {
	reqURL := fmt.Sprintf("https://api.telegram.org/bot%s/getFile?file_id=%s", botToken, url.QueryEscape(fileID))
	var lastErr error
	for attempt := 0; attempt < 5; attempt++ {
		resp, err := client.Get(reqURL)
		if err != nil {
			lastErr = err
			time.Sleep(time.Duration(300*(attempt+1)) * time.Millisecond)
			continue
		}

		var fResp tgFileResp
		_ = json.NewDecoder(resp.Body).Decode(&fResp)
		resp.Body.Close()

		if resp.StatusCode == http.StatusTooManyRequests || (fResp.Parameters != nil && fResp.Parameters.RetryAfter > 0) {
			sleepSec := 1
			if fResp.Parameters != nil && fResp.Parameters.RetryAfter > 0 {
				sleepSec = fResp.Parameters.RetryAfter
			}
			time.Sleep(time.Duration(sleepSec)*time.Second + 100*time.Millisecond)
			continue
		}

		if fResp.OK && fResp.Result != nil && fResp.Result.FilePath != "" {
			return fResp.Result, nil
		}

		if fResp.Description != "" {
			lastErr = fmt.Errorf("telegram getFile error: %s", fResp.Description)
		} else {
			lastErr = fmt.Errorf("telegram getFile status %d", resp.StatusCode)
		}
		time.Sleep(time.Duration(200*(attempt+1)) * time.Millisecond)
	}
	return nil, lastErr
}

func downloadTelegramFileWithRetry(client *http.Client, botToken string, filePath string, targetPath string) error {
	// If file already exists and is non-empty, skip re-download
	if stat, err := os.Stat(targetPath); err == nil && stat.Size() > 0 {
		return nil
	}

	downloadURL := fmt.Sprintf("https://api.telegram.org/file/bot%s/%s", botToken, filePath)
	tempPath := targetPath + fmt.Sprintf(".tmp_%d", time.Now().UnixNano())

	var lastErr error
	for attempt := 0; attempt < 4; attempt++ {
		resp, err := client.Get(downloadURL)
		if err != nil {
			lastErr = err
			time.Sleep(time.Duration(300*(attempt+1)) * time.Millisecond)
			continue
		}

		if resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			lastErr = fmt.Errorf("download status: %d", resp.StatusCode)
			time.Sleep(time.Duration(500*(attempt+1)) * time.Millisecond)
			continue
		}

		outFile, err := os.Create(tempPath)
		if err != nil {
			resp.Body.Close()
			return fmt.Errorf("create temp file: %w", err)
		}

		_, copyErr := io.Copy(outFile, resp.Body)
		outFile.Close()
		resp.Body.Close()

		if copyErr != nil {
			_ = os.Remove(tempPath)
			lastErr = copyErr
			time.Sleep(time.Duration(200*(attempt+1)) * time.Millisecond)
			continue
		}

		if stat, err := os.Stat(tempPath); err == nil && stat.Size() > 0 {
			if err := os.Rename(tempPath, targetPath); err == nil {
				return nil
			}
		}
		_ = os.Remove(tempPath)
	}
	return lastErr
}

// ImportTelegramPack imports a sticker pack by name using Telegram Bot API and persists it.
func ImportTelegramPack(botToken string, stickersDir string, rawPackName string, authorID int64, db *sql.DB) (*StickerPack, error) {
	if strings.TrimSpace(botToken) == "" {
		return nil, fmt.Errorf("TELEGRAM_BOT_TOKEN is not configured on the server")
	}

	packName := CleanTelegramPackName(rawPackName)
	if packName == "" {
		return nil, fmt.Errorf("invalid sticker pack name")
	}

	client := &http.Client{Timeout: 35 * time.Second}

	// 1. Fetch sticker set metadata with retries
	reqURL := fmt.Sprintf("https://api.telegram.org/bot%s/getStickerSet?name=%s", botToken, url.QueryEscape(packName))
	var setResp tgStickerSetResp
	var fetchErr error

	for attempt := 0; attempt < 3; attempt++ {
		resp, err := client.Get(reqURL)
		if err != nil {
			fetchErr = err
			time.Sleep(time.Duration(400*(attempt+1)) * time.Millisecond)
			continue
		}

		decodeErr := json.NewDecoder(resp.Body).Decode(&setResp)
		resp.Body.Close()

		if decodeErr != nil {
			fetchErr = decodeErr
			continue
		}

		if resp.StatusCode == http.StatusTooManyRequests || (setResp.Parameters != nil && setResp.Parameters.RetryAfter > 0) {
			sleepSec := 1
			if setResp.Parameters != nil && setResp.Parameters.RetryAfter > 0 {
				sleepSec = setResp.Parameters.RetryAfter
			}
			time.Sleep(time.Duration(sleepSec)*time.Second + 100*time.Millisecond)
			continue
		}

		if setResp.OK && setResp.Result != nil {
			fetchErr = nil
			break
		}

		if setResp.Description != "" {
			fetchErr = fmt.Errorf("telegram api error: %s", setResp.Description)
		} else {
			fetchErr = fmt.Errorf("telegram api status %d", resp.StatusCode)
		}
		time.Sleep(time.Duration(300*(attempt+1)) * time.Millisecond)
	}

	if fetchErr != nil {
		return nil, fmt.Errorf("fetch telegram sticker set: %w", fetchErr)
	}
	if !setResp.OK || setResp.Result == nil {
		return nil, fmt.Errorf("sticker set not found: %s", setResp.Description)
	}

	set := setResp.Result
	if len(set.Stickers) == 0 {
		return nil, fmt.Errorf("sticker set has no stickers")
	}

	packDir := filepath.Join(stickersDir, packName)
	if err := os.MkdirAll(packDir, 0755); err != nil {
		return nil, fmt.Errorf("create pack directory: %w", err)
	}

	packID := packName
	coverStickerID := set.Stickers[0].FileUniqueID

	now := time.Now().Unix()

	tx, err := db.Begin()
	if err != nil {
		return nil, fmt.Errorf("begin db tx: %w", err)
	}
	defer tx.Rollback()

	// Insert or replace sticker pack
	_, err = tx.Exec(`
		INSERT INTO sticker_packs (id, title, author_id, cover_sticker_id, is_animated, is_video, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET
			title = excluded.title,
			cover_sticker_id = excluded.cover_sticker_id,
			is_animated = excluded.is_animated,
			is_video = excluded.is_video
	`, packID, set.Title, authorID, coverStickerID, set.IsAnimated, set.IsVideo, now)
	if err != nil {
		return nil, fmt.Errorf("save sticker pack to db: %w", err)
	}

	var downloadedStickers []Sticker

	// 2. Download and save each sticker with rate-limit pacing and retries
	for idx, s := range set.Stickers {
		// Rate-limit pacing between sticker file queries
		if idx > 0 {
			time.Sleep(45 * time.Millisecond)
		}

		tgFileInfo, err := fetchTelegramFileWithRetry(client, botToken, s.FileID)
		if err != nil {
			// If getFile fails after retries, try to fall back to existing file on disk if available
			var fallbackExt string
			if s.IsVideo || set.IsVideo {
				fallbackExt = ".webm"
			} else if s.IsAnimated || set.IsAnimated {
				fallbackExt = ".tgs"
			} else {
				fallbackExt = ".webp"
			}
			fallbackName := fmt.Sprintf("%s%s", s.FileUniqueID, fallbackExt)
			if _, statErr := os.Stat(filepath.Join(packDir, fallbackName)); statErr == nil {
				downloadedStickers = append(downloadedStickers, Sticker{
					ID:        s.FileUniqueID,
					PackID:    packID,
					Emoji:     s.Emoji,
					FileName:  fallbackName,
					Width:     s.Width,
					Height:    s.Height,
					SortOrder: idx,
				})
			}
			continue
		}

		ext := filepath.Ext(tgFileInfo.FilePath)
		if ext == "" {
			if s.IsVideo || set.IsVideo {
				ext = ".webm"
			} else if s.IsAnimated || set.IsAnimated {
				ext = ".tgs"
			} else {
				ext = ".webp"
			}
		}

		fileName := fmt.Sprintf("%s%s", s.FileUniqueID, ext)
		targetPath := filepath.Join(packDir, fileName)

		// Download main file bytes with retry
		if err := downloadTelegramFileWithRetry(client, botToken, tgFileInfo.FilePath, targetPath); err != nil {
			// Check if file existed before
			if _, statErr := os.Stat(targetPath); statErr != nil {
				continue
			}
		}

		// If this is a video sticker, transcode to animated webp (universal compatibility & transparency) and mp4
		if ext == ".webm" {
			targetWebpPath := filepath.Join(packDir, fmt.Sprintf("%s.webp", s.FileUniqueID))
			targetMp4Path := filepath.Join(packDir, fmt.Sprintf("%s.mp4", s.FileUniqueID))
			if _, statErr := os.Stat(targetWebpPath); os.IsNotExist(statErr) {
				_ = exec.Command("ffmpeg", "-y", "-i", targetPath, "-vcodec", "libwebp", "-lossless", "0", "-compression_level", "4", "-q:v", "75", "-loop", "0", "-an", "-vsync", "0", targetWebpPath).Run()
			}
			if _, statErr := os.Stat(targetMp4Path); os.IsNotExist(statErr) {
				_ = exec.Command("ffmpeg", "-y", "-i", targetPath, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart", targetMp4Path).Run()
			}
		} else if ext == ".tgs" {
			targetThumbPath := filepath.Join(packDir, fmt.Sprintf("%s.webp", s.FileUniqueID))
			if _, statErr := os.Stat(targetThumbPath); os.IsNotExist(statErr) {
				thumbObj := s.Thumbnail
				if thumbObj == nil {
					thumbObj = s.Thumb
				}
				if thumbObj != nil && thumbObj.FileID != "" {
					if thumbInfo, err := fetchTelegramFileWithRetry(client, botToken, thumbObj.FileID); err == nil && thumbInfo != nil && thumbInfo.FilePath != "" {
						_ = downloadTelegramFileWithRetry(client, botToken, thumbInfo.FilePath, targetThumbPath)
					}
				}
			}
		}

		stickerID := s.FileUniqueID
		_, err = tx.Exec(`
			INSERT INTO stickers (id, pack_id, emoji, file_name, width, height, sort_order)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(pack_id, id) DO UPDATE SET
				emoji = excluded.emoji,
				file_name = excluded.file_name,
				width = excluded.width,
				height = excluded.height,
				sort_order = excluded.sort_order
		`, stickerID, packID, s.Emoji, fileName, s.Width, s.Height, idx)
		if err != nil {
			return nil, fmt.Errorf("save sticker %s: %w", stickerID, err)
		}

		downloadedStickers = append(downloadedStickers, Sticker{
			ID:        stickerID,
			PackID:    packID,
			Emoji:     s.Emoji,
			FileName:  fileName,
			Width:     s.Width,
			Height:    s.Height,
			SortOrder: idx,
		})
	}

	// Add to user's installed sticker packs
	if authorID > 0 {
		_, _ = tx.Exec(`
			INSERT OR IGNORE INTO user_sticker_packs (user_id, pack_id, sort_order, installed_at)
			VALUES (?, ?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM user_sticker_packs WHERE user_id = ?), ?)
		`, authorID, packID, authorID, now)
	}

	if err := tx.Commit(); err != nil {
		return nil, fmt.Errorf("commit sticker pack tx: %w", err)
	}

	return &StickerPack{
		ID:             packID,
		Title:          set.Title,
		AuthorID:       authorID,
		CoverStickerID: coverStickerID,
		IsAnimated:     set.IsAnimated,
		IsVideo:        set.IsVideo,
		CreatedAt:      now,
		Stickers:       downloadedStickers,
	}, nil
}
