package stickers

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type tgStickerSetResp struct {
	OK          bool         `json:"ok"`
	Description string       `json:"description,omitempty"`
	Result      *tgStickerSet `json:"result,omitempty"`
}

type tgStickerSet struct {
	Name        string      `json:"name"`
	Title       string      `json:"title"`
	IsAnimated  bool        `json:"is_animated"`
	IsVideo     bool        `json:"is_video"`
	Stickers    []tgSticker `json:"stickers"`
}

type tgSticker struct {
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	Width        int    `json:"width"`
	Height       int    `json:"height"`
	IsAnimated   bool   `json:"is_animated"`
	IsVideo      bool   `json:"is_video"`
	Emoji        string `json:"emoji"`
}

type tgFileResp struct {
	OK          bool    `json:"ok"`
	Description string  `json:"description,omitempty"`
	Result      *tgFile `json:"result,omitempty"`
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

// ImportTelegramPack imports a sticker pack by name using Telegram Bot API and persists it.
func ImportTelegramPack(botToken string, stickersDir string, rawPackName string, authorID int64, db *sql.DB) (*StickerPack, error) {
	if strings.TrimSpace(botToken) == "" {
		return nil, fmt.Errorf("TELEGRAM_BOT_TOKEN is not configured on the server")
	}

	packName := CleanTelegramPackName(rawPackName)
	if packName == "" {
		return nil, fmt.Errorf("invalid sticker pack name")
	}

	client := &http.Client{Timeout: 30 * time.Second}

	// 1. Fetch sticker set metadata
	reqURL := fmt.Sprintf("https://api.telegram.org/bot%s/getStickerSet?name=%s", botToken, url.QueryEscape(packName))
	resp, err := client.Get(reqURL)
	if err != nil {
		return nil, fmt.Errorf("fetch telegram sticker set: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		var errResp tgStickerSetResp
		_ = json.NewDecoder(resp.Body).Decode(&errResp)
		if errResp.Description != "" {
			return nil, fmt.Errorf("telegram api error: %s", errResp.Description)
		}
		return nil, fmt.Errorf("telegram api returned status %d", resp.StatusCode)
	}

	var setResp tgStickerSetResp
	if err := json.NewDecoder(resp.Body).Decode(&setResp); err != nil {
		return nil, fmt.Errorf("decode sticker set json: %w", err)
	}
	if !setResp.OK || setResp.Result == nil {
		return nil, fmt.Errorf("sticker set not found or error: %s", setResp.Description)
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

	// 2. Download and save each sticker
	for idx, s := range set.Stickers {
		fileResp, err := client.Get(fmt.Sprintf("https://api.telegram.org/bot%s/getFile?file_id=%s", botToken, url.QueryEscape(s.FileID)))
		if err != nil {
			continue
		}

		var fResp tgFileResp
		_ = json.NewDecoder(fileResp.Body).Decode(&fResp)
		fileResp.Body.Close()

		if !fResp.OK || fResp.Result == nil || fResp.Result.FilePath == "" {
			continue
		}

		ext := filepath.Ext(fResp.Result.FilePath)
		if ext == "" {
			if s.IsAnimated {
				ext = ".tgs"
			} else if s.IsVideo {
				ext = ".webm"
			} else {
				ext = ".webp"
			}
		}

		fileName := fmt.Sprintf("%s%s", s.FileUniqueID, ext)
		targetPath := filepath.Join(packDir, fileName)

		// Download file bytes if not already exists on disk
		if _, err := os.Stat(targetPath); os.IsNotExist(err) {
			fileDownloadURL := fmt.Sprintf("https://api.telegram.org/file/bot%s/%s", botToken, fResp.Result.FilePath)
			downResp, err := client.Get(fileDownloadURL)
			if err == nil && downResp.StatusCode == http.StatusOK {
				outFile, err := os.Create(targetPath)
				if err == nil {
					_, _ = io.Copy(outFile, downResp.Body)
					outFile.Close()
				}
				downResp.Body.Close()
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
