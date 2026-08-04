import { ws } from "./ws.js";

const DB_NAME = "penik-messenger";
const DB_VERSION = 6;

let _db = null;

export function openDB() {
  if (_db) return Promise.resolve(_db);
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains("messages")) {
        const ms = db.createObjectStore("messages", { keyPath: "msg_id" });
        ms.createIndex("chat_id", "chat_id", { unique: false });
      }
      if (!db.objectStoreNames.contains("contacts")) {
        db.createObjectStore("contacts", { keyPath: "user_id" });
      }
      if (!db.objectStoreNames.contains("e2ee_keys")) {
        db.createObjectStore("e2ee_keys", { keyPath: "id" });
      }
      // Group stores (DB v6+).
      if (!db.objectStoreNames.contains("groups")) {
        db.createObjectStore("groups", { keyPath: "id" });
      }
      if (!db.objectStoreNames.contains("group_members")) {
        const gm = db.createObjectStore("group_members", { keyPath: ["group_id", "user_id"] });
        gm.createIndex("group_id", "group_id", { unique: false });
      }
      if (!db.objectStoreNames.contains("group_keys")) {
        // key: `${group_id}:${key_version}` → { group_id, key_version, key }
        db.createObjectStore("group_keys", { keyPath: "id" });
      }
      if (!db.objectStoreNames.contains("group_messages")) {
        const gms = db.createObjectStore("group_messages", { keyPath: ["group_id", "message_id"] });
        gms.createIndex("group_id", "group_id", { unique: false });
      }
      // Clean up legacy object stores if they exist
      const legacyStores = ["identity", "pre_keys", "signed_pre_keys", "sessions_v2", "identities", "sessions", "opk_pool", "skipped_keys"];
      for (const legacy of legacyStores) {
        if (db.objectStoreNames.contains(legacy)) {
          db.deleteObjectStore(legacy);
        }
      }
    };
    req.onsuccess = (e) => {
      _db = e.target.result;
      resolve(_db);
    };
    req.onerror = () => reject(req.error);
  });
}

function tx(storeName, mode = "readonly") {
  return _db.transaction(storeName, mode).objectStore(storeName);
}

function get(store, key) {
  return new Promise((resolve, reject) => {
    const req = store.get(key);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function put(store, value) {
  return new Promise((resolve, reject) => {
    const req = store.put(value);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function del(store, key) {
  return new Promise((resolve, reject) => {
    const req = store.delete(key);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
  });
}

function getAll(store) {
  return new Promise((resolve, reject) => {
    const req = store.getAll();
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

// Messages

export async function getMessage(msgId) {
  await openDB();
  if (typeof msgId === "string" && msgId.startsWith("server-")) {
    const raw = msgId.substring(7);
    if (!isNaN(Number(raw))) {
      msgId = Number(raw);
    } else {
      msgId = raw;
    }
  }
  let res = await get(tx("messages"), msgId);
  if (!res) {
    if (typeof msgId === "number") {
      res = await get(tx("messages"), String(msgId));
    } else if (typeof msgId === "string" && !isNaN(Number(msgId))) {
      res = await get(tx("messages"), Number(msgId));
    }
  }
  if (!res) {
    res = await getMessageByClientId(String(msgId));
  }
  return res;
}

export async function getMessageByClientId(clientMsgId) {
  await openDB();
  const all = await getAllMessages();
  return all.find(m => m.client_msg_id === clientMsgId);
}

export async function deleteMessage(msgId) {
  await openDB();
  await del(tx("messages", "readwrite"), msgId);
  await del(tx("messages", "readwrite"), String(msgId));
  if (typeof msgId === "string" && !isNaN(Number(msgId))) {
    await del(tx("messages", "readwrite"), Number(msgId));
  }
}

export async function saveMessage(message) {
  await openDB();
  return put(tx("messages", "readwrite"), message);
}

export async function getMessages(chatId, limit = 50, before = null) {
  await openDB();
  return new Promise((resolve, reject) => {
    const transaction = _db.transaction("messages", "readonly");
    const store = transaction.objectStore("messages");
    const index = store.index("chat_id");
    const list = [];

    const keyRange = IDBKeyRange.only(String(chatId));
    const beforeTime = before == null ? null : (typeof before === "number" ? before : Number(before));
    const req = index.openCursor(keyRange);

    req.onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const msg = cursor.value;
        if (beforeTime == null || msg.created_at < beforeTime) {
          list.push(msg);
        }
        cursor.continue();
      } else {
        // Order strictly by wall-clock time. The cursor yields messages in
        // primary-key (msg_id / server id) order, which is NOT a reliable proxy
        // for send time: a pending message can be resolved to a fresh, larger
        // server id and would otherwise jump to the bottom despite an old
        // created_at. Sort here and return the newest `limit` messages.
        list.sort((a, b) => (a.created_at || 0) - (b.created_at || 0));
        resolve(list.slice(-limit));
      }
    };
    req.onerror = () => reject(req.error);
  });
}

export async function getAllMessages() {
  await openDB();
  return getAll(tx("messages"));
}

export async function getMaxServerMsgId() {
  await openDB();
  return new Promise((resolve, reject) => {
    const transaction = _db.transaction("messages", "readonly");
    const store = transaction.objectStore("messages");
    let maxId = 0;

    store.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const msg = cursor.value;
        const numericId = Number(msg.msg_id);
        if (!isNaN(numericId) && numericId > maxId) {
          maxId = numericId;
        }
        cursor.continue();
      } else {
        resolve(maxId);
      }
    };
    store.openCursor().onerror = (e) => reject(e.target.error);
  });
}

export async function updateMessageDelivered(msgId, status) {
  await openDB();
  const transaction = _db.transaction(["messages"], "readwrite");
  const store = transaction.objectStore("messages");
  
  const msg = await get(store, msgId);
  if (msg) {
    msg.delivered = status;
    if (status) {
      msg.delivered_at = Date.now();
    }
    await put(store, msg);
    return true;
  }
  return false;
}

export async function updateMessageRead(msgId) {
  await openDB();
  const store = tx("messages", "readwrite");
  const msg = await get(store, msgId);
  if (!msg) return false;
  msg.read = 1;
  await put(store, msg);
  return true;
}

// Contacts

export async function getContact(userId) {
  await openDB();
  return get(tx("contacts"), Number(userId));
}

export async function saveContact(contact) {
  await openDB();
  const c = { ...contact, user_id: Number(contact.user_id || contact.id) };
  return put(tx("contacts", "readwrite"), c);
}

export async function getAllContacts() {
  await openDB();
  return getAll(tx("contacts"));
}

// Database Management

export async function deleteChatData(userId) {
  await openDB();
  const uId = Number(userId);
  await new Promise((resolve, reject) => {
    const transaction = _db.transaction(["messages"], "readwrite");
    const store = transaction.objectStore("messages");
    store.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        if (String(cursor.value.chat_id) === String(uId)) {
          cursor.delete();
        }
        cursor.continue();
      } else {
        resolve();
      }
    };
    transaction.onerror = (e) => reject(e.target.error);
  });

  await new Promise((resolve, reject) => {
    const transaction = _db.transaction(["contacts"], "readwrite");
    const store = transaction.objectStore("contacts");
    const req = store.delete(uId);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
  });
}

// deleteGroupData removes a group and every locally cached row associated with
// it (members, keys, messages). Used when declining an invite or leaving.
export async function deleteGroupData(groupId) {
  await openDB();
  const gid = Number(groupId);
  await new Promise((resolve, reject) => {
    const transaction = _db.transaction(
      ["groups", "group_members", "group_keys", "group_messages"], "readwrite");
    transaction.objectStore("groups").delete(gid);
    // Members and messages are keyed by a group_id index; iterate and delete.
    for (const storeName of ["group_members", "group_messages"]) {
      const idx = transaction.objectStore(storeName).index("group_id");
      idx.openCursor(IDBKeyRange.only(gid)).onsuccess = (e) => {
        const cursor = e.target.result;
        if (cursor) { cursor.delete(); cursor.continue(); }
      };
    }
    // Keys use a string primary key `${group_id}:${version}`; scan by prefix.
    const keyStore = transaction.objectStore("group_keys");
    keyStore.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        if (Number(cursor.value.group_id) === gid) cursor.delete();
        cursor.continue();
      }
    };
    transaction.oncomplete = () => resolve();
    transaction.onerror = (e) => reject(e.target.error);
  });
}

export async function updateMsgId(oldId, newId) {
  await openDB();
  const transaction = _db.transaction(["messages"], "readwrite");
  const store = transaction.objectStore("messages");
  const msg = await get(store, oldId);
  if (msg) {
    await del(store, oldId);
    if (!msg.client_msg_id) {
      msg.client_msg_id = String(oldId);
    }
    msg.msg_id = newId;
    await put(store, msg);
    return true;
  }
  return false;
}

export async function updateMsgIdAndDelivered(oldId, newId, deliveredStatus) {
  await openDB();
  const transaction = _db.transaction(["messages"], "readwrite");
  const store = transaction.objectStore("messages");
  const msg = await get(store, oldId);
  if (msg) {
    await del(store, oldId);
    if (!msg.client_msg_id) {
      msg.client_msg_id = String(oldId);
    }
    msg.msg_id = newId;
    msg.delivered = deliveredStatus;
    if (deliveredStatus) {
      msg.delivered_at = Date.now();
    }
    await put(store, msg);
    return true;
  }
  return false;
}

export async function findAndResolvePendingSentMessage(chatId, timestamp, serverId, clientMsgId = null) {
  await openDB();
  return new Promise((resolve) => {
    const transaction = _db.transaction(["messages"], "readwrite");
    const store = transaction.objectStore("messages");

    let done = false;
    let target = null;
    let targetKey = null;

    const finalize = () => {
      if (done) return;
      done = true;
      if (target) {
        store.delete(targetKey).onsuccess = () => {
          const oldId = target.msg_id;
          target.msg_id = serverId;
          target.delivered = 1;
          if (!target.client_msg_id) {
            target.client_msg_id = oldId;
          }
          store.put(target).onsuccess = () => resolve(oldId);
        };
      } else {
        resolve(null);
      }
    };

    store.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const msg = cursor.value;
        const isTemp = typeof msg.msg_id === "string" && (msg.msg_id.startsWith("tmp-") || msg.msg_id.includes("-"));

        if (String(msg.chat_id) === String(chatId) && isTemp) {
          // Prefer an exact identity match on client_msg_id: this is the only
          // safe way to map an ACK/echo to the message that produced it. The
          // old "oldest temp in chat" heuristic could stamp an unrelated
          // lingering message with a fresh (larger) server id, sending it to
          // the bottom of the list while keeping its original created_at.
          if (clientMsgId && String(msg.client_msg_id) === String(clientMsgId)) {
            target = msg;
            targetKey = cursor.key;
            finalize();
            return;
          }
          // Legacy fallback only when we have no client id to match on.
          if (!clientMsgId && (!target || msg.created_at < target.created_at)) {
            target = msg;
            targetKey = cursor.key;
          }
        }
        cursor.continue();
      } else {
        finalize();
      }
    };
  });
}

export async function clearIndexedDB() {
  await openDB();
  return new Promise((resolve, reject) => {
    const list = ["contacts", "messages", "e2ee_keys", "groups", "group_members", "group_keys", "group_messages"];
    const transaction = _db.transaction(list, "readwrite");
    for (const s of list) {
      transaction.objectStore(s).clear();
    }
    transaction.oncomplete = () => resolve();
    transaction.onerror = (e) => reject(e.target.error);
  });
}

export async function saveIdentityKey(envelope) {
  await openDB();
  return put(tx("e2ee_keys", "readwrite"), { id: "identity_key", envelope });
}

export async function getIdentityKey() {
  await openDB();
  const record = await get(tx("e2ee_keys"), "identity_key");
  return record ? record.envelope : null;
}

// The raw private Identity Key is held in IndexedDB (not localStorage) so it is
// not trivially readable via a synchronous localStorage dump during an XSS. It
// is stored as raw bytes under a dedicated key in the e2ee_keys store.
export async function saveIKPrivate(privateKey) {
  await openDB();
  return put(tx("e2ee_keys", "readwrite"), { id: "identity_private_key", privateKey });
}

export async function getIKPrivate() {
  await openDB();
  const record = await get(tx("e2ee_keys"), "identity_private_key");
  return record ? record.privateKey : null;
}

export async function saveIKPublic(publicKey) {
  await openDB();
  return put(tx("e2ee_keys", "readwrite"), { id: "identity_public_key", publicKey });
}

export async function getIKPublic() {
  await openDB();
  const record = await get(tx("e2ee_keys"), "identity_public_key");
  return record ? record.publicKey : null;
}

export function getPersistentDeviceName() {
  let name = localStorage.getItem("device_name");
  if (!name) {
    name = "Web Client " + Math.random().toString(36).substring(2, 8).toUpperCase();
    localStorage.setItem("device_name", name);
  }
  return name;
}

// ── Groups ──

export async function saveGroup(group) {
  await openDB();
  return put(tx("groups", "readwrite"), { ...group, id: Number(group.id) });
}

export async function getGroup(groupId) {
  await openDB();
  return get(tx("groups"), Number(groupId));
}

export async function getAllGroups() {
  await openDB();
  return getAll(tx("groups"));
}

export async function saveGroupMembers(groupId, members) {
  await openDB();
  const gid = Number(groupId);
  return new Promise((resolve, reject) => {
    const transaction = _db.transaction("group_members", "readwrite");
    const store = transaction.objectStore("group_members");
    // Replace the full member set for this group.
    const idx = store.index("group_id");
    idx.openCursor(IDBKeyRange.only(gid)).onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        cursor.delete();
        cursor.continue();
      } else {
        for (const m of members) {
          store.put({ ...m, group_id: gid, user_id: Number(m.user_id) });
        }
      }
    };
    transaction.oncomplete = () => resolve();
    transaction.onerror = (e) => reject(e.target.error);
  });
}

export async function getGroupMembers(groupId) {
  await openDB();
  const gid = Number(groupId);
  return new Promise((resolve, reject) => {
    const store = _db.transaction("group_members", "readonly").objectStore("group_members");
    const idx = store.index("group_id");
    const out = [];
    const req = idx.openCursor(IDBKeyRange.only(gid));
    req.onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        out.push(cursor.value);
        cursor.continue();
      } else {
        resolve(out);
      }
    };
    req.onerror = (e) => reject(e.target.error);
  });
}

// Group keys are stored keyed by `${group_id}:${key_version}`. The key bytes are
// held as a Uint8Array; the surrounding e2ee_keys identity envelope already
// protects the device at rest.
function groupKeyId(groupId, version) {
  return `${Number(groupId)}:${Number(version)}`;
}

export async function saveGroupKey(groupId, version, keyBytes) {
  await openDB();
  return put(tx("group_keys", "readwrite"), {
    id: groupKeyId(groupId, version),
    group_id: Number(groupId),
    key_version: Number(version),
    key: keyBytes,
  });
}

export async function getGroupKey(groupId, version) {
  await openDB();
  const rec = await get(tx("group_keys"), groupKeyId(groupId, version));
  return rec ? rec.key : null;
}

export async function saveGroupMessage(message) {
  await openDB();
  return put(tx("group_messages", "readwrite"), {
    ...message,
    group_id: Number(message.group_id),
  });
}

export async function getGroupMessage(groupId, messageId) {
  await openDB();
  return get(tx("group_messages"), [Number(groupId), String(messageId)]);
}

export async function getGroupMessages(groupId, limit = 50) {
  await openDB();
  const gid = Number(groupId);
  return new Promise((resolve, reject) => {
    const store = _db.transaction("group_messages", "readonly").objectStore("group_messages");
    const idx = store.index("group_id");
    const out = [];
    const req = idx.openCursor(IDBKeyRange.only(gid), "prev");
    req.onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor && out.length < limit) {
        out.push(cursor.value);
        cursor.continue();
      } else {
        // Sort ascending by server id (falls back to created_at for pending).
        out.sort((a, b) => (a.id || 0) - (b.id || 0) || a.created_at - b.created_at);
        resolve(out);
      }
    };
    req.onerror = (e) => reject(e.target.error);
  });
}

export async function getAllGroupMembers() {
  await openDB();
  return getAll(tx("group_members"));
}

export async function getAllGroupKeys() {
  await openDB();
  return getAll(tx("group_keys"));
}

export async function getAllGroupMessages() {
  await openDB();
  return getAll(tx("group_messages"));
}

