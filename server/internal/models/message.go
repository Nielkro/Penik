package models

// Message is one canonical user-to-user message.
type Message struct {
	ID              int64
	ChatID          int64
	SenderUserID    int64
	RecipientUserID int64
	Plaintext       string
	Timestamp       int64
	Delivered       bool
}
