package stickers

type StickerPack struct {
	ID             string    `json:"id"`
	Title          string    `json:"title"`
	AuthorID       int64     `json:"author_id"`
	CoverStickerID string    `json:"cover_sticker_id"`
	IsAnimated     bool      `json:"is_animated"`
	IsVideo        bool      `json:"is_video"`
	CreatedAt      int64     `json:"created_at"`
	Stickers       []Sticker `json:"stickers,omitempty"`
}

type Sticker struct {
	ID        string `json:"id"`
	PackID    string `json:"pack_id"`
	Emoji     string `json:"emoji"`
	FileName  string `json:"file_name"`
	Width     int    `json:"width"`
	Height    int    `json:"height"`
	SortOrder int    `json:"sort_order"`
	URL       string `json:"url,omitempty"`
}
