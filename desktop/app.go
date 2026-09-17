package main

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"time"

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

// Notify triggers a native desktop notification
func (a *App) Notify(title string, body string) error {
	if title == "" {
		title = "Penik"
	}

	switch runtime.GOOS {
	case "linux":
		cmd := exec.Command("notify-send", "-a", "Penik", "-i", "penik", title, body)
		return cmd.Start()
	case "windows":
		// PowerShell toast notification fallback
		psScript := fmt.Sprintf(
			`[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null; `+
				`$template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02); `+
				`$textNodes = $template.GetElementsByTagName('text'); `+
				`$textNodes.Item(0).AppendChild($template.CreateTextNode('%s')) > $null; `+
				`$textNodes.Item(1).AppendChild($template.CreateTextNode('%s')) > $null; `+
				`$notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('Penik'); `+
				`$notification = [Windows.UI.Notifications.ToastNotification]::new($template); `+
				`$notifier.Show($notification);`,
			strings.ReplaceAll(title, "'", "''"),
			strings.ReplaceAll(body, "'", "''"),
		)
		cmd := exec.Command("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript)
		return cmd.Start()
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

