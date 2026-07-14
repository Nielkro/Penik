import { apiGet } from "../api.js";
import {
  x3dhInitiate, encryptMessage, decryptMessage,
  importX25519Priv, encodeKey, decodeKey, verifySignature
} from "../crypto.js";
import {
  getSession, getAnySession, saveSession, saveMessage, getMessages,
  updateMessageDelivered, getContact, saveContact, getAllContacts,
  getIdentity,
} from "../storage.js";
import { navigate, getWS, getCurrentUser, setActiveChatCallback, setChatListUpdateCallback } from "../app.js";
import { avatar, formatTime, formatDate, el, showToast, spinner } from "./components.js";

// ── Chat list ────────────────────────────────────────────────────────────────

export async function renderChatList(container) {
  container.innerHTML = "";

  const header = el("div", { class: "chatlist-header" },
    el("h2", { class: "chatlist-title" }, "Чаты"),
    el("button", { class: "icon-btn", title: "Поиск" },
      el("span", {}, "🔍")
    )
  );
  header.querySelector(".icon-btn").addEventListener("click", () => navigate("#search"));

  const searchInput = el("input", {
    type: "search",
    class: "chatlist-search",
    placeholder: "Поиск чатов…",
  });

  const listEl = el("ul", { class: "chatlist-contacts" });
  container.append(header, searchInput, listEl);

  let contacts = [];
  try {
    contacts = await getAllContacts();
    const me = getCurrentUser();
    const myId = me && (me.id || me.user_id);
    if (myId) {
      contacts = contacts.filter(c => String(c.user_id) !== String(myId));
    }
  } catch {
    contacts = [];
  }

  function renderContacts(list) {
    listEl.innerHTML = "";
    if (!list.length) {
      listEl.appendChild(el("li", { class: "chatlist-empty" }, "Нет чатов. Найдите пользователя."));
      return;
    }
    list.forEach(c => {
      const item = el("li", { class: "chatlist-item" },
        avatar(c, 48),
        el("div", { class: "chatlist-item-info" },
          el("span", { class: "chatlist-item-name" }, c.name || c.nickname || ""),
          el("span", { class: "chatlist-item-preview" }, c.last_message || "")
        ),
        el("span", { class: "chatlist-item-time" }, c.last_ts ? formatTime(c.last_ts) : "")
      );
      item.addEventListener("click", () => navigate(`#chat/${c.user_id}`));
      listEl.appendChild(item);
    });
  }

  renderContacts(contacts);

  searchInput.addEventListener("input", () => {
    const q = searchInput.value.toLowerCase();
    renderContacts(contacts.filter(c =>
      (c.name || "").toLowerCase().includes(q) ||
      (c.nickname || "").toLowerCase().includes(q)
    ));
  });

  setChatListUpdateCallback(() => {
    getAllContacts().then(all => {
      const me = getCurrentUser();
      const myId = me && (me.id || me.user_id);
      let list = all;
      if (myId) {
        list = list.filter(c => String(c.user_id) !== String(myId));
      }
      contacts = list;
      renderContacts(list);
    }).catch(() => {});
  });

  const obs = new MutationObserver(() => {
    if (!container.isConnected) {
      setChatListUpdateCallback(null);
      obs.disconnect();
    }
  });
  obs.observe(document.body, { childList: true, subtree: true });
}

// ── X3DH: establish session with recipient device ────────────────────────────

async function ensureSession(toUserId) {
  const ws = getWS();
  if (!ws) throw new Error("WebSocket не подключён");

  // Fetch recipient keys via WS KEY_FETCH_REQ (0x10)
  const keyBundle = await ws.request(0x10, { user_id: Number(toUserId) }, 0x11);
  if (!keyBundle || !keyBundle.devices || !keyBundle.devices.length) {
    throw new Error("Ключи получателя не найдены");
  }

  // Find the latest active device
  const devices = keyBundle.devices.map(d => ({
    ...d,
    device_id: Number(d.device_id)
  })).sort((a, b) => a.device_id - b.device_id);
  const activeDevice = devices[devices.length - 1];

  // Check if we already have a session for this specific active device
  const existing = await getSession(toUserId, activeDevice.device_id);
  if (existing) return existing;

  const identity = await getIdentity();
  if (!identity) throw new Error("Нет локального ключа идентификации");

  const ourIKPriv = await importX25519Priv(identity.ik_priv_jwk);

  const toRaw = v => v instanceof Uint8Array ? v : decodeKey(v);
  const theirIKRaw  = toRaw(activeDevice.ik_pub);
  const theirSPKRaw = toRaw(activeDevice.spk_pub);
  const theirSPKSig = toRaw(activeDevice.spk_sig);

  let theirIKDH = theirIKRaw;
  if (theirIKRaw.length === 64) {
    theirIKDH = theirIKRaw.slice(0, 32);
    const theirIKSig = theirIKRaw.slice(32, 64);

    const isValid = await verifySignature(theirIKSig, theirSPKSig, theirSPKRaw);
    if (!isValid) {
      throw new Error("Критическая ошибка E2EE: Невалидная подпись SPK! Возможна атака типа Man-in-the-Middle (MitM).");
    }
  } else {
    console.warn("Предупреждение: Получен устаревший 32-байтный ключ идентичности, проверка подписи SPK пропущена.");
  }

  const result = await x3dhInitiate(ourIKPriv, theirIKDH, theirSPKRaw, null);

  const session = {
    user_id:       toUserId,
    device_id:     activeDevice.device_id,
    sharedSecret:  result.sharedSecret,
    ekPubB64:      encodeKey(result.ekPubRaw),
    created_at:    Date.now(),
  };
  await saveSession(session);
  return session;
}

// ── Chat view ────────────────────────────────────────────────────────────────

export async function renderChat(container, userId) {
  container.innerHTML = "";

  let contact = await getContact(Number(userId));
  if (!contact) {
    try {
      const res = await apiGet(`/users/${userId}`);
      contact = res.user || res;
      await saveContact({ ...contact, user_id: Number(userId) });
    } catch {
      contact = { user_id: Number(userId), name: "Неизвестный", nickname: "" };
    }
  }

  const me = getCurrentUser();

  const sidebarToggle = el("button", {
    class: "icon-btn sidebar-toggle",
    title: "Скрыть/Показать список чатов",
    style: "margin-right: 8px; font-size: 18px;"
  }, "◀");

  const wrapEl = document.getElementById("screens-wrap");
  if (wrapEl && wrapEl.classList.contains("sidebar-collapsed")) {
    sidebarToggle.textContent = "▶";
  }

  sidebarToggle.addEventListener("click", () => {
    const wrap = document.getElementById("screens-wrap");
    if (wrap) {
      const isCollapsed = wrap.classList.toggle("sidebar-collapsed");
      sidebarToggle.textContent = isCollapsed ? "▶" : "◀";
    }
  });

  const header = el("div", { class: "chat-header" },
    el("button", { class: "icon-btn chat-back" }, "←"),
    sidebarToggle,
    avatar(contact, 40),
    el("div", { class: "chat-header-info" },
      el("span", { class: "chat-header-name" }, contact.name || contact.nickname),
      el("span", { class: "chat-header-nick" }, contact.nickname ? `@${contact.nickname}` : "")
    )
  );
  header.querySelector(".chat-back").addEventListener("click", () => navigate("#chats"));

  const messagesEl = el("div", { class: "chat-messages" });
  const inputEl    = el("textarea", { class: "chat-input", placeholder: "Сообщение…", rows: "1" });
  const sendBtn    = el("button", { class: "chat-send-btn" }, "➤");
  const inputRow   = el("div", { class: "chat-input-row" }, inputEl, sendBtn);
  const chatWrap   = el("div", { class: "chat-wrap" }, header, messagesEl, inputRow);
  container.appendChild(chatWrap);

  // Load history
  const loadEl = el("div", { class: "chat-loading" }, spinner());
  messagesEl.appendChild(loadEl);
  let messages = [];
  try { messages = await getMessages(userId, 50); } catch { messages = []; }
  loadEl.remove();

  function appendMessage(msg, prepend = false) {
    const isMine = String(msg.sender_id) === String(me && (me.id || me.user_id));
    const ts = msg.created_at || Date.now();

    const statusEl = isMine
      ? el("span", { class: "msg-status" }, msg.delivered ? "✓✓" : "✓")
      : null;
    if (statusEl) statusEl.dataset.msgId = msg.msg_id;

    const bubble = el("div", { class: `msg-bubble ${isMine ? "msg-out" : "msg-in"}` },
      el("span", { class: "msg-text" }, msg.plaintext || ""),
      el("span", { class: "msg-time" }, formatTime(ts)),
      statusEl
    );
    bubble.dataset.msgId = msg.msg_id;

    prepend ? messagesEl.prepend(bubble) : messagesEl.appendChild(bubble);
  }

  messages.forEach(m => appendMessage(m));
  messagesEl.scrollTop = messagesEl.scrollHeight;

  // ── Send ──────────────────────────────────────────────────────────────────

  async function sendMessage() {
    const text = inputEl.value.trim();
    if (!text) return;
    inputEl.value = "";
    inputEl.style.height = "auto";

    const tempId = `tmp-${Date.now()}`;
    const now = Date.now();
    appendMessage({ msg_id: tempId, sender_id: me && (me.id || me.user_id), plaintext: text, created_at: now });
    messagesEl.scrollTop = messagesEl.scrollHeight;

    try {
      const session = await ensureSession(userId);

      const aesCiphertext = await encryptMessage(session.sharedSecret, text);
      const ekPubRaw = decodeKey(session.ekPubB64);

      const cipherBytes = new Uint8Array(32 + aesCiphertext.length);
      cipherBytes.set(ekPubRaw);
      cipherBytes.set(aesCiphertext, 32);

      const msgId = crypto.randomUUID();
      const ws = getWS();
      if (!ws || !ws.isConnected()) throw new Error("Нет соединения");

      ws.send(0x01, {
        to_user_id: Number(userId),
        cipher_bytes: cipherBytes,
        msg_id: msgId,
      });

      const storedMsg = {
        msg_id: msgId,
        chat_id: userId,
        sender_id: me && (me.id || me.user_id),
        plaintext: text,
        created_at: now,
        delivered: 0,
      };
      await saveMessage(storedMsg);
      await saveContact({ ...contact, last_message: text, last_ts: now });

      const oldBubble = messagesEl.querySelector(`[data-msg-id="${tempId}"]`);
      if (oldBubble) oldBubble.dataset.msgId = msgId;

    } catch (err) {
      const oldBubble = messagesEl.querySelector(`[data-msg-id="${tempId}"]`);
      if (oldBubble) oldBubble.classList.add("msg-failed");
      showToast(err.message || "Ошибка отправки", "error");
    }
  }

  sendBtn.addEventListener("click", sendMessage);
  inputEl.addEventListener("keydown", e => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
  });
  inputEl.addEventListener("input", () => {
    inputEl.style.height = "auto";
    inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + "px";
  });

  // Register active chat callback
  setActiveChatCallback(
    userId,
    (inMsg) => {
      appendMessage(inMsg);
      messagesEl.scrollTop = messagesEl.scrollHeight;
    },
    (msgId) => {
      const el = messagesEl.querySelector(`.msg-status[data-msg-id="${msgId}"]`);
      if (el) el.textContent = "✓✓";
    }
  );

  // Cleanup
  const obs = new MutationObserver(() => {
    if (!container.isConnected) {
      setActiveChatCallback(null);
      obs.disconnect();
    }
  });
  obs.observe(document.body, { childList: true, subtree: true });
}
