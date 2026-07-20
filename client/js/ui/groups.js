import {
  createGroup, syncGroups, refreshMembers, acceptInvitation, declineInvitation,
  inviteMember, removeMember, changeMemberRole, sendGroupMessage,
  getAllGroups, getGroupMessages, onGroupUpdate,
} from "../groups.js";
import { getGroupMembers, getAllContacts } from "../storage.js";
import { navigate, getCurrentUser } from "../app.js";
import { el, avatar, formatTime, showToast, spinner, showConfirmModal } from "./components.js";

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

// A single member row: avatar, name, and role on the right (per the reference).
// Long-press (or right-click) on a manageable member opens an action menu with
// promote/demote and remove. Removed members are never rendered by the caller.
function buildMemberRow(m, { myRole, myId, onAction }) {
  const isMe = Number(m.user_id) === Number(myId);
  const name = memberName(m) + (isMe ? " (вы)" : "");

  const roleTag = el("span", {
    style: "font-size:12px;color:#8a8a94;white-space:nowrap;",
  }, roleLabel(m.role) + (m.status === "pending" ? " · приглашён" : ""));

  const row = el("li", {
    style: "display:flex;align-items:center;gap:12px;padding:10px 4px;border-radius:10px;user-select:none;-webkit-user-select:none;",
  },
    avatar(m, 44),
    el("div", { style: "flex:1;min-width:0;" },
      el("div", { style: "color:#fff;font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" }, name),
    ),
    roleTag,
  );

  // Owner can manage roles; owner/admin can remove. Never act on the owner or
  // on yourself.
  const canManage = myRole === "owner" && m.role !== "owner" && !isMe;
  const canRemove = isPrivileged(myRole) && m.role !== "owner" && !isMe;
  if (!canManage && !canRemove) return row;

  row.style.cursor = "pointer";
  const openMenu = (e) => {
    e.preventDefault();
    onAction(m, { canManage, canRemove });
  };
  // Long-press for touch, right-click for desktop.
  let pressTimer = null;
  row.addEventListener("touchstart", () => { pressTimer = setTimeout(() => openMenu(new Event("longpress")), 500); }, { passive: true });
  row.addEventListener("touchend", () => clearTimeout(pressTimer));
  row.addEventListener("touchmove", () => clearTimeout(pressTimer));
  row.addEventListener("contextmenu", openMenu);
  return row;
}

async function showMembersModal(groupId, myId) {
  const listEl = el("ul", { style: "list-style:none;padding:0;margin:4px 0;max-height:340px;overflow:auto;" });
  const closeBtn = el("button", { class: "btn-secondary", style: "font-size:14px;" }, "Закрыть");

  // "+ Добавить участника" row lives below a divider, styled after the sketch.
  const addRow = el("button", {
    style: "display:flex;align-items:center;gap:12px;width:100%;background:none;border:none;padding:12px 4px;cursor:pointer;color:#00e676;font-size:16px;text-align:left;",
  },
    el("span", { style: "width:44px;height:44px;border-radius:50%;background:rgba(0,230,118,0.12);display:flex;align-items:center;justify-content:center;font-size:24px;flex-shrink:0;" }, "＋"),
    el("span", {}, "Добавить участника"),
  );

  const overlay = el("div", { style: OVERLAY_STYLE },
    el("div", { style: BOX_STYLE },
      el("h3", { style: "font-size:18px;margin-bottom:12px;color:#fff;text-align:center;" }, "Участники"),
      listEl,
      el("hr", { style: "border:none;border-top:1px solid rgba(255,255,255,0.1);width:100%;margin:4px 0;" }),
      addRow,
      el("div", { style: "display:flex;justify-content:flex-end;margin-top:12px;" }, closeBtn),
    ),
  );
  document.body.appendChild(overlay);
  const close = () => overlay.remove();
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

  async function renderMembers() {
    listEl.innerHTML = "";
    let members = [];
    try { members = await refreshMembers(groupId); } catch { members = await getGroupMembers(groupId); }
    // Never show removed members.
    members = members.filter(m => m.status !== "removed");
    const meRow = members.find(m => Number(m.user_id) === Number(myId));
    myRole = meRow ? meRow.role : "member";

    // Owner first, then admins, then members; stable within each group.
    const order = { owner: 0, admin: 1, member: 2 };
    members.sort((a, b) => (order[a.role] ?? 3) - (order[b.role] ?? 3));

    for (const m of members) {
      listEl.appendChild(buildMemberRow(m, { myRole, myId, onAction }));
    }
    // Only owner/admin may add members: hide the add row otherwise.
    addRow.style.display = isPrivileged(myRole) ? "flex" : "none";
  }
  await renderMembers();

  addRow.addEventListener("click", async () => {
    let members = [];
    try { members = await getGroupMembers(groupId); } catch { members = []; }
    showAddMemberModal(groupId, members, renderMembers);
  });
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
