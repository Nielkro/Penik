import { apiGet, apiDelete } from "../api.js";
import {
  saveMessage, getMessages, getMessage,
  updateMessageDelivered, getContact, saveContact, getAllContacts,
  deleteChatData, deleteMessage
} from "../storage.js";
import { navigate, getWS, getCurrentUser, setActiveChatCallback, setChatListUpdateCallback, triggerChatListUpdate, pendingAcks, encryptMessagePayload } from "../app.js";
import {
  avatar, formatTime, formatDate, formatPresence, el, showToast, spinner, svgIcon,
  showDeleteChatConfirmModal, showFullscreenImage, showConfirmModal,
  setMsgTextContent, wireMsgTime, wireMsgCopy, attachScrollDownButton,
} from "./components.js";
import { syncGroups, getAllGroups, getGroupMessages, onGroupUpdate } from "../groups.js";
import { buildGroupListItem, showCreateGroupModal } from "./groups.js";
import { onPresenceUpdate } from "../presence.js";



export const avatarUpdateTimestamps = new Map();

export function getMessagePreview(plaintext) {
  if (!plaintext) return "";
  if (typeof plaintext === "string" && plaintext.startsWith("{")) {
    try {
      const parsed = JSON.parse(plaintext);
      if (parsed.type === "file" && parsed.file) {
        const isImage = (parsed.file.mime || "").startsWith("image/");
        const icon = isImage ? "📷 " : "📎 ";
        return icon + (parsed.text || parsed.file.name || "Файл");
      }
      return parsed.text || plaintext;
    } catch (e) {}
  }
  return plaintext;
}

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

  async function loadSelfChat() {
    if (!selfChatEntry) return;
    try {
      const messages = await getMessages(myId);
      const last = messages[messages.length - 1];
      selfChatEntry.last_message = getMessagePreview(last?.plaintext || "");
      selfChatEntry.last_ts = last?.created_at || 0;
    } catch {
      selfChatEntry.last_message = "";
      selfChatEntry.last_ts = 0;
    }
  }

  let contacts = await loadContacts();
  let groups = await loadGroupEntries();
  await loadSelfChat();

  function render(filter) {
    listEl.innerHTML = "";
    if (selfChatEntry && (!filter || "избранное".includes(filter))) {
      const selfItem = el("li", { class: "chatlist-item" },
        avatar({ name: "Избранное" }, 48),
        el("div", { class: "chatlist-item-info" },
          el("span", { class: "chatlist-item-name" }, "Избранное"),
          el("span", { class: "chatlist-item-preview" }, selfChatEntry.last_message || "")
        ),
        el("span", { class: "chatlist-item-time" }, selfChatEntry.last_ts ? formatTime(selfChatEntry.last_ts) : "")
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
        const avatarEl = avatar(entry, 48, avatarUpdateTimestamps.get(String(entry.user_id)));
        avatarEl.style.cursor = "zoom-in";
        avatarEl.addEventListener("click", (e) => {
          e.stopPropagation();
          const img = avatarEl.querySelector("img");
          if (img) showFullscreenImage(img.src, entry.name || entry.nickname || "");
        });
        const item = el("li", { class: "chatlist-item" },
          avatarEl,
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
    Promise.all([loadContacts(), loadSelfChat()]).then(([list]) => {
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
        last_message = getMessagePreview(last.plaintext || "");
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
    style: "margin-left: auto; cursor: pointer; background: transparent; border: none; opacity: 0.7; transition: opacity 0.2s; display: flex; align-items: center; justify-content: center; padding: 4px;"
  }, svgIcon("M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z", 20, "var(--text-muted)"));
  
  if (safetyBtn) {
    safetyBtn.addEventListener("mouseenter", () => { safetyBtn.style.opacity = "1"; });
    safetyBtn.addEventListener("mouseleave", () => { safetyBtn.style.opacity = "0.7"; });
    safetyBtn.addEventListener("click", () => showSafetyExplanationModal(userId));
  }

  const deleteBtn = isSelfChat ? null : el("button", {
    class: "icon-btn chat-delete",
    title: "Удалить чат",
    style: "margin-left: 12px; cursor: pointer; background: transparent; border: none; opacity: 0.7; transition: opacity 0.2s; display: flex; align-items: center; justify-content: center; padding: 4px;"
  }, svgIcon("M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2", 20, "var(--text-muted)"));
  
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
  avatarEl.style.cursor = "zoom-in";
  avatarEl.addEventListener("click", () => {
    const img = avatarEl.querySelector("img");
    if (img) showFullscreenImage(img.src, contact.name || contact.nickname || "");
  });
  const nameEl = el("span", { class: "chat-header-name" }, contact.name || contact.nickname);
  // Subtitle: nickname until presence resolves, then "в сети" / "был(а) в сети …".
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
  const fileInput  = el("input", { type: "file", style: "display:none;" });
  const attachBtn  = el("button", {
    class: "icon-btn chat-attach-btn",
    title: "Прикрепить файл",
    style: "background:transparent;border:none;color:var(--text-muted);font-size:20px;cursor:pointer;padding:4px 8px;display:flex;align-items:center;justify-content:center;"
  }, "📎");

  attachBtn.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => {
    if (fileInput.files && fileInput.files[0]) {
      handleFileUpload(fileInput.files[0]);
      fileInput.value = "";
    }
  });

  const inputRow   = el("div", { class: "chat-input-row" }, attachBtn, fileInput, inputEl, sendBtn);
  // messagesEl is mounted first so attachScrollDownButton can wrap it in place.
  const chatWrap   = el("div", { class: "chat-wrap" }, header, messagesEl, inputRow);
  container.appendChild(chatWrap);
  const scrollDown = attachScrollDownButton(messagesEl);

  // Resolve the real contact after the shell is mounted, then patch the header.
  if (!isSelfChat) {
    (async () => {
      let resolved = await getContact(Number(userId));
      if (!resolved || resolved.name === "Неизвестный") {
        try {
          const res = await apiGet(`/users/${userId}`);
          const profile = res.user || res;
          resolved = { ...(resolved || {}), ...profile, user_id: Number(userId) };
          await saveContact(resolved);
        } catch (e) {
          console.warn("Failed to fetch contact details in chat:", e);
          if (!resolved) {
            resolved = { user_id: Number(userId), name: "Неизвестный", nickname: "" };
          }
        }
      }
      contact = resolved;
      nameEl.textContent = resolved.name || resolved.nickname || "";
      // Leave nickEl to refreshPresence() below — it owns the subtitle once
      // presence resolves, so it doesn't get clobbered by the nickname here.
      const newAvatar = avatar(resolved, 40, avatarUpdateTimestamps.get(String(userId)));
      newAvatar.style.cursor = "zoom-in";
      newAvatar.addEventListener("click", () => {
        const img = newAvatar.querySelector("img");
        if (img) showFullscreenImage(img.src, resolved.name || resolved.nickname || "");
      });
      avatarEl.replaceWith(newAvatar);
      avatarEl = newAvatar;
    })();

    // Presence isn't cached locally: fetch it once on open, then rely on
    // PRESENCE_UPDATE websocket pushes to keep it live.
    (async () => {
      try {
        const res = await apiGet(`/users/${userId}`);
        const status = formatPresence(res);
        if (status) nickEl.textContent = status;
      } catch { /* keep whatever is currently shown */ }
    })();
    const unsubPresence = onPresenceUpdate(userId, (presence) => {
      const status = formatPresence(presence);
      if (status) nickEl.textContent = status;
    });
    const presenceObserver = new MutationObserver(() => {
      if (!document.body.contains(chatWrap)) {
        unsubPresence();
        presenceObserver.disconnect();
      }
    });
    presenceObserver.observe(document.body, { childList: true, subtree: true });
  }

  // Load history
  const loadEl = el("div", { class: "chat-loading" }, spinner());
  messagesEl.appendChild(loadEl);
  let messages = [];
  try { messages = await getMessages(userId, 50); } catch { messages = []; }
  messages = messages.filter(m => m.plaintext !== "[DELETED]");
  loadEl.remove();

  // Tracks the day label of the last message rendered at the bottom so a date
  // divider can be inserted whenever the day changes.
  let lastRenderedDay = null;

  function makeDateDivider(ts) {
    return el("div", { class: "msg-date-divider" },
      el("span", {}, formatDate(ts))
    );
  }

  // Locate a bubble by any id the reply might reference (server id or the
  // original client_msg_id/UUID), scroll to it and flash a highlight.
  async function scrollToMessage(refId) {
    const candidates = [refId];
    try {
      const parent = await getMessage(refId);
      if (parent) {
        if (parent.msg_id != null) candidates.push(parent.msg_id);
        if (parent.client_msg_id != null) candidates.push(parent.client_msg_id);
      }
    } catch { /* fall back to the raw ref id */ }

    let target = null;
    for (const cand of candidates) {
      if (cand == null) continue;
      target = messagesEl.querySelector(`[data-msg-id="${CSS.escape(String(cand))}"]`);
      if (target) break;
    }
    if (!target) {
      showToast("Исходное сообщение не найдено", "error");
      return;
    }
    target.scrollIntoView({ behavior: "smooth", block: "center" });
    target.classList.remove("msg-highlight");
    // Force reflow so re-adding the class restarts the animation on repeat taps.
    void target.offsetWidth;
    target.classList.add("msg-highlight");
    setTimeout(() => target.classList.remove("msg-highlight"), 1800);
  }

  function appendMessage(msg, prepend = false) {
    if (msg.plaintext === "[DELETED]") return;
    if (msg.msg_id) {
      const existing = messagesEl.querySelector(`[data-msg-id="${msg.msg_id}"]`);
      if (existing) {
        const txt = existing.querySelector(".msg-text");
        if (txt) {
          const isFailed = typeof msg.plaintext === "string" &&
            (msg.plaintext.startsWith("[Сообщение не расшифровано") ||
             msg.plaintext.startsWith("[Ошибка расшифрован"));
          if (isFailed) {
            txt.textContent = "🔒 Сообщение не расшифровано";
          } else {
            setMsgTextContent(txt, getMessagePreview(msg.plaintext || ""));
          }
        }
        return;
      }
    }

    const isSystem = msg.sender_id === 0;
    if (isSystem) {
      const isKeyChange = (msg.plaintext === "⚠️ Код безопасности изменился!");
      const textEl = el("span", { class: "msg-text" });
      setMsgTextContent(textEl, msg.plaintext || "");
      const bubble = el("div", {
        class: "msg-bubble msg-system",
        style: isKeyChange ? "cursor: pointer; text-decoration: underline;" : ""
      }, textEl);
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

    const isSelfChat = Number(userId) === Number(myId);
    let statusEl = null;
    if (isMine && !isSelfChat) {
      const isDouble = Boolean(msg.read || msg.delivered);
      const isRead = Boolean(msg.read);
      const statusClass = "msg-status-wrapper" + (isRead ? " msg-status-read" : "");
      if (isDouble) {
        statusEl = el("span", { class: statusClass },
          el("span", { class: "chk chk-1" }, "✓"),
          el("span", { class: "chk chk-2" }, "✓")
        );
      } else {
        statusEl = el("span", { class: statusClass },
          el("span", { class: "chk chk-1" }, "✓")
        );
      }
      statusEl.dataset.msgId = msg.msg_id;
    }

    const displayTime = ts;
    const timeEl = el("span", { class: "msg-time" });
    wireMsgTime(timeEl, displayTime);
    const metaEl = el("div", { class: "msg-meta" }, timeEl, statusEl);

    const isFailed = typeof msg.plaintext === "string" &&
      (msg.plaintext.startsWith("[Сообщение не расшифровано") ||
       msg.plaintext.startsWith("[Ошибка расшифрован"));

    const textEl = el("span", { class: "msg-text" });
    if (isFailed) {
      textEl.textContent = "🔒 Сообщение не расшифровано";
    } else {
      setMsgTextContent(textEl, msg.plaintext || "");
    }

    let replyRefEl = null;
    if (msg.reply_to_msg_id) {
      replyRefEl = el("div", { class: "msg-reply-ref" },
        el("span", { class: "reply-ref-sender" }, "Загрузка..."),
        el("span", { class: "reply-ref-text" }, "...")
      );
      replyRefEl.addEventListener("click", () => {
        scrollToMessage(msg.reply_to_msg_id);
      });
      // Asynchronously resolve parent message text
      (async () => {
        try {
          const parent = await getMessage(msg.reply_to_msg_id);
          if (parent) {
            const isParentMine = String(parent.sender_id) === String(me && (me.id || me.user_id));
            const senderName = isParentMine ? "Вы" : (contact.name || contact.nickname || "Собеседник");
            replyRefEl.querySelector(".reply-ref-sender").textContent = senderName;
            replyRefEl.querySelector(".reply-ref-text").textContent = getMessagePreview(parent.plaintext || "");
          } else {
            replyRefEl.querySelector(".reply-ref-sender").textContent = "Сообщение";
            replyRefEl.querySelector(".reply-ref-text").textContent = "Исходное сообщение удалено или недоступно";
          }
        } catch (e) {
          replyRefEl.remove();
        }
      })();
    }

    const isSingleLine = !isFailed && !(msg.plaintext || "").includes("\n") && (msg.plaintext || "").length <= 25;
    const bubbleChildren = [];
    if (replyRefEl) bubbleChildren.push(replyRefEl);
    if (isSingleLine) {
      const inlineRow = el("div", { class: "msg-single-line-row" }, textEl, metaEl);
      bubbleChildren.push(inlineRow);
    } else {
      bubbleChildren.push(textEl);
      bubbleChildren.push(metaEl);
    }

    let isMediaMsg = false;
    if (typeof msg.plaintext === "string" && msg.plaintext.startsWith("{")) {
      try {
        const p = JSON.parse(msg.plaintext);
        if (p.type === "file" && p.file && (p.file.mime || "").startsWith("image/")) {
          isMediaMsg = true;
        }
      } catch (e) {}
    }

    const bubbleClass = `msg-bubble ${isMine ? "msg-out" : "msg-in"}${isFailed ? " msg-failed" : ""}${isMediaMsg ? " msg-media-bubble" : ""}`;
    const bubble = el("div", { class: bubbleClass },
      ...bubbleChildren
    );
    bubble.dataset.msgId = msg.msg_id;
    if (!isFailed) {
      wireMsgCopy(bubble, () => msg.plaintext || "", () => {
        setActiveReply({
          msg_id: msg.client_msg_id || msg.msg_id || bubble.dataset.msgId,
          text: getMessagePreview(msg.plaintext || ""),
          sender: isMine ? "Вы" : (contact.name || contact.nickname || "Собеседник")
        });
      }, async () => {
        const ok = await showConfirmModal("Удалить сообщение?", "Сообщение будет удалено на вашем устройстве.");
        if (!ok) return;
        try {
          await deleteMessage(msg.msg_id);
          bubble.remove();
          triggerChatListUpdate();
          showToast("Сообщение удалено");
        } catch (err) {
          showToast("Не удалось удалить сообщение", "error");
        }
      });
    }

    if (isFailed) {
      const delBtn = el("button", { class: "msg-del-btn", title: "Удалить локально" }, svgIcon("M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2", 14, "var(--danger)"));
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

    if (prepend) {
      messagesEl.prepend(bubble);
    } else {
      const day = formatDate(ts);
      if (day && day !== lastRenderedDay) {
        messagesEl.appendChild(makeDateDivider(ts));
        lastRenderedDay = day;
      }
      messagesEl.appendChild(bubble);
    }
    scrollDown.update();
    if (!isMine && msg.msg_id) {
      const socket = getWS();
      if (socket?.isConnected()) socket.send(0x18, { msg_id: Number(msg.msg_id) });
    }
  }

  messages.forEach(m => appendMessage(m));
  scrollDown.scrollToBottom();

  // ── Send ──────────────────────────────────────────────────────────────────

  let activeReply = null;
  const replyBarContainer = el("div", { style: "display: contents;" });
  chatWrap.insertBefore(replyBarContainer, inputRow);

  function setActiveReply(reply) {
    activeReply = reply;
    replyBarContainer.innerHTML = "";
    if (reply) {
      const bar = el("div", { class: "reply-preview-bar" },
        el("div", { class: "reply-preview-content" },
          el("span", { class: "reply-preview-sender" }, reply.sender),
          el("span", { class: "reply-preview-text" }, reply.text)
        ),
        el("button", { class: "reply-preview-close" }, "✕")
      );
      bar.querySelector(".reply-preview-close").addEventListener("click", () => {
        setActiveReply(null);
      });
      replyBarContainer.appendChild(bar);
      inputEl.focus();
    }
  }

  async function handleFileUpload(file) {
    const textCaption = inputEl.value.trim();
    inputEl.value = "";
    inputEl.style.height = "auto";
    showToast("Загрузка и шифрование файла...", "info");

    try {
      const fileBuffer = new Uint8Array(await file.arrayBuffer());
      const { encryptFileChaCha20, encodeKey } = await import("../crypto.js");
      const { uploadVKAttachment } = await import("../api.js");
      const { decryptedBlobCache } = await import("./components.js");

      const localBlob = new Blob([fileBuffer], { type: file.type || "application/octet-stream" });
      const localBlobUrl = URL.createObjectURL(localBlob);

      const { encryptedBytes, key } = await encryptFileChaCha20(fileBuffer);
      const encryptedBlob = new Blob([encryptedBytes], { type: "application/octet-stream" });

      // 2. Upload to VK CDN via Go server
      const cdnUrl = await uploadVKAttachment(encryptedBlob, file.name);

      // Cache original unencrypted BlobUrl locally for sender so no redownload is needed
      decryptedBlobCache.set(cdnUrl, localBlobUrl);

      // 3. Generate thumbnail if image
      let thumbBase64 = null;
      if (file.type.startsWith("image/")) {
        try {
          thumbBase64 = await createThumbnailBase64(file);
        } catch (e) {}
      }

      // 4. Construct file payload
      const filePayload = {
        v: 1,
        type: "file",
        text: textCaption,
        file: {
          url: cdnUrl,
          name: file.name,
          size: file.size,
          mime: file.type || "application/octet-stream",
          key: encodeKey(key),
          thumb: thumbBase64
        }
      };

      const payloadStr = JSON.stringify(filePayload);
      const currentReply = activeReply;
      setActiveReply(null);

      const tempId = `tmp-${Date.now()}`;
      const now = Date.now();
      const myId = me && (me.id || me.user_id);
      appendMessage({
        msg_id: tempId,
        sender_id: myId,
        plaintext: payloadStr,
        created_at: now,
        reply_to_msg_id: currentReply ? currentReply.msg_id : null
      });
      scrollDown.scrollToBottom();

      const ws = getWS();
      if (!ws || !ws.isConnected()) throw new Error("Нет соединения");

      const msgId = crypto.randomUUID();
      const ciphertexts = await encryptMessagePayload(payloadStr, userId);

      const storedMsg = {
        msg_id: msgId,
        client_msg_id: msgId,
        chat_id: userId,
        sender_id: myId,
        plaintext: payloadStr,
        created_at: now,
        delivered: 0,
        ciphertexts: ciphertexts,
        reply_to_msg_id: currentReply ? currentReply.msg_id : null
      };
      await saveMessage(storedMsg);
      await saveContact({ ...contact, last_message: getMessagePreview(payloadStr), last_ts: now });

      pendingAcks.set(String(msgId), { tempId: tempId, userId: userId });

      let sent = false;
      try {
        sent = ws.send(0x01, {
          to_user_id: Number(userId),
          devices: ciphertexts,
          msg_id: msgId,
          reply_to_msg_id: currentReply ? String(currentReply.msg_id) : undefined
        });
      } catch (sendErr) {
        console.warn("WebSocket send threw an error:", sendErr);
      }

      if (!sent) {
        pendingAcks.delete(String(msgId));
        throw new Error("Не удалось отправить файл (ошибка сокета)");
      }

      const oldBubble = messagesEl.querySelector(`[data-msg-id="${tempId}"]`);
      if (oldBubble) oldBubble.dataset.msgId = msgId;
      showToast("Файл отправлен!", "success");
    } catch (err) {
      console.error("handleFileUpload error:", err);
      showToast("Ошибка при отправке файла: " + (err.message || err), "error");
    }
  }

  async function sendMessage() {
    const text = inputEl.value.trim();
    if (!text) return;
    inputEl.value = "";
    inputEl.style.height = "auto";

    const currentReply = activeReply;
    setActiveReply(null);

    const tempId = `tmp-${Date.now()}`;
    const now = Date.now();
    const myId = me && (me.id || me.user_id);
    appendMessage({
      msg_id: tempId,
      sender_id: myId,
      plaintext: text,
      created_at: now,
      reply_to_msg_id: currentReply ? currentReply.msg_id : null
    });
    scrollDown.scrollToBottom();

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
        ciphertexts: ciphertexts,
        reply_to_msg_id: currentReply ? currentReply.msg_id : null
      };
      await saveMessage(storedMsg);
      await saveContact({ ...contact, last_message: text, last_ts: now });

      pendingAcks.set(String(msgId), { tempId: tempId, userId: userId });

      let sent = false;
      try {
        sent = ws.send(0x01, {
          to_user_id: Number(userId),
          devices: ciphertexts,
          msg_id: msgId,
          reply_to_msg_id: currentReply ? String(currentReply.msg_id) : undefined
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
    if (e.key !== "Enter") return;
    if (e.ctrlKey || e.metaKey) {
      // Ctrl/Cmd+Enter inserts a newline at the caret instead of sending.
      e.preventDefault();
      const start = inputEl.selectionStart;
      const end = inputEl.selectionEnd;
      const v = inputEl.value;
      inputEl.value = v.slice(0, start) + "\n" + v.slice(end);
      inputEl.selectionStart = inputEl.selectionEnd = start + 1;
      inputEl.style.height = "auto";
      inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + "px";
    } else if (!e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  });
  inputEl.addEventListener("input", () => {
    inputEl.style.height = "auto";
    inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + "px";
  });

  // Register active chat callback
  setActiveChatCallback(
    userId,
    (inMsg) => {
      const stick = scrollDown.isNearBottom();
      appendMessage(inMsg);
      if (stick) scrollDown.scrollToBottom();
      else scrollDown.update();
    },
    (msgId, clientMsgId) => {
      const targetId = clientMsgId || msgId;
      const statusEl = messagesEl.querySelector(`.msg-status[data-msg-id="${targetId}"]`);
      if (statusEl) {
        statusEl.textContent = "✓✓";
      }
      const bubble = messagesEl.querySelector(`[data-msg-id="${targetId}"]`);
      if (bubble) {
        bubble.dataset.msgId = msgId;
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

async function createThumbnailBase64(file, maxSide = 180) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      let w = img.width;
      let h = img.height;
      if (w > maxSide || h > maxSide) {
        if (w > h) {
          h = Math.round((h * maxSide) / w);
          w = maxSide;
        } else {
          w = Math.round((w * maxSide) / h);
          h = maxSide;
        }
      }
      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      const ctx = canvas.getContext("2d");
      ctx.drawImage(img, 0, 0, w, h);
      resolve(canvas.toDataURL("image/webp", 0.35));
    };
    img.onerror = (err) => {
      URL.revokeObjectURL(url);
      reject(err);
    };
    img.src = url;
  });
}
