import { getToken, setToken, getUserById } from './api.js';
import {
  openDB, getIdentity, getOrEstablishReceiverSession, saveMessage,
  saveContact, getContact, updateMessageDelivered, clearIndexedDB
} from './storage.js';
import { decryptMessage } from './crypto.js';
import { ws } from './ws.js';
import { renderAuth } from './ui/auth.js';
import { renderChatList, renderChat } from './ui/chat.js';
import { renderProfile } from './ui/profile.js';
import { renderSearch } from './ui/search.js';

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
      if (!localUserId) {
        const identity = await getIdentity();
        if (identity && identity.user_id) {
          localUserId = String(identity.user_id);
          localStorage.setItem("user_id", localUserId);
        }
      }

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
export function logout() {
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
  clearIndexedDB().catch(() => {});
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

async function onMsgRecvGlobal(payload) {
  const fromUserId = Number(payload.from_user_id);
  const fromDeviceId = Number(payload.from_device_id);

  let plaintext = "⚠️ Не удалось расшифровать";
  try {
    const cipherBytes = payload.cipher_bytes;
    if (!cipherBytes || cipherBytes.length < 45) {
      throw new Error("Сообщение слишком короткое");
    }
    const theirEKPub = cipherBytes.slice(0, 32);
    const aesCipherBytes = cipherBytes.slice(32);

    const session = await getOrEstablishReceiverSession(fromUserId, fromDeviceId, theirEKPub);
    plaintext = await decryptMessage(session.sharedSecret, aesCipherBytes);
  } catch (err) {
    console.error("Ошибка дешифрования сообщения:", err);
  }

  const inMsg = {
    msg_id: payload.msg_id,
    chat_id: String(fromUserId),
    sender_id: fromUserId,
    plaintext,
    created_at: payload.ts ? payload.ts * 1000 : Date.now(),
    delivered: 1,
  };

  await saveMessage(inMsg);

  let contact = await getContact(fromUserId);
  if (!contact) {
    try {
      const res = await getUserById(String(fromUserId));
      contact = res.user || res;
    } catch {
      contact = { user_id: fromUserId, name: "Неизвестный", nickname: "" };
    }
  }

  await saveContact({
    ...contact,
    user_id: fromUserId,
    last_message: plaintext,
    last_ts: inMsg.created_at,
  });

  if (ws) {
    ws.send(0x04, { msg_id: payload.msg_id });
  }

  if (_activeChatCallback && String(_activeChatCallback.userId) === String(fromUserId)) {
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

function setupGlobalWSListeners() {
  ws.on(0x02, onMsgRecvGlobal);
  ws.on(0x04, onMsgAckGlobal);
}
