import {
  createGroup, syncGroups, refreshMembers, acceptInvitation, declineInvitation,
  inviteMember, removeMember, changeMemberRole, sendGroupMessage,
  getAllGroups, getGroupMessages, onGroupUpdate, backfillCurrentKey,
  renameGroup, uploadGroupAvatar, rotateAndDistribute
} from "../groups.js";
import { apiGet, getUserById, uploadVKAttachment } from "../api.js";
import { encryptFileChaCha20, encodeKey } from "../crypto.js";
import { getGroupMembers, getAllContacts, getContact, saveContact, getGroupMessage, saveCachedMedia } from "../storage.js";
import { navigate, getCurrentUser, triggerChatListUpdate } from "../app.js";
import {
  el, avatar, groupAvatar, groupAvatarUpdateTimestamps, formatTime, formatPresence,
  showToast, spinner, showConfirmModal, showPromptModal, showFullscreenImage, showForwardModal,
  setMsgTextContent, wireMsgTime, wireMsgCopy, attachScrollDownButton, decryptedBlobCache
} from "./components.js";
import { onPresenceUpdate } from "../presence.js";
import { getMessagePreview, getMessagePreviewInfo } from "./chat.js";

// Role labels in Russian for the members UI.
const ROLE_LABEL = { owner: "владелец", admin: "админ", member: "участник" };
const roleLabel = (r) => ROLE_LABEL[r] || r;
const memberName = (m) => m.name || m.nickname || m.username || `#${m.user_id}`;
const isPrivileged = (role) => role === "owner" || role === "admin";

const OVERLAY_STYLE = "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:9999;padding:16px;";
const BOX_STYLE = "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:24px;width:100%;max-width:440px;box-shadow:0 8px 32px rgba(0,0,0,0.5);display:flex;flex-direction:column;";

// ── Group list ───────────────────────────────────────────────────────────────

// Build a single <li> for a group. Pending invites render accept/decline
// actions instead of opening the chat; active groups navigate on click.
// `onChange` is invoked after accept/decline so the surrounding list can
// refresh itself (works for both the groups-only and unified lists).
export function buildGroupListItem(g, onChange) {
  const isPending = g.status === "pending";
  const previewSpan = el("span", { class: "chatlist-item-preview" }, isPending ? "Приглашение в группу" : "...");

  const info = el("div", { class: "chatlist-item-info" },
    el("span", { class: "chatlist-item-name" }, g.name),
    previewSpan,
  );

  if (!isPending) {
    getGroupMessages(g.id).then(async (msgs) => {
      const last = msgs && msgs[msgs.length - 1];
      if (last) {
        const my = getCurrentUser();
        const myId = my && (my.id || my.user_id);
        let senderName = "";
        if (Number(last.sender_user_id) === Number(myId)) {
          senderName = "Вы";
        } else {
          const members = await getGroupMembers(g.id).catch(() => []);
          const sender = members.find(m => Number(m.user_id) === Number(last.sender_user_id));
          if (sender) {
            senderName = sender.name || sender.nickname || sender.username || `#${last.sender_user_id}`;
          } else {
            let u = await getContact(Number(last.sender_user_id));
            if (!u || u.name === "Неизвестный") {
              try {
                const res = await getUserById(String(last.sender_user_id));
                const profile = res.user || res;
                u = { ...(u || {}), ...profile, user_id: Number(last.sender_user_id) };
                await saveContact(u);
              } catch (err) {
                console.warn("[groups] preview: failed to fetch sender profile", err);
              }
            }
            if (u) {
              senderName = u.name || u.nickname || u.username || `#${last.sender_user_id}`;
            } else {
              senderName = `#${last.sender_user_id}`;
            }
          }
        }
        previewSpan.textContent = `${senderName}: ${last.plaintext || ""}`;
      } else {
        previewSpan.textContent = g.role ? `Роль: ${roleLabel(g.role)}` : "";
      }
    }).catch((err) => {
      console.error("[groups] preview load failed", err);
      previewSpan.textContent = g.role ? `Роль: ${roleLabel(g.role)}` : "";
    });
  }

  const avatarEl = groupAvatar(g, 48);
  avatarEl.style.cursor = "zoom-in";
  avatarEl.addEventListener("click", (e) => {
    e.stopPropagation();
    const img = avatarEl.querySelector("img");
    if (img) showFullscreenImage(img.src, g.name || "");
  });

  const item = el("li", { class: "chatlist-item" },
    avatarEl,
    info,
  );

  if (isPending) {
    const acceptBtn = el("button", { class: "btn-primary", style: "padding:6px 12px;font-size:13px;" }, "Принять");
    const declineBtn = el("button", { class: "btn-secondary", style: "padding:6px 12px;font-size:13px;" }, "Отклонить");
    acceptBtn.addEventListener("click", async (e) => {
      e.stopPropagation();
      acceptBtn.disabled = declineBtn.disabled = true;
      try {
        await acceptInvitation(g.id);
        showToast("Вы вступили в группу");
        if (onChange) await onChange();
        navigate(`#group/${g.id}`);
      } catch (err) {
        showToast(err.message || "Не удалось принять приглашение");
        acceptBtn.disabled = declineBtn.disabled = false;
      }
    });
    declineBtn.addEventListener("click", async (e) => {
      e.stopPropagation();
      acceptBtn.disabled = declineBtn.disabled = true;
      try {
        await declineInvitation(g.id);
        showToast("Приглашение отклонено");
        if (onChange) await onChange();
      } catch (err) {
        showToast(err.message || "Не удалось отклонить приглашение");
        acceptBtn.disabled = declineBtn.disabled = false;
      }
    });
    item.appendChild(el("div", { style: "display:flex;gap:8px;align-items:center;" }, acceptBtn, declineBtn));
  } else {
    if (g.last_ts) info.after(el("span", { class: "chatlist-item-time" }, formatTime(g.last_ts)));
    item.addEventListener("click", () => navigate(`#group/${g.id}`));
  }
  return item;
}

export async function renderGroupList(container) {
  container.innerHTML = "";

  const header = el("div", { class: "chatlist-header" },
    el("h2", { class: "chatlist-title" }, "Группы"),
    el("button", { class: "icon-btn", title: "Создать группу" }, el("span", {}, "＋")),
  );
  header.querySelector(".icon-btn").addEventListener("click", () => showCreateGroupModal(() => renderGroupList(container)));

  const listEl = el("ul", { class: "chatlist-contacts" });
  container.append(header, listEl);

  // Live-update the list when membership changes elsewhere (e.g. a new invite
  // arriving over WS). Re-subscribe on each render but keep only one active
  // listener by tearing down the previous one first.
  if (container._groupListUnsub) container._groupListUnsub();
  let stale = false;
  const unsub = onGroupUpdate((evt) => {
    if (evt.type !== "members" && evt.type !== "avatar") return;
    if (stale || !document.body.contains(container)) { unsub(); return; }
    stale = true;
    renderGroupList(container);
  });
  container._groupListUnsub = () => { stale = true; unsub(); };

  const loadEl = el("div", { class: "chat-loading" }, spinner());
  listEl.appendChild(loadEl);

  let groups = [];
  try {
    groups = await syncGroups();
  } catch {
    try { groups = await getAllGroups(); } catch { groups = []; }
  }
  loadEl.remove();

  if (!groups.length) {
    listEl.appendChild(el("li", { class: "chatlist-empty" }, "Нет групп. Создайте новую."));
    return;
  }

  for (const g of groups) {
    listEl.appendChild(buildGroupListItem(g, () => renderGroupList(container)));
  }
}

export function showCreateGroupModal(onDone) {
  const nameInput = el("input", { type: "text", class: "chatlist-search", placeholder: "Название группы" });
  const status = el("div", { style: "min-height:18px;color:#ff5252;font-size:13px;margin-top:6px;" });
  const createBtn = el("button", { class: "btn-primary", style: "padding:8px 16px;font-size:14px;" }, "Создать");
  const cancelBtn = el("button", { class: "btn-secondary", style: "font-size:14px;" }, "Отмена");

  const overlay = el("div", { style: OVERLAY_STYLE },
    el("div", { style: BOX_STYLE },
      el("h3", { style: "font-size:18px;margin-bottom:12px;color:#fff;text-align:center;" }, "Новая группа"),
      nameInput, status,
      el("div", { style: "display:flex;gap:8px;justify-content:flex-end;margin-top:12px;" }, cancelBtn, createBtn),
    ),
  );
  document.body.appendChild(overlay);
  const close = () => overlay.remove();
  cancelBtn.addEventListener("click", close);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  createBtn.addEventListener("click", async () => {
    const name = nameInput.value.trim();
    if (!name) { status.textContent = "Введите название"; return; }
    createBtn.disabled = true;
    try {
      const group = await createGroup(name, []);
      close();
      showToast("Группа создана");
      if (typeof onDone === "function") await onDone(group);
      navigate(`#group/${group.id}`);
    } catch (e) {
      status.textContent = e.message || "Ошибка создания";
      createBtn.disabled = false;
    }
  });
}

// ── Group chat view ──────────────────────────────────────────────────────────

export async function renderGroup(container, groupId) {
  container.innerHTML = "";
  groupId = Number(groupId);
  const me = getCurrentUser();
  const myId = me && (me.id || me.user_id);

  // Build and mount the shell synchronously so the active pane never sits
  // blank while data loads. On a fresh boot the WS onConnect handler is
  // concurrently running history/group sync, which contends IndexedDB and can
  // stall these reads; deferring them until after the shell is attached keeps
  // the chat visible instead of leaving just the (sidebar) main screen.
  const avatarContainer = el("div", { style: "cursor:pointer;margin-right:12px;display:flex;align-items:center;" });
  avatarContainer.addEventListener("click", () => {
    const img = avatarContainer.querySelector("img");
    if (img) showFullscreenImage(img.src, headerGroup.name || "");
    else showMembersModal(groupId, myId);
  });

  const nameEl = el("span", { class: "chat-header-name" }, `Группа ${groupId}`);

  const sidebarToggle = el("button", {
    class: "icon-btn sidebar-toggle",
    title: "Toggle Sidebar"
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
    sidebarToggle,
    el("button", { class: "icon-btn chat-back" }, "←"),
    avatarContainer,
    el("div", { class: "chat-header-info" },
      nameEl,
      el("span", { class: "chat-header-nick" }, ""),
    ),
    el("button", { class: "icon-btn group-rotate-btn", title: "Смена ключа (временная)", style: "margin-left:auto;font-size:18px;margin-right:8px;" }, "🔑"),
    el("button", { class: "icon-btn group-members-btn", title: "Участники", style: "font-size:18px;" }, "👥"),
  );
  header.querySelector(".chat-back").addEventListener("click", () => navigate("#chats"));
  header.querySelector(".group-members-btn").addEventListener("click", () => showMembersModal(groupId, myId));
  header.querySelector(".group-rotate-btn").addEventListener("click", async () => {
    try {
      showToast("Инициализация смены ключа...", "info");
      const newVer = await rotateAndDistribute(groupId);
      showToast(`Ключ успешно изменен! Новая версия: ${newVer}`, "success");
    } catch (e) {
      showToast(`Ошибка смены ключа: ${e.message}`, "error");
    }
  });

  // Cache sender_user_id → display name so bubbles show names, not "#3".
  const nameById = new Map();

  const messagesEl = el("div", { class: "chat-messages", "data-group-id": groupId });
  const inputEl = el("textarea", { class: "chat-input", placeholder: "Сообщение…", rows: "1" });
  const sendBtn = el("button", { class: "chat-send-btn" }, "➤");
  const fileInput = el("input", { type: "file", style: "display:none;" });
  const attachBtn = el("button", {
    class: "icon-btn chat-attach-btn",
    title: "Прикрепить файл",
    style: "background:transparent;border:none;color:var(--text-muted);font-size:20px;cursor:pointer;padding:4px 8px;display:flex;align-items:center;justify-content:center;"
  }, "📎");

  attachBtn.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => {
    if (fileInput.files && fileInput.files[0]) {
      handleGroupFileUpload(fileInput.files[0]);
      fileInput.value = "";
    }
  });

  const inputRow = el("div", { class: "chat-input-row" }, attachBtn, fileInput, inputEl, sendBtn);
  const chatWrap = el("div", { class: "chat-wrap" }, header, messagesEl, inputRow);
  container.appendChild(chatWrap);
  const scrollDown = attachScrollDownButton(messagesEl);

  // Resolve the real title and member names after the shell is mounted.
  let headerGroup = { id: groupId, name: `Группа ${groupId}` };
  function renderHeaderAvatar() {
    avatarContainer.innerHTML = "";
    avatarContainer.appendChild(groupAvatar(headerGroup, 40));
  }
  (async () => {
    try {
      const groups = await getAllGroups();
      const group = groups.find(g => Number(g.id) === groupId);
      if (group) {
        headerGroup = group;
        nameEl.textContent = group.name;
      }
    } catch { /* keep fallback name/avatar */ }
    renderHeaderAvatar();
    try {
      const members = await getGroupMembers(groupId);
      for (const m of members) nameById.set(Number(m.user_id), memberName(m));
      // If we own/admin this group, stage the current key for any active member
      // device that is missing it (e.g. someone who joined or re-logged in after
      // the last rotation). Without this those devices 404 on every key fetch and
      // never see messages. Idempotent and cheap when nothing is missing.
      const meRow = members.find(m => Number(m.user_id) === Number(myId));
      if (meRow && isPrivileged(meRow.role)) {
        backfillCurrentKey(groupId).catch(e => console.warn('[groups] backfill failed', e.message));
      }
    } catch { /* names fall back to #id */ }
  })();

  const seen = new Set();
  function appendMessage(msg) {
    if (msg.plaintext === "[DELETED]") return;
    const key = msg.message_id;
    if (seen.has(key)) {
      const existing = messagesEl.querySelector(`[data-mid="${cssEscape(key)}"]`);
      if (existing) {
        const st = existing.querySelector(".msg-status");
        if (st) st.textContent = msg.delivered ? "✓" : "…";
      }
      return;
    }
    seen.add(key);
    const mine = Number(msg.sender_user_id) === Number(myId);
    const senderId = Number(msg.sender_user_id);
    const hue = senderId ? (senderId * 137) % 360 : 0;
    const nickColor = `hsl(${hue}, 60%, 65%)`;
    const senderNameSpan = mine ? null : el("span", { class: "msg-sender", style: `display:block;font-size:11px;opacity:0.9;margin-bottom:2px;font-weight:bold;color:${nickColor};` }, nameById.get(senderId) || msg.sender_name || `#${senderId}`);
    
    if (!mine && !nameById.has(senderId)) {
      // Resolve sender name from API if not loaded in roster yet
      apiGet(`/users/${senderId}`).then(usr => {
        const resolvedName = usr.name || usr.nickname || `#${senderId}`;
        nameById.set(senderId, resolvedName);
        if (senderNameSpan) senderNameSpan.textContent = resolvedName;
      }).catch(() => {});
    }

    const textEl = el("span", { class: "msg-text" });
    setMsgTextContent(textEl, msg.plaintext || "");

    let replyRefEl = null;
    if (msg.reply_to_msg_id) {
      replyRefEl = el("div", { class: "msg-reply-ref" },
        el("div", { class: "reply-ref-details" },
          el("span", { class: "reply-ref-sender" }, "Загрузка..."),
          el("span", { class: "reply-ref-text" }, "...")
        )
      );
      replyRefEl.addEventListener("click", () => {
        const targetBubble = messagesEl.querySelector(`[data-mid="${cssEscape(msg.reply_to_msg_id)}"]`);
        if (targetBubble) {
          targetBubble.scrollIntoView({ behavior: "smooth", block: "center" });
          targetBubble.style.transition = "background-color 0.5s";
          const originalBg = targetBubble.style.backgroundColor;
          targetBubble.style.backgroundColor = "rgba(255, 255, 255, 0.2)";
          setTimeout(() => {
            targetBubble.style.backgroundColor = originalBg;
          }, 1000);
        }
      });
      // Asynchronously resolve parent message text
      (async () => {
        try {
          const parent = await getGroupMessage(groupId, msg.reply_to_msg_id);
          if (parent) {
            const isParentMine = Number(parent.sender_user_id) === Number(myId);
            const parentSenderId = Number(parent.sender_user_id);
            const senderName = isParentMine ? "Вы" : (nameById.get(parentSenderId) || parent.sender_name || `#${parentSenderId}`);
            const info = getMessagePreviewInfo(parent.plaintext || "");
            replyRefEl.querySelector(".reply-ref-sender").textContent = senderName;
            replyRefEl.querySelector(".reply-ref-text").textContent = info.text;
            if (info.thumb) {
              const thumbImg = el("img", { class: "reply-ref-thumb", src: info.thumb });
              replyRefEl.insertBefore(thumbImg, replyRefEl.firstChild);
            }
          } else {
            replyRefEl.querySelector(".reply-ref-sender").textContent = "Сообщение";
            replyRefEl.querySelector(".reply-ref-text").textContent = "Исходное сообщение удалено или недоступно";
          }
        } catch (e) {
          replyRefEl.remove();
        }
      })();
    }

    const timeEl = el("span", { class: "msg-time" });
    wireMsgTime(timeEl, msg.created_at);

    const isStructuredPayload = typeof msg.plaintext === "string" && msg.plaintext.trim().startsWith("{");
    const isSingleLine = !isStructuredPayload && !(msg.plaintext || "").includes("\n") && (msg.plaintext || "").length <= 35;

    const metaEl = el("div", { class: "msg-meta" },
      timeEl,
      mine ? el("span", { class: "msg-status" }, msg.delivered ? "✓" : "…") : null,
    );

    const bubbleChildren = [];
    if (senderNameSpan) bubbleChildren.push(senderNameSpan);
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
        if (p.type === "file" && p.file && ((p.file.mime || "").startsWith("image/") || (p.file.mime || "").startsWith("video/")) && mine && !p.text && !p.fwd_from) {
          isMediaMsg = true;
        }
      } catch (e) {}
    }

    const bubble = el("div", { class: `msg-bubble ${mine ? "msg-out" : "msg-in"}${isMediaMsg ? " msg-media-bubble" : ""}`, "data-mid": key },
      ...bubbleChildren
    );
    wireMsgCopy(bubble, () => msg.plaintext || "", () => {
      const info = getMessagePreviewInfo(msg.plaintext || "");
      setActiveReply({
        msg_id: msg.message_id,
        text: info.text,
        thumb: info.thumb,
        sender: mine ? "Вы" : (nameById.get(senderId) || msg.sender_name || `#${senderId}`)
      });
    }, null, () => {
      const senderName = mine ? "Вы" : (nameById.get(senderId) || msg.sender_name || `#${senderId}`);
      showForwardModal(msg.plaintext || "", senderName);
    });
    const stick = scrollDown.isNearBottom();
    messagesEl.appendChild(bubble);
    if (stick) scrollDown.scrollToBottom();
    else scrollDown.update();
  }

  const loadEl = el("div", { class: "chat-loading" }, spinner());
  messagesEl.appendChild(loadEl);
  let messages = [];
  try { messages = await getGroupMessages(groupId, 100); } catch { messages = []; }
  messages = messages.filter(m => m.plaintext !== "[DELETED]");
  loadEl.remove();
  for (const m of messages) appendMessage(m);
  scrollDown.scrollToBottom();

  // Live updates for this group.
  const unsub = onGroupUpdate((evt) => {
    if (Number(evt.groupId) !== groupId) return;
    if (evt.type === "message" || evt.type === "ack") appendMessage(evt.message);
    if (evt.type === "avatar") renderHeaderAvatar();
  });
  const onLocalGroupSent = (e) => {
    if (Number(e.detail?.groupId) === groupId && e.detail?.record) {
      appendMessage(e.detail.record);
      scrollDown.scrollToBottom();
    }
  };
  window.addEventListener("local-group-msg-sent", onLocalGroupSent);

  // Detach the listener when the view is torn down (navigation replaces innerHTML).
  const observer = new MutationObserver(() => {
    if (!document.body.contains(chatWrap)) {
      unsub();
      window.removeEventListener("local-group-msg-sent", onLocalGroupSent);
      observer.disconnect();
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });

  let activeReply = null;
  const replyBarContainer = el("div", { style: "display: contents;" });
  chatWrap.insertBefore(replyBarContainer, inputRow);

  function setActiveReply(reply) {
    activeReply = reply;
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

  async function handleGroupFileUpload(file) {
    const textCaption = inputEl.value.trim();
    inputEl.value = "";
    showToast("Загрузка и шифрование файла...", "info");

    try {
      const fileBuffer = new Uint8Array(await file.arrayBuffer());

      const localBlob = new Blob([fileBuffer], { type: file.type || "application/octet-stream" });
      const localBlobUrl = URL.createObjectURL(localBlob);

      const { encryptedBytes, key } = await encryptFileChaCha20(fileBuffer);
      const encryptedBlob = new Blob([encryptedBytes], { type: "application/octet-stream" });

      // 2. Upload to VK CDN via Go server
      const cdnUrl = await uploadVKAttachment(encryptedBlob, file.name);

      // Cache original unencrypted BlobUrl locally for sender
      decryptedBlobCache.set(cdnUrl, localBlobUrl);
      saveCachedMedia(cdnUrl, localBlob, file.type).catch(() => {});

      // 3. Generate thumbnail if image
      let thumbBase64 = null;
      if (file.type.startsWith("image/")) {
        try {
          const img = new Image();
          const url = URL.createObjectURL(file);
          thumbBase64 = await new Promise((resolve, reject) => {
            img.onload = () => {
              URL.revokeObjectURL(url);
              let w = img.width, h = img.height, maxSide = 180;
              if (w > maxSide || h > maxSide) {
                if (w > h) { h = Math.round((h * maxSide) / w); w = maxSide; }
                else { w = Math.round((w * maxSide) / h); h = maxSide; }
              }
              const canvas = document.createElement("canvas");
              canvas.width = w; canvas.height = h;
              canvas.getContext("2d").drawImage(img, 0, 0, w, h);
              resolve(canvas.toDataURL("image/webp", 0.35));
            };
            img.onerror = (err) => { URL.revokeObjectURL(url); reject(err); };
            img.src = url;
          });
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

      const createdAt = Date.now();
      const messageId = await sendGroupMessage(groupId, payloadStr, currentReply ? currentReply.msg_id : null);
      appendMessage({
        message_id: messageId, sender_user_id: myId,
        plaintext: payloadStr, created_at: createdAt, delivered: 0,
        reply_to_msg_id: currentReply ? currentReply.msg_id : null
      });
      showToast("Файл отправлен!", "success");
    } catch (e) {
      console.error("handleGroupFileUpload error:", e);
      showToast(e.message || "Не удалось отправить файл", "error");
    }
  }

  async function doSend() {
    const text = inputEl.value.trim();
    if (!text) return;
    inputEl.value = "";

    const currentReply = activeReply;
    setActiveReply(null);

    const createdAt = Date.now();
    try {
      // sendGroupMessage persists the optimistic copy and returns its real
      // message_id; render under that id so the later ACK updates it in place.
      const messageId = await sendGroupMessage(groupId, text, currentReply ? currentReply.msg_id : null);
      appendMessage({
        message_id: messageId, sender_user_id: myId,
        plaintext: text, created_at: createdAt, delivered: 0,
        reply_to_msg_id: currentReply ? currentReply.msg_id : null
      });
    } catch (e) {
      showToast(e.message || "Не удалось отправить", "error");
    }
  }
  sendBtn.addEventListener("click", doSend);
  inputEl.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); doSend(); }
  });
}

function cssEscape(s) {
  return String(s).replace(/["\\]/g, "\\$&");
}

// ── Members modal ────────────────────────────────────────────────────────────

// A single member row: avatar, name, and role on the right (per the reference).
// Two tap targets: the avatar opens the member's profile; the rest of the row
// opens the action menu (promote/demote, remove) when the viewer may manage the
// member. Removed members are never rendered by the caller.
function buildMemberRow(m, { myRole, myId, onAction, onProfile }) {
  const isMe = Number(m.user_id) === Number(myId);
  const name = memberName(m) + (isMe ? " (вы)" : "");

  const roleTag = el("span", {
    style: "font-size:12px;color:#8a8a94;white-space:nowrap;",
  }, roleLabel(m.role) + (m.status === "pending" ? " · приглашён" : ""));

  // Avatar is its own tap target → profile.
  const avatarBtn = el("div", {
    style: "cursor:pointer;flex-shrink:0;border-radius:50%;",
    title: "Открыть профиль",
  }, avatar(m, 44));
  avatarBtn.addEventListener("click", (e) => { e.stopPropagation(); onProfile(m); });

  const body = el("div", { style: "flex:1;min-width:0;" },
    el("div", { style: "color:#fff;font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" }, name),
  );

  const row = el("li", {
    style: "display:flex;align-items:center;gap:12px;padding:10px 4px;border-radius:10px;",
  }, avatarBtn, body, roleTag);

  // Owner can manage roles; owner/admin can remove. Never act on the owner or
  // on yourself. Tapping the row (outside the avatar) opens the action menu.
  const canManage = myRole === "owner" && m.role !== "owner" && !isMe;
  const canRemove = isPrivileged(myRole) && m.role !== "owner" && !isMe;
  if (canManage || canRemove) {
    row.style.cursor = "pointer";
    row.addEventListener("click", () => onAction(m, { canManage, canRemove }));
  }
  return row;
}

async function showMembersModal(groupId, myId) {
  let groups = [];
  try { groups = await getAllGroups(); } catch {}
  let group = groups.find(g => Number(g.id) === groupId) || { id: groupId, name: `Группа ${groupId}` };

  const listEl = el("ul", { style: "list-style:none;padding:0;margin:4px 0;max-height:220px;overflow:auto;" });
  const closeBtn = el("button", { class: "btn-secondary", style: "font-size:14px;" }, "Закрыть");

  // Avatar display
  const avatarInner = el("div", {
    style: "width:100%;height:100%;border-radius:50%;overflow:hidden;cursor:zoom-in;display:flex;align-items:center;justify-content:center;background:#1a1a2e;"
  });
  const editAvatarBtn = el("button", {
    style: "position:absolute;top:-2px;right:-2px;width:26px;height:26px;border-radius:50%;background:#00e676;border:2px solid #1e1e24;display:none;align-items:center;justify-content:center;cursor:pointer;padding:0;font-size:13px;z-index:3;",
    title: "Изменить аватар группы",
  }, "📷");
  const avatarWrapper = el("div", {
    style: "position:relative;margin: 8px auto 12px;width:80px;height:80px;"
  }, avatarInner, editAvatarBtn);
  const fileInput = el("input", { type: "file", accept: "image/*", style: "display:none;" });

  // No forceTimestamp here: groupAvatar() falls back to the shared
  // groupAvatarUpdateTimestamps cache-buster, which is kept fresh by both a
  // local upload and incoming GROUP_AVATAR_UPDATE websocket events.
  function updateAvatarDisplay() {
    avatarInner.innerHTML = "";
    avatarInner.appendChild(groupAvatar(group, 80));
  }
  updateAvatarDisplay();

  // Rename components
  const titleEl = el("span", { style: "font-size:18px;font-weight:bold;color:#fff;" }, group.name);
  const renameIcon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  renameIcon.setAttribute("viewBox", "0 0 24 24");
  renameIcon.setAttribute("width", "16");
  renameIcon.setAttribute("height", "16");
  renameIcon.setAttribute("fill", "none");
  renameIcon.setAttribute("stroke", "#00e676");
  renameIcon.setAttribute("stroke-width", "2");
  renameIcon.setAttribute("stroke-linecap", "round");
  renameIcon.setAttribute("stroke-linejoin", "round");
  renameIcon.innerHTML = `<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 1 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>`;

  const renameBtn = el("button", {
    style: "background:none;border:none;cursor:pointer;padding:4px;display:none;align-items:center;justify-content:center;"
  }, renameIcon);
  const titleContainer = el("div", {
    style: "display:flex;align-items:center;justify-content:center;gap:8px;margin-bottom:16px;"
  }, titleEl, renameBtn, fileInput);

  // "+ Добавить участника" row lives below a divider, styled after the sketch.
  const addRow = el("button", {
    style: "display:none;align-items:center;gap:12px;width:100%;background:none;border:none;padding:12px 4px;cursor:pointer;color:#00e676;font-size:16px;text-align:left;",
  },
    el("span", { style: "width:44px;height:44px;border-radius:50%;background:rgba(0,230,118,0.12);display:flex;align-items:center;justify-content:center;font-size:24px;flex-shrink:0;" }, "＋"),
    el("span", {}, "Добавить участника"),
  );

  const overlay = el("div", { style: OVERLAY_STYLE },
    el("div", { style: BOX_STYLE },
      avatarWrapper,
      titleContainer,
      el("h4", { style: "font-size:14px;color:#8a8a94;margin:8px 0;font-weight:normal;" }, "Участники"),
      listEl,
      el("hr", { style: "border:none;border-top:1px solid rgba(255,255,255,0.1);width:100%;margin:4px 0;" }),
      addRow,
      el("div", { style: "display:flex;justify-content:flex-end;margin-top:12px;" }, closeBtn),
    ),
  );
  document.body.appendChild(overlay);

  // Live-refresh the avatar if it changes while this modal is open — either
  // because we just uploaded a new one, or another privileged member did.
  const unsubAvatar = onGroupUpdate((evt) => {
    if (evt.type === "avatar" && Number(evt.groupId) === groupId) {
      updateAvatarDisplay();
    }
  });

  avatarInner.addEventListener("click", () => {
    const img = avatarInner.querySelector("img");
    if (img) showFullscreenImage(img.src, group.name || "");
  });

  editAvatarBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    fileInput.click();
  });

  fileInput.addEventListener("change", async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      avatarInner.style.opacity = "0.5";
      await uploadGroupAvatar(groupId, file);
      groupAvatarUpdateTimestamps.set(String(groupId), Date.now());
      updateAvatarDisplay();
      triggerChatListUpdate();
      showToast("Аватар группы обновлен!");
    } catch (err) {
      showToast(err.message || "Не удалось загрузить аватар", "error");
    } finally {
      avatarInner.style.opacity = "1";
    }
  });

  renameBtn.addEventListener("click", async () => {
    const newName = await showPromptModal("Переименовать группу", "Название группы", group.name);
    if (newName && newName !== group.name) {
      try {
        await renameGroup(groupId, newName);
        group.name = newName;
        titleEl.textContent = newName;
        const chatNameEl = document.querySelector(".chat-header-name");
        if (chatNameEl && (chatNameEl.textContent === group.name || chatNameEl.textContent === `Группа ${groupId}`)) {
          chatNameEl.textContent = newName;
        }
        showToast("Название группы изменено!");
      } catch (err) {
        showToast(err.message || "Не удалось переименовать группу", "error");
      }
    }
  });

  const close = () => { unsubAvatar(); overlay.remove(); };
  closeBtn.addEventListener("click", close);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  let myRole = "member";

  function onAction(m, { canManage, canRemove }) {
    const actions = [];
    if (canManage) {
      actions.push(m.role === "admin"
        ? { label: "Снять роль админа", run: () => changeMemberRole(groupId, m.user_id, "member") }
        : { label: "Сделать админом", run: () => changeMemberRole(groupId, m.user_id, "admin") });
    }
    if (canRemove) {
      actions.push({ label: "Удалить из группы", danger: true, run: async () => {
        const ok = await showConfirmModal("Удалить участника?", `${memberName(m)} потеряет доступ к новым сообщениям.`);
        if (!ok) return "skip";
        await removeMember(groupId, m.user_id);
      } });
    }
    showActionSheet(memberName(m), actions, renderMembers);
  }

  function displayMembersList(membersList) {
    listEl.innerHTML = "";
    // Never show removed members.
    const activeMembers = (membersList || []).filter(m => m.status !== "removed");
    const meRow = activeMembers.find(m => Number(m.user_id) === Number(myId));
    myRole = meRow ? meRow.role : "member";

    // Owner first, then admins, then members; stable within each group.
    const order = { owner: 0, admin: 1, member: 2 };
    activeMembers.sort((a, b) => (order[a.role] ?? 3) - (order[b.role] ?? 3));

    for (const m of activeMembers) {
      listEl.appendChild(buildMemberRow(m, { myRole, myId, onAction, onProfile: showMemberProfileModal }));
    }
    // Only owner/admin may add members: hide the add row otherwise.
    addRow.style.display = isPrivileged(myRole) ? "flex" : "none";
    renameBtn.style.display = isPrivileged(myRole) ? "inline-block" : "none";
    editAvatarBtn.style.display = isPrivileged(myRole) ? "flex" : "none";
  }

  async function renderMembers() {
    // 1. Show cached members immediately
    let cachedMembers = [];
    try {
      cachedMembers = await getGroupMembers(groupId);
    } catch (e) {
      console.warn("Failed to read cached members", e);
    }
    displayMembersList(cachedMembers);

    // 2. Fetch fresh members from network and update
    try {
      const freshMembers = await refreshMembers(groupId);
      displayMembersList(freshMembers);
    } catch (e) {
      console.warn("Failed to refresh members from network", e);
    }
  }
  renderMembers();

  addRow.addEventListener("click", async () => {
    let members = [];
    try { members = await getGroupMembers(groupId); } catch { members = []; }
    showAddMemberModal(groupId, members, renderMembers);
  });
}

// ── Member profile modal ─────────────────────────────────────────────────────

// showMemberProfileModal displays a read-only profile card for a group member:
// large avatar, name, @nick, id, and role. Opened by tapping a member's avatar.
function showMemberProfileModal(m) {
  const nick = m.username || m.nickname;
  const av = avatar(m, 96);
  av.style.margin = "4px auto 12px";

  const presence = formatPresence(m);
  const presenceEl = presence
    ? el("div", { style: `color:${m.online ? "#00e676" : "#8a8a94"};font-size:13px;text-align:center;margin-top:4px;` }, presence)
    : el("div", { style: "display:none;" });

  const rows = [
    el("div", { style: "color:#fff;font-size:20px;font-weight:600;text-align:center;" }, memberName(m)),
    nick ? el("div", { style: "color:#8a8a94;font-size:15px;text-align:center;margin-top:2px;" }, `@${nick}`) : null,
    presenceEl,
    el("div", { style: "display:flex;justify-content:space-between;padding:10px 4px;border-top:1px solid rgba(255,255,255,0.08);margin-top:16px;" },
      el("span", { style: "color:#8a8a94;font-size:14px;" }, "Роль"),
      el("span", { style: "color:#fff;font-size:14px;" }, roleLabel(m.role)),
    ),
    el("div", { style: "display:flex;justify-content:space-between;padding:10px 4px;border-top:1px solid rgba(255,255,255,0.08);" },
      el("span", { style: "color:#8a8a94;font-size:14px;" }, "ID"),
      el("span", { style: "color:#fff;font-size:14px;" }, String(m.user_id)),
    ),
  ];

  const closeBtn = el("button", { class: "btn-secondary", style: "width:100%;margin-top:16px;font-size:14px;" }, "Закрыть");
  const box = el("div", { style: BOX_STYLE, onclick: (e) => e.stopPropagation() }, av, ...rows, closeBtn);
  const overlay = el("div", { style: OVERLAY_STYLE }, box);

  // Live-refresh presence while this profile card is open.
  const unsubPresence = onPresenceUpdate(m.user_id, (p) => {
    const text = formatPresence(p);
    if (!text) return;
    presenceEl.textContent = text;
    presenceEl.style.display = "block";
    presenceEl.style.color = p.online ? "#00e676" : "#8a8a94";
  });

  const close = () => { unsubPresence(); overlay.remove(); };
  closeBtn.addEventListener("click", close);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
  document.body.appendChild(overlay);
}

// showActionSheet renders a small bottom-anchored menu of actions. Each action's
// run() may return "skip" to leave the sheet's onDone uncalled (e.g. cancelled
// confirm). Errors surface as a toast.
function showActionSheet(title, actions, onDone) {
  if (!actions.length) return;
  const sheet = el("div", { style: "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:8px;width:100%;max-width:360px;box-shadow:0 8px 32px rgba(0,0,0,0.5);" },
    el("div", { style: "padding:10px 12px;color:#8a8a94;font-size:13px;text-align:center;" }, title),
  );
  const overlay = el("div", { style: OVERLAY_STYLE }, sheet);
  const close = () => overlay.remove();
  for (const a of actions) {
    const btn = el("button", {
      style: `display:block;width:100%;background:none;border:none;padding:14px 12px;font-size:15px;text-align:left;cursor:pointer;border-radius:10px;color:${a.danger ? "#ff5252" : "#fff"};`,
    }, a.label);
    btn.addEventListener("click", async () => {
      btn.disabled = true;
      try {
        const r = await a.run();
        close();
        if (r !== "skip" && typeof onDone === "function") await onDone();
      } catch (e) {
        close();
        showToast(e.message || "Ошибка", "error");
      }
    });
    sheet.appendChild(btn);
  }
  const cancel = el("button", { class: "btn-secondary", style: "width:100%;margin-top:6px;font-size:15px;" }, "Отмена");
  cancel.addEventListener("click", close);
  sheet.appendChild(cancel);
  document.body.appendChild(overlay);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
}

// ── Add member dialog ────────────────────────────────────────────────────────

// Separate dialog for adding members. Lists only contacts the user already has
// a conversation with, excludes people already in the group, and shows all such
// contacts by default with an optional filter box. A checkbox controls whether
// the pre-join chat history is shared with the invitee.
async function showAddMemberModal(groupId, currentMembers, onDone) {
  const existing = new Set((currentMembers || [])
    .filter(m => m.status !== "removed")
    .map(m => Number(m.user_id)));

  let contacts = [];
  try { contacts = await getAllContacts(); } catch { contacts = []; }
  // Only people we actually have correspondence with, and not already members.
  contacts = contacts
    .filter(c => c.last_ts || c.last_message)
    .filter(c => !existing.has(Number(c.user_id)))
    .sort((a, b) => (b.last_ts || 0) - (a.last_ts || 0));

  const filterInput = el("input", { type: "text", class: "chatlist-search", placeholder: "Поиск по контактам…" });
  const listEl = el("ul", { style: "list-style:none;padding:0;margin:8px 0;max-height:280px;overflow:auto;" });

  const shareChk = el("input", { type: "checkbox", style: "width:18px;height:18px;accent-color:#00e676;cursor:pointer;" });
  const shareLabel = el("label", { style: "display:flex;align-items:center;gap:10px;padding:8px 4px;cursor:pointer;color:#ccc;font-size:14px;" },
    shareChk,
    el("span", {}, "Передать историю чата до вступления"),
  );

  const closeBtn = el("button", { class: "btn-secondary", style: "font-size:14px;" }, "Закрыть");

  const overlay = el("div", { style: OVERLAY_STYLE },
    el("div", { style: BOX_STYLE },
      el("h3", { style: "font-size:18px;margin-bottom:12px;color:#fff;text-align:center;" }, "Добавить участника"),
      filterInput,
      listEl,
      shareLabel,
      el("div", { style: "display:flex;justify-content:flex-end;margin-top:12px;" }, closeBtn),
    ),
  );
  document.body.appendChild(overlay);
  const close = () => overlay.remove();

  let changed = false;

  function render(filter = "") {
    listEl.innerHTML = "";
    const q = filter.trim().toLowerCase();
    const shown = contacts.filter(c => {
      if (!q) return true;
      return (memberName(c).toLowerCase().includes(q)) ||
        (c.username || c.nickname || "").toLowerCase().includes(q);
    });
    if (!shown.length) {
      listEl.appendChild(el("li", { style: "padding:16px 4px;color:#8a8a94;text-align:center;font-size:14px;" },
        contacts.length ? "Никого не найдено" : "Нет контактов для добавления"));
      return;
    }
    for (const c of shown) {
      const nick = c.username || c.nickname;
      const addBtn = el("button", { class: "btn-primary", style: "padding:6px 14px;font-size:13px;" }, "Добавить");
      const row = el("li", { style: "display:flex;align-items:center;gap:12px;padding:8px 4px;" },
        avatar(c, 44),
        el("div", { style: "flex:1;min-width:0;" },
          el("div", { style: "color:#fff;font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" }, memberName(c)),
          nick ? el("div", { style: "color:#8a8a94;font-size:13px;" }, `@${nick}`) : null,
        ),
        addBtn,
      );
      addBtn.addEventListener("click", async () => {
        addBtn.disabled = true;
        try {
          await inviteMember(groupId, Number(c.user_id), { shareHistory: shareChk.checked });
          changed = true;
          showToast("Приглашение отправлено");
          // Drop from the local list so it can't be added twice.
          contacts = contacts.filter(x => Number(x.user_id) !== Number(c.user_id));
          render(filterInput.value);
        } catch (e) {
          addBtn.disabled = false;
          showToast(e.message || "Ошибка", "error");
        }
      });
      listEl.appendChild(row);
    }
  }
  render();

  let t = null;
  filterInput.addEventListener("input", () => {
    clearTimeout(t);
    t = setTimeout(() => render(filterInput.value), 150);
  });

  // When the dialog closes, refresh the members list behind us if anything was
  // added. Both the button and backdrop dismiss route through here.
  const finish = () => { close(); if (changed && onDone) onDone(); };
  closeBtn.addEventListener("click", finish);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) finish(); });
}

export { acceptInvitation };
