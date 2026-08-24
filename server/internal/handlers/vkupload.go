package handlers

import (
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

// vkUploadURLResponse hands a short-lived VK upload endpoint to a client that
// performs the multipart POST itself (mobile flow), so the file bytes never
// pass through this server.
type vkUploadURLResponse struct {
	UploadURL string `json:"upload_url"`
}

// vkSaveRequest carries the opaque `file` token VK's upload server returns to
// the client, which only the bot token holder can commit via docs.save.
type vkSaveRequest struct {
	File string `json:"file"`
	Name string `json:"name"`
}

// maxVKFileTokenLen bounds the opaque upload token echoed back by the client.
// Real tokens are a few hundred bytes; the cap keeps an oversized body out of
// the outbound VK API URL.
const maxVKFileTokenLen = 8192

// vkAPIClient talks to api.vk.com. A shared client with a timeout keeps a
// hanging VK response from pinning a request goroutine indefinitely.
var vkAPIClient = &http.Client{Timeout: 30 * time.Second}

// vkAPIBase is the VK API method root. It is a variable so tests can point the
// upload flow at a stub server instead of the live API.
var vkAPIBase = "https://api.vk.com/method"

// sanitizeVKFilename reduces a client-supplied name to a plain, bounded file
// name: no path separators, no control characters, never empty.
func sanitizeVKFilename(name string) string {
	name = strings.TrimSpace(name)
	if i := strings.LastIndexAny(name, `/\`); i >= 0 {
		name = name[i+1:]
	}
	name = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f {
			return -1
		}
		return r
	}, name)
	if len(name) > 255 {
		name = name[:255]
	}
	if name == "" {
		return "encrypted.bin"
	}
	return name
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
//
// This is the web flow: browsers cannot POST to the VK upload server directly
// (no CORS headers there), so the bytes are relayed through this server.
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

		// Parse multipart with 32MB in-memory buffer, remainder streams to temp file on disk
		if err := r.ParseMultipartForm(32 << 20); err != nil {
			http.Error(w, `{"error":"failed to parse multipart form"}`, http.StatusBadRequest)
			return
		}

		file, header, err := r.FormFile("file")
		if err != nil {
			http.Error(w, `{"error":"missing file field"}`, http.StatusBadRequest)
			return
		}
		defer file.Close()

		filename := sanitizeVKFilename(header.Filename)

		cdnURL, err := uploadStreamToVK(file, filename, cfg.VKBotToken)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, redactVKToken(err.Error(), cfg.VKBotToken)), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(vkuploadResponse{URL: cdnURL})
	}
}

// IssueVKUploadURL hands the caller a one-shot VK upload endpoint so the client
// uploads the encrypted bytes itself. This is the native (Android) flow: the
// payload never touches this server, which removes it as a bandwidth and memory
// bottleneck for large attachments.
//
// The returned URL is a VK-signed, single-use endpoint that accepts a multipart
// POST and answers with an opaque `file` token; that token still has to be
// committed through SaveVKAttachment, which is the only place the bot token is
// used. The bot token itself is never exposed.
func IssueVKUploadURL(cfg *config.Config) http.HandlerFunc {
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

		uploadURL, err := fetchVKUploadURL(cfg.VKBotToken)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, redactVKToken(err.Error(), cfg.VKBotToken)), http.StatusBadGateway)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		json.NewEncoder(w).Encode(vkUploadURLResponse{UploadURL: uploadURL})
	}
}

// SaveVKAttachment commits a client-side upload: it takes the opaque `file`
// token the VK upload server returned to the client, calls docs.save with the
// bot token, and answers with the direct CDN link.
func SaveVKAttachment(cfg *config.Config) http.HandlerFunc {
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

		var req vkSaveRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, maxVKFileTokenLen+1024)).Decode(&req); err != nil {
			http.Error(w, `{"error":"invalid json body"}`, http.StatusBadRequest)
			return
		}

		req.File = strings.TrimSpace(req.File)
		if req.File == "" {
			http.Error(w, `{"error":"missing file parameter"}`, http.StatusBadRequest)
			return
		}
		if len(req.File) > maxVKFileTokenLen {
			http.Error(w, `{"error":"file token too long"}`, http.StatusBadRequest)
			return
		}

		filename := sanitizeVKFilename(req.Name)

		cdnURL, err := saveVKDoc(req.File, filename, cfg.VKBotToken)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, redactVKToken(err.Error(), cfg.VKBotToken)), http.StatusBadGateway)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(vkuploadResponse{URL: cdnURL})
	}
}

func uploadStreamToVK(fileReader io.Reader, filename string, botToken string) (string, error) {
	uploadURL, err := fetchVKUploadURL(botToken)
	if err != nil {
		return "", err
	}

	pr, pw := io.Pipe()
	writer := multipart.NewWriter(pw)

	errChan := make(chan error, 1)
	go func() {
		defer pw.Close()
		fileWriter, err := writer.CreateFormFile("file", filename)
		if err != nil {
			errChan <- fmt.Errorf("create form file: %w", err)
			return
		}
		if _, err := io.Copy(fileWriter, fileReader); err != nil {
			errChan <- fmt.Errorf("stream copy to form: %w", err)
			return
		}
		if err := writer.Close(); err != nil {
			errChan <- fmt.Errorf("close multipart writer: %w", err)
			return
		}
		errChan <- nil
	}()

	req, err := http.NewRequest("POST", uploadURL, pr)
	if err != nil {
		pr.Close()
		return "", fmt.Errorf("create POST request: %w", err)
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())

	client := &http.Client{Timeout: 10 * time.Minute}
	uploadResp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("POST to VK upload server failed: %w", err)
	}
	defer uploadResp.Body.Close()

	if pipeErr := <-errChan; pipeErr != nil {
		return "", pipeErr
	}

	uploadResponseBody, err := io.ReadAll(io.LimitReader(uploadResp.Body, 1024*1024))
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

	return saveVKDoc(fileStr, filename, botToken)
}

// fetchVKUploadURL asks VK for a one-shot upload endpoint, preferring the
// group wall server when the bot token belongs to a community.
func fetchVKUploadURL(botToken string) (string, error) {
	var groupID int
	groupAPI := fmt.Sprintf(vkAPIBase+"/groups.getById?access_token=%s&v=5.131", url.QueryEscape(botToken))
	respGroup, err := vkAPIClient.Get(groupAPI)
	if err == nil {
		var groupResp vkAPIResponse[[]struct {
			ID int `json:"id"`
		}]
		if json.NewDecoder(respGroup.Body).Decode(&groupResp) == nil && groupResp.Error == nil && len(groupResp.Response) > 0 {
			groupID = groupResp.Response[0].ID
		}
		respGroup.Body.Close()
	}

	var uploadServerAPI string
	if groupID > 0 {
		uploadServerAPI = fmt.Sprintf(
			vkAPIBase+"/docs.getWallUploadServer?group_id=%d&access_token=%s&v=5.131",
			groupID,
			url.QueryEscape(botToken),
		)
	} else {
		uploadServerAPI = fmt.Sprintf(
			vkAPIBase+"/docs.getUploadServer?access_token=%s&v=5.131",
			url.QueryEscape(botToken),
		)
	}

	respUploadServer, err := vkAPIClient.Get(uploadServerAPI)
	if err != nil {
		return "", fmt.Errorf("upload server lookup failed: %s", redactVKToken(err.Error(), botToken))
	}
	defer respUploadServer.Body.Close()

	var vkResp vkAPIResponse[vkUploadServerInfo]
	if err := json.NewDecoder(respUploadServer.Body).Decode(&vkResp); err != nil {
		return "", fmt.Errorf("decode upload server response: %w", err)
	}
	if vkResp.Error != nil {
		return "", fmt.Errorf("VK upload server error (%d): %s", vkResp.Error.ErrorCode, vkResp.Error.ErrorMsg)
	}
	if vkResp.Response.UploadURL == "" {
		return "", fmt.Errorf("VK returned an empty upload url")
	}
	return vkResp.Response.UploadURL, nil
}

// saveVKDoc commits an uploaded file token via docs.save and returns the direct
// document link. The token is opaque and useless without the bot access token,
// which is why the client may hold it between the two calls.
func saveVKDoc(fileToken string, filename string, botToken string) (string, error) {
	saveAPIURL := fmt.Sprintf(
		vkAPIBase+"/docs.save?file=%s&title=%s&access_token=%s&v=5.131",
		url.QueryEscape(fileToken),
		url.QueryEscape(filename),
		url.QueryEscape(botToken),
	)

	saveResp, err := vkAPIClient.Get(saveAPIURL)
	if err != nil {
		return "", fmt.Errorf("docs.save request failed: %s", redactVKToken(err.Error(), botToken))
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

// redactVKToken strips the bot access token from text that is about to reach a
// client or the log, in both raw and percent-encoded form.
func redactVKToken(text, botToken string) string {
	if botToken == "" {
		return text
	}
	text = strings.ReplaceAll(text, url.QueryEscape(botToken), "[REDACTED_TOKEN]")
	return strings.ReplaceAll(text, botToken, "[REDACTED_TOKEN]")
}

