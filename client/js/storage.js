import { decodeKey, encodeKey, replacer, reviver } from "./crypto.js";
import { ws } from "./ws.js";
import { KeyHelper, SessionBuilder, SessionCipher, SignalProtocolAddress } from '@privacyresearch/libsignal-protocol-typescript';

const DB_NAME = "penik-messenger";
const DB_VERSION = 4;

let _db = null;
const trustedIdentities = new Map();

export async function preloadIdentities(dbInstance) {
  return new Promise((resolve, reject) => {
    const transaction = dbInstance.transaction("identities", "readonly");
    const store = transaction.objectStore("identities");
    store.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        trustedIdentities.set(cursor.value.address, cursor.value.publicKey);
        cursor.continue();
      } else {
        resolve();
      }
    };
    transaction.onerror = () => reject(transaction.error);
  });
}

export function openDB() {
  if (_db) return Promise.resolve(_db);
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains("identity")) {
        db.createObjectStore("identity", { keyPath: "id" });
      }
      if (!db.objectStoreNames.contains("messages")) {
        const ms = db.createObjectStore("messages", { keyPath: "msg_id" });
        ms.createIndex("chat_id", "chat_id", { unique: false });
      }
      if (!db.objectStoreNames.contains("contacts")) {
        db.createObjectStore("contacts", { keyPath: "user_id" });
      }
      // V3 stores for libsignal
      if (!db.objectStoreNames.contains("pre_keys")) {
        db.createObjectStore("pre_keys", { keyPath: "keyId" });
      }
      if (!db.objectStoreNames.contains("signed_pre_keys")) {
        db.createObjectStore("signed_pre_keys", { keyPath: "keyId" });
      }
      if (!db.objectStoreNames.contains("sessions_v2")) {
        db.createObjectStore("sessions_v2", { keyPath: "address" });
      }
      if (!db.objectStoreNames.contains("identities")) {
        db.createObjectStore("identities", { keyPath: "address" });
      }
      // Clean up legacy object stores
      for (const legacy of ["sessions", "opk_pool", "skipped_keys"]) {
        if (db.objectStoreNames.contains(legacy)) {
          db.deleteObjectStore(legacy);
        }
      }
    };
    req.onsuccess = (e) => {
      _db = e.target.result;
      preloadIdentities(_db)
        .then(() => resolve(_db))
        .catch((err) => reject(err));
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

// Identity

export async function getIdentity() {
  await openDB();
  return get(tx("identity"), "local");
}

export async function saveIdentity(identity) {
  await openDB();
  return put(tx("identity", "readwrite"), { id: "local", ...identity });
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
    const store = tx("messages");
    const index = store.index("chat_id");
    const results = [];
    const range = IDBKeyRange.only(chatId);
    const req = index.openCursor(range, "prev");
    req.onsuccess = (e) => {
      const cursor = e.target.result;
      if (!cursor || results.length >= limit) {
        results.sort((a, b) => a.created_at - b.created_at);
        resolve(results);
        return;
      }
      const msg = cursor.value;
      if (before === null || msg.created_at < before) {
        results.push(msg);
      }
      cursor.continue();
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
    const store = tx("messages");
    let maxId = 0;
    const req = store.openCursor();
    req.onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const id = Number(cursor.value.msg_id);
        if (!isNaN(id) && id > maxId) {
          maxId = id;
        }
        cursor.continue();
      } else {
        resolve(maxId);
      }
    };
    req.onerror = () => reject(req.error);
  });
}

export async function updateMessageDelivered(msgId, status) {
  const msg = await getMessage(msgId);
  if (!msg) return;
  msg.delivered = status;
  msg.delivery_status = status;
  await openDB();
  const store = tx("messages", "readwrite");
  return new Promise((resolve, reject) => {
    const putReq = store.put(msg);
    putReq.onsuccess = () => resolve();
    putReq.onerror = () => reject(putReq.error);
  });
}

// Contacts

export async function getContact(userId) {
  await openDB();
  return get(tx("contacts"), Number(userId));
}

export async function saveContact(contact) {
  await openDB();
  if (contact && contact.user_id !== undefined) {
    contact.user_id = Number(contact.user_id);
  }
  return put(tx("contacts", "readwrite"), contact);
}

export async function getAllContacts() {
  await openDB();
  return getAll(tx("contacts"));
}

export async function exportHistoryData() {
  const dump = await exportAllData();
  return {
    db_dump: dump,
  };
}

export async function importHistoryData(data) {
  if (data && data.db_dump) {
    await importAllData(data.db_dump);
  } else {
    throw new Error("Неверный формат резервной копии");
  }
}



export async function clearUserSessions(userId) {
  await openDB();
  const uIdStr = String(userId);

  // Clear from cache
  for (const addr of trustedIdentities.keys()) {
    if (addr.split(".")[0] === uIdStr) {
      trustedIdentities.delete(addr);
    }
  }
  
  // Clear sessions_v2
  await new Promise((resolve, reject) => {
    const transaction = _db.transaction(["sessions_v2"], "readwrite");
    const store = transaction.objectStore("sessions_v2");
    store.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const parts = cursor.value.address.split(".");
        if (parts[0] === uIdStr) {
          cursor.delete();
        }
        cursor.continue();
      }
    };
    transaction.oncomplete = () => resolve();
    transaction.onerror = (e) => reject(e.target.error);
  });

  // Clear identities
  await new Promise((resolve, reject) => {
    const transaction = _db.transaction(["identities"], "readwrite");
    const store = transaction.objectStore("identities");
    store.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const parts = cursor.value.address.split(".");
        if (parts[0] === uIdStr) {
          cursor.delete();
        }
        cursor.continue();
      }
    };
    transaction.oncomplete = () => resolve();
    transaction.onerror = (e) => reject(e.target.error);
  });
}

export async function deleteChatData(userId) {
  await openDB();
  const uId = Number(userId);
  const uIdStr = String(userId);

  return new Promise((resolve, reject) => {
    const transaction = _db.transaction(["contacts", "messages", "sessions_v2", "identities"], "readwrite");

    transaction.objectStore("contacts").delete(uId);

    const msgStore = transaction.objectStore("messages");
    const msgIndex = msgStore.index("chat_id");
    const msgRange = IDBKeyRange.only(uIdStr);
    msgIndex.openCursor(msgRange).onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        cursor.delete();
        cursor.continue();
      }
    };

    const sessionStore = transaction.objectStore("sessions_v2");
    sessionStore.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const parts = cursor.value.address.split(".");
        if (parts[0] === uIdStr) {
          cursor.delete();
        }
        cursor.continue();
      }
    };

    const identStore = transaction.objectStore("identities");
    identStore.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const parts = cursor.value.address.split(".");
        if (parts[0] === uIdStr) {
          cursor.delete();
        }
        cursor.continue();
      }
    };

    transaction.oncomplete = () => resolve();
    transaction.onerror = (e) => reject(e.target.error);
  });
}

export async function updateMsgId(oldId, newId) {
  await openDB();
  return new Promise((resolve, reject) => {
    const transaction = _db.transaction(["messages"], "readwrite");
    const store = transaction.objectStore("messages");
    const getReq = store.get(oldId);
    getReq.onsuccess = () => {
      const msg = getReq.result;
      if (!msg) {
        resolve();
        return;
      }
      const delReq = store.delete(oldId);
      delReq.onsuccess = () => {
        msg.msg_id = newId;
        if (!msg.client_msg_id) {
          msg.client_msg_id = oldId;
        }
        const putReq = store.put(msg);
        putReq.onsuccess = () => resolve();
        putReq.onerror = () => reject(putReq.error);
      };
      delReq.onerror = () => reject(delReq.error);
    };
    getReq.onerror = () => reject(getReq.error);
  });
}

export async function updateMsgIdAndDelivered(oldId, newId, deliveredStatus) {
  await openDB();
  return new Promise((resolve, reject) => {
    const transaction = _db.transaction(["messages"], "readwrite");
    const store = transaction.objectStore("messages");
    const getReq = store.get(oldId);
    getReq.onsuccess = () => {
      const msg = getReq.result;
      if (!msg) {
        resolve();
        return;
      }
      const delReq = store.delete(oldId);
      delReq.onsuccess = () => {
        msg.msg_id = newId;
        msg.delivered = deliveredStatus;
        msg.delivery_status = deliveredStatus;
        if (!msg.client_msg_id) {
          msg.client_msg_id = oldId;
        }
        const putReq = store.put(msg);
        putReq.onsuccess = () => resolve();
        putReq.onerror = () => reject(putReq.error);
      };
      delReq.onerror = () => reject(delReq.error);
    };
    getReq.onerror = () => reject(getReq.error);
  });
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
  trustedIdentities.clear();
  return new Promise((resolve, reject) => {
    const list = ["identity", "contacts", "messages", "pre_keys", "signed_pre_keys", "sessions_v2", "identities"];
    const transaction = _db.transaction(list, "readwrite");
    for (const s of list) {
      transaction.objectStore(s).clear();
    }
    transaction.oncomplete = () => resolve();
    transaction.onerror = (e) => reject(e.target.error);
  });
}

export class IndexedDBSignalStore {
  async getIdentityKeyPair() {
    const idData = await getIdentity();
    if (!idData) return undefined;
    return idData.identityKeyPair;
  }

  async getLocalRegistrationId() {
    const idData = await getIdentity();
    if (!idData) return undefined;
    return idData.registrationId;
  }

  isTrustedIdentity(identifier, identityKey, direction) {
    const existing = trustedIdentities.get(identifier);
    if (!existing) return true; // auto-trust first use per-device!
    
    const existingKey = new Uint8Array(existing);
    const newKey = new Uint8Array(identityKey);
    if (existingKey.length !== newKey.length) return false;
    for (let i = 0; i < existingKey.length; i++) {
      if (existingKey[i] !== newKey[i]) return false;
    }
    return true;
  }

  async saveIdentity(encodedAddress, publicKey) {
    trustedIdentities.set(encodedAddress, publicKey);
    await openDB();
    await new Promise((resolve, reject) => {
      const t = _db.transaction("identities", "readwrite");
      t.objectStore("identities").put({ address: encodedAddress, publicKey });
      t.oncomplete = () => resolve();
      t.onerror = (e) => reject(e.target.error);
    });
    return true;
  }

  async loadPreKey(keyId) {
    await openDB();
    const k = await new Promise((resolve, reject) => {
      const req = tx("pre_keys").get(Number(keyId));
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    if (!k) return undefined;
    return k.keyPair;
  }

  async storePreKey(keyId, keyPair) {
    await openDB();
    await new Promise((resolve, reject) => {
      const t = _db.transaction("pre_keys", "readwrite");
      t.objectStore("pre_keys").put({ keyId: Number(keyId), keyPair });
      t.oncomplete = () => resolve();
      t.onerror = (e) => reject(e.target.error);
    });
  }

  async removePreKey(keyId) {
    await openDB();
    await new Promise((resolve, reject) => {
      const t = _db.transaction("pre_keys", "readwrite");
      t.objectStore("pre_keys").delete(Number(keyId));
      t.oncomplete = () => resolve();
      t.onerror = (e) => reject(e.target.error);
    });
  }

  async storeSession(encodedAddress, record) {
    await openDB();
    await new Promise((resolve, reject) => {
      const t = _db.transaction("sessions_v2", "readwrite");
      t.objectStore("sessions_v2").put({ address: encodedAddress, record });
      t.oncomplete = () => resolve();
      t.onerror = (e) => reject(e.target.error);
    });
  }

  async loadSession(encodedAddress) {
    await openDB();
    const s = await new Promise((resolve, reject) => {
      const req = tx("sessions_v2").get(encodedAddress);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    if (!s) return undefined;
    return s.record;
  }

  async loadSignedPreKey(keyId) {
    await openDB();
    const k = await new Promise((resolve, reject) => {
      const req = tx("signed_pre_keys").get(Number(keyId));
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    if (!k) return undefined;
    return k.keyPair;
  }

  async storeSignedPreKey(keyId, keyPair, signature) {
    await openDB();
    await new Promise((resolve, reject) => {
      const t = _db.transaction("signed_pre_keys", "readwrite");
      t.objectStore("signed_pre_keys").put({ keyId: Number(keyId), keyPair, signature });
      t.oncomplete = () => resolve();
      t.onerror = (e) => reject(e.target.error);
    });
  }

  async removeSignedPreKey(keyId) {
    await openDB();
    await new Promise((resolve, reject) => {
      const t = _db.transaction("signed_pre_keys", "readwrite");
      t.objectStore("signed_pre_keys").delete(Number(keyId));
      t.oncomplete = () => resolve();
      t.onerror = (e) => reject(e.target.error);
    });
  }
}

export const signalStore = new IndexedDBSignalStore();

export async function getIdentitiesForUser(userId) {
  await openDB();
  const uIdStr = String(userId);
  return new Promise((resolve, reject) => {
    const list = [];
    const transaction = _db.transaction("identities", "readonly");
    const store = transaction.objectStore("identities");
    
    store.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        const addr = cursor.value.address;
        const parts = addr.split(".");
        if (parts[0] === uIdStr) {
          list.push(cursor.value);
        }
        cursor.continue();
      } else {
        resolve(list);
      }
    };
    transaction.onerror = () => reject(transaction.error);
  });
}

export async function getAnySession(userId) {
  await openDB();
  const uIdStr = String(userId);
  return new Promise((resolve, reject) => {
    const store = tx("sessions_v2");
    const req = store.openCursor();
    req.onsuccess = () => {
      const cursor = req.result;
      if (!cursor) { resolve(null); return; }
      const addr = cursor.value.address;
      const parts = addr.split(".");
      if (parts[0] === uIdStr) {
        resolve(cursor.value);
      } else {
        cursor.continue();
      }
    };
    req.onerror = () => reject(req.error);
  });
}

export async function getIdentityKeyForUser(userId) {
  const list = await getIdentitiesForUser(userId);
  if (list.length > 0) {
    return list[0].publicKey;
  }
  return null;
}



export async function getIdentityKey(encodedAddress) {
  const existing = trustedIdentities.get(encodedAddress);
  return existing || null;
}

export async function getSignedPreKeyRecord(keyId) {
  await openDB();
  return new Promise((resolve, reject) => {
    const req = tx("signed_pre_keys").get(Number(keyId));
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function exportAllData() {
  await openDB();
  const stores = ["contacts", "messages"];
  const dump = {};
  for (const sName of stores) {
    if (_db.objectStoreNames.contains(sName)) {
      dump[sName] = await new Promise((resolve) => {
        const transaction = _db.transaction(sName, "readonly");
        const req = transaction.objectStore(sName).getAll();
        req.onsuccess = () => resolve(req.result);
      });
    }
  }
  return dump;
}

export async function importAllData(dump) {
  await openDB();
  for (const sName in dump) {
    if (sName !== "contacts" && sName !== "messages") continue;
    if (_db.objectStoreNames.contains(sName)) {
      await new Promise((resolve, reject) => {
        const transaction = _db.transaction(sName, "readwrite");
        const store = transaction.objectStore(sName);
        store.clear().onsuccess = () => {
          let count = dump[sName].length;
          if (count === 0) {
            resolve();
            return;
          }
          for (const val of dump[sName]) {
            store.put(val).onsuccess = () => {
              count--;
              if (count === 0) resolve();
            };
          }
        };
        transaction.onerror = (e) => reject(e.target.error);
      });
    }
  }
}

export class BoundSignalStore {
  constructor(store, address) {
    this.store = store;
    this.address = address;
  }

  isTrustedIdentity(identifier, identityKey, direction) {
    // Override the identifier to be the full device address
    return this.store.isTrustedIdentity(this.address.toString(), identityKey, direction);
  }

  saveIdentity(encodedAddress, publicKey) {
    return this.store.saveIdentity(encodedAddress, publicKey);
  }

  getIdentityKeyPair() { return this.store.getIdentityKeyPair(); }
  getLocalRegistrationId() { return this.store.getLocalRegistrationId(); }
  loadPreKey(keyId) { return this.store.loadPreKey(keyId); }
  storePreKey(keyId, keyPair) { return this.store.storePreKey(keyId, keyPair); }
  removePreKey(keyId) { return this.store.removePreKey(keyId); }
  storeSession(encodedAddress, record) { return this.store.storeSession(encodedAddress, record); }
  loadSession(encodedAddress) { return this.store.loadSession(encodedAddress); }
  loadSignedPreKey(keyId) { return this.store.loadSignedPreKey(keyId); }
  storeSignedPreKey(keyId, keyPair, signature) { return this.store.storeSignedPreKey(keyId, keyPair, signature); }
  removeSignedPreKey(keyId) { return this.store.removeSignedPreKey(keyId); }
}

export function getPersistentDeviceName() {
  let instId = localStorage.getItem("installation_id");
  if (!instId) {
    instId = crypto.randomUUID();
    localStorage.setItem("installation_id", instId);
  }
  const browserName = navigator.userAgent.slice(0, 60);
  return `${browserName} [ID: ${instId}]`;
}

export { KeyHelper, SessionBuilder, SessionCipher, SignalProtocolAddress, replacer, reviver };
