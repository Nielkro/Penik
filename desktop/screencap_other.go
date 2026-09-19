//go:build !windows && !linux

package main

import (
	"context"
)

func getPlatformCaptureSources() ([]CaptureSource, error) {
	return []CaptureSource{}, nil
}

func getPlatformSourceThumbnail(sourceID string) (string, error) {
	return "", nil
}

func startPlatformCapture(ctx context.Context, sourceID string, onFrame func(*CapturedFrame)) {
	<-ctx.Done()
}
