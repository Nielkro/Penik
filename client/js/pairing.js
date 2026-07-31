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

  for (const message of data.messages || []) {
    await saveMessage({
      ...message,
      msg_id: message.msg_id ?? message.server_id ?? `import-${message.created_at}`,
      chat_id: String(message.chat_id ?? message.chat_user_id),
      chat_user_id: Number(message.chat_user_id ?? message.chat_id),
      sender_id: Number(message.sender_id),
      recipient_id: Number(message.recipient_id),
      plaintext: message.text ?? message.plaintext ?? "",
    });
  }
  for (const contact of data.contacts || []) await saveContact(contact);
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
