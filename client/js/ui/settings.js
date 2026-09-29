import { el, showToast, spinner, formatFullTime, avatar, showConfirmModal, formatDate, formatTime } from "./components.js";
import { getTheme, setTheme } from "../theme.js";
import {
  listDevices,
  apiPatch,
  apiPost,
  apiGet,
  getApiOrigin
} from "../api.js";
import {
  getCurrentUser,
  navigate,
  logout,
  getDevicesBackTarget,
  setDevicesBackTarget
} from "../app.js";

const createSection = (titleText, ...children) => {
  return el("div", { style: "display:flex;flex-direction:column;gap:8px;margin-top:16px;" },
    el("span", { style: "font-size:12px;font-weight:600;text-transform:uppercase;color:var(--text-muted);letter-spacing:0.5px;padding-left:4px;" }, titleText),
    ...children
  );
};

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
    const origin = getApiOrigin();
    currentAvatarUser.avatar_url = `${origin}/api/v1/avatar/${userId}?t=${Date.now()}`;
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
    const confirmed = await showConfirmModal(
      "Отозвать все сессии?",
      "Вы действительно хотите завершить все активные сессии на остальных устройствах? На текущем устройстве вы останетесь в аккаунте.",
      "Отозвать",
      "Отмена",
      true
    );
    if (!confirmed) return;

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

  // --- 3. Devices Section ---
  const devicesBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:12px 16px;cursor:pointer;font-size:14px;"
  },
    el("span", { style: "color:var(--text);" }, "Мои устройства"),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  devicesBtn.addEventListener("click", () => {
    setDevicesBackTarget("#settings");
    navigate("#devices");
  });

  const devicesBox = el("div", {
    style: "background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:4px;display:flex;flex-direction:column;gap:4px;"
  },
    devicesBtn
  );

  const devicesSection = createSection("Устройства", devicesBox);

  // --- 4. Logout Section ---
  const logoutBtn = el("button", {
    class: "btn-danger",
    style: "width:100%;padding:14px 18px;cursor:pointer;margin-top:20px;font-size:15px;border-radius:var(--r);font-weight:500;"
  }, "Выйти из аккаунта");
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

  const content = el("div", { style: "display:flex;flex-direction:column;gap:10px;padding:20px;max-width:860px;margin:0 auto;width:100%;box-sizing:border-box;" },
    profileCard,
    appearanceSection,
    securitySection,
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
  backBtn.addEventListener("click", () => navigate(getDevicesBackTarget()));

  const header = el("div", { class: "profile-header", style: "display:flex;align-items:center;gap:12px;padding:14px 16px;background:var(--panel);border-bottom:1px solid var(--border);" },
    backBtn,
    el("h2", { class: "profile-title", style: "margin:0;font-size:18px;font-weight:700;" }, "Мои устройства")
  );

  // Devices list
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
        d.is_online
          ? el("span", { style: "margin-left:8px;color:var(--success);" }, "в сети")
          : (d.has_session
              ? el("span", { style: "margin-left:8px;color:var(--text-muted);" }, "не в сети")
              : el("span", { style: "margin-left:8px;color:var(--text-muted);" }, "нет активной сессии"))
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

  const devicesListSection = createSection("Подключенные устройства", list);

  const content = el("div", { style: "display:flex;flex-direction:column;gap:12px;padding:20px;max-width:860px;margin:0 auto;width:100%;box-sizing:border-box;" },
    devicesListSection
  );
  const scrollWrapper = el("div", { style: "flex:1;overflow-y:auto;overflow-x:hidden;" }, content);

  const wrap = el("div", { class: "settings-wrap", style: "display:flex;flex-direction:column;height:100%;overflow:hidden;width:100%;" },
    header,
    scrollWrapper
  );
  container.appendChild(wrap);
}



