package ws

// Opcode defines the binary protocol opcode byte.
type Opcode byte

const (
	OpMsgSend             Opcode = 0x01
	OpMsgRecv             Opcode = 0x02
	OpMsgAck              Opcode = 0x03
	OpMsgDelivered        Opcode = 0x04
	OpOfflineBatch        Opcode = 0x05
	OpPing                Opcode = 0x06
	OpPong                Opcode = 0x07
	OpChatPurge           Opcode = 0x08 // server→client: wipe a chat (delete-for-everyone)
	OpChatPurgeAck        Opcode = 0x09 // client→server: purge applied, safe to hard-delete
	OpKeyFetchReq         Opcode = 0x10
	OpKeyFetchResp        Opcode = 0x11
	OpKeyPublish          Opcode = 0x12
	OpKeyBundleResp       Opcode = 0x13
	OpKeyBundleReq        Opcode = 0x14
	OpRefillPreKeys       Opcode = 0x15
	OpMsgRetryReq         Opcode = 0x16
	OpMsgRetryResp        Opcode = 0x17
	OpMsgRead             Opcode = 0x18
	OpPairingHistoryReady Opcode = 0x19
	OpPairingClaimed      Opcode = 0x1a
	OpMsgStatusBatch      Opcode = 0x1b
	OpUserAvatarUpdate    Opcode = 0x1c

	OpGroupMessageSend      Opcode = 0x20
	OpGroupMessageRecv      Opcode = 0x21
	OpGroupMessageAck       Opcode = 0x22
	OpGroupKeyAvailable     Opcode = 0x23
	OpGroupMemberChanged    Opcode = 0x24
	OpGroupMessageDelivered Opcode = 0x25
	OpGroupMessageRead      Opcode = 0x26
	OpGroupHistoryReady     Opcode = 0x27
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
	MsgID       int64  `msgpack:"msg_id"`
	ClientMsgID string `msgpack:"client_msg_id,omitempty"`
}

type MsgRead struct {
	MsgID       int64  `msgpack:"msg_id"`
	ClientMsgID string `msgpack:"client_msg_id,omitempty"`
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
}

// KeyFetchResp carries all devices' key bundles for a user.
type KeyFetchResp struct {
	Devices []DeviceKeyBundle `msgpack:"devices"`
}

type E2EPayload struct {
	DeviceID   int64  `msgpack:"device_id"`
	Ciphertext []byte `msgpack:"ciphertext"`
	Salt       []byte `msgpack:"salt"`
	Nonce      []byte `msgpack:"nonce"`
}

type MsgSendEncrypted struct {
	ToUserID int64        `msgpack:"to_user_id"`
	MsgID    string       `msgpack:"msg_id"`
	Devices  []E2EPayload `msgpack:"devices"`
}

type MsgRecvEncrypted struct {
	FromUserID        int64  `msgpack:"from_user_id"`
	FromDeviceID      int64  `msgpack:"from_device_id"`
	RecipientDeviceID int64  `msgpack:"recipient_device_id"`
	FromIdentityKey   []byte `msgpack:"from_identity_key"`
	ChatUserID        int64  `msgpack:"chat_user_id"`
	MsgID             int64  `msgpack:"msg_id"`
	Ciphertext        []byte `msgpack:"ciphertext"`
	Salt              []byte `msgpack:"salt"`
	Nonce             []byte `msgpack:"nonce"`
	TS                int64  `msgpack:"ts"`
}

type OfflineBatchEncrypted struct {
	Msgs []MsgRecvEncrypted `msgpack:"msgs"`
}

type KeyPublishReq struct {
	X25519Pub []byte              `msgpack:"x25519_pub"`
}

type KeyBundleReq struct {
	UserID int64 `msgpack:"user_id"`
}

type MsgRetryReq struct {
	SenderDeviceID    int64 `msgpack:"sender_device_id"`
	RequesterDeviceID int64 `msgpack:"requester_device_id"`
	MsgID             int64 `msgpack:"msg_id"`
}

type MsgRetryResp struct {
	MsgID      int64  `msgpack:"msg_id"`
	Ciphertext []byte `msgpack:"ciphertext"`
	Salt       []byte `msgpack:"salt"`
	Nonce      []byte `msgpack:"nonce"`
}

type PairingHistoryReady struct {
	SessionID string `msgpack:"session_id"`
}

type PairingClaimed struct {
	SessionID string `msgpack:"session_id"`
	PublicKey string `msgpack:"public_key"`
}

// GroupKeyAvailable notifies a device that a new group key version has an
// envelope waiting for it. The device fetches the envelope over REST.
type GroupKeyAvailable struct {
	GroupID    int64 `msgpack:"group_id"`
	KeyVersion int64 `msgpack:"key_version"`
}

// GroupHistoryReady notifies a device that a one-shot pre-join history packet
// is staged for it. The device fetches (and thereby deletes) it over REST.
type GroupHistoryReady struct {
	GroupID int64 `msgpack:"group_id"`
}

// GroupMemberChanged notifies active devices that a group's membership changed
// and a key rotation is expected.
type GroupMemberChanged struct {
	GroupID           int64 `msgpack:"group_id"`
	MembershipVersion int64 `msgpack:"membership_version"`
}

// GroupMessageSend is sent client→server to post an encrypted group message.
// Sender identity is taken from the authenticated connection, never the payload.
type GroupMessageSend struct {
	GroupID    int64  `msgpack:"group_id"`
	MessageID  string `msgpack:"message_id"`
	KeyVersion int64  `msgpack:"key_version"`
	Ciphertext []byte `msgpack:"ciphertext"`
	Salt       []byte `msgpack:"salt"`
	Nonce      []byte `msgpack:"nonce"`
	CreatedAt  int64  `msgpack:"created_at"`
}

// GroupMessageRecv is pushed server→client to deliver a group message.
type GroupMessageRecv struct {
	GroupID        int64  `msgpack:"group_id"`
	ID             int64  `msgpack:"id"`
	MessageID      string `msgpack:"message_id"`
	SenderUserID   int64  `msgpack:"sender_user_id"`
	SenderDeviceID int64  `msgpack:"sender_device_id"`
	KeyVersion     int64  `msgpack:"key_version"`
	Ciphertext     []byte `msgpack:"ciphertext"`
	Salt           []byte `msgpack:"salt"`
	Nonce          []byte `msgpack:"nonce"`
	CreatedAt      int64  `msgpack:"created_at"`
}

// GroupMessageAck confirms server-side persistence of a GroupMessageSend.
type GroupMessageAck struct {
	GroupID   int64  `msgpack:"group_id"`
	MessageID string `msgpack:"message_id"`
	ID        int64  `msgpack:"id"`
}

// GroupMessageDelivered / GroupMessageRead are sent client→server for receipts.
type GroupMessageDelivered struct {
	ID int64 `msgpack:"id"`
}

type GroupMessageRead struct {
	ID int64 `msgpack:"id"`
}

type MsgStatusItem struct {
	MsgID       int64  `msgpack:"msg_id"`
	ClientMsgID string `msgpack:"client_msg_id,omitempty"`
	Delivered   bool   `msgpack:"delivered"`
	DeliveredAt *int64 `msgpack:"delivered_at,omitempty"`
	Read        bool   `msgpack:"read"`
}

type MsgStatusBatch struct {
	Statuses []MsgStatusItem `msgpack:"statuses"`
}
