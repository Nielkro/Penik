import { apiGet, apiDelete } from "../api.js";
import {
  saveMessage, getMessages,
  updateMessageDelivered, getContact, saveContact, getAllContacts,
  deleteChatData, deleteMessage
} from "../storage.js";
import { navigate, getWS, getCurrentUser, setActiveChatCallback, setChatListUpdateCallback, triggerChatListUpdate, pendingAcks, encryptMessagePayload } from "../app.js";
import { avatar, formatTime, formatDate, el, showToast, spinner, showDeleteChatConfirmModal } from "./components.js";
import { syncGroups, getAllGroups, getGroupMessages, onGroupUpdate } from "../groups.js";
import { buildGroupListItem, showCreateGroupModal } from "./groups.js";



export const avatarUpdateTimestamps = new Map();

// ── Chat list ────────────────────────────────────────────────────────────────

export async function renderChatList(container) {
  container.innerHTML = "";

  const header = el("div", { class: "chatlist-header" },
    el("h2", { class: "chatlist-title" }, "Чаты"),
    el("button", { class: "icon-btn", title: "Создать" },
      el("span", {}, "＋")
    )
  );
  const createBtn = header.querySelector(".icon-btn");
  createBtn.addEventListener("click", () => showCreateMenu(createBtn, () => renderChatList(container)));

  const searchInput = el("input", {
    type: "search",
    class: "chatlist-search",
    placeholder: "Поиск чатов и групп…",
  });

  const listEl = el("ul", { class: "chatlist-contacts" });
  container.append(header, searchInput, listEl);

  const me = getCurrentUser();
  const myId = me && (me.id || me.user_id);

  async function loadContacts() {
    try {
      let all = await getAllContacts();
      if (myId) all = all.filter(c => String(c.user_id) !== String(myId));
      return all.map(c => ({ ...c, _kind: "chat" }));
    } catch {
      return [];
    }
  }

  const selfChatEntry = myId ? {
    user_id: myId,
    name: "Избранное",
    nickname: "",
    last_message: "",
    last_ts: 0
  } : null;

  let contacts = await loadContacts();
  let groups = await loadGroupEntries();

  function render(filter) {
    listEl.innerHTML = "";
    if (selfChatEntry && (!filter || "избранное".includes(filter))) {
      const selfItem = el("li", { class: "chatlist-item" },
        el("div", { class: "chatlist-item-avatar", style: "width:48px;height:48px;border-radius:50%;background:#1a1a2e;display:flex;align-items:center;justify-content:center;font-size:22px;color:#00e676;" }, "\uD83D\uDCDD"),
        el("div", { class: "chatlist-item-info" },
          el("span", { class: "chatlist-item-name" }, "Избранное"),
          el("span", { class: "chatlist-item-preview" }, "")
        )
      );
      selfItem.addEventListener("click", () => navigate(`#chat/${myId}`));
      listEl.appendChild(selfItem);
    }
    // Merge personal chats and groups, then sort by most recent activity.
    // Quiet conversations and pending invites (last_ts 0) sink to the bottom.
    let merged = [...contacts, ...groups];
    if (filter) {
      merged = merged.filter(x =>
        (x.name || "").toLowerCase().includes(filter) ||
        (x.nickname || "").toLowerCase().includes(filter)
      );
    }
    merged.sort((a, b) => (b.last_ts || 0) - (a.last_ts || 0));

    if (!merged.length && !selfChatEntry) {
      listEl.appendChild(el("li", { class: "chatlist-empty" }, "Пусто. Найдите пользователя или создайте группу."));
      return;
    }

    for (const entry of merged) {
      if (entry._kind === "group") {
        listEl.appendChild(buildGroupListItem(entry, async () => {
          groups = await loadGroupEntries();
          render(searchInput.value.trim().toLowerCase());
        }));
      } else {
        const item = el("li", { class: "chatlist-item" },
          avatar(entry, 48, avatarUpdateTimestamps.get(String(entry.user_id))),
          el("div", { class: "chatlist-item-info" },
            el("span", { class: "chatlist-item-name" }, entry.name || entry.nickname || ""),
            el("span", { class: "chatlist-item-preview" }, entry.last_message || "")
          ),
          el("span", { class: "chatlist-item-time" }, entry.last_ts ? formatTime(entry.last_ts) : "")
        );
        item.addEventListener("click", () => navigate(`#chat/${entry.user_id}`));
        listEl.appendChild(item);
      }
    }
  }

  render("");

  searchInput.addEventListener("input", () => {
    render(searchInput.value.trim().toLowerCase());
  });

  // Refresh on personal-chat updates (new/incoming messages, deletions).
  setChatListUpdateCallback(() => {
    loadContacts().then(list => {
      contacts = list;
      render(searchInput.value.trim().toLowerCase());
    }).catch(() => {});
  });

  // Refresh on group updates (new invite, message, roster change).
  const unsubGroups = onGroupUpdate(() => {
    loadGroupEntries().then(list => {
      groups = list;
      render(searchInput.value.trim().toLowerCase());
    }).catch(() => {});
  });

  const obs = new MutationObserver(() => {
    if (!container.isConnected) {
      setChatListUpdateCallback(null);
      unsubGroups();
      obs.disconnect();
    }
  });
  obs.observe(document.body, { childList: true, subtree: true });
}

// Load groups and enrich each with a last-message preview + timestamp so they
// can be interleaved with personal chats and sorted by recent activity.
async function loadGroupEntries() {
  let list = [];
  try {
    list = await syncGroups();
  } catch {
    try { list = await getAllGroups(); } catch { list = []; }
  }
  return Promise.all(list.map(async (g) => {
    let last_ts = 0;
    let last_message = "";
    try {
      const msgs = await getGroupMessages(g.id);
      const last = msgs[msgs.length - 1];
      if (last) {
        last_ts = last.created_at || 0;
        last_message = last.plaintext || "";
      }
    } catch { /* preview falls back to role/empty */ }
    return { ...g, _kind: "group", last_ts, last_message };
  }));
}

// Small popup anchored under the "+" button offering the two creation flows.
function showCreateMenu(anchor, onGroupCreated) {
  const existing = document.getElementById("create-menu-popup");
  if (existing) { existing.remove(); return; }

  const rect = anchor.getBoundingClientRect();
  const menu = el("div", {
    id: "create-menu-popup",
    style: `position:fixed;top:${rect.bottom + 6}px;right:${Math.max(8, window.innerWidth - rect.right)}px;` +
      "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:12px;" +
      "box-shadow:0 8px 32px rgba(0,0,0,0.5);z-index:9999;overflow:hidden;min-width:200px;",
  });

  const mkItem = (icon, label, onClick) => {
    const it = el("button", {
      style: "display:flex;align-items:center;gap:10px;width:100%;padding:12px 16px;" +
        "background:transparent;border:none;color:#fff;font-size:14px;cursor:pointer;text-align:left;",
    }, el("span", { style: "font-size:18px;" }, icon), el("span", {}, label));
    it.addEventListener("mouseenter", () => { it.style.background = "rgba(255,255,255,0.06)"; });
    it.addEventListener("mouseleave", () => { it.style.background = "transparent"; });
    it.addEventListener("click", () => { menu.remove(); onClick(); });
    return it;
  };

  menu.append(
    mkItem("🔍", "Новый чат", () => navigate("#search")),
    mkItem("👥", "Новая группа", () => showCreateGroupModal(onGroupCreated)),
  );

  const onDoc = (e) => {
    if (!menu.contains(e.target) && e.target !== anchor) {
      menu.remove();
      document.removeEventListener("click", onDoc, true);
    }
  };
  setTimeout(() => document.addEventListener("click", onDoc, true), 0);

  document.body.appendChild(menu);
}

// ── Chat view ────────────────────────────────────────────────────────────────

export async function renderChat(container, userId) {
  container.innerHTML = "";

  const me = getCurrentUser();
  const myId = me && (me.id || me.user_id);
  const isSelfChat = Number(userId) === Number(myId);

  // Placeholder contact so the shell can mount synchronously. On a fresh boot
  // the WS onConnect handler concurrently runs history/group sync, which
  // contends IndexedDB and the network; blocking the shell on getContact/
  // apiGet here leaves the active pane blank (just the main screen). Resolve
  // the real contact after mount and patch the header in place.
  let contact = isSelfChat
    ? { user_id: Number(userId), name: "Избранное", nickname: "" }
    : { user_id: Number(userId), name: "…", nickname: "" };

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

  const safetyBtn = isSelfChat ? null : el("button", {
    class: "icon-btn chat-safety",
    title: "Код безопасности E2EE",
    style: "margin-left: auto; font-size: 18px; cursor: pointer; background: transparent; border: none; opacity: 0.7; transition: opacity 0.2s;"
  }, "\uD83D\uDEE1\uFE0F");
  
  if (safetyBtn) {
    safetyBtn.addEventListener("mouseenter", () => { safetyBtn.style.opacity = "1"; });
    safetyBtn.addEventListener("mouseleave", () => { safetyBtn.style.opacity = "0.7"; });
    safetyBtn.addEventListener("click", () => showSafetyExplanationModal(userId));
  }

  const deleteBtn = isSelfChat ? null : el("button", {
    class: "icon-btn chat-delete",
    title: "Удалить чат",
    style: "margin-left: 12px; font-size: 18px; cursor: pointer; background: transparent; border: none; opacity: 0.7; transition: opacity 0.2s;"
  }, "\uD83D\uDDD1\uFE0F");
  
  if (deleteBtn) {
    deleteBtn.addEventListener("mouseenter", () => { deleteBtn.style.opacity = "1"; });
    deleteBtn.addEventListener("mouseleave", () => { deleteBtn.style.opacity = "0.7"; });
  }

  if (deleteBtn) {
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
  }

  let avatarEl = avatar(contact, 40, avatarUpdateTimestamps.get(String(userId)));
  const nameEl = el("span", { class: "chat-header-name" }, contact.name || contact.nickname);
  const nickEl = el("span", { class: "chat-header-nick" }, contact.nickname ? `@${contact.nickname}` : "");
  const headerChildren = [
    el("button", { class: "icon-btn chat-back" }, "\u2190"),
    sidebarToggle,
    avatarEl,
    el("div", { class: "chat-header-info" }, nameEl, nickEl)
  ];
  if (safetyBtn) headerChildren.push(safetyBtn);
  if (deleteBtn) headerChildren.push(deleteBtn);

  const header = el("div", { class: "chat-header" }, ...headerChildren);
  header.querySelector(".chat-back").addEventListener("click", () => navigate("#chats"));

  const messagesEl = el("div", { class: "chat-messages", "data-user-id": userId });
  const inputEl    = el("textarea", { class: "chat-input", placeholder: "Сообщение…", rows: "1" });
  const sendBtn    = el("button", { class: "chat-send-btn" }, "➤");
  const inputRow   = el("div", { class: "chat-input-row" }, inputEl, sendBtn);
  const chatWrap   = el("div", { class: "chat-wrap" }, header, messagesEl, inputRow);
  container.appendChild(chatWrap);

  // Resolve the real contact after the shell is mounted, then patch the header.
  if (!isSelfChat) {
    (async () => {
      let resolved = await getContact(Number(userId));
      if (!resolved) {
        try {
          const res = await apiGet(`/users/${userId}`);
          resolved = res.user || res;
          await saveContact({ ...resolved, user_id: Number(userId) });
        } catch {
          resolved = { user_id: Number(userId), name: "Неизвестный", nickname: "" };
        }
      }
      contact = resolved;
      nameEl.textContent = resolved.name || resolved.nickname || "";
      nickEl.textContent = resolved.nickname ? `@${resolved.nickname}` : "";
      const newAvatar = avatar(resolved, 40, avatarUpdateTimestamps.get(String(userId)));
      avatarEl.replaceWith(newAvatar);
      avatarEl = newAvatar;
    })();
  }

  // Load history
  const loadEl = el("div", { class: "chat-loading" }, spinner());
  messagesEl.appendChild(loadEl);
  let messages = [];
  try { messages = await getMessages(userId, 50); } catch { messages = []; }
  loadEl.remove();

  function appendMessage(msg, prepend = false) {
    if (msg.msg_id) {
      const existing = messagesEl.querySelector(`[data-msg-id="${msg.msg_id}"]`);
      if (existing) {
        const txt = existing.querySelector(".msg-text");
        if (txt) txt.textContent = msg.plaintext || "";
        return;
      }
    }

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
          showSafetyExplanationModal(msg.chat_id);
        });
      }
      bubble.dataset.msgId = msg.msg_id;
      prepend ? messagesEl.prepend(bubble) : messagesEl.appendChild(bubble);
      return;
    }

    const isMine = String(msg.sender_id) === String(me && (me.id || me.user_id));
    const ts = msg.created_at || Date.now();

    const deliveredAt = msg.delivered_at;
    const statusText = msg.read ? "✓✓" : (msg.delivered ? "✓✓" : "✓");
    const statusClass = "msg-status" + (msg.read ? " msg-status-read" : "");
    const statusEl = isMine
      ? el("span", { class: statusClass }, statusText)
      : null;
    if (statusEl) statusEl.dataset.msgId = msg.msg_id;

    // `delivered_at` is the server acknowledgement time, not the message
    // creation time. Using it here makes an old message jump to the time it
    // was delivered/replayed (and can look like it appeared hours later).
    // Chat bubbles must always show when the message was sent/created.
    const displayTime = ts;
    const metaEl = el("div", { class: "msg-meta" },
      el("span", { class: "msg-time" }, formatTime(displayTime)),
      statusEl
    );

    // A message that only exists locally as an undecryptable placeholder is
    // dead weight: it can never be recovered, so mark it visually and let the
    // user delete it from their local store.
    const isFailed = typeof msg.plaintext === "string" &&
      (msg.plaintext.startsWith("[Сообщение не расшифровано") ||
       msg.plaintext.startsWith("[Ошибка расшифрован"));

    const bubble = el("div", { class: `msg-bubble ${isMine ? "msg-out" : "msg-in"}${isFailed ? " msg-failed" : ""}` },
      el("span", { class: "msg-text" }, isFailed ? "🔒 Сообщение не расшифровано" : (msg.plaintext || "")),
      metaEl
    );
    bubble.dataset.msgId = msg.msg_id;

    if (isFailed) {
      const delBtn = el("button", { class: "msg-del-btn", title: "Удалить локально" }, "🗑");
      delBtn.addEventListener("click", async (e) => {
        e.stopPropagation();
        try {
          await deleteMessage(msg.msg_id);
          bubble.remove();
          triggerChatListUpdate();
        } catch (err) {
          showToast(err.message || "Не удалось удалить", "error");
        }
      });
      bubble.appendChild(delBtn);
    }

    prepend ? messagesEl.prepend(bubble) : messagesEl.appendChild(bubble);
    if (!isMine && msg.msg_id) {
      const socket = getWS();
      if (socket?.isConnected()) socket.send(0x18, { msg_id: Number(msg.msg_id) });
    }
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

      // Encrypt payload for all target devices
      const ciphertexts = await encryptMessagePayload(text, userId);

      const storedMsg = {
        msg_id: msgId,
        client_msg_id: msgId,
        chat_id: userId,
        sender_id: myId,
        plaintext: text,
        created_at: now,
        delivered: 0,
        ciphertexts: ciphertexts
      };
      await saveMessage(storedMsg);
      await saveContact({ ...contact, last_message: text, last_ts: now });

      pendingAcks.set(String(msgId), { tempId: msgId, userId: userId });

      let sent = false;
      try {
        sent = ws.send(0x01, {
          to_user_id: Number(userId),
          devices: ciphertexts,
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
      const statusEl = messagesEl.querySelector(`.msg-status[data-msg-id="${msgId}"]`);
      if (statusEl) {
        statusEl.textContent = "✓✓";
        // Update the time shown next to the checkmarks to delivery time
        const timeEl = statusEl.closest(".msg-meta")?.querySelector(".msg-time");
        if (timeEl) timeEl.textContent = formatTime(Date.now());
      }
    },
    (msgId, status) => {
      const statusEl = messagesEl.querySelector(`.msg-status[data-msg-id="${msgId}"]`);
      if (statusEl) {
        statusEl.textContent = "✓✓";
        if (status === "read") {
          statusEl.classList.add("msg-status-read");
        }
      }
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

export async function calculateSafetyNumber(userId1, userId2) {
  const bundle1 = await apiGet(`/keys/bundle/${userId1}`);
  const bundle2 = await apiGet(`/keys/bundle/${userId2}`);

  if (!bundle1 || !bundle1.devices || bundle1.devices.length === 0) {
    throw new Error("Не удалось получить ключи пользователя 1");
  }
  if (!bundle2 || !bundle2.devices || bundle2.devices.length === 0) {
    throw new Error("Не удалось получить ключи пользователя 2");
  }

  const ik1 = bundle1.devices[0].identity_key;
  const ik2 = bundle2.devices[0].identity_key;

  const bytes1 = new Uint8Array(atob(ik1).split("").map(c => c.charCodeAt(0)));
  const bytes2 = new Uint8Array(atob(ik2).split("").map(c => c.charCodeAt(0)));

  const sorted = [bytes1, bytes2].sort((a, b) => {
    for (let i = 0; i < 32; i++) {
      if (a[i] !== b[i]) return a[i] - b[i];
    }
    return 0;
  });

  const concat = new Uint8Array(64);
  concat.set(sorted[0], 0);
  concat.set(sorted[1], 32);

  const hashBuffer = await window.crypto.subtle.digest("SHA-256", concat);
  const hashArray = new Uint8Array(hashBuffer);

  let numStr = "";
  for (let i = 0; i < hashArray.length; i += 2) {
    const val = (hashArray[i] << 8) | hashArray[i+1];
    numStr += String(val).padStart(5, "0").substring(0, 5);
  }

  const blocks = [];
  for (let i = 0; i < numStr.length && blocks.length < 5; i += 5) {
    blocks.push(numStr.substring(i, i + 5));
  }
  return blocks.join(" ");
}

export async function showSafetyExplanationModal(peerId) {
  const me = getCurrentUser();
  const myId = me.id || me.user_id;

  const modal = el("div", {
    style: "position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.8);display:flex;align-items:center;justify-content:center;z-index:9999;padding:16px;box-sizing:border-box;"
  });

  const content = el("div", {
    style: "background:#1e1e1e;border:1px solid rgba(255,255,255,0.1);padding:24px;border-radius:12px;max-width:400px;width:100%;color:#fff;text-align:center;box-shadow: 0 8px 32px rgba(0,0,0,0.5);"
  },
    el("h3", { style: "margin-top:0;font-size:18px;margin-bottom:12px;" }, "Код безопасности E2EE"),
    el("p", { style: "font-size:13px;color:#aaa;line-height:1.4;margin-bottom:20px;" },
      "Сравните эти числа с числами на устройстве вашего собеседника. Если они совпадают, ваше сквозное шифрование на 100% защищено от перехвата."
    )
  );

  const numberEl = el("div", {
    style: "font-size:24px;font-weight:bold;letter-spacing:2px;background:rgba(255,255,255,0.05);padding:16px;border-radius:8px;border:1px dashed rgba(255,255,255,0.2);margin-bottom:20px;color:#00e676;font-family:monospace;"
  }, "Загрузка...");

  content.appendChild(numberEl);

  const closeBtn = el("button", {
    class: "btn-primary",
    style: "width:100%;padding:10px;cursor:pointer;",
    onclick: () => modal.remove()
  }, "Закрыть");

  content.appendChild(closeBtn);
  modal.appendChild(content);
  document.body.appendChild(modal);

  try {
    const safetyNumber = await calculateSafetyNumber(myId, peerId);
    numberEl.textContent = safetyNumber;
  } catch (err) {
    numberEl.textContent = "Ошибка загрузки";
    console.error("Error calculating safety number:", err);
  }
}
