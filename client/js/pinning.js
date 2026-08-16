// TOFU (trust-on-first-use) pinning of peer devices' public identity keys.
//
// The first X25519 identity key observed for a (user_id, device_id) pair is
// pinned in IndexedDB. If the server later presents a different key for that
// pair — a possible server-side key substitution or MITM — decryption and
// encryption are blocked until the user explicitly accepts the new key in a
// confirmation dialog. This turns the manual safety-number check into an
// automatic change detector.

import { encodeKey } from './crypto.js';
import { getPinnedIK, savePinnedIK } from './storage.js';
import { showConfirmModal, showToast } from './ui/components.js';

// In-memory single-flight: concurrent checks for the same device share one
// dialog instead of stacking modals (e.g. during history sync).
const _inflight = new Map();
// Keys the user already declined this session: do not re-prompt.
const _declined = new Set();

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
//   'accepted' — key differed, user accepted it, pin updated
//   'changed'  — key differed, user declined (or non-interactive); NOT trusted
export async function verifyPeerIdentityKey(userId, deviceId, ikPubBytes, { interactive = true } = {}) {
  const key = pinId(userId, deviceId);
  const presented = encodeKey(ikPubBytes);

  const pinned = await getPinnedIK(userId, deviceId);
  if (!pinned) {
    await savePinnedIK(userId, deviceId, presented);
    return 'new';
  }
  if (pinned === presented) return 'ok';

  if (_declined.has(key)) return 'changed';

  if (!interactive) return 'changed';

  if (_inflight.has(key)) return _inflight.get(key);

  const promise = (async () => {
    // Let listeners (chat UI) react to the change too.
    window.dispatchEvent(new CustomEvent('peer-ik-changed', {
      detail: { userId: Number(userId), deviceId: Number(deviceId) },
    }));

    const accepted = await showConfirmModal(
      'Ключ устройства изменился',
      `Публичный ключ пользователя (устройство №${Number(deviceId)}) изменился. Это может быть переустановка устройства — либо подмена ключей сервером. Рекомендуется сверить safety number лично. Принять новый ключ?`,
      'Принять новый ключ',
      'Отклонить',
      true,
    );
    if (accepted) {
      await savePinnedIK(userId, deviceId, presented);
      showToast('Новый ключ устройства принят', 'warning');
      return 'accepted';
    }
    _declined.add(key);
    showToast('Сообщения с этим устройством заблокированы: ключ не принят', 'error');
    return 'changed';
  })();

  _inflight.set(key, promise);
  try {
    return await promise;
  } finally {
    _inflight.delete(key);
  }
}
