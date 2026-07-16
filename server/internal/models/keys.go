package models

// IdentityKey holds a device's long-term key bundle.
type IdentityKey struct {
	DeviceID  int64
	IKPub     []byte
	SPKPub    []byte
	SPKSig    []byte
	UpdatedAt int64
}

// OneTimeKey is a single pre-key for the handshake.
type OneTimeKey struct {
	ID       int64
	DeviceID int64
	OPKPub   []byte
	Used     bool
}

// KeyBackup holds an encrypted key backup blob for a device.
type KeyBackup struct {
	DeviceID      int64
	UserID        int64
	EncryptedBlob []byte
	KDFSalt       []byte
	CreatedAt     int64
}

// DeviceKeyBundle bundles everything a sender needs to initiate a session.
type DeviceKeyBundle struct {
	DeviceID int64  `msgpack:"device_id"`
	IKPub    []byte `msgpack:"ik_pub"`
	SPKPub   []byte `msgpack:"spk_pub"`
	SPKSig   []byte `msgpack:"spk_sig"`
	OPKPub   []byte `msgpack:"opk_pub"` // may be nil if exhausted
}
