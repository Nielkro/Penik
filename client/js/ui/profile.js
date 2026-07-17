import { apiPatch, apiPost } from "../api.js";
import { navigate, getCurrentUser, setCurrentUser, logout, backupE2EEKeys, restoreE2EEKeys } from "../app.js";
import { avatar, el, showToast, spinner } from "./components.js";

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
    class: "profile-input hidden",
    style: "width:100%;margin-bottom:8px;padding:8px;border-radius:6px;background:rgba(255,255,255,0.05);border:1px solid rgba(255,255,255,0.1);color:#fff;"
  });

  const doBackupBtn = el("button", { class: "btn-primary hidden", style: "margin-right:8px;padding:8px 12px;font-size:12px;cursor:pointer;" }, "Создать копию");
  const doRestoreBtn = el("button", { class: "btn-secondary hidden", style: "margin-right:8px;padding:8px 12px;font-size:12px;cursor:pointer;" }, "Восстановить");
  const cancelBackupBtn = el("button", { class: "btn-ghost hidden", style: "padding:8px 12px;font-size:12px;cursor:pointer;" }, "Отмена");

  backupSectionBtn.addEventListener("click", () => {
    backupPassInput.classList.remove("hidden");
    doBackupBtn.classList.remove("hidden");
    doRestoreBtn.classList.remove("hidden");
    cancelBackupBtn.classList.remove("hidden");
    backupSectionBtn.classList.add("hidden");
    backupPassInput.focus();
  });

  cancelBackupBtn.addEventListener("click", () => {
    backupPassInput.value = "";
    backupPassInput.classList.add("hidden");
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
    backupPassInput,
    el("div", { style: "display:flex;margin-top:8px;" }, doBackupBtn, doRestoreBtn, cancelBackupBtn)
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
    pwSection,
    backupSection
  );

  const wrap = el("div", { class: "profile-wrap" },
    header,
    card,
    el("div", { class: "profile-actions" }, logoutBtn)
  );

  container.appendChild(wrap);
}
