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
  acceptGroupInvitation,
  declineGroupInvitation,
  listGroupKeyVersions,
  getGroupEnvelope,
  uploadGroupEnvelopes,
  rotateGroupKey as apiRotateGroupKey,
  getGroupHistory,
} from './api.js';
import {
  deriveSharedSecret,
  generateGroupKey,
  groupEncrypt,
  groupDecrypt,
  wrapGroupKeyForDevice,
  unwrapGroupKey,
} from './crypto.js';
import {
  saveGroup, getGroup as dbGetGroup, getAllGroups, deleteGroupData,
  saveGroupMembers, getGroupMembers,
  saveGroupKey, getGroupKey,
  saveGroupMessage, getGroupMessage, getGroupMessages,
} from './storage.js';
import { ws, OP } from './ws.js';
import { state } from './app.js';

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

function resolvePrivateIK() {
  if (state.privateIK) return state.privateIK;
  const stored = localStorage.getItem('penik_ik_priv');
  if (stored) {
    state.privateIK = new Uint8Array(atob(stored).split('').map(c => c.charCodeAt(0)));
    return state.privateIK;
  }
  throw new Error('Private Identity Key not found in memory/localStorage');
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
      bundle = await apiGet(`/keys/bundle/${uid}?skip_otk=true`);
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
async function wrapKeyForDevices(groupKey, devices) {
  const myPriv = resolvePrivateIK();
  const envelopes = [];
  for (const dev of devices) {
    const secret = await deriveSharedSecret(myPriv, dev.ik_pub);
    const { encryptedKey, salt, nonce } = await wrapGroupKeyForDevice(groupKey, secret);
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

  const myPriv = resolvePrivateIK();
  const secret = await deriveSharedSecret(myPriv, senderIK);
  const groupKey = await unwrapGroupKey(
    b64uDecode(env.encrypted_key), secret, b64uDecode(env.salt), b64uDecode(env.nonce),
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
    const envelopes = await wrapKeyForDevices(groupKey, devices);
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
  // A newly active member triggers a rotation by whoever owns/admins the group;
  // our own device will receive the new envelope via GROUP_KEY_AVAILABLE.
}

// declineInvitation rejects a pending invitation and drops the local copy so the
// group no longer shows up in the list.
export async function declineInvitation(groupId) {
  await declineGroupInvitation(groupId);
  await deleteGroupData(groupId);
}

// inviteMember adds a user then rotates the key so the new epoch covers them
// once they accept. Rotation is performed here by the inviting owner/admin.
export async function inviteMember(groupId, userId) {
  await inviteGroupMember(groupId, userId);
  await refreshMembers(groupId);
  await rotateAndDistribute(groupId);
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
    const envelopes = await wrapKeyForDevices(groupKey, devices);
    await uploadGroupEnvelopes(groupId, version, envelopes);
  }
  // Reflect the new current version locally.
  const g = await dbGetGroup(groupId);
  if (g) { g.current_key_version = version; await saveGroup(g); }
  return version;
}

/* ── Public API: messaging ── */

export async function sendGroupMessage(groupId, text) {
  const group = await dbGetGroup(groupId);
  const version = group ? Number(group.current_key_version) : await currentVersion(groupId);
  const groupKey = await ensureGroupKey(groupId, version);

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
  let cursor;
  // Walk pages newest→oldest; decrypt what we have keys for. A missing key for
  // one page must not block the rest.
  do {
    const page = await getGroupHistory(groupId, { limit: 100, before_id: cursor });
    for (const m of page.messages) {
      const existing = await getGroupMessage(groupId, String(m.message_id));
      if (existing && existing.id) continue;
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
      console.warn('[groups] key fetch on notify failed', e.message);
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

    // The server sends this to active owner/admins when someone accepts an
    // invitation. Whoever is owner/admin rotates the key so the newly active
    // device gets a fresh envelope (it has no key for the current version yet).
    try {
      const g = await dbGetGroup(groupId);
      if (g && g.status === 'active' && (g.role === 'owner' || g.role === 'admin')) {
        await rotateAndDistribute(groupId);
        emit({ type: 'key', groupId, version: Number(g.current_key_version || 0) + 1 });
      }
    } catch (e) {
      console.warn('[groups] rotation on member change failed', e.message);
    }
  });
}

