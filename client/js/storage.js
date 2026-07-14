import { decryptMessage, x3dhRespond, decodeKey, encodeKey, importX25519Priv } from "./crypto.js";
import { ws } from "./ws.js";

const DB_NAME = "penik-messenger";
const DB_VERSION = 1;

let _db = null;

export function openDB() {
  if (_db) return Promise.resolve(_db);
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains("identity")) {
        db.createObjectStore("identity", { keyPath: "id" });
      }
      if (!db.objectStoreNames.contains("sessions")) {
        db.createObjectStore("sessions", { keyPath: ["user_id", "device_id"] });
      }
      if (!db.objectStoreNames.contains("messages")) {
        const ms = db.createObjectStore("messages", { keyPath: "msg_id" });
        ms.createIndex("chat_id", "chat_id", { unique: false });
      }
      if (!db.objectStoreNames.contains("contacts")) {
        db.createObjectStore("contacts", { keyPath: "user_id" });
      }
      if (!db.objectStoreNames.contains("opk_pool")) {
        db.createObjectStore("opk_pool", { keyPath: "id" });
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

// Identity

export async function getIdentity() {
  await openDB();
  return get(tx("identity"), "local");
}

export async function saveIdentity(identity) {
  await openDB();
  return put(tx("identity", "readwrite"), { id: "local", ...identity });
}

// Sessions

export async function getSession(userId, deviceId) {
  await openDB();
  return get(tx("sessions"), [userId, deviceId]);
}

export async function saveSession(session) {
  await openDB();
  return put(tx("sessions", "readwrite"), session);
}

export async function getAnySession(userId) {
  await openDB();
  return new Promise((resolve, reject) => {
    const store = tx("sessions");
    const req = store.openCursor();
    req.onsuccess = () => {
      const cursor = req.result;
      if (!cursor) { resolve(null); return; }
      if (String(cursor.value.user_id) === String(userId)) {
        resolve(cursor.value);
      } else {
        cursor.continue();
      }
    };
    req.onerror = () => reject(req.error);
  });
}

// Messages

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
        resolve(results.reverse());
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

export async function updateMessageDelivered(msgId, status) {
  await openDB();
  const store = tx("messages", "readwrite");
  return new Promise((resolve, reject) => {
    const req = store.get(msgId);
    req.onsuccess = () => {
      const msg = req.result;
      if (!msg) { resolve(); return; }
      msg.delivery_status = status;
      const putReq = store.put(msg);
      putReq.onsuccess = () => resolve();
      putReq.onerror = () => reject(putReq.error);
    };
    req.onerror = () => reject(req.error);
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

// OPK pool

export async function saveOPK(opk) {
  await openDB();
  return put(tx("opk_pool", "readwrite"), opk);
}

export async function saveOPKs(opks) {
  await openDB();
  for (const opk of opks) {
    await put(tx("opk_pool", "readwrite"), {
      id:      opk.pubRaw ? Array.from(opk.pubRaw).slice(0, 8).join('-') : crypto.randomUUID(),
      privJwk: opk.privJwk,
      pubRaw:  opk.pubRaw,
    });
  }
}

export async function getAndRemoveOPK(id) {
  await openDB();
  return new Promise((resolve, reject) => {
    const store = tx("opk_pool", "readwrite");
    const req = store.get(id);
    req.onsuccess = () => {
      const opk = req.result;
      if (!opk) { resolve(null); return; }
      const delReq = store.delete(id);
      delReq.onsuccess = () => resolve(opk);
      delReq.onerror = () => reject(delReq.error);
    };
    req.onerror = () => reject(req.error);
  });
}

export async function countOPKs() {
  await openDB();
  return new Promise((resolve, reject) => {
    const req = tx("opk_pool").count();
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function exportIdentityData() {
  const identity = await getIdentity();
  if (!identity) return null;
  return {
    ik_priv_jwk: identity.ik_priv_jwk,
    ik_pub_raw: Array.from(new Uint8Array(identity.ik_pub_raw)),
    sig_priv_jwk: identity.sig_priv_jwk,
    sig_pub_raw: identity.sig_pub_raw ? Array.from(new Uint8Array(identity.sig_pub_raw)) : null,
    spk_priv_jwk: identity.spk_priv_jwk,
    spk_pub_raw: Array.from(new Uint8Array(identity.spk_pub_raw)),
    spk_sig: Array.from(new Uint8Array(identity.spk_sig)),
    user_id: identity.user_id,
  };
}

export async function importIdentityData(data) {
  if (!data.ik_priv_jwk || !data.ik_pub_raw || !data.spk_priv_jwk || !data.spk_pub_raw || !data.spk_sig) {
    throw new Error("Неверный формат резервной копии");
  }
  const identity = {
    ik_priv_jwk: data.ik_priv_jwk,
    ik_pub_raw: new Uint8Array(data.ik_pub_raw),
    sig_priv_jwk: data.sig_priv_jwk,
    sig_pub_raw: data.sig_pub_raw ? new Uint8Array(data.sig_pub_raw) : null,
    spk_priv_jwk: data.spk_priv_jwk,
    spk_pub_raw: new Uint8Array(data.spk_pub_raw),
    spk_sig: new Uint8Array(data.spk_sig),
    user_id: data.user_id,
  };
  await saveIdentity(identity);
  return identity;
}

export async function getOrEstablishReceiverSession(fromUserId, fromDeviceId, theirEKPub) {
  let session = await getSession(fromUserId, fromDeviceId);
  if (session) return session;

  const keyBundle = await ws.request(0x10, { user_id: Number(fromUserId) }, 0x11);
  if (!keyBundle || !keyBundle.devices) {
    throw new Error("Не удалось загрузить ключи отправителя");
  }

  const device = keyBundle.devices.find(d => Number(d.device_id) === Number(fromDeviceId));
  if (!device) {
    throw new Error("Устройство отправителя не найдено в ключевом бандле");
  }

  const identity = await getIdentity();
  if (!identity) throw new Error("Нет локального ключа идентификации");

  const ourIKPriv = await importX25519Priv(identity.ik_priv_jwk);
  const ourSPKPriv = await importX25519Priv(identity.spk_priv_jwk);

  let theirIKPub = device.ik_pub instanceof Uint8Array ? device.ik_pub : decodeKey(device.ik_pub);
  if (theirIKPub.length === 64) {
    theirIKPub = theirIKPub.slice(0, 32);
  }

  const sharedSecret = await x3dhRespond(ourIKPriv, ourSPKPriv, null, theirIKPub, theirEKPub);

  session = {
    user_id: fromUserId,
    device_id: fromDeviceId,
    sharedSecret,
    ekPubB64: encodeKey(theirEKPub),
    created_at: Date.now()
  };
  await saveSession(session);
  return session;
}

export async function clearIndexedDB() {
  await openDB();
  return new Promise((resolve, reject) => {
    const transaction = _db.transaction(["identity", "sessions", "contacts", "messages"], "readwrite");
    transaction.objectStore("identity").clear();
    transaction.objectStore("sessions").clear();
    transaction.objectStore("contacts").clear();
    transaction.objectStore("messages").clear();
    transaction.oncomplete = () => resolve();
    transaction.onerror = (e) => reject(e.target.error);
  });
}
