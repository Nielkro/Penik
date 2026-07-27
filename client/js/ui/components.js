// DOM factory

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

// Avatar

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
  }

  if (avatarUrl) {
    const img = el("img", {
      src: avatarUrl,
      alt: user.name || user.username || "?",
      style: `width:${size}px;height:${size}px;object-fit:cover;`,
    });
    img.onerror = () => {
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

export function formatTime(ts) {
  const d = new Date(typeof ts === "number" && ts < 1e12 ? ts * 1000 : ts);
  if (isNaN(d.getTime())) return "";
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
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
