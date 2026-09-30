import { getToken, setToken, primeToken, getUserById, apiGet, apiPost, syncServerTime, getServerTimeMs, getServerTimeSec, getApiOrigin } from './api.js';
import {
  openDB, saveMessage, updateMessageRead, updateMessageText, updateMessagePlaintext,
  saveContact, getContact, updateMessageDelivered, clearIndexedDB,
  updateMsgId, updateMsgIdAndDelivered, getMessage, getAllContacts, getAllMessages,
  findAndResolvePendingSentMessage, deleteChatData, deleteMessage,
  getMessageByClientId, isMessageDeletedLocally,
  getPersistentDeviceName, getClientPlatform,
  getAllGroupMessages, saveGroupMessage
} from './storage.js';
import { ws, OP } from './ws.js';
import { renderAuth } from './ui/auth.js';
import { renderChatList, renderChat, avatarUpdateTimestamps } from './ui/chat.js';
import { renderCalls } from './ui/calls.js';
import { groupAvatarUpdateTimestamps, showToast, setMsgTextContent } from './ui/components.js';
import { renderGroup } from './ui/groups.js';
import { renderProfile } from './ui/profile.js';
import { renderSearch } from './ui/search.js';
import { renderSettings, renderDevices } from './ui/settings.js';
import { initTheme } from './theme.js';
import { appSounds } from './sounds.js';
import { getMessagePreview } from './ui/chat.js';
import { registerGroupWSListeners, syncGroups, syncHistory } from './groups.js';
import { emitPresenceUpdate, emitTypingUpdate } from './presence.js';
import { getCachedMedia } from './storage.js';
import { callManager } from './call.js';
import { initCallUI } from './ui/call_modal.js';
import { initDesktop, isDesktop, sendDesktopNotification } from './desktop.js';

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
        // Enforce authentication: refuse to serve decrypted media if user is logged out
        if (!getToken()) {
          port.postMessage(null);
          return;
        }
        if (!mediaId || typeof mediaId !== 'string' || mediaId.includes('..')) {
          port.postMessage(null);
          return;
        }

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

// Per-chat lowest server message id returned by history sync. Scroll-back must
// page by this watermark so local-only rows cannot skip an unfetched server gap.
const historyWatermarks = new Map();
let lastHistorySyncStats = null;

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
  retryCounters: new Map(), // msg_id -> retry attempt count
};

export function getCurrentUser() { return state.currentUser; }
export function setCurrentUser(u) { state.currentUser = u; }
export function getWS() { return ws; }

/* ── Navigation ── */
const routes = {
  '#login':    () => showAuth('login'),
  '#register': () => showAuth('register'),
  '#chats':    () => showMain('chats'),
  '#groups':   () => showMain('chats'),
  '#calls':    () => showMain('calls'),
  '#search':   () => showMain('search'),
  '#profile':  () => showMain('profile'),
  '#settings': () => showMain('settings'),
};

function parseHash() {
  const hash = location.hash || '';
  if (hash.startsWith('#chat/')) {
    const raw = hash.slice(6);
    const [userIdPart] = raw.split('?');
    return { screen: 'chat', userId: userIdPart };
  }
  if (hash.startsWith('#secret-chat/')) {
    return { screen: 'chat', userId: hash.slice(13) };
  }
  if (hash.startsWith('#group/')) return { screen: 'group', userId: hash.slice(7) };
  return { screen: hash || '#chats' };
}

let _devicesBackTarget = '#settings';
let unauthorizedHookInstalled = false;

export function setDevicesBackTarget(target) {
  _devicesBackTarget = target || '#settings';
}

export function getDevicesBackTarget() {
  return _devicesBackTarget || '#settings';
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

  /* Nav bar */
  const nav = document.createElement('nav');
  nav.className = 'nav-bar';
  nav.innerHTML = `
    <button class="nav-item" data-screen="chats">
      <span class="nav-icon"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg></span>
      <span>Чаты</span>
    </button>
    <button class="nav-item" data-screen="calls">
      <span class="nav-icon"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg></span>
      <span>Звонки</span>
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

  const chatListScreen = document.createElement('div');
  chatListScreen.className = 'screen chatlist-screen';
  chatListScreen.id = 'screen-chats';

  const callsScreen = document.createElement('div');
  callsScreen.className = 'screen chatlist-screen';
  callsScreen.id = 'screen-calls';

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

  screensWrap.append(chatListScreen, callsScreen, chatScreen, searchScreen, profileScreen, groupScreen, settingsScreen, devicesScreen);
  wrap.append(screensWrap, nav);
  app.appendChild(wrap);

  _mainLayout = { chatListScreen, callsScreen, chatScreen, searchScreen, profileScreen, groupScreen, settingsScreen, devicesScreen, nav };
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
  const mainWrap = document.getElementById('main-wrap');

  const isChat = (screen === 'chat' || screen === 'group');
  if (mainWrap) {
    mainWrap.classList.toggle('in-chat', isChat);
  }

  /* Update nav */
  const activeNavScreen = isChat ? 'chats'
    : (screen === 'devices') ? 'settings'
    : screen;
  layout.nav.querySelectorAll('.nav-item').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.screen === activeNavScreen);
  });

  /* Hide all screens */
  ['chats', 'calls', 'chat', 'search', 'profile', 'group', 'settings', 'devices'].forEach(s => {
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
    } else {
      layout.chatListScreen.classList.remove('active');
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
    } else {
      const chatEl = document.getElementById('screen-chat');
      if (chatEl) chatEl.classList.remove('active');
      const groupEl = document.getElementById('screen-group');
      if (groupEl) groupEl.classList.remove('active');
    }
  } else if (screen === 'calls') {
    layout.callsScreen.classList.add('active');
    layout.callsScreen.innerHTML = '';
    renderCalls(layout.callsScreen);
  } else if (screen === 'search') {
    layout.searchScreen.classList.add('active');
    layout.searchScreen.innerHTML = '';
    renderSearch(layout.searchScreen);
  } else if (screen === 'profile') {
    _devicesBackTarget = '#profile';
    layout.profileScreen.classList.add('active');
    layout.profileScreen.innerHTML = '';
    renderProfile(layout.profileScreen);
  } else if (screen === 'settings') {
    _devicesBackTarget = '#settings';
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
    } else {
      layout.chatListScreen.classList.remove('active');
    }
  }
}

/* ── Router ── */
function handleRoute() {
  const { screen, userId } = parseHash();

  if (!getToken()) {
    if (screen === '#register') {
      showAuth('register');
    } else if (screen === '#login') {
      showAuth('login');
    } else {
      showAuth('welcome');
    }
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
  await initDesktop();
  initTheme();
  localStorage.removeItem("penik_sign_jwk");
  await openDB();
  await primeToken();
  syncServerTime().catch(() => {});
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

  function updateNetworkStatus() {
    let bar = document.getElementById('network-status-bar');
    if (!navigator.onLine) {
      if (!bar) {
        bar = document.createElement('div');
        bar.id = 'network-status-bar';
        bar.className = 'network-status-bar';
        bar.textContent = 'Ожидание сети';
        document.body.prepend(bar);
      }
    } else {
      if (bar) bar.remove();
    }
  }
  window.addEventListener('online', updateNetworkStatus);
  window.addEventListener('offline', updateNetworkStatus);
  updateNetworkStatus();

  window.addEventListener('hashchange', handleRoute);
  window.addEventListener('resize', () => {
    if (!_mainLayout) return;
    const { screen } = parseHash();
    const isWide = window.innerWidth >= 700;
    if (screen === 'chat' || screen === 'group') {
      if (isWide) {
        if (!_mainLayout.chatListScreen.classList.contains('active')) {
          _mainLayout.chatListScreen.classList.add('active');
          if (!_chatListRendered) {
            _chatListRendered = true;
            renderChatList(_mainLayout.chatListScreen);
          }
        }
      } else {
        _mainLayout.chatListScreen.classList.remove('active');
      }
    } else if (screen === '#chats' || screen === 'chats') {
      if (!isWide) {
        const chatEl = document.getElementById('screen-chat');
        if (chatEl) chatEl.classList.remove('active');
        const groupEl = document.getElementById('screen-group');
        if (groupEl) groupEl.classList.remove('active');
      }
    }
  });
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

  // Optimistic file payloads keep upload_msg_id and blob:/local: URLs with no
  // key. They must never win over a final server payload (/api/v1/... + key).
  function isBrokenFilePlaintext(plaintext) {
    if (!plaintext || typeof plaintext !== "string" || !plaintext.startsWith("{")) return false;
    try {
      const p = JSON.parse(plaintext);
      if (!p || p.type !== "file" || !p.file) return false;
      const f = p.file;
      const url = String(f.url || "");
      return Boolean(f.upload_msg_id) ||
        !f.key ||
        url.startsWith("local:") ||
        url.startsWith("blob:") ||
        (Boolean(url) && !url.startsWith("/api/v1/attachments/"));
    } catch {
      return false;
    }
  }

  function isGoodFilePlaintext(plaintext) {
    if (!plaintext || typeof plaintext !== "string" || !plaintext.startsWith("{")) return false;
    try {
      const p = JSON.parse(plaintext);
      if (!p || p.type !== "file" || !p.file) return false;
      const f = p.file;
      const url = String(f.url || "");
      return Boolean(f.key) && url.startsWith("/api/v1/attachments/") && !f.upload_msg_id;
    } catch {
      return false;
    }
  }

  function updateActiveBubblePlaintext(msgId, clientMsgId, newPlaintext) {
    if (!_activeChatCallback) return;
    const ids = [msgId, clientMsgId].filter(Boolean);
    for (const id of ids) {
      const esc = CSS.escape(String(id));
      const bubble = /** @type {(HTMLElement & {_msg?: {plaintext?: string}})|null} */ (
        document.querySelector(`[data-msg-id="${esc}"], [data-client-msg-id="${esc}"]`)
      );
      if (!bubble) continue;
      if (bubble._msg) bubble._msg.plaintext = newPlaintext;
      const txt = bubble.querySelector(".msg-text");
      if (txt) setMsgTextContent(txt, newPlaintext);
      return;
    }
  }

  // Replace a stale optimistic file copy with the final server payload.
  async function tryUpgradeStaleFilePlaintext(localMsg, serverPlaintext) {
    if (!localMsg || !serverPlaintext) return false;
    if (!isBrokenFilePlaintext(localMsg.plaintext)) return false;
    if (!isGoodFilePlaintext(serverPlaintext)) return false;
    const updated = { ...localMsg, plaintext: serverPlaintext };
    await saveMessage(updated);
    updateActiveBubblePlaintext(localMsg.msg_id, localMsg.client_msg_id, serverPlaintext);
    return true;
  }

  // After a successful decrypt, write plaintext onto an existing shell (empty,
  // placeholder, or failed) so a prior partial save cannot swallow the text.
  // Returns true when the record was updated. Does not mark the message edited.
  async function fillMissingPlaintext(msgId, text) {
    if (!text) return false;
    const existing = await getMessage(msgId);
    if (!existing) return false;
    const p = existing.plaintext;
    if (p &&
        !p.startsWith('[Сообщение не расшифровано') &&
        !p.startsWith('[Ошибка') &&
        !isBrokenFilePlaintext(p)) {
      return false;
    }
    if (isBrokenFilePlaintext(p)) return false;
    await updateMessagePlaintext(msgId, text);
    updateActiveBubblePlaintext(msgId, existing.client_msg_id, text);
    return true;
  }

async function onMsgRecvGlobal(payload) {
  const fromUserId = Number(payload.from_user_id);
  const myId = localStorage.getItem("user_id");
  const isMine = String(fromUserId) === String(myId);

  // Prevent duplicate rendering of messages sent by this device
  const existingByServer = payload.msg_id ? await getMessage(payload.msg_id) : null;
  if (existingByServer) {
    return;
  }

  if (payload.client_msg_id) {
    const existingByClient = await getMessageByClientId(payload.client_msg_id);
    if (existingByClient) {
      if (payload.msg_id && String(existingByClient.msg_id) !== String(payload.msg_id)) {
        await updateMsgIdAndDelivered(existingByClient.msg_id, payload.msg_id, 1);
        if (_activeChatCallback) {
          const bubble = /** @type {HTMLElement} */ (
            document.querySelector(`[data-msg-id="${CSS.escape(String(existingByClient.msg_id))}"]`) ||
            document.querySelector(`[data-client-msg-id="${CSS.escape(String(payload.client_msg_id))}"]`)
          );
          if (bubble) {
            bubble.dataset.msgId = String(payload.msg_id);
            const statusEl = /** @type {HTMLElement} */ (bubble.querySelector(".msg-status, .msg-status-wrapper"));
            if (statusEl) statusEl.dataset.msgId = String(payload.msg_id);
          }
        }
      }
      return;
    }
  }

  if (isMine) {
    const chatPartnerId = payload.chat_user_id || fromUserId;
    const resolvedOldId = await findAndResolvePendingSentMessage(chatPartnerId, payload.ts * 1000, payload.msg_id, payload.client_msg_id);
    if (resolvedOldId) {
      let domId = resolvedOldId;
      const pending = pendingAcks.get(String(resolvedOldId));
      if (pending?.tempId) {
        domId = pending.tempId;
      }
      pendingAcks.delete(String(resolvedOldId));

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
      triggerChatListUpdate();
      return;
    }
  }

  const plaintext = payload.plaintext || "";
  const chatPartnerId = payload.chat_user_id || fromUserId;

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
    ws.send(0x04, { msg_id: payload.msg_id });
  }

  if (!isMine) {
    appSounds.playMessageReceived();
    const isCurrentChatOpen = _activeChatCallback && String(_activeChatCallback.userId) === String(chatPartnerId) && !document.hidden;
    if (!isCurrentChatOpen) {
      const avatarUrl = contact?.avatar_url || `${getApiOrigin()}/api/v1/avatar/${chatPartnerId}`;
      showDesktopNotification(contact?.name || "Пользователь", plaintext, `chat_${chatPartnerId}`, () => {
        window.focus();
        location.hash = `#/chat/${chatPartnerId}`;
      }, avatarUrl);
    }
  }

  if (_activeChatCallback && String(_activeChatCallback.userId) === String(chatPartnerId)) {
    _activeChatCallback.fn(inMsg);
    if (ws && payload.msg_id && !isMine) {
      ws.send(0x18, { msg_id: Number(payload.msg_id) });
    }
  }

  if (_chatListUpdateCallback) {
    _chatListUpdateCallback();
  }
}

export function showDesktopNotification(title, body, tag, onClick, avatarUrl = '') {
  const textPreview = getMessagePreview(body) || body;

  if (isDesktop()) {
    sendDesktopNotification(title, textPreview, tag, avatarUrl);
    return;
  }

  if (!("Notification" in window) || Notification.permission !== "granted") {
    return;
  }
  try {
    const notification = new Notification(title, {
      body: textPreview,
      icon: avatarUrl || '/assets/favicon-32x32.png',
      tag: tag || 'penik_msg',
      badge: '/assets/favicon-32x32.png',
      silent: true
    });
    if (onClick) {
      notification.onclick = () => {
        onClick();
        notification.close();
      };
    }
  } catch (err) {
    console.warn("[notification] showDesktopNotification error:", err);
  }
}

export function requestNotificationPermission() {
  if ("Notification" in window && Notification.permission === "default") {
    Notification.requestPermission().catch(() => {});
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
  if (!payload?.msg_id && !payload?.client_msg_id) return;
  const msgId = payload.msg_id;
  const clientMsgId = payload.client_msg_id;
  await updateMessageDelivered(msgId, 1, clientMsgId);
  if (_activeChatCallback) _activeChatCallback.onStatus?.(msgId, "delivered", clientMsgId);
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
      const bubble = /** @type {HTMLElement} */ (document.querySelector(`[data-msg-id="${pending.tempId}"], [data-client-msg-id="${pending.tempId}"]`));
      if (bubble) {
        bubble.dataset.msgId = serverMsgId;
        const statusEl = /** @type {HTMLElement} */ (bubble.querySelector(".msg-status, .msg-status-wrapper"));
        if (statusEl && !statusEl.classList.contains("msg-status-read") && statusEl.querySelectorAll(".chk").length < 2) {
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
  if (!payload?.msg_id && !payload?.client_msg_id) return;
  const msgId = payload.msg_id;
  const clientMsgId = payload.client_msg_id;
  await updateMessageRead(msgId, clientMsgId);
  if (_activeChatCallback) _activeChatCallback.onStatus?.(msgId, "read", clientMsgId);
}

async function onMsgDeleteNotifyGlobal(payload) {
  if (!payload?.msg_id) return;
  try {
    const existing = await getMessage(payload.msg_id) || await getMessageByClientId(payload.msg_id);
    const targetIds = new Set([String(payload.msg_id)]);
    if (existing) {
      if (existing.msg_id) targetIds.add(String(existing.msg_id));
      if (existing.client_msg_id) targetIds.add(String(existing.client_msg_id));
    }

    await deleteMessage(payload.msg_id);

    for (const id of targetIds) {
      const escaped = CSS.escape(id);
      const bubbles = document.querySelectorAll(`[data-msg-id="${escaped}"], [data-client-msg-id="${escaped}"]`);
      bubbles.forEach(b => b.remove());

      const replyRefs = document.querySelectorAll(`.msg-reply-ref[data-reply-to-id="${escaped}"]`);
      replyRefs.forEach(ref => {
        const senderEl = ref.querySelector(".reply-ref-sender");
        const textEl = ref.querySelector(".reply-ref-text");
        const thumbEl = ref.querySelector(".reply-ref-thumb");
        if (senderEl) senderEl.textContent = "Сообщение";
        if (textEl) textEl.textContent = "Сообщение удалено";
        if (thumbEl) thumbEl.remove();
      });
    }

    triggerChatListUpdate();
  } catch (e) {
    console.error("[ws] Error applying delete notify:", e);
  }
}

async function onMsgStatusBatchGlobal(payload) {
  if (!payload || !payload.statuses || !Array.isArray(payload.statuses)) return;
  for (const item of payload.statuses) {
    if (!item.msg_id && !item.client_msg_id) continue;
    const msgId = item.msg_id;
    const clientMsgId = item.client_msg_id;
    if (item.delivered) {
      await updateMessageDelivered(msgId, 1, clientMsgId);
    }
    if (item.read) {
      await updateMessageRead(msgId, clientMsgId);
    }
    if (_activeChatCallback) {
      if (item.read) {
        _activeChatCallback.onStatus?.(msgId, "read", clientMsgId);
      } else if (item.delivered) {
        _activeChatCallback.onStatus?.(msgId, "delivered", clientMsgId);
      }
    }
  }
}

function recordHistoryWatermark(chatUserId, rows) {
  if (chatUserId == null || !Array.isArray(rows) || rows.length === 0) return;
  let minId = Infinity;
  for (const row of rows) {
    const id = Number(row?.id);
    if (Number.isFinite(id) && id < minId) minId = id;
  }
  if (!Number.isFinite(minId)) return;
  const key = String(chatUserId);
  const prev = historyWatermarks.get(key);
  if (prev == null || minId < prev) historyWatermarks.set(key, minId);
}

export function getHistoryWatermark(chatUserId) {
  if (chatUserId == null) return null;
  const value = historyWatermarks.get(String(chatUserId));
  return value == null ? null : value;
}

export function getLastHistorySyncStats() {
  return lastHistorySyncStats;
}

export async function syncMessageHistory(options = {}) {
  try {
    const limit = options.limit || 500;
    let url = `/messages/history?limit=${limit}`;
    if (options.before_id) {
      url += `&before_id=${options.before_id}`;
    } else if (options.after_id) {
      url += `&after_id=${options.after_id}`;
    } else if (!options.chat_user_id) {
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
    if (!history || !Array.isArray(history) || history.length === 0) {
      lastHistorySyncStats = {
        url,
        total: 0,
        at: Date.now(),
        before_id: options.before_id || null,
        chat_user_id: options.chat_user_id || null,
      };
      return [];
    }

    if (options.chat_user_id) {
      recordHistoryWatermark(options.chat_user_id, history);
    }

    const me = state.currentUser;
    if (!me) return [];
    const myId = Number(me.id || me.user_id);

    history.sort((a, b) => a.timestamp - b.timestamp);

    for (const item of history) {
      const existing = await getMessage(item.id);
      const isEdited = item.edited_at && (!existing || !existing.edited_at || (item.edited_at * 1000 > existing.edited_at));
      if (existing && !isEdited) {
        continue;
      }

      const peerId = Number(item.chat_user_id || (Number(item.sender_id) === myId ? item.recipient_id : item.sender_id));
      const text = item.plaintext || "";

      const storedMsg = {
        msg_id: item.id,
        chat_id: String(peerId),
        sender_id: Number(item.sender_id),
        plaintext: text,
        created_at: item.timestamp * 1000,
        delivered: item.delivered != null ? (item.delivered ? 1 : 0) : 1,
        read: item.read ? 1 : 0,
        client_msg_id: item.client_msg_id,
        reply_to_msg_id: item.reply_to_msg_id || null,
        edited_at: item.edited_at ? item.edited_at * 1000 : null,
      };
      await saveMessage(storedMsg);

      if (_activeChatCallback && String(_activeChatCallback.userId) === String(peerId)) {
        _activeChatCallback.fn(storedMsg);
      }
    }

    triggerChatListUpdate();
    return history;
  } catch (err) {
    console.error("Failed to sync message history:", err);
    return [];
  }
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
      addPendingAck(clientMsgId, { tempId: msg.msg_id, userId: msg.chat_id });
      const msgCreatedAt = Number(msg.created_at || getServerTimeMs());
      const tsSec = msgCreatedAt > 1e11 ? Math.floor(msgCreatedAt / 1000) : msgCreatedAt;
      const sent = ws.send(0x01, {
        to_user_id: Number(msg.chat_id),
        plaintext: msg.plaintext || "",
        msg_id: clientMsgId,
        created_at: tsSec,
        reply_to_msg_id: msg.reply_to_msg_id ? String(msg.reply_to_msg_id) : undefined
      });
      if (!sent) {
        pendingAcks.delete(clientMsgId);
        break;
      }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
  } catch (e) {
    console.warn("Failed to flush outbox:", e);
  }
}

async function onMsgEditNotifyGlobal(payload) {
  if (!payload) return;
  try {
    const text = payload.plaintext != null ? payload.plaintext : "";
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
  if (typeof window !== "undefined") {
    window.addEventListener("penik-call-ended", () => {
      triggerChatListUpdate();
    });
  }
  ws.on(0x02, onMsgRecvGlobal);
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
  document.addEventListener('visibilitychange', () => {
    if (ws.isConnected()) {
      ws.send(OP.PRESENCE_UPDATE, { online: !document.hidden });
    }
  });
  registerGroupWSListeners();
  initCallUI();
  callManager.init();

  if (!_pendingAckSweepTimer) {
    _pendingAckSweepTimer = setInterval(() => sweepPendingAcks(), PENDING_ACK_TTL_MS);
    if (_pendingAckSweepTimer.unref) _pendingAckSweepTimer.unref();
  }
  ws.onDisconnect(() => clearPendingAcks());
  ws.onUnauthorized(() => {
    console.warn('[ws] Session revoked or expired by server');
    logout();
  });
  if (!unauthorizedHookInstalled) {
    unauthorizedHookInstalled = true;
    let loggingOut = false;
    window.addEventListener('penik:unauthorized', () => {
      if (loggingOut || !getToken()) return;
      loggingOut = true;
      console.warn('[api] Session dead (401), logging out');
      logout().catch(() => {}).finally(() => { loggingOut = false; });
    });
  }

  ws.onConnect(async () => {
    await flushOutbox();
    await syncMessageHistory();
    await refreshContactProfiles();
    try {
      const groups = await syncGroups();
      for (const g of groups) {
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

if (typeof window !== "undefined") {
  window.__penikSyncStats = getLastHistorySyncStats;
}
