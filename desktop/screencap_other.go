//go:build !windows

package main

import (
	"context"
)

func getPlatformCaptureSources() ([]CaptureSource, error) {
	// Fallback single display for non-Windows
	return []CaptureSource{
		{
			ID:     "screen:0",
			Name:   "Основной экран",
			Type:   "screen",
			Width:  1920,
			Height: 1080,
		},
	}, nil
}

func startPlatformCapture(ctx context.Context, sourceID string, onFrame func([]byte)) {
	// No-op or standard fallback
	<-ctx.Done()
}
