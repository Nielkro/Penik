// Group management, message send/receive, history sync.

import {
  apiGet,
  apiPost,
  createGroup as apiCreateGroup,
  listGroups as apiListGroups,
  getGroup as apiGetGroup,
  listGroupMembers,
  inviteGroupMember,
  removeGroupMember,
  changeGroupMemberRole,
  acceptGroupInvitation,
  declineGroupInvitation,
  getGroupHistory,
  renameGroup as apiRenameGroup,
  uploadGroupAvatar,
  getServerTimeSec,
  getApiOrigin,
} from './api.js';
import {
  saveGroup, getGroup as dbGetGroup, getAllGroups, deleteGroupData,
  saveGroupMembers, getGroupMembers,
  saveGroupMessage, getGroupMessage, getGroupMessages, updateGroupMessageText, deleteGroupMessage,
} from './storage.js';
import { ws, OP } from './ws.js';
import { showDesktopNotification } from './app.js';
import { appSounds } from './sounds.js';
import { groupAvatarUpdateTimestamps } from './ui/components.js';

function myUserId() { return Number(localStorage.getItem('user_id')); }
function myDeviceId() { return Number(localStorage.getItem('device_id')); }

/* ── Public API: group lifecycle ── */

export async function createGroup(name, memberUserIds) {
  const group = await apiCreateGroup({ name, member_user_ids: memberUserIds });
  await saveGroup(group);
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
  try {
    await syncHistory(groupId);
  } catch (e) {
    console.warn('[groups] syncHistory on accept failed', e.message);
  }
}

export async function declineInvitation(groupId) {
  await declineGroupInvitation(groupId);
  await deleteGroupData(groupId);
}

export async function inviteMember(groupId, userId) {
  await inviteGroupMember(groupId, userId);
  await refreshMembers(groupId);
}

export async function changeMemberRole(groupId, userId, role) {
  await changeGroupMemberRole(groupId, userId, role);
  await refreshMembers(groupId);
}

export async function removeMember(groupId, userId) {
  await removeGroupMember(groupId, userId);
  await refreshMembers(groupId);
}

/* ── Public API: messaging ── */

export async function sendGroupMessage(groupId, text, replyToMsgId = null) {
  const messageId = crypto.randomUUID();
  const createdAt = getServerTimeSec();
  const senderUserId = myUserId();

  const localRecord = {
    group_id: groupId,
    message_id: messageId,
    id: 0,
    reply_to_msg_id: replyToMsgId,
    sender_user_id: senderUserId,
    sender_device_id: myDeviceId(),
    key_version: 0,
    plaintext: text,
    created_at: createdAt,
    delivered: 0,
  };
  await saveGroupMessage(localRecord);
  emit({ type: 'message', groupId: Number(groupId) });
  window.dispatchEvent(new CustomEvent("local-group-msg-sent", { detail: { groupId: Number(groupId), record: localRecord } }));

  ws.send(OP.GROUP_MESSAGE_SEND, {
    group_id: groupId,
    message_id: messageId,
    reply_to_msg_id: replyToMsgId || undefined,
    plaintext: text,
    created_at: createdAt,
  });
  return messageId;
}

export async function decryptIncoming(frame) {
  const groupId = Number(frame.group_id);
  const messageId = String(frame.message_id);
  const senderUserId = Number(frame.sender_user_id);
  const senderDeviceId = Number(frame.sender_device_id || 0);
  const text = frame.plaintext != null ? frame.plaintext : "";
  const createdAt = Number(frame.created_at);
  const editedAt = frame.edited_at ? Number(frame.edited_at) * 1000 : null;

  const existing = await getGroupMessage(groupId, messageId);
  const isEdited = frame.edited_at && (!existing || !existing.edited_at || (Number(frame.edited_at) * 1000 > existing.edited_at));
  if (existing && existing.id && !isEdited) return null;

  if (existing && existing.id) {
    if (frame.edited_at) {
      await updateGroupMessageText(groupId, messageId, text, editedAt);
      emit({ type: 'edit', groupId, messageId, text, editedAt });
    }
    return existing;
  }

  const record = {
    group_id: groupId,
    message_id: messageId,
    id: Number(frame.id || 0),
    reply_to_msg_id: frame.reply_to_msg_id || null,
    sender_user_id: senderUserId,
    sender_device_id: senderDeviceId,
    key_version: 0,
    plaintext: text,
    created_at: createdAt,
    edited_at: editedAt,
    delivered: 1,
  };
  await saveGroupMessage(record);
  if (record.id) {
    ws.send(OP.GROUP_MESSAGE_DELIVERED, { id: record.id });
  }
  return record;
}

/* ── Offline history sync ── */

export async function syncHistory(groupId) {
  let cursor;
  do {
    const page = await getGroupHistory(groupId, { limit: 100, before_id: cursor });
    for (const m of page.messages) {
      const existing = await getGroupMessage(groupId, String(m.message_id));
      const isEdited = m.edited_at && (!existing || !existing.edited_at || (Number(m.edited_at) * 1000 > existing.edited_at));
      if (existing && existing.id && !isEdited) continue;

      await decryptIncoming({
        group_id: groupId,
        id: m.id,
        message_id: m.message_id,
        reply_to_msg_id: m.reply_to_msg_id || null,
        sender_user_id: m.sender_user_id,
        sender_device_id: m.sender_device_id,
        plaintext: m.plaintext || "",
        created_at: m.created_at,
        edited_at: m.edited_at,
      });
    }
    cursor = page.next_cursor;
  } while (cursor);
}

export { getAllGroups, getGroupMessages };

/* ── WS wiring ── */

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

export function registerGroupWSListeners() {
  ws.on(OP.GROUP_MESSAGE_RECV, async (frame) => {
    const record = await decryptIncoming(frame);
    if (record) {
      const myId = localStorage.getItem("user_id");
      if (String(record.sender_user_id || record.sender_id) !== String(myId)) {
        appSounds.playMessageReceived();
        const g = await dbGetGroup(record.group_id);
        const title = g?.name || 'Группа';
        const sender = record.sender_name ? `${record.sender_name}: ` : '';
        const avatarUrl = `${getApiOrigin()}/api/v1/groups/${record.group_id}/avatar`;
        showDesktopNotification(title, sender + (record.plaintext || ''), `group_${record.group_id}`, () => {
          window.focus();
          location.hash = `#/group/${record.group_id}`;
        }, avatarUrl);
      }
      emit({ type: 'message', groupId: record.group_id, message: record });
    }
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

  ws.on(OP.GROUP_AVATAR_UPDATE, (frame) => {
    const groupId = Number(frame.group_id);
    const ts = frame.ts ? frame.ts * 1000 : Date.now();
    groupAvatarUpdateTimestamps.set(String(groupId), ts);
    emit({ type: 'avatar', groupId });
  });

  ws.on(OP.GROUP_MEMBER_CHANGED, async (frame) => {
    const groupId = Number(frame.group_id);
    try { await syncGroups(); } catch (e) { console.warn('[groups] sync on member change failed', e.message); }
    try { await refreshMembers(groupId); } catch { /* pending invitee: not yet allowed */ }
    emit({ type: 'members', groupId });
  });

  ws.on(OP.GROUP_MESSAGE_EDIT_NOTIFY, async (frame) => {
    const groupId = Number(frame.group_id);
    const messageId = String(frame.message_id);
    const text = frame.plaintext != null ? frame.plaintext : "";
    const editedAt = Number(frame.edited_at) * 1000;
    await updateGroupMessageText(groupId, messageId, text, editedAt);
    emit({ type: 'edit', groupId, messageId, text, editedAt });
  });

  ws.on(OP.GROUP_MESSAGE_DELETE_NOTIFY, async (frame) => {
    const groupId = Number(frame.group_id);
    const messageId = String(frame.message_id);
    await deleteGroupMessage(groupId, messageId);
    emit({ type: 'delete', groupId, messageId });
  });
}

export async function deleteGroupMsg(groupId, messageId) {
  await deleteGroupMessage(groupId, messageId);
  emit({ type: 'delete', groupId: Number(groupId), messageId: String(messageId) });
  ws.send(OP.GROUP_MESSAGE_DELETE, {
    group_id: Number(groupId),
    message_id: String(messageId),
  });
}

export async function editGroupMessage(groupId, messageId, newText) {
  const editedAt = getServerTimeSec();
  await updateGroupMessageText(groupId, messageId, newText, editedAt * 1000);
  emit({ type: 'edit', groupId, messageId, text: newText, editedAt: editedAt * 1000 });

  ws.send(OP.GROUP_MESSAGE_EDIT, {
    group_id: groupId,
    message_id: messageId,
    plaintext: newText,
    edited_at: editedAt
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
