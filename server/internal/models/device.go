package models

// Device represents a physical device belonging to a user.
type Device struct {
	ID         int64
	UserID     int64
	DeviceName string
	CreatedAt  int64
	LastSeen   int64
}
