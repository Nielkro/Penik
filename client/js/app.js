import { getToken, setToken, getUserById, apiGet, apiPost } from './api.js';
import {
  openDB, saveMessage, updateMessageRead,
  saveContact, getContact, updateMessageDelivered, clearIndexedDB,
  updateMsgId, updateMsgIdAndDelivered, getMessage, getAllContacts, getAllMessages,
  findAndResolvePendingSentMessage, deleteChatData, savePreKeyPrivate,
  getPreKeyPrivate, deletePreKeyPrivate
} from './storage.js';
import { ws } from './ws.js';
import { renderAuth } from './ui/auth.js';
import { renderChatList, renderChat } from './ui/chat.js';
import { renderProfile } from './ui/profile.js';
import { renderSearch } from './ui/search.js';
import {
  deriveSharedSecret, hkdfDerive, chacha20Poly1305Encrypt, chacha20Poly1305Decrypt,
  encryptKeyBackup, decryptKeyBackup
} from './crypto.js';

function u8ToHex(arr) {
  return Array.from(arr).map(b => b.toString(16).padStart(2, "0")).join("");
}

function read32BE(buf, offset) {
  const view = new DataView(buf.buffer, buf.byteOffset + offset, 4);
  return view.getUint32(0, false);
}

export const pendingAcks = new Map();

async function ensurePreKeyPool() {
  if (!getToken()) return;
  try {
    const status = await apiGet("/keys/prekeys/status");
    const minPool = 5;
    const poolSize = 20;
    
    if (status && status.available < minPool) {
      const count = poolSize - status.available;
      const prekeys = [];
      const uploadItems = [];
      
      for (let i = 0; i < count; i++) {
        const keyPair = await window.crypto.subtle.generateKey(
          { name: "X25519" },
          true,
          ["deriveBits"]
        );
        const pub = new Uint8Array(await window.crypto.subtle.exportKey("raw", keyPair.publicKey));
        const privFull = new Uint8Array(await window.crypto.subtle.exportKey("pkcs8", keyPair.privateKey));
        const priv = privFull.slice(privFull.length - 32);
        
        const keyId = window.crypto.getRandomValues(new Uint32Array(1))[0];
        const pubB64 = btoa(String.fromCharCode(...pub));
        
        prekeys.push({ keyId, privateKey: priv });
        uploadItems.push({ key_id: keyId, public_key: pubB64 });
      }
      
      for (const otpk of prekeys) {
        await savePreKeyPrivate(otpk.keyId, otpk.privateKey);
      }

      try {
        await apiPost("/keys/prekeys", { prekeys: uploadItems });
      } catch (err) {
        for (const otpk of prekeys) {
          await deletePreKeyPrivate(otpk.keyId);
        }
        throw err;
      }
      console.log(`Successfully replenished ${count} OTPKs on server.`);
    }
  } catch (err) {
    console.error("Failed to check/replenish OTPKs:", err);
  }
}

/* ── App state ── */
export const state = {
  currentUser: null,
  privateIK: null,
  retryCounters: new Map(), // msg_id -> retry attempt count
  pendingPrekeyDeletes: new Map(), // msg_id -> prekeyId, delete OTPK only after server ack
};

export function getCurrentUser() { return state.currentUser; }
export function setCurrentUser(u) { state.currentUser = u; }
export function getWS() { return ws; }

/* ── Navigation ── */
const routes = {
  '#login':    () => showAuth('login'),
  '#register': () => showAuth('register'),
  '#chats':    () => showMain('chats'),
  '#search':   () => showMain('search'),
  '#profile':  () => showMain('profile'),
};

function parseHash() {
  const hash = location.hash || '';
  if (hash.startsWith('#chat/')) return { screen: 'chat', userId: hash.slice(6) };
  return { screen: hash || '#chats' };
}

function navigate(hash) {
  location.hash = hash;
}

export { navigate };

/* ── Layout ── */
let _mainLayout = null;

function buildAuthLayout(mode) {
  const app = document.getElementById('app');
  app.innerHTML = '';
  const screen = document.createElement('div');
  screen.className = 'screen auth-screen active';
  screen.id = 'screen-auth';
  app.appendChild(screen);
  renderAuth(screen, mode);
}

function buildMainLayout() {
  if (_mainLayout) return _mainLayout;

  const app = document.getElementById('app');
  app.innerHTML = '';

  const wrap = document.createElement('div');
  wrap.id = 'main-wrap';
  wrap.style.cssText = 'display:flex;flex:1;overflow:hidden;height:100%';

  /* Nav bar */
  const nav = document.createElement('nav');
  nav.className = 'nav-bar';
  nav.innerHTML = `
    <button class="nav-item" data-screen="chats">
      <span class="nav-icon">💬</span>
      <span>Чаты</span>
    </button>
    <button class="nav-item" data-screen="search">
      <span class="nav-icon">🔍</span>
      <span>Поиск</span>
    </button>
    <button class="nav-item" data-screen="profile">
      <span class="nav-icon">👤</span>
      <span>Профиль</span>
    </button>
  `;

  nav.querySelectorAll('.nav-item').forEach(btn => {
    btn.addEventListener('click', () => navigate('#' + btn.dataset.screen));
  });

  /* Screens container */
  const screensWrap = document.createElement('div');
  screensWrap.id = 'screens-wrap';
  screensWrap.style.cssText = 'flex:1;overflow:hidden;display:flex;position:relative';

  const chatListScreen = document.createElement('div');
  chatListScreen.className = 'screen chatlist-screen';
  chatListScreen.id = 'screen-chats';

  const chatScreen = document.createElement('div');
  chatScreen.className = 'screen chat-screen';
  chatScreen.id = 'screen-chat';

  const searchScreen = document.createElement('div');
  searchScreen.className = 'screen search-screen';
  searchScreen.id = 'screen-search';

  const profileScreen = document.createElement('div');
  profileScreen.className = 'screen profile-screen';
  profileScreen.id = 'screen-profile';

  screensWrap.append(chatListScreen, chatScreen, searchScreen, profileScreen);
  wrap.append(screensWrap, nav);
  app.appendChild(wrap);

  _mainLayout = { chatListScreen, chatScreen, searchScreen, profileScreen, nav };
  return _mainLayout;
}

let _chatListRendered = false;

function showAuth(mode) {
  _mainLayout = null;
  _chatListRendered = false;
  buildAuthLayout(mode);
}

function showMain(screen, userId) {
  const layout = buildMainLayout();

  /* Update nav */
  layout.nav.querySelectorAll('.nav-item').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.screen === screen);
  });

  /* Hide all screens */
  ['chats', 'chat', 'search', 'profile'].forEach(s => {
    const el = document.getElementById(`screen-${s}`);
    if (el) el.classList.remove('active');
  });

  if (screen === 'chat' && userId) {
    layout.chatScreen.classList.add('active');
    layout.chatScreen.innerHTML = '';
    renderChat(layout.chatScreen, userId);
    /* Also show chat list on wide screens */
    if (window.innerWidth >= 700) {
      layout.chatListScreen.classList.add('active');
      if (!_chatListRendered) {
        _chatListRendered = true;
        renderChatList(layout.chatListScreen);
      }
    }
  } else if (screen === 'chats') {
    layout.chatListScreen.classList.add('active');
    if (!_chatListRendered) {
      _chatListRendered = true;
      renderChatList(layout.chatListScreen);
    }
    if (window.innerWidth >= 700) {
      /* Keep chat screen open on desktop */
      const chatEl = document.getElementById('screen-chat');
      if (chatEl && chatEl.innerHTML.trim()) chatEl.classList.add('active');
    }
  } else if (screen === 'search') {
    layout.searchScreen.classList.add('active');
    layout.searchScreen.innerHTML = '';
    renderSearch(layout.searchScreen);
  } else if (screen === 'profile') {
    layout.profileScreen.classList.add('active');
    layout.profileScreen.innerHTML = '';
    renderProfile(layout.profileScreen);
  }
}

/* ── Router ── */
function handleRoute() {
  const { screen, userId } = parseHash();

  if (!getToken()) {
    if (screen !== '#register') showAuth('login');
    else showAuth('register');
    return;
  }

  // Ensure WS is connected if we are logged in
  if (!ws.connected && (!ws._ws || ws._ws.readyState === WebSocket.CLOSED || ws._ws.readyState === WebSocket.CLOSING)) {
    ws.connect();
    ensurePreKeyPool();
  }

  if (screen === '#login' || screen === '#register') {
    navigate('#chats');
    return;
  }

  if (screen === 'chat') {
    showMain('chat', userId);
  } else {
    const key = screen.startsWith('#') ? screen : '#' + screen;
    const cleanKey = key.replace('#', '');
    showMain(cleanKey || 'chats');
  }
}

/* ── Bootstrap ── */
async function boot() {
  localStorage.removeItem("penik_sign_jwk");
  await openDB();
  setupGlobalWSListeners();

  const token = getToken();
  if (token) {
    try {
      let localUserId = localStorage.getItem("user_id");

      if (localUserId) {
        const user = await getUserById(localUserId);
        if (user) {
          user.user_id = user.id;
          user.username = user.nickname;
          setCurrentUser(user);
        } else {
          logout();
          return;
        }
      } else {
        logout();
        return;
      }
      ws.connect();
      ensurePreKeyPool();
    } catch (err) {
      console.error('Failed to fetch current user on boot:', err);
      if (err.status === 401 || err.status === 400 || err.status === 404) {
        logout();
        return;
      }
    }
  }

  const loading = document.getElementById('loading');
  if (loading) loading.remove();

  window.addEventListener('hashchange', handleRoute);
  handleRoute();
}

boot().catch(err => {
  console.error('Boot error:', err);
  document.getElementById('app').innerHTML =
    `<div style="color:#e05252;padding:24px;text-align:center">Не удалось запустить: ${err.message}</div>`;
});

/* ── Exports for use by UI modules ── */
export async function logout() {
  ws.disconnect();
  setToken(null);
  localStorage.removeItem("user_id");
  localStorage.removeItem("device_id");
  localStorage.removeItem("penik_sign_jwk");
  state.currentUser = null;
  _mainLayout = null;
  _chatListRendered = false;
  setActiveChatCallback(null);
  setChatListUpdateCallback(null);
  try {
    await clearIndexedDB();
  } catch (error) {
    console.error("Failed to clear local data on logout:", error);
  }
  navigate('#login');
}

let _activeChatCallback = null;
let _chatListUpdateCallback = null;

export function setActiveChatCallback(userId, fn, onAck) {
  _activeChatCallback = userId ? { userId, fn, onAck } : null;
}

export function setChatListUpdateCallback(cb) {
  _chatListUpdateCallback = cb;
}

export function triggerChatListUpdate() {
  if (_chatListUpdateCallback) {
    _chatListUpdateCallback();
  }
}

async function onMsgRecvGlobal(payload) {
  const currentDeviceId = Number(localStorage.getItem("device_id"));
  if (payload.recipient_device_id != null &&
      Number(payload.recipient_device_id) !== currentDeviceId) {
    console.warn("Ignoring message addressed to another device", {
      msgId: payload.msg_id,
      recipientDeviceId: payload.recipient_device_id,
      currentDeviceId
    });
    return;
  }
  const fromUserId = Number(payload.from_user_id);
  
  let decryptSuccess = true;
  let plaintext = "";
  let usedPrekeyId = null;
  if (payload.plaintext) {
    plaintext = payload.plaintext;
  } else if (payload.ciphertext) {
    try {
      // The server may replay a message after reconnect/reload.  OTPKs are
      // one-time keys, so never decrypt the same message twice: the plaintext
      // saved during the first delivery is the authoritative copy.
      const existing = payload.msg_id ? await getMessage(payload.msg_id) : null;
      if (existing?.plaintext &&
          !existing.plaintext.startsWith('[Ошибка расшифрования')) {
        plaintext = existing.plaintext;
      } else {
        const result = await decryptMessagePayload(payload);
        plaintext = result.text;
        usedPrekeyId = result.prekeyId;
      }
    } catch (e) {
      plaintext = `[Ошибка расшифрования сообщения: ${e.message}]`;
      decryptSuccess = false;
    }
  }

  const chatPartnerId = payload.chat_user_id || fromUserId;
  const inMsg = {
    msg_id: payload.msg_id,
    chat_id: String(chatPartnerId),
    sender_id: fromUserId,
    plaintext,
    created_at: payload.ts ? payload.ts * 1000 : Date.now(),
    delivered: 1,
  };

  await saveMessage(inMsg);

  let contact = await getContact(chatPartnerId);
  if (!contact) {
    try {
      const res = await getUserById(String(chatPartnerId));
      contact = res.user || res;
    } catch {
      contact = { user_id: chatPartnerId, name: "Неизвестный", nickname: "" };
    }
  }

  await saveContact({
    ...contact,
    user_id: chatPartnerId,
    last_message: plaintext,
    last_ts: inMsg.created_at,
  });

  if (ws) {
    if (decryptSuccess) {
      if (usedPrekeyId) {
        state.pendingPrekeyDeletes.set(String(payload.msg_id), usedPrekeyId);
      }
      ws.send(0x04, { msg_id: payload.msg_id });
      state.retryCounters.delete(payload.msg_id);
    } else if (payload.from_device_id && payload.msg_id) {
      const msgKey = String(payload.msg_id);
      const attempts = state.retryCounters.get(msgKey) || 0;
      if (attempts < 2) {
        state.retryCounters.set(msgKey, attempts + 1);
        console.log(`onMsgRecvGlobal: decryption failed, requesting retry for msg ${payload.msg_id} from device ${payload.from_device_id} (attempt ${attempts + 1}/2)`);
        ws.send(0x16, {
          sender_device_id: Number(payload.from_device_id),
          requester_device_id: Number(localStorage.getItem("device_id")),
          msg_id: Number(payload.msg_id)
        });
      } else {
        console.warn(`onMsgRecvGlobal: giving up on msg ${payload.msg_id} after 2 retry attempts`);
      }
    }
  }

  if (_activeChatCallback && String(_activeChatCallback.userId) === String(chatPartnerId)) {
    _activeChatCallback.fn(inMsg);
  }

  if (_chatListUpdateCallback) {
    _chatListUpdateCallback();
  }
}

async function onMsgAckGlobal(payload) {
  try {
    await updateMessageDelivered(payload.msg_id, 1);
  } catch (e) {}

  const msgId = String(payload.msg_id);
  const prekeyId = state.pendingPrekeyDeletes.get(msgId);
  if (prekeyId) {
    state.pendingPrekeyDeletes.delete(msgId);
    try { await deletePreKeyPrivate(prekeyId); } catch (e) {}
  }

  if (_activeChatCallback) {
    _activeChatCallback.onAck(payload.msg_id);
  }
}

async function onMsgDeliveredGlobal(payload) {
  if (!payload?.msg_id) return;
  await updateMessageDelivered(payload.msg_id, 1);
  if (_activeChatCallback) _activeChatCallback.onAck?.(payload.msg_id);
}

async function onOfflineBatchGlobal(payload) {
  if (payload && payload.msgs && Array.isArray(payload.msgs)) {
    for (const msg of payload.msgs) {
      try {
        await onMsgRecvGlobal(msg);
      } catch (err) {
        console.error("Error processing offline message in batch:", err);
      }
    }
  }
}

async function onChatPurgeGlobal(payload) {
  const chatUserId = payload && (payload.chat_user_id ?? payload.chatUserId);
  if (chatUserId === undefined || chatUserId === null) return;
  try {
    await deleteChatData(chatUserId);
    // Ack so the server can hard-delete its tombstoned rows.
    ws.send(0x09, { chat_user_id: Number(chatUserId) });

    // If the wiped chat is open, clear the view.
    if (_activeChatCallback && String(_activeChatCallback.userId) === String(chatUserId)) {
      const chatEl = document.getElementById('screen-chat');
      if (chatEl) chatEl.innerHTML = '';
      navigate("#chats");
    }
    triggerChatListUpdate();
  } catch (err) {
    console.error("Failed to apply chat purge:", err);
  }
}

async function onMsgRetryReq(payload) {
  const msgId = payload.msg_id;
  const msg = await getMessage(msgId);
  if (!msg) {
    console.error(`onMsgRetryReq: message ${msgId} not found locally`);
    return;
  }

  const recipientUserId = Number(msg.chat_id);
  const text = msg.plaintext;
  
  if (!text) {
    console.error(`onMsgRetryReq: message ${msgId} has no plaintext locally`);
    return;
  }

  console.log(`onMsgRetryReq: re-encrypting message ${msgId} for user ${recipientUserId}`);
  const payloads = await encryptMessagePayload(text, recipientUserId);
  
  const targetPayload = payloads.find(p => Number(p.device_id) === Number(payload.requester_device_id));
  if (!targetPayload) {
    console.error(`onMsgRetryReq: target device ${payload.requester_device_id} not found in re-encrypted payloads`);
    return;
  }

  if (ws) {
    ws.send(0x17, {
      msg_id: Number(msgId),
      prekey_id: targetPayload.prekey_id ? Number(targetPayload.prekey_id) : 0,
      ciphertext: targetPayload.ciphertext,
      salt: targetPayload.salt,
      nonce: targetPayload.nonce
    });
  }
}

async function onMsgAckReceivedGlobal(payload) {
  const serverMsgId = payload.msg_id;
  if (!serverMsgId) return;

  const clientMsgId = payload.client_msg_id || serverMsgId;
  const pending = pendingAcks.get(String(clientMsgId));
  if (!pending) return;
  pendingAcks.delete(String(clientMsgId));

  try {
    // Server stored the message (single check). Real delivery (double check)
    // arrives later via MSG_DELIVERED once the recipient's device receives it.
    await updateMsgIdAndDelivered(pending.tempId, serverMsgId, 0);

    if (_activeChatCallback && String(_activeChatCallback.userId) === String(pending.userId)) {
      const bubble = document.querySelector(`[data-msg-id="${pending.tempId}"]`);
      if (bubble) {
        bubble.dataset.msgId = serverMsgId;
        const statusEl = bubble.querySelector(".msg-status");
        if (statusEl) {
          statusEl.dataset.msgId = serverMsgId;
        }
      }
    }
  } catch (err) {
    console.error("Failed to process MSG_ACK:", err);
  }
}

async function onMsgReadGlobal(payload) {
  if (!payload?.msg_id) return;
  await updateMessageRead(payload.msg_id);
  if (_activeChatCallback) _activeChatCallback.onStatus?.(payload.msg_id, "read");
}

export async function syncMessageHistory() {
  try {
    const limit = 100;
    const history = await apiGet(`/messages/history?limit=${limit}`);
    if (!history || !Array.isArray(history) || history.length === 0) return;

    const me = state.currentUser;
    if (!me) return;
    const myId = Number(me.id || me.user_id);

    history.sort((a, b) => a.timestamp - b.timestamp);

    for (const item of history) {
      // History is device-scoped.  Never try to decrypt a fan-out copy that
      // belongs to another device of the same account (for example, the
      // phone copy while this browser is the web device).  Such a copy uses
      // that device's OTPK and can never be decrypted here.
      const currentDeviceId = Number(localStorage.getItem("device_id"));
      // A fan-out row addressed to the phone must never be processed by the
      // web client, even when the message was sent from this web client.
      // The sender's copy is encrypted with the recipient device's OTPK.
      const myId = Number(state.currentUser?.id || state.currentUser?.user_id);
      const isSelfChat = Number(item.sender_id) === myId &&
        Number(item.recipient_id) === myId;
      const belongsToThisDevice = isSelfChat || Number(item.sender_id) !== myId
        ? Number(item.recipient_device_id) === currentDeviceId
        : Number(item.sender_device_id) === currentDeviceId;
      if (!belongsToThisDevice) {
        continue;
      }
      const existing = await getMessage(item.id);
      if (existing && existing.plaintext &&
          !existing.plaintext.startsWith('[Ошибка расшифрования')) {
        continue;
      }

      const peerId = Number(item.chat_user_id || (Number(item.sender_id) === myId ? item.recipient_id : item.sender_id));

      let text = "";
      if (item.plaintext) {
        text = item.plaintext;
      } else if (item.ciphertext) {
        try {
          // History can contain a message that was already received live and
          // decrypted.  In that case the OTPK may have been consumed already;
          // use the locally persisted plaintext instead of trying to decrypt
          // the ciphertext a second time after a reload.
          const locallyStored = await getMessage(item.id);
          if (locallyStored && locallyStored.plaintext &&
              !locallyStored.plaintext.startsWith('[Ошибка расшифрования')) {
            text = locallyStored.plaintext;
            throw { __alreadyDecrypted: true };
          }
          const senderBundle = await apiGet(`/keys/bundle/${item.sender_id}`);
          const senderDevice = senderBundle?.devices?.find(d => Number(d.device_id) === Number(item.sender_device_id));
          const fromIdentityKey = senderDevice?.identity_key;

          const decrypted = await decryptMessagePayload({
            ciphertext: item.ciphertext,
            salt: item.encryption_salt,
            nonce: item.encryption_nonce,
            prekey_id: item.prekey_id,
            from_identity_key: fromIdentityKey
          });
          text = decrypted.text;
        } catch (e) {
          if (e?.__alreadyDecrypted) continue;
          text = `[Ошибка расшифрования: ${e.message}]`;
        }
      }

      let contact = await getContact(peerId);
      if (!contact) {
        try {
          const res = await getUserById(String(peerId));
          contact = res.user || res;
        } catch (e) {
          console.error("Failed to fetch contact details for syncing:", e);
          contact = { user_id: peerId, name: "Неизвестный", nickname: "" };
        }
      }
      await saveContact({
        ...contact,
        user_id: peerId,
        last_message: text,
        last_ts: item.timestamp * 1000
      });

      const existingMsg = await getMessage(item.id);
      if (existingMsg) continue;

      if (Number(item.sender_id) === myId) {
        const resolved = item.client_msg_id
          ? await updateMsgIdAndDelivered(item.client_msg_id, item.id, item.delivered)
          : await findAndResolvePendingSentMessage(peerId, item.timestamp, item.id);
        if (resolved) continue;
      }

      const storedMsg = {
        msg_id: item.id,
        chat_id: String(peerId),
        sender_id: Number(item.sender_id),
        plaintext: text,
        created_at: item.timestamp * 1000,
        delivered: 1
      };
      await saveMessage(storedMsg);
    }

    triggerChatListUpdate();
  } catch (err) {
    console.error("Failed to sync message history:", err);
  }
}

export async function decryptMessagePayload(payload) {
  const toUint8Array = (val) => {
    if (!val) return new Uint8Array(0);
    if (val instanceof Uint8Array) {
      let isBase64Ascii = val.length > 0 && val.length % 4 === 0;
      if (isBase64Ascii) {
        for (let i = 0; i < val.length; i++) {
          const b = val[i];
          if (!((b >= 65 && b <= 90) || (b >= 97 && b <= 122) || (b >= 48 && b <= 57) || b === 43 || b === 47 || b === 61)) {
            isBase64Ascii = false;
            break;
          }
        }
      }
      if (isBase64Ascii) {
        const str = String.fromCharCode(...val);
        try {
          const bin = atob(str);
          const out = new Uint8Array(bin.length);
          for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
          return out;
        } catch (_) {}
      }
      return val;
    }
    if (typeof val === "string") {
      const bin = atob(val);
      const out = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    }
    return new Uint8Array(0);
  };

  const ciphertext = toUint8Array(payload.ciphertext);
  const salt = toUint8Array(payload.salt);
  const nonce = toUint8Array(payload.nonce);
  const fromIdentityKey = toUint8Array(payload.from_identity_key);
  const prekeyId = payload.prekey_id;

  let myPrivateIK;
  if (prekeyId) {
    myPrivateIK = await getPreKeyPrivate(prekeyId);
    if (!myPrivateIK) {
      throw new Error(`OTPK private key not found locally for id: ${prekeyId}`);
    }
  } else {
    myPrivateIK = state.privateIK;
    if (!myPrivateIK) {
      const stored = localStorage.getItem("penik_ik_priv");
      if (stored) {
        state.privateIK = new Uint8Array(atob(stored).split("").map(c => c.charCodeAt(0)));
        myPrivateIK = state.privateIK;
      }
    }
    if (!myPrivateIK) {
      throw new Error("Private Identity Key not found in memory/localStorage");
    }
  }

  let secret;
  if (prekeyId) {
    const dh1 = await deriveSharedSecret(myPrivateIK, fromIdentityKey);
    let myIKPriv = state.privateIK;
    if (!myIKPriv) {
      const stored = localStorage.getItem("penik_ik_priv");
      if (stored) {
        state.privateIK = new Uint8Array(atob(stored).split("").map(c => c.charCodeAt(0)));
        myIKPriv = state.privateIK;
      }
    }
    if (!myIKPriv) {
      throw new Error("Private Identity Key not found in memory/localStorage");
    }
    const dh2 = await deriveSharedSecret(myIKPriv, fromIdentityKey);
    secret = new Uint8Array(dh1.length + dh2.length);
    secret.set(dh1, 0);
    secret.set(dh2, dh1.length);
  } else {
    secret = await deriveSharedSecret(myPrivateIK, fromIdentityKey);
  }

  const info = new TextEncoder().encode("PenikE2EE");
  const derivedKey = await hkdfDerive(salt, secret, info, 32);

  const plaintextBytes = await chacha20Poly1305Decrypt(derivedKey, nonce, ciphertext);

  return { text: new TextDecoder().decode(plaintextBytes), prekeyId };
}

export async function encryptMessagePayload(text, recipientUserId) {
  const myId = Number(localStorage.getItem("user_id"));
  const myDeviceId = Number(localStorage.getItem("device_id"));
  const isSelfChat = Number(recipientUserId) === myId;

  const bundleUrl = isSelfChat
    ? `/keys/bundle/${recipientUserId}?skip_otk=true`
    : `/keys/bundle/${recipientUserId}`;
  const senderBundleUrl = `/keys/bundle/${myId}?skip_otk=true`;

  const recipientBundle = await apiGet(bundleUrl);
  const senderBundle = await apiGet(senderBundleUrl);

  const recipientDevices = recipientBundle?.devices || [];
  const senderDevices = senderBundle?.devices || [];

    const filteredSenderDevices = isSelfChat ? [] : senderDevices.filter(d => Number(d.device_id) !== myDeviceId);
    const allDevices = [...recipientDevices, ...filteredSenderDevices];

  let myPrivateIK = state.privateIK;
  if (!myPrivateIK) {
    const stored = localStorage.getItem("penik_ik_priv");
    if (stored) {
      state.privateIK = new Uint8Array(atob(stored).split("").map(c => c.charCodeAt(0)));
      myPrivateIK = state.privateIK;
    }
  }
  if (!myPrivateIK) {
    throw new Error("Private Identity Key not found in memory/localStorage");
  }

  const payloads = [];
  for (const device of allDevices) {
    const recipientIKPub = new Uint8Array(atob(device.identity_key).split("").map(c => c.charCodeAt(0)));

    let secret;
    if (device.one_time_key && device.key_id) {
      const recipientOTPKPub = new Uint8Array(atob(device.one_time_key).split("").map(c => c.charCodeAt(0)));
      const dh1 = await deriveSharedSecret(myPrivateIK, recipientOTPKPub);
      const dh2 = await deriveSharedSecret(myPrivateIK, recipientIKPub);

      secret = new Uint8Array(dh1.length + dh2.length);
      secret.set(dh1, 0);
      secret.set(dh2, dh1.length);
    } else {
      secret = await deriveSharedSecret(myPrivateIK, recipientIKPub);
    }

    const salt = window.crypto.getRandomValues(new Uint8Array(32));
    const nonce = window.crypto.getRandomValues(new Uint8Array(12));

    const info = new TextEncoder().encode("PenikE2EE");
    const derivedKey = await hkdfDerive(salt, secret, info, 32);

    const ciphertext = await chacha20Poly1305Encrypt(derivedKey, nonce, new TextEncoder().encode(text));

    payloads.push({
      device_id: Number(device.device_id),
      prekey_id: device.key_id ? Number(device.key_id) : null,
      ciphertext: ciphertext,
      salt: salt,
      nonce: nonce
    });
  }

  return payloads;
}

export async function flushOutbox() {
  const me = state.currentUser;
  if (!me) return;
  const myId = Number(me.id || me.user_id);
  try {
    const allMsgs = await getAllMessages();
    const unsent = allMsgs.filter(m => String(m.sender_id) === String(myId) && m.delivered === 0 && m.ciphertexts);
    for (const msg of unsent) {
      const clientMsgId = msg.client_msg_id || String(msg.msg_id);
      if (!pendingAcks.has(clientMsgId)) {
        pendingAcks.set(clientMsgId, { tempId: msg.msg_id, userId: msg.chat_id });
      }
      const seen = new Set();
      const uniqueDevices = (msg.ciphertexts || []).filter(d => {
        const id = Number(d.device_id);
        if (seen.has(id)) return false;
        seen.add(id);
        return true;
      });
      const sent = ws.send(0x01, {
        to_user_id: Number(msg.chat_id),
        devices: uniqueDevices,
        msg_id: clientMsgId,
      });
      if (!sent) {
        pendingAcks.delete(clientMsgId);
        break;
      }
    }
  } catch (e) {
    console.warn("Failed to flush outbox:", e);
  }
}



function setupGlobalWSListeners() {
  ws.on(0x02, onMsgRecvGlobal);
  ws.on(0x16, onMsgRetryReq);
  ws.on(0x03, onMsgAckReceivedGlobal);
  ws.on(0x04, onMsgDeliveredGlobal);
  ws.on(0x18, onMsgReadGlobal);
  ws.on(0x05, onOfflineBatchGlobal);
  ws.on(0x08, onChatPurgeGlobal);
  ws.on(0x15, async () => {
    console.log("Received REFILL_PREKEYS signal from server. Replenishing pool...");
    await ensurePreKeyPool();
  });
  ws.onConnect(async () => {
    await flushOutbox();
    await syncMessageHistory();
  });
}

export async function backupE2EEKeys(passphrase) {
  const stored = localStorage.getItem("penik_ik_priv");
  if (!stored) {
    throw new Error("Локальный приватный ключ не найден. Нечего резервировать.");
  }
  const privBytes = new Uint8Array(atob(stored).split("").map(c => c.charCodeAt(0)));
  const backup = await encryptKeyBackup(privBytes, passphrase);

  await apiPost("/keys/backup", {
    encrypted_blob: btoa(String.fromCharCode(...backup.encryptedBlob)),
    salt: btoa(String.fromCharCode(...backup.salt)),
    iv: btoa(String.fromCharCode(...backup.iv))
  });
}

export async function restoreE2EEKeys(passphrase) {
  const backup = await apiGet("/keys/backup");
  if (!backup || !backup.encrypted_blob) {
    throw new Error("Резервная копия ключей не найдена на сервере.");
  }

  const toUint8Array = (val) => {
    const bin = atob(val);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  };

  const encryptedBlob = toUint8Array(backup.encrypted_blob);
  const salt = toUint8Array(backup.salt);
  const iv = toUint8Array(backup.iv);

  const decrypted = await decryptKeyBackup(encryptedBlob, salt, iv, passphrase);
  
  localStorage.setItem("penik_ik_priv", btoa(String.fromCharCode(...decrypted)));
  state.privateIK = decrypted;
  
  console.log("E2EE keys successfully restored from server backup!");
}
