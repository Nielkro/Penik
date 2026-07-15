import { apiGet } from "../api.js";
import {
  x3dhInitiate, encryptMessage, decryptMessage,
  importX25519Priv, importX25519Pub, encodeKey, decodeKey, verifySignature,
  diffieHellman, generateDH, kdf_rk, kdf_ck, computeSafetyNumber
} from "../crypto.js";
import {
  getSession, getAnySession, saveSession, saveMessage, getMessages,
  updateMessageDelivered, getContact, saveContact, getAllContacts,
  getIdentity, deleteChatData, clearUserSessions
} from "../storage.js";
import { navigate, getWS, getCurrentUser, setActiveChatCallback, setChatListUpdateCallback, triggerChatListUpdate, pendingAcksQueue, triggerBackgroundBackup } from "../app.js";
import { avatar, formatTime, formatDate, el, showToast, spinner, showSafetyNumberModal, showConfirmModal } from "./components.js";

// Helper to convert a number to a 32-bit Big-Endian Uint8Array
function numTo32BE(num) {
  const arr = new Uint8Array(4);
  const view = new DataView(arr.buffer);
  view.setUint32(0, num, false);
  return arr;
}

// Helper to concatenate multiple Uint8Arrays
function concatU8(...arrays) {
  const total = arrays.reduce((n, a) => n + a.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const a of arrays) { out.set(a, off); off += a.length; }
  return out;
}

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
  const theirIKRaw  = toRaw(activeDevice.ik_pub);
  let theirIKDH = theirIKRaw;
  if (theirIKRaw.length === 64) {
    theirIKDH = theirIKRaw.slice(0, 32);
  }

  // Check if we already have a session for this specific active device
  const existing = await getSession(toUserId, activeDevice.device_id);
  if (existing) {
    if (existing.their_ik_pub) {
      let isSame = true;
      for (let i = 0; i < 32; i++) {
        if (existing.their_ik_pub[i] !== theirIKDH[i]) {
          isSame = false;
          break;
        }
      }
      if (!isSame) {
        const trusted = await showConfirmModal(
          "Изменение кода безопасности",
          `Код безопасности для устройства ${activeDevice.device_id} пользователя изменился. Это может означать попытку взлома или то, что пользователь переустановил приложение. Вы доверяете новому коду?`
        );
        if (!trusted) {
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
        
        // Append to DOM immediately if chat is open
        const messagesEl = document.querySelector(`.chat-messages[data-user-id="${toUserId}"]`);
        if (messagesEl) {
          const bubble = el("div", { class: "msg-bubble msg-system" },
            el("span", { class: "msg-text" }, "⚠️ Код безопасности изменился!")
          );
          messagesEl.appendChild(bubble);
          messagesEl.scrollTop = messagesEl.scrollHeight;
        }

        await clearUserSessions(toUserId);
        triggerChatListUpdate();
        // Do NOT return existing, let it fall through to negotiate a new session!
      } else {
        return existing;
      }
    } else {
      existing.their_ik_pub = theirIKDH;
      await saveSession(existing);
      return existing;
    }
  }

  // Check if we have any other session with this user to detect identity key change
  const anyExisting = await getAnySession(toUserId);
  if (anyExisting && anyExisting.their_ik_pub) {
    let isSame = true;
    for (let i = 0; i < 32; i++) {
      if (anyExisting.their_ik_pub[i] !== theirIKDH[i]) {
        isSame = false;
        break;
      }
    }
    if (!isSame) {
      const trusted = await showConfirmModal(
        "Изменение кода безопасности",
        `Код безопасности пользователя изменился. Вы доверяете новому коду?`
      );
      if (!trusted) {
        throw new Error("Отправка отменена: код безопасности пользователя не подтвержден.");
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

      // Append to DOM immediately if chat is open
      const messagesEl = document.querySelector(`.chat-messages[data-user-id="${toUserId}"]`);
      if (messagesEl) {
        const bubble = el("div", { class: "msg-bubble msg-system" },
          el("span", { class: "msg-text" }, "⚠️ Код безопасности изменился!")
        );
        messagesEl.appendChild(bubble);
        messagesEl.scrollTop = messagesEl.scrollHeight;
      }

      await clearUserSessions(toUserId);
      triggerChatListUpdate();
    }
  }

  const identity = await getIdentity();
  if (!identity) throw new Error("Нет локального ключа идентификации");

  const ourIKPriv = await importX25519Priv(identity.ik_priv_jwk);

  const theirSPKRaw = toRaw(activeDevice.spk_pub);
  const theirSPKSig = toRaw(activeDevice.spk_sig);

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

  let theirOPKRaw = null;
  if (activeDevice.opk_pub) {
    theirOPKRaw = toRaw(activeDevice.opk_pub);
  }

  const result = await x3dhInitiate(ourIKPriv, theirIKDH, theirSPKRaw, theirOPKRaw);
  const rootKey = result.sharedSecret; // SK
  const sessionInitEk = result.ekPubRaw; // EK_A

  // Generate Alice's first ephemeral DH key pair
  const ourDH = await generateDH();

  // Compute shared secret: DH(our_dh, their_SPK_Pub)
  const theirSPKPubImported = await importX25519Pub(theirSPKRaw);
  const sharedSecret = await diffieHellman(ourDH.privateKey, theirSPKPubImported);

  // Derive root key and send chain key
  const step = await kdf_rk(rootKey, sharedSecret, "DoubleRatchetRoot");

  const session = {
    user_id: Number(toUserId),
    device_id: activeDevice.device_id,
    root_key: step.newRootKey,
    send_chain_key: step.chainKey,
    recv_chain_key: null,
    our_dh_private_jwk: ourDH.privJwk,
    our_dh_public_raw: ourDH.pubRaw,
    their_dh_public_raw: theirSPKRaw,
    their_ik_pub: theirIKDH,
    session_init_ek: sessionInitEk,
    n_send: 0,
    n_recv: 0,
    pn: 0,
    created_at: Date.now()
  };
  if (theirOPKRaw) {
    session.used_opk_pub = theirOPKRaw;
  }
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
      
      if (!identity || !identity.ik_pub_raw) {
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
        new Uint8Array(identity.ik_pub_raw),
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
    if (!confirm("Вы действительно хотите удалить этот чат и все сообщения? Это также сбросит криптографическую сессию с пользователем.")) {
      return;
    }
    try {
      await deleteChatData(userId);
      showToast("Чат удален");
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
      const bubble = el("div", { class: "msg-bubble msg-system" },
        el("span", { class: "msg-text" }, msg.plaintext || "")
      );
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

    try {
      const ws = getWS();
      if (!ws || !ws.isConnected()) throw new Error("Нет соединения");

      // 1. Fetch key bundles for recipient
      const keyBundle = await ws.request(0x10, { user_id: Number(userId) }, 0x11);
      if (!keyBundle || !keyBundle.devices || !keyBundle.devices.length) {
        throw new Error("У собеседника нет активных устройств");
      }

      // 2. Fetch key bundles for our own other devices (for multi-device sync)
      let ourBundle = null;
      try {
        ourBundle = await ws.request(0x10, { user_id: Number(myId) }, 0x11);
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

      // 4. Encrypt separately for each target device
      const devicesCiphertexts = [];
      for (const target of targetDevices) {
        const session = await ensureSessionForDevice(target.userId, target.dev);
        if (!session.send_chain_key) {
          throw new Error(`Отправляющая цепочка не инициализирована для устройства ${target.dev.device_id}`);
        }

        // Derive message key and new send chain key
        const { newChainKey, messageKey } = await kdf_ck(session.send_chain_key);

        const ourDhPub = session.our_dh_public_raw;
        const nBytes = numTo32BE(session.n_send);
        const pnBytes = numTo32BE(session.pn);

        let aad;
        if (session.n_send === 0 && session.session_init_ek) {
          const usedOpkPub = session.used_opk_pub ? new Uint8Array(session.used_opk_pub) : new Uint8Array(32);
          aad = concatU8(session.session_init_ek, ourDhPub, usedOpkPub, nBytes, pnBytes);
        } else {
          aad = concatU8(ourDhPub, nBytes, pnBytes);
        }

        // Encrypt message with AAD
        const ivAndCiphertext = await encryptMessage(messageKey, text, aad);
        const cipherBytes = concatU8(aad, ivAndCiphertext);

        // Update session state
        session.send_chain_key = newChainKey;
        session.n_send += 1;
        await saveSession(session);

        devicesCiphertexts.push({
          device_id: Number(target.dev.device_id),
          cipher_bytes: cipherBytes
        });
      }

      const msgId = crypto.randomUUID();

      ws.send(0x01, {
        to_user_id: Number(userId),
        devices: devicesCiphertexts,
        msg_id: msgId,
      });

      pendingAcksQueue.push({ tempId: msgId, userId: userId });

      const storedMsg = {
        msg_id: msgId,
        chat_id: userId,
        sender_id: myId,
        plaintext: text,
        created_at: now,
        delivered: 0,
      };
      await saveMessage(storedMsg);
      await saveContact({ ...contact, last_message: text, last_ts: now });
      triggerBackgroundBackup();

      const oldBubble = messagesEl.querySelector(`[data-msg-id="${tempId}"]`);
      if (oldBubble) oldBubble.dataset.msgId = msgId;

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
