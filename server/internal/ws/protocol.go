package ws

// Opcode defines the binary protocol opcode byte.
type Opcode byte

const (
	OpMsgSend      Opcode = 0x01
	OpMsgRecv      Opcode = 0x02
	OpMsgAck       Opcode = 0x03
	OpMsgDelivered Opcode = 0x04
	OpOfflineBatch Opcode = 0x05
	OpPing         Opcode = 0x06
	OpPong         Opcode = 0x07
	OpChatPurge    Opcode = 0x08 // server→client: wipe a chat (delete-for-everyone)
	OpChatPurgeAck Opcode = 0x09 // client→server: purge applied, safe to hard-delete
	OpKeyFetchReq  Opcode = 0x10
	OpKeyFetchResp Opcode = 0x11
)

// Envelope is the top-level wire frame: [opcode byte][msgpack payload bytes...]
// All messages are sent as binary WebSocket frames.

type MsgSend struct {
	ToUserID  int64  `msgpack:"to_user_id"`
	Plaintext string `msgpack:"plaintext"`
	MsgID     string `msgpack:"msg_id"` // client-generated idempotency key
}

// MsgRecv is pushed to recipient clients.
type MsgRecv struct {
	FromUserID int64  `msgpack:"from_user_id"`
	ChatUserID int64  `msgpack:"chat_user_id"` // User ID of the other party in this chat
	Plaintext  string `msgpack:"plaintext"`
	MsgID      int64  `msgpack:"msg_id"` // server-assigned DB id
	TS         int64  `msgpack:"ts"`
}

// MsgAck is sent server→client to confirm receipt/storage of a MsgSend.
type MsgAck struct {
	MsgID       int64  `msgpack:"msg_id"` // server-assigned id
	ClientMsgID string `msgpack:"client_msg_id"`
}

// MsgDelivered is sent client→server when the recipient has received a message.
type MsgDelivered struct {
	MsgID int64 `msgpack:"msg_id"`
}

// OfflineBatch is pushed on connect with all undelivered messages.
type OfflineBatch struct {
	Msgs []MsgRecv `msgpack:"msgs"`
}

// ChatPurge is pushed server→client to wipe a chat locally (delete-for-everyone).
// ChatUserID identifies the other party of the chat to erase.
type ChatPurge struct {
	ChatUserID int64 `msgpack:"chat_user_id"`
}

// ChatPurgeAck is sent client→server after the chat was wiped locally,
// allowing the server to hard-delete the tombstoned rows.
type ChatPurgeAck struct {
	ChatUserID int64 `msgpack:"chat_user_id"`
}

// KeyFetchReq asks for key bundles for a user.
type KeyFetchReq struct {
	UserID   int64 `msgpack:"user_id"`
	NoOTK    bool  `msgpack:"no_otk"`
	DeviceID int64 `msgpack:"device_id"`
}

// DeviceKeyBundle is one device's key material for a KeyFetchResp.
type DeviceKeyBundle struct {
	DeviceID       int64  `msgpack:"device_id"`
	RegistrationID int64  `msgpack:"registration_id"`
	IKPub          []byte `msgpack:"ik_pub"`
	SPKPub         []byte `msgpack:"spk_pub"`
	SPKSig         []byte `msgpack:"spk_sig"`
	OPKPub         []byte `msgpack:"opk_pub"`
	OPKID          int64  `msgpack:"opk_id"`
}

// KeyFetchResp carries all devices' key bundles for a user.
type KeyFetchResp struct {
	Devices []DeviceKeyBundle `msgpack:"devices"`
}
