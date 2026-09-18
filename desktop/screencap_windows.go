//go:build windows

package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"fmt"
	"image"
	"image/jpeg"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

var (
	user32  = syscall.NewLazyDLL("user32.dll")
	gdi32   = syscall.NewLazyDLL("gdi32.dll")
	dwmapi  = syscall.NewLazyDLL("dwmapi.dll")

	procGetDC                 = user32.NewProc("GetDC")
	procReleaseDC             = user32.NewProc("ReleaseDC")
	procGetWindowRect         = user32.NewProc("GetWindowRect")
	procGetSystemMetrics      = user32.NewProc("GetSystemMetrics")
	procIsIconic              = user32.NewProc("IsIconic")
	procIsWindowVisible       = user32.NewProc("IsWindowVisible")
	procEnumWindows           = user32.NewProc("EnumWindows")
	procGetWindowTextW         = user32.NewProc("GetWindowTextW")
	procGetWindowTextLengthW   = user32.NewProc("GetWindowTextLengthW")
	procGetClassNameW          = user32.NewProc("GetClassNameW")
	procPrintWindow           = user32.NewProc("PrintWindow")
	procEnumDisplayMonitors   = user32.NewProc("EnumDisplayMonitors")
	procGetMonitorInfoW       = user32.NewProc("GetMonitorInfoW")

	procCreateCompatibleDC     = gdi32.NewProc("CreateCompatibleDC")
	procDeleteDC               = gdi32.NewProc("DeleteDC")
	procCreateCompatibleBitmap = gdi32.NewProc("CreateCompatibleBitmap")
	procDeleteObject           = gdi32.NewProc("DeleteObject")
	procSelectObject           = gdi32.NewProc("SelectObject")
	procBitBlt                 = gdi32.NewProc("BitBlt")
	procGetDIBits              = gdi32.NewProc("GetDIBits")

	procDwmGetWindowAttribute  = dwmapi.NewProc("DwmGetWindowAttribute")
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

const (
	smCxScreen         = 0
	smCyScreen         = 1
	smCxVirtualScreen  = 78
	smCyVirtualScreen  = 79
	smXVirtualScreen   = 76
	smYVirtualScreen   = 77
	srccopy            = 0x00CC0020
	captureblt         = 0x40000000
	biRgb              = 0
	dibRgbColors       = 0
	dwmwaCloaked       = 14
	pwRenderFullContent = 0x00000002
)

type rect struct {
	Left   int32
	Top    int32
	Right  int32
	Bottom int32
}

type monitorInfo struct {
	CbSize    uint32
	RcMonitor rect
	RcWork    rect
	DwFlags   uint32
}

type bitmapInfoHeader struct {
	BiSize          uint32
	BiWidth         int32
	BiHeight        int32
	BiPlanes        uint16
	BiBitCount      uint16
	BiCompression   uint32
	BiSizeImage     uint32
	BiXPelsPerMeter int32
	BiYPelsPerMeter int32
	BiClrUsed       uint32
	BiClrImportant  uint32
}

type bitmapInfo struct {
	BmiHeader bitmapInfoHeader
	BmiColors [3]uint32
}

func getPlatformCaptureSources() ([]CaptureSource, error) {
	var sources []CaptureSource

	// 1. Enumerate Screens (Monitors)
	type monData struct {
		rc rect
	}
	var monitors []monData

	enumMonCb := syscall.NewCallback(func(hMonitor uintptr, hdcMonitor uintptr, lprcMonitor uintptr, dwData uintptr) uintptr {
		var mi monitorInfo
		mi.CbSize = uint32(unsafe.Sizeof(mi))
		procGetMonitorInfoW.Call(hMonitor, uintptr(unsafe.Pointer(&mi)))
		monitors = append(monitors, monData{rc: mi.RcMonitor})
		return 1
	})
	procEnumDisplayMonitors.Call(0, 0, enumMonCb, 0)

	if len(monitors) == 0 {
		// Fallback to primary screen
		w, _, _ := procGetSystemMetrics.Call(uintptr(smCxScreen))
		h, _, _ := procGetSystemMetrics.Call(uintptr(smCyScreen))
		monitors = append(monitors, monData{rc: rect{0, 0, int32(w), int32(h)}})
	}

	for i, m := range monitors {
		w := int(m.rc.Right - m.rc.Left)
		h := int(m.rc.Bottom - m.rc.Top)
		if w <= 0 || h <= 0 {
			continue
		}
		sources = append(sources, CaptureSource{
			ID:     fmt.Sprintf("screen:%d", i),
			Name:   fmt.Sprintf("Экран %d (%dx%d)", i+1, w, h),
			Type:   "screen",
			Width:  w,
			Height: h,
		})
	}

	// 2. Enumerate Windows
	enumWinCb := syscall.NewCallback(func(hwnd uintptr, lParam uintptr) uintptr {
		vis, _, _ := procIsWindowVisible.Call(hwnd)
		if vis == 0 {
			return 1
		}
		iconic, _, _ := procIsIconic.Call(hwnd)
		if iconic != 0 {
			return 1
		}

		// Check if window is cloaked (e.g. Windows Store hidden apps)
		var cloaked uint32
		procDwmGetWindowAttribute.Call(hwnd, uintptr(dwmwaCloaked), uintptr(unsafe.Pointer(&cloaked)), uintptr(unsafe.Sizeof(cloaked)))
		if cloaked != 0 {
			return 1
		}

		title := getWindowText(hwnd)
		if strings.TrimSpace(title) == "" {
			return 1
		}

		className := getWindowClassName(hwnd)
		// Skip shell, taskbar, tooltips, background worker windows
		if className == "Progman" || className == "WorkerW" || className == "Shell_TrayWnd" || strings.Contains(className, "Tooltips") {
			return 1
		}

		var r rect
		procGetWindowRect.Call(hwnd, uintptr(unsafe.Pointer(&r)))
		w := int(r.Right - r.Left)
		h := int(r.Bottom - r.Top)
		if w < 100 || h < 100 {
			return 1
		}

		sources = append(sources, CaptureSource{
			ID:     fmt.Sprintf("window:%d", hwnd),
			Name:   title,
			Type:   "window",
			Width:  w,
			Height: h,
		})
		return 1
	})
	procEnumWindows.Call(enumWinCb, 0)

	// Generate low-res preview thumbnails only for screens (fast, 1-2 displays)
	for i := range sources {
		if sources[i].Type == "screen" {
			if thumb, err := getPlatformSourceThumbnail(sources[i].ID); err == nil && thumb != "" {
				sources[i].Thumbnail = thumb
			}
		}
	}

	return sources, nil
}

func getPlatformSourceThumbnail(sourceID string) (string, error) {
	img, err := captureRawImage(sourceID)
	if err != nil || img == nil {
		return "", err
	}

	// Downsample thumbnail to ~320px width
	thumb := downscaleImage(img, 320)
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, thumb, &jpeg.Options{Quality: 50}); err != nil {
		return "", err
	}
	return "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(buf.Bytes()), nil
}

func downscaleImage(src *image.RGBA, targetWidth int) image.Image {
	bounds := src.Bounds()
	w := bounds.Dx()
	h := bounds.Dy()
	if w <= targetWidth || w == 0 || h == 0 {
		return src
	}
	targetHeight := (h * targetWidth) / w
	dst := image.NewRGBA(image.Rect(0, 0, targetWidth, targetHeight))

	for y := 0; y < targetHeight; y++ {
		srcY := (y * h) / targetHeight
		for x := 0; x < targetWidth; x++ {
			srcX := (x * w) / targetWidth
			dst.SetRGBA(x, y, src.RGBAAt(srcX, srcY))
		}
	}
	return dst
}

func captureRawImage(sourceID string) (*image.RGBA, error) {
	if strings.HasPrefix(sourceID, "screen:") {
		idxStr := strings.TrimPrefix(sourceID, "screen:")
		idx, _ := strconv.Atoi(idxStr)
		return captureScreenIndex(idx)
	} else if strings.HasPrefix(sourceID, "window:") {
		hwndStr := strings.TrimPrefix(sourceID, "window:")
		hwndVal, _ := strconv.ParseUint(hwndStr, 10, 64)
		return captureWindowHWND(uintptr(hwndVal))
	}
	return captureScreenIndex(0)
}

func captureScreenIndex(screenIndex int) (*image.RGBA, error) {
	type monData struct {
		rc rect
	}
	var monitors []monData
	enumMonCb := syscall.NewCallback(func(hMonitor uintptr, hdcMonitor uintptr, lprcMonitor uintptr, dwData uintptr) uintptr {
		var mi monitorInfo
		mi.CbSize = uint32(unsafe.Sizeof(mi))
		procGetMonitorInfoW.Call(hMonitor, uintptr(unsafe.Pointer(&mi)))
		monitors = append(monitors, monData{rc: mi.RcMonitor})
		return 1
	})
	procEnumDisplayMonitors.Call(0, 0, enumMonCb, 0)

	var targetRect rect
	if screenIndex >= 0 && screenIndex < len(monitors) {
		targetRect = monitors[screenIndex].rc
	} else {
		w, _, _ := procGetSystemMetrics.Call(uintptr(smCxScreen))
		h, _, _ := procGetSystemMetrics.Call(uintptr(smCyScreen))
		targetRect = rect{0, 0, int32(w), int32(h)}
	}

	w := int(targetRect.Right - targetRect.Left)
	h := int(targetRect.Bottom - targetRect.Top)
	if w <= 0 || h <= 0 {
		return nil, fmt.Errorf("invalid screen bounds")
	}

	hdcScreen, _, _ := procGetDC.Call(0)
	if hdcScreen == 0 {
		return nil, fmt.Errorf("failed to get screen DC")
	}
	defer procReleaseDC.Call(0, hdcScreen)

	hdcMem, _, _ := procCreateCompatibleDC.Call(hdcScreen)
	if hdcMem == 0 {
		return nil, fmt.Errorf("failed to create memory DC")
	}
	defer procDeleteDC.Call(hdcMem)

	hBmp, _, _ := procCreateCompatibleBitmap.Call(hdcScreen, uintptr(w), uintptr(h))
	if hBmp == 0 {
		return nil, fmt.Errorf("failed to create bitmap")
	}
	defer procDeleteObject.Call(hBmp)

	oldObj, _, _ := procSelectObject.Call(hdcMem, hBmp)
	defer procSelectObject.Call(hdcMem, oldObj)

	procBitBlt.Call(
		hdcMem,
		0,
		0,
		uintptr(w),
		uintptr(h),
		hdcScreen,
		uintptr(targetRect.Left),
		uintptr(targetRect.Top),
		uintptr(srccopy|captureblt),
	)

	return bitmapToRGBA(hdcMem, hBmp, w, h)
}

func captureWindowHWND(hwnd uintptr) (*image.RGBA, error) {
	var r rect
	procGetWindowRect.Call(hwnd, uintptr(unsafe.Pointer(&r)))
	w := int(r.Right - r.Left)
	h := int(r.Bottom - r.Top)
	if w <= 0 || h <= 0 {
		return nil, fmt.Errorf("invalid window bounds")
	}

	hdcWin, _, _ := procGetDC.Call(hwnd)
	if hdcWin == 0 {
		// Try desktop DC with window rect fallback
		return captureScreenRect(r)
	}
	defer procReleaseDC.Call(hwnd, hdcWin)

	hdcMem, _, _ := procCreateCompatibleDC.Call(hdcWin)
	if hdcMem == 0 {
		return nil, fmt.Errorf("failed to create memory DC")
	}
	defer procDeleteDC.Call(hdcMem)

	hBmp, _, _ := procCreateCompatibleBitmap.Call(hdcWin, uintptr(w), uintptr(h))
	if hBmp == 0 {
		return nil, fmt.Errorf("failed to create bitmap")
	}
	defer procDeleteObject.Call(hBmp)

	oldObj, _, _ := procSelectObject.Call(hdcMem, hBmp)
	defer procSelectObject.Call(hdcMem, oldObj)

	// Try PrintWindow first for hardware-accelerated/obscured window contents
	ret, _, _ := procPrintWindow.Call(hwnd, hdcMem, uintptr(pwRenderFullContent))
	if ret == 0 {
		// Fallback to BitBlt from window DC
		procBitBlt.Call(hdcMem, 0, 0, uintptr(w), uintptr(h), hdcWin, 0, 0, uintptr(srccopy))
	}

	return bitmapToRGBA(hdcMem, hBmp, w, h)
}

func captureScreenRect(r rect) (*image.RGBA, error) {
	w := int(r.Right - r.Left)
	h := int(r.Bottom - r.Top)
	hdcScreen, _, _ := procGetDC.Call(0)
	if hdcScreen == 0 {
		return nil, fmt.Errorf("failed to get screen DC")
	}
	defer procReleaseDC.Call(0, hdcScreen)

	hdcMem, _, _ := procCreateCompatibleDC.Call(hdcScreen)
	defer procDeleteDC.Call(hdcMem)

	hBmp, _, _ := procCreateCompatibleBitmap.Call(hdcScreen, uintptr(w), uintptr(h))
	defer procDeleteObject.Call(hBmp)

	oldObj, _, _ := procSelectObject.Call(hdcMem, hBmp)
	defer procSelectObject.Call(hdcMem, oldObj)

	procBitBlt.Call(hdcMem, 0, 0, uintptr(w), uintptr(h), hdcScreen, uintptr(r.Left), uintptr(r.Top), uintptr(srccopy|captureblt))
	return bitmapToRGBA(hdcMem, hBmp, w, h)
}

func bitmapToRGBA(hdc uintptr, hBmp uintptr, w, h int) (*image.RGBA, error) {
	var bi bitmapInfo
	bi.BmiHeader.BiSize = uint32(unsafe.Sizeof(bi.BmiHeader))
	bi.BmiHeader.BiWidth = int32(w)
	bi.BmiHeader.BiHeight = -int32(h) // Top-down
	bi.BmiHeader.BiPlanes = 1
	bi.BmiHeader.BiBitCount = 32
	bi.BmiHeader.BiCompression = biRgb

	img := image.NewRGBA(image.Rect(0, 0, w, h))

	ret, _, _ := procGetDIBits.Call(
		hdc,
		hBmp,
		0,
		uintptr(h),
		uintptr(unsafe.Pointer(&img.Pix[0])),
		uintptr(unsafe.Pointer(&bi)),
		uintptr(dibRgbColors),
	)
	if ret == 0 {
		return nil, fmt.Errorf("failed to get DIBits")
	}

	// Swap BGRA to RGBA in-place
	for i := 0; i < len(img.Pix); i += 4 {
		img.Pix[i], img.Pix[i+2] = img.Pix[i+2], img.Pix[i]
		img.Pix[i+3] = 255 // Ensure fully opaque
	}

	return img, nil
}

func startPlatformCapture(ctx context.Context, sourceID string, onFrame func([]byte)) {
	ticker := time.NewTicker(33 * time.Millisecond) // ~30 FPS
	defer ticker.Stop()

	var buf bytes.Buffer
	jpegOpts := &jpeg.Options{Quality: 82}

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			img, err := captureRawImage(sourceID)
			if err != nil || img == nil {
				continue
			}

			buf.Reset()
			if err := jpeg.Encode(&buf, img, jpegOpts); err != nil {
				continue
			}

			onFrame(buf.Bytes())
		}
	}
}
