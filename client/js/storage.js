import { ws } from "./ws.js";
import { sealBytes, openBytes, sealString, openString, isSealed, resetWrappingKey } from "./vault.js";

const DB_NAME = "penik-messenger";
const DB_VERSION = 8;

let _db = null;

export function openDB() {
  if (_db) return Promise.resolve(_db);
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = (e) => {
      const db = req.result;
      if (!db.objectStoreNames.contains("messages")) {
        const ms = db.createObjectStore("messages", { keyPath: "msg_id" });
        ms.createIndex("chat_id", "chat_id", { unique: false });
      }
      if (!db.objectStoreNames.contains("deleted_messages")) {
        db.createObjectStore("deleted_messages", { keyPath: "msg_id" });
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
      if (!db.objectStoreNames.contains("media_cache")) {
        db.createObjectStore("media_cache", { keyPath: "url" });
      }
      // Clean up legacy object stores if they exist
      const legacyStores = ["identity", "pre_keys", "signed_pre_keys", "sessions_v2", "identities", "sessions", "opk_pool", "skipped_keys"];
      for (const legacy of legacyStores) {
        if (db.objectStoreNames.contains(legacy)) {
          db.deleteObjectStore(legacy);
        }
      }
    };
    req.onsuccess = () => {
      _db = req.result;
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
  if (msgId == null) return null;
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
  if (res) return unsealMessageRecord(res);

  const all = await getAllMessages();
  const targetStr = String(msgId);
  const targetNum = Number(msgId);
  const found = all.find(m => 
    String(m.msg_id) === targetStr || 
    String(m.server_id) === targetStr || 
    String(m.client_msg_id) === targetStr ||
    (!isNaN(targetNum) && (m.server_id === targetNum || m.msg_id === targetNum))
  );
  return found || null;
}

export async function getMessageByClientId(clientMsgId) {
  await openDB();
  const all = await getAllMessages();
  return all.find(m => m.client_msg_id === clientMsgId || String(m.msg_id) === String(clientMsgId));
}

export async function markMessageDeletedLocally(msgId) {
  if (!msgId) return;
  await openDB();
  const idStr = String(msgId);
  await put(tx("deleted_messages", "readwrite"), { msg_id: idStr, deleted_at: Date.now() });
}

export async function isMessageDeletedLocally(msgId) {
  if (!msgId) return false;
  await openDB();
  const idStr = String(msgId);
  const found = await get(tx("deleted_messages", "readonly"), idStr);
  return Boolean(found);
}

export async function deleteMessage(msgId) {
  await openDB();
  console.log("[storage] deleteMessage called for msgId:", msgId);
  await markMessageDeletedLocally(msgId);

  const all = await getAllMessages();
  const targetIdStr = String(msgId);
  const targetIdNum = Number(msgId);

  const matches = all.filter(m => 
    String(m.msg_id) === targetIdStr || 
    m.client_msg_id === targetIdStr ||
    (m.msg_id != null && m.msg_id === targetIdNum)
  );

  const keysToDelete = new Set();
  keysToDelete.add(msgId);
  keysToDelete.add(targetIdStr);
  if (!isNaN(targetIdNum)) keysToDelete.add(targetIdNum);
  matches.forEach(m => {
    if (m.msg_id != null) {
      keysToDelete.add(m.msg_id);
      markMessageDeletedLocally(m.msg_id);
    }
    if (m.client_msg_id) {
      keysToDelete.add(m.client_msg_id);
      markMessageDeletedLocally(m.client_msg_id);
    }
  });

  for (const key of keysToDelete) {
    try {
      await del(tx("messages", "readwrite"), key);
      console.log("[storage] Successfully deleted message key from IndexedDB:", key);
    } catch (err) {
      console.warn("[storage] delete key warning:", key, err);
    }
  }
}

export async function saveMessage(message) {
  await openDB();
  if (!message) return;
  const toStore = { ...message };
  if (toStore.chat_id != null) {
    toStore.chat_id = String(toStore.chat_id);
  }
  // Seal text if present and not already sealed.
  if (toStore.text != null && typeof toStore.text === "string") {
    toStore.sealed_text = await sealString(vaultStore, toStore.text);
    delete toStore.text;
  }
  return put(tx("messages", "readwrite"), toStore);
}

async function unsealMessageRecord(msg) {
  if (!msg) return msg;
  if (msg.sealed_text && isSealed(msg.sealed_text)) {
    const plain = await openString(vaultStore, msg.sealed_text);
    return { ...msg, text: plain ?? "" };
  }
  return msg;
}

export async function getMessages(chatId, limit = 50, before = null) {
  await openDB();
  return new Promise((resolve, reject) => {
    const transaction = _db.transaction("messages", "readonly");
    const store = transaction.objectStore("messages");
    const index = store.index("chat_id");
    const list = [];
    const seen = new Set();

    const strId = String(chatId);
    const numId = Number(chatId);
    const beforeTime = before == null ? null : (typeof before === "number" ? before : Number(before));

    const finalize = async () => {
      list.sort((a, b) => (a.created_at || 0) - (b.created_at || 0));
      const sliced = list.slice(-limit);
      const unsealed = await Promise.all(sliced.map(m => unsealMessageRecord(m)));
      resolve(unsealed);
    };

    const scanKey = (k, next) => {
      const req = index.openCursor(IDBKeyRange.only(k));
      req.onsuccess = (e) => {
        const cursor = e.target.result;
        if (cursor) {
          const msg = cursor.value;
          const msgKey = msg.msg_id != null ? String(msg.msg_id) : (msg.client_msg_id || String(cursor.key));
          if (!seen.has(msgKey)) {
            seen.add(msgKey);
            if (beforeTime == null || msg.created_at < beforeTime) {
              list.push(msg);
            }
          }
          cursor.continue();
        } else if (next) {
          next();
        } else {
          finalize();
        }
      };
      req.onerror = () => {
        if (next) next();
        else finalize();
      };
    };

    if (!isNaN(numId) && String(numId) !== strId) {
      scanKey(strId, () => scanKey(numId, null));
    } else if (!isNaN(numId)) {
      scanKey(strId, () => scanKey(numId, null));
    } else {
      scanKey(strId, null);
    }
  });
}

export async function getAllMessages() {
  await openDB();
  const all = await getAll(tx("messages"));
  return Promise.all(all.map(m => unsealMessageRecord(m)));
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
  let msg = await get(store, oldId);
  if (!msg && typeof oldId === "string" && !isNaN(Number(oldId))) {
    msg = await get(store, Number(oldId));
  }
  if (msg) {
    await del(store, oldId);
    if (!msg.client_msg_id) {
      msg.client_msg_id = String(oldId);
    }
    msg.msg_id = newId;
    msg.delivered = deliveredStatus;
    delete msg.pending;
    msg.server_acked = true;
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
  // The wrapping key is cleared together with the sealed records it opens, so the
  // cached handle must be dropped or the next login would seal against a key that
  // is no longer in the database.
  resetWrappingKey();
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

// vaultStore adapts the e2ee_keys object store for vault.js, which needs a way to
// persist its own non-extractable wrapping key without importing storage internals.
const vaultStore = {
  read: async (id) => {
    await openDB();
    return get(tx("e2ee_keys"), id);
  },
  write: async (record) => {
    await openDB();
    return put(tx("e2ee_keys", "readwrite"), record);
  },
};

// The private Identity Key never leaves IndexedDB in the clear: it is sealed with
// the origin's non-extractable wrapping key (see vault.js), so a database dump is
// not a usable copy of the key.
export async function saveIKPrivate(privateKey) {
  await openDB();
  const sealed = await sealBytes(vaultStore, privateKey);
  return put(tx("e2ee_keys", "readwrite"), { id: "identity_private_key", sealed });
}

export async function getIKPrivate() {
  await openDB();
  const record = await get(tx("e2ee_keys"), "identity_private_key");
  if (!record) return null;
  if (isSealed(record.sealed)) return openBytes(vaultStore, record.sealed);
  // Legacy plaintext record from before the vault: seal it on first read so the
  // cleartext copy does not survive this session.
  if (record.privateKey) {
    const bytes = record.privateKey;
    await saveIKPrivate(bytes);
    return bytes;
  }
  return null;
}

export async function saveIKPublic(publicKey) {
  await openDB();
  return put(tx("e2ee_keys", "readwrite"), { id: "identity_public_key", publicKey });
}

// The session bearer token is sealed for the same reason as the identity key: it
// is a long-lived credential, and an unencrypted copy in IndexedDB is a
// take-away credential rather than a page-bound one.
export async function saveSessionToken(token) {
  await openDB();
  const sealed = await sealString(vaultStore, token);
  return put(tx("e2ee_keys", "readwrite"), { id: "session_token", sealed });
}

export async function getSessionToken() {
  await openDB();
  const record = await get(tx("e2ee_keys"), "session_token");
  if (!record) return null;
  if (isSealed(record.sealed)) return openString(vaultStore, record.sealed);
  if (record.token) {
    await saveSessionToken(record.token);
    return record.token;
  }
  return null;
}

export async function deleteSessionToken() {
  await openDB();
  return del(tx("e2ee_keys", "readwrite"), "session_token");
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

// getClientPlatform builds a human-readable OS + browser label for this browser,
// e.g. "Linux · Chrome" or "Windows · Firefox", used to describe the device on
// the user's devices screen.
export function getClientPlatform() {
  const ua = navigator.userAgent || "";
  let os = "";
  if (/Android/i.test(ua)) os = "Android";
  else if (/Windows/i.test(ua)) os = "Windows";
  else if (/iPhone|iPad|iPod/i.test(ua)) os = "iOS";
  else if (/Mac OS X|Macintosh/i.test(ua)) os = "macOS";
  else if (/Linux/i.test(ua)) os = "Linux";

  let browser = "";
  if (/Firefox\//i.test(ua)) browser = "Firefox";
  else if (/Edg\//i.test(ua)) browser = "Edge";
  else if (/Chrome\//i.test(ua)) browser = "Chrome";
  else if (/Safari\//i.test(ua)) browser = "Safari";

  if (os && browser) return `${os} · ${browser}`;
  return os || browser || "Веб-клиент";
}

// getClientLocation derives a coarse location label from the browser time zone,
// e.g. "Europe/Moscow" becomes "Moscow". This avoids requesting geolocation
// permission while still giving the user a recognizable place hint.
export function getClientLocation() {
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || "";
    if (!tz) return "";
    const parts = tz.split("/");
    return parts[parts.length - 1].replace(/_/g, " ");
  } catch (e) {
    return "";
  }
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
// sealed with the origin's non-extractable wrapping key (vault.js): a single group
// key decrypts every message of that key version, so it must not sit in the
// database as plaintext.
function groupKeyId(groupId, version) {
  return `${Number(groupId)}:${Number(version)}`;
}

export async function saveGroupKey(groupId, version, keyBytes) {
  await openDB();
  const sealed = await sealBytes(vaultStore, keyBytes);
  return put(tx("group_keys", "readwrite"), {
    id: groupKeyId(groupId, version),
    group_id: Number(groupId),
    key_version: Number(version),
    sealed,
  });
}

export async function getGroupKey(groupId, version) {
  await openDB();
  const rec = await get(tx("group_keys"), groupKeyId(groupId, version));
  if (!rec) return null;
  if (isSealed(rec.sealed)) return openBytes(vaultStore, rec.sealed);
  // Legacy plaintext row: seal it on first read.
  if (rec.key) {
    await saveGroupKey(groupId, version, rec.key);
    return rec.key;
  }
  return null;
}

export async function saveGroupMessage(message) {
  await openDB();
  if (!message) return;
  const toStore = {
    ...message,
    group_id: Number(message.group_id),
  };
  if (toStore.text != null && typeof toStore.text === "string") {
    toStore.sealed_text = await sealString(vaultStore, toStore.text);
    delete toStore.text;
  }
  return put(tx("group_messages", "readwrite"), toStore);
}

export async function getGroupMessage(groupId, messageId) {
  await openDB();
  const raw = await get(tx("group_messages"), [Number(groupId), String(messageId)]);
  return unsealMessageRecord(raw);
}

export async function getGroupMessages(groupId, limit = 50) {
  await openDB();
  const gid = Number(groupId);
  return new Promise((resolve, reject) => {
    const store = _db.transaction("group_messages", "readonly").objectStore("group_messages");
    const idx = store.index("group_id");
    const out = [];
    const req = idx.openCursor(IDBKeyRange.only(gid), "prev");
    req.onsuccess = async (e) => {
      const cursor = e.target.result;
      if (cursor && out.length < limit) {
        out.push(cursor.value);
        cursor.continue();
      } else {
        // Sort ascending by server id (falls back to created_at for pending).
        out.sort((a, b) => (a.id || 0) - (b.id || 0) || a.created_at - b.created_at);
        const unsealed = await Promise.all(out.map(m => unsealMessageRecord(m)));
        resolve(unsealed);
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

// getAllGroupKeysPlain unseals every group key for pairing export. The result is
// immediately re-encrypted to the new device's public key, so it must never be
// written back to storage or logged.
export async function getAllGroupKeysPlain() {
  const rows = await getAllGroupKeys();
  const out = [];
  for (const row of rows) {
    const key = isSealed(row.sealed) ? await openBytes(vaultStore, row.sealed) : row.key;
    if (!key) continue;
    out.push({ id: row.id, group_id: row.group_id, key_version: row.key_version, key });
  }
  return out;
}

export async function getAllGroupMessages() {
  await openDB();
  return getAll(tx("group_messages"));
}

export async function saveCachedMedia(url, blob, mime) {
  await openDB();
  return put(tx("media_cache", "readwrite"), { url, blob, mime, created_at: Date.now() });
}

export async function getCachedMedia(url) {
  await openDB();
  const entry = await get(tx("media_cache"), url);
  if (!entry) return null;
  return URL.createObjectURL(entry.blob);
}


/* ── TOFU identity key pins ──
   Peer devices' public identity keys are pinned on first sight in the
   e2ee_keys store: id = `pinned_ik:{user_id}:{device_id}` → { id, ik }
   where ik is base64. A later change must be accepted by the user before
   messages are encrypted to / decrypted from that device again. */

export async function getPinnedIK(userId, deviceId) {
  await openDB();
  const record = await get(tx("e2ee_keys"), `pinned_ik:${userId}:${deviceId}`);
  return record ? record.ik : null;
}

export async function savePinnedIK(userId, deviceId, ikB64) {
  await openDB();
  return put(tx("e2ee_keys", "readwrite"), { id: `pinned_ik:${userId}:${deviceId}`, ik: ikB64 });
}
