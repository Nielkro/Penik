package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"

	"messenger/server/internal/config"
	"messenger/server/internal/middleware"
)

type vkAPIResponse[T any] struct {
	Response T `json:"response"`
	Error    *struct {
		ErrorCode int    `json:"error_code"`
		ErrorMsg  string `json:"error_msg"`
	} `json:"error"`
}

type vkUploadServerInfo struct {
	UploadURL string `json:"upload_url"`
}

type vkuploadResponse struct {
	URL string `json:"url"`
}

// ProxyVKAttachment fetches a file from VK CDN on behalf of the web client to bypass CORS restrictions.
func ProxyVKAttachment() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}

		targetURL := r.URL.Query().Get("url")
		if targetURL == "" {
			http.Error(w, `{"error":"missing url parameter"}`, http.StatusBadRequest)
			return
		}

		resp, err := http.Get(targetURL)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusBadGateway)
			return
		}
		defer resp.Body.Close()

		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.WriteHeader(resp.StatusCode)

		_, _ = io.Copy(w, resp.Body)
	}
}

// UploadVKAttachment accepts an encrypted file payload from an authenticated user,
// uploads it to VK CDN via VK's docs API, and returns the direct CDN URL.
func UploadVKAttachment(cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := middleware.UserIDFromCtx(r.Context())
		if userID == 0 {
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}

		if cfg.VKBotToken == "" {
			http.Error(w, `{"error":"vk upload service not configured"}`, http.StatusServiceUnavailable)
			return
		}

		file, header, err := r.FormFile("file")
		if err != nil {
			http.Error(w, `{"error":"missing file field"}`, http.StatusBadRequest)
			return
		}
		defer file.Close()

		fileBytes, err := io.ReadAll(file)
		if err != nil {
			http.Error(w, `{"error":"failed to read upload data"}`, http.StatusInternalServerError)
			return
		}

		filename := header.Filename
		if filename == "" {
			filename = "encrypted.bin"
		}

		cdnURL, err := uploadBytesToVK(fileBytes, filename, cfg.VKBotToken)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(vkuploadResponse{URL: cdnURL})
	}
}

func uploadBytesToVK(fileBytes []byte, filename string, botToken string) (string, error) {
	// 1. Get group ID
	groupAPI := fmt.Sprintf("https://api.vk.com/method/groups.getById?access_token=%s&v=5.131", url.QueryEscape(botToken))
	respGroup, err := http.Get(groupAPI)
	if err != nil {
		return "", fmt.Errorf("group lookup failed: %w", err)
	}
	defer respGroup.Body.Close()

	var groupResp vkAPIResponse[[]struct {
		ID int `json:"id"`
	}]
	if err := json.NewDecoder(respGroup.Body).Decode(&groupResp); err != nil {
		return "", fmt.Errorf("decode group response: %w", err)
	}
	if groupResp.Error != nil || len(groupResp.Response) == 0 {
		return "", fmt.Errorf("group lookup error: %v", groupResp.Error)
	}

	groupID := groupResp.Response[0].ID

	// 2. Get upload URL for wall docs
	uploadServerAPI := fmt.Sprintf(
		"https://api.vk.com/method/docs.getWallUploadServer?group_id=%d&access_token=%s&v=5.131",
		groupID,
		url.QueryEscape(botToken),
	)

	respUploadServer, err := http.Get(uploadServerAPI)
	if err != nil {
		return "", fmt.Errorf("upload server lookup failed: %w", err)
	}
	defer respUploadServer.Body.Close()

	var vkResp vkAPIResponse[vkUploadServerInfo]
	if err := json.NewDecoder(respUploadServer.Body).Decode(&vkResp); err != nil {
		return "", fmt.Errorf("decode upload server response: %w", err)
	}
	if vkResp.Error != nil {
		return "", fmt.Errorf("VK upload server error (%d): %s", vkResp.Error.ErrorCode, vkResp.Error.ErrorMsg)
	}

	uploadURL := vkResp.Response.UploadURL

	// 3. Multipart POST to uploadURL
	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	fileWriter, err := writer.CreateFormFile("file", filename)
	if err != nil {
		return "", fmt.Errorf("create form file: %w", err)
	}
	if _, err := fileWriter.Write(fileBytes); err != nil {
		return "", fmt.Errorf("write bytes to form: %w", err)
	}
	writer.Close()

	req, err := http.NewRequest("POST", uploadURL, body)
	if err != nil {
		return "", fmt.Errorf("create POST request: %w", err)
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())

	client := &http.Client{}
	uploadResp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("POST to VK upload server failed: %w", err)
	}
	defer uploadResp.Body.Close()

	uploadResponseBody, err := io.ReadAll(uploadResp.Body)
	if err != nil {
		return "", fmt.Errorf("read upload response: %w", err)
	}

	var uploadResult map[string]interface{}
	if err := json.Unmarshal(uploadResponseBody, &uploadResult); err != nil {
		return "", fmt.Errorf("unmarshal upload response: %w", err)
	}

	fileStr, _ := uploadResult["file"].(string)
	if fileStr == "" {
		return "", fmt.Errorf("VK rejected uploaded file: %s", string(uploadResponseBody))
	}

	// 4. Call docs.save to commit the upload
	saveAPIURL := fmt.Sprintf(
		"https://api.vk.com/method/docs.save?file=%s&title=%s&access_token=%s&v=5.131",
		url.QueryEscape(fileStr),
		url.QueryEscape(filename),
		url.QueryEscape(botToken),
	)

	saveResp, err := http.Get(saveAPIURL)
	if err != nil {
		return "", fmt.Errorf("docs.save request failed: %w", err)
	}
	defer saveResp.Body.Close()

	var saveResult map[string]interface{}
	if err := json.NewDecoder(saveResp.Body).Decode(&saveResult); err != nil {
		return "", fmt.Errorf("decode docs.save response: %w", err)
	}

	if errVal, ok := saveResult["error"]; ok {
		return "", fmt.Errorf("VK docs.save error: %v", errVal)
	}

	if respMap, ok := saveResult["response"].(map[string]interface{}); ok {
		if docMap, ok := respMap["doc"].(map[string]interface{}); ok {
			if docURL, ok := docMap["url"].(string); ok && docURL != "" {
				return docURL, nil
			}
		}
	}

	if respArr, ok := saveResult["response"].([]interface{}); ok && len(respArr) > 0 {
		if docMap, ok := respArr[0].(map[string]interface{}); ok {
			if docURL, ok := docMap["url"].(string); ok && docURL != "" {
				return docURL, nil
			}
		}
	}

	return "", fmt.Errorf("could not extract direct URL from docs.save response: %v", saveResult)
}
