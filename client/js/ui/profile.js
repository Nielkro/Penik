import { apiPatch, apiPost, apiGet } from "../api.js";
import { navigate, getCurrentUser, setCurrentUser, logout } from "../app.js";
import { avatar, el, showToast, spinner, showPinModal } from "./components.js";
import { exportHistoryData, importHistoryData } from "../storage.js";
import {
  encryptIdentityEnvelope,
  decryptIdentityEnvelope,
  rewrapEnvelope,
  encodeKey,
  decodeKey
} from "../crypto.js";

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

  // Backup buttons
  const backupBtn = el("button", { class: "btn-secondary profile-backup-btn" }, "Экспортировать историю (.json)");
  backupBtn.addEventListener("click", async () => {
    try {
      const data = await exportHistoryData();
      if (!data) {
        showToast("Не удалось экспортировать историю", "error");
        return;
      }

      const password = await showPinModal("Придумайте пароль/PIN для шифрования файла истории (не менее 6 символов):", "Пароль/PIN-код");
      if (!password) return;
      if (password.length < 6) {
        showToast("Пароль должен быть не менее 6 символов", "error");
        return;
      }

      const env = await encryptIdentityEnvelope(data, password);
      const backupData = {
        version: 2,
        encrypted_dek_b64: encodeKey(env.encrypted_dek),
        iv_kek_b64: encodeKey(env.iv_kek),
        salt_kek_b64: encodeKey(env.salt_kek),
        encrypted_keys_b64: encodeKey(env.encrypted_keys),
        iv_dek_b64: encodeKey(env.iv_dek)
      };

      const blob = new Blob([JSON.stringify(backupData, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `penik_history_backup_${user.username || user.nickname || "user"}.json`;
      a.click();
      URL.revokeObjectURL(url);
      showToast("Зашифрованная резервная копия истории скачана!", "success");
    } catch (err) {
      showToast("Ошибка создания бэкапа истории: " + err.message, "error");
    }
  });

  const restoreInput = el("input", { type: "file", accept: ".json", style: "display:none;" });
  restoreInput.addEventListener("change", async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      const reader = new FileReader();
      reader.onload = async (event) => {
        try {
          const data = JSON.parse(event.target.result);
          if (!data.version || (!data.ciphertext_b64 && !data.encrypted_keys_b64)) {
            throw new Error("Неверный формат или файл не зашифрован");
          }

          const password = await showPinModal("Введите пароль/PIN для расшифрования файла истории:", "Пароль/PIN-код");
          if (!password) return;

          let decrypted;
          if (data.version === 2) {
            const envelope = {
              encrypted_dek: decodeKey(data.encrypted_dek_b64),
              iv_kek: decodeKey(data.iv_kek_b64),
              salt_kek: decodeKey(data.salt_kek_b64),
              encrypted_keys: decodeKey(data.encrypted_keys_b64),
              iv_dek: decodeKey(data.iv_dek_b64)
            };
            decrypted = await decryptIdentityEnvelope(envelope, password);
          } else {
            throw new Error("Неподдерживаемая версия файла");
          }

          await importHistoryData(decrypted);
          showToast("История успешно импортирована!", "success");
          // Refresh profile screen to show correct user id / username
          renderProfile(container);
        } catch (err) {
          showToast("Неверный формат резервной копии истории: " + err.message, "error");
        }
      };
      reader.readAsText(file);
    } catch (err) {
      showToast(err.message || "Ошибка импорта истории", "error");
    }
  });

  const restoreBtn = el("button", { class: "btn-secondary profile-restore-btn" }, "Импортировать историю");
  restoreBtn.addEventListener("click", () => {
    restoreInput.click();
  });

  const backupSection = el("div", { class: "profile-backup-section", style: "margin-top: 16px; border-top: 1px solid rgba(255,255,255,0.1); padding-top: 16px; width: 100%;" },
    el("h3", { style: "font-size: 14px; margin-bottom: 8px; color: #aaa;" }, "Резервное копирование переписки (E2EE)"),
    el("p", { style: "font-size: 12px; margin-bottom: 12px; color: #888; line-height: 1.4;" }, "Экспортируйте историю переписки в зашифрованный файл. Обратите внимание, что криптографические ключи устройств не переносятся ради безопасности."),
    el("div", { style: "display: flex; gap: 8px; flex-wrap: wrap;" }, backupBtn, restoreBtn, restoreInput)
  );

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
      // 1. Change password on the server first
      await apiPatch("/users/me/password", { old_password: oldPassword, new_password: newPassword });

      // 2. Try to fetch and re-wrap the E2EE cloud backup if it is encrypted with password
      try {
        const backup = await apiGet("/keys/backup").catch(() => null);
        if (backup && backup.encrypted_blob) {
          const combinedBlob = decodeKey(backup.encrypted_blob);
          const jsonStr = new TextDecoder().decode(combinedBlob);
          const backupData = JSON.parse(jsonStr);

          if (backupData.version === 2 && backupData.method === "password") {
            const envelope = {
              encrypted_dek: decodeKey(backupData.encrypted_dek_b64),
              iv_kek: decodeKey(backupData.iv_kek_b64),
              salt_kek: decodeKey(backupData.salt_kek_b64),
              encrypted_keys: decodeKey(backupData.encrypted_keys_b64),
              iv_dek: decodeKey(backupData.iv_dek_b64)
            };

            // Rekey/rewrap DEK envelope with the new KEK derived from newPassword
            const updatedEnv = await rewrapEnvelope(envelope, oldPassword, newPassword);

            const updatedWrapper = {
              version: 2,
              method: "password",
              encrypted_dek_b64: encodeKey(updatedEnv.encrypted_dek),
              iv_kek_b64: encodeKey(updatedEnv.iv_kek),
              salt_kek_b64: encodeKey(updatedEnv.salt_kek),
              encrypted_keys_b64: encodeKey(updatedEnv.encrypted_keys),
              iv_dek_b64: encodeKey(updatedEnv.iv_dek)
            };

            const newJsonStr = JSON.stringify(updatedWrapper);
            const encoder = new TextEncoder();
            const combinedBlobNew = encoder.encode(newJsonStr);

            // Upload the re-wrapped backup back to the server
            await apiPost("/keys/backup", {
              encrypted_blob: encodeKey(combinedBlobNew),
              kdf_salt: encodeKey(updatedEnv.salt_kek)
            });
          }
        }
      } catch (backupErr) {
        console.warn("Не удалось обновить бэкап при смене пароля:", backupErr);
        showToast("Предупреждение: Пароль изменён на сервере, но облачный бэкап E2EE не обновлён. Для восстановления на новом устройстве используйте старый пароль.", "warning");
      }

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

  const infoSection = el("div", { class: "profile-info" },
    el("div", { class: "profile-name-row" }, nameDisplay, nameInput),
    usernameEl,
    el("div", { class: "profile-edit-row" }, editBtn, saveBtn, cancelBtn),
    userIdEl
  );

  const card = el("div", { class: "profile-card", style: "display: flex; flex-direction: column; align-items: center;" },
    avatarEl,
    infoSection,
    backupSection,
    pwSection
  );

  const wrap = el("div", { class: "profile-wrap" },
    header,
    card,
    el("div", { class: "profile-actions" }, logoutBtn)
  );

  container.appendChild(wrap);
}
