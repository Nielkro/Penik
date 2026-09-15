package models

// User represents a registered user.
type User struct {
	ID                int64
	Name              string
	Nickname          string
	NicknameChangedAt int64
	PasswordHash      string
	Avatar            []byte
	IsBot             bool
	CreatedAt         int64
}

// UserProfile is the public-facing subset of User.
type UserProfile struct {
	ID       int64  `json:"id"`
	Name     string `json:"name"`
	Nickname string `json:"nickname"`
	IsBot    bool   `json:"is_bot"`
}

