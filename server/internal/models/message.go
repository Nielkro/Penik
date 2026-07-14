package models

// Message is a stored encrypted message.
type Message struct {
	ID                int64
	ChatID            int64
	SenderDeviceID    int64
	RecipientDeviceID int64
	Ciphertext        []byte
	Timestamp         int64
	Delivered         bool
}
