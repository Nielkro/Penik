import { getMyStickers, getStickerPack, installStickerPack, uninstallStickerPack, importTelegramStickerPack, getFullApiUrl } from "../api.js";
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

function createStickerMediaElement(url, isVideo, emoji = "", className = "sticker-img") {
  if (isVideo) {
    const vid = document.createElement("video");
    vid.src = url;
    vid.autoplay = true;
    vid.loop = true;
    vid.muted = true;
    vid.defaultMuted = true;
    vid.playsInline = true;
    vid.controls = false;
    vid.disablePictureInPicture = true;
    vid.setAttribute("autoplay", "");
    vid.setAttribute("loop", "");
    vid.setAttribute("muted", "");
    vid.setAttribute("playsinline", "");
    vid.style.pointerEvents = "none";
    vid.className = className;
    vid.addEventListener("canplay", () => {
      vid.play().catch(() => {});
    });
    setTimeout(() => {
      vid.play().catch(() => {});
    }, 50);
    return vid;
  }

  const img = document.createElement("img");
  img.src = url;
  img.className = className;
  img.loading = "lazy";
  img.alt = emoji || "стикер";
  img.style.pointerEvents = "none";
  img.onerror = () => {
    const vid = document.createElement("video");
    vid.src = url.replace(/\.[a-zA-Z0-9]+$/, '.webm');
    vid.autoplay = true;
    vid.loop = true;
    vid.muted = true;
    vid.defaultMuted = true;
    vid.playsInline = true;
    vid.controls = false;
    vid.disablePictureInPicture = true;
    vid.setAttribute("autoplay", "");
    vid.setAttribute("loop", "");
    vid.setAttribute("muted", "");
    vid.setAttribute("playsinline", "");
    vid.style.pointerEvents = "none";
    vid.className = className;
    vid.addEventListener("canplay", () => {
      vid.play().catch(() => {});
    });
    img.replaceWith(vid);
  };
  return img;
}

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

  const header = el("div", { class: "sticker-picker-header", style: "display:flex;align-items:center;justify-content:space-between;padding:10px 14px;border-bottom:1px solid var(--border);box-sizing:border-box;" });
  const title = el("span", { class: "sticker-picker-title", style: "font-weight:600;font-size:14px;color:var(--text);" }, "Стикеры");
  const addBtn = el("button", {
    class: "btn-add-pack",
    title: "Импортировать стикерпак из Telegram",
    style: "display:flex;align-items:center;gap:4px;padding:4px 8px;background:rgba(91,110,245,0.15);color:#5b6ef5;border:none;border-radius:6px;font-size:12px;font-weight:600;cursor:pointer;flex-shrink:0;"
  }, svgIcon(ICON_PLUS, 14, "#5b6ef5"), "Импорт");
  header.appendChild(title);
  header.appendChild(addBtn);

  const contentArea = el("div", { class: "sticker-picker-content" });
  const tabsBar = el("div", { class: "sticker-picker-tabs" });

  container.appendChild(header);
  container.appendChild(contentArea);
  container.appendChild(tabsBar);

  container.addEventListener("click", (e) => e.stopPropagation());
  container.addEventListener("pointerdown", (e) => e.stopPropagation());

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

      const coverExt = pack.cover_sticker_id && !pack.cover_sticker_id.includes('.')
        ? `.${pack.is_video ? 'webm' : (pack.is_animated ? 'tgs' : 'webp')}`
        : '';
      const coverUrl = pack.cover_sticker_id
        ? getFullApiUrl(`/api/v1/stickers/file/${pack.id}/${pack.cover_sticker_id}${coverExt}`)
        : "";

      if (coverUrl) {
        if (pack.is_video) {
          const vid = createStickerMediaElement(coverUrl, true, pack.title, "sticker-tab-icon");
          tabBtn.appendChild(vid);
        } else if (!pack.is_animated) {
          const img = createStickerMediaElement(coverUrl, false, pack.title, "sticker-tab-icon");
          tabBtn.appendChild(img);
        } else {
          tabBtn.textContent = pack.title.slice(0, 2);
        }
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
      style: "display:flex;align-items:center;justify-content:center;color:#5b6ef5;cursor:pointer;"
    }, [svgIcon(ICON_PLUS, 18, "#5b6ef5")]);
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
        const emptyWrap = el("div", { class: "sticker-picker-empty", style: "display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;margin:auto;text-align:center;padding:24px 16px;" });
        emptyWrap.appendChild(el("p", { style: "margin:0;color:var(--text-muted);font-size:13px;" }, "Здесь будут ваши недавние стикеры"));
        const importBtn = el("button", {
          class: "btn btn-primary",
          style: "padding:8px 18px;font-size:13px;border-radius:10px;display:inline-flex;align-items:center;gap:6px;cursor:pointer;"
        }, svgIcon(ICON_PLUS, 16, "#fff"), "Импорт из Telegram");
        importBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          showImportStickersModal(() => loadPacks());
        });
        emptyWrap.appendChild(importBtn);
        contentArea.appendChild(emptyWrap);
        return;
      }
      const packHeader = el("div", { class: "sticker-pack-preview-header" });
      packHeader.appendChild(el("span", { class: "pack-name" }, "Недавние"));
      contentArea.appendChild(packHeader);

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
    const isVideo = Boolean(pack?.is_video || s.file_name?.endsWith('.webm'));
    const isTgs = Boolean(pack?.is_animated || s.file_name?.endsWith('.tgs'));
    const url = getFullApiUrl(s.url || `/api/v1/stickers/file/${s.pack_id}/${s.file_name || (s.id + (isVideo ? '.webm' : (isTgs ? '.tgs' : '.webp')))}`);

    const item = el("button", { class: "sticker-grid-item", title: s.emoji || "" });
    const mediaEl = createStickerMediaElement(url, isVideo, s.emoji || "стикер", "sticker-img");
    item.appendChild(mediaEl);

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
  const modalBox = el("div", {
    style: "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:20px;width:100%;max-width:440px;box-shadow:0 8px 32px rgba(0,0,0,0.6);display:flex;flex-direction:column;max-height:80vh;"
  });

  const overlay = el("div", {
    style: "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.75);backdrop-filter:blur(6px);display:flex;align-items:center;justify-content:center;z-index:99999;padding:16px;box-sizing:border-box;"
  }, modalBox);

  modalBox.appendChild(spinner());
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

    modalBox.innerHTML = "";

    const header = el("div", { style: "display:flex;align-items:center;justify-content:space-between;margin-bottom:14px;" },
      el("h3", { style: "margin:0;font-size:17px;color:#fff;font-weight:600;" }, pack.title),
      el("button", {
        class: "btn-icon",
        style: "background:transparent;border:none;color:rgba(255,255,255,0.6);cursor:pointer;padding:4px;"
      }, svgIcon(ICON_CLOSE, 18, "currentColor"))
    );
    header.querySelector("button").addEventListener("click", close);
    modalBox.appendChild(header);

    const body = el("div", { style: "flex:1;overflow-y:auto;margin-bottom:16px;padding-right:4px;" });
    const grid = el("div", { class: "stickers-grid" });

    for (const s of (pack.stickers || [])) {
      const isVideo = Boolean(pack.is_video || s.file_name?.endsWith('.webm'));
      const url = getFullApiUrl(s.url || `/api/v1/stickers/file/${pack.id}/${s.file_name}`);
      const item = el("div", { class: "sticker-grid-item preview-only" });
      const mediaEl = createStickerMediaElement(url, isVideo, s.emoji || "стикер", "sticker-img");
      item.appendChild(mediaEl);
      grid.appendChild(item);
    }

    body.appendChild(grid);
    modalBox.appendChild(body);

    const footer = el("div", { style: "display:flex;gap:10px;justify-content:flex-end;" });
    const actionBtn = el("button", {
      class: `btn ${isInstalled ? "btn-secondary" : "btn-primary"}`,
      style: "width:100%;padding:10px;font-size:14px;border-radius:10px;font-weight:600;"
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
    modalBox.appendChild(footer);
  } catch (err) {
    modalBox.innerHTML = "";
    modalBox.appendChild(el("h3", { style: "margin-top:0;color:#fff;font-size:16px;" }, "Ошибка"));
    modalBox.appendChild(el("p", { style: "color:rgba(255,255,255,0.7);font-size:14px;" }, "Не удалось загрузить информацию о стикерпаке."));
    const closeBtn = el("button", { class: "btn btn-secondary", style: "align-self:flex-end;margin-top:12px;" }, "Закрыть");
    closeBtn.addEventListener("click", close);
    modalBox.appendChild(closeBtn);
  }
}

/**
 * Modal to import a sticker pack from Telegram by link or name.
 */
export function showImportStickersModal(onSuccess) {
  const input = el("input", {
    type: "text",
    class: "input",
    placeholder: "https://t.me/addstickers/Doge",
    style: "width:100%;box-sizing:border-box;margin-bottom:16px;padding:10px 12px;border-radius:8px;border:1px solid rgba(255,255,255,0.15);background:rgba(0,0,0,0.25);color:#fff;font-size:14px;outline:none;"
  });

  const cancelBtn = el("button", {
    class: "btn btn-secondary",
    style: "flex:1;padding:10px;border-radius:10px;font-size:14px;cursor:pointer;"
  }, "Отмена");

  const importBtn = el("button", {
    class: "btn btn-primary",
    style: "flex:1;padding:10px;border-radius:10px;font-size:14px;cursor:pointer;font-weight:600;"
  }, "Импортировать");

  const closeBtn = el("button", {
    class: "btn-icon",
    style: "background:transparent;border:none;color:rgba(255,255,255,0.6);cursor:pointer;padding:4px;"
  }, svgIcon(ICON_CLOSE, 18, "currentColor"));

  const header = el("div", { style: "display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;" },
    el("h3", { style: "margin:0;font-size:17px;color:#fff;font-weight:600;" }, "Импорт стикеров из Telegram"),
    closeBtn
  );

  const modalBox = el("div", {
    style: "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:24px;width:100%;max-width:420px;box-shadow:0 8px 32px rgba(0,0,0,0.6);display:flex;flex-direction:column;box-sizing:border-box;"
  },
    header,
    el("p", { style: "margin:0 0 16px;color:rgba(255,255,255,0.7);font-size:13px;line-height:1.4;" },
      "Вставьте ссылку на стикерпак (например, ",
      el("code", { style: "background:rgba(255,255,255,0.08);padding:2px 4px;border-radius:4px;" }, "t.me/addstickers/pack_name"),
      ") или просто его название."
    ),
    input,
    el("div", { style: "display:flex;gap:10px;" }, cancelBtn, importBtn)
  );

  const overlay = el("div", {
    style: "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.75);backdrop-filter:blur(6px);display:flex;align-items:center;justify-content:center;z-index:99999;padding:16px;box-sizing:border-box;"
  }, modalBox);

  document.body.appendChild(overlay);
  setTimeout(() => input.focus(), 50);

  const close = () => overlay.remove();
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
  closeBtn.addEventListener("click", close);
  cancelBtn.addEventListener("click", close);

  const doImport = async () => {
    const val = input.value.trim();
    if (!val) {
      showToast("Введите ссылку на стикерпак", "error");
      return;
    }

    importBtn.disabled = true;
    cancelBtn.disabled = true;
    importBtn.textContent = "Импортируем...";

    try {
      const pack = await importTelegramStickerPack(val);
      showToast(`Стикерпак "${pack.title}" успешно импортирован!`, "success");
      close();
      if (typeof onSuccess === "function") onSuccess(pack);
    } catch (err) {
      showToast(err.message || "Ошибка импорта стикерпака", "error");
      importBtn.disabled = false;
      cancelBtn.disabled = false;
      importBtn.textContent = "Импортировать";
    }
  };

  importBtn.addEventListener("click", doImport);
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") doImport();
    if (e.key === "Escape") close();
  });
}
