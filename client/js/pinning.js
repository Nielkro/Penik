// TOFU (trust-on-first-use) pinning of peer devices' public identity keys.
//
// The first X25519 identity key observed for a (user_id, device_id) pair is
// pinned in IndexedDB. If the key changes (e.g. device reinstall or re-login),
// a warning notification is displayed to inform the user and the pin is updated,
// without blocking message delivery or encryption.

import { encodeKey } from './crypto.js';
import { getPinnedIK, savePinnedIK, saveMessage } from './storage.js';
import { showToast } from './ui/components.js';

// Track keys we've already warned about this session to avoid repeated toast spam.
const _warned = new Set();

export class IdentityKeyChangedError extends Error {
  constructor(userId, deviceId) {
    super(`identity key changed for user ${userId} device ${deviceId}`);
    this.userId = userId;
    this.deviceId = deviceId;
  }
}

function pinId(userId, deviceId) {
  return `${Number(userId)}:${Number(deviceId)}`;
}

// verifyPeerIdentityKey returns one of:
//   'new'      — key pinned (first sight)
//   'ok'       — key matches the pin
//   'updated'  — key differed, warning issued, pin updated to the new key
export async function verifyPeerIdentityKey(userId, deviceId, ikPubBytes) {
  const key = pinId(userId, deviceId);
  const presented = encodeKey(ikPubBytes);

  const pinned = await getPinnedIK(userId, deviceId);
  if (!pinned) {
    await savePinnedIK(userId, deviceId, presented);
    return 'new';
  }
  if (pinned === presented) return 'ok';

  // Key changed: update pin and notify the user with a warning toast & system message in chat
  await savePinnedIK(userId, deviceId, presented);

  if (!_warned.has(key)) {
    _warned.add(key);
    window.dispatchEvent(new CustomEvent('peer-ik-changed', {
      detail: { userId: Number(userId), deviceId: Number(deviceId) },
    }));
    showToast(`Ключ безопасности пользователя (устройство №${Number(deviceId)}) изменился`, 'warning');

    try {
      const sysMsg = {
        msg_id: `sys-keychange-${Date.now()}-${Number(userId)}-${Number(deviceId)}`,
        chat_id: Number(userId),
        sender_id: 0,
        plaintext: "⚠️ Код безопасности изменился!",
        created_at: Date.now(),
        delivered: 1,
        pending: 0
      };
      await saveMessage(sysMsg);
    } catch (_) {}
  }

  return 'updated';
}
