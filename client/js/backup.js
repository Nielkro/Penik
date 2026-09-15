import {
  openDB, getAllContacts, getAllMessages, getAllGroups,
  getAllGroupMembers, getAllGroupKeysPlain, getAllGroupMessages,
  getIKPrivate, getIKPublic, saveIKPrivate, saveIKPublic,
  saveContact, saveMessage, saveGroup, saveGroupMembers,
  saveGroupKey, saveGroupMessage
} from "./storage.js";
import { encryptKeyBackup, decryptKeyBackup, derivePublicKey } from "./crypto.js";
import { generateMnemonicPhrase, defaultWordCoder } from "./wordcoder.js";

/**
 * Export complete local history to an encrypted .penikbackup JSON envelope.
 * @param {string} passphrase - User-defined passphrase or mnemonic seed phrase
 * @returns {Promise<string>} Encrypted backup JSON string
 */
export async function exportHistoryToBackup(passphrase) {
  const normalizedPassphrase = (passphrase || "").trim();
  if (!normalizedPassphrase) {
    throw new Error("Пароль или мнемоническая фраза не могут быть пустыми");
  }

  await openDB();

  const privBytes = await getIKPrivate();
  const pubBytes = await getIKPublic();
  const contacts = await getAllContacts();
  const messages = await getAllMessages();
  const groups = await getAllGroups();
  const members = await getAllGroupMembers();
  const groupKeys = await getAllGroupKeysPlain();
  const groupMessages = await getAllGroupMessages();

  const toB64 = (arr) => arr ? btoa(String.fromCharCode(...arr)) : null;

  const innerPayload = {
    exported_at: Date.now(),
    private_key: toB64(privBytes),
    public_key: toB64(pubBytes),
    chats: contacts.map(c => ({
      user_id: c.user_id,
      name: c.name || "",
      nickname: c.nickname || "",
      last_message: c.last_message || "",
      last_message_timestamp: c.last_message_timestamp || 0,
      unread_count: c.unread_count || 0,
      avatar_url: c.avatar_url || ""
    })),
    messages: messages.map(m => ({
      local_id: m.client_msg_id || String(m.msg_id || ""),
      server_id: m.msg_id && typeof m.msg_id === "number" ? m.msg_id : 0,
      chat_user_id: Number(m.chat_id || 0),
      sender_id: Number(m.sender_id || 0),
      text: m.text || "",
      timestamp: m.created_at || m.timestamp || 0,
      delivered: Boolean(m.delivered),
      read: Boolean(m.read),
      sent_by_me: Boolean(m.sent_by_me || m.is_mine),
      reply_to_msg_id: m.reply_to_msg_id || null,
      edited_at: m.edited_at || null
    })),
    groups: groups.map(g => ({
      id: g.id,
      name: g.name || "",
      avatar_url: g.avatar_url || "",
      current_key_version: g.current_key_version || 1,
      my_role: g.my_role || "member",
      last_message: g.last_message || "",
      last_message_timestamp: g.last_message_timestamp || 0,
      unread_count: g.unread_count || 0
    })),
    group_members: members.map(mem => ({
      group_id: mem.group_id,
      user_id: mem.user_id,
      role: mem.role || "member",
      name: mem.name || "",
      nickname: mem.nickname || "",
      avatar_url: mem.avatar_url || ""
    })),
    group_keys: groupKeys.map(gk => ({
      group_id: gk.group_id,
      key_version: gk.key_version,
      key_bytes: toB64(gk.key)
    })),
    group_messages: groupMessages.map(gm => ({
      group_id: gm.group_id,
      message_id: gm.message_id || gm.client_msg_id || "",
      server_id: gm.id || 0,
      sender_user_id: gm.sender_id || gm.sender_user_id || 0,
      text: gm.text || "",
      created_at: gm.created_at || 0,
      delivered: gm.delivered !== undefined ? gm.delivered : 1,
      sent_by_me: Boolean(gm.sent_by_me || gm.is_mine),
      sender_name: gm.sender_name || "",
      reply_to_msg_id: gm.reply_to_msg_id || null,
      edited_at: gm.edited_at || null
    }))
  };

  const payloadBytes = new TextEncoder().encode(JSON.stringify(innerPayload));
  const backup = await encryptKeyBackup(payloadBytes, normalizedPassphrase);

  const outerEnvelope = {
    penik_backup_version: 1,
    created_at: Date.now(),
    platform: "Web",
    salt: toB64(backup.salt),
    iv: toB64(backup.iv),
    encrypted_data: toB64(backup.encryptedBlob)
  };

  return JSON.stringify(outerEnvelope, null, 2);
}

/**
 * Import and decrypt complete history from .penikbackup JSON envelope.
 * @param {string} backupJsonString - Raw JSON file content
 * @param {string} passphrase - User-defined passphrase or mnemonic seed phrase
 * @returns {Promise<Object>} Import summary stats
 */
export async function importHistoryFromBackup(backupJsonString, passphrase) {
  const normalizedPassphrase = (passphrase || "").trim();
  if (!normalizedPassphrase) {
    throw new Error("Пароль или мнемоническая фраза не могут быть пустыми");
  }

  const fromB64 = (val) => {
    const bin = atob(val);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  };

  const outer = JSON.parse(backupJsonString);
  if (!outer.salt || !outer.iv || !outer.encrypted_data) {
    throw new Error("Неверный формат файла резервной копии Penik");
  }

  const salt = fromB64(outer.salt);
  const iv = fromB64(outer.iv);
  const encData = fromB64(outer.encrypted_data);

  const decryptedBytes = await decryptKeyBackup(encData, salt, iv, normalizedPassphrase);
  const innerPayload = JSON.parse(new TextDecoder().decode(decryptedBytes));

  await openDB();

  // Restore Identity Keys if needed
  if (innerPayload.private_key) {
    const existingPriv = await getIKPrivate();
    if (!existingPriv) {
      const privBytes = fromB64(innerPayload.private_key);
      const pubBytes = await derivePublicKey(privBytes);
      await saveIKPrivate(privBytes);
      await saveIKPublic(pubBytes);
    }
  }

  let chatsCount = 0;
  let messagesCount = 0;
  let groupsCount = 0;
  let groupMessagesCount = 0;

  // Restore Contacts / Chats
  if (Array.isArray(innerPayload.chats)) {
    for (const c of innerPayload.chats) {
      if (c.user_id) {
        await saveContact(c);
        chatsCount++;
      }
    }
  }

  // Restore Messages
  if (Array.isArray(innerPayload.messages)) {
    for (const m of innerPayload.messages) {
      await saveMessage({
        msg_id: m.server_id > 0 ? m.server_id : m.local_id,
        client_msg_id: m.local_id,
        chat_id: String(m.chat_user_id),
        sender_id: m.sender_id,
        text: m.text,
        timestamp: m.timestamp,
        created_at: m.timestamp,
        delivered: m.delivered ? 1 : 0,
        read: m.read ? 1 : 0,
        sent_by_me: m.sent_by_me,
        reply_to_msg_id: m.reply_to_msg_id,
        edited_at: m.edited_at
      });
      messagesCount++;
    }
  }

  // Restore Groups
  if (Array.isArray(innerPayload.groups)) {
    for (const g of innerPayload.groups) {
      if (g.id) {
        await saveGroup(g);
        groupsCount++;
      }
    }
  }

  // Restore Group Members
  if (Array.isArray(innerPayload.group_members)) {
    const grouped = {};
    for (const mem of innerPayload.group_members) {
      if (!grouped[mem.group_id]) grouped[mem.group_id] = [];
      grouped[mem.group_id].push(mem);
    }
    for (const [gid, mems] of Object.entries(grouped)) {
      await saveGroupMembers(Number(gid), mems);
    }
  }

  // Restore Group Keys
  if (Array.isArray(innerPayload.group_keys)) {
    for (const gk of innerPayload.group_keys) {
      if (gk.group_id && gk.key_version && gk.key_bytes) {
        const kBytes = fromB64(gk.key_bytes);
        await saveGroupKey(gk.group_id, gk.key_version, kBytes);
      }
    }
  }

  // Restore Group Messages
  if (Array.isArray(innerPayload.group_messages)) {
    for (const gm of innerPayload.group_messages) {
      if (gm.group_id) {
        await saveGroupMessage({
          id: gm.server_id,
          message_id: gm.message_id,
          group_id: gm.group_id,
          sender_id: gm.sender_user_id,
          sender_name: gm.sender_name,
          text: gm.text,
          created_at: gm.created_at,
          delivered: gm.delivered,
          sent_by_me: gm.sent_by_me,
          reply_to_msg_id: gm.reply_to_msg_id,
          edited_at: gm.edited_at
        });
        groupMessagesCount++;
      }
    }
  }

  return {
    chatsCount,
    messagesCount,
    groupsCount,
    groupMessagesCount
  };
}

/**
 * Triggers a browser file download of the backup envelope.
 * @param {string} jsonString - The .penikbackup JSON content
 * @param {string} filename - Optional file name
 */
export function downloadBackupFile(jsonString, filename = null) {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  const dateStr = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
  const defaultName = `penik_backup_${dateStr}.penikbackup`;

  const blob = new Blob([jsonString], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename || defaultName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/**
 * Reads a user-selected File as a text string.
 * @param {File} file
 * @returns {Promise<string>}
 */
export function readBackupFile(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === "string" ? reader.result : "");
    reader.onerror = () => reject(reader.error);
    reader.readAsText(file);
  });
}
