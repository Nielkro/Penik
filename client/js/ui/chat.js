import { apiGet, apiDelete } from "../api.js";
import {
  saveMessage, getMessages,
  updateMessageDelivered, getContact, saveContact, getAllContacts,
  deleteChatData
} from "../storage.js";
import { navigate, getWS, getCurrentUser, setActiveChatCallback, setChatListUpdateCallback, triggerChatListUpdate, pendingAcks } from "../app.js";
import { avatar, formatTime, formatDate, el, showToast, spinner, showDeleteChatConfirmModal } from "./components.js";



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

  const deleteBtn = el("button", {
    class: "icon-btn chat-delete",
    title: "Удалить чат",
    style: "margin-left: auto; font-size: 18px; cursor: pointer; background: transparent; border: none; opacity: 0.7; transition: opacity 0.2s;"
  }, "🗑️");
  
  deleteBtn.addEventListener("mouseenter", () => { deleteBtn.style.opacity = "1"; });
  deleteBtn.addEventListener("mouseleave", () => { deleteBtn.style.opacity = "0.7"; });

  deleteBtn.addEventListener("click", async () => {
    const res = await showDeleteChatConfirmModal();
    if (!res.confirmed) {
      return;
    }
    try {
      // 1. Delete on the server
      const url = res.deleteForEveryone ? `/chats/${userId}?everyone=true` : `/chats/${userId}`;
      await apiDelete(url);
      
      // 2. Delete locally in IndexedDB
      await deleteChatData(userId);
      showToast(res.deleteForEveryone ? "Чат удален для всех" : "Чат удален");
      const chatEl = document.getElementById('screen-chat');
      if (chatEl) chatEl.innerHTML = '';
      navigate("#chats");
      triggerChatListUpdate();
    } catch (err) {
      console.error("Failed to delete chat:", err);
      showToast("Не удалось удалить чат", "error");
    }
  });

  const header = el("div", { class: "chat-header" },
    el("button", { class: "icon-btn chat-back" }, "←"),
    sidebarToggle,
    avatar(contact, 40),
    el("div", { class: "chat-header-info" },
      el("span", { class: "chat-header-name" }, contact.name || contact.nickname),
      el("span", { class: "chat-header-nick" }, contact.nickname ? `@${contact.nickname}` : "")
    ),
    deleteBtn
  );
  header.querySelector(".chat-back").addEventListener("click", () => navigate("#chats"));

  const messagesEl = el("div", { class: "chat-messages", "data-user-id": userId });
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
    const isSystem = msg.sender_id === 0;
    if (isSystem) {
      const isKeyChange = (msg.plaintext === "⚠️ Код безопасности изменился!");
      const bubble = el("div", {
        class: "msg-bubble msg-system",
        style: isKeyChange ? "cursor: pointer; text-decoration: underline;" : ""
      },
        el("span", { class: "msg-text" }, msg.plaintext || "")
      );
      if (isKeyChange) {
        bubble.addEventListener("click", () => {
          showSafetyExplanationModal();
        });
      }
      bubble.dataset.msgId = msg.msg_id;
      prepend ? messagesEl.prepend(bubble) : messagesEl.appendChild(bubble);
      return;
    }

    const isMine = String(msg.sender_id) === String(me && (me.id || me.user_id));
    const ts = msg.created_at || Date.now();

    const deliveredAt = msg.delivered_at;
    const statusText = msg.delivered 
      ? (deliveredAt ? `✓✓ ${formatTime(deliveredAt)}` : "✓✓")
      : "✓";
    const statusEl = isMine
      ? el("span", { class: "msg-status" }, statusText)
      : null;
    if (statusEl) statusEl.dataset.msgId = msg.msg_id;

    const metaEl = el("div", { class: "msg-meta" },
      el("span", { class: "msg-time" }, formatTime(ts)),
      statusEl
    );

    const bubble = el("div", { class: `msg-bubble ${isMine ? "msg-out" : "msg-in"}` },
      el("span", { class: "msg-text" }, msg.plaintext || ""),
      metaEl
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
    const myId = me && (me.id || me.user_id);
    appendMessage({ msg_id: tempId, sender_id: myId, plaintext: text, created_at: now });
    messagesEl.scrollTop = messagesEl.scrollHeight;

    const sendFn = async () => {
      const ws = getWS();
      if (!ws || !ws.isConnected()) throw new Error("Нет соединения");

      const msgId = crypto.randomUUID();

      const storedMsg = {
        msg_id: msgId,
        client_msg_id: msgId,
        chat_id: userId,
        sender_id: myId,
        plaintext: text,
        created_at: now,
        delivered: 0
      };
      await saveMessage(storedMsg);
      await saveContact({ ...contact, last_message: text, last_ts: now });

      pendingAcks.set(String(msgId), { tempId: msgId, userId: userId });

      let sent = false;
      try {
        sent = ws.send(0x01, {
          to_user_id: Number(userId),
          plaintext: text,
          msg_id: msgId,
        });
      } catch (sendErr) {
        console.warn("WebSocket send threw an error:", sendErr);
      }

      if (!sent) {
        pendingAcks.delete(String(msgId));
        throw new Error("Не удалось отправить сообщение (ошибка сокета)");
      }

      const oldBubble = messagesEl.querySelector(`[data-msg-id="${tempId}"]`);
      if (oldBubble) oldBubble.dataset.msgId = msgId;
    };

    try {
      await sendFn();
    } catch (err) {
      console.error("SendMessage error:", err);
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
      if (el) el.textContent = `✓✓ ${formatTime(Date.now())}`;
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
