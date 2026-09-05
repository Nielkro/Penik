package handlers

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"time"

	"messenger/server/internal/config"
	"messenger/server/internal/db"
	"messenger/server/internal/middleware"
)

var attachmentIDRegex = regexp.MustCompile(`^[a-f0-9]{32}$`)

type uploadAttachmentResponse struct {
	ID  string `json:"id"`
	URL string `json:"url"`
}

// UploadAttachment handles POST /api/v1/attachments/upload (multipart, authenticated).
func UploadAttachment(database *db.DB, cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		uploaderID := middleware.UserIDFromCtx(r.Context())
		if uploaderID == 0 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		if err := r.ParseMultipartForm(cfg.MaxUploadSize); err != nil {
			http.Error(w, "multipart parse error", http.StatusBadRequest)
			return
		}

		file, _, err := r.FormFile("file")
		if err != nil {
			http.Error(w, "file field required", http.StatusBadRequest)
			return
		}
		defer file.Close()

		var idBytes [16]byte
		if _, err := rand.Read(idBytes[:]); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		id := hex.EncodeToString(idBytes[:])

		attachDir := filepath.Join(cfg.UploadDir, "attachments")
		if err := os.MkdirAll(attachDir, 0755); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		filePath := filepath.Join(attachDir, id+".bin")
		dst, err := os.OpenFile(filePath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0644)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		defer dst.Close()

		limitedReader := io.LimitReader(file, cfg.MaxUploadSize+1)
		written, err := io.Copy(dst, limitedReader)
		if err != nil {
			_ = os.Remove(filePath)
			http.Error(w, "write error", http.StatusInternalServerError)
			return
		}
		if written > cfg.MaxUploadSize {
			_ = os.Remove(filePath)
			http.Error(w, "file exceeds maximum upload size", http.StatusRequestEntityTooLarge)
			return
		}

		if database != nil {
			now := time.Now().Unix()
			_, err = database.ExecContext(r.Context(),
				`INSERT INTO attachments(id, uploader_user_id, created_at) VALUES(?, ?, ?)`,
				id, uploaderID, now)
			if err != nil {
				_ = os.Remove(filePath)
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(uploadAttachmentResponse{
			ID:  id,
			URL: fmt.Sprintf("/api/v1/attachments/file/%s", id),
		})
	}
}

// GetAttachment handles GET /api/v1/attachments/file/{id} (authenticated, supports Range requests).
func GetAttachment(database *db.DB, cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if !attachmentIDRegex.MatchString(id) {
			http.Error(w, "invalid attachment id", http.StatusBadRequest)
			return
		}

		if database != nil {
			var uploaderID int64
			err := database.QueryRowContext(r.Context(),
				`SELECT uploader_user_id FROM attachments WHERE id=?`, id).Scan(&uploaderID)
			if err == sql.ErrNoRows {
				http.Error(w, "attachment not found", http.StatusNotFound)
				return
			}
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}

			callerID := middleware.UserIDFromCtx(r.Context())
			if callerID == 0 {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			allowed, err := database.CanAccessAttachment(r.Context(), callerID, uploaderID)
			if err != nil {
				http.Error(w, "internal error", http.StatusInternalServerError)
				return
			}
			if !allowed {
				http.Error(w, "forbidden: attachment not accessible", http.StatusForbidden)
				return
			}
		}

		filePath := filepath.Join(cfg.UploadDir, "attachments", id+".bin")
		f, err := os.Open(filePath)
		if err != nil {
			if os.IsNotExist(err) {
				http.Error(w, "attachment not found", http.StatusNotFound)
			} else {
				http.Error(w, "internal error", http.StatusInternalServerError)
			}
			return
		}
		defer f.Close()

		stat, err := f.Stat()
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("ETag", fmt.Sprintf(`"%s"`, id))

		http.ServeContent(w, r, id+".bin", stat.ModTime(), f)
	}
}
