import { apiPut, uploadAvatar, getApiOrigin } from "../api.js";
import { navigate, getCurrentUser, setCurrentUser, logout, setDevicesBackTarget } from "../app.js";
import { avatar, el, showToast, spinner, showConfirmModal } from "./components.js";

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

  const userId = user.user_id || user.id || "";
  const origin = getApiOrigin();

  const avatarContainer = el("div", {
    style: "position:relative;cursor:pointer;display:inline-block;border-radius:50%;overflow:hidden;margin-bottom:16px;box-shadow:0 8px 24px rgba(0,0,0,0.3);",
    title: "Нажмите, чтобы изменить аватар"
  });

  const currentAvatarUser = { ...user };
  if (!currentAvatarUser.avatar_url && userId) {
    currentAvatarUser.avatar_url = `${origin}/api/v1/avatar/${userId}?t=${Date.now()}`;
  }

  const avatarEl = avatar(currentAvatarUser, 110);
  avatarEl.classList.add("profile-avatar");

  const avatarOverlay = el("div", {
    style: "position:absolute;inset:0;background:rgba(0,0,0,0.5);display:flex;flex-direction:column;align-items:center;justify-content:center;color:#fff;font-size:22px;opacity:0;transition:opacity 0.2s ease;"
  }, el("span", {}, "📷"), el("span", { style: "font-size:11px;margin-top:2px;" }, "Изменить"));

  avatarContainer.addEventListener("mouseenter", () => avatarOverlay.style.opacity = "1");
  avatarContainer.addEventListener("mouseleave", () => avatarOverlay.style.opacity = "0");

  const fileInput = el("input", {
    type: "file",
    accept: "image/png, image/jpeg, image/webp, image/gif",
    style: "display:none;"
  });

  avatarContainer.appendChild(avatarEl);
  avatarContainer.appendChild(avatarOverlay);
  avatarContainer.appendChild(fileInput);

  avatarContainer.addEventListener("click", () => fileInput.click());

  fileInput.addEventListener("change", async () => {
    const file = fileInput.files?.[0];
    if (!file) return;

    if (file.size > 5 * 1024 * 1024) {
      showToast("Размер файла не должен превышать 5МБ", "error");
      return;
    }

    avatarOverlay.style.opacity = "1";
    avatarOverlay.innerHTML = "";
    avatarOverlay.appendChild(spinner());

    try {
      await uploadAvatar(file);
      const newAvatarUrl = `${origin}/api/v1/avatar/${userId}?t=${Date.now()}`;
      const updatedUser = { ...user, avatar_url: newAvatarUrl };
      setCurrentUser(updatedUser);

      showToast("Аватар успешно обновлён!", "success");
      renderProfile(container);
    } catch (err) {
      showToast(err.message || "Ошибка загрузки аватара", "error");
      avatarOverlay.style.opacity = "0";
      avatarOverlay.textContent = "📷";
    }
  });

  const nameDisplay = el("span", { class: "profile-name", id: "profile-name-display", style: "font-size:20px;font-weight:700;" }, user.name || "Без имени");
  const nameInput = el("input", {
    type: "text",
    class: "profile-name-input hidden",
    value: user.name || "",
    id: "profile-name-input",
    maxlength: "64",
    style: "padding:8px 12px;border-radius:var(--r);background:var(--bg);border:1px solid var(--border);color:var(--text);font-size:16px;text-align:center;width:100%;max-width:260px;"
  });
  const editBtn = el("button", { class: "btn-secondary profile-edit-btn", id: "profile-edit-btn", style: "font-size:13px;padding:6px 14px;" }, "Изменить имя");
  const saveBtn = el("button", { class: "btn-primary profile-save-btn hidden", id: "profile-save-btn", style: "font-size:13px;padding:6px 14px;" }, "Сохранить");
  const cancelBtn = el("button", { class: "btn-ghost profile-cancel-btn hidden", id: "profile-cancel-btn", style: "font-size:13px;padding:6px 14px;" }, "Отмена");

  const username = user.username || user.nickname || "";
  const usernameEl = el("span", { class: "profile-username", style: "font-size:14px;color:var(--text-muted);margin-top:4px;" }, `@${username}`);
  const userIdEl = el("span", { class: "profile-uid", style: "font-size:12px;color:var(--text-muted);opacity:0.75;margin-top:4px;" }, `ID аккаунта: ${userId}`);

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
      const res = await apiPut("/users/me/name", { name: newName });
      const updatedUser = { ...user, name: res?.name || newName };
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

  const infoSection = el("div", { class: "profile-info", style: "display:flex;flex-direction:column;align-items:center;margin-bottom:16px;width:100%;" },
    el("div", { class: "profile-name-row", style: "display:flex;align-items:center;justify-content:center;width:100%;margin-bottom:4px;" }, nameDisplay, nameInput),
    usernameEl,
    userIdEl,
    el("div", { class: "profile-edit-row", style: "display:flex;gap:8px;margin-top:12px;" }, editBtn, saveBtn, cancelBtn)
  );

  // Quick navigation menu items inside profile
  const settingsLinkBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 16px;cursor:pointer;font-size:14px;margin-top:8px;border-radius:var(--r);"
  },
    el("span", { style: "display:flex;align-items:center;gap:10px;color:var(--text);" },
      el("span", {}, "⚙️"),
      el("span", {}, "Настройки и безопасность")
    ),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  settingsLinkBtn.addEventListener("click", () => navigate("#settings"));

  const devicesLinkBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 16px;cursor:pointer;font-size:14px;margin-top:8px;border-radius:var(--r);"
  },
    el("span", { style: "display:flex;align-items:center;gap:10px;color:var(--text);" },
      el("span", {}, "📱"),
      el("span", {}, "Подключенные устройства")
    ),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  devicesLinkBtn.addEventListener("click", () => {
    setDevicesBackTarget("#profile");
    navigate("#devices");
  });

  const navSection = el("div", { style: "width:100%;margin-top:8px;border-top:1px solid var(--border);padding-top:12px;" },
    settingsLinkBtn,
    devicesLinkBtn
  );

  const logoutBtn = el("button", {
    class: "btn-danger profile-logout-btn",
    style: "width:100%;padding:12px 16px;cursor:pointer;margin-top:20px;font-size:14px;border-radius:var(--r);"
  }, "Выйти");
  logoutBtn.addEventListener("click", async () => {
    const confirmed = await showConfirmModal(
      "Выйти из аккаунта?",
      "Вы действительно хотите выйти из текущего аккаунта?",
      "Выйти",
      "Отмена",
      true
    );
    if (!confirmed) return;
    logout();
    showToast("Вы вышли из системы", "info");
  });

  const card = el("div", {
    class: "profile-card",
    style: "display:flex;flex-direction:column;align-items:center;background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:32px 24px;max-width:860px;margin:0 auto;width:100%;box-sizing:border-box;"
  },
    avatarContainer,
    infoSection,
    navSection,
    logoutBtn
  );

  const scrollWrapper = el("div", { style: "flex:1;overflow-y:auto;overflow-x:hidden;padding:20px;box-sizing:border-box;" }, card);

  const wrap = el("div", { class: "profile-wrap", style: "display:flex;flex-direction:column;height:100%;overflow:hidden;width:100%;" },
    header,
    scrollWrapper
  );

  container.appendChild(wrap);
}
