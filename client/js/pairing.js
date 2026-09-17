import { decryptPairingHistory } from "./crypto.js";
import {
  saveMessage, saveContact, saveGroup, saveGroupMembers,
  saveGroupKey, saveGroupMessage,
} from "./storage.js";

const decodeB64Url = value => {
  const normalized = String(value).replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, char => char.charCodeAt(0));
};

export async function importPairingHistory(encoded, sharedSecret) {
  const envelope = JSON.parse(new TextDecoder().decode(decodeB64Url(encoded)));
  const data = await decryptPairingHistory(envelope, sharedSecret);

  const lastMsgByChat = new Map();
  for (const message of data.messages || []) {
    const saved = {
      ...message,
      msg_id: message.msg_id ?? message.server_id ?? `import-${message.created_at}`,
      chat_id: String(message.chat_id ?? message.chat_user_id),
      chat_user_id: Number(message.chat_user_id ?? message.chat_id),
      sender_id: Number(message.sender_id),
      recipient_id: Number(message.recipient_id),
      plaintext: message.text ?? message.plaintext ?? "",
    };
    await saveMessage(saved);
    const peerId = saved.chat_user_id;
    const existingLast = lastMsgByChat.get(peerId);
    if (!existingLast || (saved.created_at || 0) >= (existingLast.created_at || 0)) {
      lastMsgByChat.set(peerId, saved);
    }
  }
  for (const contact of data.contacts || []) {
    const peerId = Number(contact.user_id || contact.id);
    const lastMsg = lastMsgByChat.get(peerId);
    await saveContact({
      ...contact,
      last_message: contact.last_message || (lastMsg ? (lastMsg.plaintext || lastMsg.text || "") : ""),
      last_ts: contact.last_ts || (lastMsg ? (lastMsg.created_at || 0) : 0),
    });
  }
  for (const group of data.groups || []) await saveGroup(group);

  const membersByGroup = new Map();
  for (const member of data.group_members || []) {
    const list = membersByGroup.get(Number(member.group_id)) || [];
    list.push(member);
    membersByGroup.set(Number(member.group_id), list);
  }
  for (const [groupId, members] of membersByGroup) await saveGroupMembers(groupId, members);
  for (const key of data.group_keys || []) {
    await saveGroupKey(key.group_id, key.key_version, decodeB64Url(key.key));
  }
  for (const message of data.group_messages || []) {
    await saveGroupMessage(message);
  }
  return data;
}
