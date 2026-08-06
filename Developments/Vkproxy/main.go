package main

import (
	"io"
	"log"
	"net/http"
	"net/url"
	"time"
)

var client = &http.Client{
	Timeout: 30 * time.Second,
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		return http.ErrUseLastResponse
	},
}

func proxyHandler(w http.ResponseWriter, r *http.Request) {
	target := r.URL.Query().Get("url")
	if target == "" {
		http.Error(w, "missing 'url' query parameter", http.StatusBadRequest)
		return
	}

	parsed, err := url.Parse(target)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		http.Error(w, "invalid or unsupported url scheme", http.StatusBadRequest)
		return
	}

	req, err := http.NewRequestWithContext(r.Context(), r.Method, target, r.Body)
	if err != nil {
		http.Error(w, "failed to build request: "+err.Error(), http.StatusBadRequest)
		return
	}

	copyHeaders(req.Header, r.Header)
	req.Header.Set("Host", parsed.Host)
	req.Host = parsed.Host

	resp, err := client.Do(req)
	if err != nil {
		http.Error(w, "proxy request failed: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	for k, vs := range resp.Header {
		for _, v := range vs {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func copyHeaders(dst, src http.Header) {
	for _, k := range []string{"Authorization", "Content-Type", "Accept", "Accept-Encoding", "User-Agent", "Cookie"} {
		if v := src.Get(k); v != "" {
			dst.Set(k, v)
		}
	}
}

func main() {
	addr := ":8080"
	http.HandleFunc("/api/v1/proxy", proxyHandler)
	log.Printf("vkproxy listening on %s", addr)
	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Fatal(err)
	}
}
