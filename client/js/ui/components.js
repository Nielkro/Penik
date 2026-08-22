import { decodeKey, decryptFileChaCha20 } from "../crypto.js";
import { getToken } from "../api.js";
import { getCachedMedia, saveCachedMedia, getAllContacts, getAllGroups } from "../storage.js";
import { sendGroupMessage } from "../groups.js";
import { sendDirectMessageToUser } from "./chat.js";

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

export function showFullscreenImage(url, altText = "") {
  showFullscreenMedia(url, false);
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

// Match http(s) and www. URLs; trailing punctuation is stripped from the href.
const MSG_URL_RE = /(?:https?:\/\/|www\.)[^\s<>"']+/gi;

/**
 * Fill a .msg-text element with plain text, turning http(s) and www. URLs into safe
 * <a class="msg-link"> anchors (no innerHTML — plaintext is never parsed as HTML).
 */
export function setMsgTextContent(el, text) {
  el.replaceChildren();
  if (!text) return;

  const s = String(text).trim();

  if (s.startsWith("{")) {
    try {
      const parsed = JSON.parse(s);
      if (parsed && parsed.type === "fwd") {
        el.style.display = "block";
        el.style.width = "100%";
        const header = document.createElement("div");
        header.style.cssText = "font-size:12px;font-weight:600;color:var(--accent);margin-bottom:4px;display:flex;align-items:center;gap:4px;";
        header.textContent = "↪ Переслано от " + (parsed.from || "неизвестного");
        el.appendChild(header);

        const bodyEl = document.createElement("div");
        setMsgTextContent(bodyEl, parsed.text || "");
        el.appendChild(bodyEl);
        return;
      }
      if (parsed && (parsed.type === "file" || parsed.file) && (parsed.file?.url || parsed.url)) {
        if (!parsed.file) parsed.file = { ...parsed };
        if (parsed.fwd_from) {
          const header = document.createElement("div");
          header.style.cssText = "font-size:12px;font-weight:600;color:var(--accent);margin-bottom:4px;display:flex;align-items:center;gap:4px;";
          header.textContent = "↪ Переслано от " + parsed.fwd_from;
          el.appendChild(header);
        }
        renderFileCard(el, parsed);
        return;
      }
    } catch (e) {
      console.error("[setMsgTextContent] JSON parse error:", e);
    }
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
      const href = url.toLowerCase().startsWith("www.") ? "https://" + url : url;
      const a = document.createElement("a");
      a.className = "msg-link";
      a.href = href;
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

// describeUndecodableVideo reads back the already-decrypted blob and returns a
// human-readable codec name, or "" when the container tells us nothing useful.
async function describeUndecodableVideo(blobUrl) {
  try {
    const resp = await fetch(blobUrl);
    const bytes = new Uint8Array(await resp.arrayBuffer());
    return MP4_VIDEO_CODECS[sniffMp4VideoCodec(bytes)] || "";
  } catch (e) {
    return "";
  }
}

function renderFileCard(container, fileMsg) {
  const f = fileMsg.file;
  const isImage = (f.mime || "").startsWith("image/");
  const isVideo = (f.mime || "").startsWith("video/");

  if (isImage) {
    const fileCard = el("div", { class: "msg-file-card", style: "display:flex;flex-direction:column;gap:4px;width:100%;padding:0;" });
    const cachedBlobUrl = decryptedBlobCache.get(f.url);
    const initialSrc = cachedBlobUrl || f.thumb || "";

    const mediaWrap = el("div", { style: "position:relative;display:inline-block;max-width:100%;border-radius:14px;overflow:hidden;" });

    const imgEl = el("img", {
      src: initialSrc,
      alt: f.name || "Изображение",
      style: "display:block;width:100%;max-width:360px;max-height:560px;object-fit:contain;border-radius:14px;cursor:pointer;background:rgba(255,255,255,0.05);transition:opacity 0.2s;"
    });
    imgEl.addEventListener("click", (e) => {
      e.stopPropagation();
      downloadAndDecryptFile(f, true);
    });
    mediaWrap.appendChild(imgEl);
    fileCard.appendChild(mediaWrap);

    if (!cachedBlobUrl) {
      downloadAndDecryptFile(f, false, null, true).then((fullBlobUrl) => {
        if (fullBlobUrl && document.body.contains(imgEl)) {
          imgEl.src = fullBlobUrl;
        }
      }).catch(() => {/* Keep thumbnail fallback */});
    }

    if (fileMsg.text) {
      const captionEl = el("div", { style: "margin-top:2px;font-size:14px;word-break:break-word;padding:4px 6px;" }, fileMsg.text);
      fileCard.appendChild(captionEl);
    }

    container.appendChild(fileCard);
    return;
  }

  if (isVideo) {
    const fileCard = el("div", { class: "msg-file-card", style: "display:flex;flex-direction:column;gap:4px;width:100%;padding:0;position:relative;" });
    const cachedBlobUrl = decryptedBlobCache.get(f.url);

    const videoEl = el("video", {
      muted: true,
      playsinline: true,
      // Without an explicit preload the element paints nothing until playback
      // starts, so a card with no poster stays an empty rectangle.
      preload: "metadata",
      style: "display:block;width:100%;max-width:360px;min-height:180px;max-height:560px;border-radius:16px;background:rgba(255,255,255,0.05);cursor:pointer;object-fit:contain;"
    });

    if (f.thumb) {
      videoEl.poster = f.thumb.startsWith("data:") ? f.thumb : "data:image/jpeg;base64," + f.thumb;
    }
    videoEl.addEventListener("loadedmetadata", () => {
      if (!f.thumb && videoEl.duration) videoEl.currentTime = 0;
    });

    // Appended up front so a fallback card replacing it later cannot be undone
    // by a deferred append.
    fileCard.appendChild(videoEl);

    // An unplayable video element has no intrinsic size, so an overlay badge
    // would collapse to a sliver — swap the whole element for a document card.
    let noteEl = null;
    const showFallbackCard = (note, downloadable) => {
      videoEl.remove();
      if (noteEl) {
        noteEl.textContent = note;
        return;
      }
      noteEl = el("div", { style: "font-size:11px;line-height:1.4;color:#ffb4ab;" }, note);
      const card = el("div", {
        style: "display:flex;flex-direction:column;gap:6px;width:260px;max-width:100%;box-sizing:border-box;" +
          "background:rgba(255,255,255,0.06);padding:10px 12px;border-radius:14px;" +
          (downloadable ? "cursor:pointer;" : "")
      },
        el("div", { style: "display:flex;align-items:center;gap:10px;min-width:0;" },
          el("span", { style: "font-size:24px;flex-shrink:0;" }, "🎬"),
          el("div", { style: "display:flex;flex-direction:column;min-width:0;flex:1;" },
            el("span", { style: "font-weight:600;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#fff;" }, f.name || "Видео"),
            el("span", { style: "font-size:11px;color:var(--text-muted);" }, formatFileSize(f.size || 0))
          )
        ),
        noteEl
      );
      if (downloadable) {
        card.addEventListener("click", (e) => {
          e.stopPropagation();
          downloadAndDecryptFile(f, false);
        });
      }
      fileCard.insertBefore(card, fileCard.firstChild);
    };

    // A browser without a decoder for this codec still parses the container and
    // plays the audio track, but reports videoWidth 0 and paints black. Name the
    // codec so the cause is obvious, and offer the file for download.
    videoEl.addEventListener("loadeddata", () => {
      if (videoEl.videoWidth) return;
      const src = videoEl.src;
      showFallbackCard("Браузер не может декодировать это видео. Нажмите, чтобы скачать.", true);
      describeUndecodableVideo(src).then((codec) => {
        if (codec && noteEl) {
          noteEl.textContent = `Видео в ${codec} — браузер этот кодек не поддерживает. Нажмите, чтобы скачать.`;
        }
      });
    });
    videoEl.addEventListener("error", () => {
      showFallbackCard("Не удалось воспроизвести видео. Нажмите, чтобы скачать.", true);
    });

    if (cachedBlobUrl) {
      videoEl.src = cachedBlobUrl;
    } else {
      // Decrypting every video in the history at once blocks the main thread and
      // freezes the page, so the fetch is deferred until the card is scrolled to.
      const startLoad = () => {
        downloadAndDecryptFile(f, false, null, true).then((fullBlobUrl) => {
          if (fullBlobUrl) {
            videoEl.src = fullBlobUrl;
          }
        }).catch((err) => {
          console.error("[video] Progressive load failed error details:", err);
          showFallbackCard(
            err && err.code === "expired"
              ? "Видео больше недоступно на CDN."
              : "Ошибка расшифровки видео.",
            false
          );
        });
      };
      if (typeof IntersectionObserver === "function") {
        const observer = new IntersectionObserver((entries) => {
          if (entries.some((entry) => entry.isIntersecting)) {
            observer.disconnect();
            startLoad();
          }
        }, { rootMargin: "200px" });
        observer.observe(videoEl);
      } else {
        startLoad();
      }
    }

    let hoverTimer = null;

    const stopPreview = () => {
      if (hoverTimer) {
        clearTimeout(hoverTimer);
        hoverTimer = null;
      }
      videoEl.pause();
      try { videoEl.currentTime = 0; } catch (e) {}
      videoEl.muted = true;
    };

    videoEl.addEventListener("mouseenter", () => {
      if (!videoEl.src) return;
      videoEl.muted = true;
      try { videoEl.currentTime = 0; } catch (e) {}
      videoEl.play().then(() => {
        hoverTimer = setTimeout(() => {
          stopPreview();
        }, 5000);
      }).catch(() => {});
    });

    videoEl.addEventListener("mouseleave", () => {
      stopPreview();
    });

    // Click opens Telegram-style lightbox player
    videoEl.addEventListener("click", (e) => {
      e.stopPropagation();
      stopPreview();
      if (videoEl.src) {
        showFullscreenMedia(videoEl.src, true);
      }
    });

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

// Blob URLs pin their whole decrypted payload in memory until revoked, so the
// cache is bounded: the oldest entry is revoked once the cap is reached.
const MAX_CACHED_BLOB_URLS = 40;
export const decryptedBlobCache = new Map();

// cacheBlobUrl stores a blob URL and revokes the eldest one when the cache is
// full, releasing the memory the browser holds for it.
function cacheBlobUrl(sourceUrl, blobUrl) {
  const previous = decryptedBlobCache.get(sourceUrl);
  if (previous && previous !== blobUrl) {
    try { URL.revokeObjectURL(previous); } catch (e) {/* already revoked */}
  }
  decryptedBlobCache.set(sourceUrl, blobUrl);
  while (decryptedBlobCache.size > MAX_CACHED_BLOB_URLS) {
    const oldest = decryptedBlobCache.keys().next();
    if (oldest.done || oldest.value === sourceUrl) break;
    const staleUrl = decryptedBlobCache.get(oldest.value);
    decryptedBlobCache.delete(oldest.value);
    if (window._streamMediaCache) window._streamMediaCache.delete(oldest.value);
    try { URL.revokeObjectURL(staleUrl); } catch (e) {/* already revoked */}
  }
}

// revokeCachedBlobUrls releases every cached blob URL — call on logout so the
// decrypted attachments do not stay alive in the page.
export function revokeCachedBlobUrls() {
  for (const url of decryptedBlobCache.values()) {
    try { URL.revokeObjectURL(url); } catch (e) {/* already revoked */}
  }
  decryptedBlobCache.clear();
  if (window._streamMediaCache) window._streamMediaCache.clear();
}

// Human-readable names for the MP4 sample entry codes a video track can carry.
// Only avc1/avc3 (H.264) decode everywhere; HEVC needs hardware support the
// browser may not expose, and AV1 needs a recent build.
const MP4_VIDEO_CODECS = {
  avc1: "H.264",
  avc3: "H.264",
  hvc1: "H.265 (HEVC)",
  hev1: "H.265 (HEVC)",
  av01: "AV1",
  vp09: "VP9",
  mp4v: "MPEG-4 Part 2",
};

// sniffMp4VideoCodec walks moov > trak > mdia > minf > stbl > stsd and returns
// the first video sample entry code it finds, so a track the browser refuses to
// decode can be named in the error message instead of failing silently.
function sniffMp4VideoCodec(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const containers = new Set(["moov", "trak", "mdia", "minf", "stbl"]);

  const walk = (start, end, depth) => {
    let offset = start;
    while (offset + 8 <= end && depth < 8) {
      const size = view.getUint32(offset);
      let type = "";
      for (let i = 0; i < 4; i++) type += String.fromCharCode(view.getUint8(offset + 4 + i));
      // A size of 0 means "until end of file"; 1 means a 64-bit size follows,
      // which only appears on mdat and is never on the path to stsd.
      const boxEnd = size === 0 ? end : offset + size;
      if (size === 1 || boxEnd <= offset || boxEnd > end) return "";

      if (type === "stsd") {
        // FullBox header (4) + entry_count (4), then size (4) + code (4).
        const entry = offset + 16;
        if (entry + 8 > boxEnd) return "";
        let code = "";
        for (let i = 0; i < 4; i++) code += String.fromCharCode(view.getUint8(entry + 4 + i));
        if (MP4_VIDEO_CODECS[code]) return code;
      } else if (containers.has(type)) {
        const found = walk(offset + 8, boxEnd, depth + 1);
        if (found) return found;
      }
      offset = boxEnd;
    }
    return "";
  };

  return walk(0, bytes.byteLength, 0);
}

// An encrypted payload always starts with a 12-byte random nonce, so a body that
// begins with HTML markup is never our file — it is a VK document page the proxy
// could not resolve to a direct link, served with 200 OK. Without this check the
// markup is fed to decryptFileChaCha20 and surfaces as a misleading
// "cannot be decrypted" error.
function looksLikeHTMLPage(bytes) {
  if (bytes.length < 14) return false;
  const head = new TextDecoder("latin1").decode(bytes.subarray(0, 512)).trimStart().toLowerCase();
  return head.startsWith("<!doctype html") || head.startsWith("<html");
}

// AttachmentError marks a download failure the UI can react to specifically.
// `code === "expired"` means VK no longer serves the file, so retrying is
// pointless and the card should offer a plain download link instead.
class AttachmentError extends Error {
  /**
   * @param {string} message
   * @param {string} [code]
   */
  constructor(message, code) {
    super(message);
    this.name = "AttachmentError";
    this.code = code;
  }
}

async function downloadAndDecryptFile(fileInfo, isPreviewClick = false, btn = null, isBackgroundFetch = false) {
  if (btn) {
    btn.disabled = true;
    btn.textContent = "Загрузка…";
  }
  try {
    let blobUrl = decryptedBlobCache.get(fileInfo.url);
    if (!blobUrl) {
      // Check persistent IndexedDB media cache first
      try {
        blobUrl = await getCachedMedia(fileInfo.url);
      } catch (e) {}

      if (!blobUrl) {
        const token = getToken();
        const proxyUrl = `/api/v1/attachments/proxy?url=${encodeURIComponent(fileInfo.url)}`;
        /** @type {Record<string, string>} */
        const headers = {};
        if (token) headers['Authorization'] = `Bearer ${token}`;

        const resp = await fetch(proxyUrl, { headers });
        if (!resp.ok) {
          let detail = `HTTP ${resp.status}`;
          try {
            const errBody = await resp.json();
            if (errBody && errBody.error) detail = errBody.error;
          } catch (e) {/* non-JSON error body */}
          throw new AttachmentError(detail, resp.status === 410 ? "expired" : undefined);
        }
        const encryptedBuf = await resp.arrayBuffer();
        const encryptedBytes = new Uint8Array(encryptedBuf);

        if (looksLikeHTMLPage(encryptedBytes)) {
          throw new AttachmentError("VK returned an HTML page instead of the file", "expired");
        }

        const keyBytes = decodeKey(fileInfo.key);
        const decryptedBytes = await decryptFileChaCha20(encryptedBytes, keyBytes);

        const blob = new Blob([/** @type {BlobPart} */ (decryptedBytes)], { type: fileInfo.mime || "application/octet-stream" });
        blobUrl = URL.createObjectURL(blob);
        saveCachedMedia(fileInfo.url, blob, fileInfo.mime).catch(() => {});
      }

      if (!window._streamMediaCache) window._streamMediaCache = new Map();
      window._streamMediaCache.set(fileInfo.url, blobUrl);

      cacheBlobUrl(fileInfo.url, blobUrl);
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
      showToast(
        err && err.code === "expired"
          ? "Файл больше недоступен на CDN — попросите отправить его заново"
          : "Ошибка скачивания или расшифровки файла",
        "error"
      );
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
 * Right-click / long-press on a message bubble → mini menu with "Копировать", "Ответить", "Переслать", "Удалить".
 * `getText` is a string or a function returning the plaintext to copy.
 */
export function wireMsgCopy(bubble, getText, onReply, onDelete, onForward) {
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
    showMsgActionMenu(e.clientX, e.clientY, doCopy, onReply, onDelete, onForward);
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
        showMsgActionMenu(startX, startY, doCopy, onReply, onDelete, onForward);
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

function showMsgActionMenu(x, y, onCopy, onReply, onDelete, onForward) {
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

  if (onForward) {
    const fwdItem = el("button", { type: "button", class: "msg-action-item" }, "Переслать");
    fwdItem.addEventListener("click", (e) => {
      e.stopPropagation();
      menu.remove();
      onForward();
    });
    menu.appendChild(fwdItem);
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
  };
  // Defer so the opening event does not immediately close the menu.
  setTimeout(() => {
    document.addEventListener("pointerdown", close, true);
  }, 0);
}

export async function showForwardModal(rawMsgText, senderName) {
  const contacts = await getAllContacts();
  const groups = await getAllGroups();

  document.getElementById("forward-modal")?.remove();

  const modal = el("div", {
    id: "forward-modal",
    class: "modal-backdrop",
    style: "position:fixed;inset:0;background:rgba(0,0,0,0.65);z-index:10005;display:flex;align-items:center;justify-content:center;padding:16px;"
  });

  const card = el("div", {
    class: "modal-card",
    style: "background:var(--panel);border:1px solid var(--border);border-radius:16px;width:100%;max-width:380px;max-height:80vh;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 12px 32px rgba(0,0,0,0.5);"
  });

  const header = el("div", {
    style: "display:flex;align-items:center;justify-content:space-between;padding:14px 16px;border-bottom:1px solid var(--border);"
  },
    el("span", { style: "font-size:16px;font-weight:700;color:#fff;" }, "Переслать сообщение"),
    el("button", {
      type: "button",
      style: "background:none;border:none;color:var(--text-muted);cursor:pointer;font-size:18px;padding:4px;",
      onclick: () => modal.remove()
    }, "✕")
  );

  const searchInput = el("input", {
    type: "text",
    placeholder: "Поиск чата...",
    class: "search-input",
    style: "margin:12px 16px;width:calc(100% - 32px);box-sizing:border-box;"
  });

  const listContainer = el("div", {
    style: "flex:1;overflow-y:auto;padding:0 8px 12px;"
  });

  const renderList = (filterText = "") => {
    listContainer.replaceChildren();
    const query = filterText.trim().toLowerCase();

    const items = [];
    for (const c of contacts) {
      const name = c.name || c.nickname || `Пользователь #${c.user_id}`;
      if (!query || name.toLowerCase().includes(query)) {
        items.push({ type: "user", id: c.user_id, name, item: c });
      }
    }
    for (const g of groups) {
      const name = g.name || `Группа #${g.id}`;
      if (!query || name.toLowerCase().includes(query)) {
        items.push({ type: "group", id: g.id, name, item: g });
      }
    }

    if (items.length === 0) {
      listContainer.appendChild(
        el("div", { style: "padding:24px;text-align:center;color:var(--text-dim);font-size:14px;" }, "Чаты не найдены")
      );
      return;
    }

    for (const target of items) {
      const itemRow = el("div", {
        style: "display:flex;align-items:center;gap:12px;padding:10px 12px;border-radius:10px;cursor:pointer;transition:background 0.15s;",
        onclick: async () => {
          modal.remove();
          await executeForward(target, rawMsgText, senderName);
        }
      });
      itemRow.addEventListener("mouseenter", () => itemRow.style.background = "rgba(255,255,255,0.06)");
      itemRow.addEventListener("mouseleave", () => itemRow.style.background = "transparent");

      const avNode = target.type === "group" ? groupAvatar(target.item, 40) : avatar(target.item, 40);
      const nameNode = el("span", { style: "font-weight:600;font-size:15px;color:#fff;" }, target.name);

      itemRow.append(avNode, nameNode);
      listContainer.appendChild(itemRow);
    }
  };

  searchInput.addEventListener("input", (e) => renderList(e.target.value));
  renderList();

  card.append(header, searchInput, listContainer);
  modal.appendChild(card);
  document.body.appendChild(modal);

  modal.addEventListener("click", (e) => {
    if (e.target === modal) modal.remove();
  });
}

async function executeForward(target, rawMsgText, senderName) {
  let forwardedPayload;
  if (rawMsgText.startsWith("{")) {
    try {
      const parsed = JSON.parse(rawMsgText);
      if (parsed.type === "file" || parsed.file) {
        parsed.fwd_from = parsed.fwd_from || senderName;
        forwardedPayload = JSON.stringify(parsed);
      } else if (parsed.type === "fwd") {
        forwardedPayload = JSON.stringify({ type: "fwd", from: parsed.from || senderName, text: parsed.text });
      } else {
        forwardedPayload = JSON.stringify({ type: "fwd", from: senderName, text: rawMsgText });
      }
    } catch {
      forwardedPayload = JSON.stringify({ type: "fwd", from: senderName, text: rawMsgText });
    }
  } else {
    forwardedPayload = JSON.stringify({ type: "fwd", from: senderName, text: rawMsgText });
  }

  try {
    if (target.type === "group") {
      await sendGroupMessage(target.id, forwardedPayload);
    } else {
      await sendDirectMessageToUser(target.id, forwardedPayload);
    }
    showToast("Сообщение переслано");
  } catch (err) {
    console.error("Forward failed:", err);
    showToast("Не удалось переслать сообщение", "error");
  }
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
  const yesterday = new Date(today.getTime() - 86400000);
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
  const yesterday = new Date(today.getTime() - 86400000);
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

export function showDeleteChatConfirmModal(title = "Удалить чат", text = "Вы действительно хотите удалить этот чат и все сообщения? Это также сбросит криптографическую сессию с пользователем.") {
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
      el("h3", { style: "font-size:18px;margin-bottom:12px;color:#fff;font-weight:600;line-height:1.4;text-align:center;" }, title),
      el("p", { style: "font-size:13px;color:#a0a0b5;margin-bottom:16px;line-height:1.5;text-align:center;" }, text),
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

    const closeWithResult = (result) => {
      document.body.removeChild(overlay);
      resolve(result);
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

export function showFullscreenMedia(src, isVideo = false) {
  const backdrop = el("div", {
    style: "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);backdrop-filter:blur(8px);display:flex;align-items:center;justify-content:center;z-index:10000;padding:20px;box-sizing:border-box;"
  });

  const contentWrap = el("div", {
    style: "position:relative;max-width:90vw;max-height:90vh;display:flex;align-items:center;justify-content:center;box-sizing:border-box;"
  });

  let mediaEl;
  if (isVideo) {
    mediaEl = el("video", {
      src: src,
      autoplay: true,
      playsinline: true,
      style: "display:block;max-width:100%;max-height:82vh;object-fit:contain;border-radius:12px;box-shadow:0 12px 48px rgba(0,0,0,0.6);cursor:pointer;"
    });

    const iconPause = svgIcon("M6 4h4v16H6zm8 0h4v16h-4z", 16, "#ffffff", 1);
    iconPause.setAttribute("fill", "#ffffff");
    const iconPlay = svgIcon("M5 3l14 9-14 9V3z", 16, "#ffffff", 1);
    iconPlay.setAttribute("fill", "#ffffff");

    const iconVolume = svgIcon("M11 5L6 9H2v6h4l5 4V5zM15.54 8.46a5 5 0 0 1 0 7.07", 18, "#ffffff", 2);
    const iconMute = svgIcon("M11 5L6 9H2v6h4l5 4V5zM23 9l-6 6M17 9l6 6", 18, "#ffffff", 2);

    const playPauseBtn = el("button", {
      style: "background:none;border:none;color:#fff;cursor:pointer;padding:6px;display:flex;align-items:center;justify-content:center;border-radius:50%;"
    }, iconPause);

    const timeCurrent = el("span", { style: "font-size:12px;color:#e2e2e9;font-family:monospace;min-width:36px;" }, "0:00");
    const timeTotal = el("span", { style: "font-size:12px;color:rgba(255,255,255,0.6);font-family:monospace;min-width:36px;" }, "0:00");

    const seekRange = el("input", {
      type: "range",
      min: "0",
      max: "100",
      value: "0",
      step: "0.1",
      style: "flex:1;height:4px;accent-color:#22c55e;cursor:pointer;margin:0 8px;"
    });

    const muteBtn = el("button", {
      style: "background:none;border:none;color:#fff;cursor:pointer;padding:6px;display:flex;align-items:center;justify-content:center;border-radius:50%;"
    }, iconVolume);

    const formatSecs = (sec) => {
      if (isNaN(sec) || sec < 0) return "0:00";
      const m = Math.floor(sec / 60);
      const s = Math.floor(sec % 60);
      return `${m}:${s < 10 ? "0" : ""}${s}`;
    };

    const updatePlayState = () => {
      playPauseBtn.replaceChildren(mediaEl.paused ? iconPlay : iconPause);
    };

    playPauseBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      if (mediaEl.paused) {
        mediaEl.play();
      } else {
        mediaEl.pause();
      }
      updatePlayState();
    });

    mediaEl.addEventListener("click", (e) => {
      e.stopPropagation();
      playPauseBtn.click();
    });

    mediaEl.addEventListener("play", updatePlayState);
    mediaEl.addEventListener("pause", updatePlayState);

    mediaEl.addEventListener("timeupdate", () => {
      if (mediaEl.duration) {
        const pct = (mediaEl.currentTime / mediaEl.duration) * 100;
        seekRange.value = pct;
        timeCurrent.textContent = formatSecs(mediaEl.currentTime);
      }
    });

    mediaEl.addEventListener("loadedmetadata", () => {
      timeTotal.textContent = formatSecs(mediaEl.duration);
    });

    seekRange.addEventListener("input", (e) => {
      e.stopPropagation();
      if (mediaEl.duration) {
        mediaEl.currentTime = (seekRange.value / 100) * mediaEl.duration;
      }
    });

    muteBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      mediaEl.muted = !mediaEl.muted;
      muteBtn.replaceChildren(mediaEl.muted ? iconMute : iconVolume);
    });

    const controlsBar = el("div", {
      style: "width:100%;max-width:540px;background:rgba(20,20,28,0.9);backdrop-filter:blur(12px);border:1px solid rgba(255,255,255,0.1);padding:8px 16px;border-radius:18px;display:flex;align-items:center;gap:8px;box-shadow:0 8px 32px rgba(0,0,0,0.5);margin-top:10px;box-sizing:border-box;"
    }, playPauseBtn, timeCurrent, seekRange, timeTotal, muteBtn);

    contentWrap.style.flexDirection = "column";
    contentWrap.appendChild(mediaEl);
    contentWrap.appendChild(controlsBar);
  } else {
    mediaEl = el("img", {
      src: src,
      style: "max-width:100%;max-height:88vh;object-fit:contain;border-radius:12px;box-shadow:0 12px 48px rgba(0,0,0,0.6);"
    });
    contentWrap.appendChild(mediaEl);
  }

  const closeBtn = el("button", {
    style: "position:absolute;top:16px;right:20px;background:rgba(0,0,0,0.5);border:none;color:#fff;font-size:24px;width:40px;height:40px;border-radius:50%;cursor:pointer;display:flex;align-items:center;justify-content:center;z-index:10003;"
  }, "✕");

  const close = () => {
    if (document.body.contains(backdrop)) {
      document.body.removeChild(backdrop);
    }
  };

  closeBtn.addEventListener("click", close);
  backdrop.addEventListener("click", (e) => {
    if (e.target === backdrop || e.target === contentWrap) close();
  });

  const onKeyDown = (e) => {
    if (e.key === "Escape") {
      close();
      document.removeEventListener("keydown", onKeyDown);
    }
  };
  document.addEventListener("keydown", onKeyDown);

  backdrop.append(closeBtn, contentWrap);
  document.body.appendChild(backdrop);
}
