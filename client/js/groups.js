// Group E2EE orchestration: key management, message send/receive, rotation.
//
// Trust model (see EasyGroups plan): the server routes ciphertext and assigns
// sender identity from the authenticated session. It never sees the group key or
// plaintext. Each group epoch has a 32-byte group key, wrapped per recipient
// device with the pairwise X25519 shared secret.

import {
  apiGet,
  createGroup as apiCreateGroup,
  listGroups as apiListGroups,
  getGroup as apiGetGroup,
  listGroupMembers,
  inviteGroupMember,
  removeGroupMember,
  changeGroupMemberRole,
  acceptGroupInvitation,
  declineGroupInvitation,
  listGroupKeyVersions,
  getGroupEnvelope,
  uploadGroupEnvelopes,
  rotateGroupKey as apiRotateGroupKey,
  getGroupHistory,
  uploadGroupHistoryPackets,
  renameGroup as apiRenameGroup,
  uploadGroupAvatar,
} from './api.js';
import {
  deriveSharedSecret,
  generateGroupKey,
  groupEncrypt,
  groupDecrypt,
  wrapGroupKeyForDevice,
  unwrapGroupKey,
  e2eeEncrypt,
  e2eeDecrypt,
} from './crypto.js';
import {
  saveGroup, getGroup as dbGetGroup, getAllGroups, deleteGroupData,
  saveGroupMembers, getGroupMembers,
  saveGroupKey, getGroupKey,
  saveGroupMessage, getGroupMessage, getGroupMessages,
} from './storage.js';
import { ws, OP } from './ws.js';
import { loadPrivateIK } from './app.js';
import { groupAvatarUpdateTimestamps } from './ui/components.js';

/* ── base64url helpers (server uses RawURLEncoding for group blobs) ── */

function b64uEncode(bytes) {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64uDecode(str) {
  const pad = str.length % 4 === 0 ? '' : '='.repeat(4 - (str.length % 4));
  const bin = atob(str.replace(/-/g, '+').replace(/_/g, '/') + pad);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

// Standard base64 (key bundles encode identity_key with StdEncoding).
function stdB64Decode(str) {
  const bin = atob(str);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function resolvePrivateIK() {
  const priv = await loadPrivateIK();
  if (!priv) throw new Error('Private Identity Key not found');
  return priv;
}

function myUserId() { return Number(localStorage.getItem('user_id')); }
function myDeviceId() { return Number(localStorage.getItem('device_id')); }

/* ── Device enumeration + key wrapping ── */

// activeDeviceKeys returns [{ device_id, ik_pub }] for every device of the given
// user ids, using the existing pairwise key-bundle endpoint.
async function fetchDeviceKeys(userIds) {
  const result = [];
  for (const uid of userIds) {
    let bundle;
    try {
      bundle = await apiGet(`/keys/bundle/${uid}`);
    } catch {
      continue;
    }
    for (const d of bundle?.devices || []) {
      if (!d.identity_key) continue;
      result.push({ device_id: Number(d.device_id), ik_pub: stdB64Decode(d.identity_key) });
    }
  }
  return result;
}

// wrapKeyForDevices builds envelope items for every device, using our private IK
// and each device's public IK to derive the pairwise secret.
async function wrapKeyForDevices(groupKey, devices, groupId, version) {
  const myPriv = await resolvePrivateIK();
  const envelopes = [];
  for (const dev of devices) {
    const secret = await deriveSharedSecret(myPriv, dev.ik_pub);
    const { encryptedKey, salt, nonce } = await wrapGroupKeyForDevice(groupKey, secret, groupId, version);
    envelopes.push({
      device_id: dev.device_id,
      encrypted_key: b64uEncode(encryptedKey),
      salt: b64uEncode(salt),
      nonce: b64uEncode(nonce),
    });
  }
  return envelopes;
}

/* ── Key acquisition ── */

// ensureGroupKey returns the plaintext group key for a version, fetching and
// unwrapping the device envelope if it is not cached locally.
export async function ensureGroupKey(groupId, version) {
  const cached = await getGroupKey(groupId, version);
  if (cached) return cached;

  const env = await getGroupEnvelope(groupId, version);
  // The envelope was wrapped by sender_device_id using our device's pairwise
  // secret. We derive the same secret from our private IK and the sender's IK.
  const senderDeviceId = Number(env.sender_device_id);
  const senderIK = await fetchDeviceIK(groupId, senderDeviceId);
  if (!senderIK) throw new Error(`sender device ${senderDeviceId} identity key not found`);

  const myPriv = await resolvePrivateIK();
  const secret = await deriveSharedSecret(myPriv, senderIK);
  const groupKey = await unwrapGroupKey(
    b64uDecode(env.encrypted_key), secret, b64uDecode(env.salt), b64uDecode(env.nonce),
    groupId, version
  );
  await saveGroupKey(groupId, version, groupKey);
  return groupKey;
}

// fetchDeviceIK returns one device's public identity key by scanning the given
// group's members' key bundles. groupId is passed explicitly so concurrent
// lookups for different groups cannot race on shared state.
async function fetchDeviceIK(groupId, deviceId) {
  const members = await getGroupMembers(groupId).catch(() => []);
  const userIds = members.map(m => m.user_id);
  const devices = await fetchDeviceKeys(userIds.length ? userIds : [myUserId()]);
  const found = devices.find(d => d.device_id === deviceId);
  return found ? found.ik_pub : null;
}

/* ── Public API: group lifecycle ── */

export async function createGroup(name, memberUserIds) {
  const group = await apiCreateGroup({ name, member_user_ids: memberUserIds });
  await saveGroup(group);

  // Generate the epoch-1 key and distribute envelopes to all active devices.
  const groupKey = generateGroupKey();
  await saveGroupKey(group.id, 1, groupKey);

  // Only the owner is active at creation; invitees are pending until they accept.
  const devices = await fetchDeviceKeys([myUserId()]);
  if (devices.length) {
    const envelopes = await wrapKeyForDevices(groupKey, devices, group.id, 1);
    await uploadGroupEnvelopes(group.id, 1, envelopes);
  }
  await refreshMembers(group.id);
  return group;
}

export async function syncGroups() {
  const { groups } = await apiListGroups();
  for (const g of groups) await saveGroup(g);
  return groups;
}

export async function refreshMembers(groupId) {
  const { members } = await listGroupMembers(groupId);
  await saveGroupMembers(groupId, members);
  return members;
}

export async function acceptInvitation(groupId) {
  await acceptGroupInvitation(groupId);
  await refreshMembers(groupId);
  // The inviter already staged our key envelope at invite time (variant A), and
  // the fetch gate opens once we are active. Pull the current key, then decrypt
  // whatever history that key covers.
  try {
    const version = await currentVersion(groupId);
    await ensureGroupKey(groupId, version);
    await syncHistory(groupId);
  } catch (e) {
    console.warn('[groups] key fetch on accept failed', e.message);
  }
  // Variant B: pull the pre-join backlog the inviter staged for this device.
  try {
    await pullHistoryPacket(groupId);
  } catch (e) {
    console.warn('[groups] history packet pull on accept failed', e.message);
  }
}

// declineInvitation rejects a pending invitation and drops the local copy so the
// group no longer shows up in the list.
export async function declineInvitation(groupId) {
  await declineGroupInvitation(groupId);
  await deleteGroupData(groupId);
}

// inviteMember adds a user then pre-stages the current group key for their
// devices: we wrap the existing key on the current version and upload envelopes,
// rather than rotating. The invitee fetches it on accept and uses it for new
// messages sent after they join.
//
// When shareHistory is set, we deliver the pre-join backlog via variant B: our
// locally held plaintext is re-encrypted per invitee device under the pairwise
// secret and uploaded as a one-shot history packet, instead of sharing old
// group keys. See shareHistoryWithInvitee.
export async function inviteMember(groupId, userId, { shareHistory = false } = {}) {
  await inviteGroupMember(groupId, userId);
  await refreshMembers(groupId);

  const devices = await fetchDeviceKeys([userId]);
  if (!devices.length) return;

  const g = await dbGetGroup(groupId);
  const current = g ? Number(g.current_key_version) : await currentVersion(groupId);

  // Stage the current version so the invitee can read messages sent after join.
  const groupKey = await ensureGroupKey(groupId, current);
  const envelopes = await wrapKeyForDevices(groupKey, devices, groupId, current);
  await uploadGroupEnvelopes(groupId, current, envelopes);

  if (shareHistory) {
    await shareHistoryWithInvitee(groupId, userId, devices);
  }
}

// shareHistoryWithInvitee packs the locally held plaintext backlog and uploads a
// per-device history packet (variant B). Each packet is encrypted under the
// pairwise secret between our device and the invitee device, so the server only
// stores opaque ciphertext. We can only share what we hold locally; gaps in our
// own history simply do not reach the invitee.
export async function shareHistoryWithInvitee(groupId, userId, devices) {
  const messages = await getGroupMessages(groupId).catch(() => []);
  if (!messages.length || !devices.length) return;

  const blob = packHistoryBlob(messages);
  const myPriv = await resolvePrivateIK();
  const packets = [];
  for (const dev of devices) {
    const secret = await deriveSharedSecret(myPriv, dev.ik_pub);
    const { ciphertext, salt, nonce } = await e2eeEncrypt(blob, secret);
    packets.push({
      device_id: dev.device_id,
      encrypted_history: b64uEncode(ciphertext),
      salt: b64uEncode(salt),
      nonce: b64uEncode(nonce),
    });
  }
  await uploadGroupHistoryPackets(groupId, packets);
}

// pullHistoryPacket fetches this device's one-shot history packet (delete-on-
// fetch), derives the pairwise secret from the sender device's IK, decrypts, and
// stores the messages locally. A 404 means nothing was staged for us.
export async function pullHistoryPacket(groupId) {
  let packet;
  try {
    packet = await getGroupHistoryPacket(groupId);
  } catch (e) {
    if (e.status === 404) return;
    throw e;
  }
  const senderDeviceId = Number(packet.sender_device_id);
  const senderIK = await fetchDeviceIK(groupId, senderDeviceId);
  if (!senderIK) throw new Error(`sender device ${senderDeviceId} identity key not found`);

  const myPriv = await resolvePrivateIK();
  const secret = await deriveSharedSecret(myPriv, senderIK);
  const pt = await e2eeDecrypt(
    b64uDecode(packet.encrypted_history), secret, b64uDecode(packet.salt), b64uDecode(packet.nonce),
  );
  const { messages } = unpackHistoryBlob(pt);
  for (const m of messages) {
    const existing = await getGroupMessage(groupId, String(m.message_id));
    if (existing && existing.id) continue;
    await saveGroupMessage({
      group_id: groupId, message_id: String(m.message_id), id: Number(m.id) || 0,
      sender_user_id: Number(m.sender_user_id), sender_device_id: Number(m.sender_device_id) || 0,
      key_version: Number(m.key_version), plaintext: m.plaintext,
      created_at: Number(m.created_at), delivered: 1,
    });
  }
}

// packHistoryBlob serializes locally readable messages into the versioned
// envelope the invitee's pullHistoryPacket expects.
function packHistoryBlob(messages) {
  return JSON.stringify({
    version: 1,
    messages: messages.map(m => ({
      id: m.id, message_id: m.message_id, sender_user_id: m.sender_user_id,
      sender_device_id: m.sender_device_id, key_version: m.key_version,
      plaintext: m.plaintext, created_at: m.created_at,
    })),
  });
}

function unpackHistoryBlob(bytes) {
  const parsed = JSON.parse(new TextDecoder().decode(bytes));
  return { messages: Array.isArray(parsed?.messages) ? parsed.messages : [] };
}

// changeMemberRole promotes/demotes a member (owner only, server-enforced) and
// refreshes the local member cache so the UI reflects the new role.
export async function changeMemberRole(groupId, userId, role) {
  await changeGroupMemberRole(groupId, userId, role);
  await refreshMembers(groupId);
}

export async function removeMember(groupId, userId) {
  await removeGroupMember(groupId, userId);
  await refreshMembers(groupId);
  await rotateAndDistribute(groupId);
}

// rotateAndDistribute creates a new key version and uploads envelopes for every
// currently active device. Only owner/admin may call this (server-enforced).
export async function rotateAndDistribute(groupId) {
  const resp = await apiRotateGroupKey(groupId);
  const version = Number(resp.key_version);
  const groupKey = generateGroupKey();
  await saveGroupKey(groupId, version, groupKey);

  const userIds = [...new Set((resp.devices || []).map(d => Number(d.user_id)))];
  const devices = await fetchDeviceKeys(userIds);
  if (devices.length) {
    const envelopes = await wrapKeyForDevices(groupKey, devices, groupId, version);
    await uploadGroupEnvelopes(groupId, version, envelopes);
  }
  // Reflect the new current version locally.
  const g = await dbGetGroup(groupId);
  if (g) { g.current_key_version = version; await saveGroup(g); }
  return version;
}

/* ── Public API: messaging ── */

// backfillCurrentKey re-wraps the CURRENT group key for every active member
// device that is missing an envelope for it, and uploads the additions. This
// repairs the common case where a device joined (or a member re-logged in on a
// new browser profile) AFTER the last rotation/invite, so no one ever staged the
// key for it — the symptom is that device 404-ing on every keys/{version} fetch.
//
// Owner/admin only (the server rejects envelope uploads from others). Idempotent:
// the upload uses ON CONFLICT DO UPDATE, and we only send envelopes for devices
// that currently lack one, so calling it repeatedly is cheap and safe.
export async function backfillCurrentKey(groupId) {
  const grp = await dbGetGroup(groupId);
  const version = grp ? Number(grp.current_key_version) : await currentVersion(groupId);

  // We can only wrap a key we actually hold.
  let groupKey;
  try {
    groupKey = await ensureGroupKey(groupId, version);
  } catch (e) {
    console.warn('[groups] backfill skipped, no current key locally', e.message);
    return 0;
  }

  const members = await getGroupMembers(groupId).catch(() => []);
  const activeUserIds = members
    .filter(m => (m.status || 'active') === 'active')
    .map(m => Number(m.user_id));
  if (!activeUserIds.length) return 0;

  const devices = await fetchDeviceKeys(activeUserIds);
  if (!devices.length) return 0;

  // Which of those devices already have an envelope for this version?
  let covered = new Set();
  try {
    const { device_ids } = await apiGet(`/groups/${groupId}/keys/${version}/devices`);
    covered = new Set((device_ids || []).map(Number));
  } catch (e) {
    // Endpoint unavailable: fall back to re-wrapping for everyone. The upload is
    // idempotent, so this is correct, just slightly more work.
    console.warn('[groups] envelope-coverage lookup failed, rewrapping all', e.message);
  }

  const missing = devices.filter(d => !covered.has(Number(d.device_id)));
  if (!missing.length) return 0;

  const envelopes = await wrapKeyForDevices(groupKey, missing, groupId, version);
  await uploadGroupEnvelopes(groupId, version, envelopes);
  console.log('[groups] backfilled current key v' + version + ' for', missing.length, 'device(s)');
  return missing.length;
}

export async function sendGroupMessage(groupId, text) {
  const group = await dbGetGroup(groupId);
  let version = group ? Number(group.current_key_version) : await currentVersion(groupId);
  let groupKey;
  try {
    groupKey = await ensureGroupKey(groupId, version);
  } catch (e) {
    // If the envelope is missing for this device, attempt to rotate the group key.
    // This succeeds if the user is an owner/admin, restoring their ability to send.
    console.log('[groups] key unavailable, attempting auto-rotation...', e.message);
    try {
      version = await rotateAndDistribute(groupId);
      groupKey = await ensureGroupKey(groupId, version);
    } catch (rotateErr) {
      console.error('[groups] auto-rotation failed', rotateErr);
      throw e;
    }
  }

  const messageId = crypto.randomUUID();
  // created_at is bound into the AAD and must match what the server persists and
  // relays. The server works in Unix seconds, so encode seconds here — mixing
  // milliseconds made the recipient's AAD mismatch and decryption fail.
  const createdAt = Math.floor(Date.now() / 1000);
  const { ciphertext, salt, nonce } = await groupEncrypt(text, groupKey, groupId, version, messageId, createdAt);

  // Persist an optimistic local copy (pending until ACK assigns a server id).
  await saveGroupMessage({
    group_id: groupId, message_id: messageId, id: 0,
    sender_user_id: myUserId(), sender_device_id: myDeviceId(),
    key_version: version, plaintext: text, created_at: createdAt, delivered: 0,
  });

  ws.send(OP.GROUP_MESSAGE_SEND, {
    group_id: groupId, message_id: messageId, key_version: version,
    ciphertext, salt, nonce, created_at: createdAt,
  });
  return messageId;
}

async function currentVersion(groupId) {
  try {
    const g = await apiGetGroup(groupId);
    await saveGroup(g);
    return Number(g.current_key_version);
  } catch {
    const { versions } = await listGroupKeyVersions(groupId);
    return versions.length ? Math.max(...versions) : 1;
  }
}

// decryptIncoming decrypts a received group message frame and persists it.
// Returns the stored message record, or null if it was a duplicate/undecryptable.
export async function decryptIncoming(frame) {
  const groupId = Number(frame.group_id);
  const version = Number(frame.key_version);
  const messageId = String(frame.message_id);

  // Dedup by (group_id, message_id).
  const existing = await getGroupMessage(groupId, messageId);
  if (existing && existing.id) return null;

  let groupKey;
  try {
    groupKey = await ensureGroupKey(groupId, version);
  } catch (e) {
    // Missing key (e.g. envelope not yet delivered): leave for later retry.
    console.warn('[groups] key unavailable for', groupId, version, e.message);
    return null;
  }

  let text;
  try {
    const pt = await groupDecrypt(
      toU8(frame.ciphertext), groupKey, toU8(frame.salt), toU8(frame.nonce),
      groupId, version, messageId, Number(frame.created_at),
    );
    text = new TextDecoder().decode(pt);
    // Log details of the decrypted incoming group message
    const grp = await dbGetGroup(groupId);
    const groupName = grp ? grp.name : `Группа ${groupId}`;
    const keyHex = Array.from(groupKey).map(b => b.toString(16).padStart(2, '0')).join('');
    console.log(`[groups] Пришло новое сообщение в группу "${groupName}": "${text}" (использован ключ v${version}: ${keyHex})`);
  } catch (e) {
    console.error('[groups] decrypt/AAD failed for', groupId, messageId, e.message);
    return null;
  }

  const record = {
    group_id: groupId, message_id: messageId, id: Number(frame.id),
    sender_user_id: Number(frame.sender_user_id), sender_device_id: Number(frame.sender_device_id),
    key_version: version, plaintext: text, created_at: Number(frame.created_at), delivered: 1,
  };
  await saveGroupMessage(record);
  ws.send(OP.GROUP_MESSAGE_DELIVERED, { id: record.id });
  return record;
}

// toU8 normalizes a WS MsgPack binary field to Uint8Array. WS frames carry raw
// bytes; base64 decoding belongs to the REST/history path (b64uDecode), never
// here — guessing between the two silently corrupts ciphertext.
function toU8(v) {
  if (v instanceof Uint8Array) return v;
  if (v instanceof ArrayBuffer) return new Uint8Array(v);
  if (Array.isArray(v)) return new Uint8Array(v);
  throw new Error(`group frame: unsupported binary field type ${typeof v}`);
}

/* ── Offline history sync (ciphertext) ── */

export async function syncHistory(groupId) {
  // Which key versions this device actually has an envelope for. Under variant A
  // a member only receives the version current at invite time, so older epochs
  // are unreadable. Resolve the set once and skip messages on other versions —
  // otherwise every such message triggers a doomed envelope fetch (404 storm).
  const available = new Set();
  let haveVersionList = false;
  try {
    const { versions } = await listGroupKeyVersions(groupId);
    for (const v of versions || []) available.add(Number(v));
    haveVersionList = true;
  } catch (e) {
    console.warn('[groups] key-version list failed, syncing all', e.message);
  }

  // A device that joined after the last rotation has NO envelopes at all. The
  // version list then comes back empty; without this guard we would fall through
  // and hammer the envelope endpoint for every message (the 404 storm). Bail so
  // the UI shows "key unavailable" instead of flooding the network.
  if (haveVersionList && available.size === 0) {
    console.warn('[groups] no key envelopes for this device in group', groupId, '- skipping history');
    return;
  }

  // Versions we already failed to fetch this pass: many messages share a version,
  // and ensureGroupKey only caches on success, so a single missing envelope would
  // otherwise re-fetch once per message.
  const failedVersions = new Set();

  let cursor;
  // Walk pages newest→oldest; decrypt what we have keys for. A missing key for
  // one page must not block the rest.
  do {
    const page = await getGroupHistory(groupId, { limit: 100, before_id: cursor });
    for (const m of page.messages) {
      const existing = await getGroupMessage(groupId, String(m.message_id));
      if (existing && existing.id) continue;
      const version = Number(m.key_version);
      // Skip versions we can never decrypt so we don't hammer the envelope
      // endpoint. If the version list was unavailable, fall through and try.
      if (available.size && !available.has(version)) continue;
      if (failedVersions.has(version)) continue;
      try {
        await ensureGroupKey(groupId, version);
      } catch (e) {
        failedVersions.add(version);
        continue;
      }
      await decryptIncoming({
        group_id: groupId, id: m.id, message_id: m.message_id,
        sender_user_id: m.sender_user_id, sender_device_id: m.sender_device_id,
        key_version: m.key_version, ciphertext: b64uDecode(m.ciphertext),
        salt: b64uDecode(m.salt), nonce: b64uDecode(m.nonce), created_at: m.created_at,
      });
    }
    cursor = page.next_cursor;
  } while (cursor);
}

export { getAllGroups, getGroupMessages };

/* ── WS wiring ── */

// Listeners notified when a group's state changes (new message, key, roster).
// The UI subscribes to re-render the active group view.
const _listeners = new Set();

export function onGroupUpdate(fn) {
  _listeners.add(fn);
  return () => _listeners.delete(fn);
}

function emit(evt) {
  for (const fn of _listeners) {
    try { fn(evt); } catch (e) { console.error('[groups] listener error', e); }
  }
}

// registerGroupWSListeners wires the group opcodes into the shared WS manager.
// Call once during app init, after setupGlobalWSListeners.
export function registerGroupWSListeners() {
  ws.on(OP.GROUP_MESSAGE_RECV, async (frame) => {
    const record = await decryptIncoming(frame);
    if (record) emit({ type: 'message', groupId: record.group_id, message: record });
  });

  ws.on(OP.GROUP_MESSAGE_ACK, async (frame) => {
    const groupId = Number(frame.group_id);
    const messageId = String(frame.message_id);
    const rec = await getGroupMessage(groupId, messageId);
    if (rec) {
      rec.id = Number(frame.id);
      rec.delivered = 1;
      await saveGroupMessage(rec);
      emit({ type: 'ack', groupId, message: rec });
    }
  });

  ws.on(OP.GROUP_KEY_AVAILABLE, async (frame) => {
    const groupId = Number(frame.group_id);
    const version = Number(frame.key_version);
    try {
      await ensureGroupKey(groupId, version);
      const g = await dbGetGroup(groupId);
      if (g && version > Number(g.current_key_version || 0)) {
        g.current_key_version = version;
        await saveGroup(g);
      }
      // Newly available key may unlock messages that arrived before the envelope.
      await syncHistory(groupId);
      emit({ type: 'key', groupId, version });
    } catch (e) {
      // 403 here is expected: the envelope was staged while we were still a
      // pending invitee, and the fetch gate opens only once we accept. We pull
      // the key again in acceptInvitation, so this notification can be ignored.
      if (e.status === 403) return;
      console.warn('[groups] key fetch on notify failed', e.message);
    }
  });

  ws.on(OP.GROUP_AVATAR_UPDATE, (frame) => {
    const groupId = Number(frame.group_id);
    const ts = frame.ts ? frame.ts * 1000 : Date.now();
    groupAvatarUpdateTimestamps.set(String(groupId), ts);
    emit({ type: 'avatar', groupId });
  });

  ws.on(OP.GROUP_HISTORY_READY, async (frame) => {
    const groupId = Number(frame.group_id);
    try {
      await pullHistoryPacket(groupId);
      emit({ type: 'history', groupId });
    } catch (e) {
      console.warn('[groups] history packet pull on notify failed', e.message);
    }
  });

  ws.on(OP.GROUP_MEMBER_CHANGED, async (frame) => {
    const groupId = Number(frame.group_id);
    // Sync the group list first: this is also how a fresh invitation arrives, so
    // the group may not exist locally yet. Member refresh is best-effort since a
    // pending invitee has no right to list the roster until they accept.
    try { await syncGroups(); } catch (e) { console.warn('[groups] sync on member change failed', e.message); }
    try { await refreshMembers(groupId); } catch { /* pending invitee: not yet allowed */ }
    emit({ type: 'members', groupId });
  });
}

export async function renameGroup(groupId, newName) {
  await apiRenameGroup(groupId, newName);
  const groups = await getAllGroups();
  const group = groups.find(g => Number(g.id) === groupId);
  if (group) {
    group.name = newName;
    await saveGroup(group);
  }
}

export { uploadGroupAvatar };

