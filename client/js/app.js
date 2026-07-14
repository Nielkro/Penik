import { getToken, setToken, getUserById } from './api.js';
import {
  openDB, getIdentity, getOrEstablishReceiverSession, saveMessage,
  saveContact, getContact, updateMessageDelivered, clearIndexedDB,
  getSession, saveSession, saveSkippedKey, getAndRemoveSkippedKey,
  updateMsgId
} from './storage.js';
import {
  decryptMessage, importX25519Priv, importX25519Pub,
  diffieHellman, generateDH, kdf_rk, kdf_ck
} from './crypto.js';
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

export const pendingAcksQueue = [];

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

export function triggerChatListUpdate() {
  if (_chatListUpdateCallback) {
    _chatListUpdateCallback();
  }
}

async function onMsgRecvGlobal(payload) {
  const fromUserId = Number(payload.from_user_id);
  const fromDeviceId = Number(payload.from_device_id);

  let plaintext = "⚠️ Не удалось расшифровать";
  try {
    const cipherBytes = payload.cipher_bytes;
    if (!cipherBytes || cipherBytes.length < 13) {
      throw new Error("Сообщение слишком короткое");
    }

    const session = await getSession(fromUserId, fromDeviceId);
    let decryptedText = null;
    let isBootstrapFallback = false;

    if (session) {
      try {
        if (cipherBytes.length < 52) {
          throw new Error("Стандартный пакет слишком короткий");
        }
        const dh_pub = cipherBytes.slice(0, 32);
        const n = read32BE(cipherBytes, 32);
        const pn = read32BE(cipherBytes, 36);
        const iv = cipherBytes.slice(40, 52);
        const ciphertext = cipherBytes.slice(52);
        const aad = cipherBytes.slice(0, 40);

        const dhPubHex = u8ToHex(dh_pub);
        const skippedKey = await getAndRemoveSkippedKey(fromUserId, fromDeviceId, dhPubHex, n);
        if (skippedKey) {
          decryptedText = await decryptMessage(skippedKey, cipherBytes.slice(40), aad);
        } else {
          const isSameDH = (dhPubHex === u8ToHex(session.their_dh_public_raw));
          if (isSameDH && n < session.n_recv) {
            throw new Error("Сообщение устарело или является дубликатом");
          }

          let rootKey = session.root_key;
          let recvChainKey = session.recv_chain_key;
          let sendChainKey = session.send_chain_key;
          let ourDhPrivateJwk = session.our_dh_private_jwk;
          let ourDhPublicRaw = session.our_dh_public_raw;
          let theirDhPub = session.their_dh_public_raw;
          let n_recv = session.n_recv;
          let n_send = session.n_send;
          let pn_state = session.pn;

          const pendingSkippedKeys = [];
          async function skipMessageKeys(limit) {
            if (recvChainKey === null) return;
            if (n_recv + 1000 < limit) {
              throw new Error("Слишком много пропущенных ключей");
            }
            while (n_recv < limit) {
              const { newChainKey, messageKey } = await kdf_ck(recvChainKey);
              recvChainKey = newChainKey;
              const currentDhPubHex = u8ToHex(theirDhPub);
              pendingSkippedKeys.push({ dhPubHex: currentDhPubHex, n: n_recv, keyBytes: messageKey });
              n_recv += 1;
            }
          }

          if (!isSameDH) {
            await skipMessageKeys(pn);
            const ourPrivKey = await importX25519Priv(ourDhPrivateJwk);
            const theirPubImported = await importX25519Pub(dh_pub);

            const sharedSecret1 = await diffieHellman(ourPrivKey, theirPubImported);
            const step1 = await kdf_rk(rootKey, sharedSecret1, "DoubleRatchetRoot");
            rootKey = step1.newRootKey;
            recvChainKey = step1.chainKey;

            const newOurDH = await generateDH();
            const sharedSecret2 = await diffieHellman(newOurDH.privateKey, theirPubImported);
            const step2 = await kdf_rk(rootKey, sharedSecret2, "DoubleRatchetRoot");
            rootKey = step2.newRootKey;
            sendChainKey = step2.chainKey;

            ourDhPrivateJwk = newOurDH.privJwk;
            ourDhPublicRaw = newOurDH.pubRaw;
            theirDhPub = dh_pub;
            pn_state = n_send;
            n_send = 0;
            n_recv = 0;
          }

          await skipMessageKeys(n);

          const { newChainKey, messageKey } = await kdf_ck(recvChainKey);
          decryptedText = await decryptMessage(messageKey, cipherBytes.slice(40), aad);

          for (const sk of pendingSkippedKeys) {
            await saveSkippedKey(fromUserId, fromDeviceId, sk.dhPubHex, sk.n, sk.keyBytes);
          }

          session.root_key = rootKey;
          session.recv_chain_key = newChainKey;
          session.send_chain_key = sendChainKey;
          session.our_dh_private_jwk = ourDhPrivateJwk;
          session.our_dh_public_raw = ourDhPublicRaw;
          session.their_dh_public_raw = theirDhPub;
          session.n_recv = n + 1;
          session.n_send = n_send;
          session.pn = pn_state;
          session.session_init_ek = null;

          await saveSession(session);
        }
      } catch (err) {
        console.warn("Standard packet decryption failed, trying Bootstrap fallback...", err);
        isBootstrapFallback = true;
      }
    }

    if (!session || isBootstrapFallback) {
      if (cipherBytes.length < 84) {
        throw new Error("Bootstrap-пакет слишком короткий");
      }
      const session_init_ek = cipherBytes.slice(0, 32);
      const dh_pub = cipherBytes.slice(32, 64);
      const n = read32BE(cipherBytes, 64);
      const pn = read32BE(cipherBytes, 68);
      const iv = cipherBytes.slice(72, 84);
      const ciphertext = cipherBytes.slice(84);
      const aad = cipherBytes.slice(0, 72);

      const newSession = await getOrEstablishReceiverSession(fromUserId, fromDeviceId, session_init_ek, dh_pub);

      let rootKey = newSession.root_key;
      let recvChainKey = newSession.recv_chain_key;
      let sendChainKey = newSession.send_chain_key;
      let ourDhPrivateJwk = newSession.our_dh_private_jwk;
      let ourDhPublicRaw = newSession.our_dh_public_raw;
      let theirDhPub = newSession.their_dh_public_raw;
      let n_recv = newSession.n_recv;
      let n_send = newSession.n_send;
      let pn_state = newSession.pn;

      const pendingSkippedKeys = [];
      async function skipMessageKeys(limit) {
        if (recvChainKey === null) return;
        if (n_recv + 1000 < limit) {
          throw new Error("Слишком много пропущенных ключей");
        }
        while (n_recv < limit) {
          const { newChainKey, messageKey } = await kdf_ck(recvChainKey);
          recvChainKey = newChainKey;
          const dhPubHex = u8ToHex(theirDhPub);
          pendingSkippedKeys.push({ dhPubHex, n: n_recv, keyBytes: messageKey });
          n_recv += 1;
        }
      }

      await skipMessageKeys(n);

      const { newChainKey, messageKey } = await kdf_ck(recvChainKey);
      decryptedText = await decryptMessage(messageKey, cipherBytes.slice(72), aad);

      for (const sk of pendingSkippedKeys) {
        await saveSkippedKey(fromUserId, fromDeviceId, sk.dhPubHex, sk.n, sk.keyBytes);
      }

      newSession.root_key = rootKey;
      newSession.recv_chain_key = newChainKey;
      newSession.send_chain_key = sendChainKey;
      newSession.our_dh_private_jwk = ourDhPrivateJwk;
      newSession.our_dh_public_raw = ourDhPublicRaw;
      newSession.their_dh_public_raw = theirDhPub;
      newSession.n_recv = n + 1;
      newSession.n_send = n_send;
      newSession.pn = pn_state;
      newSession.session_init_ek = null;

      await saveSession(newSession);
    }

    plaintext = decryptedText;
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

async function onMsgAckReceivedGlobal(payload) {
  const serverMsgId = payload.msg_id;
  if (!serverMsgId) return;

  const pending = pendingAcksQueue.shift();
  if (!pending) return;

  try {
    await updateMsgId(pending.tempId, serverMsgId);
    await updateMessageDelivered(serverMsgId, 1);

    if (_activeChatCallback && String(_activeChatCallback.userId) === String(pending.userId)) {
      const bubble = document.querySelector(`[data-msg-id="${pending.tempId}"]`);
      if (bubble) {
        bubble.dataset.msgId = serverMsgId;
        const statusEl = bubble.querySelector(".msg-status");
        if (statusEl) {
          statusEl.dataset.msgId = serverMsgId;
        }
      }
      _activeChatCallback.onAck(serverMsgId);
    }
  } catch (err) {
    console.error("Failed to process MSG_ACK:", err);
  }
}

function setupGlobalWSListeners() {
  ws.on(0x02, onMsgRecvGlobal);
  ws.on(0x03, onMsgAckReceivedGlobal);
  ws.on(0x04, onMsgAckGlobal);
  ws.on(0x05, onOfflineBatchGlobal);
}
