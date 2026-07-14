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
	OpKeyFetchReq  Opcode = 0x10
	OpKeyFetchResp Opcode = 0x11
)

// Envelope is the top-level wire frame: [opcode byte][msgpack payload bytes...]
// All messages are sent as binary WebSocket frames.

// MsgSend is sent by a client to deliver a message to another user.
type MsgSend struct {
	ToUserID   int64  `msgpack:"to_user_id"`
	CipherBytes []byte `msgpack:"cipher_bytes"`
	MsgID      string `msgpack:"msg_id"` // client-generated idempotency key
}

// MsgRecv is pushed to recipient clients.
type MsgRecv struct {
	FromUserID   int64  `msgpack:"from_user_id"`
	FromDeviceID int64  `msgpack:"from_device_id"`
	CipherBytes  []byte `msgpack:"cipher_bytes"`
	MsgID        int64  `msgpack:"msg_id"` // server-assigned DB id
	TS           int64  `msgpack:"ts"`
}

// MsgAck is sent server→client to confirm receipt/storage of a MsgSend.
type MsgAck struct {
	MsgID int64 `msgpack:"msg_id"` // server-assigned id
}

// MsgDelivered is sent client→server when the recipient has received a message.
type MsgDelivered struct {
	MsgID int64 `msgpack:"msg_id"`
}

// OfflineBatch is pushed on connect with all undelivered messages.
type OfflineBatch struct {
	Msgs []MsgRecv `msgpack:"msgs"`
}

// KeyFetchReq asks for all key bundles for a user.
type KeyFetchReq struct {
	UserID int64 `msgpack:"user_id"`
}

// DeviceKeyBundle is one device's key material for a KeyFetchResp.
type DeviceKeyBundle struct {
	DeviceID int64  `msgpack:"device_id"`
	IKPub    []byte `msgpack:"ik_pub"`
	SPKPub   []byte `msgpack:"spk_pub"`
	SPKSig   []byte `msgpack:"spk_sig"`
	OPKPub   []byte `msgpack:"opk_pub"`
}

// KeyFetchResp carries all devices' key bundles for a user.
type KeyFetchResp struct {
	Devices []DeviceKeyBundle `msgpack:"devices"`
}
