package handlers

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"messenger/server/internal/middleware"
)

func TestIsVKAttachmentHost(t *testing.T) {
	cases := []struct {
		host string
		want bool
	}{
		{"vk.com", true},
		{"vk.ru", true},
		{"psv4.vkuserphoto.ru", true},
		{"sun9-1.userapi.com", true},
		{"vk.com:443", true},
		{"VK.COM", true},
		{"vk.com.", true},
		{"localhost", false},
		{"127.0.0.1", false},
		{"127.0.0.1:8080", false},
		{"169.254.169.254", false},
		{"evil-vk.com", false},
		{"vk.com.evil.net", false},
		{"", false},
	}
	for _, c := range cases {
		if got := isVKAttachmentHost(c.host); got != c.want {
			t.Errorf("isVKAttachmentHost(%q) = %v, want %v", c.host, got, c.want)
		}
	}
}

func TestExtractVKDocURL(t *testing.T) {
	page := `<html><script>Docs.initDoc({"docOwnerId":-215471784,"docId":707304694,` +
		`"docExt":"mp4","docSize":6683301,` +
		`"docUrl":"https:\/\/psv4.vkuserphoto.ru\/s\/v1\/d2\/abc\/output.mp4"});</script></html>`
	want := "https://psv4.vkuserphoto.ru/s/v1/d2/abc/output.mp4"
	if got := extractVKDocURL([]byte(page)); got != want {
		t.Errorf("extractVKDocURL() = %q, want %q", got, want)
	}
	if got := extractVKDocURL([]byte(`<html>doc was deleted</html>`)); got != "" {
		t.Errorf("extractVKDocURL() on a page without a link = %q, want empty", got)
	}
}

// proxyRequest builds an authenticated request to the attachment proxy and adds
// the stub server host to the allowlist, which otherwise rejects 127.0.0.1.
func proxyRequest(t *testing.T, target string) *http.Request {
	t.Helper()
	parsed, err := url.Parse(target)
	if err != nil {
		t.Fatal(err)
	}
	saved := vkAttachmentHosts
	vkAttachmentHosts = append(append([]string{}, saved...), parsed.Hostname())
	t.Cleanup(func() { vkAttachmentHosts = saved })

	r := httptest.NewRequest(http.MethodGet, "/api/vk/attachment?url="+url.QueryEscape(target), nil)
	return r.WithContext(context.WithValue(r.Context(), middleware.ContextUserID, int64(1)))
}

func TestProxyVKAttachmentFollowsPreviewPage(t *testing.T) {
	const fileBody = "encrypted-bytes"
	var vk *httptest.Server
	vk = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/file.mp4" {
			w.Header().Set("Content-Type", "video/mp4")
			fmt.Fprint(w, fileBody)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=windows-1251")
		fmt.Fprintf(w, `<html>Docs.initDoc({"docUrl":%q});</html>`, vk.URL+"/file.mp4")
	}))
	defer vk.Close()

	w := httptest.NewRecorder()
	ProxyVKAttachment()(w, proxyRequest(t, vk.URL+"/doc-215471784_707304694"))

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", w.Code, w.Body.String())
	}
	if w.Body.String() != fileBody {
		t.Errorf("body = %q, want %q", w.Body.String(), fileBody)
	}
	if ct := w.Header().Get("Content-Type"); ct != "application/octet-stream" {
		t.Errorf("Content-Type = %q, want application/octet-stream", ct)
	}
}

func TestProxyVKAttachmentRejectsNonVKHost(t *testing.T) {
	w := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodGet, "/api/vk/attachment?url=http://127.0.0.1:9/secret", nil)
	r = r.WithContext(context.WithValue(r.Context(), middleware.ContextUserID, int64(1)))

	ProxyVKAttachment()(w, r)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusBadGateway)
	}
}

func TestProxyVKAttachmentPageWithoutLinkIsGone(t *testing.T) {
	vk := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		fmt.Fprint(w, `<html>Document was deleted</html>`)
	}))
	defer vk.Close()

	w := httptest.NewRecorder()
	ProxyVKAttachment()(w, proxyRequest(t, vk.URL+"/doc1_2"))

	if w.Code != http.StatusGone {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusGone)
	}
}
