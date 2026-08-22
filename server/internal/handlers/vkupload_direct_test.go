package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"messenger/server/internal/config"
	"messenger/server/internal/middleware"
)

func TestSanitizeVKFilename(t *testing.T) {
	cases := []struct{ in, want string }{
		{"photo.jpg", "photo.jpg"},
		{"", "encrypted.bin"},
		{"   ", "encrypted.bin"},
		{"../../etc/passwd", "passwd"},
		{`C:\windows\system32.dll`, "system32.dll"},
		{"bad\nname.bin", "badname.bin"},
	}
	for _, c := range cases {
		if got := sanitizeVKFilename(c.in); got != c.want {
			t.Errorf("sanitizeVKFilename(%q) = %q, want %q", c.in, got, c.want)
		}
	}
	if got := sanitizeVKFilename(strings.Repeat("a", 400)); len(got) != 255 {
		t.Errorf("long name length = %d, want 255", len(got))
	}
}

// stubVKAPI points the VK API base at a test server for one test.
func stubVKAPI(t *testing.T, handler http.HandlerFunc) {
	t.Helper()
	srv := httptest.NewServer(handler)
	saved := vkAPIBase
	vkAPIBase = srv.URL
	t.Cleanup(func() {
		vkAPIBase = saved
		srv.Close()
	})
}

// authedRequest builds a request carrying an authenticated user id in context.
func vkAuthedRequest(t *testing.T, method, target, body string) *http.Request {
	t.Helper()
	var r *http.Request
	if body == "" {
		r = httptest.NewRequest(method, target, nil)
	} else {
		r = httptest.NewRequest(method, target, strings.NewReader(body))
	}
	return r.WithContext(context.WithValue(r.Context(), middleware.ContextUserID, int64(7)))
}

func TestIssueVKUploadURLReturnsUploadEndpoint(t *testing.T) {
	stubVKAPI(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/groups.getById"):
			fmt.Fprint(w, `{"response":[{"id":12345}]}`)
		case strings.HasSuffix(r.URL.Path, "/docs.getWallUploadServer"):
			if got := r.URL.Query().Get("group_id"); got != "12345" {
				t.Errorf("group_id = %q, want 12345", got)
			}
			fmt.Fprint(w, `{"response":{"upload_url":"https://pu.vk.com/c123/upload_doc"}}`)
		default:
			t.Errorf("unexpected VK method %q", r.URL.Path)
		}
	})

	w := httptest.NewRecorder()
	cfg := &config.Config{VKBotToken: "secret-bot-token"}
	IssueVKUploadURL(cfg)(w, vkAuthedRequest(t, http.MethodGet, "/api/v1/attachments/vk-upload-url", ""))

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", w.Code, w.Body.String())
	}
	var got vkUploadURLResponse
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.UploadURL != "https://pu.vk.com/c123/upload_doc" {
		t.Errorf("upload_url = %q", got.UploadURL)
	}
	if strings.Contains(w.Body.String(), "secret-bot-token") {
		t.Error("response leaks the VK bot token")
	}
}

func TestIssueVKUploadURLRequiresAuth(t *testing.T) {
	w := httptest.NewRecorder()
	cfg := &config.Config{VKBotToken: "token"}
	IssueVKUploadURL(cfg)(w, httptest.NewRequest(http.MethodGet, "/api/v1/attachments/vk-upload-url", nil))

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusUnauthorized)
	}
}

func TestIssueVKUploadURLWithoutBotToken(t *testing.T) {
	w := httptest.NewRecorder()
	IssueVKUploadURL(&config.Config{})(w, vkAuthedRequest(t, http.MethodGet, "/api/v1/attachments/vk-upload-url", ""))

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusServiceUnavailable)
	}
}

func TestSaveVKAttachmentCommitsFileToken(t *testing.T) {
	stubVKAPI(t, func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/docs.save") {
			t.Errorf("unexpected VK method %q", r.URL.Path)
		}
		if got := r.URL.Query().Get("file"); got != "opaque-file-token" {
			t.Errorf("file = %q", got)
		}
		if got := r.URL.Query().Get("title"); got != "photo.enc" {
			t.Errorf("title = %q, want photo.enc", got)
		}
		fmt.Fprint(w, `{"response":{"type":"doc","doc":{"url":"https://vk.com/doc1_2?hash=x"}}}`)
	})

	w := httptest.NewRecorder()
	body := `{"file":"opaque-file-token","name":"../photo.enc"}`
	SaveVKAttachment(&config.Config{VKBotToken: "token"})(
		w, vkAuthedRequest(t, http.MethodPost, "/api/v1/attachments/vk-save", body))

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", w.Code, w.Body.String())
	}
	var got vkuploadResponse
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.URL != "https://vk.com/doc1_2?hash=x" {
		t.Errorf("url = %q", got.URL)
	}
}

func TestSaveVKAttachmentRejectsBadBody(t *testing.T) {
	cfg := &config.Config{VKBotToken: "token"}
	for _, body := range []string{`{}`, `{"file":"   "}`, `not json`} {
		w := httptest.NewRecorder()
		SaveVKAttachment(cfg)(w, vkAuthedRequest(t, http.MethodPost, "/api/v1/attachments/vk-save", body))
		if w.Code != http.StatusBadRequest {
			t.Errorf("body %q: status = %d, want %d", body, w.Code, http.StatusBadRequest)
		}
	}
}

func TestSaveVKAttachmentRejectsOversizedToken(t *testing.T) {
	body := fmt.Sprintf(`{"file":%q}`, strings.Repeat("t", maxVKFileTokenLen+1))
	w := httptest.NewRecorder()
	SaveVKAttachment(&config.Config{VKBotToken: "token"})(
		w, vkAuthedRequest(t, http.MethodPost, "/api/v1/attachments/vk-save", body))

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusBadRequest)
	}
}

func TestSaveVKAttachmentRequiresAuth(t *testing.T) {
	w := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodPost, "/api/v1/attachments/vk-save", strings.NewReader(`{"file":"tok"}`))
	SaveVKAttachment(&config.Config{VKBotToken: "token"})(w, r)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusUnauthorized)
	}
}

func TestSaveVKAttachmentRedactsTokenOnVKError(t *testing.T) {
	stubVKAPI(t, func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"error":{"error_code":5,"error_msg":"auth failed"}}`)
	})

	w := httptest.NewRecorder()
	SaveVKAttachment(&config.Config{VKBotToken: "secret-bot-token"})(
		w, vkAuthedRequest(t, http.MethodPost, "/api/v1/attachments/vk-save", `{"file":"tok"}`))

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusBadGateway)
	}
	if strings.Contains(w.Body.String(), "secret-bot-token") {
		t.Error("error response leaks the VK bot token")
	}
}

func TestRedactVKToken(t *testing.T) {
	token := "abc/def+123"
	text := "call to " + token + " and " + url.QueryEscape(token) + " failed"
	if got := redactVKToken(text, token); strings.Contains(got, token) ||
		strings.Contains(got, url.QueryEscape(token)) {
		t.Errorf("redactVKToken left the token in %q", got)
	}
	if redactVKToken("unchanged", "") != "unchanged" {
		t.Error("redactVKToken with an empty token must not alter the text")
	}
}
