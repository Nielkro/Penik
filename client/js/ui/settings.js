import { el, showToast, spinner, formatFullTime } from "./components.js";
import { getTheme, setTheme } from "../theme.js";
import { listDevices } from "../api.js";
import { navigate } from "../app.js";

// renderSettings renders the settings screen: a theme switch and a link to the
// separate devices screen.
export function renderSettings(container) {
  const header = el("div", { class: "search-header" },
    el("h2", { class: "search-title" }, "Настройки")
  );

  // --- Theme toggle ---
  const themeLabel = el("span", { style: "font-size:15px;color:var(--text);" }, "Тёмная тема");
  const themeToggle = el("button", {
    class: "btn-secondary",
    style: "min-width:90px;cursor:pointer;"
  }, getTheme() === "light" ? "Светлая" : "Тёмная");

  function syncThemeLabel() {
    themeToggle.textContent = getTheme() === "light" ? "Светлая" : "Тёмная";
  }
  themeToggle.addEventListener("click", () => {
    setTheme(getTheme() === "light" ? "dark" : "light");
    syncThemeLabel();
  });

  const themeRow = el("div", {
    style: "display:flex;align-items:center;justify-content:space-between;padding:14px 16px;background:var(--panel);border:1px solid var(--border);border-radius:var(--r);"
  }, themeLabel, themeToggle);

  // --- Devices link ---
  const devicesBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 16px;cursor:pointer;"
  },
    el("span", { style: "font-size:15px;color:var(--text);" }, "Мои устройства"),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  devicesBtn.addEventListener("click", () => navigate("#devices"));

  const wrap = el("div", { class: "search-wrap", style: "display:flex;flex-direction:column;gap:12px;padding:16px;" },
    header,
    themeRow,
    devicesBtn
  );
  container.appendChild(wrap);
}

// renderDevices renders the dedicated devices screen listing the user's devices.
export function renderDevices(container) {
  const backBtn = el("button", { class: "btn-ghost", style: "cursor:pointer;" }, "‹ Назад");
  backBtn.addEventListener("click", () => navigate("#settings"));

  const header = el("div", { class: "search-header", style: "display:flex;align-items:center;gap:8px;" },
    backBtn,
    el("h2", { class: "search-title" }, "Мои устройства")
  );

  const list = el("div", { style: "display:flex;flex-direction:column;" }, spinner());

  function render(devices) {
    list.innerHTML = "";
    if (!devices || devices.length === 0) {
      list.appendChild(el("div", { style: "color:var(--text-muted);font-size:13px;padding:8px;" }, "Нет устройств"));
      return;
    }
    for (const d of devices) {
      const title = el("div", { style: "font-size:15px;color:var(--text);font-weight:500;" },
        d.device_name || "Устройство",
        d.is_current ? el("span", { style: "margin-left:8px;font-size:11px;color:var(--success);" }, "· это устройство") : ""
      );
      const meta = el("div", { style: "font-size:12px;color:var(--text-muted);margin-top:2px;" },
        `Активно: ${formatFullTime(d.last_seen * 1000)}`,
        d.has_session
          ? el("span", { style: "margin-left:8px;color:var(--success);" }, "в сети")
          : el("span", { style: "margin-left:8px;color:var(--text-muted);" }, "нет активной сессии")
      );
      list.appendChild(el("div", {
        style: "padding:12px 16px;background:var(--panel);border:1px solid var(--border);border-radius:var(--r);margin-bottom:8px;"
      }, title, meta));
    }
  }

  listDevices()
    .then(render)
    .catch(() => {
      list.innerHTML = "";
      list.appendChild(el("div", { style: "color:var(--danger);font-size:13px;padding:8px;" }, "Не удалось загрузить устройства"));
    });

  const wrap = el("div", { class: "search-wrap", style: "padding:16px;" }, header, list);
  container.appendChild(wrap);
}
