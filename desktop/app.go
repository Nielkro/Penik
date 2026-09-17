package main

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/base64"
	"fmt"
	"image"
	"image/color"
	_ "image/gif"
	_ "image/jpeg"
	"image/png"
	"io"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	_ "golang.org/x/image/webp"
	wruntime "github.com/wailsapp/wails/v2/pkg/runtime"

	"github.com/getlantern/systray"
)

const AppVersion = "1.0.0"

type FileFilter struct {
	DisplayName string `json:"displayName"`
	Pattern     string `json:"pattern"`
}

type HttpResponse struct {
	Status     int               `json:"status"`
	StatusText string            `json:"statusText"`
	Headers    map[string]string `json:"headers"`
	Body       string            `json:"body"`
}

type HttpBinaryResponse struct {
	Status     int               `json:"status"`
	StatusText string            `json:"statusText"`
	Headers    map[string]string `json:"headers"`
	BodyBase64 string            `json:"bodyBase64"`
}

type VersionInfo struct {
	Version    string `json:"version"`
	Platform   string `json:"platform"`
	DeviceName string `json:"deviceName"`
	ServerURL  string `json:"serverUrl"`
}

// App struct
type App struct {
	ctx        context.Context
	config     *Config
	quitting   bool
}

// NewApp creates a new App application struct
func NewApp() *App {
	cfg, _ := LoadConfig()
	return &App{
		config: cfg,
	}
}

// startup is called when the app starts. The context is saved
// so we can call the runtime methods
func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
}

// beforeClose is called when the application is closing
func (a *App) beforeClose(ctx context.Context) (prevent bool) {
	if a.quitting {
		return false
	}
	if a.config != nil && a.config.MinimizeToTray {
		wruntime.WindowHide(a.ctx)
		return true
	}
	return false
}

// GetServerURL returns the configured backend server URL
func (a *App) GetServerURL() string {
	if a.config != nil && a.config.ServerURL != "" {
		return a.config.ServerURL
	}
	return DefaultServerURL
}

// SetServerURL saves and applies a new server URL
func (a *App) SetServerURL(serverURL string) error {
	trimmed := strings.TrimRight(strings.TrimSpace(serverURL), "/")
	if trimmed == "" {
		trimmed = DefaultServerURL
	}
	if a.config == nil {
		a.config = getDefaultConfig()
	}
	a.config.ServerURL = trimmed
	return SaveConfig(a.config)
}

// GetPlatform returns current OS name ("linux", "windows", "darwin")
func (a *App) GetPlatform() string {
	return runtime.GOOS
}

// GetDeviceName returns human-readable desktop device label
func (a *App) GetDeviceName() string {
	hostname, err := os.Hostname()
	if err != nil || hostname == "" {
		switch runtime.GOOS {
		case "windows":
			return "Windows Desktop"
		case "linux":
			return "Linux Desktop"
		case "darwin":
			return "macOS Desktop"
		default:
			return "Desktop Client"
		}
	}

	switch runtime.GOOS {
	case "windows":
		return fmt.Sprintf("Windows (%s)", hostname)
	case "linux":
		return fmt.Sprintf("Linux (%s)", hostname)
	case "darwin":
		return fmt.Sprintf("macOS (%s)", hostname)
	default:
		return fmt.Sprintf("Desktop (%s)", hostname)
	}
}

// GetVersionInfo returns structured version information for the client
func (a *App) GetVersionInfo() VersionInfo {
	return VersionInfo{
		Version:    AppVersion,
		Platform:   a.GetPlatform(),
		DeviceName: a.GetDeviceName(),
		ServerURL:  a.GetServerURL(),
	}
}

// cropCircle takes raw image bytes (JPEG, PNG, WebP), crops to a center square, masks to a smooth circle, and encodes as PNG.
func cropCircle(raw []byte) ([]byte, error) {
	src, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}

	bounds := src.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	size := w
	if h < size {
		size = h
	}
	if size <= 0 {
		return nil, fmt.Errorf("invalid image dimensions")
	}

	offsetX := (w - size) / 2
	offsetY := (h - size) / 2

	dst := image.NewRGBA(image.Rect(0, 0, size, size))
	radius := float64(size) / 2.0
	centerX := radius
	centerY := radius

	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			dx := float64(x) + 0.5 - centerX
			dy := float64(y) + 0.5 - centerY
			distSq := dx*dx + dy*dy
			rSq := radius * radius

			if distSq > rSq {
				dst.SetRGBA(x, y, color.RGBA{0, 0, 0, 0})
			} else {
				srcColor := src.At(bounds.Min.X+offsetX+x, bounds.Min.Y+offsetY+y)
				r, g, b, a := srcColor.RGBA()

				edgeDist := radius - math.Sqrt(distSq)
				alphaMult := 1.0
				if edgeDist < 1.0 && edgeDist >= 0.0 {
					alphaMult = edgeDist
				}

				origAlpha := float64(a) / 65535.0
				finalAlpha := uint8(origAlpha * alphaMult * 255.0)
				dst.SetRGBA(x, y, color.RGBA{
					R: uint8((r * 255) / 65535),
					G: uint8((g * 255) / 65535),
					B: uint8((b * 255) / 65535),
					A: finalAlpha,
				})
			}
		}
	}

	var buf bytes.Buffer
	if err := png.Encode(&buf, dst); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// getAvatarIconPath downloads and caches user/group avatar as a circle for notification icon
func (a *App) getAvatarIconPath(avatarURL string) string {
	if avatarURL == "" {
		return "penik"
	}
	serverURL := a.GetServerURL()
	fullURL := avatarURL
	if strings.HasPrefix(avatarURL, "/") {
		fullURL = strings.TrimRight(serverURL, "/") + avatarURL
	}
	if !strings.HasPrefix(fullURL, "http://") && !strings.HasPrefix(fullURL, "https://") {
		return "penik"
	}

	cacheDir, err := os.UserCacheDir()
	if err != nil {
		cacheDir = os.TempDir()
	}
	avatarDir := filepath.Join(cacheDir, "penik", "avatars")
	_ = os.MkdirAll(avatarDir, 0755)

	hash := fmt.Sprintf("%x", md5.Sum([]byte(fullURL)))
	localPath := filepath.Join(avatarDir, hash+".png")

	if fi, err := os.Stat(localPath); err == nil && time.Since(fi.ModTime()) < 1*time.Hour && fi.Size() > 0 {
		return localPath
	}

	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get(fullURL)
	if err != nil || resp.StatusCode != http.StatusOK {
		if resp != nil {
			_ = resp.Body.Close()
		}
		if _, err := os.Stat(localPath); err == nil {
			return localPath
		}
		return "penik"
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil || len(data) == 0 {
		return "penik"
	}

	// Crop downloaded avatar into a smooth circular PNG with transparent background
	if circleData, err := cropCircle(data); err == nil && len(circleData) > 0 {
		_ = os.WriteFile(localPath, circleData, 0644)
	} else {
		_ = os.WriteFile(localPath, data, 0644)
	}
	return localPath
}

// Notify triggers a native desktop notification with optional tag and avatar
func (a *App) Notify(title string, body string, tag string, avatarUrl string) error {
	if title == "" {
		title = "Penik"
	}

	switch runtime.GOOS {
	case "linux":
		go func() {
			iconPath := a.getAvatarIconPath(avatarUrl)
			cmd := exec.Command("notify-send", "--action=default=Открыть", "-a", "Penik", "-i", iconPath, title, body)
			out, err := cmd.Output()
			if err != nil {
				// Fallback without --action if flag is not supported on legacy libnotify
				_ = exec.Command("notify-send", "-a", "Penik", "-i", iconPath, title, body).Run()
				return
			}
			if strings.TrimSpace(string(out)) == "default" {
				a.Show()
				if tag != "" && a.ctx != nil {
					wruntime.EventsEmit(a.ctx, "desktop:notification_clicked", tag)
				}
			}
		}()
		return nil
	case "windows":
		go func() {
			iconPath := a.getAvatarIconPath(avatarUrl)
			var psScript string
			if iconPath != "penik" {
				absIconPath, _ := filepath.Abs(iconPath)
				fileUri := "file:///" + filepath.ToSlash(absIconPath)
				psScript = fmt.Sprintf(
					`[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null; `+
						`$template = [Windows.UI.Notifications.ToastTemplateType]::ToastImageAndText02; `+
						`$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent($template); `+
						`$textNodes = $xml.GetElementsByTagName('text'); `+
						`$textNodes.Item(0).AppendChild($xml.CreateTextNode('%s')) > $null; `+
						`$textNodes.Item(1).AppendChild($xml.CreateTextNode('%s')) > $null; `+
						`$imageNodes = $xml.GetElementsByTagName('image'); `+
						`$imageNodes.Item(0).Attributes.GetNamedItem('src').NodeValue = '%s'; `+
						`$notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('Penik'); `+
						`$notification = [Windows.UI.Notifications.ToastNotification]::new($xml); `+
						`$notifier.Show($notification);`,
					strings.ReplaceAll(title, "'", "''"),
					strings.ReplaceAll(body, "'", "''"),
					strings.ReplaceAll(fileUri, "'", "''"),
				)
			} else {
				psScript = fmt.Sprintf(
					`[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null; `+
						`$template = [Windows.UI.Notifications.ToastTemplateType]::ToastText02; `+
						`$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent($template); `+
						`$textNodes = $xml.GetElementsByTagName('text'); `+
						`$textNodes.Item(0).AppendChild($xml.CreateTextNode('%s')) > $null; `+
						`$textNodes.Item(1).AppendChild($xml.CreateTextNode('%s')) > $null; `+
						`$notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('Penik'); `+
						`$notification = [Windows.UI.Notifications.ToastNotification]::new($xml); `+
						`$notifier.Show($notification);`,
					strings.ReplaceAll(title, "'", "''"),
					strings.ReplaceAll(body, "'", "''"),
				)
			}
			cmd := exec.Command("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript)
			_ = cmd.Run()
		}()
		return nil
	default:
		return nil
	}
}

// OpenFileDialog prompts user to choose a file and returns its path
func (a *App) OpenFileDialog(title string, filters []FileFilter) (string, error) {
	var dialogFilters []wruntime.FileFilter
	for _, f := range filters {
		dialogFilters = append(dialogFilters, wruntime.FileFilter{
			DisplayName: f.DisplayName,
			Pattern:     f.Pattern,
		})
	}

	opts := wruntime.OpenDialogOptions{
		Title:   title,
		Filters: dialogFilters,
	}

	return wruntime.OpenFileDialog(a.ctx, opts)
}

// SaveFileDialog prompts user for a destination file path
func (a *App) SaveFileDialog(title string, defaultFilename string, filters []FileFilter) (string, error) {
	var dialogFilters []wruntime.FileFilter
	for _, f := range filters {
		dialogFilters = append(dialogFilters, wruntime.FileFilter{
			DisplayName: f.DisplayName,
			Pattern:     f.Pattern,
		})
	}

	opts := wruntime.SaveDialogOptions{
		Title:           title,
		DefaultFilename: defaultFilename,
		Filters:         dialogFilters,
	}

	return wruntime.SaveFileDialog(a.ctx, opts)
}

// SaveFile writes base64 encoded data to target file path
func (a *App) SaveFile(filePath string, base64Data string) error {
	data, err := base64.StdEncoding.DecodeString(base64Data)
	if err != nil {
		return fmt.Errorf("failed to decode base64: %w", err)
	}
	return os.WriteFile(filePath, data, 0644)
}

// ReadFile reads target file and returns contents as base64
func (a *App) ReadFile(filePath string) (string, error) {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(data), nil
}

// Show restores and focuses the main application window
func (a *App) Show() {
	wruntime.WindowShow(a.ctx)
	wruntime.WindowUnminimise(a.ctx)
}

// Hide hides the main application window to tray
func (a *App) Hide() {
	wruntime.WindowHide(a.ctx)
}

// ToggleWindow toggles main window visibility
func (a *App) ToggleWindow() {
	if wruntime.WindowIsMinimised(a.ctx) {
		a.Show()
	} else {
		a.Hide()
	}
}

// Quit terminates the application bypassing minimize-to-tray
func (a *App) Quit() {
	a.quitting = true
	systray.Quit()
	wruntime.Quit(a.ctx)
}

// HttpRequest performs a native HTTP request from Go, bypassing browser CORS
func (a *App) HttpRequest(method string, urlStr string, headers map[string]string, body string) (*HttpResponse, error) {
	var bodyReader io.Reader
	if body != "" {
		bodyReader = strings.NewReader(body)
	}

	req, err := http.NewRequestWithContext(a.ctx, method, urlStr, bodyReader)
	if err != nil {
		return nil, err
	}

	for k, v := range headers {
		req.Header.Set(k, v)
	}

	client := &http.Client{
		Timeout: 30 * time.Second,
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	respHeaders := make(map[string]string)
	for k, v := range resp.Header {
		if len(v) > 0 {
			respHeaders[strings.ToLower(k)] = v[0]
		}
	}

	return &HttpResponse{
		Status:     resp.StatusCode,
		StatusText: resp.Status,
		Headers:    respHeaders,
		Body:       string(respBody),
	}, nil
}

// HttpBinaryRequest performs a native HTTP request from Go and returns the body as base64
func (a *App) HttpBinaryRequest(method string, urlStr string, headers map[string]string, body string) (*HttpBinaryResponse, error) {
	var bodyReader io.Reader
	if body != "" {
		bodyReader = strings.NewReader(body)
	}

	req, err := http.NewRequestWithContext(a.ctx, method, urlStr, bodyReader)
	if err != nil {
		return nil, err
	}

	for k, v := range headers {
		req.Header.Set(k, v)
	}

	client := &http.Client{
		Timeout: 60 * time.Second,
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	respHeaders := make(map[string]string)
	for k, v := range resp.Header {
		if len(v) > 0 {
			respHeaders[strings.ToLower(k)] = v[0]
		}
	}

	return &HttpBinaryResponse{
		Status:     resp.StatusCode,
		StatusText: resp.Status,
		Headers:    respHeaders,
		BodyBase64: base64.StdEncoding.EncodeToString(respBody),
	}, nil
}

