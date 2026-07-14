import {
  decryptMessage, x3dhRespond, decodeKey, encodeKey,
  importX25519Priv, importX25519Pub, diffieHellman, generateDH, kdf_rk, kdf_ck
} from "./crypto.js";
import { ws } from "./ws.js";

const DB_NAME = "penik-messenger";
const DB_VERSION = 2;

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
      if (!db.objectStoreNames.contains("skipped_keys")) {
        const sk = db.createObjectStore("skipped_keys", { keyPath: ["user_id", "device_id", "dh_pub_hex", "n"] });
        sk.createIndex("session_time_idx", ["user_id", "device_id", "created_at"], { unique: false });
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
  const uId = Number(userId);
  const dId = Number(deviceId);
  const session = await get(tx("sessions"), [uId, dId]);
  if (session) {
    if (!session.root_key && session.sharedSecret) {
      // Legacy session: delete and return null
      await new Promise((resolve, reject) => {
        const store = tx("sessions", "readwrite");
        const req = store.delete([uId, dId]);
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
      return null;
    }
  }
  return session;
}

export async function saveSession(session) {
  await openDB();
  if (session) {
    session.user_id = Number(session.user_id);
    session.device_id = Number(session.device_id);
  }
  return put(tx("sessions", "readwrite"), session);
}

export async function saveSkippedKey(userId, deviceId, dhPubHex, n, keyBytes) {
  await openDB();
  const uId = Number(userId);
  const dId = Number(deviceId);
  
  return new Promise((resolve, reject) => {
    const transaction = _db.transaction(["skipped_keys"], "readwrite");
    const store = transaction.objectStore("skipped_keys");
    const index = store.index("session_time_idx");
    
    const range = IDBKeyRange.only([uId, dId]);
    const countReq = index.count(range);
    
    countReq.onsuccess = () => {
      const count = countReq.result;
      if (count >= 1000) {
        const cursorReq = index.openCursor(range, "next");
        cursorReq.onsuccess = (e) => {
          const cursor = e.target.result;
          if (cursor) {
            const delReq = cursor.delete();
            delReq.onsuccess = () => {
              writeKey();
            };
            delReq.onerror = () => reject(delReq.error);
          } else {
            writeKey();
          }
        };
        cursorReq.onerror = () => reject(cursorReq.error);
      } else {
        writeKey();
      }
    };
    countReq.onerror = () => reject(countReq.error);
    
    function writeKey() {
      const entry = {
        user_id: uId,
        device_id: dId,
        dh_pub_hex: dhPubHex,
        n: Number(n),
        key_bytes: keyBytes,
        created_at: Date.now()
      };
      const putReq = store.put(entry);
      putReq.onsuccess = () => resolve();
      putReq.onerror = () => reject(putReq.error);
    }
  });
}

export async function getAndRemoveSkippedKey(userId, deviceId, dhPubHex, n) {
  await openDB();
  const uId = Number(userId);
  const dId = Number(deviceId);
  const numN = Number(n);
  
  return new Promise((resolve, reject) => {
    const transaction = _db.transaction(["skipped_keys"], "readwrite");
    const store = transaction.objectStore("skipped_keys");
    
    const getReq = store.get([uId, dId, dhPubHex, numN]);
    getReq.onsuccess = () => {
      const entry = getReq.result;
      if (!entry) {
        resolve(null);
        return;
      }
      const delReq = store.delete([uId, dId, dhPubHex, numN]);
      delReq.onsuccess = () => resolve(entry.key_bytes);
      delReq.onerror = () => reject(delReq.error);
    };
    getReq.onerror = () => reject(getReq.error);
  });
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

export async function getOrEstablishReceiverSession(fromUserId, fromDeviceId, sessionInitEk, initialDHPub) {
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

  // 1. Perform X3DH respond to establish initial shared secret (SK)
  const rootKey = await x3dhRespond(ourIKPriv, ourSPKPriv, null, theirIKPub, sessionInitEk);

  // 2. Import Signed Prekey as our initial DH key pair
  const spkPriv = await importX25519Priv(identity.spk_priv_jwk);
  const theirDHPubImported = await importX25519Pub(initialDHPub);

  // 3. First DH-ratchet step
  const sharedSecret1 = await diffieHellman(spkPriv, theirDHPubImported);
  const step1 = await kdf_rk(rootKey, sharedSecret1, "DoubleRatchetRoot");
  let currentRootKey = step1.newRootKey;
  const recvChainKey = step1.chainKey;

  // 4. Generate new Bob's ephemeral key
  const newOurDH = await generateDH();
  const sharedSecret2 = await diffieHellman(newOurDH.privateKey, theirDHPubImported);
  const step2 = await kdf_rk(currentRootKey, sharedSecret2, "DoubleRatchetRoot");
  currentRootKey = step2.newRootKey;
  const sendChainKey = step2.chainKey;

  session = {
    user_id: Number(fromUserId),
    device_id: Number(fromDeviceId),
    root_key: currentRootKey,
    send_chain_key: sendChainKey,
    recv_chain_key: recvChainKey,
    our_dh_private_jwk: newOurDH.privJwk,
    our_dh_public_raw: newOurDH.pubRaw,
    their_dh_public_raw: initialDHPub,
    n_send: 0,
    n_recv: 0,
    pn: 0,
    created_at: Date.now()
  };
  await saveSession(session);
  return session;
}

export async function deleteChatData(userId) {
  await openDB();
  const uId = Number(userId);
  const uIdStr = String(userId);

  return new Promise((resolve, reject) => {
    const transaction = _db.transaction(["contacts", "messages", "sessions", "skipped_keys"], "readwrite");

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

    const sessionStore = transaction.objectStore("sessions");
    sessionStore.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        if (Number(cursor.value.user_id) === uId) {
          cursor.delete();
        }
        cursor.continue();
      }
    };

    const skStore = transaction.objectStore("skipped_keys");
    skStore.openCursor().onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) {
        if (Number(cursor.value.user_id) === uId) {
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
        const putReq = store.put(msg);
        putReq.onsuccess = () => resolve();
        putReq.onerror = () => reject(putReq.error);
      };
      delReq.onerror = () => reject(delReq.error);
    };
    getReq.onerror = () => reject(getReq.error);
  });
}

export async function clearIndexedDB() {
  await openDB();
  return new Promise((resolve, reject) => {
    const transaction = _db.transaction(["identity", "sessions", "contacts", "messages", "skipped_keys"], "readwrite");
    transaction.objectStore("identity").clear();
    transaction.objectStore("sessions").clear();
    transaction.objectStore("contacts").clear();
    transaction.objectStore("messages").clear();
    transaction.objectStore("skipped_keys").clear();
    transaction.oncomplete = () => resolve();
    transaction.onerror = (e) => reject(e.target.error);
  });
}
