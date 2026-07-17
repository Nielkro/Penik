import { getToken, setToken, getUserById, apiGet, apiPost } from './api.js';
import {
  openDB, saveMessage,
  saveContact, getContact, updateMessageDelivered, clearIndexedDB,
  updateMsgId, updateMsgIdAndDelivered, getMessage, getAllContacts, getAllMessages,
  findAndResolvePendingSentMessage, deleteChatData, savePreKeyPrivate
} from './storage.js';
import { ws } from './ws.js';
import { renderAuth } from './ui/auth.js';
import { renderChatList, renderChat } from './ui/chat.js';
import { renderProfile } from './ui/profile.js';
import { renderSearch } from './ui/search.js';

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
      
      await apiPost("/keys/prekeys", { prekeys: uploadItems });
      
      for (const otpk of prekeys) {
        await savePreKeyPrivate(otpk.keyId, otpk.privateKey);
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
  const fromUserId = Number(payload.from_user_id);
  const plaintext = payload.plaintext || "";

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
    ws.send(0x04, { msg_id: payload.msg_id });
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

export async function syncMessageHistory() {
  try {
    const limit = 100;
    const history = await apiGet(`/messages/history?limit=${limit}`);
    if (!history || !Array.isArray(history) || history.length === 0) return;

    const me = state.currentUser;
    if (!me) return;
    const myId = Number(me.id || me.user_id);

    // Sort by timestamp ascending just to be sure
    history.sort((a, b) => a.timestamp - b.timestamp);

    for (const item of history) {
      const existing = await getMessage(item.id);
      if (existing) {
        continue;
      }

      const peerId = Number(item.chat_user_id || (Number(item.sender_id) === myId ? item.recipient_id : item.sender_id));

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
        last_message: item.plaintext,
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
        plaintext: item.plaintext,
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
      const sent = ws.send(0x01, {
        to_user_id: Number(msg.chat_id),
        devices: msg.ciphertexts,
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
  ws.on(0x03, onMsgAckReceivedGlobal);
  ws.on(0x04, onMsgAckGlobal);
  ws.on(0x05, onOfflineBatchGlobal);
  ws.on(0x08, onChatPurgeGlobal);
  ws.onConnect(async () => {
    await flushOutbox();
    await syncMessageHistory();
  });
}
