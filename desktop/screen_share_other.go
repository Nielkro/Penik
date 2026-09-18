//go:build !windows

package main

import "context"

// startScreenShareNotificationSuppressor is a no-op on non-Windows platforms.
func startScreenShareNotificationSuppressor(ctx context.Context) {
	// No-op
}
