import { getMyStickers, getStickerPack, installStickerPack, uninstallStickerPack, importTelegramStickerPack } from "../api.js";
import { el, showToast, spinner, svgIcon } from "./components.js";

const RECENT_KEY = "penik_recent_stickers";

export function getRecentStickers() {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export function addRecentSticker(sticker) {
  try {
    let recents = getRecentStickers();
    recents = recents.filter(s => !(s.pack_id === sticker.pack_id && s.id === sticker.id));
    recents.unshift(sticker);
    if (recents.length > 32) recents = recents.slice(0, 32);
    localStorage.setItem(RECENT_KEY, JSON.stringify(recents));
  } catch {}
}

const ICON_PLUS = "M12 5v14M5 12h14";
const ICON_CLOCK = "M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z";
const ICON_CLOSE = "M18 6L6 18M6 6l12 12";

/**
 * Creates the sticker picker popover component.
 * @param {(sticker: object) => void} onSelect
 */
export function createStickerPicker(onSelect) {
  let activeTab = "recent";
  let installedPacks = [];
  let currentPackDetails = null;
  let isOpen = false;

  const container = el("div", { class: "sticker-picker-container hidden" });

  const header = el("div", { class: "sticker-picker-header" });
  const title = el("span", { class: "sticker-picker-title" }, "Стикеры");
  const addBtn = el("button", {
    class: "btn-add-pack",
    title: "Импортировать стикерпак из Telegram",
    style: "display:inline-flex;align-items:center;gap:4px;padding:4px 10px;background:var(--primary-alpha, rgba(91,110,245,0.15));color:var(--primary, #5b6ef5);border:none;border-radius:8px;font-size:12px;font-weight:600;cursor:pointer;"
  }, svgIcon(ICON_PLUS, 14, "var(--primary, #5b6ef5)"), "Импорт");
  header.appendChild(title);
  header.appendChild(addBtn);

  const contentArea = el("div", { class: "sticker-picker-content" });
  const tabsBar = el("div", { class: "sticker-picker-tabs" });

  container.appendChild(header);
  container.appendChild(contentArea);
  container.appendChild(tabsBar);

  addBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    showImportStickersModal(() => {
      loadPacks();
    });
  });

  async function loadPacks() {
    try {
      installedPacks = await getMyStickers() || [];
    } catch {
      installedPacks = [];
    }
    renderTabs();
    renderContent();
  }

  function renderTabs() {
    tabsBar.innerHTML = "";

    // Recent tab
    const recentTab = el("button", {
      class: `sticker-tab ${activeTab === "recent" ? "active" : ""}`,
      title: "Недавние"
    }, [svgIcon(ICON_CLOCK, 18)]);
    recentTab.addEventListener("click", () => {
      activeTab = "recent";
      renderTabs();
      renderContent();
    });
    tabsBar.appendChild(recentTab);

    // Installed packs tabs
    for (const pack of installedPacks) {
      const isAct = activeTab === pack.id;
      const tabBtn = el("button", {
        class: `sticker-tab ${isAct ? "active" : ""}`,
        title: pack.title
      });

      const coverUrl = pack.cover_sticker_id
        ? `/api/v1/stickers/file/${pack.id}/${pack.cover_sticker_id}.${pack.is_video ? 'webm' : (pack.is_animated ? 'tgs' : 'webp')}`
        : "";

      if (coverUrl && !pack.is_video && !pack.is_animated) {
        const img = el("img", { src: coverUrl, class: "sticker-tab-icon", loading: "lazy" });
        tabBtn.appendChild(img);
      } else {
        tabBtn.textContent = pack.title.slice(0, 2);
      }

      tabBtn.addEventListener("click", () => {
        activeTab = pack.id;
        renderTabs();
        renderContent();
      });
      tabsBar.appendChild(tabBtn);
    }

    // Add pack button tab at the end of tabs bar
    const addTab = el("button", {
      class: "sticker-tab sticker-tab-add",
      title: "Импортировать стикерпак",
      style: "display:flex;align-items:center;justify-content:center;color:var(--primary, #5b6ef5);"
    }, [svgIcon(ICON_PLUS, 18, "var(--primary, #5b6ef5)")]);
    addTab.addEventListener("click", (e) => {
      e.stopPropagation();
      showImportStickersModal(() => {
        loadPacks();
      });
    });
    tabsBar.appendChild(addTab);
  }

  async function renderContent() {
    contentArea.innerHTML = "";

    if (activeTab === "recent") {
      const recents = getRecentStickers();
      if (recents.length === 0) {
        const emptyWrap = el("div", { class: "sticker-picker-empty", style: "display:flex;flex-direction:column;align-items:center;gap:12px;margin:auto;text-align:center;" });
        emptyWrap.appendChild(el("p", { style: "margin:0;color:var(--text-muted);font-size:13px;" }, "Здесь будут ваши недавние стикеры"));
        const importBtn = el("button", {
          class: "btn btn-primary",
          style: "padding:8px 16px;font-size:13px;border-radius:10px;display:inline-flex;align-items:center;gap:6px;"
        }, svgIcon(ICON_PLUS, 16, "#fff"), "Импорт из Telegram");
        importBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          showImportStickersModal(() => loadPacks());
        });
        emptyWrap.appendChild(importBtn);
        contentArea.appendChild(emptyWrap);
        return;
      }
      const grid = el("div", { class: "stickers-grid" });
      for (const s of recents) {
        grid.appendChild(createStickerItem(s));
      }
      contentArea.appendChild(grid);
      return;
    }

    contentArea.appendChild(spinner());
    try {
      const pack = await getStickerPack(activeTab);
      currentPackDetails = pack;
      contentArea.innerHTML = "";

      const packHeader = el("div", { class: "sticker-pack-preview-header" });
      packHeader.appendChild(el("span", { class: "pack-name" }, pack.title));
      contentArea.appendChild(packHeader);

      if (!pack.stickers || pack.stickers.length === 0) {
        contentArea.appendChild(el("div", { class: "sticker-picker-empty" }, "В этом паке нет стикеров"));
        return;
      }

      const grid = el("div", { class: "stickers-grid" });
      for (const s of pack.stickers) {
        grid.appendChild(createStickerItem(s, pack));
      }
      contentArea.appendChild(grid);
    } catch (err) {
      contentArea.innerHTML = "";
      contentArea.appendChild(el("div", { class: "sticker-picker-empty" }, "Не удалось загрузить стикеры"));
    }
  }

  function createStickerItem(s, pack) {
    const isVideo = pack?.is_video || s.file_name?.endsWith('.webm');
    const isTgs = pack?.is_animated || s.file_name?.endsWith('.tgs');
    const url = s.url || `/api/v1/stickers/file/${s.pack_id}/${s.file_name || (s.id + (isVideo ? '.webm' : (isTgs ? '.tgs' : '.webp')))}`;

    const item = el("button", { class: "sticker-grid-item", title: s.emoji || "" });

    if (isVideo) {
      const vid = el("video", {
        src: url,
        autoplay: true,
        loop: true,
        muted: true,
        playsinline: true,
        class: "sticker-img"
      });
      item.appendChild(vid);
    } else {
      const img = el("img", {
        src: url,
        class: "sticker-img",
        loading: "lazy",
        alt: s.emoji || "стикер"
      });
      item.appendChild(img);
    }

    item.addEventListener("click", () => {
      const stickerPayload = {
        type: "sticker",
        pack_id: s.pack_id,
        id: s.id,
        emoji: s.emoji || "",
        file_name: s.file_name || "",
        url: url,
        is_video: isVideo,
        is_animated: isTgs,
        width: s.width || 512,
        height: s.height || 512
      };
      addRecentSticker(stickerPayload);
      onSelect(stickerPayload);
      toggle(false);
    });

    return item;
  }

  function toggle(force) {
    isOpen = force !== undefined ? force : !isOpen;
    if (isOpen) {
      container.classList.remove("hidden");
      loadPacks();
    } else {
      container.classList.add("hidden");
    }
    return isOpen;
  }

  return {
    element: container,
    toggle,
    isOpen: () => isOpen,
    reload: loadPacks
  };
}

/**
 * Opens a modal showing all stickers in a pack with an Install/Uninstall action.
 */
export async function showStickerPackModal(packId, onUpdate) {
  const overlay = el("div", { class: "modal-overlay active" });
  const modal = el("div", { class: "modal-content sticker-pack-modal" });
  modal.appendChild(spinner());
  overlay.appendChild(modal);
  document.body.appendChild(overlay);

  const close = () => {
    overlay.remove();
  };

  overlay.addEventListener("click", (e) => {
    if (e.target === overlay) close();
  });

  try {
    const pack = await getStickerPack(packId);
    let myPacks = [];
    try { myPacks = await getMyStickers(); } catch {}
    const isInstalled = myPacks.some(p => p.id === pack.id);

    modal.innerHTML = "";

    const header = el("div", { class: "modal-header" });
    header.appendChild(el("h3", {}, pack.title));
    const closeBtn = el("button", { class: "btn-icon modal-close-btn" }, [svgIcon(ICON_CLOSE, 18)]);
    closeBtn.addEventListener("click", close);
    header.appendChild(closeBtn);
    modal.appendChild(header);

    const body = el("div", { class: "modal-body sticker-pack-modal-body" });
    const grid = el("div", { class: "stickers-grid" });

    for (const s of (pack.stickers || [])) {
      const isVideo = pack.is_video || s.file_name?.endsWith('.webm');
      const url = s.url || `/api/v1/stickers/file/${pack.id}/${s.file_name}`;
      const item = el("div", { class: "sticker-grid-item preview-only" });

      if (isVideo) {
        item.appendChild(el("video", { src: url, autoplay: true, loop: true, muted: true, playsinline: true, class: "sticker-img" }));
      } else {
        item.appendChild(el("img", { src: url, class: "sticker-img", loading: "lazy" }));
      }
      grid.appendChild(item);
    }

    body.appendChild(grid);
    modal.appendChild(body);

    const footer = el("div", { class: "modal-footer" });
    const actionBtn = el("button", {
      class: `btn ${isInstalled ? "btn-secondary" : "btn-primary"}`
    }, isInstalled ? "Удалить стикерпак" : "Добавить стикерпак");

    actionBtn.addEventListener("click", async () => {
      actionBtn.disabled = true;
      try {
        if (isInstalled) {
          await uninstallStickerPack(pack.id);
          showToast("Стикерпак удален", "info");
        } else {
          await installStickerPack(pack.id);
          showToast("Стикерпак добавлен", "success");
        }
        if (typeof onUpdate === "function") onUpdate();
        close();
      } catch (err) {
        showToast(err.message || "Ошибка управления паком", "error");
        actionBtn.disabled = false;
      }
    });

    footer.appendChild(actionBtn);
    modal.appendChild(footer);
  } catch (err) {
    modal.innerHTML = `
      <div class="modal-header"><h3>Ошибка</h3></div>
      <div class="modal-body"><p>Не удалось загрузить информацию о стикерпаке.</p></div>
      <div class="modal-footer"><button class="btn btn-secondary" id="err-close-btn">Закрыть</button></div>
    `;
    modal.querySelector("#err-close-btn")?.addEventListener("click", close);
  }
}

/**
 * Modal to import a sticker pack from Telegram by link or name.
 */
export function showImportStickersModal(onSuccess) {
  const overlay = el("div", { class: "modal-overlay active" });
  const modal = el("div", { class: "modal-content import-stickers-modal" });

  modal.innerHTML = `
    <div class="modal-header">
      <h3>Импорт стикеров из Telegram</h3>
      <button class="btn-icon modal-close-btn">${svgIcon(ICON_CLOSE, 18).outerHTML}</button>
    </div>
    <div class="modal-body">
      <p class="text-secondary" style="margin-bottom: 16px;">
        Вставьте ссылку на стикерпак вида <code>t.me/addstickers/pack_name</code> или просто название пака.
      </p>
      <div class="form-group">
        <label for="tg-sticker-url">Ссылка или название</label>
        <input type="text" id="tg-sticker-url" class="input" placeholder="https://t.me/addstickers/Doge" autofocus />
      </div>
    </div>
    <div class="modal-footer">
      <button class="btn btn-secondary btn-cancel">Отмена</button>
      <button class="btn btn-primary btn-import">Импортировать</button>
    </div>
  `;

  overlay.appendChild(modal);
  document.body.appendChild(overlay);

  const close = () => overlay.remove();
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
  modal.querySelector(".modal-close-btn")?.addEventListener("click", close);
  modal.querySelector(".btn-cancel")?.addEventListener("click", close);

  const input = modal.querySelector("#tg-sticker-url");
  const importBtn = modal.querySelector(".btn-import");

  const doImport = async () => {
    const val = input.value.trim();
    if (!val) {
      showToast("Введите ссылку на стикерпак", "error");
      return;
    }

    importBtn.disabled = true;
    importBtn.textContent = "Импортируем...";

    try {
      const pack = await importTelegramStickerPack(val);
      showToast(`Стикерпак "${pack.title}" успешно импортирован!`, "success");
      close();
      if (typeof onSuccess === "function") onSuccess(pack);
    } catch (err) {
      showToast(err.message || "Ошибка импорта стикерпака", "error");
      importBtn.disabled = false;
      importBtn.textContent = "Импортировать";
    }
  };

  importBtn.addEventListener("click", doImport);
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") doImport();
  });
}
