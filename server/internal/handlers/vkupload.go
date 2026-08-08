package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

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

// vkAttachmentHosts are the domains the attachment proxy is allowed to reach.
// A doc link starts on vk.com/vk.ru and ends up on a storage CDN, so every hop
// is checked against this list — the target URL comes from the client and would
// otherwise let an authenticated user probe internal services.
var vkAttachmentHosts = []string{
	"vk.com",
	"vk.ru",
	"userapi.com",
	"vkuserphoto.ru",
	"vkuseraudio.net",
	"vkuservideo.net",
	"vkuserlive.net",
	"vk-cdn.net",
}

func isVKAttachmentHost(host string) bool {
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	for _, allowed := range vkAttachmentHosts {
		if host == allowed || strings.HasSuffix(host, "."+allowed) {
			return true
		}
	}
	return false
}

// maxVKDocPageSize caps how much of a VK preview page is buffered while looking
// for the direct link. The pages run ~200 KB; anything larger is not one.
const maxVKDocPageSize = 2 << 20

// vkDocURLRe pulls the direct download link out of VK's document preview page.
// Fetching a vk.com/doc<owner>_<id> link returns that HTML page rather than the
// file itself — the real storage link sits in the Docs.initDoc({...}) payload.
var vkDocURLRe = regexp.MustCompile(`"docUrl"\s*:\s*("(?:[^"\\]|\\.)*")`)

// extractVKDocURL returns the direct file link embedded in a VK preview page,
// or an empty string when the page carries no link (deleted or expired doc).
func extractVKDocURL(page []byte) string {
	m := vkDocURLRe.FindSubmatch(page)
	if m == nil {
		return ""
	}
	var link string
	if err := json.Unmarshal(m[1], &link); err != nil {
		return ""
	}
	return link
}

func isHTMLResponse(resp *http.Response) bool {
	return strings.HasPrefix(strings.ToLower(resp.Header.Get("Content-Type")), "text/html")
}

// ProxyVKAttachment fetches a file from VK CDN on behalf of the web client to bypass CORS restrictions.
func ProxyVKAttachment() http.HandlerFunc {
	client := &http.Client{
		Timeout: 60 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 10 {
				return fmt.Errorf("too many redirects")
			}
			if !isVKAttachmentHost(req.URL.Host) {
				return fmt.Errorf("redirect to disallowed host %q", req.URL.Host)
			}
			return nil
		},
	}

	get := func(r *http.Request, target string) (*http.Response, error) {
		parsed, err := url.Parse(target)
		if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || !isVKAttachmentHost(parsed.Host) {
			return nil, fmt.Errorf("url is not a VK attachment link")
		}
		req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, target, nil)
		if err != nil {
			return nil, fmt.Errorf("invalid url")
		}
		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		if resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			return nil, fmt.Errorf("VK returned status %d", resp.StatusCode)
		}
		return resp, nil
	}

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

		resp, err := get(r, targetURL)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusBadGateway)
			return
		}
		defer func() { resp.Body.Close() }()

		// A doc page URL answers with the HTML preview instead of the file. Follow
		// the direct link it embeds; forwarding the markup as octet-stream would
		// reach the client as garbage ciphertext and surface as a decryption error.
		if isHTMLResponse(resp) {
			page, readErr := io.ReadAll(io.LimitReader(resp.Body, maxVKDocPageSize))
			resp.Body.Close()
			if readErr != nil {
				http.Error(w, `{"error":"failed to read VK preview page"}`, http.StatusBadGateway)
				return
			}
			docURL := extractVKDocURL(page)
			if docURL == "" {
				http.Error(w, `{"error":"attachment link expired"}`, http.StatusGone)
				return
			}
			resp, err = get(r, docURL)
			if err != nil {
				http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusBadGateway)
				return
			}
			if isHTMLResponse(resp) {
				http.Error(w, `{"error":"attachment link expired"}`, http.StatusGone)
				return
			}
		}

		w.Header().Set("Content-Type", "application/octet-stream")
		if cl := resp.Header.Get("Content-Length"); cl != "" {
			w.Header().Set("Content-Length", cl)
		}
		w.WriteHeader(http.StatusOK)

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
	var lastErr error
	for attempt := 1; attempt <= 3; attempt++ {
		cdnURL, err := tryUploadBytesToVK(fileBytes, filename, botToken)
		if err == nil {
			return cdnURL, nil
		}
		lastErr = err
		time.Sleep(time.Duration(attempt*300) * time.Millisecond)
	}
	return "", lastErr
}

func tryUploadBytesToVK(fileBytes []byte, filename string, botToken string) (string, error) {
	var groupID int
	groupAPI := fmt.Sprintf("https://api.vk.com/method/groups.getById?access_token=%s&v=5.131", url.QueryEscape(botToken))
	respGroup, err := http.Get(groupAPI)
	if err == nil {
		var groupResp vkAPIResponse[[]struct {
			ID int `json:"id"`
		}]
		if json.NewDecoder(respGroup.Body).Decode(&groupResp) == nil && groupResp.Error == nil && len(groupResp.Response) > 0 {
			groupID = groupResp.Response[0].ID
		}
		respGroup.Body.Close()
	}

	// 2. Get upload URL for docs (using group_id if available, or standard docs.getUploadServer)
	var uploadServerAPI string
	if groupID > 0 {
		uploadServerAPI = fmt.Sprintf(
			"https://api.vk.com/method/docs.getWallUploadServer?group_id=%d&access_token=%s&v=5.131",
			groupID,
			url.QueryEscape(botToken),
		)
	} else {
		uploadServerAPI = fmt.Sprintf(
			"https://api.vk.com/method/docs.getUploadServer?access_token=%s&v=5.131",
			url.QueryEscape(botToken),
		)
	}

	respUploadServer, err := http.Get(uploadServerAPI)
	if err != nil {
		cleanErr := strings.ReplaceAll(err.Error(), url.QueryEscape(botToken), "[REDACTED_TOKEN]")
		cleanErr = strings.ReplaceAll(cleanErr, botToken, "[REDACTED_TOKEN]")
		return "", fmt.Errorf("upload server lookup failed: %s", cleanErr)
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
		return "", fmt.Errorf("VK upload server returned non-JSON (code %d): %s", uploadResp.StatusCode, string(uploadResponseBody))
	}

	fileStr, _ := uploadResult["file"].(string)
	if fileStr == "" || fileStr == "null" {
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
		cleanErr := strings.ReplaceAll(err.Error(), url.QueryEscape(botToken), "[REDACTED_TOKEN]")
		cleanErr = strings.ReplaceAll(cleanErr, botToken, "[REDACTED_TOKEN]")
		return "", fmt.Errorf("docs.save request failed: %s", cleanErr)
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
