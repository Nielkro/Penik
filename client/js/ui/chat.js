import { apiGet, apiDelete, uploadAttachment } from "../api.js";
import { encryptFileChaCha20, encodeKey, computeSafetyNumber } from "../crypto.js";
import {
  saveMessage, getMessages, getMessage,
  updateMessageDelivered, updateMessageText, getContact, saveContact, getAllContacts,
  deleteChatData, deleteMessage, saveCachedMedia
} from "../storage.js";
import { navigate, getWS, getCurrentUser, setActiveChatCallback, setChatListUpdateCallback, triggerChatListUpdate, pendingAcks, addPendingAck, encryptMessagePayload, syncMessageHistory } from "../app.js";
import { OP } from "../ws.js";
import {
  avatar, formatTime, formatDate, formatPresence, el, showToast, spinner, svgIcon, stickerIcon, clockIcon, paperclipIcon, sendIcon, closeIcon, checkIcon, doubleCheckIcon,
  showDeleteChatConfirmModal, showFullscreenImage, showConfirmModal, showForwardModal,
  setMsgTextContent, wireMsgTime, wireMsgCopy, attachScrollDownButton, decryptedBlobCache
} from "./components.js";
import { syncGroups, getAllGroups, getGroupMessages, onGroupUpdate } from "../groups.js";
import { buildGroupListItem, showCreateGroupModal } from "./groups.js";
import { onPresenceUpdate, onTypingUpdate } from "../presence.js";
import { callManager } from "../call.js";
import { createStickerPicker } from "./stickers.js";



export const avatarUpdateTimestamps = new Map();

export function getMessagePreviewInfo(plaintext) {
  if (!plaintext) return { text: "", thumb: null, isMedia: false };
  let prefix = "";
  let payloadStr = typeof plaintext === "string" ? plaintext.trim() : "";

  // Handle "Sender: {"v":1,...}" prefix in group preview
  const colonMatch = payloadStr.match(/^([^:]+):\s*(\{.*)$/s);
  if (colonMatch) {
    prefix = colonMatch[1] + ": ";
    payloadStr = colonMatch[2];
  }

  if (payloadStr.startsWith("{")) {
    try {
      const parsed = JSON.parse(payloadStr);
      if (parsed.type === "fwd") {
        const fromPrefix = parsed.from ? `↪ ${parsed.from}: ` : "↪ Переслано: ";
        const inner = getMessagePreviewInfo(parsed.text || "");
        return { text: prefix + fromPrefix + inner.text, thumb: inner.thumb, isMedia: inner.isMedia };
      }
      if (parsed.type === "sticker") {
        return { text: prefix + (parsed.emoji ? `${parsed.emoji} Стикер` : "🖼 Стикер"), thumb: null, isMedia: false };
      }
      if ((parsed.type === "file" || parsed.file) && (parsed.file || parsed.url)) {
        const fileObj = parsed.file || parsed;
        const mime = fileObj.mime || "";
        const fileName = fileObj.name || "";
        const isImage = mime.startsWith("image/") || /\.(png|jpe?g|gif|webp|bmp|svg)$/i.test(fileName);
        const isVideo = mime.startsWith("video/") || /\.(mp4|mov|webm|mkv|avi)$/i.test(fileName);
        const isAudio = mime.startsWith("audio/") || /\.(mp3|ogg|wav|m4a|aac|flac)$/i.test(fileName);

        let text = parsed.text ? parsed.text.trim() : "";
        if (text) {
          if (isImage) text = `📷 ${text}`;
          else if (isVideo) text = `🎬 ${text}`;
          else if (isAudio) text = `🎵 ${text}`;
          else text = `📎 ${text}`;
        } else {
          if (isImage) text = "📷 Фото";
          else if (isVideo) text = "🎬 Видео";
          else if (isAudio) text = "🎵 Аудио";
          else text = fileName ? `📎 ${fileName}` : "📎 Файл";
        }

        if (parsed.fwd_from) {
          text = `↪ ${parsed.fwd_from}: ${text}`;
        }
        let thumb = fileObj.thumb || null;
        if (thumb && !thumb.startsWith("data:")) {
          thumb = "data:image/jpeg;base64," + thumb;
        }
        return { text: prefix + text, thumb, isMedia: true };
      }
      if (parsed.text) {
        const inner = getMessagePreviewInfo(parsed.text);
        return { text: prefix + inner.text, thumb: inner.thumb, isMedia: inner.isMedia };
      }
      if (parsed.type) {
        return { text: prefix + (parsed.type === "file" ? "📎 Файл" : "Сообщение"), thumb: null, isMedia: false };
      }
    } catch (e) {}
  }
  return { text: prefix + payloadStr.replace(/\s+/g, " "), thumb: null, isMedia: false };
}

export function getMessagePreview(plaintext) {
  return getMessagePreviewInfo(plaintext).text;
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
          el("span", { class: "chatlist-item-preview" }, getMessagePreview(selfChatEntry.last_message || ""))
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
        avatarEl.addEventListener("click", (e) => {
          const img = avatarEl.querySelector("img");
          if (img) {
            e.stopPropagation();
            showFullscreenImage(img.src, entry.name || entry.nickname || "");
          }
        });
        const item = el("li", { class: "chatlist-item" },
          avatarEl,
          el("div", { class: "chatlist-item-info" },
            el("span", { class: "chatlist-item-name" }, entry.name || entry.nickname || ""),
            el("span", { class: "chatlist-item-preview" }, getMessagePreview(entry.last_message || ""))
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

  const audioCallBtn = isSelfChat ? null : el("button", {
    class: "icon-btn chat-audio-call",
    title: "Аудиозвонок",
    style: "margin-left: auto; cursor: pointer; background: transparent; border: none; opacity: 0.7; transition: opacity 0.2s; display: flex; align-items: center; justify-content: center; padding: 4px;"
  }, svgIcon("M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z", 20, "var(--text-muted)"));

  if (audioCallBtn) {
    audioCallBtn.addEventListener("mouseenter", () => { audioCallBtn.style.opacity = "1"; });
    audioCallBtn.addEventListener("mouseleave", () => { audioCallBtn.style.opacity = "0.7"; });
    audioCallBtn.addEventListener("click", () => callManager.startCall(Number(userId), false));
  }

  const videoCallBtn = isSelfChat ? null : el("button", {
    class: "icon-btn chat-video-call",
    title: "Видеозвонок",
    style: "margin-left: 8px; cursor: pointer; background: transparent; border: none; opacity: 0.7; transition: opacity 0.2s; display: flex; align-items: center; justify-content: center; padding: 4px;"
  }, svgIcon("m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.934a.5.5 0 0 0-.777-.416L16 11V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4z", 20, "var(--text-muted)"));

  if (videoCallBtn) {
    videoCallBtn.addEventListener("mouseenter", () => { videoCallBtn.style.opacity = "1"; });
    videoCallBtn.addEventListener("mouseleave", () => { videoCallBtn.style.opacity = "0.7"; });
    videoCallBtn.addEventListener("click", () => callManager.startCall(Number(userId), true));
  }

  const safetyBtn = isSelfChat ? null : el("button", {
    class: "icon-btn chat-safety",
    title: "Код безопасности E2EE",
  }, svgIcon("M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z", 18, "var(--accent)"));
  
  if (safetyBtn) {
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
  if (audioCallBtn) headerChildren.push(audioCallBtn);
  if (videoCallBtn) headerChildren.push(videoCallBtn);
  if (safetyBtn) headerChildren.push(safetyBtn);
  if (deleteBtn) headerChildren.push(deleteBtn);

  const header = el("div", { class: "chat-header" }, ...headerChildren);
  header.querySelector(".chat-back").addEventListener("click", () => navigate("#chats"));

  const messagesEl = el("div", { class: "chat-messages", "data-user-id": userId });
  const inputEl    = el("textarea", { class: "chat-input", placeholder: "Сообщение…", rows: "1" });
  const sendBtn    = el("button", { class: "chat-action-btn chat-send-btn", title: "Отправить", style: "display:none;" }, sendIcon(18));
  const attachBtn  = el("button", { class: "chat-action-btn chat-attach-btn", title: "Прикрепить файл" }, paperclipIcon(20));
  const fileInput  = el("input", { type: "file", style: "display:none;" });

  attachBtn.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => {
    if (fileInput.files && fileInput.files[0]) {
      handleFileUpload(fileInput.files[0]);
      fileInput.value = "";
    }
  });

  const updateInputButtons = () => {
    const hasText = Boolean(inputEl.value.trim());
    attachBtn.style.display = hasText ? "none" : "flex";
    sendBtn.style.display = hasText ? "flex" : "none";
  };

  const stickerBtn = el("button", {
    class: "icon-btn chat-sticker-btn",
    title: "Стикеры"
  }, stickerIcon(20));

  const stickerPicker = createStickerPicker((sticker) => {
    sendSticker(sticker);
  });

  stickerBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    stickerPicker.toggle();
  });

  const onDocClick = (e) => {
    const path = e.composedPath ? e.composedPath() : [];
    if (path.includes(stickerPicker.element) || path.includes(stickerBtn) || stickerPicker.element.contains(e.target) || stickerBtn.contains(e.target)) {
      return;
    }
    stickerPicker.toggle(false);
  };
  document.addEventListener("click", onDocClick);

  const inputPill  = el("div", { class: "chat-input-pill" }, stickerBtn, inputEl);
  const inputRow   = el("div", { class: "chat-input-row", style: "position: relative;" }, fileInput, stickerPicker.element, inputPill, attachBtn, sendBtn);
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
      // The user may have navigated away while the profile request was in flight.
      // Patching a detached header is not merely wasted work: it also rebinds
      // avatarEl to a node that is no longer on screen, so the next update writes
      // into a dead subtree.
      if (!chatWrap.isConnected) return;
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

    let currentPresenceStatus = "";
    let typingTimer = null;

    // Every subtitle write goes through here, and every one of them is skipped
    // once the header has left the DOM: presence and typing both arrive
    // asynchronously and would otherwise keep writing into a detached node after
    // the user navigated away.
    const updateStatusText = (status) => {
      if (status) currentPresenceStatus = status;
      if (!chatWrap.isConnected) return;
      if (!typingTimer) nickEl.textContent = currentPresenceStatus;
    };

    (async () => {
      try {
        const res = await apiGet(`/users/${userId}`);
        const status = formatPresence(res);
        updateStatusText(status);
      } catch { /* keep whatever is currently shown */ }
    })();

    const unsubPresence = onPresenceUpdate(userId, (presence) => {
      const status = formatPresence(presence);
      updateStatusText(status);
    });

    const unsubTyping = onTypingUpdate(userId, (isTyping) => {
      if (typingTimer) clearTimeout(typingTimer);
      if (!chatWrap.isConnected) {
        typingTimer = null;
        return;
      }
      if (isTyping) {
        nickEl.textContent = "печатает...";
        nickEl.classList.add("status-typing");
        typingTimer = setTimeout(() => {
          typingTimer = null;
          nickEl.classList.remove("status-typing");
          nickEl.textContent = currentPresenceStatus;
        }, 4000);
      } else {
        typingTimer = null;
        nickEl.classList.remove("status-typing");
        nickEl.textContent = currentPresenceStatus;
      }
    });

    const onLocalSent = (e) => {
      if (String(e.detail?.targetUserId) === String(userId) && e.detail?.storedMsg) {
        appendMessage(e.detail.storedMsg);
        scrollDown.scrollToBottom();
      }
    };
    window.addEventListener("local-msg-sent", onLocalSent);

    const presenceObserver = new MutationObserver(() => {
      if (!document.body.contains(chatWrap)) {
        unsubPresence();
        unsubTyping();
        if (typingTimer) clearTimeout(typingTimer);
        window.removeEventListener("local-msg-sent", onLocalSent);
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
    const lookupId = msg.msg_id || msg.client_msg_id;
    if (lookupId) {
      const escaped = CSS.escape(String(lookupId));
      const existing = messagesEl.querySelector(`[data-msg-id="${escaped}"], [data-client-msg-id="${escaped}"]`);
      if (existing) {
        console.log("[appendMessage] found existing bubble for lookupId:", lookupId);
        const txt = existing.querySelector(".msg-text");
        if (txt) {
          const isFailed = typeof msg.plaintext === "string" &&
            (msg.plaintext.startsWith("[Сообщение не расшифровано") ||
             msg.plaintext.startsWith("[Ошибка расшифрован"));
          if (isFailed) {
            txt.textContent = "🔒 Сообщение не расшифровано";
          } else {
            setMsgTextContent(txt, msg.plaintext || "");
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
      const hasServerId = (typeof msg.msg_id === "number") || (typeof msg.msg_id === "string" && /^\d+$/.test(msg.msg_id));
      const isPending = !hasServerId && (msg.pending === 1 || String(msg.msg_id).startsWith("tmp-") || !msg.server_acked);
      const isDouble = Boolean(msg.read || (msg.delivered && msg.delivered > 0));
      const isRead = Boolean(msg.read);
      const statusClass = "msg-status-wrapper" + (isRead ? " msg-status-read" : "") + (isPending ? " msg-status-pending" : "");
      if (isPending) {
        statusEl = el("span", { class: statusClass, title: "Отправляется..." },
          clockIcon(12, "currentColor")
        );
      } else if (isDouble) {
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
    const editedBadge = (msg.edited_at || msg.is_edited) ? el("span", { class: "msg-edited-badge", style: "font-size:10px;opacity:0.6;margin-right:3px;" }, "ред.") : null;
    const metaEl = el("div", { class: "msg-meta" }, editedBadge, timeEl, statusEl);

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
      replyRefEl = el("div", { class: "msg-reply-ref", "data-reply-to-id": msg.reply_to_msg_id },
        el("div", { class: "reply-ref-details" },
          el("span", { class: "reply-ref-sender" }, "Загрузка..."),
          el("span", { class: "reply-ref-text" }, "...")
        )
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
            const info = getMessagePreviewInfo(parent.plaintext || parent.text || "");
            replyRefEl.querySelector(".reply-ref-sender").textContent = senderName;
            replyRefEl.querySelector(".reply-ref-text").textContent = info.text;
            if (info.thumb) {
              const thumbImg = el("img", { class: "reply-ref-thumb", src: info.thumb });
              replyRefEl.insertBefore(thumbImg, replyRefEl.firstChild);
            }
          } else {
            replyRefEl.querySelector(".reply-ref-sender").textContent = "Сообщение";
            replyRefEl.querySelector(".reply-ref-text").textContent = "Сообщение удалено";
          }
        } catch (e) {
          replyRefEl.remove();
        }
      })();
    }

    const isStructuredPayload = typeof msg.plaintext === "string" && msg.plaintext.trim().startsWith("{");
    const isSingleLine = !isFailed && !isStructuredPayload && !(msg.plaintext || "").includes("\n") && (msg.plaintext || "").length <= 35;
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
    let isStickerMsg = false;
    if (typeof msg.plaintext === "string") {
      const trimmed = msg.plaintext.trim();
      if (trimmed.startsWith("{")) {
        try {
          const p = JSON.parse(trimmed);
          if (p.type === "sticker") {
            isStickerMsg = true;
          }
          if (p.type === "file" && p.file && ((p.file.mime || "").startsWith("image/") || (p.file.mime || "").startsWith("video/"))) {
            isMediaMsg = true;
          }
        } catch (e) {}
      }
    }

    const bubbleClass = `msg-bubble ${isMine ? "msg-out" : "msg-in"}${isFailed ? " msg-failed" : ""}${isMediaMsg ? " msg-media-bubble" : ""}${isStickerMsg ? " sticker-message msg-sticker-bubble" : ""}`;
    const bubble = el("div", { class: bubbleClass },
      ...bubbleChildren
    );
    bubble._msg = msg;
    bubble.dataset.msgId = msg.msg_id;
    if (msg.client_msg_id) {
      bubble.dataset.clientMsgId = msg.client_msg_id;
    }
    wireMsgCopy(bubble, () => (bubble._msg ? bubble._msg.plaintext : msg.plaintext) || "", () => {
      if (isFailed) return;
      const currentText = (bubble._msg ? bubble._msg.plaintext : msg.plaintext) || "";
      const info = getMessagePreviewInfo(currentText);
      setActiveReply({
        msg_id: msg.client_msg_id || msg.msg_id || bubble.dataset.msgId,
        text: info.text,
        thumb: info.thumb,
        sender: isMine ? "Вы" : (contact.name || contact.nickname || "Собеседник")
      });
    }, async () => {
      if (isFailed) {
        // Delete immediately without confirmation modal
        try {
          const realMsgId = (bubble.dataset.msgId && !bubble.dataset.msgId.startsWith("tmp-")) ? bubble.dataset.msgId : (msg.client_msg_id || msg.msg_id || bubble.dataset.clientMsgId || bubble.dataset.msgId);
          await deleteMessage(realMsgId);
          bubble.remove();
          triggerChatListUpdate();
          showToast("Сообщение удалено");
        } catch (err) {
          showToast(err.message || "Не удалось удалить", "error");
        }
        return;
      }

      const { confirmed, deleteForEveryone } = await showDeleteChatConfirmModal("Удалить сообщение?", "Вы уверены, что хотите удалить это сообщение?", true);
      if (!confirmed) return;
      try {
        const realMsgId = (bubble.dataset.msgId && !bubble.dataset.msgId.startsWith("tmp-")) ? bubble.dataset.msgId : (msg.client_msg_id || msg.msg_id || bubble.dataset.clientMsgId || bubble.dataset.msgId);
        await deleteMessage(realMsgId);
        bubble.remove();

        if (deleteForEveryone) {
          getWS().send(OP.MSG_DELETE, {
            msg_id: String(realMsgId),
            chat_id: Number(userId),
            delete_for_everyone: true
          });
        }

        triggerChatListUpdate();
        showToast("Сообщение удалено");
      } catch (err) {
        console.error("Failed to delete message error:", err);
        showToast("Не удалось удалить сообщение", "error");
      }
    }, () => {
      if (isFailed) return;
      const currentText = (bubble._msg ? bubble._msg.plaintext : msg.plaintext) || "";
      const senderName = isMine ? (me?.name || "Вы") : (contact.name || contact.nickname || "Собеседник");
      showForwardModal(currentText, senderName);
    }, (isMine && !isFailed && !isMediaMsg && !isStickerMsg) ? () => {
      setActiveEdit(bubble._msg || msg);
    } : null);

    if (isFailed) {
      const delBtn = el("button", { class: "msg-del-btn", title: "Удалить локально" }, svgIcon("M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2", 14, "var(--danger)"));
      delBtn.addEventListener("click", async (e) => {
        e.stopPropagation();
        try {
          const realMsgId = (bubble.dataset.msgId && !bubble.dataset.msgId.startsWith("tmp-")) ? bubble.dataset.msgId : (msg.client_msg_id || msg.msg_id || bubble.dataset.clientMsgId || bubble.dataset.msgId);
          await deleteMessage(realMsgId);
          bubble.remove();
          triggerChatListUpdate();
          showToast("Сообщение удалено");
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
  requestAnimationFrame(() => {
    scrollDown.scrollToBottom();
    setTimeout(() => scrollDown.scrollToBottom(), 100);
  });

  let isLoadingOlder = false;
  let hasMoreOlder = true;

  async function loadOlderHistory() {
    if (isLoadingOlder || !hasMoreOlder) return;
    isLoadingOlder = true;
    const oldest = messages[0];
    if (!oldest) {
      isLoadingOlder = false;
      return;
    }
    const oldestTs = oldest.created_at || Date.now();
    const oldestServerId = typeof oldest.msg_id === "number" ? oldest.msg_id : (oldest.server_id || null);

    // 1. Try local IndexedDB
    let olderLocal = [];
    try {
      olderLocal = await getMessages(userId, 50, oldestTs);
    } catch (e) {
      olderLocal = [];
    }

    if (olderLocal.length > 0) {
      const scrollHeightBefore = messagesEl.scrollHeight;
      const scrollTopBefore = messagesEl.scrollTop;
      
      messages = [...olderLocal, ...messages];
      olderLocal.slice().reverse().forEach(m => appendMessage(m, true));
      
      messagesEl.scrollTop = messagesEl.scrollHeight - scrollHeightBefore + scrollTopBefore;
      isLoadingOlder = false;
      return;
    }

    // 2. If local DB had no more older messages, query server with before_id
    if (oldestServerId) {
      try {
        const fetched = await syncMessageHistory({
          chat_user_id: userId,
          before_id: oldestServerId,
          limit: 50
        });
        if (!fetched || fetched.length === 0) {
          hasMoreOlder = false;
        } else {
          const freshLocal = await getMessages(userId, 50, oldestTs);
          if (freshLocal.length > 0) {
            const scrollHeightBefore = messagesEl.scrollHeight;
            const scrollTopBefore = messagesEl.scrollTop;
            messages = [...freshLocal, ...messages];
            freshLocal.slice().reverse().forEach(m => appendMessage(m, true));
            messagesEl.scrollTop = messagesEl.scrollHeight - scrollHeightBefore + scrollTopBefore;
          } else {
            hasMoreOlder = false;
          }
        }
      } catch (err) {
        console.warn("Failed to fetch older history from server:", err);
      }
    } else {
      hasMoreOlder = false;
    }
    isLoadingOlder = false;
  }

  messagesEl.addEventListener("scroll", () => {
    if (messagesEl.scrollTop < 60) {
      loadOlderHistory();
    }
  });

  // ── Send ──────────────────────────────────────────────────────────────────

  let activeReply = null;
  let activeEditMessage = null;
  const replyBarContainer = el("div", { style: "display: contents;" });
  chatWrap.insertBefore(replyBarContainer, inputRow);

  function setActiveReply(reply) {
    activeReply = reply;
    if (reply) activeEditMessage = null;
    replyBarContainer.innerHTML = "";
    if (reply) {
      const barChildren = [];
      if (reply.thumb) {
        barChildren.push(el("img", { class: "reply-preview-thumb", src: reply.thumb }));
      }
      barChildren.push(el("div", { class: "reply-preview-content" },
        el("span", { class: "reply-preview-sender" }, reply.sender),
        el("span", { class: "reply-preview-text" }, reply.text)
      ));
      barChildren.push(el("button", { class: "reply-preview-close" }, "✕"));

      const bar = el("div", { class: "reply-preview-bar" }, ...barChildren);
      bar.querySelector(".reply-preview-close").addEventListener("click", () => {
        setActiveReply(null);
      });
      replyBarContainer.appendChild(bar);
      inputEl.focus();
    }
  }

  function setActiveEdit(msg) {
    activeEditMessage = msg;
    if (msg) activeReply = null;
    replyBarContainer.innerHTML = "";
    if (msg) {
      const barChildren = [
        el("div", { class: "reply-preview-content" },
          el("span", { class: "reply-preview-sender", style: "color:var(--accent);" }, "Редактирование"),
          el("span", { class: "reply-preview-text" }, msg.plaintext || "")
        ),
        el("button", { class: "reply-preview-close" }, "✕")
      ];

      const bar = el("div", { class: "reply-preview-bar" }, ...barChildren);
      bar.querySelector(".reply-preview-close").addEventListener("click", () => {
        setActiveEdit(null);
        inputEl.value = "";
      });
      replyBarContainer.appendChild(bar);
      inputEl.value = msg.plaintext || "";
      inputEl.focus();
      inputEl.style.height = "auto";
      const newH = Math.min(inputEl.scrollHeight, 120);
      inputEl.style.height = newH + "px";
      inputEl.style.overflowY = inputEl.scrollHeight > 120 ? "auto" : "hidden";
      sendBtn.disabled = !inputEl.value.trim();
      updateInputButtons();
    } else {
      sendBtn.disabled = !inputEl.value.trim();
      updateInputButtons();
    }
  }

  function updateDomMessageText(msgId, newText, editedAt) {
    const escaped = CSS.escape(String(msgId));
    const bubbles = messagesEl.querySelectorAll(`.msg-bubble[data-msg-id="${escaped}"], .msg-bubble[data-client-msg-id="${escaped}"]`);
    bubbles.forEach(bubble => {
      if (bubble._msg) {
        bubble._msg.plaintext = newText;
        bubble._msg.edited_at = editedAt;
      }
      const textEl = bubble.querySelector(".msg-text");
      if (textEl) {
        setMsgTextContent(textEl, newText);
      }
      const metaEl = bubble.querySelector(".msg-meta");
      if (metaEl && !metaEl.querySelector(".msg-edited-badge")) {
        const badge = el("span", { class: "msg-edited-badge", style: "font-size:10px;opacity:0.6;margin-right:3px;" }, "ред.");
        metaEl.prepend(badge);
      }
    });

    const replyRefs = messagesEl.querySelectorAll(`.msg-reply-ref[data-reply-to-id="${escaped}"]`);
    replyRefs.forEach(r => {
      const rText = r.querySelector(".reply-ref-text");
      if (rText) {
        const info = getMessagePreviewInfo(newText);
        rText.textContent = info.text;
      }
    });
  }

  async function handleFileUpload(file) {
    const textCaption = inputEl.value.trim();
    inputEl.value = "";
    inputEl.style.height = "auto";

    const msgId = crypto.randomUUID();
    const now = Date.now();
    const myId = me && (me.id || me.user_id);
    const localBlobUrl = URL.createObjectURL(file);
    decryptedBlobCache.set(localBlobUrl, localBlobUrl);

    const currentReply = activeReply;
    setActiveReply(null);

    // 1. Optimistic instant local message with circular loader & size badge
    const initialPayload = {
      v: 1,
      type: "file",
      text: textCaption,
      file: {
        upload_msg_id: msgId,
        url: localBlobUrl,
        name: file.name,
        size: file.size,
        mime: file.type || "application/octet-stream",
        key: "",
        thumb: null
      }
    };

    appendMessage({
      msg_id: msgId,
      client_msg_id: msgId,
      sender_id: myId,
      plaintext: JSON.stringify(initialPayload),
      created_at: now,
      reply_to_msg_id: currentReply ? currentReply.msg_id : null,
      pending: 1
    });
    scrollDown.scrollToBottom();

    try {
      const fileBuffer = new Uint8Array(await file.arrayBuffer());

      const localBlob = new Blob([fileBuffer], { type: file.type || "application/octet-stream" });

      const { encryptedBytes, key } = await encryptFileChaCha20(fileBuffer);
      const encryptedBlob = new Blob([encryptedBytes], { type: "application/octet-stream" });

      // 2. Upload to server with progress events
      const cdnUrl = await uploadAttachment(encryptedBlob, file.name, (loaded, total) => {
        window.dispatchEvent(new CustomEvent("penik:upload-progress", {
          detail: { msgId, loaded, total }
        }));
      });

      // Cache original unencrypted BlobUrl locally for sender so no redownload is needed
      decryptedBlobCache.set(cdnUrl, localBlobUrl);
      saveCachedMedia(cdnUrl, localBlob, file.type).catch(() => {});

      // 3. Generate thumbnail if image or video
      let thumbBase64 = null;
      const isImgFile = (file.type && file.type.startsWith("image/")) || /\.(png|jpe?g|gif|webp|bmp|svg)$/i.test(file.name);
      const isVidFile = (file.type && file.type.startsWith("video/")) || /\.(mp4|mov|webm|mkv|avi)$/i.test(file.name);

      if (isImgFile) {
        try {
          thumbBase64 = await createThumbnailBase64(file);
        } catch (e) {}
      } else if (isVidFile) {
        try {
          thumbBase64 = await new Promise((resolve) => {
            const video = document.createElement("video");
            const vUrl = URL.createObjectURL(file);
            video.src = vUrl;
            video.muted = true;
            video.playsInline = true;
            video.preload = "auto";
            let resolved = false;
            const finish = (res) => {
              if (resolved) return;
              resolved = true;
              try { URL.revokeObjectURL(vUrl); } catch (_) {}
              resolve(res);
            };
            const timer = setTimeout(() => finish(null), 4000);

            const capture = () => {
              try {
                if (!video.videoWidth || !video.videoHeight) {
                  return;
                }
                const canvas = document.createElement("canvas");
                let w = video.videoWidth;
                let h = video.videoHeight;
                const maxSide = 180;
                if (w > maxSide || h > maxSide) {
                  if (w > h) { h = Math.round((h * maxSide) / w); w = maxSide; }
                  else { w = Math.round((w * maxSide) / h); h = maxSide; }
                }
                canvas.width = Math.max(1, w);
                canvas.height = Math.max(1, h);
                const ctx = canvas.getContext("2d");
                ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
                clearTimeout(timer);
                finish(canvas.toDataURL("image/jpeg", 0.6));
              } catch (_) {
                finish(null);
              }
            };

            video.onseeked = capture;
            video.onloadeddata = () => {
              if (video.videoWidth && video.videoHeight) capture();
              else {
                try { video.currentTime = 0.001; } catch (_) { capture(); }
              }
            };
            video.onloadedmetadata = () => {
              try { video.currentTime = 0.001; } catch (_) { capture(); }
            };
            video.oncanplay = capture;
            video.onerror = () => finish(null);
            try { video.load(); } catch (_) {}
          });
        } catch (e) {}
      }

      // 4. Construct final file payload
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

      // Update the optimistic bubble in the DOM to completed state
      const escaped = CSS.escape(String(msgId));
      const bubble = messagesEl.querySelector(`[data-msg-id="${escaped}"], [data-client-msg-id="${escaped}"]`);
      if (bubble) {
        const txt = bubble.querySelector(".msg-text");
        if (txt) {
          setMsgTextContent(txt, payloadStr);
        }
      }

      const ws = getWS();
      if (!ws || !ws.isConnected()) throw new Error("Нет соединения");

      const ciphertexts = await encryptMessagePayload(payloadStr, userId, msgId, now);

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

      addPendingAck(msgId, { tempId: msgId, userId: userId });

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
    inputEl.style.overflowY = "hidden";
    sendBtn.disabled = true;
    updateInputButtons();

    if (activeEditMessage) {
      const editMsg = activeEditMessage;
      setActiveEdit(null);
      const msgId = editMsg.client_msg_id || editMsg.msg_id;
      const now = Date.now();

      await updateMessageText(msgId, text, now);
      updateDomMessageText(msgId, text, now);
      triggerChatListUpdate();

      try {
        const ciphertexts = await encryptMessagePayload(text, userId, msgId, now);
        const ws = getWS();
        if (ws && ws.isConnected() && ciphertexts) {
          ws.send(OP.MSG_EDIT, {
            to_user_id: Number(userId),
            msg_id: String(msgId),
            devices: ciphertexts,
            edited_at: Math.floor(now / 1000)
          });
        }
      } catch (err) {
        console.warn("Failed to encrypt edited message:", err);
      }
      return;
    }

    const msgId = crypto.randomUUID();
    const now = Date.now();
    const myId = me && (me.id || me.user_id);
    const currentReply = activeReply;
    setActiveReply(null);

    appendMessage({
      msg_id: msgId,
      client_msg_id: msgId,
      sender_id: myId,
      plaintext: text,
      created_at: now,
      delivered: 0,
      pending: 1,
      reply_to_msg_id: currentReply ? currentReply.msg_id : null
    });
    scrollDown.scrollToBottom();

    let ciphertexts = null;
    try {
      ciphertexts = await encryptMessagePayload(text, userId, msgId, now);
    } catch (encErr) {
      console.warn("Failed to encrypt message immediately (will retry on flush):", encErr);
    }

    const storedMsg = {
      msg_id: msgId,
      client_msg_id: msgId,
      chat_id: userId,
      sender_id: myId,
      plaintext: text,
      created_at: now,
      delivered: 0,
      pending: 1,
      ciphertexts: ciphertexts,
      reply_to_msg_id: currentReply ? currentReply.msg_id : null
    };
    await saveMessage(storedMsg);
    await saveContact({ ...contact, last_message: getMessagePreview(text), last_ts: now });

    const ws = getWS();
    if (ws && ws.isConnected() && ciphertexts) {
      addPendingAck(msgId, { tempId: msgId, userId: userId });
      try {
        const sent = ws.send(0x01, {
          to_user_id: Number(userId),
          devices: ciphertexts,
          msg_id: msgId,
          created_at: Math.floor(now / 1000),
          reply_to_msg_id: currentReply ? String(currentReply.msg_id) : undefined
        });
        if (!sent) {
          pendingAcks.delete(String(msgId));
        }
      } catch (sendErr) {
        console.warn("WebSocket send threw an error:", sendErr);
        pendingAcks.delete(String(msgId));
      }
    }
  }

  async function sendSticker(sticker) {
    const payload = JSON.stringify(sticker);
    const msgId = crypto.randomUUID();
    const now = Date.now();
    const myId = me && (me.id || me.user_id);
    const currentReply = activeReply;
    setActiveReply(null);

    appendMessage({
      msg_id: msgId,
      client_msg_id: msgId,
      sender_id: myId,
      plaintext: payload,
      created_at: now,
      delivered: 0,
      pending: 1,
      reply_to_msg_id: currentReply ? currentReply.msg_id : null
    });
    scrollDown.scrollToBottom();

    let ciphertexts = null;
    try {
      ciphertexts = await encryptMessagePayload(payload, userId, msgId, now);
    } catch (encErr) {
      console.warn("Failed to encrypt sticker immediately:", encErr);
    }

    const storedMsg = {
      msg_id: msgId,
      client_msg_id: msgId,
      chat_id: userId,
      sender_id: myId,
      plaintext: payload,
      created_at: now,
      delivered: 0,
      pending: 1,
      ciphertexts: ciphertexts,
      reply_to_msg_id: currentReply ? currentReply.msg_id : null
    };
    await saveMessage(storedMsg);
    await saveContact({ ...contact, last_message: getMessagePreview(payload), last_ts: now });

    const ws = getWS();
    if (ws && ws.isConnected() && ciphertexts) {
      addPendingAck(msgId, { tempId: msgId, userId: userId });
      try {
        const sent = ws.send(0x01, {
          to_user_id: Number(userId),
          devices: ciphertexts,
          msg_id: msgId,
          created_at: Math.floor(now / 1000),
          reply_to_msg_id: currentReply ? String(currentReply.msg_id) : undefined
        });
        if (!sent) {
          pendingAcks.delete(String(msgId));
        }
      } catch (sendErr) {
        console.warn("WebSocket send error:", sendErr);
        pendingAcks.delete(String(msgId));
      }
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
  let sendTypingTimer = null;
  let isTypingActive = false;

  inputEl.addEventListener("input", () => {
    inputEl.style.height = "auto";
    const newH = Math.min(inputEl.scrollHeight, 120);
    inputEl.style.height = newH + "px";
    inputEl.style.overflowY = inputEl.scrollHeight > 120 ? "auto" : "hidden";
    sendBtn.disabled = !inputEl.value.trim();
    updateInputButtons();

    if (!isTypingActive && userId) {
      isTypingActive = true;
      const socket = getWS();
      if (socket?.isConnected()) {
        socket.send(OP.TYPING, { to_user_id: Number(userId), is_typing: true });
      }
    }
    if (sendTypingTimer) clearTimeout(sendTypingTimer);
    sendTypingTimer = setTimeout(() => {
      isTypingActive = false;
      const socket = getWS();
      if (socket?.isConnected()) {
        socket.send(OP.TYPING, { to_user_id: Number(userId), is_typing: false });
      }
    }, 3000);
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
      const bubble = messagesEl.querySelector(`[data-msg-id="${targetId}"]`);
      if (bubble) {
        bubble.dataset.msgId = msgId;
        const statusWrapper = bubble.querySelector(".msg-status-wrapper");
        if (statusWrapper) {
          statusWrapper.dataset.msgId = msgId;
          statusWrapper.className = "msg-status-wrapper";
          statusWrapper.innerHTML = '<span class="chk chk-1">✓</span>';
        }
      }
    },
    (msgId, status) => {
      const bubble = messagesEl.querySelector(`[data-msg-id="${msgId}"]`);
      if (bubble) {
        const statusWrapper = bubble.querySelector(".msg-status-wrapper");
        if (statusWrapper) {
          statusWrapper.className = "msg-status-wrapper" + (status === "read" ? " msg-status-read" : "");
          statusWrapper.innerHTML = '<span class="chk chk-1">✓</span><span class="chk chk-2">✓</span>';
        }
      }
    },
    (msgId, newText, editedAt) => {
      updateDomMessageText(msgId, newText, editedAt);
    }
  );

  // Cleanup
  const obs = new MutationObserver(() => {
    if (!container.isConnected) {
      document.removeEventListener("click", onDocClick);
      setActiveChatCallback(null);
      obs.disconnect();
    }
  });
  obs.observe(document.body, { childList: true, subtree: true });
}

// calculateSafetyNumber fetches both identity keys and delegates the derivation to
// the one shared implementation in crypto.js. It used to carry its own copy of the
// hashing and formatting, which is how the codebase ended up with three variants
// that could disagree.
export async function calculateSafetyNumber(userId1, userId2) {
  const bundle1 = await apiGet(`/keys/bundle/${userId1}`);
  const bundle2 = await apiGet(`/keys/bundle/${userId2}`);

  if (!bundle1 || !bundle1.devices || bundle1.devices.length === 0) {
    throw new Error("Не удалось получить ключи пользователя 1");
  }
  if (!bundle2 || !bundle2.devices || bundle2.devices.length === 0) {
    throw new Error("Не удалось получить ключи пользователя 2");
  }

  const decode = (b64) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  const keys1 = bundle1.devices.map(d => decode(d.identity_key));
  const keys2 = bundle2.devices.map(d => decode(d.identity_key));
  return computeSafetyNumber(keys1, keys2);
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

export async function sendDirectMessageToUser(targetUserId, text) {
  const ws = getWS();
  if (!ws || !ws.isConnected()) throw new Error("Нет соединения через WebSocket");

  const me = getCurrentUser();
  const myId = me && (me.id || me.user_id);
  const msgId = crypto.randomUUID();
  const now = Date.now();

  const ciphertexts = await encryptMessagePayload(text, targetUserId);

  const storedMsg = {
    msg_id: msgId,
    client_msg_id: msgId,
    chat_id: String(targetUserId),
    sender_id: myId,
    plaintext: text,
    created_at: now,
    delivered: 0,
    ciphertexts: ciphertexts
  };
  await saveMessage(storedMsg);
  window.dispatchEvent(new CustomEvent("local-msg-sent", { detail: { targetUserId: String(targetUserId), storedMsg } }));

  const contact = await getContact(targetUserId);
  if (contact) {
    await saveContact({ ...contact, last_message: getMessagePreview(text), last_ts: now });
    triggerChatListUpdate();
  }

  const sent = ws.send(0x01, {
    to_user_id: Number(targetUserId),
    devices: ciphertexts,
    msg_id: msgId
  });

  if (!sent) throw new Error("Не удалось отправить сообщение");
  return msgId;
}
