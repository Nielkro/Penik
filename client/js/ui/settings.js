import { el, showToast, spinner, formatFullTime, avatar } from "./components.js";
import { getTheme, setTheme } from "../theme.js";
import {
  listDevices,
  apiPatch,
  apiPost,
  apiGet,
  createPairingSession,
  getPairingSession,
  uploadPairingHistory
} from "../api.js";
import {
  getCurrentUser,
  navigate,
  logout,
  backupE2EEKeys,
  restoreE2EEKeys
} from "../app.js";
import {
  deriveSharedSecret,
  encryptPairingHistory,
  generateKeyPair
} from "../crypto.js";
import { importPairingHistory } from "../pairing.js";
import {
  getAllMessages,
  getAllContacts,
  getAllGroups,
  getAllGroupMembers,
  getAllGroupKeysPlain,
  getAllGroupMessages
} from "../storage.js";
import { ws, OP } from "../ws.js";
import QRCode from "qrcode";

const decodeB64Url = s => {
  const normalized = String(s).trim().replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, c => c.charCodeAt(0));
};

const encodeB64Url = b => {
  const bytes = b instanceof Uint8Array ? b : new Uint8Array(b);
  let binary = "";
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
};

const pack = ({ ciphertext, salt, nonce }) =>
  new TextEncoder().encode(JSON.stringify({
    ciphertext: encodeB64Url(ciphertext),
    salt: encodeB64Url(salt),
    nonce: encodeB64Url(nonce)
  }));

export function renderSettings(container) {
  container.innerHTML = "";

  const user = getCurrentUser();
  if (!user) {
    navigate("#login");
    return;
  }

  const userId = user.user_id || user.id || "";
  const currentAvatarUser = { ...user };
  if (!currentAvatarUser.avatar_url && userId) {
    currentAvatarUser.avatar_url = `/api/v1/avatar/${userId}?t=${Date.now()}`;
  }

  // --- Header with Back Button ---
  const header = el("div", { class: "profile-header", style: "display:flex;align-items:center;gap:12px;padding:14px 16px;background:var(--panel);border-bottom:1px solid var(--border);position:sticky;top:0;z-index:10;" },
    el("button", { class: "icon-btn", onclick: () => navigate("#chats"), title: "Назад" }, "←"),
    el("h2", { class: "profile-title", style: "margin:0;font-size:18px;font-weight:700;" }, "Настройки")
  );

  // --- Profile Card Header ---
  const userAvatarEl = avatar(currentAvatarUser, 56);
  const profileInfo = el("div", { style: "display:flex;flex-direction:column;flex:1;overflow:hidden;margin-left:14px;" },
    el("span", { style: "font-size:17px;font-weight:600;color:var(--text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;" }, user.name || "Пользователь"),
    el("span", { style: "font-size:13px;color:var(--text-muted);margin-top:2px;" }, `@${user.username || user.nickname || ""}`),
    el("span", { style: "font-size:12px;color:var(--text-muted);opacity:0.8;margin-top:2px;" }, `ID: ${userId}`)
  );
  const editProfileArrow = el("span", { style: "color:var(--text-muted);font-size:18px;padding-right:4px;" }, "›");

  const profileCard = el("div", {
    style: "display:flex;align-items:center;padding:16px 18px;background:var(--panel);border:1px solid var(--border);border-radius:var(--r);cursor:pointer;transition:background 0.15s ease;",
    title: "Редактировать профиль",
    onclick: () => navigate("#profile")
  }, userAvatarEl, profileInfo, editProfileArrow);

  // --- Section Helper ---
  const createSection = (titleText, ...children) => {
    return el("div", { style: "display:flex;flex-direction:column;gap:8px;margin-top:16px;" },
      el("span", { style: "font-size:12px;font-weight:600;text-transform:uppercase;color:var(--text-muted);letter-spacing:0.5px;padding-left:4px;" }, titleText),
      ...children
    );
  };

  // --- 1. Appearance Section ---
  const currentTheme = getTheme();
  const themeSub = el("span", { style: "font-size:12px;color:var(--text-muted);" }, currentTheme === "light" ? "Светлая" : "Тёмная");
  const themeTextCol = el("div", { style: "display:flex;flex-direction:column;gap:2px;" },
    el("span", { style: "font-size:15px;color:var(--text);font-weight:500;" }, "Тема"),
    themeSub
  );

  const themeToggle = el("button", {
    class: "btn-secondary",
    style: "min-width:110px;cursor:pointer;padding:8px 14px;font-size:13px;"
  }, currentTheme === "light" ? "🌙 Тёмная" : "☀️ Светлая");

  function syncThemeState() {
    const isLight = getTheme() === "light";
    themeSub.textContent = isLight ? "Светлая" : "Тёмная";
    themeToggle.textContent = isLight ? "🌙 Тёмная" : "☀️ Светлая";
  }

  themeToggle.addEventListener("click", () => {
    setTheme(getTheme() === "light" ? "dark" : "light");
    syncThemeState();
  });

  const themeRow = el("div", {
    style: "display:flex;align-items:center;justify-content:space-between;padding:14px 18px;background:var(--panel);border:1px solid var(--border);border-radius:var(--r);"
  }, themeTextCol, themeToggle);

  const appearanceSection = createSection("Оформление", themeRow);

  // --- 2. Security & Account Section ---
  const changePasswordBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:12px 16px;cursor:pointer;font-size:14px;"
  },
    el("span", { style: "color:var(--text);" }, "Сменить пароль"),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );

  const oldPwInput = el("input", {
    type: "password",
    placeholder: "Текущий пароль",
    class: "profile-input hidden",
    style: "width:100%;margin-bottom:8px;padding:10px 12px;border-radius:var(--r);background:var(--bg);border:1px solid var(--border);color:var(--text);font-size:14px;"
  });
  const newPwInput = el("input", {
    type: "password",
    placeholder: "Новый пароль (от 6 символов)",
    class: "profile-input hidden",
    style: "width:100%;margin-bottom:8px;padding:10px 12px;border-radius:var(--r);background:var(--bg);border:1px solid var(--border);color:var(--text);font-size:14px;"
  });
  const submitPwBtn = el("button", {
    class: "btn-primary hidden",
    style: "margin-right:8px;padding:8px 14px;font-size:13px;cursor:pointer;"
  }, "Сохранить новый пароль");
  const cancelPwBtn = el("button", {
    class: "btn-ghost hidden",
    style: "padding:8px 14px;font-size:13px;cursor:pointer;"
  }, "Отмена");

  const revokeCheckbox = el("input", { type: "checkbox", id: "pw-revoke-others", style: "margin:0;cursor:pointer;" });
  const revokeLabel = el("label", {
    class: "hidden",
    for: "pw-revoke-others",
    style: "display:flex;align-items:center;gap:8px;margin-bottom:8px;font-size:12px;color:var(--text-muted);cursor:pointer;",
  }, revokeCheckbox, "Отозвать все сессии кроме текущей");

  changePasswordBtn.addEventListener("click", () => {
    oldPwInput.classList.remove("hidden");
    newPwInput.classList.remove("hidden");
    revokeLabel.classList.remove("hidden");
    submitPwBtn.classList.remove("hidden");
    cancelPwBtn.classList.remove("hidden");
    changePasswordBtn.classList.add("hidden");
    oldPwInput.focus();
  });

  cancelPwBtn.addEventListener("click", () => {
    oldPwInput.value = "";
    newPwInput.value = "";
    revokeCheckbox.checked = false;
    oldPwInput.classList.add("hidden");
    newPwInput.classList.add("hidden");
    revokeLabel.classList.add("hidden");
    submitPwBtn.classList.add("hidden");
    cancelPwBtn.classList.add("hidden");
    changePasswordBtn.classList.remove("hidden");
  });

  submitPwBtn.addEventListener("click", async () => {
    const oldPassword = oldPwInput.value;
    const newPassword = newPwInput.value;
    if (!oldPassword || !newPassword) {
      showToast("Заполните оба поля пароля", "error");
      return;
    }
    if (newPassword.length < 6) {
      showToast("Новый пароль должен быть не менее 6 символов", "error");
      return;
    }

    submitPwBtn.disabled = true;
    const origText = submitPwBtn.textContent;
    submitPwBtn.textContent = "";
    submitPwBtn.appendChild(spinner());

    try {
      const pwRes = await apiPatch("/users/me/password", {
        old_password: oldPassword,
        new_password: newPassword,
        revoke_other_sessions: revokeCheckbox.checked,
      });

      showToast("Пароль успешно изменен!", "success");
      if (revokeCheckbox.checked) {
        if (pwRes && pwRes.revoked_other_sessions) {
          showToast("Остальные сессии отозваны", "success");
        } else if (pwRes && pwRes.revoke_skipped_reason === "session_too_recent") {
          showToast("Сессии не отозваны: этот сеанс младше 24 часов", "info");
        } else {
          showToast("Не удалось отозвать остальные сессии", "error");
        }
      }
      cancelPwBtn.click();
    } catch (err) {
      showToast("Не удалось изменить пароль: " + err.message, "error");
    } finally {
      submitPwBtn.disabled = false;
      submitPwBtn.textContent = origText;
    }
  });

  const revokeSessionsBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 18px;cursor:pointer;"
  },
    el("div", { style: "display:flex;flex-direction:column;align-items:flex-start;gap:2px;" },
      el("span", { style: "color:var(--text);font-size:15px;font-weight:500;" }, "Отозвать все сессии"),
      el("span", { style: "color:var(--text-muted);font-size:12px;" }, "Кроме текущей сессии")
    ),
    el("span", { style: "color:var(--text-muted);font-size:18px;" }, "›")
  );
  revokeSessionsBtn.addEventListener("click", async () => {
    revokeSessionsBtn.disabled = true;
    const origLabel = revokeSessionsBtn.innerHTML;
    revokeSessionsBtn.textContent = "";
    revokeSessionsBtn.appendChild(spinner());
    try {
      await apiPost("/logout/all");
      showToast("Остальные сессии отозваны", "success");
    } catch (err) {
      showToast(
        err && err.status === 403
          ? "Этот сеанс младше 24 часов — отзыв пока недоступен"
          : (err?.message || "Не удалось отозвать сессии"),
        err && err.status === 403 ? "info" : "error"
      );
    } finally {
      revokeSessionsBtn.disabled = false;
      revokeSessionsBtn.innerHTML = origLabel;
    }
  });

  const securityBox = el("div", {
    style: "background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:4px;display:flex;flex-direction:column;gap:4px;"
  },
    changePasswordBtn,
    oldPwInput,
    newPwInput,
    revokeLabel,
    el("div", { style: "display:flex;padding:0 8px 8px;" }, submitPwBtn, cancelPwBtn),
    revokeSessionsBtn
  );

  const securitySection = createSection("Безопасность и вход", securityBox);

  // --- 3. E2EE Key Backup Section ---
  const backupSectionBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:12px 16px;cursor:pointer;font-size:14px;"
  },
    el("span", { style: "color:var(--text);" }, "Резервная копия ключей"),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );

  const backupPassInput = el("input", {
    type: "password",
    placeholder: "Пароль резервной копии",
    class: "profile-input",
    style: "width:100%;padding:10px 12px;padding-right:36px;border-radius:var(--r);background:var(--bg);border:1px solid var(--border);color:var(--text);font-size:14px;"
  });

  const toggleVisibilityBtn = el("button", {
    type: "button",
    style: "position:absolute;right:8px;top:50%;transform:translateY(-50%);background:none;border:none;color:var(--text-muted);cursor:pointer;font-size:16px;padding:4px;user-select:none;line-height:1;display:flex;align-items:center;justify-content:center;"
  }, "👁️");

  toggleVisibilityBtn.addEventListener("click", () => {
    if (backupPassInput.type === "password") {
      backupPassInput.type = "text";
      toggleVisibilityBtn.textContent = "🙈";
    } else {
      backupPassInput.type = "password";
      toggleVisibilityBtn.textContent = "👁️";
    }
  });

  const backupPassWrapper = el("div", {
    class: "hidden",
    style: "position:relative;width:100%;margin-bottom:8px;"
  }, backupPassInput, toggleVisibilityBtn);

  const doBackupBtn = el("button", { class: "btn-primary hidden", style: "margin-right:8px;padding:8px 14px;font-size:13px;cursor:pointer;" }, "Создать копию");
  const doRestoreBtn = el("button", { class: "btn-secondary hidden", style: "margin-right:8px;padding:8px 14px;font-size:13px;cursor:pointer;" }, "Восстановить");
  const cancelBackupBtn = el("button", { class: "btn-ghost hidden", style: "padding:8px 14px;font-size:13px;cursor:pointer;" }, "Отмена");

  backupSectionBtn.addEventListener("click", () => {
    backupPassWrapper.classList.remove("hidden");
    doBackupBtn.classList.remove("hidden");
    doRestoreBtn.classList.remove("hidden");
    cancelBackupBtn.classList.remove("hidden");
    backupSectionBtn.classList.add("hidden");
    backupPassInput.focus();
  });

  cancelBackupBtn.addEventListener("click", () => {
    backupPassInput.value = "";
    backupPassInput.type = "password";
    toggleVisibilityBtn.textContent = "👁️";
    backupPassWrapper.classList.add("hidden");
    doBackupBtn.classList.add("hidden");
    doRestoreBtn.classList.add("hidden");
    cancelBackupBtn.classList.add("hidden");
    backupSectionBtn.classList.remove("hidden");
  });

  doBackupBtn.addEventListener("click", async () => {
    const password = backupPassInput.value;
    if (!password || password.length < 6) {
      showToast("Пароль резервной копии должен быть не менее 6 символов", "error");
      return;
    }

    doBackupBtn.disabled = true;
    const origText = doBackupBtn.textContent;
    doBackupBtn.textContent = "";
    doBackupBtn.appendChild(spinner());

    try {
      await backupE2EEKeys(password);
      showToast("Резервная копия ключей успешно создана на сервере!", "success");
      cancelBackupBtn.click();
    } catch (err) {
      showToast("Ошибка создания копии: " + err.message, "error");
    } finally {
      doBackupBtn.disabled = false;
      doBackupBtn.textContent = origText;
    }
  });

  doRestoreBtn.addEventListener("click", async () => {
    const password = backupPassInput.value;
    if (!password) {
      showToast("Введите пароль резервной копии", "error");
      return;
    }

    doRestoreBtn.disabled = true;
    const origText = doRestoreBtn.textContent;
    doRestoreBtn.textContent = "";
    doRestoreBtn.appendChild(spinner());

    try {
      await restoreE2EEKeys(password);
      showToast("Ключи шифрования успешно восстановлены!", "success");
      cancelBackupBtn.click();
    } catch (err) {
      showToast("Ошибка восстановления: " + err.message, "error");
    } finally {
      doRestoreBtn.disabled = false;
      doRestoreBtn.textContent = origText;
    }
  });

  apiGet("/keys/backup").then(backup => {
    if (!backup || !backup.encrypted_blob) {
      doRestoreBtn.style.display = "none";
    }
  }).catch(() => {
    doRestoreBtn.style.display = "none";
  });

  const backupBox = el("div", {
    style: "background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:4px;display:flex;flex-direction:column;"
  },
    backupSectionBtn,
    backupPassWrapper,
    el("div", { style: "display:flex;padding:0 8px 8px;" }, doBackupBtn, doRestoreBtn, cancelBackupBtn)
  );

  const backupSection = createSection("Ключи и шифрование (E2EE)", backupBox);

  // --- 4. Devices & Pairing Section ---
  const devicesBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:12px 16px;cursor:pointer;font-size:14px;"
  },
    el("span", { style: "color:var(--text);" }, "Мои устройства"),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  devicesBtn.addEventListener("click", () => navigate("#devices"));

  const pairingBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:12px 16px;cursor:pointer;font-size:14px;"
  },
    el("span", { style: "color:var(--text);" }, "Подключить устройство (QR)"),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  pairingBtn.addEventListener("click", async () => {
    pairingBtn.disabled = true;
    try {
      const kp = await generateKeyPair();
      const keyText = encodeB64Url(kp.publicKey);
      const session = await createPairingSession({ ephemeral_public_key: keyText });
      const payload = `penik-pair-v1:${session.session_id}:${session.token}:${session.ephemeral_public_key}`;
      const canvas = document.createElement("canvas");
      await QRCode.toCanvas(canvas, payload, { width: 280, margin: 2 });
      const modal = el("div", { style: "position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;padding:20px;" });
      const close = () => modal.remove();
      modal.addEventListener("click", event => { if (event.target === modal) close(); });
      modal.appendChild(el("div", { style: "width:min(360px,100%);background:var(--panel);border:1px solid var(--border);border-radius:16px;padding:24px;text-align:center;box-shadow:0 12px 40px rgba(0,0,0,.45);" },
        el("h3", { style: "margin:0 0 10px;color:var(--text);" }, "Подключение устройства"),
        el("p", { style: "margin:0 0 16px;color:var(--text-muted);font-size:13px;line-height:1.4;" }, "Отсканируйте этот QR-код телефоном. Код действует 5 минут."),
        canvas,
        el("p", { style: "margin:14px 0;color:var(--text-muted);font-size:11px;word-break:break-all;" }, `Сессия: ${session.session_id}`),
        el("button", { class: "btn-ghost", style: "width:100%;cursor:pointer;", onclick: close }, "Закрыть")
      ));
      document.body.appendChild(modal);
      const state = await new Promise((resolve, reject) => {
        let finished = false;
        const finish = value => { if (finished) return; finished = true; clearTimeout(timer); clearInterval(poller); unsubscribe(); resolve(value); };
        const timer = setTimeout(() => { if (!finished) { finished = true; clearInterval(poller); unsubscribe(); reject(new Error("Телефон не подтвердил подключение")); } }, 5 * 60 * 1000);
        const unsubscribe = ws.on(OP.PAIRING_CLAIMED, event => {
          if (event.session_id === session.session_id) finish(event);
        });
        const poller = setInterval(async () => {
          try {
            const current = await getPairingSession(session.session_id);
            if (current.claimed && current.public_key) finish(current);
          } catch (_) {}
        }, 1000);
      });
      if (state.public_key) {
        const secret = await deriveSharedSecret(kp.privateKey, decodeB64Url(state.public_key));
        const messages = await getAllMessages();
        const contacts = await getAllContacts();
        const groups = await getAllGroups();
        const groupMembers = await getAllGroupMembers();
        const rawGroupKeys = await getAllGroupKeysPlain();
        const groupKeys = rawGroupKeys.map(k => ({
          ...k,
          key: encodeB64Url(k.key)
        }));
        const groupMessages = await getAllGroupMessages();

        const blob = await encryptPairingHistory({
          messages,
          contacts,
          groups,
          group_members: groupMembers,
          group_keys: groupKeys,
          group_messages: groupMessages
        }, secret);
        const messageIds = (messages || [])
          .map(m => Number(m.msg_id))
          .filter(id => !isNaN(id) && id > 0);

        await uploadPairingHistory(session.session_id, {
          encrypted_history: encodeB64Url(pack(blob)),
          message_ids: messageIds
        });
        showToast("История передана устройству", "success");
      }
    } catch (err) {
      showToast(err.message || "Не удалось создать сессию", "error");
    } finally {
      pairingBtn.disabled = false;
    }
  });

  const receiveHistoryBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:12px 16px;cursor:pointer;font-size:14px;"
  },
    el("span", { style: "color:var(--text);" }, "Получить историю с телефона"),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  receiveHistoryBtn.addEventListener("click", async () => {
    receiveHistoryBtn.disabled = true;
    try {
      const kp = await generateKeyPair();
      const session = await createPairingSession({
        ephemeral_public_key: encodeB64Url(kp.publicKey),
        transfer_direction: "phone_to_web"
      });
      const payload = `penik-pair-v1:${session.session_id}:${session.token}:${session.ephemeral_public_key}`;
      const canvas = document.createElement("canvas");
      await QRCode.toCanvas(canvas, payload, { width: 280, margin: 2 });
      const modal = el("div", { style: "position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;padding:20px;" });
      const close = () => modal.remove();
      modal.appendChild(el("div", { style: "width:min(360px,100%);background:var(--panel);border:1px solid var(--border);border-radius:16px;padding:24px;text-align:center;box-shadow:0 12px 40px rgba(0,0,0,.45);" },
        el("h3", { style: "margin:0 0 10px;color:var(--text);" }, "Получение истории"),
        el("p", { style: "margin:0 0 16px;color:var(--text-muted);font-size:13px;line-height:1.4;" }, "Отсканируйте QR-код телефоном. После подтверждения история будет передана сюда."),
        canvas,
        el("button", { class: "btn-ghost", style: "width:100%;cursor:pointer;", onclick: close }, "Закрыть")
      ));
      document.body.appendChild(modal);

      await new Promise((resolve, reject) => {
        let finished = false;
        const finish = value => { if (finished) return; finished = true; clearTimeout(timer); clearInterval(poller); unsubscribe(); resolve(value); };
        const unsubscribe = ws.on(OP.PAIRING_CLAIMED, event => {
          if (event.session_id === session.session_id) finish(event);
        });
        const poller = setInterval(async () => {
          try {
            const current = await getPairingSession(session.session_id);
            if (current.claimed && current.public_key) finish(current);
          } catch (_) {}
        }, 1000);
        const timer = setTimeout(() => {
          if (finished) return;
          finished = true;
          clearInterval(poller);
          unsubscribe();
          reject(new Error("Телефон не подтвердил передачу"));
        }, 5 * 60 * 1000);
      });

      let state = await getPairingSession(session.session_id);
      const deadline = Date.now() + 5 * 60 * 1000;
      while (!state.encrypted_history && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 1000));
        state = await getPairingSession(session.session_id);
      }
      if (!state.encrypted_history) throw new Error("Телефон не передал историю");
      const secret = await deriveSharedSecret(kp.privateKey, decodeB64Url(state.public_key));
      await importPairingHistory(state.encrypted_history, secret);
      close();
      showToast("История получена с телефона", "success");
    } catch (err) {
      showToast(err.message || "Не удалось получить историю", "error");
    } finally {
      receiveHistoryBtn.disabled = false;
    }
  });

  const devicesBox = el("div", {
    style: "background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:4px;display:flex;flex-direction:column;gap:4px;"
  },
    devicesBtn,
    pairingBtn,
    receiveHistoryBtn
  );

  const devicesSection = createSection("Устройства и синхронизация", devicesBox);

  // --- 5. Logout Section ---
  const logoutBtn = el("button", {
    class: "btn-danger",
    style: "width:100%;padding:14px 18px;cursor:pointer;margin-top:20px;font-size:15px;border-radius:var(--r);font-weight:500;"
  }, "Выйти из аккаунта");
  logoutBtn.addEventListener("click", () => {
    logout();
    showToast("Вы вышли из системы", "info");
  });

  const content = el("div", { style: "display:flex;flex-direction:column;gap:10px;padding:20px;max-width:860px;margin:0 auto;width:100%;box-sizing:border-box;" },
    profileCard,
    appearanceSection,
    securitySection,
    backupSection,
    devicesSection,
    logoutBtn
  );

  const scrollWrapper = el("div", { style: "flex:1;overflow-y:auto;overflow-x:hidden;" }, content);

  const wrap = el("div", { class: "settings-wrap", style: "display:flex;flex-direction:column;height:100%;overflow:hidden;width:100%;" },
    header,
    scrollWrapper
  );

  container.appendChild(wrap);
}

// renderDevices renders the dedicated devices screen listing the user's devices.
export function renderDevices(container) {
  const backBtn = el("button", { class: "icon-btn", style: "cursor:pointer;", title: "Назад" }, "←");
  backBtn.addEventListener("click", () => navigate("#settings"));

  const header = el("div", { class: "profile-header", style: "display:flex;align-items:center;gap:12px;padding:14px 16px;background:var(--panel);border-bottom:1px solid var(--border);" },
    backBtn,
    el("h2", { class: "profile-title", style: "margin:0;font-size:18px;font-weight:700;" }, "Мои устройства")
  );

  const list = el("div", { style: "display:flex;flex-direction:column;gap:10px;" }, spinner());

  function render(devices) {
    list.innerHTML = "";
    if (!devices || devices.length === 0) {
      list.appendChild(el("div", { style: "color:var(--text-muted);font-size:14px;padding:16px;text-align:center;" }, "Нет подключенных устройств"));
      return;
    }
    for (const d of devices) {
      const title = el("div", { style: "font-size:15px;color:var(--text);font-weight:600;" },
        d.platform || d.device_name || "Устройство",
        d.is_current ? el("span", { style: "margin-left:8px;font-size:12px;color:var(--success);font-weight:normal;" }, "· это устройство") : ""
      );
      const locationLine = el("div", { style: "font-size:13px;color:var(--text-muted);margin-top:4px;" },
        d.location ? `📍 ${d.location}` : "📍 Местоположение неизвестно"
      );
      const meta = el("div", { style: "font-size:12px;color:var(--text-muted);margin-top:4px;" },
        `Активно: ${formatFullTime(d.last_seen * 1000)}`,
        d.has_session
          ? el("span", { style: "margin-left:8px;color:var(--success);" }, "в сети")
          : el("span", { style: "margin-left:8px;color:var(--text-muted);" }, "нет активной сессии")
      );
      list.appendChild(el("div", {
        style: "padding:14px 18px;background:var(--panel);border:1px solid var(--border);border-radius:var(--r);"
      }, title, locationLine, meta));
    }
  }

  listDevices()
    .then(render)
    .catch(() => {
      list.innerHTML = "";
      list.appendChild(el("div", { style: "color:var(--danger);font-size:13px;padding:12px;" }, "Не удалось загрузить устройства"));
    });

  const content = el("div", { style: "padding:20px;max-width:860px;margin:0 auto;width:100%;box-sizing:border-box;" }, list);
  const scrollWrapper = el("div", { style: "flex:1;overflow-y:auto;overflow-x:hidden;" }, content);

  const wrap = el("div", { class: "settings-wrap", style: "display:flex;flex-direction:column;height:100%;overflow:hidden;width:100%;" },
    header,
    scrollWrapper
  );
  container.appendChild(wrap);
}
