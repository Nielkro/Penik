import { ws } from "./ws.js";

const DB_NAME = "penik-messenger";
const DB_VERSION = 5;

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
  let res = await get(tx("messages"), msgId);
  if (!res) {
    if (typeof msgId === "number") {
      res = await get(tx("messages"), String(msgId));
    } else if (typeof msgId === "string" && !isNaN(Number(msgId))) {
      res = await get(tx("messages"), Number(msgId));
    }
  }
  return res;
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
    const req = index.openCursor(keyRange, "prev");

    req.onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const msg = cursor.value;
        if (before) {
          const beforeTime = typeof before === "number" ? before : Number(before);
          if (msg.created_at >= beforeTime) {
            cursor.continue();
            return;
          }
        }
        list.push(msg);
        if (list.length < limit) {
          cursor.continue();
        } else {
          resolve(list.reverse());
        }
      } else {
        resolve(list.reverse());
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

export async function updateMsgId(oldId, newId) {
  await openDB();
  const transaction = _db.transaction(["messages"], "readwrite");
  const store = transaction.objectStore("messages");
  const msg = await get(store, oldId);
  if (msg) {
    await del(store, oldId);
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

export async function findAndResolvePendingSentMessage(chatId, timestamp, serverId) {
  await openDB();
  return new Promise((resolve) => {
    const transaction = _db.transaction(["messages"], "readwrite");
    const store = transaction.objectStore("messages");
    
    let oldestMsg = null;
    let oldestKey = null;

    store.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const msg = cursor.value;
        const isTemp = typeof msg.msg_id === "string" && (msg.msg_id.startsWith("tmp-") || msg.msg_id.includes("-"));
        
        if (String(msg.chat_id) === String(chatId) && isTemp) {
          if (!oldestMsg || msg.created_at < oldestMsg.created_at) {
            oldestMsg = msg;
            oldestKey = cursor.key;
          }
        }
        cursor.continue();
      } else {
        if (oldestMsg) {
          store.delete(oldestKey).onsuccess = () => {
            const oldId = oldestMsg.msg_id;
            oldestMsg.msg_id = serverId;
            oldestMsg.delivered = 1;
            if (!oldestMsg.client_msg_id) {
              oldestMsg.client_msg_id = oldId;
            }
            store.put(oldestMsg).onsuccess = () => {
              resolve(true);
            };
          };
        } else {
          resolve(false);
        }
      }
    };
  });
}

export async function clearIndexedDB() {
  await openDB();
  return new Promise((resolve, reject) => {
    const list = ["contacts", "messages", "e2ee_keys"];
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

export async function savePreKeyPrivate(keyId, privateKey) {
  await openDB();
  const id = `prekey_${keyId.toString()}`;
  return put(tx("e2ee_keys", "readwrite"), { id, keyId: keyId.toString(), privateKey });
}

export async function getPreKeyPrivate(keyId) {
  await openDB();
  const id = `prekey_${keyId.toString()}`;
  const record = await get(tx("e2ee_keys"), id);
  return record ? record.privateKey : null;
}

export async function deletePreKeyPrivate(keyId) {
  await openDB();
  const id = `prekey_${keyId.toString()}`;
  return del(tx("e2ee_keys", "readwrite"), id);
}

export async function getAllPreKeyIds() {
  await openDB();
  const records = await getAll(tx("e2ee_keys"));
  return records
    .filter(r => r.id.startsWith("prekey_"))
    .map(r => r.keyId);
}

export function getPersistentDeviceName() {
  let name = localStorage.getItem("device_name");
  if (!name) {
    name = "Web Client " + Math.random().toString(36).substring(2, 8).toUpperCase();
    localStorage.setItem("device_name", name);
  }
  return name;
}
