import { getToken, setToken, getUserById, apiGet, apiPost } from './api.js';
import {
  openDB, saveMessage, updateMessageRead,
  saveContact, getContact, updateMessageDelivered, clearIndexedDB,
  updateMsgId, updateMsgIdAndDelivered, getMessage, getAllContacts, getAllMessages,
  findAndResolvePendingSentMessage, deleteChatData,
  getMessageByClientId,
  getIKPrivate, saveIKPrivate, getIKPublic, saveIKPublic
} from './storage.js';
import { ws, OP } from './ws.js';
import { renderAuth } from './ui/auth.js';
import { renderChatList, renderChat, avatarUpdateTimestamps } from './ui/chat.js';
import { renderGroup } from './ui/groups.js';
import { renderProfile } from './ui/profile.js';
import { renderSearch } from './ui/search.js';
import {
  deriveSharedSecret, hkdfDerive, chacha20Poly1305Encrypt, chacha20Poly1305Decrypt,
  encryptKeyBackup, decryptKeyBackup, derivePublicKey
} from './crypto.js';
import { registerGroupWSListeners, syncGroups, syncHistory } from './groups.js';

function u8ToHex(arr) {
  return Array.from(arr).map(b => b.toString(16).padStart(2, "0")).join("");
}

function read32BE(buf, offset) {
  const view = new DataView(buf.buffer, buf.byteOffset + offset, 4);
  return view.getUint32(0, false);
}

export const pendingAcks = new Map();

// pendingAcks maps client_msg_id -> { tempId, userId, ts }. Entries are removed
// the instant their ACK arrives (onMsgAckReceivedGlobal). This TTL sweep is a
// safety net for ACKs that never come (dropped frame, server restart) so the map
// cannot grow without bound. ts is the enqueue time in ms.
export const PENDING_ACK_TTL_MS = 5 * 60 * 1000;

let _pendingAckSweepTimer = null;

// addPendingAck records a pending ACK with an enqueue timestamp. Idempotent:
// re-adding an existing key keeps the original timestamp so a retry does not
// reset its TTL.
export function addPendingAck(clientMsgId, entry, now = Date.now()) {
  const key = String(clientMsgId);
  if (pendingAcks.has(key)) return;
  pendingAcks.set(key, { ...entry, ts: now });
}

// sweepPendingAcks removes entries older than PENDING_ACK_TTL_MS. Idempotent and
// side-effect free beyond the map, so it is safe to call on a timer or on
// disconnect. Returns the number of entries dropped.
export function sweepPendingAcks(now = Date.now()) {
  let dropped = 0;
  for (const [key, entry] of pendingAcks) {
    if (now - (entry.ts || 0) >= PENDING_ACK_TTL_MS) {
      pendingAcks.delete(key);
      dropped++;
    }
  }
  return dropped;
}

// clearPendingAcks drops every pending ACK. Called on disconnect: the outbox is
// re-flushed on reconnect, which re-registers whatever is still undelivered.
export function clearPendingAcks() {
  pendingAcks.clear();
}

/* ── App state ── */
export const state = {
  currentUser: null,
  privateIK: null,
  retryCounters: new Map(), // msg_id -> retry attempt count
};

export function getCurrentUser() { return state.currentUser; }
export function setCurrentUser(u) { state.currentUser = u; }
export function getWS() { return ws; }

// loadPrivateIK returns the raw private Identity Key, caching it in memory. It
// reads from IndexedDB, transparently migrating any key left in localStorage by
// an older build (then removing the plaintext localStorage copy). Returns null
// if no key exists anywhere.
export async function loadPrivateIK() {
  if (state.privateIK) return state.privateIK;

  const stored = await getIKPrivate();
  if (stored) {
    state.privateIK = stored instanceof Uint8Array ? stored : new Uint8Array(stored);
    return state.privateIK;
  }

  // One-time migration from the legacy localStorage location.
  const legacy = localStorage.getItem("penik_ik_priv");
  if (legacy) {
    state.privateIK = new Uint8Array(atob(legacy).split("").map(c => c.charCodeAt(0)));
    await saveIKPrivate(state.privateIK);
    localStorage.removeItem("penik_ik_priv");
    return state.privateIK;
  }

  return null;
}

/* ── Navigation ── */
const routes = {
  '#login':    () => showAuth('login'),
  '#register': () => showAuth('register'),
  '#chats':    () => showMain('chats'),
  '#groups':   () => showMain('chats'),
  '#search':   () => showMain('search'),
  '#profile':  () => showMain('profile'),
};

function parseHash() {
  const hash = location.hash || '';
  if (hash.startsWith('#chat/')) return { screen: 'chat', userId: hash.slice(6) };
  if (hash.startsWith('#group/')) return { screen: 'group', userId: hash.slice(7) };
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

  const groupScreen = document.createElement('div');
  groupScreen.className = 'screen chat-screen';
  groupScreen.id = 'screen-group';

  screensWrap.append(chatListScreen, chatScreen, searchScreen, profileScreen, groupScreen);
  wrap.append(screensWrap, nav);
  app.appendChild(wrap);

  _mainLayout = { chatListScreen, chatScreen, searchScreen, profileScreen, groupScreen, nav };
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
  ['chats', 'chat', 'search', 'profile', 'group'].forEach(s => {
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
  } else if (screen === 'group' && userId) {
    layout.groupScreen.classList.add('active');
    layout.groupScreen.innerHTML = '';
    renderGroup(layout.groupScreen, userId);
    /* Keep the unified chat list visible as a sidebar on wide screens. */
    if (window.innerWidth >= 700) {
      layout.chatListScreen.classList.add('active');
      if (!_chatListRendered) {
        _chatListRendered = true;
        renderChatList(layout.chatListScreen);
      }
    }
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
  }

  if (screen === '#login' || screen === '#register') {
    navigate('#chats');
    return;
  }

  if (screen === 'chat') {
    showMain('chat', userId);
  } else if (screen === 'group') {
    showMain('group', userId);
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
  if (state.privateIK) {
    state.privateIK.fill(0);
    state.privateIK = null;
  }
  state.retryCounters.clear();
  pendingAcks.clear();
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

export function setActiveChatCallback(userId, fn, onAck, onStatus) {
  _activeChatCallback = userId ? { userId, fn, onAck, onStatus } : null;
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
  if (payload.plaintext) {
    plaintext = `[Нешифрованное] ${payload.plaintext}`;
  } else if (payload.ciphertext) {
    try {
      // The server may replay a message after reconnect/reload. OTPKs are
      // one-time keys, so never decrypt the same message twice: the plaintext
      // saved during the first delivery is the authoritative copy.
      const existing = payload.msg_id ? await getMessage(payload.msg_id) : null;
      if (existing?.plaintext &&
          !existing.plaintext.startsWith('[Ошибка расшифрования')) {
        plaintext = existing.plaintext;
      } else {
        const result = await decryptMessagePayload(payload);
        plaintext = result.text;
      }
    } catch (e) {
      plaintext = `[Сообщение не расшифровано]`;
      decryptSuccess = false;
    }
  }

  const chatPartnerId = payload.chat_user_id || fromUserId;

  if (plaintext.startsWith('[Сообщение не расшифровано')) {
    const clientMsgId = payload.client_msg_id;
    if (clientMsgId) {
      const existing = await getMessageByClientId(clientMsgId);
      if (existing?.plaintext && !existing.plaintext.startsWith('[Сообщение не расшифровано')) {
        return;
      }
    }
    return;
  }

  const inMsg = {
    msg_id: payload.msg_id,
    chat_id: String(chatPartnerId),
    sender_id: fromUserId,
    plaintext,
    created_at: payload.ts ? payload.ts * 1000 : Date.now(),
    delivered: 1,
    client_msg_id: payload.client_msg_id,
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

async function onMsgStatusBatchGlobal(payload) {
  if (!payload || !payload.statuses || !Array.isArray(payload.statuses)) return;
  for (const item of payload.statuses) {
    if (!item.msg_id) continue;
    if (item.delivered) {
      await updateMessageDelivered(item.msg_id, 1);
    }
    if (item.read) {
      await updateMessageRead(item.msg_id);
    }
    if (_activeChatCallback) {
      if (item.read) {
        _activeChatCallback.onStatus?.(item.msg_id, "read");
      } else if (item.delivered) {
        _activeChatCallback.onAck?.(item.msg_id);
      }
    }
  }
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

    // Cache key bundles per sender within this sync pass so a chat with N
    // messages from the same sender doesn't fire N identical bundle requests.
    const bundleCache = new Map();
    const getSenderBundle = async (senderId) => {
      const key = String(senderId);
      if (bundleCache.has(key)) return bundleCache.get(key);
      const b = await apiGet(`/keys/bundle/${senderId}`);
      bundleCache.set(key, b);
      return b;
    };

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
          !existing.plaintext.startsWith('[Сообщение не расшифровано')) {
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
              !locallyStored.plaintext.startsWith('[Сообщение не расшифровано')) {
            text = locallyStored.plaintext;
            throw { __alreadyDecrypted: true };
          }
          const senderBundle = await getSenderBundle(item.sender_id);
          const senderDevice = senderBundle?.devices?.find(d => Number(d.device_id) === Number(item.sender_device_id));
          const fromIdentityKey = senderDevice?.identity_key;

          const decrypted = await decryptMessagePayload({
            ciphertext: item.ciphertext,
            salt: item.encryption_salt,
            nonce: item.encryption_nonce,
            from_identity_key: fromIdentityKey
          });
          text = decrypted.text;
        } catch (e) {
          if (e?.__alreadyDecrypted) continue;
          text = `[Сообщение не расшифровано]`;
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
      // A message fans out to every device; only the copy encrypted for this
      // device decrypts, the rest fail. Never store an error placeholder —
      // just skip the copies that don't belong to us.
      if (text.startsWith('[Сообщение не расшифровано')) {
        continue;
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
        delivered: 1,
        client_msg_id: item.client_msg_id,
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
    if (val instanceof Uint8Array) return val;
    if (val instanceof ArrayBuffer) return new Uint8Array(val);
    if (Array.isArray(val)) return new Uint8Array(val);
    if (typeof val === "string") {
      const bin = atob(val);
      const out = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    }
    throw new Error(`decryptMessagePayload: unsupported binary field type ${typeof val}`);
  };

  const ciphertext = toUint8Array(payload.ciphertext);
  const salt = toUint8Array(payload.salt);
  const nonce = toUint8Array(payload.nonce);
  const fromIdentityKey = toUint8Array(payload.from_identity_key);

  const myPrivateIK = await loadPrivateIK();
  if (!myPrivateIK) {
    throw new Error("Приватный ключ не найден");
  }

  const secret = await deriveSharedSecret(myPrivateIK, fromIdentityKey);
  const info = new TextEncoder().encode("PenikE2EE");
  const derivedKey = await hkdfDerive(salt, secret, info, 32);

  const plaintextBytes = await chacha20Poly1305Decrypt(derivedKey, nonce, ciphertext);

  return { text: new TextDecoder().decode(plaintextBytes) };
}

export async function encryptMessagePayload(text, recipientUserId) {
  const myId = Number(localStorage.getItem("user_id"));
  const myDeviceId = Number(localStorage.getItem("device_id"));
  const isSelfChat = Number(recipientUserId) === myId;

  const bundleUrl = `/keys/bundle/${recipientUserId}`;
  const senderBundleUrl = `/keys/bundle/${myId}`;

  const recipientBundle = await apiGet(bundleUrl);
  const senderBundle = await apiGet(senderBundleUrl);

  const recipientDevices = recipientBundle?.devices || [];
  const senderDevices = senderBundle?.devices || [];

    const filteredSenderDevices = isSelfChat ? [] : senderDevices.filter(d => Number(d.device_id) !== myDeviceId);
    const allDevices = [...recipientDevices, ...filteredSenderDevices];

  const myPrivateIK = await loadPrivateIK();
  if (!myPrivateIK) {
    throw new Error("Private Identity Key not found");
  }

  const payloads = [];
  for (const device of allDevices) {
    const recipientIKPub = new Uint8Array(atob(device.identity_key).split("").map(c => c.charCodeAt(0)));

    const secret = await deriveSharedSecret(myPrivateIK, recipientIKPub);

    const salt = window.crypto.getRandomValues(new Uint8Array(32));
    const nonce = window.crypto.getRandomValues(new Uint8Array(12));

    const info = new TextEncoder().encode("PenikE2EE");
    const derivedKey = await hkdfDerive(salt, secret, info, 32);

    const ciphertext = await chacha20Poly1305Encrypt(derivedKey, nonce, new TextEncoder().encode(text));

    payloads.push({
      device_id: Number(device.device_id),
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
      addPendingAck(clientMsgId, { tempId: msg.msg_id, userId: msg.chat_id });
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
  ws.on(0x1b, onMsgStatusBatchGlobal);
  ws.on(0x05, onOfflineBatchGlobal);
  ws.on(0x08, onChatPurgeGlobal);
  ws.on(OP.USER_AVATAR_UPDATE, (payload) => {
    if (payload && payload.user_id) {
      console.log(`[ws] Received avatar update for user ${payload.user_id}`);
      const ts = payload.ts ? payload.ts * 1000 : Date.now();
      avatarUpdateTimestamps.set(String(payload.user_id), ts);
      triggerChatListUpdate();
      if (_activeChatCallback && String(_activeChatCallback.userId) === String(payload.user_id)) {
        const chatScreen = document.getElementById('screen-chat');
        if (chatScreen && chatScreen.classList.contains('active')) {
          renderChat(chatScreen, payload.user_id);
        }
      }
    }
  });
  registerGroupWSListeners();

  // Periodically drop pending ACKs that never resolved, and clear them on
  // disconnect (the outbox re-flush on reconnect re-registers live ones).
  if (!_pendingAckSweepTimer) {
    _pendingAckSweepTimer = setInterval(() => sweepPendingAcks(), PENDING_ACK_TTL_MS);
    if (_pendingAckSweepTimer.unref) _pendingAckSweepTimer.unref();
  }
  ws.onDisconnect(() => clearPendingAcks());

  ws.onConnect(async () => {
    // Publish current local public identity key
    const pubKey = await getIKPublic();
    if (pubKey) {
      ws.send(0x12, { x25519_pub: new Uint8Array(pubKey) });
    }
    await flushOutbox();
    await syncMessageHistory();
    try {
      const groups = await syncGroups();
      for (const g of groups) {
        // Skip pending invites: we're not a member yet, so history/key
        // requests 403. Isolate per-group so one failure doesn't abort the rest.
        if (g.status === 'pending') continue;
        try {
          await syncHistory(g.id);
        } catch (e) {
          console.warn(`[groups] history sync for ${g.id} failed`, e.message);
        }
      }
    } catch (e) {
      console.warn('[groups] sync on connect failed', e.message);
    }
  });
}

export async function backupE2EEKeys(passphrase) {
  const privBytes = await loadPrivateIK();
  if (!privBytes) {
    throw new Error("Локальный приватный ключ не найден. Нечего резервировать.");
  }
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
  const derivedPub = await derivePublicKey(decrypted);

  await saveIKPrivate(decrypted);
  await saveIKPublic(derivedPub);
  state.privateIK = decrypted;

  console.log("E2EE keys successfully restored from server backup!");
}
