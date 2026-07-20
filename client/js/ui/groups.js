import {
  createGroup, syncGroups, refreshMembers, acceptInvitation, declineInvitation,
  inviteMember, removeMember, sendGroupMessage,
  getAllGroups, getGroupMessages, onGroupUpdate,
} from "../groups.js";
import { getGroupMembers } from "../storage.js";
import { searchUsers } from "../api.js";
import { navigate, getCurrentUser } from "../app.js";
import { el, formatTime, showToast, spinner, showConfirmModal } from "./components.js";

// Role/status labels in Russian for the members UI.
const ROLE_LABEL = { owner: "владелец", admin: "админ", member: "участник" };
const STATUS_LABEL = { active: "активен", pending: "приглашён", removed: "удалён" };
const roleLabel = (r) => ROLE_LABEL[r] || r;
const statusLabel = (s) => STATUS_LABEL[s] || s;
const memberName = (m) => m.name || m.nickname || `#${m.user_id}`;

const OVERLAY_STYLE = "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:9999;padding:16px;";
const BOX_STYLE = "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:24px;width:100%;max-width:440px;box-shadow:0 8px 32px rgba(0,0,0,0.5);display:flex;flex-direction:column;";

// ── Group list ───────────────────────────────────────────────────────────────

// Build a single <li> for a group. Pending invites render accept/decline
// actions instead of opening the chat; active groups navigate on click.
// `onChange` is invoked after accept/decline so the surrounding list can
// refresh itself (works for both the groups-only and unified lists).
export function buildGroupListItem(g, onChange) {
  const isPending = g.status === "pending";
  const preview = isPending
    ? "Приглашение в группу"
    : (g.last_message || (g.role ? `Роль: ${roleLabel(g.role)}` : ""));

  const info = el("div", { class: "chatlist-item-info" },
    el("span", { class: "chatlist-item-name" }, g.name),
    el("span", { class: "chatlist-item-preview" }, preview),
  );

  const item = el("li", { class: "chatlist-item" },
    el("div", {
      class: "chatlist-item-avatar",
      style: "width:48px;height:48px;border-radius:50%;background:#1a1a2e;display:flex;align-items:center;justify-content:center;font-size:22px;color:#00e676;",
    }, "👥"),
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
    if (evt.type !== "members") return;
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

  let group = null;
  try {
    const groups = await getAllGroups();
    group = groups.find(g => Number(g.id) === groupId);
  } catch { /* ignore */ }
  const title = group ? group.name : `Группа ${groupId}`;

  const header = el("div", { class: "chat-header" },
    el("button", { class: "icon-btn chat-back" }, "←"),
    el("div", { class: "chat-header-info" },
      el("span", { class: "chat-header-name" }, title),
      el("span", { class: "chat-header-nick" }, ""),
    ),
    el("button", { class: "icon-btn group-members-btn", title: "Участники", style: "margin-left:auto;font-size:18px;" }, "👥"),
  );
  header.querySelector(".chat-back").addEventListener("click", () => navigate("#chats"));
  header.querySelector(".group-members-btn").addEventListener("click", () => showMembersModal(groupId, myId));

  // Cache sender_user_id → display name so bubbles show names, not "#3".
  const nameById = new Map();
  try {
    const members = await getGroupMembers(groupId);
    for (const m of members) nameById.set(Number(m.user_id), memberName(m));
  } catch { /* names fall back to #id */ }

  const messagesEl = el("div", { class: "chat-messages", "data-group-id": groupId });
  const inputEl = el("textarea", { class: "chat-input", placeholder: "Сообщение…", rows: "1" });
  const sendBtn = el("button", { class: "chat-send-btn" }, "➤");
  const inputRow = el("div", { class: "chat-input-row" }, inputEl, sendBtn);
  const chatWrap = el("div", { class: "chat-wrap" }, header, messagesEl, inputRow);
  container.appendChild(chatWrap);

  const seen = new Set();
  function appendMessage(msg) {
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
    const bubble = el("div", { class: `msg-bubble ${mine ? "msg-out" : "msg-in"}`, "data-mid": key },
      mine ? null : el("span", { class: "msg-sender", style: "display:block;font-size:11px;opacity:0.7;margin-bottom:2px;" }, nameById.get(Number(msg.sender_user_id)) || msg.sender_name || `#${msg.sender_user_id}`),
      el("span", { class: "msg-text" }, msg.plaintext || ""),
      el("div", { class: "msg-meta" },
        el("span", { class: "msg-time" }, formatTime(msg.created_at)),
        mine ? el("span", { class: "msg-status" }, msg.delivered ? "✓" : "…") : null,
      ),
    );
    messagesEl.appendChild(bubble);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  const loadEl = el("div", { class: "chat-loading" }, spinner());
  messagesEl.appendChild(loadEl);
  let messages = [];
  try { messages = await getGroupMessages(groupId, 100); } catch { messages = []; }
  loadEl.remove();
  for (const m of messages) appendMessage(m);

  // Live updates for this group.
  const unsub = onGroupUpdate((evt) => {
    if (Number(evt.groupId) !== groupId) return;
    if (evt.type === "message" || evt.type === "ack") appendMessage(evt.message);
  });
  // Detach the listener when the view is torn down (navigation replaces innerHTML).
  const observer = new MutationObserver(() => {
    if (!document.body.contains(chatWrap)) { unsub(); observer.disconnect(); }
  });
  observer.observe(document.body, { childList: true, subtree: true });

  async function doSend() {
    const text = inputEl.value.trim();
    if (!text) return;
    inputEl.value = "";
    const createdAt = Date.now();
    try {
      // sendGroupMessage persists the optimistic copy and returns its real
      // message_id; render under that id so the later ACK updates it in place.
      const messageId = await sendGroupMessage(groupId, text);
      appendMessage({
        message_id: messageId, sender_user_id: myId,
        plaintext: text, created_at: createdAt, delivered: 0,
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

async function showMembersModal(groupId, myId) {
  const listEl = el("ul", { style: "list-style:none;padding:0;margin:8px 0;max-height:200px;overflow:auto;" });
  const searchInput = el("input", { type: "text", class: "chatlist-search", placeholder: "Добавить участника по нику…" });
  const results = el("ul", { style: "list-style:none;padding:0;margin:4px 0;max-height:120px;overflow:auto;" });
  const closeBtn = el("button", { class: "btn-secondary", style: "font-size:14px;" }, "Закрыть");

  const overlay = el("div", { style: OVERLAY_STYLE },
    el("div", { style: BOX_STYLE },
      el("h3", { style: "font-size:18px;margin-bottom:12px;color:#fff;text-align:center;" }, "Участники"),
      listEl,
      el("hr", { style: "border-color:rgba(255,255,255,0.1);width:100%;" }),
      searchInput, results,
      el("div", { style: "display:flex;justify-content:flex-end;margin-top:12px;" }, closeBtn),
    ),
  );
  document.body.appendChild(overlay);
  const close = () => overlay.remove();
  closeBtn.addEventListener("click", close);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  let myRole = "member";

  async function renderMembers() {
    listEl.innerHTML = "";
    let members = [];
    try { members = await refreshMembers(groupId); } catch { members = await getGroupMembers(groupId); }
    const meRow = members.find(m => Number(m.user_id) === Number(myId));
    myRole = meRow ? meRow.role : "member";
    for (const m of members) {
      const canRemove = (myRole === "owner" || myRole === "admin") && m.role !== "owner" && Number(m.user_id) !== Number(myId);
      const row = el("li", { style: "display:flex;align-items:center;gap:8px;padding:4px 0;" },
        el("span", { style: "flex:1;" }, `${memberName(m)} · ${roleLabel(m.role)} · ${statusLabel(m.status)}`),
        canRemove ? el("button", { class: "icon-btn", title: "Удалить", style: "color:#ff5252;" }, "✕") : null,
      );
      const rm = row.querySelector("button");
      if (rm) rm.addEventListener("click", async () => {
        const res = await showConfirmModal("Удалить участника?", `Пользователь #${m.user_id} потеряет доступ к новым сообщениям.`);
        if (!res) return;
        try { await removeMember(groupId, m.user_id); showToast("Участник удалён"); await renderMembers(); }
        catch (e) { showToast(e.message || "Ошибка", "error"); }
      });
      listEl.appendChild(row);
    }
  }
  await renderMembers();

  let searchTimer = null;
  searchInput.addEventListener("input", () => {
    clearTimeout(searchTimer);
    const q = searchInput.value.trim();
    results.innerHTML = "";
    if (!q || (myRole !== "owner" && myRole !== "admin")) return;
    searchTimer = setTimeout(async () => {
      let found = [];
      try { const res = await searchUsers(q); found = res.users || res || []; } catch { found = []; }
      results.innerHTML = "";
      for (const u of found) {
        const row = el("li", { style: "display:flex;align-items:center;gap:8px;padding:4px 0;" },
          el("span", { style: "flex:1;" }, `${u.name || u.nickname} · @${u.nickname}`),
          el("button", { class: "icon-btn", style: "color:#00e676;" }, "＋"),
        );
        row.querySelector("button").addEventListener("click", async () => {
          try { await inviteMember(groupId, Number(u.id || u.user_id)); showToast("Приглашение отправлено"); await renderMembers(); }
          catch (e) { showToast(e.message || "Ошибка", "error"); }
        });
        results.appendChild(row);
      }
    }, 300);
  });
}

export { acceptInvitation };
