import { apiGet, apiDelete } from "../api.js";
import {
  encodeKey, decodeKey, computeSafetyNumber
} from "../crypto.js";
import {
  saveMessage, getMessages,
  updateMessageDelivered, getContact, saveContact, getAllContacts,
  getIdentity, deleteChatData, clearUserSessions,
  signalStore, SignalProtocolAddress, SessionBuilder, SessionCipher,
  getIdentityKey, BoundSignalStore, getAnySession, saveSession
} from "../storage.js";
import { navigate, getWS, getCurrentUser, setActiveChatCallback, setChatListUpdateCallback, triggerChatListUpdate, pendingAcks, triggerBackgroundBackup } from "../app.js";
import { avatar, formatTime, formatDate, el, showToast, spinner, showSafetyNumberModal, showConfirmModal, showSafetyExplanationModal, showDeleteChatConfirmModal } from "./components.js";



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

async function ensureSessionForDevice(toUserId, activeDevice) {
  const toRaw = v => v instanceof Uint8Array ? v : decodeKey(v);
  // Return a tight ArrayBuffer. WS msgpack decode yields Uint8Array views into
  // the whole frame, so `.buffer` would expose the entire frame, not the key.
  const toBuf = v => {
    const u = toRaw(v);
    return u.buffer.slice(u.byteOffset, u.byteOffset + u.byteLength);
  };
  const theirIKRaw = toRaw(activeDevice.ik_pub);
  const address = new SignalProtocolAddress(String(toUserId), activeDevice.device_id);
  const boundStore = new BoundSignalStore(signalStore, address);
  const cipher = new SessionCipher(boundStore, address);
  
  const fn = async () => {
    const hasSession = await cipher.hasOpenSession();
    if (hasSession) {
      return;
    }

    const existingKey = await getIdentityKey(address.toString());
    if (existingKey) {
      const existingKeyU8 = new Uint8Array(existingKey);
      let isSame = (existingKeyU8.length === theirIKRaw.length);
      if (isSame) {
        for (let i = 0; i < existingKeyU8.length; i++) {
          if (existingKeyU8[i] !== theirIKRaw[i]) {
            isSame = false;
            break;
          }
        }
      }
      if (!isSame) {
        const confirmed = await showConfirmModal(
          "Изменение кода безопасности",
          `Код безопасности для устройства ${activeDevice.device_id} пользователя изменился. Вы доверяете новому коду?`
        );
        if (!confirmed) {
          throw new Error("Отправка отменена: код безопасности устройства не подтвержден.");
        }

        const systemMsg = {
          msg_id: crypto.randomUUID(),
          chat_id: String(toUserId),
          sender_id: 0,
          plaintext: "⚠️ Код безопасности изменился!",
          created_at: Date.now(),
          delivered: 1
        };
        await saveMessage(systemMsg);

        const messagesEl = document.querySelector(`.chat-messages[data-user-id="${toUserId}"]`);
        if (messagesEl) {
          const bubble = el("div", {
            class: "msg-bubble msg-system",
            style: "cursor: pointer; text-decoration: underline;"
          },
            el("span", { class: "msg-text" }, "⚠️ Код безопасности изменился!")
          );
          bubble.addEventListener("click", () => {
            showSafetyExplanationModal();
          });
          messagesEl.appendChild(bubble);
          messagesEl.scrollTop = messagesEl.scrollHeight;
        }

        await clearUserSessions(toUserId);
        triggerChatListUpdate();
      }
    }

    const builder = new SessionBuilder(boundStore, address);

    const preKeyBundle = {
      identityKey: toBuf(activeDevice.ik_pub),
      registrationId: Number(activeDevice.registration_id || 1),
      signedPreKey: {
        keyId: 1,
        publicKey: toBuf(activeDevice.spk_pub),
        signature: toBuf(activeDevice.spk_sig)
      }
    };

    if (activeDevice.opk_pub) {
      preKeyBundle.preKey = {
        keyId: Number(activeDevice.opk_id || 0),
        publicKey: toBuf(activeDevice.opk_pub)
      };
    }

    await builder.processPreKey(preKeyBundle);
  };
  await fn();
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

  const safetyBtn = el("button", {
    class: "icon-btn chat-safety",
    title: "Код безопасности",
    style: "margin-left: auto; font-size: 18px; cursor: pointer; background: transparent; border: none; opacity: 0.7; transition: opacity 0.2s;"
  }, "🛡️");
  
  safetyBtn.addEventListener("mouseenter", () => { safetyBtn.style.opacity = "1"; });
  safetyBtn.addEventListener("mouseleave", () => { safetyBtn.style.opacity = "0.7"; });

  safetyBtn.addEventListener("click", async () => {
    try {
      let session = await getAnySession(userId);
      const identity = await getIdentity();

      const myIKRaw = identity && (identity.ik_pub_raw
        ? new Uint8Array(identity.ik_pub_raw)
        : (identity.identityKeyPair && identity.identityKeyPair.pubKey
            ? new Uint8Array(identity.identityKeyPair.pubKey)
            : null));

      if (!myIKRaw) {
        showToast("Код безопасности недоступен: локальный ключ не найден.", "error");
        return;
      }

      if (!session) {
        showToast("Код безопасности недоступен: сессия не установлена. Отправьте сообщение сначала.", "warning");
        return;
      }

      // Dynamic migration: if their_ik_pub is missing in old session, fetch it on-demand
      if (!session.their_ik_pub) {
        const ws = getWS();
        if (!ws) throw new Error("WebSocket не подключён");
        const keyBundle = await ws.request(0x10, { user_id: Number(userId) }, 0x11);
        if (keyBundle && keyBundle.devices && keyBundle.devices.length) {
          const devices = keyBundle.devices.map(d => ({
            ...d,
            device_id: Number(d.device_id)
          })).sort((a, b) => a.device_id - b.device_id);
          const activeDevice = devices[devices.length - 1];
          const toRaw = v => v instanceof Uint8Array ? v : decodeKey(v);
          let theirIKRaw = toRaw(activeDevice.ik_pub);
          if (theirIKRaw.length === 64) {
            theirIKRaw = theirIKRaw.slice(0, 32);
          }
          session.their_ik_pub = theirIKRaw;
          await saveSession(session);
        } else {
          showToast("Код безопасности недоступен: не удалось загрузить ключи собеседника.", "error");
          return;
        }
      }

      const safetyNumber = await computeSafetyNumber(
        myIKRaw,
        new Uint8Array(session.their_ik_pub)
      );
      await showSafetyNumberModal(`Код безопасности для ${contact.name || contact.nickname}`, safetyNumber);
    } catch (err) {
      console.error("Safety number error:", err);
      showToast("Ошибка при расчете кода безопасности", "error");
    }
  });

  const deleteBtn = el("button", {
    class: "icon-btn chat-delete",
    title: "Удалить чат",
    style: "margin-left: 8px; font-size: 18px; cursor: pointer; background: transparent; border: none; opacity: 0.7; transition: opacity 0.2s;"
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
    safetyBtn,
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

    const statusEl = isMine
      ? el("span", { class: "msg-status" }, msg.delivered ? "✓✓" : "✓")
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

      // 1. Fetch key bundles for recipient with no_otk: true
      const keyBundle = await ws.request(0x10, { user_id: Number(userId), no_otk: true }, 0x11);
      if (!keyBundle || !keyBundle.devices || !keyBundle.devices.length) {
        throw new Error("У собеседника нет активных устройств");
      }

      // 2. Fetch key bundles for our own other devices (for multi-device sync) with no_otk: true
      let ourBundle = null;
      try {
        ourBundle = await ws.request(0x10, { user_id: Number(myId), no_otk: true }, 0x11);
      } catch (e) {
        console.warn("Failed to fetch our own devices:", e);
      }

      // 3. Collect target devices
      const targetDevices = [];
      for (const dev of keyBundle.devices) {
        targetDevices.push({ userId: Number(userId), dev });
      }

      if (ourBundle && ourBundle.devices) {
        const myDeviceId = Number(localStorage.getItem("device_id"));
        for (const dev of ourBundle.devices) {
          if (Number(dev.device_id) !== myDeviceId) {
            targetDevices.push({ userId: Number(myId), dev });
          }
        }
      }

      // 4. Encrypt separately for each target device using libsignal
      const devicesCiphertexts = [];
      for (const target of targetDevices) {
        const address = new SignalProtocolAddress(String(target.userId), target.dev.device_id);
        const lockName = `penik-crypto-lock-${target.userId}.${target.dev.device_id}`;

        const encryptForDevice = async () => {
          const boundStore = new BoundSignalStore(signalStore, address);
          const cipher = new SessionCipher(boundStore, address);
          
          const hasSession = await cipher.hasOpenSession();
          if (!hasSession) {
            try {
              const singleBundle = await ws.request(0x10, {
                user_id: target.userId,
                device_id: target.dev.device_id,
                no_otk: false
              }, 0x11);
              if (singleBundle && singleBundle.devices && singleBundle.devices.length > 0) {
                const fetchedDev = singleBundle.devices[0];
                target.dev.opk_pub = fetchedDev.opk_pub;
                target.dev.opk_id = fetchedDev.opk_id;
              }
            } catch (e) {
              console.warn(`Failed to fetch OPK for device ${target.dev.device_id}:`, e);
            }
          }

          // Check connection state again to prevent advancing the ratchet locally if the socket disconnected in the meantime
          if (!ws.isConnected()) {
            throw new Error("Соединение потеряно перед шифрованием");
          }

          await ensureSessionForDevice(target.userId, target.dev);

          const textBytes = new TextEncoder().encode(text);
          const signalMsg = await cipher.encrypt(textBytes.buffer);

          const typeByte = signalMsg.type; // 1 (WhisperMessage) or 3 (PreKeyWhisperMessage)
          const bodyBytes = new Uint8Array(
            Array.from(signalMsg.body).map(c => c.charCodeAt(0))
          );
          const cipherBytes = new Uint8Array(1 + bodyBytes.length);
          cipherBytes[0] = typeByte;
          cipherBytes.set(bodyBytes, 1);

          devicesCiphertexts.push({
            device_id: Number(target.dev.device_id),
            cipher_bytes: cipherBytes
          });
        };

        if (navigator.locks) {
          await navigator.locks.request(lockName, encryptForDevice);
        } else {
          await encryptForDevice();
        }
      }

      const msgId = crypto.randomUUID();

      const storedMsg = {
        msg_id: msgId,
        client_msg_id: msgId,
        chat_id: userId,
        sender_id: myId,
        plaintext: text,
        created_at: now,
        delivered: 0,
        ciphertexts: devicesCiphertexts
      };
      await saveMessage(storedMsg);
      await saveContact({ ...contact, last_message: text, last_ts: now });
      triggerBackgroundBackup();

      pendingAcks.set(String(msgId), { tempId: msgId, userId: userId });

      let sent = false;
      try {
        sent = ws.send(0x01, {
          to_user_id: Number(userId),
          devices: devicesCiphertexts,
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
