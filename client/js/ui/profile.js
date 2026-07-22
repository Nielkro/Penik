import { apiPatch, createPairingSession, getPairingSession, uploadPairingHistory } from "../api.js";
import { getAllMessages, getAllGroups, getAllGroupMembers, getAllGroupKeys, getAllGroupMessages } from "../storage.js";
import { deriveSharedSecret, encryptPairingHistory, generateKeyPair } from "../crypto.js";
const decodeB64Url = s => {
  const normalized = String(s).trim().replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, c => c.charCodeAt(0));
};
const encodeB64Url = b => btoa(String.fromCharCode(...b)).replaceAll("+","-").replaceAll("/","_").replaceAll("=","");
const pack = ({ ciphertext, salt, nonce }) => new TextEncoder().encode(JSON.stringify({ ciphertext: encodeB64Url(ciphertext), salt: encodeB64Url(salt), nonce: encodeB64Url(nonce) }));
import { navigate, getCurrentUser, setCurrentUser, logout, backupE2EEKeys, restoreE2EEKeys } from "../app.js";
import { avatar, el, showToast, spinner } from "./components.js";
import QRCode from "qrcode";
import { ws, OP } from "../ws.js";

export function renderProfile(container) {
  container.innerHTML = "";

  const user = getCurrentUser();
  if (!user) {
    navigate("#login");
    return;
  }

  const header = el("div", { class: "profile-header" },
    el("button", { class: "icon-btn", onclick: () => navigate("#chats"), title: "Назад" }, "←"),
    el("h2", { class: "profile-title" }, "Профиль")
  );

  const avatarEl = avatar(user, 80);
  avatarEl.classList.add("profile-avatar");

  const nameDisplay = el("span", { class: "profile-name", id: "profile-name-display" }, user.name || "");
  const nameInput = el("input", {
    type: "text",
    class: "profile-name-input hidden",
    value: user.name || "",
    id: "profile-name-input",
    maxlength: "64",
  });
  const editBtn = el("button", { class: "btn-secondary profile-edit-btn", id: "profile-edit-btn" }, "Изменить имя");
  const saveBtn = el("button", { class: "btn-primary profile-save-btn hidden", id: "profile-save-btn" }, "Сохранить");
  const cancelBtn = el("button", { class: "btn-ghost profile-cancel-btn hidden", id: "profile-cancel-btn" }, "Отмена");

  const username = user.username || user.nickname || "";
  const userId = user.user_id || user.id || "";
  const usernameEl = el("span", { class: "profile-username" }, `@${username}`);
  const userIdEl = el("span", { class: "profile-uid" }, `ID: ${userId}`);

  editBtn.addEventListener("click", () => {
    nameDisplay.classList.add("hidden");
    nameInput.classList.remove("hidden");
    editBtn.classList.add("hidden");
    saveBtn.classList.remove("hidden");
    cancelBtn.classList.remove("hidden");
    nameInput.focus();
    nameInput.select();
  });

  cancelBtn.addEventListener("click", () => {
    nameInput.value = user.name || "";
    nameInput.classList.add("hidden");
    nameDisplay.classList.remove("hidden");
    saveBtn.classList.add("hidden");
    cancelBtn.classList.add("hidden");
    editBtn.classList.remove("hidden");
  });

  saveBtn.addEventListener("click", async () => {
    const newName = nameInput.value.trim();
    if (!newName) {
      showToast("Имя не может быть пустым", "error");
      return;
    }
    if (newName === user.name) {
      cancelBtn.click();
      return;
    }

    saveBtn.disabled = true;
    saveBtn.textContent = "";
    saveBtn.appendChild(spinner());

    try {
      const res = await apiPatch("/users/me", { name: newName });
      const updatedUser = { ...user, name: res.name || newName };
      setCurrentUser(updatedUser);
      nameDisplay.textContent = updatedUser.name;

      nameInput.classList.add("hidden");
      nameDisplay.classList.remove("hidden");
      saveBtn.classList.add("hidden");
      cancelBtn.classList.add("hidden");
      editBtn.classList.remove("hidden");

      showToast("Имя обновлено", "success");
    } catch (err) {
      showToast(err.message || "Не удалось обновить имя", "error");
    } finally {
      saveBtn.disabled = false;
      saveBtn.textContent = "Сохранить";
    }
  });

  nameInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") saveBtn.click();
    if (e.key === "Escape") cancelBtn.click();
  });

  const logoutBtn = el("button", { class: "btn-danger profile-logout-btn" }, "Выйти");
  logoutBtn.addEventListener("click", () => {
    logout();
    showToast("Вы вышли из системы", "info");
  });

  const changePasswordBtn = el("button", { class: "btn-secondary profile-pw-btn", style: "width:100%;margin-top:8px;cursor:pointer;" }, "Сменить пароль");
  const oldPwInput = el("input", { type: "password", placeholder: "Текущий пароль", class: "profile-input hidden", style: "width:100%;margin-bottom:8px;padding:8px;border-radius:6px;background:rgba(255,255,255,0.05);border:1px solid rgba(255,255,255,0.1);color:#fff;" });
  const newPwInput = el("input", { type: "password", placeholder: "Новый пароль (от 6 символов)", class: "profile-input hidden", style: "width:100%;margin-bottom:8px;padding:8px;border-radius:6px;background:rgba(255,255,255,0.05);border:1px solid rgba(255,255,255,0.1);color:#fff;" });
  const submitPwBtn = el("button", { class: "btn-primary hidden", style: "margin-right:8px;padding:8px 12px;font-size:12px;cursor:pointer;" }, "Сохранить новый пароль");
  const cancelPwBtn = el("button", { class: "btn-ghost hidden", style: "padding:8px 12px;font-size:12px;cursor:pointer;" }, "Отмена");

  changePasswordBtn.addEventListener("click", () => {
    oldPwInput.classList.remove("hidden");
    newPwInput.classList.remove("hidden");
    submitPwBtn.classList.remove("hidden");
    cancelPwBtn.classList.remove("hidden");
    changePasswordBtn.classList.add("hidden");
    oldPwInput.focus();
  });

  cancelPwBtn.addEventListener("click", () => {
    oldPwInput.value = "";
    newPwInput.value = "";
    oldPwInput.classList.add("hidden");
    newPwInput.classList.add("hidden");
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
      // 1. Change password on the server
      await apiPatch("/users/me/password", { old_password: oldPassword, new_password: newPassword });

      showToast("Пароль успешно изменен!", "success");
      cancelPwBtn.click();
    } catch (err) {
      showToast("Не удалось изменить пароль: " + err.message, "error");
    } finally {
      submitPwBtn.disabled = false;
      submitPwBtn.textContent = origText;
    }
  });

  const pwSection = el("div", { class: "profile-password-section", style: "margin-top: 16px; border-top: 1px solid rgba(255,255,255,0.1); padding-top: 16px; width: 100%;" },
    el("h3", { style: "font-size: 14px; margin-bottom: 8px; color: #aaa;" }, "Безопасность"),
    changePasswordBtn,
    oldPwInput,
    newPwInput,
    el("div", { style: "display:flex;margin-top:8px;" }, submitPwBtn, cancelPwBtn)
  );

  // --- E2EE Key Backup Section ---
  const backupSectionBtn = el("button", { class: "btn-secondary", style: "width:100%;margin-top:8px;cursor:pointer;" }, "Резервная копия ключей E2EE");
  
  const backupPassInput = el("input", {
    type: "password",
    placeholder: "Пароль резервной копии",
    class: "profile-input",
    style: "width:100%;padding:8px;padding-right:32px;border-radius:6px;background:rgba(255,255,255,0.05);border:1px solid rgba(255,255,255,0.1);color:#fff;"
  });

  const toggleVisibilityBtn = el("button", {
    type: "button",
    style: "position:absolute;right:8px;top:50%;transform:translateY(-50%);background:none;border:none;color:#aaa;cursor:pointer;font-size:16px;padding:4px;user-select:none;line-height:1;display:flex;align-items:center;justify-content:center;"
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

  const doBackupBtn = el("button", { class: "btn-primary hidden", style: "margin-right:8px;padding:8px 12px;font-size:12px;cursor:pointer;" }, "Создать копию");
  const doRestoreBtn = el("button", { class: "btn-secondary hidden", style: "margin-right:8px;padding:8px 12px;font-size:12px;cursor:pointer;" }, "Восстановить");
  const cancelBackupBtn = el("button", { class: "btn-ghost hidden", style: "padding:8px 12px;font-size:12px;cursor:pointer;" }, "Отмена");

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

  const backupSection = el("div", { class: "profile-backup-section", style: "margin-top: 16px; border-top: 1px solid rgba(255,255,255,0.1); padding-top: 16px; width: 100%;" },
    el("h3", { style: "font-size: 14px; margin-bottom: 8px; color: #aaa;" }, "Резервное копирование (E2EE)"),
    backupSectionBtn,
    backupPassWrapper,
    el("div", { style: "display:flex;margin-top:8px;" }, doBackupBtn, doRestoreBtn, cancelBackupBtn)
  );

  const pairingBtn = el("button", { class: "btn-secondary", style: "width:100%;margin-top:8px;cursor:pointer;" }, "Подключить устройство");
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
      modal.appendChild(el("div", { style: "width:min(360px,100%);background:#202024;border-radius:16px;padding:24px;text-align:center;box-shadow:0 12px 40px rgba(0,0,0,.45);" },
        el("h3", { style: "margin:0 0 10px;color:#fff;" }, "Подключение устройства"),
        el("p", { style: "margin:0 0 16px;color:#aaa;font-size:13px;line-height:1.4;" }, "Отсканируйте этот QR-код телефоном. Код действует 5 минут."),
        canvas,
        el("p", { style: "margin:14px 0;color:#777;font-size:11px;word-break:break-all;" }, `Сессия: ${session.session_id}`),
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
             } catch (_) { /* websocket remains the primary fast path */ }
           }, 1000);
         });
        if (state.public_key) {
          const secret = await deriveSharedSecret(kp.privateKey, decodeB64Url(state.public_key));
          const messages = await getAllMessages();
          const groups = await getAllGroups();
          const groupMembers = await getAllGroupMembers();
          const rawGroupKeys = await getAllGroupKeys();
          const groupKeys = rawGroupKeys.map(k => ({
            ...k,
            key: encodeB64Url(k.key)
          }));
          const groupMessages = await getAllGroupMessages();

          const blob = await encryptPairingHistory({
            messages,
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
    } catch (err) { showToast(err.message || "Не удалось создать сессию", "error"); }
    finally { pairingBtn.disabled = false; }
  });
  const pairingSection = el("div", { style: "margin-top:16px;border-top:1px solid rgba(255,255,255,.1);padding-top:16px;width:100%;" }, el("h3", { style: "font-size:14px;color:#aaa;" }, "Устройства"), pairingBtn);

  const infoSection = el("div", { class: "profile-info" },
    el("div", { class: "profile-name-row" }, nameDisplay, nameInput),
    usernameEl,
    el("div", { class: "profile-edit-row" }, editBtn, saveBtn, cancelBtn),
    userIdEl
  );

  const card = el("div", { class: "profile-card", style: "display: flex; flex-direction: column; align-items: center;" },
    avatarEl,
    infoSection,
    pwSection,
    backupSection
    , pairingSection
  );

  const wrap = el("div", { class: "profile-wrap" },
    header,
    card,
    el("div", { class: "profile-actions" }, logoutBtn)
  );

  container.appendChild(wrap);
}
