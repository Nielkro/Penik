//go:build !linux

package main

func setupPlatformWebview() {
	// WebRTC and permissions are enabled by default on WebView2 (Windows) and WKWebView (macOS)
}
