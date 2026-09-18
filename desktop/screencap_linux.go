//go:build linux

package main

/*
#cgo pkg-config: x11

#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <stdlib.h>
#include <string.h>

static int x11_silent_error_handler(Display *d, XErrorEvent *e) {
    (void)d;
    (void)e;
    return 0;
}

static void init_x11_error_handling() {
    XSetErrorHandler(x11_silent_error_handler);
}

typedef struct {
    Display *display;
    Window root;
    int screen;
    int width;
    int height;
} X11Context;

static X11Context* init_x11() {
    init_x11_error_handling();
    Display *d = XOpenDisplay(NULL);
    if (!d) return NULL;
    X11Context *ctx = (X11Context*)calloc(1, sizeof(X11Context));
    ctx->display = d;
    ctx->screen = DefaultScreen(d);
    ctx->root = RootWindow(d, ctx->screen);
    ctx->width = DisplayWidth(d, ctx->screen);
    ctx->height = DisplayHeight(d, ctx->screen);
    return ctx;
}

static void close_x11(X11Context *ctx) {
    if (ctx) {
        if (ctx->display) XCloseDisplay(ctx->display);
        free(ctx);
    }
}

// Captures a region from X11 window into allocated RGBA buffer.
// Returns 1 on success, 0 on error.
static int capture_x11_window(X11Context *ctx, Window win, int x, int y, int width, int height, unsigned char **out_rgba) {
    if (!ctx || !ctx->display || width <= 0 || height <= 0) return 0;

    init_x11_error_handling();
    XImage *image = XGetImage(ctx->display, win, x, y, width, height, AllPlanes, ZPixmap);
    if (!image) return 0;

    unsigned char *rgba = (unsigned char*)malloc(width * height * 4);
    if (!rgba) {
        XDestroyImage(image);
        return 0;
    }

    unsigned long r_mask = image->red_mask;
    unsigned long g_mask = image->green_mask;
    unsigned long b_mask = image->blue_mask;

    int idx = 0;
    for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
            unsigned long pixel = XGetPixel(image, col, row);
            rgba[idx]     = (unsigned char)((pixel & r_mask) >> 16);
            rgba[idx + 1] = (unsigned char)((pixel & g_mask) >> 8);
            rgba[idx + 2] = (unsigned char)(pixel & b_mask);
            rgba[idx + 3] = 255;
            idx += 4;
        }
    }

    XDestroyImage(image);
    *out_rgba = rgba;
    return 1;
}

static char* get_window_title(Display *d, Window win) {
    char *name = NULL;
    Atom net_wm_name = XInternAtom(d, "_NET_WM_NAME", False);
    Atom utf8_string = XInternAtom(d, "UTF8_STRING", False);
    Atom actual_type;
    int actual_format;
    unsigned long nitems, bytes_after;
    unsigned char *prop = NULL;

    if (XGetWindowProperty(d, win, net_wm_name, 0, 1024, False, utf8_string,
                           &actual_type, &actual_format, &nitems, &bytes_after, &prop) == Success && prop) {
        name = strdup((char*)prop);
        XFree(prop);
        return name;
    }

    if (XFetchName(d, win, &name) > 0 && name) {
        char *res = strdup(name);
        XFree(name);
        return res;
    }
    return NULL;
}
*/
import "C"
import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"log"
	"os"
	"os/exec"
	"reflect"
	"strconv"
	"strings"
	"time"
	"unsafe"

	"github.com/godbus/dbus/v5"
)

func isWaylandSession() bool {
	xdgType := os.Getenv("XDG_SESSION_TYPE")
	if strings.ToLower(xdgType) == "wayland" {
		return true
	}
	waylandDisplay := os.Getenv("WAYLAND_DISPLAY")
	return waylandDisplay != ""
}

func getPlatformCaptureSources() ([]CaptureSource, error) {
	// 1. If Wayland session, use Wayland portal as the primary and only safe source.
	if isWaylandSession() {
		return []CaptureSource{
			{
				ID:        "wayland:portal",
				Name:      "Экран или окно (Wayland)",
				Type:      "screen",
				Width:     1920,
				Height:    1080,
				Thumbnail: getSimpleIconBase64(),
			},
		}, nil
	}

	// 2. Pure X11 Display enumeration
	sources := make([]CaptureSource, 0)
	ctx := C.init_x11()
	if ctx == nil {
		return sources, nil
	}
	defer C.close_x11(ctx)

	w := int(ctx.width)
	h := int(ctx.height)

	// Main screen
	thumb := captureX11Thumbnail(ctx, ctx.root, 0, 0, w, h)
	sources = append(sources, CaptureSource{
		ID:        "screen:0",
		Name:      fmt.Sprintf("Экран (%d×%d)", w, h),
		Type:      "screen",
		Width:     w,
		Height:    h,
		Thumbnail: thumb,
	})

	// Windows enumeration
	var rootRet, parentRet C.Window
	var children *C.Window
	var nChildren C.uint

	if C.XQueryTree(ctx.display, ctx.root, &rootRet, &parentRet, &children, &nChildren) != 0 && children != nil {
		defer C.XFree(unsafe.Pointer(children))

		cSlice := unsafe.Slice(children, int(nChildren))
		for i := len(cSlice) - 1; i >= 0; i-- {
			win := cSlice[i]
			var attrs C.XWindowAttributes
			if C.XGetWindowAttributes(ctx.display, win, &attrs) == 0 {
				continue
			}

			if attrs.map_state != C.IsViewable || attrs.width < 100 || attrs.height < 100 {
				continue
			}

			cTitle := C.get_window_title(ctx.display, win)
			if cTitle == nil {
				continue
			}
			title := C.GoString(cTitle)
			C.free(unsafe.Pointer(cTitle))

			if title == "" || title == "Desktop" || title == "Desktop Window" {
				continue
			}

			winW := int(attrs.width)
			winH := int(attrs.height)
			winThumb := captureX11Thumbnail(ctx, win, 0, 0, winW, winH)

			sources = append(sources, CaptureSource{
				ID:        fmt.Sprintf("window:%d", uint64(win)),
				Name:      title,
				Type:      "window",
				Width:     winW,
				Height:    winH,
				Thumbnail: winThumb,
			})
		}
	}

	return sources, nil
}

func captureX11Thumbnail(ctx *C.X11Context, win C.Window, x, y, width, height int) string {
	if width <= 0 || height <= 0 {
		return ""
	}
	var rgbaPtr *C.uchar
	if C.capture_x11_window(ctx, win, C.int(x), C.int(y), C.int(width), C.int(height), &rgbaPtr) == 0 || rgbaPtr == nil {
		return ""
	}
	defer C.free(unsafe.Pointer(rgbaPtr))

	rgbaSlice := unsafe.Slice((*byte)(rgbaPtr), width*height*4)
	img := &image.RGBA{
		Pix:    rgbaSlice,
		Stride: width * 4,
		Rect:   image.Rect(0, 0, width, height),
	}

	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 50}); err != nil {
		return ""
	}
	return "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(buf.Bytes())
}

func getPlatformSourceThumbnail(sourceID string) (string, error) {
	if isWaylandSession() || strings.HasPrefix(sourceID, "wayland:") {
		return getSimpleIconBase64(), nil
	}

	ctx := C.init_x11()
	if ctx == nil {
		return getSimpleIconBase64(), nil
	}
	defer C.close_x11(ctx)

	if strings.HasPrefix(sourceID, "screen:") {
		return captureX11Thumbnail(ctx, ctx.root, 0, 0, int(ctx.width), int(ctx.height)), nil
	}

	if strings.HasPrefix(sourceID, "window:") {
		winIDStr := strings.TrimPrefix(sourceID, "window:")
		winID, _ := strconv.ParseUint(winIDStr, 10, 64)
		if winID > 0 {
			var attrs C.XWindowAttributes
			if C.XGetWindowAttributes(ctx.display, C.Window(winID), &attrs) != 0 {
				return captureX11Thumbnail(ctx, C.Window(winID), 0, 0, int(attrs.width), int(attrs.height)), nil
			}
		}
	}

	return getSimpleIconBase64(), nil
}

func startPlatformCapture(ctx context.Context, sourceID string, onFrame func([]byte)) {
	if isWaylandSession() || strings.HasPrefix(sourceID, "wayland:") {
		if startWaylandPortalCapture(ctx, onFrame) {
			return
		}
	}

	// Fallback to X11 direct loop
	startX11Capture(ctx, sourceID, onFrame)
}

func randomToken(prefix string) string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return prefix + hex.EncodeToString(b)
}

func extractNodeID(val interface{}) uint32 {
	if val == nil {
		return 0
	}
	v := reflect.ValueOf(val)
	if v.Kind() == reflect.Slice && v.Len() > 0 {
		firstElem := v.Index(0)
		if firstElem.Kind() == reflect.Slice && firstElem.Len() > 0 {
			elem0 := firstElem.Index(0)
			if elem0.Kind() == reflect.Interface {
				elem0 = elem0.Elem()
			}
			if id, ok := elem0.Interface().(uint32); ok {
				return id
			}
		}
		if firstElem.Kind() == reflect.Struct && firstElem.NumField() > 0 {
			if id, ok := firstElem.Field(0).Interface().(uint32); ok {
				return id
			}
		}
	}
	return 0
}

func startWaylandPortalCapture(ctx context.Context, onFrame func([]byte)) bool {
	gstPath, err := exec.LookPath("gst-launch-1.0")
	if err != nil {
		log.Printf("[screencap] gst-launch-1.0 not found: %v", err)
		return false
	}

	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		log.Printf("[screencap] DBus session bus connect error: %v", err)
		return false
	}
	defer conn.Close()

	// Register match rule so DBus daemon sends signal to our client
	matchRule := "type='signal',interface='org.freedesktop.portal.Request',member='Response'"
	call := conn.BusObject().Call("org.freedesktop.DBus.AddMatch", 0, matchRule)
	if call.Err != nil {
		log.Printf("[screencap] DBus AddMatch error: %v", call.Err)
		return false
	}

	signalChan := make(chan *dbus.Signal, 20)
	conn.Signal(signalChan)
	defer conn.RemoveSignal(signalChan)

	portalObj := conn.Object("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop")

	waitForResponse := func(reqPath dbus.ObjectPath) (uint32, map[string]dbus.Variant, error) {
		for {
			select {
			case <-ctx.Done():
				return 2, nil, ctx.Err()
			case sig, ok := <-signalChan:
				if !ok {
					return 2, nil, fmt.Errorf("signal closed")
				}
				if sig.Path == reqPath && strings.HasSuffix(sig.Name, ".Response") {
					if len(sig.Body) >= 2 {
						code, _ := sig.Body[0].(uint32)
						results, _ := sig.Body[1].(map[string]dbus.Variant)
						return code, results, nil
					}
					return 2, nil, fmt.Errorf("malformed portal response")
				}
			}
		}
	}

	// 1. CreateSession
	sessionToken := randomToken("s_")
	createReqToken := randomToken("r_")

	var createRespPath dbus.ObjectPath
	err = portalObj.CallWithContext(ctx, "org.freedesktop.portal.ScreenCast.CreateSession", 0, map[string]dbus.Variant{
		"handle_token":         dbus.MakeVariant(createReqToken),
		"session_handle_token": dbus.MakeVariant(sessionToken),
	}).Store(&createRespPath)
	if err != nil {
		log.Printf("[screencap] CreateSession call error: %v", err)
		return false
	}

	code, results, err := waitForResponse(createRespPath)
	if err != nil || code != 0 {
		log.Printf("[screencap] CreateSession failed: code=%d err=%v", code, err)
		return false
	}

	sessionHandleStr, ok := results["session_handle"].Value().(string)
	if !ok || sessionHandleStr == "" {
		log.Printf("[screencap] Invalid session handle in response: %+v", results)
		return false
	}
	sessionHandle := dbus.ObjectPath(sessionHandleStr)

	defer func() {
		sessionObj := conn.Object("org.freedesktop.portal.Desktop", sessionHandle)
		sessionObj.Call("org.freedesktop.portal.Session.Close", 0)
	}()

	// 2. SelectSources
	selectReqToken := randomToken("r_")

	var selectRespPath dbus.ObjectPath
	err = portalObj.CallWithContext(ctx, "org.freedesktop.portal.ScreenCast.SelectSources", 0, sessionHandle, map[string]dbus.Variant{
		"handle_token": dbus.MakeVariant(selectReqToken),
		"types":        dbus.MakeVariant(uint32(3)), // 1=Screen, 2=Window, 3=Both
		"multiple":     dbus.MakeVariant(false),
		"cursor_mode":  dbus.MakeVariant(uint32(2)), // Embedded cursor
	}).Store(&selectRespPath)
	if err != nil {
		log.Printf("[screencap] SelectSources call error: %v", err)
		return false
	}

	code, _, err = waitForResponse(selectRespPath)
	if err != nil || code != 0 {
		log.Printf("[screencap] SelectSources failed: code=%d err=%v", code, err)
		return false
	}

	// 3. Start
	startReqToken := randomToken("r_")

	var startRespPath dbus.ObjectPath
	err = portalObj.CallWithContext(ctx, "org.freedesktop.portal.ScreenCast.Start", 0, sessionHandle, "", map[string]dbus.Variant{
		"handle_token": dbus.MakeVariant(startReqToken),
	}).Store(&startRespPath)
	if err != nil {
		log.Printf("[screencap] Start call error: %v", err)
		return false
	}

	code, startResults, err := waitForResponse(startRespPath)
	if err != nil || code != 0 {
		log.Printf("[screencap] Start failed (user cancelled or error): code=%d err=%v", code, err)
		return false
	}

	// Extract stream node_id if provided
	var nodeID uint32
	if streamsVal, ok := startResults["streams"]; ok {
		nodeID = extractNodeID(streamsVal.Value())
	}

	// 4. OpenPipeWireRemote
	var unixFD dbus.UnixFD
	err = portalObj.CallWithContext(ctx, "org.freedesktop.portal.ScreenCast.OpenPipeWireRemote", 0, sessionHandle, map[string]dbus.Variant{}).Store(&unixFD)
	if err != nil || unixFD < 0 {
		log.Printf("[screencap] OpenPipeWireRemote error: %v fd=%d", err, unixFD)
		return false
	}
	pwFile := os.NewFile(uintptr(unixFD), "pipewire_remote")
	defer pwFile.Close()

	// 5. GStreamer pipeline with pipewiresrc
	var pipewireArgs []string
	if nodeID > 0 {
		pipewireArgs = []string{
			"-q",
			"pipewiresrc", "fd=3", fmt.Sprintf("path=%d", nodeID), "do-timestamp=true",
			"!", "videoconvert",
			"!", "jpegenc", "quality=70",
			"!", "fdsink", "fd=1",
		}
	} else {
		pipewireArgs = []string{
			"-q",
			"pipewiresrc", "fd=3", "do-timestamp=true",
			"!", "videoconvert",
			"!", "jpegenc", "quality=70",
			"!", "fdsink", "fd=1",
		}
	}

	cmd := exec.CommandContext(ctx, gstPath, pipewireArgs...)
	cmd.ExtraFiles = []*os.File{pwFile}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		log.Printf("[screencap] StdoutPipe error: %v", err)
		return false
	}

	if err := cmd.Start(); err != nil {
		log.Printf("[screencap] GStreamer start error: %v", err)
		return false
	}

	log.Printf("[screencap] Wayland portal screencast active (nodeID=%d, fd=%d)", nodeID, unixFD)

	header := []byte{0xFF, 0xD8}
	footer := []byte{0xFF, 0xD9}
	rawBuf := make([]byte, 65536)
	frameAcc := make([]byte, 0, 500000)

	doneChan := make(chan struct{})
	go func() {
		defer close(doneChan)
		defer cmd.Wait()
		for {
			select {
			case <-ctx.Done():
				return
			default:
				n, err := stdout.Read(rawBuf)
				if err != nil || n == 0 {
					return
				}
				frameAcc = append(frameAcc, rawBuf[:n]...)

				for {
					start := bytes.Index(frameAcc, header)
					if start == -1 {
						frameAcc = frameAcc[:0]
						break
					}
					end := bytes.Index(frameAcc[start+2:], footer)
					if end == -1 {
						if start > 0 {
							frameAcc = frameAcc[start:]
						}
						break
					}
					fullEnd := start + 2 + end + 2
					jpegBytes := frameAcc[start:fullEnd]
					onFrame(jpegBytes)
					frameAcc = frameAcc[fullEnd:]
				}
			}
		}
	}()

	select {
	case <-ctx.Done():
	case <-doneChan:
	}
	return true
}

func startX11Capture(ctx context.Context, sourceID string, onFrame func([]byte)) {
	x11Ctx := C.init_x11()
	if x11Ctx == nil {
		return
	}
	defer C.close_x11(x11Ctx)

	targetWin := x11Ctx.root
	var targetW = int(x11Ctx.width)
	var targetH = int(x11Ctx.height)

	if strings.HasPrefix(sourceID, "window:") {
		winIDStr := strings.TrimPrefix(sourceID, "window:")
		winID, err := strconv.ParseUint(winIDStr, 10, 64)
		if err == nil && winID > 0 {
			targetWin = C.Window(winID)
		}
	}

	ticker := time.NewTicker(33 * time.Millisecond) // ~30 FPS
	defer ticker.Stop()

	var buf bytes.Buffer

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			var attrs C.XWindowAttributes
			if C.XGetWindowAttributes(x11Ctx.display, targetWin, &attrs) != 0 {
				targetW = int(attrs.width)
				targetH = int(attrs.height)
			}
			if targetW <= 0 || targetH <= 0 {
				continue
			}

			var rgbaPtr *C.uchar
			if C.capture_x11_window(x11Ctx, targetWin, 0, 0, C.int(targetW), C.int(targetH), &rgbaPtr) != 0 && rgbaPtr != nil {
				rgbaSlice := unsafe.Slice((*byte)(rgbaPtr), targetW*targetH*4)
				img := &image.RGBA{
					Pix:    rgbaSlice,
					Stride: targetW * 4,
					Rect:   image.Rect(0, 0, targetW, targetH),
				}

				buf.Reset()
				if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 70}); err == nil {
					onFrame(buf.Bytes())
				}
				C.free(unsafe.Pointer(rgbaPtr))
			}
		}
	}
}

func getSimpleIconBase64() string {
	img := image.NewRGBA(image.Rect(0, 0, 120, 80))
	for y := 0; y < 80; y++ {
		for x := 0; x < 120; x++ {
			img.Set(x, y, color.RGBA{R: 35, G: 45, B: 60, A: 255})
		}
	}
	var buf bytes.Buffer
	_ = jpeg.Encode(&buf, img, &jpeg.Options{Quality: 50})
	return "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(buf.Bytes())
}
