//go:build windows

package main

import (
	"context"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

var (
	user32                   = syscall.NewLazyDLL("user32.dll")
	kernel32                 = syscall.NewLazyDLL("kernel32.dll")
	procEnumWindows          = user32.NewProc("EnumWindows")
	procEnumChildWindows     = user32.NewProc("EnumChildWindows")
	procGetClassNameW        = user32.NewProc("GetClassNameW")
	procGetWindowTextW       = user32.NewProc("GetWindowTextW")
	procGetWindowTextLengthW = user32.NewProc("GetWindowTextLengthW")
	procGetWindowThreadProcId= user32.NewProc("GetWindowThreadProcessId")
	procShowWindow           = user32.NewProc("ShowWindow")
	procSetWindowPos         = user32.NewProc("SetWindowPos")
	procIsWindowVisible      = user32.NewProc("IsWindowVisible")
	procGetCurrentProcessId  = kernel32.NewProc("GetCurrentProcessId")
)

const (
	swHide         = 0
	swpNoSize      = 0x0001
	swpNoMove      = 0x0002
	swpNoZOrder    = 0x0004
	swpNoActivate  = 0x0010
	swpHideWindow  = 0x0080
)

func getWindowText(hwnd uintptr) string {
	lenRes, _, _ := procGetWindowTextLengthW.Call(hwnd)
	textLen := int(lenRes)
	if textLen == 0 {
		return ""
	}
	buf := make([]uint16, textLen+1)
	procGetWindowTextW.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), uintptr(textLen+1))
	return syscall.UTF16ToString(buf)
}

func getWindowClassName(hwnd uintptr) string {
	buf := make([]uint16, 256)
	procGetClassNameW.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
	return syscall.UTF16ToString(buf)
}

// suppressScreenShareNotification scans all top-level windows and suppresses
// the Chromium/WebView2 screen capture notification bar overlay.
func suppressScreenShareNotification() {
	var currentPID uint32
	pidRes, _, _ := procGetCurrentProcessId.Call()
	currentPID = uint32(pidRes)

	cb := syscall.NewCallback(func(hwnd uintptr, lParam uintptr) uintptr {
		// Check if window is visible
		vis, _, _ := procIsWindowVisible.Call(hwnd)
		if vis == 0 {
			return 1 // Continue enumeration
		}

		className := getWindowClassName(hwnd)
		// Chromium popup windows typically use Chrome_WidgetWin_1
		if !strings.Contains(className, "Chrome_WidgetWin_") && !strings.Contains(className, "Chrome_RenderWidgetHostHWND") {
			return 1
		}

		var windowPID uint32
		procGetWindowThreadProcId.Call(hwnd, uintptr(unsafe.Pointer(&windowPID)))

		// Gather text from window and child controls
		var accumulatedTexts []string
		title := getWindowText(hwnd)
		if title != "" {
			accumulatedTexts = append(accumulatedTexts, title)
		}

		childCb := syscall.NewCallback(func(childHwnd uintptr, childLParam uintptr) uintptr {
			childText := getWindowText(childHwnd)
			if childText != "" {
				accumulatedTexts = append(accumulatedTexts, childText)
			}
			return 1
		})
		procEnumChildWindows.Call(hwnd, childCb, 0)

		fullText := strings.ToLower(strings.Join(accumulatedTexts, " "))

		// Match screen sharing indicator strings (multilingual: EN/RU)
		isScreenShareBar := strings.Contains(fullText, "wails.localhost") ||
			strings.Contains(fullText, "sharing your screen") ||
			strings.Contains(fullText, "доступ к экрану") ||
			strings.Contains(fullText, "предоставлен доступ") ||
			(strings.Contains(fullText, "stop sharing") && (windowPID == currentPID || currentPID == 0)) ||
			(strings.Contains(fullText, "прекратить доступ") && (windowPID == currentPID || currentPID == 0))

		if isScreenShareBar {
			// Immediately hide and reposition off-screen
			procShowWindow.Call(hwnd, uintptr(swHide))
			var offscreen int32 = -32000
			procSetWindowPos.Call(
				hwnd,
				0,
				uintptr(uint32(offscreen)),
				uintptr(uint32(offscreen)),
				0,
				0,
				uintptr(swpNoActivate|swpNoZOrder|swpNoSize|swpHideWindow),
			)
		}

		return 1
	})

	procEnumWindows.Call(cb, 0)
}

// startScreenShareNotificationSuppressor starts a background goroutine that
// automatically suppresses the screen share popup bar during the application lifecycle.
func startScreenShareNotificationSuppressor(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				suppressScreenShareNotification()
			}
		}
	}()
}
