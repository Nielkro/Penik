import { getToken, setToken, primeToken, getUserById, apiGet, apiPost } from './api.js';
import {
  openDB, saveMessage, updateMessageRead, updateMessageText,
  saveContact, getContact, updateMessageDelivered, clearIndexedDB,
  updateMsgId, updateMsgIdAndDelivered, getMessage, getAllContacts, getAllMessages,
  findAndResolvePendingSentMessage, deleteChatData, deleteMessage,
  getMessageByClientId, isMessageDeletedLocally,
  getIKPrivate, saveIKPrivate, getIKPublic, saveIKPublic
} from './storage.js';
import { ws, OP } from './ws.js';
import { renderAuth } from './ui/auth.js';
import { renderChatList, renderChat, avatarUpdateTimestamps } from './ui/chat.js';
import { groupAvatarUpdateTimestamps, showToast } from './ui/components.js';
import { renderGroup } from './ui/groups.js';
import { renderProfile } from './ui/profile.js';
import { renderSearch } from './ui/search.js';
import { renderSettings, renderDevices } from './ui/settings.js';
import { initTheme } from './theme.js';
import {
  deriveSharedSecret, e2eeEncrypt, e2eeDecrypt, buildPairwiseAAD,
  encryptKeyBackup, decryptKeyBackup, derivePublicKey, generateKeyPair
} from './crypto.js';
import { registerGroupWSListeners, syncGroups, syncHistory } from './groups.js';
import { verifyPeerIdentityKey } from './pinning.js';
import { emitPresenceUpdate, emitTypingUpdate } from './presence.js';
import { getCachedMedia } from './storage.js';
import { callManager } from './call.js';
import { initCallUI } from './ui/call_modal.js';

// Service Worker registration for HTTP 206 Partial Content Range streaming
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js', { scope: '/' }).then((reg) => {
    console.log('[sw] Service Worker registered for HTTP 206 streaming');
  }).catch((err) => {
    console.warn('[sw] Service Worker registration failed:', err);
  });

  navigator.serviceWorker.addEventListener('message', async (event) => {
    if (event.data?.type === 'GET_STREAM_DATA') {
      const { mediaId } = event.data;
      const port = event.ports[0];
      if (!port) return;

      try {
        const rawBlobUrl = window._streamMediaCache?.get(mediaId);
        let blob = null;
        if (rawBlobUrl) {
          const res = await fetch(rawBlobUrl);
          blob = await res.blob();
        } else {
          // Fallback to IndexedDB
          const cachedUrl = await getCachedMedia(mediaId);
          if (cachedUrl) {
            const res = await fetch(cachedUrl);
            blob = await res.blob();
          }
        }
        port.postMessage({ blob, mime: blob?.type || 'video/mp4' });
      } catch (e) {
        port.postMessage(null);
      }
    }
  });
}

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
      <span class="nav-icon"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg></span>
      <span>Чаты</span>
    </button>
    <button class="nav-item" data-screen="search">
      <span class="nav-icon"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg></span>
      <span>Поиск</span>
    </button>
    <button class="nav-item" data-screen="settings">
      <span class="nav-icon"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg></span>
      <span>Настройки</span>
    </button>
    <button class="nav-item" data-screen="profile">
      <span class="nav-icon"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg></span>
      <span>Профиль</span>
    </button>
  `;

  /** @type {NodeListOf<HTMLElement>} */ (nav.querySelectorAll('.nav-item')).forEach(btn => {
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

  const settingsScreen = document.createElement('div');
  settingsScreen.className = 'screen settings-screen';
  settingsScreen.id = 'screen-settings';

  const devicesScreen = document.createElement('div');
  devicesScreen.className = 'screen devices-screen';
  devicesScreen.id = 'screen-devices';

  screensWrap.append(chatListScreen, chatScreen, searchScreen, profileScreen, groupScreen, settingsScreen, devicesScreen);
  wrap.append(screensWrap, nav);
  app.appendChild(wrap);

  _mainLayout = { chatListScreen, chatScreen, searchScreen, profileScreen, groupScreen, settingsScreen, devicesScreen, nav };
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
  const activeNavScreen = (screen === 'chat' || screen === 'group') ? 'chats'
    : (screen === 'devices') ? 'settings'
    : screen;
  layout.nav.querySelectorAll('.nav-item').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.screen === activeNavScreen);
  });

  /* Hide all screens */
  ['chats', 'chat', 'search', 'profile', 'group', 'settings', 'devices'].forEach(s => {
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
  } else if (screen === 'settings') {
    layout.settingsScreen.classList.add('active');
    layout.settingsScreen.innerHTML = '';
    renderSettings(layout.settingsScreen);
  } else if (screen === 'devices') {
    layout.devicesScreen.classList.add('active');
    layout.devicesScreen.innerHTML = '';
    renderDevices(layout.devicesScreen);
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

  // Ensure WS is connected if we are logged in. ws.connect() is idempotent, so
  // it is safe to call on every route change: it no-ops while a socket is
  // opening or open instead of racing boot()'s connection.
  ws.connect();

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
  initTheme();
  localStorage.removeItem("penik_sign_jwk");
  await openDB();
  await primeToken();
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
  const appEl = document.getElementById('app');
  if (!appEl) return;
  appEl.textContent = '';
  const box = document.createElement('div');
  box.style.cssText = 'color:#e05252;padding:24px;text-align:center';
  // textContent, not innerHTML: the message can carry server- or peer-supplied text.
  box.textContent = `Не удалось запустить: ${err?.message || 'неизвестная ошибка'}`;
  appEl.appendChild(box);
});

/* ── Exports for use by UI modules ── */
export async function logout() {
  ws.disconnect();
  // Revoke the session server-side so the token cannot be replayed. Best-effort:
  // proceed with local teardown even if the request fails (e.g. offline).
  try {
    await apiPost('/logout');
  } catch (error) {
    console.warn("Server-side logout failed, clearing locally anyway:", error);
  }
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

export function setActiveChatCallback(userId, fn, onAck, onStatus, onMessageEdited) {
  _activeChatCallback = userId ? { userId, fn, onAck, onStatus, onMessageEdited } : null;
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

  const myId = localStorage.getItem("user_id");
  const isMine = String(fromUserId) === String(myId);

  // Prevent duplicate rendering of messages sent by this device
  const existingByServer = payload.msg_id ? await getMessage(payload.msg_id) : null;
  if (existingByServer) return;

  if (isMine) {
    const chatPartnerId = payload.chat_user_id || fromUserId;
    const resolvedOldId = await findAndResolvePendingSentMessage(chatPartnerId, payload.ts * 1000, payload.msg_id, payload.client_msg_id);
    if (resolvedOldId) {
      // Find DOM temporary ID mapping if it exists in pendingAcks
      let domId = resolvedOldId;
      const pending = pendingAcks.get(String(resolvedOldId));
      if (pending?.tempId) {
        domId = pending.tempId;
      }
      pendingAcks.delete(String(resolvedOldId));
      
      // Update DOM dataset ID of the message bubble
      if (_activeChatCallback) {
        const bubble = /** @type {HTMLElement} */ (document.querySelector(`[data-msg-id="${domId}"]`) || document.querySelector(`[data-msg-id="${resolvedOldId}"]`));
        if (bubble) {
          bubble.dataset.msgId = payload.msg_id;
          const statusEl = /** @type {HTMLElement} */ (bubble.querySelector(".msg-status"));
          if (statusEl) {
            statusEl.dataset.msgId = payload.msg_id;
          }
        }
      }
      return;
    }
  }
  
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
  }

  const inMsg = {
    msg_id: payload.msg_id,
    chat_id: String(chatPartnerId),
    sender_id: fromUserId,
    plaintext,
    created_at: payload.ts ? payload.ts * 1000 : Date.now(),
    delivered: 1,
    client_msg_id: payload.client_msg_id,
    reply_to_msg_id: payload.reply_to_msg_id || null,
  };

  await saveMessage(inMsg);

  let contact = await getContact(chatPartnerId);
  if (!contact || contact.name === "Неизвестный") {
    try {
      const res = await getUserById(String(chatPartnerId));
      contact = res.user || res;
    } catch (e) {
      console.warn("Failed to fetch contact details in onMsgRecvGlobal:", e);
      if (!contact) {
        contact = { user_id: chatPartnerId, name: "Неизвестный", nickname: "" };
      }
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
    if (payload.client_msg_id && payload.msg_id) {
      await updateMsgIdAndDelivered(payload.client_msg_id, payload.msg_id, 1);
    } else if (payload.msg_id) {
      await updateMessageDelivered(payload.msg_id, 1);
    }
  } catch (e) {}

  if (_activeChatCallback) {
    _activeChatCallback.onAck(payload.msg_id, payload.client_msg_id);
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
    await updateMsgIdAndDelivered(clientMsgId, serverMsgId, 0);

    if (_activeChatCallback && String(_activeChatCallback.userId) === String(pending.userId)) {
      _activeChatCallback.onAck?.(serverMsgId, clientMsgId);
      const bubble = /** @type {HTMLElement} */ (document.querySelector(`[data-msg-id="${pending.tempId}"]`));
      if (bubble) {
        bubble.dataset.msgId = serverMsgId;
        const statusEl = /** @type {HTMLElement} */ (bubble.querySelector(".msg-status, .msg-status-wrapper"));
        if (statusEl) {
          statusEl.dataset.msgId = serverMsgId;
          statusEl.className = "msg-status-wrapper";
          statusEl.innerHTML = '<span class="chk chk-1">✓</span>';
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

async function onMsgDeleteNotifyGlobal(payload) {
  if (!payload?.msg_id) return;
  try {
    await deleteMessage(payload.msg_id);
    const escaped = CSS.escape(String(payload.msg_id));
    const bubbles = document.querySelectorAll(`[data-msg-id="${escaped}"], [data-client-msg-id="${escaped}"]`);
    bubbles.forEach(b => b.remove());
    triggerChatListUpdate();
  } catch (e) {
    console.error("[ws] Error applying delete notify:", e);
  }
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

export async function syncMessageHistory(options = {}) {
  try {
    const limit = options.limit || 500;
    let url = `/messages/history?limit=${limit}`;
    if (options.before_id) {
      url += `&before_id=${options.before_id}`;
    } else if (options.after_id) {
      url += `&after_id=${options.after_id}`;
    } else {
      const allLocal = await getAllMessages();
      let maxServerId = 0;
      for (const m of allLocal) {
        const idNum = Number(m.msg_id);
        if (Number.isInteger(idNum) && idNum > maxServerId) {
          maxServerId = idNum;
        }
      }
      if (maxServerId > 0) {
        url += `&after_id=${maxServerId}`;
      }
    }
    if (options.chat_user_id) {
      url += `&chat_user_id=${options.chat_user_id}`;
    }
    const history = await apiGet(url);
    if (!history || !Array.isArray(history) || history.length === 0) return [];

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
      const isEdited = item.edited_at && (!existing || !existing.edited_at || (item.edited_at * 1000 > existing.edited_at));
      if (existing && existing.plaintext &&
          !existing.plaintext.startsWith('[Сообщение не расшифровано') && !isEdited) {
        continue;
      }

      const peerId = Number(item.chat_user_id || (Number(item.sender_id) === myId ? item.recipient_id : item.sender_id));

      let text = "";
      if (item.plaintext) {
        text = item.plaintext;
      } else if (item.ciphertext) {
        try {
          // History can contain a message that was already received live and
          // decrypted. In that case the OTPK may have been consumed already;
          // use the locally persisted plaintext unless it was edited.
          const locallyStored = await getMessage(item.id);
          if (locallyStored && locallyStored.plaintext &&
              !locallyStored.plaintext.startsWith('[Сообщение не расшифровано') && !isEdited) {
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
            from_identity_key: fromIdentityKey,
            sender_user_id: item.sender_id,
            sender_device_id: item.sender_device_id,
            client_msg_id: item.client_msg_id,
            timestamp: item.timestamp,
            edited_at: item.edited_at
          });
          text = decrypted.text;
        } catch (e) {
          if (e?.__alreadyDecrypted) continue;
          text = existing?.plaintext || `[Сообщение не расшифровано]`;
        }
      }

      let contact = await getContact(peerId);
      if (!contact || contact.name === "Неизвестный") {
        try {
          const res = await getUserById(String(peerId));
          contact = res.user || res;
        } catch (e) {
          console.error("Failed to fetch contact details for syncing:", e);
          if (!contact) {
            contact = { user_id: peerId, name: "Неизвестный", nickname: "" };
          }
        }
      }
      if (text.startsWith('[Сообщение не расшифровано')) {
        const clientMsgId = item.client_msg_id;
        if (clientMsgId) {
          const existing = await getMessageByClientId(clientMsgId);
          if (existing?.plaintext && !existing.plaintext.startsWith('[Сообщение не расшифровано')) {
            continue;
          }
        }
      }

      await saveContact({
        ...contact,
        user_id: peerId,
        last_message: text,
        last_ts: item.timestamp * 1000
      });

      const existingMsg = await getMessage(item.id);
      if (existingMsg) {
        if (item.edited_at) {
          await updateMessageText(item.id, text, item.edited_at * 1000);
          if (_activeChatCallback && typeof _activeChatCallback.onMessageEdited === "function") {
            _activeChatCallback.onMessageEdited(item.id, text, item.edited_at * 1000);
          }
        }
        continue;
      }

      if (await isMessageDeletedLocally(item.id) || (item.client_msg_id && await isMessageDeletedLocally(item.client_msg_id))) {
        console.log("[sync] Skipping locally deleted message:", item.id);
        continue;
      }

      if (Number(item.sender_id) === myId) {
        const resolved = item.client_msg_id
          ? await updateMsgIdAndDelivered(item.client_msg_id, item.id, item.delivered)
          : await findAndResolvePendingSentMessage(peerId, item.timestamp, item.id);
        if (resolved) {
          if (item.edited_at) {
            await updateMessageText(item.id, text, item.edited_at * 1000);
          }
          continue;
        }
      }

      const storedMsg = {
        msg_id: item.id,
        chat_id: String(peerId),
        sender_id: Number(item.sender_id),
        plaintext: text,
        created_at: item.timestamp * 1000,
        delivered: 1,
        client_msg_id: item.client_msg_id,
        reply_to_msg_id: item.reply_to_msg_id || null,
        edited_at: item.edited_at ? item.edited_at * 1000 : null,
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

  // TOFU pinning: verify and pin identity key; displays warning on change.
  const pinUserId = Number(payload.from_user_id ?? payload.sender_user_id);
  const pinDeviceId = Number(payload.from_device_id ?? payload.sender_device_id);
  if (pinUserId && pinDeviceId && fromIdentityKey.length) {
    await verifyPeerIdentityKey(pinUserId, pinDeviceId, fromIdentityKey);
  }

  const myPrivateIK = await loadPrivateIK();
  if (!myPrivateIK) {
    throw new Error("Приватный ключ не найден");
  }

  const myId = Number(localStorage.getItem("user_id"));
  const senderUserId = Number(payload.from_user_id ?? payload.sender_user_id ?? payload.sender_id ?? pinUserId ?? 0);
  let chatPartnerId = Number(payload.chat_user_id ?? payload.chat_id ?? (senderUserId === myId ? 0 : senderUserId));
  let recipientUserId = Number(payload.to_user_id ?? payload.recipient_user_id ?? (senderUserId === myId ? chatPartnerId : myId));
  let clientMsgId = payload.client_msg_id || (typeof payload.msg_id === "string" && isNaN(Number(payload.msg_id)) ? payload.msg_id : "");

  let localMsg = null;
  const lookupId = payload.client_msg_id || payload.msg_id || payload.id;
  if (lookupId) {
    localMsg = await getMessage(lookupId);
  }
  if (localMsg) {
    if (!chatPartnerId && localMsg.chat_id) {
      chatPartnerId = Number(localMsg.chat_id);
    }
    if (!clientMsgId && (localMsg.client_msg_id || localMsg.localId)) {
      clientMsgId = localMsg.client_msg_id || localMsg.localId;
    }
  }
  if (senderUserId === myId && !recipientUserId) {
    recipientUserId = chatPartnerId;
  }

  const rawTs = Number(payload.timestamp ?? payload.created_at ?? payload.ts ?? payload.edited_at ?? 0);
  const tsSec = rawTs > 1e11 ? Math.floor(rawTs / 1000) : rawTs;

  const secret = await deriveSharedSecret(myPrivateIK, fromIdentityKey);

  // Candidate AADs in order of priority (including clock drift / network transit delta ±1s to ±5s):
  const candidateUsers = [
    { s: senderUserId, r: recipientUserId },
    { s: senderUserId, r: chatPartnerId },
    { s: myId, r: chatPartnerId },
    { s: senderUserId, r: myId }
  ].filter(u => u.s > 0 && u.r > 0);

  const candidateClientIds = [clientMsgId, localMsg?.client_msg_id, localMsg?.msg_id, ""].filter(Boolean);
  const candidateTimestamps = [tsSec];
  if (rawTs > 1e11) candidateTimestamps.push(rawTs);
  if (localMsg?.created_at) {
    const localTs = localMsg.created_at > 1e11 ? Math.floor(localMsg.created_at / 1000) : localMsg.created_at;
    candidateTimestamps.push(localTs);
  }

  const timeOffsets = [0, -1, 1, -2, 2, -3, 3, -4, 4, -5, 5];
  const candidateAads = [];
  const addedSet = new Set();

  for (const { s, r } of candidateUsers) {
    for (const cId of candidateClientIds) {
      for (const baseTs of candidateTimestamps) {
        for (const offset of timeOffsets) {
          const t = baseTs + offset;
          const key = `${s}:${r}:${cId}:${t}`;
          if (!addedSet.has(key)) {
            addedSet.add(key);
            candidateAads.push(buildPairwiseAAD(s, r, cId, t));
          }
        }
      }
    }
  }
  candidateAads.push(new Uint8Array(0));

  let textBytes = null;
  let lastErr = null;
  for (const aad of candidateAads) {
    try {
      textBytes = await e2eeDecrypt(ciphertext, secret, salt, nonce, "penik-pairwise-message-v1", aad);
      break;
    } catch (e) {
      lastErr = e;
    }
  }

  if (!textBytes) {
    console.error("[PenikE2EE] Pairwise decryption failed:", {
      senderUserId,
      recipientUserId,
      chatPartnerId,
      clientMsgId,
      tsSec,
      rawTs,
      fromIdentityKeyLen: fromIdentityKey?.length,
      ctLen: ciphertext?.length,
      saltLen: salt?.length,
      nonceLen: nonce?.length,
      candidatesCount: candidateAads.length
    }, lastErr);
    throw lastErr || new Error("Failed to decrypt pairwise message");
  }

  return { text: new TextDecoder().decode(textBytes) };
}

const bundleMemoryCache = new Map(); // userId -> { bundle, expiresAt }
export async function getCachedKeyBundle(userId, forceRefresh = false) {
  const key = String(userId);
  const now = Date.now();
  if (!forceRefresh && bundleMemoryCache.has(key)) {
    const cached = bundleMemoryCache.get(key);
    if (cached.expiresAt > now) {
      return cached.bundle;
    }
  }
  const bundle = await apiGet(`/keys/bundle/${userId}`);
  bundleMemoryCache.set(key, { bundle, expiresAt: now + 2 * 60 * 1000 });
  return bundle;
}

export async function encryptMessagePayload(text, recipientUserId, clientMsgId = "", timestamp = 0) {
  const myId = Number(localStorage.getItem("user_id"));
  const myDeviceId = Number(localStorage.getItem("device_id"));
  const isSelfChat = Number(recipientUserId) === myId;
  const tsSec = Number(timestamp) > 1e11 ? Math.floor(Number(timestamp) / 1000) : (Number(timestamp) || Math.floor(Date.now() / 1000));

  let recipientBundle = await getCachedKeyBundle(recipientUserId);
  let senderBundle = await getCachedKeyBundle(myId);

  let recipientDevices = recipientBundle?.devices || [];
  let senderDevices = senderBundle?.devices || [];

  if (recipientDevices.length === 0) {
    recipientBundle = await getCachedKeyBundle(recipientUserId, true);
    recipientDevices = recipientBundle?.devices || [];
  }

  const filteredSenderDevices = isSelfChat ? [] : senderDevices.filter(d => Number(d.device_id) !== myDeviceId);
  const allDevices = [
    ...recipientDevices.map(d => ({ ...d, owner_user_id: recipientUserId })),
    ...filteredSenderDevices.map(d => ({ ...d, owner_user_id: myId })),
  ];

  const myPrivateIK = await loadPrivateIK();
  if (!myPrivateIK) {
    throw new Error("Private Identity Key not found");
  }

  const aad = buildPairwiseAAD(myId, recipientUserId, clientMsgId, tsSec);

  const payloads = [];
  for (const device of allDevices) {
    const recipientIKPub = new Uint8Array(atob(device.identity_key).split("").map(c => c.charCodeAt(0)));

    // TOFU pinning: verify and pin identity key; displays warning on change.
    await verifyPeerIdentityKey(device.owner_user_id, device.device_id, recipientIKPub);

    const secret = await deriveSharedSecret(myPrivateIK, recipientIKPub);
    const { ciphertext, salt, nonce } = await e2eeEncrypt(text, secret, "penik-pairwise-message-v1", aad);

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
    const unsent = allMsgs.filter(m => {
      const isMine = String(m.sender_id) === String(myId);
      const isUnsent = m.delivered === 0 && (m.pending === 1 || !m.server_acked);
      const isRecent = (Date.now() - (m.created_at || 0)) < 30 * 60 * 1000;
      return isMine && isUnsent && isRecent;
    });
    for (const msg of unsent) {
      const clientMsgId = msg.client_msg_id || String(msg.msg_id);
      let ciphertexts = msg.ciphertexts;
      if (!ciphertexts || !ciphertexts.length) {
        try {
          ciphertexts = await encryptMessagePayload(msg.plaintext || "", msg.chat_id, clientMsgId, msg.created_at || Date.now());
          msg.ciphertexts = ciphertexts;
          await saveMessage(msg);
        } catch (encErr) {
          console.warn("flushOutbox: failed to encrypt msg", msg.msg_id, encErr);
          continue;
        }
      }
      addPendingAck(clientMsgId, { tempId: msg.msg_id, userId: msg.chat_id });
      const seen = new Set();
      const uniqueDevices = (ciphertexts || []).filter(d => {
        const id = Number(d.device_id);
        if (seen.has(id)) return false;
        seen.add(id);
        return true;
      });
      const msgCreatedAt = Number(msg.created_at || Date.now());
      const tsSec = msgCreatedAt > 1e11 ? Math.floor(msgCreatedAt / 1000) : msgCreatedAt;
      const sent = ws.send(0x01, {
        to_user_id: Number(msg.chat_id),
        devices: uniqueDevices,
        msg_id: clientMsgId,
        created_at: tsSec,
        reply_to_msg_id: msg.reply_to_msg_id ? String(msg.reply_to_msg_id) : undefined
      });
      if (!sent) {
        pendingAcks.delete(clientMsgId);
        break;
      }
      // Introduce a small delay to avoid triggering WebSocket rate limiting on the server
      await new Promise(resolve => setTimeout(resolve, 100));
    }
  } catch (e) {
    console.warn("Failed to flush outbox:", e);
  }
}



async function onMsgEditNotifyGlobal(payload) {
  if (!payload) return;
  try {
    const res = await decryptMessagePayload(payload);
    const text = (typeof res === "object" && res !== null && "text" in res) ? res.text : String(res || "");
    const msgId = payload.client_msg_id || payload.msg_id;
    const editedAt = payload.edited_at ? payload.edited_at * 1000 : Date.now();
    await updateMessageText(msgId, text, editedAt);

    if (_activeChatCallback && typeof _activeChatCallback.onMessageEdited === "function") {
      _activeChatCallback.onMessageEdited(msgId, text, editedAt);
    }
    triggerChatListUpdate();
  } catch (err) {
    console.error("[ws] onMsgEditNotifyGlobal failed:", err);
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
  ws.on(OP.MSG_DELETE_NOTIFY, onMsgDeleteNotifyGlobal);
  ws.on(OP.MSG_EDIT_NOTIFY, onMsgEditNotifyGlobal);
  ws.on(OP.USER_AVATAR_UPDATE, (payload) => {
    if (payload && payload.user_id) {
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
  // A peer's display name is cached with the local contact row and was previously
  // only refreshed for brand-new chats, so a rename stayed invisible to everyone
  // already talking to them. The server now pushes it.
  ws.on(OP.USER_PROFILE_UPDATE, async (payload) => {
    if (!payload || !payload.user_id || !payload.name) return;
    const userId = Number(payload.user_id);
    try {
      const existing = await getContact(userId);
      if (existing && existing.name === payload.name) return;
      await saveContact({ ...(existing || { user_id: userId }), user_id: userId, name: payload.name });
    } catch (err) {
      console.warn('[ws] profile update save failed:', err);
      return;
    }
    triggerChatListUpdate();
    if (_activeChatCallback && String(_activeChatCallback.userId) === String(userId)) {
      const chatScreen = document.getElementById('screen-chat');
      if (chatScreen && chatScreen.classList.contains('active')) {
        renderChat(chatScreen, userId);
      }
    }
  });
  ws.on(OP.PRESENCE_UPDATE, (payload) => {
    if (payload && payload.user_id != null) {
      emitPresenceUpdate(payload.user_id, { online: payload.online, last_seen: payload.last_seen });
    }
  });
  ws.on(OP.TYPING, (payload) => {
    if (payload && payload.from_user_id != null) {
      emitTypingUpdate(payload.from_user_id, !!payload.is_typing);
    }
  });
  ws.on(OP.SERVER_SHUTDOWN, () => {
    console.log('[ws] Server is shutting down, disconnecting');
    ws.closeForServerShutdown();
    showToast('Сервер выключается…', 'warning');
  });
  registerGroupWSListeners();
  initCallUI();
  callManager.init();

  // Periodically drop pending ACKs that never resolved, and clear them on
  // disconnect (the outbox re-flush on reconnect re-registers live ones).
  if (!_pendingAckSweepTimer) {
    _pendingAckSweepTimer = setInterval(() => sweepPendingAcks(), PENDING_ACK_TTL_MS);
    if (_pendingAckSweepTimer.unref) _pendingAckSweepTimer.unref();
  }
  ws.onDisconnect(() => clearPendingAcks());

  ws.onConnect(async () => {
    // Publish current local public identity key
    let pubKey = await getIKPublic();
    if (!pubKey) {
      try {
        const ik = await generateKeyPair();
        await saveIKPrivate(ik.privateKey);
        await saveIKPublic(ik.publicKey);
        state.privateIK = ik.privateKey;
        pubKey = ik.publicKey;
        console.log('[E2EE] Generated new identity key pair for this device');
      } catch (e) {
        console.error('[E2EE] Failed to generate identity key pair', e);
      }
    }
    if (pubKey) {
      ws.send(0x12, { x25519_pub: new Uint8Array(pubKey) });
    }
    await flushOutbox();
    await syncMessageHistory();
    await refreshContactProfiles();
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

// refreshContactProfiles re-reads the display name of every known contact.
//
// A rename is pushed live over opcode 0x0c, which by definition misses anyone who
// was offline at the time: the web client kept the name it first stored forever,
// while Android happened to be right because its history sync re-fetches the
// profile per chat. This closes that gap on every reconnect. Failures are
// per-contact and silent — a stale name is not worth an error toast.
async function refreshContactProfiles() {
  let contacts;
  try {
    contacts = await getAllContacts();
  } catch (e) {
    console.warn('[contacts] profile refresh skipped', e.message);
    return;
  }
  let changed = false;
  for (const contact of contacts) {
    const userId = Number(contact.user_id);
    if (!userId) continue;
    let profile;
    try {
      const res = await getUserById(String(userId));
      profile = res.user || res;
    } catch {
      continue;
    }
    if (!profile) continue;
    const name = profile.name || '';
    const nickname = profile.nickname || '';
    if ((!name || name === contact.name) && (!nickname || nickname === contact.nickname)) continue;
    await saveContact({
      ...contact,
      user_id: userId,
      name: name || contact.name,
      nickname: nickname || contact.nickname,
    });
    changed = true;
  }
  if (changed) triggerChatListUpdate();
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
