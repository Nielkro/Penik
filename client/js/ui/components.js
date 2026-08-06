import { decodeKey, decryptFileChaCha20 } from "../crypto.js";
import { getToken } from "../api.js";

export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, val] of Object.entries(attrs)) {
    if (key.startsWith("on") && typeof val === "function") {
      node.addEventListener(key.slice(2).toLowerCase(), val);
    } else if (key === "class") {
      node.className = val;
    } else if (val !== null && val !== undefined && val !== false) {
      node.setAttribute(key, val);
    }
  }
  for (const child of children) {
    if (child === null || child === undefined) continue;
    if (typeof child === "string" || typeof child === "number") {
      node.appendChild(document.createTextNode(String(child)));
    } else if (child instanceof Node) {
      node.appendChild(child);
    }
  }
  return node;
}

export function svgIcon(pathD, size = 20, color = "currentColor", strokeWidth = 2, viewBox = "0 0 24 24") {
  const svgEl = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svgEl.setAttribute("viewBox", viewBox);
  svgEl.setAttribute("width", String(size));
  svgEl.setAttribute("height", String(size));
  svgEl.setAttribute("fill", "none");
  svgEl.setAttribute("stroke", color);
  svgEl.setAttribute("stroke-width", String(strokeWidth));
  svgEl.setAttribute("stroke-linecap", "round");
  svgEl.setAttribute("stroke-linejoin", "round");
  svgEl.style.display = "block";
  svgEl.style.flexShrink = "0";
  svgEl.innerHTML = `<path d="${pathD}"></path>`;
  return svgEl;
}

const failedAvatars = new Set();

export function avatar(user, size = 40, forceTimestamp = null) {
  if (user && user.name === "Избранное") {
    const svgEl = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svgEl.setAttribute("viewBox", "0 0 24 24");
    svgEl.setAttribute("width", String(Math.round(size * 0.45)));
    svgEl.setAttribute("height", String(Math.round(size * 0.45)));
    svgEl.setAttribute("fill", "none");
    svgEl.setAttribute("stroke", "#ffffff");
    svgEl.setAttribute("stroke-width", "2.5");
    svgEl.setAttribute("stroke-linecap", "round");
    svgEl.setAttribute("stroke-linejoin", "round");
    svgEl.innerHTML = '<path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path>';

    return el("div", {
      class: "avatar",
      style: `width:${size}px;height:${size}px;border-radius:50%;background:#5fa8df;display:flex;align-items:center;justify-content:center;flex-shrink:0;`
    }, svgEl);
  }

  const wrap = el("div", { class: "avatar", style: `width:${size}px;height:${size}px;border-radius:50%;overflow:hidden;display:flex;align-items:center;justify-content:center;flex-shrink:0;` });

  const userId = user && (user.user_id || user.id);
  let avatarUrl = (user && user.avatar_url) || (userId ? `/api/v1/avatar/${userId}` : null);
  if (avatarUrl && forceTimestamp) {
    avatarUrl += (avatarUrl.includes("?") ? "&t=" : "?t=") + forceTimestamp;
    failedAvatars.delete(`/api/v1/avatar/${userId}`);
  }

  const cacheKey = `/api/v1/avatar/${userId}`;
  if (avatarUrl && !failedAvatars.has(cacheKey)) {
    const img = el("img", {
      src: avatarUrl,
      alt: user.name || user.username || "?",
      style: `width:${size}px;height:${size}px;object-fit:cover;`,
    });
    img.onerror = () => {
      if (userId) failedAvatars.add(cacheKey);
      if (wrap.contains(img)) wrap.removeChild(img);
      wrap.appendChild(initialsNode(user, size));
    };
    wrap.appendChild(img);
  } else {
    wrap.appendChild(initialsNode(user, size));
  }

  return wrap;
}

// showFullscreenImage opens a tap-to-close overlay showing `url` at full size.
// Used for viewing user/group avatars full-screen instead of navigating away.
export function showFullscreenImage(url, altText = "") {
  const overlay = el("div", {
    style: "position:fixed;inset:0;background:rgba(0,0,0,0.92);z-index:9999;display:flex;align-items:center;justify-content:center;cursor:zoom-out;",
  });
  const img = el("img", {
    src: url,
    alt: altText,
    style: "max-width:92vw;max-height:92vh;border-radius:8px;object-fit:contain;cursor:default;",
  });
  img.addEventListener("click", (e) => e.stopPropagation());
  const closeBtn = el("button", {
    style: "position:absolute;top:16px;right:16px;background:none;border:none;color:#fff;font-size:28px;cursor:pointer;line-height:1;padding:8px;",
  }, "✕");
  const close = () => overlay.remove();
  closeBtn.addEventListener("click", close);
  overlay.addEventListener("click", close);
  overlay.append(img, closeBtn);
  document.body.appendChild(overlay);
  return overlay;
}

// Shared cache-buster for group avatars: bumped locally after a self-upload,
// and by the GROUP_AVATAR_UPDATE websocket event when any member changes it.
export const groupAvatarUpdateTimestamps = new Map();

export function groupAvatar(group, size = 40, forceTimestamp = null) {
  const container = el("div", {
    style: `position:relative;width:${size}px;height:${size}px;flex-shrink:0;`
  });

  const wrap = el("div", {
    class: "avatar",
    style: `width:100%;height:100%;border-radius:50%;overflow:hidden;display:flex;align-items:center;justify-content:center;`
  });

  const groupId = group && group.id;
  const ts = forceTimestamp || (groupId ? groupAvatarUpdateTimestamps.get(String(groupId)) : null);
  let avatarUrl = groupId ? `/api/v1/groups/${groupId}/avatar` : null;
  if (avatarUrl && ts) {
    avatarUrl += `?t=${ts}`;
  }

  if (avatarUrl) {
    const img = el("img", {
      src: avatarUrl,
      alt: (group && group.name) || "?",
      style: `width:100%;height:100%;object-fit:cover;`,
    });
    img.onerror = () => {
      if (wrap.contains(img)) wrap.removeChild(img);
      wrap.appendChild(initialsNode(group, size));
    };
    wrap.appendChild(img);
  } else {
    wrap.appendChild(initialsNode(group, size));
  }

  container.appendChild(wrap);

  const badgeSize = Math.max(14, Math.min(22, Math.round(size * 0.35)));
  const badgeFontSize = Math.max(9, Math.min(14, Math.round(badgeSize * 0.6)));

  const badge = el("div", {
    style: `position:absolute;bottom:-2px;right:-2px;width:${badgeSize}px;height:${badgeSize}px;border-radius:50%;background:#00e676;color:#121214;display:flex;align-items:center;justify-content:center;font-size:${badgeFontSize}px;box-shadow:0 0 0 2px #1e1e24;font-weight:bold;z-index:2;line-height:1;`
  }, "👥");

  container.appendChild(badge);

  return container;
}

function initialsNode(user, size) {
  const name = (user && (user.name || user.username)) || "?";
  const initials = name
    .split(" ")
    .map((w) => w[0])
    .join("")
    .slice(0, 2)
    .toUpperCase();

  const userId = Number(user && (user.user_id || user.id)) || 0;
  const hue = userId ? (userId * 137) % 360 : [...name].reduce((acc, ch) => acc + ch.charCodeAt(0), 0) % 360;
  const bg = `hsl(${hue}, 55%, 50%)`;
  const fontSize = Math.round(size * 0.4);

  const span = el("span", {
    style: `width:${size}px;height:${size}px;border-radius:50%;background:${bg};display:flex;align-items:center;justify-content:center;color:#fff;font-size:${fontSize}px;font-weight:600;user-select:none;`,
  }, initials);

  return span;
}

// Time formatting

function toDate(ts) {
  return new Date(typeof ts === "number" && ts < 1e12 ? ts * 1000 : ts);
}

export function formatTime(ts) {
  const d = toDate(ts);
  if (isNaN(d.getTime())) return "";
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
}

/** Full timestamp for hover/tap, e.g. "27 июля 2026 г., 21:38:12". */
export function formatFullTime(ts) {
  const d = toDate(ts);
  if (isNaN(d.getTime())) return "";
  return d.toLocaleString("ru-RU", {
    day: "numeric",
    month: "long",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}

// Match http(s) URLs; trailing punctuation is stripped from the href.
const MSG_URL_RE = /https?:\/\/[^\s<>"']+/gi;

/**
 * Fill a .msg-text element with plain text, turning http(s) URLs into safe
 * <a class="msg-link"> anchors (no innerHTML — plaintext is never parsed as HTML).
 */
export function setMsgTextContent(el, text) {
  el.replaceChildren();
  if (!text) return;

  const s = String(text);
  if (s.startsWith("{")) {
    try {
      const parsed = JSON.parse(s);
      if (parsed.type === "file" && parsed.file) {
        renderFileCard(el, parsed);
        return;
      }
    } catch (e) {}
  }

  let last = 0;
  MSG_URL_RE.lastIndex = 0;
  let m;
  while ((m = MSG_URL_RE.exec(s)) !== null) {
    if (m.index > last) {
      el.appendChild(document.createTextNode(s.slice(last, m.index)));
    }
    let url = m[0];
    let trail = "";
    while (url.length && /[.,;:!?)]+$/.test(url)) {
      trail = url.slice(-1) + trail;
      url = url.slice(0, -1);
    }
    if (url) {
      const a = document.createElement("a");
      a.className = "msg-link";
      a.href = url;
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      a.textContent = url;
      a.addEventListener("click", (e) => e.stopPropagation());
      el.appendChild(a);
    }
    if (trail) el.appendChild(document.createTextNode(trail));
    last = m.index + m[0].length;
  }
  if (last < s.length) {
    el.appendChild(document.createTextNode(s.slice(last)));
  }
}

function renderFileCard(container, fileMsg) {
  const f = fileMsg.file;
  const isImage = (f.mime || "").startsWith("image/");
  const isVideo = (f.mime || "").startsWith("video/");

  if (isImage) {
    const fileCard = el("div", { class: "msg-file-card", style: "display:flex;flex-direction:column;gap:4px;width:100%;padding:1px;" });
    const cachedBlobUrl = decryptedBlobCache.get(f.url);
    const initialSrc = cachedBlobUrl || f.thumb;

    if (initialSrc) {
      const imgEl = el("img", {
        src: initialSrc,
        alt: f.name || "Изображение",
        style: "display:block;width:100%;height:auto;max-width:100%;border-radius:14px;cursor:pointer;background:rgba(255,255,255,0.05);transition:opacity 0.2s;"
      });
      imgEl.addEventListener("click", (e) => {
        e.stopPropagation();
        downloadAndDecryptFile(f, true);
      });
      fileCard.appendChild(imgEl);

      if (!cachedBlobUrl) {
        downloadAndDecryptFile(f, false, null, true).then((fullBlobUrl) => {
          if (fullBlobUrl && document.body.contains(imgEl)) {
            imgEl.src = fullBlobUrl;
          }
        }).catch(() => {/* Keep thumbnail fallback */});
      }
    }

    if (fileMsg.text) {
      const captionEl = el("div", { style: "margin-top:2px;font-size:14px;word-break:break-word;padding:0 4px;" }, fileMsg.text);
      fileCard.appendChild(captionEl);
    }

    container.appendChild(fileCard);
    return;
  }

  if (isVideo) {
    const fileCard = el("div", { class: "msg-file-card", style: "display:flex;flex-direction:column;gap:4px;width:100%;padding:1px;position:relative;" });
    const cachedBlobUrl = decryptedBlobCache.get(f.url);

    const videoEl = el("video", {
      controls: true,
      loop: true,
      muted: true,
      playsinline: true,
      style: "display:block;width:100%;max-height:360px;object-fit:cover;border-radius:14px;background:#000;cursor:pointer;"
    });

    if (f.thumb) {
      videoEl.poster = f.thumb;
    }

    if (cachedBlobUrl) {
      videoEl.src = cachedBlobUrl;
    } else {
      // Background progressive fetch
      downloadAndDecryptFile(f, false, null, true).then((fullBlobUrl) => {
        if (fullBlobUrl) {
          videoEl.src = fullBlobUrl;
        }
      }).catch((err) => {
        console.warn("[video] Progressive load failed:", err);
      });
    }

    // Toggle play/pause or mute on click
    videoEl.addEventListener("click", (e) => {
      e.stopPropagation();
      if (videoEl.paused) {
        videoEl.play().catch(() => {});
      } else {
        videoEl.pause();
      }
    });

    fileCard.appendChild(videoEl);

    if (fileMsg.text) {
      const captionEl = el("div", { style: "margin-top:2px;font-size:14px;word-break:break-word;padding:0 4px;" }, fileMsg.text);
      fileCard.appendChild(captionEl);
    }

    container.appendChild(fileCard);
    return;
  }

  // Non-image files (documents, archives, etc.) keep document card UI
  const fileCard = el("div", { class: "msg-file-card", style: "display:flex;flex-direction:column;gap:8px;max-width:320px;padding:2px;" });
  const infoRow = el("div", {
    style: "display:flex;align-items:center;gap:10px;background:rgba(255,255,255,0.06);padding:8px 12px;border-radius:8px;cursor:pointer;"
  });
  infoRow.addEventListener("click", (e) => {
    e.stopPropagation();
    downloadAndDecryptFile(f, false);
  });

  const iconNode = el("span", { style: "font-size:24px;flex-shrink:0;" }, "📎");
  const metaBox = el("div", { style: "display:flex;flex-direction:column;min-width:0;flex:1;" },
    el("span", { style: "font-weight:600;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#fff;" }, f.name || "Файл"),
    el("span", { style: "font-size:11px;color:var(--text-muted);" }, formatFileSize(f.size || 0))
  );

  infoRow.append(iconNode, metaBox);
  fileCard.appendChild(infoRow);

  if (fileMsg.text) {
    const captionEl = el("div", { style: "margin-top:2px;font-size:14px;word-break:break-word;padding:0 4px;" }, fileMsg.text);
    fileCard.appendChild(captionEl);
  }

  container.appendChild(fileCard);
}

export const decryptedBlobCache = new Map();

async function downloadAndDecryptFile(fileInfo, isPreviewClick = false, btn = null, isBackgroundFetch = false) {
  if (btn) {
    btn.disabled = true;
    btn.textContent = "Загрузка…";
  }
  try {
    let blobUrl = decryptedBlobCache.get(fileInfo.url);
    if (!blobUrl) {
      const token = getToken();
      const proxyUrl = `/api/v1/attachments/proxy?url=${encodeURIComponent(fileInfo.url)}`;
      const headers = {};
      if (token) headers['Authorization'] = `Bearer ${token}`;

      const resp = await fetch(proxyUrl, { headers });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const encryptedBuf = await resp.arrayBuffer();
      const encryptedBytes = new Uint8Array(encryptedBuf);

      const keyBytes = decodeKey(fileInfo.key);
      const decryptedBytes = await decryptFileChaCha20(encryptedBytes, keyBytes);

      const blob = new Blob([decryptedBytes], { type: fileInfo.mime || "application/octet-stream" });
      blobUrl = URL.createObjectURL(blob);
      decryptedBlobCache.set(fileInfo.url, blobUrl);
    }

    if (isBackgroundFetch) {
      return blobUrl;
    }

    if (isPreviewClick && (fileInfo.mime || "").startsWith("image/")) {
      showFullscreenImage(blobUrl, fileInfo.name);
    } else {
      const a = document.createElement("a");
      a.href = blobUrl;
      a.download = fileInfo.name || "file";
      document.body.appendChild(a);
      a.click();
      a.remove();
    }
    return blobUrl;
  } catch (err) {
    if (!isBackgroundFetch) {
      console.error("Failed to download or decrypt file:", err);
      showToast("Ошибка скачивания или расшифровки файла", "error");
    }
    throw err;
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.textContent = "Скачать";
    }
  }
}

function formatFileSize(bytes) {
  if (!bytes || bytes === 0) return "0 Б";
  const k = 1024;
  const sizes = ["Б", "КБ", "МБ", "ГБ"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
}

/** Wire a .msg-time span: hover shows a floating full-time tooltip next to it. */
export function wireMsgTime(timeEl, ts) {
  const short = formatTime(ts);
  const full = formatFullTime(ts);
  timeEl.textContent = short;
  timeEl.title = "";
  timeEl.dataset.short = short;
  timeEl.dataset.full = full;
  timeEl.style.position = "relative";

  let tip = null;

  const showTip = () => {
    if (!full || tip) return;
    tip = document.createElement("div");
    tip.className = "msg-time-tooltip";
    tip.textContent = full;
    document.body.appendChild(tip);

    // Position: above the timeEl, aligned to its right edge.
    const rect = timeEl.getBoundingClientRect();
    const tipW = 200;
    let left = rect.right - tipW;
    if (left < 6) left = 6;
    if (left + tipW > window.innerWidth - 6) left = window.innerWidth - tipW - 6;

    // Prefer above; fall back to below if not enough space.
    const spaceAbove = rect.top;
    let top;
    if (spaceAbove >= 36) {
      top = rect.top - 4;
      tip.style.transform = "translateY(-100%)";
    } else {
      top = rect.bottom + 4;
    }
    tip.style.left = left + "px";
    tip.style.top = top + "px";
  };

  const hideTip = () => {
    if (tip) { tip.remove(); tip = null; }
  };

  timeEl.addEventListener("mouseenter", showTip);
  timeEl.addEventListener("mouseleave", hideTip);
  // Hide when the scroll container moves so the tip doesn't float at the wrong position.
  const scroller = timeEl.closest(".chat-messages");
  if (scroller) {
    scroller.addEventListener("scroll", hideTip, { passive: true });
  }
  // Clean up if the element is removed from the DOM.
  timeEl.addEventListener("blur", hideTip, { passive: true });
}

/**
 * Right-click / long-press on a message bubble → mini menu with "Копировать".
 * `getText` is a string or a function returning the plaintext to copy.
 */
export function wireMsgCopy(bubble, getText, onReply, onDelete) {
  const resolveText = () => {
    const t = typeof getText === "function" ? getText() : getText;
    return (t == null ? "" : String(t)).trim();
  };

  const doCopy = async () => {
    const text = resolveText();
    if (!text) return;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const ta = document.createElement("textarea");
        ta.value = text;
        ta.style.cssText = "position:fixed;left:-9999px;top:0";
        document.body.appendChild(ta);
        ta.select();
        document.execCommand("copy");
        ta.remove();
      }
      showToast("Скопировано");
    } catch {
      showToast("Не удалось скопировать", "error");
    }
  };

  bubble.addEventListener("contextmenu", (e) => {
    // Keep the native browser menu on links.
    if (e.target.closest?.("a.msg-link")) return;
    e.preventDefault();
    e.stopPropagation();
    showMsgActionMenu(e.clientX, e.clientY, doCopy, onReply, onDelete);
  });

  // Touch handlers for long-press & swipe-to-reply.
  let pressTimer = null;
  let startX = 0;
  let startY = 0;
  let currentOffsetX = 0;
  let isSwiping = false;
  let triggered = false;

  const clearPress = () => {
    if (pressTimer != null) {
      clearTimeout(pressTimer);
      pressTimer = null;
    }
  };

  const resetSwipe = () => {
    bubble.style.transition = "transform 0.2s ease-out";
    bubble.style.transform = "";
    setTimeout(() => { bubble.style.transition = ""; }, 200);
    currentOffsetX = 0;
    isSwiping = false;
    triggered = false;
  };

  bubble.addEventListener("touchstart", (e) => {
    if (e.target.closest?.("a.msg-link")) return;
    const t = e.touches[0];
    if (!t) return;
    startX = t.clientX;
    startY = t.clientY;
    currentOffsetX = 0;
    isSwiping = false;
    triggered = false;
    clearPress();
    pressTimer = setTimeout(() => {
      pressTimer = null;
      if (!isSwiping) {
        showMsgActionMenu(startX, startY, doCopy, onReply, onDelete);
      }
    }, 480);
  }, { passive: true });

  bubble.addEventListener("touchmove", (e) => {
    const t = e.touches[0];
    if (!t) return;
    const diffX = t.clientX - startX;
    const diffY = t.clientY - startY;

    if (pressTimer != null && (Math.abs(diffX) > 10 || Math.abs(diffY) > 10)) {
      clearPress();
    }

    // Swipe horizontally (rightwards for reply)
    if (!isSwiping && Math.abs(diffX) > Math.abs(diffY) * 1.5 && diffX > 8) {
      isSwiping = true;
    }

    if (isSwiping && diffX > 0) {
      // Resistance dragging formula
      const maxDrag = 80;
      const drag = Math.min(diffX * 0.5, maxDrag);
      currentOffsetX = drag;
      bubble.style.transform = `translateX(${drag}px)`;

      if (drag >= 50 && !triggered) {
        triggered = true;
        if (navigator.vibrate) navigator.vibrate(25);
      }
    }
  }, { passive: true });

  const handleTouchEnd = () => {
    clearPress();
    if (isSwiping) {
      if (triggered && typeof onReply === "function") {
        onReply();
      }
      resetSwipe();
    }
  };

  bubble.addEventListener("touchend", handleTouchEnd, { passive: true });
  bubble.addEventListener("touchcancel", () => { clearPress(); resetSwipe(); }, { passive: true });
}

function showMsgActionMenu(x, y, onCopy, onReply, onDelete) {
  document.getElementById("msg-action-menu")?.remove();
  const menu = el("div", {
    id: "msg-action-menu",
    class: "msg-action-menu",
    style: `left:${x}px;top:${y}px;`,
  });
  const copyItem = el("button", { type: "button", class: "msg-action-item" }, "Копировать");
  copyItem.addEventListener("click", (e) => {
    e.stopPropagation();
    menu.remove();
    onCopy();
  });
  menu.appendChild(copyItem);

  if (onReply) {
    const replyItem = el("button", { type: "button", class: "msg-action-item" }, "Ответить");
    replyItem.addEventListener("click", (e) => {
      e.stopPropagation();
      menu.remove();
      onReply();
    });
    menu.appendChild(replyItem);
  }

  if (onDelete) {
    const delItem = el("button", { type: "button", class: "msg-action-item", style: "color:var(--danger);" }, "Удалить");
    delItem.addEventListener("click", (e) => {
      e.stopPropagation();
      menu.remove();
      onDelete();
    });
    menu.appendChild(delItem);
  }

  document.body.appendChild(menu);


  // Keep menu inside the viewport.
  requestAnimationFrame(() => {
    const r = menu.getBoundingClientRect();
    let left = x;
    let top = y;
    if (r.right > window.innerWidth - 8) left = Math.max(8, window.innerWidth - r.width - 8);
    if (r.bottom > window.innerHeight - 8) top = Math.max(8, window.innerHeight - r.height - 8);
    menu.style.left = `${left}px`;
    menu.style.top = `${top}px`;
  });

  const close = (ev) => {
    if (menu.contains(ev.target)) return;
    menu.remove();
    document.removeEventListener("pointerdown", close, true);
    document.removeEventListener("keydown", onKey, true);
  };
  const onKey = (ev) => {
    if (ev.key === "Escape") {
      menu.remove();
      document.removeEventListener("pointerdown", close, true);
      document.removeEventListener("keydown", onKey, true);
    }
  };
  // Defer so the opening event does not immediately close the menu.
  setTimeout(() => {
    document.addEventListener("pointerdown", close, true);
    document.addEventListener("keydown", onKey, true);
  }, 0);
}

/**
 * Wrap a .chat-messages scroller with a floating "↓" button that appears when
 * the user scrolls up. Returns { isNearBottom, scrollToBottom, update }.
 */
export function attachScrollDownButton(messagesEl) {
  const parent = messagesEl.parentNode;
  const wrap = el("div", { class: "chat-messages-wrap" });
  parent.insertBefore(wrap, messagesEl);
  wrap.appendChild(messagesEl);

  const btn = el("button", {
    type: "button",
    class: "chat-scroll-down",
    title: "К последним сообщениям",
    "aria-label": "К последним сообщениям",
  }, "↓");
  wrap.appendChild(btn);

  const THRESH = 120;
  const isNearBottom = () => {
    const dist = messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight;
    return dist <= THRESH;
  };
  const update = () => {
    btn.hidden = isNearBottom();
  };
  const scrollToBottom = (smooth = false) => {
    if (smooth && typeof messagesEl.scrollTo === "function") {
      messagesEl.scrollTo({ top: messagesEl.scrollHeight, behavior: "smooth" });
    } else {
      messagesEl.scrollTop = messagesEl.scrollHeight;
    }
    btn.hidden = true;
  };

  messagesEl.addEventListener("scroll", update, { passive: true });
  btn.addEventListener("click", () => scrollToBottom(true));
  // New content may change scrollHeight without a scroll event.
  if (typeof ResizeObserver !== "undefined") {
    const ro = new ResizeObserver(update);
    ro.observe(messagesEl);
  }
  update();
  return { isNearBottom, scrollToBottom, update };
}

// Date separator label

export function formatDate(ts) {
  const d = new Date(typeof ts === "number" && ts < 1e12 ? ts * 1000 : ts);
  if (isNaN(d.getTime())) return "";

  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterday = new Date(today - 86400000);
  const msgDay = new Date(d.getFullYear(), d.getMonth(), d.getDate());

  if (msgDay.getTime() === today.getTime()) return "Сегодня";
  if (msgDay.getTime() === yesterday.getTime()) return "Вчера";

  return d.toLocaleDateString([], { year: "numeric", month: "long", day: "numeric" });
}

// Presence: "в сети" / "был(а) в сети <when>". `presence` is the
// {online, last_seen} shape returned by GET /users/:id and group members.
export function formatPresence(presence) {
  if (!presence) return "";
  if (presence.online) return "в сети";
  const ts = presence.last_seen;
  if (!ts) return "";
  const d = new Date(ts * 1000);
  if (isNaN(d.getTime()) || ts <= 0) return "";

  const now = new Date();
  // Within the last minute — show "just now" instead of a clock time.
  if (now.getTime() - d.getTime() < 60_000) return "был(а) в сети только что";

  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterday = new Date(today - 86400000);
  const seenDay = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });

  if (seenDay.getTime() === today.getTime()) return `был(а) в сети в ${time}`;
  if (seenDay.getTime() === yesterday.getTime()) return `был(а) в сети вчера в ${time}`;
  return `был(а) в сети ${d.toLocaleDateString([], { day: "numeric", month: "long" })} в ${time}`;
}

// Toast notifications

let _toastContainer = null;

function getToastContainer() {
  if (!_toastContainer) {
    _toastContainer = el("div", {
      class: "toast-container",
      style: "position:fixed;bottom:24px;right:24px;z-index:9999;display:flex;flex-direction:column;gap:8px;align-items:flex-end;pointer-events:none;"
    });
    document.body.appendChild(_toastContainer);
  }
  return _toastContainer;
}

export function showToast(msg, type = "info") {
  const colorMap = {
    success: "#22c55e",
    error: "#ef4444",
    info: "#3b82f6",
    warning: "#f59e0b",
  };
  const bg = colorMap[type] || colorMap.info;

  const closeBtn = el("button", {
    style: "background:none;border:none;color:inherit;font-size:18px;line-height:1;margin-left:12px;padding:0;cursor:pointer;opacity:0.7;font-weight:bold;flex-shrink:0;",
    onclick: (e) => {
      e.stopPropagation();
      toast.remove();
    }
  }, "×");
  closeBtn.addEventListener("mouseenter", () => closeBtn.style.opacity = "1");
  closeBtn.addEventListener("mouseleave", () => closeBtn.style.opacity = "0.7");

  const toast = el("div", {
    class: `toast toast-${type}`,
    style: `background:${bg};color:#fff;padding:10px 16px;border-radius:8px;font-size:14px;font-weight:500;box-shadow:0 4px 12px rgba(0,0,0,0.25);pointer-events:auto;opacity:0;transition:opacity 0.2s, transform 0.2s;transform:translateY(10px);max-width:320px;display:flex;align-items:center;justify-content:space-between;`,
  }, el("span", { style: "flex-grow:1;text-align:left;" }, msg), closeBtn);

  const container = getToastContainer();
  container.appendChild(toast);

  requestAnimationFrame(() => {
    toast.style.opacity = "1";
    toast.style.transform = "translateY(0)";
  });

  setTimeout(() => {
    toast.style.opacity = "0";
    toast.style.transform = "translateY(-10px)";
    let removed = false;
    const remove = () => {
      if (removed) return;
      removed = true;
      toast.remove();
    };
    toast.addEventListener("transitionend", remove, { once: true });
    setTimeout(remove, 300);
  }, 4000);
}

// Loading spinner

export function spinner() {
  const s = el("span", {
    class: "spinner",
    style: "display:inline-block;width:18px;height:18px;border:2.5px solid rgba(255,255,255,0.3);border-top-color:#fff;border-radius:50%;animation:spin 0.7s linear infinite;vertical-align:middle;",
  });

  if (!document.getElementById("_spinner_style")) {
    const style = document.createElement("style");
    style.id = "_spinner_style";
    style.textContent = "@keyframes spin{to{transform:rotate(360deg)}}";
    document.head.appendChild(style);
  }

  return s;
}

export function showPinModal(title, placeholder = "Пароль/PIN-код") {
  return new Promise((resolve) => {
    const input = el("input", {
      type: "password",
      placeholder: placeholder,
      style: "width:100%;padding:12px;background:rgba(255,255,255,0.05);border:1px solid rgba(255,255,255,0.1);border-radius:8px;color:#fff;font-size:16px;margin-bottom:16px;outline:none;"
    });

    const submitBtn = el("button", {
      class: "btn-primary",
      style: "flex:1;padding:12px;font-size:14px;border-radius:8px;cursor:pointer;"
    }, "Подтвердить");

    const cancelBtn = el("button", {
      class: "btn-secondary",
      style: "flex:1;padding:12px;font-size:14px;border-radius:8px;cursor:pointer;"
    }, "Отмена");

    const modalBox = el("div", {
      style: "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:24px;width:100%;max-width:400px;box-shadow:0 8px 32px rgba(0,0,0,0.5);display:flex;flex-direction:column;"
    },
      el("h3", { style: "font-size:18px;margin-bottom:12px;color:#fff;font-weight:600;line-height:1.4;" }, title),
      input,
      el("div", { style: "display:flex;gap:12px;" }, cancelBtn, submitBtn)
    );

    const overlay = el("div", {
      style: "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:9999;padding:16px;"
    }, modalBox);

    const cleanUp = () => {
      document.body.removeChild(overlay);
    };

    submitBtn.addEventListener("click", () => {
      const val = input.value;
      cleanUp();
      resolve(val || null);
    });

    cancelBtn.addEventListener("click", () => {
      cleanUp();
      resolve(null);
    });

    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        submitBtn.click();
      } else if (e.key === "Escape") {
        cancelBtn.click();
      }
    });

    document.body.appendChild(overlay);
    input.focus();
  });
}

export function showSafetyNumberModal(title, safetyNumber) {
  return new Promise((resolve) => {
    const closeBtn = el("button", {
      class: "btn-primary",
      style: "width:100%;padding:12px;font-size:14px;border-radius:8px;cursor:pointer;margin-top:16px;"
    }, "Закрыть");

    const numEl = el("div", {
      style: "background:rgba(0,0,0,0.25);border:1px solid rgba(255,255,255,0.05);border-radius:8px;padding:16px;color:#22c55e;font-family:monospace;font-size:18px;text-align:center;word-spacing:10px;line-height:1.6;margin-bottom:12px;letter-spacing:1px;user-select:all;"
    }, safetyNumber);

    const modalBox = el("div", {
      style: "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:24px;width:100%;max-width:440px;box-shadow:0 8px 32px rgba(0,0,0,0.5);display:flex;flex-direction:column;"
    },
      el("h3", { style: "font-size:18px;margin-bottom:12px;color:#fff;font-weight:600;line-height:1.4;text-align:center;" }, title),
      el("p", { style: "font-size:13px;color:#a0a0b5;margin-bottom:16px;line-height:1.5;text-align:center;" }, "Сравните этот код безопасности с кодом вашего собеседника. Если они совпадают, сквозное шифрование на 100% защищено от перехвата (MitM)."),
      numEl,
      closeBtn
    );

    const overlay = el("div", {
      style: "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:9999;padding:16px;"
    }, modalBox);

    const cleanUp = () => {
      document.body.removeChild(overlay);
      resolve();
    };

    closeBtn.addEventListener("click", cleanUp);
    overlay.addEventListener("click", (e) => {
      if (e.target === overlay) cleanUp();
    });

    document.body.appendChild(overlay);
  });
}

export function showConfirmModal(title, text, confirmText = "Подтвердить", cancelText = "Отмена", isDanger = false) {
  return new Promise((resolve) => {
    const cancelBtn = el("button", {
      class: "btn-secondary",
      style: "flex:1;padding:12px;font-size:14px;border-radius:8px;cursor:pointer;margin-right:8px;background:rgba(255,255,255,0.05);color:#fff;border:1px solid rgba(255,255,255,0.1);"
    }, cancelText);

    const confirmBtn = el("button", {
      class: "btn-primary",
      style: `flex:1;padding:12px;font-size:14px;border-radius:8px;cursor:pointer;background:${isDanger ? "#ef4444" : "#22c55e"};color:#fff;border:none;`
    }, confirmText);

    const modalBox = el("div", {
      style: "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:24px;width:100%;max-width:440px;box-shadow:0 8px 32px rgba(0,0,0,0.5);display:flex;flex-direction:column;"
    },
      el("h3", { style: "font-size:18px;margin-bottom:12px;color:#fff;font-weight:600;line-height:1.4;text-align:center;" }, title),
      el("p", { style: "font-size:13px;color:#a0a0b5;margin-bottom:20px;line-height:1.5;text-align:center;" }, text),
      el("div", { style: "display:flex;justify-content:space-between;" }, cancelBtn, confirmBtn)
    );

    const overlay = el("div", {
      style: "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:9999;padding:16px;"
    }, modalBox);

    const closeWithResult = (result) => {
      document.body.removeChild(overlay);
      resolve(result);
    };

    confirmBtn.addEventListener("click", () => closeWithResult(true));
    cancelBtn.addEventListener("click", () => closeWithResult(false));
    overlay.addEventListener("click", (e) => {
      if (e.target === overlay) closeWithResult(false);
    });

    document.body.appendChild(overlay);
  });
}

export function showDeleteChatConfirmModal() {
  return new Promise((resolve) => {
    const checkboxId = "delete-for-everyone-chk";
    const checkbox = el("input", {
      type: "checkbox",
      id: checkboxId,
      style: "margin-right:8px;cursor:pointer;width:16px;height:16px;accent-color:#ef4444;"
    });

    const checkboxLabel = el("label", {
      for: checkboxId,
      style: "font-size:13px;color:#e2e2e9;cursor:pointer;user-select:none;display:flex;align-items:center;"
    }, checkbox, "Удалить также для собеседника");

    const checkboxRow = el("div", {
      style: "display:flex;align-items:center;margin-bottom:20px;padding:8px;background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.05);border-radius:8px;"
    }, checkboxLabel);

    const cancelBtn = el("button", {
      class: "btn-secondary",
      style: "flex:1;padding:12px;font-size:14px;border-radius:8px;cursor:pointer;margin-right:8px;background:rgba(255,255,255,0.05);color:#fff;border:1px solid rgba(255,255,255,0.1);"
    }, "Отмена");

    const confirmBtn = el("button", {
      class: "btn-primary",
      style: "flex:1;padding:12px;font-size:14px;border-radius:8px;cursor:pointer;background:#ef4444;color:#fff;border:none;"
    }, "Удалить");

    const modalBox = el("div", {
      style: "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:24px;width:100%;max-width:440px;box-shadow:0 8px 32px rgba(0,0,0,0.5);display:flex;flex-direction:column;"
    },
      el("h3", { style: "font-size:18px;margin-bottom:12px;color:#fff;font-weight:600;line-height:1.4;text-align:center;" }, "Удалить чат"),
      el("p", { style: "font-size:13px;color:#a0a0b5;margin-bottom:16px;line-height:1.5;text-align:center;" }, 
        "Вы действительно хотите удалить этот чат и все сообщения? Это также сбросит криптографическую сессию с пользователем."
      ),
      checkboxRow,
      el("div", { style: "display:flex;justify-content:space-between;" }, cancelBtn, confirmBtn)
    );

    const overlay = el("div", {
      style: "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:9999;padding:16px;"
    }, modalBox);

    const closeWithResult = (confirmed) => {
      document.body.removeChild(overlay);
      resolve({ confirmed, deleteForEveryone: checkbox.checked });
    };

    confirmBtn.addEventListener("click", () => closeWithResult(true));
    cancelBtn.addEventListener("click", () => closeWithResult(false));
    overlay.addEventListener("click", (e) => {
      if (e.target === overlay) closeWithResult(false);
    });

    document.body.appendChild(overlay);
  });
}

export function showPromptModal(title, placeholder, defaultValue = "") {
  return new Promise((resolve) => {
    const input = el("input", {
      type: "text",
      value: defaultValue,
      placeholder: placeholder,
      style: "width:100%;padding:12px;font-size:14px;border-radius:8px;background:#1a1a2e;color:#fff;border:1px solid rgba(255,255,255,0.1);margin-bottom:20px;outline:none;"
    });

    const cancelBtn = el("button", {
      class: "btn-secondary",
      style: "flex:1;padding:12px;font-size:14px;border-radius:8px;cursor:pointer;margin-right:8px;background:rgba(255,255,255,0.05);color:#fff;border:1px solid rgba(255,255,255,0.1);"
    }, "Отмена");

    const confirmBtn = el("button", {
      class: "btn-primary",
      style: "flex:1;padding:12px;font-size:14px;border-radius:8px;cursor:pointer;background:#22c55e;color:#fff;border:none;"
    }, "Сохранить");

    const modalBox = el("div", {
      style: "background:#1e1e24;border:1px solid rgba(255,255,255,0.1);border-radius:16px;padding:24px;width:100%;max-width:440px;box-shadow:0 8px 32px rgba(0,0,0,0.5);display:flex;flex-direction:column;"
    },
      el("h3", { style: "font-size:18px;margin-bottom:16px;color:#fff;font-weight:600;line-height:1.4;text-align:center;" }, title),
      input,
      el("div", { style: "display:flex;justify-content:space-between;" }, cancelBtn, confirmBtn)
    );

    const overlay = el("div", {
      style: "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:9999;padding:16px;"
    }, modalBox);

    const closeWithResult = (value) => {
      document.body.removeChild(overlay);
      resolve(value);
    };

    confirmBtn.addEventListener("click", () => {
      closeWithResult(input.value.trim());
    });
    cancelBtn.addEventListener("click", () => closeWithResult(null));
    overlay.addEventListener("click", (e) => {
      if (e.target === overlay) closeWithResult(null);
    });

    document.body.appendChild(overlay);
    input.focus();
    input.select();
  });
}
