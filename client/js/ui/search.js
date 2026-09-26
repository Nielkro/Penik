import { apiGet } from "../api.js";
import { navigate, getCurrentUser } from "../app.js";
import { saveContact } from "../storage.js";
import { avatar, el, showToast, spinner } from "./components.js";

export function renderSearch(container) {
  container.innerHTML = "";

  const header = el("div", { class: "search-header" },
    el("button", { class: "icon-btn", onclick: () => navigate("#chats"), title: "Назад" }, "←"),
    el("h2", { class: "search-title" }, "Поиск людей")
  );

  const searchInput = el("input", {
    type: "search",
    class: "search-input",
    placeholder: "Поиск по имени или @нику…",
    autofocus: true,
  });

  const resultsEl = el("ul", { class: "search-results" });
  const statusEl = el("div", { class: "search-status" });

  container.appendChild(el("div", { class: "search-wrap" },
    header,
    el("div", { class: "search-bar" }, searchInput),
    statusEl,
    resultsEl
  ));

  let debounceTimer = null;
  let lastQuery = "";
  let currentSpinner = null;

  function clearResults() {
    resultsEl.innerHTML = "";
    statusEl.innerHTML = "";
  }

  function showEmpty(msg) {
    clearResults();
    statusEl.appendChild(el("p", { class: "search-empty" }, msg));
  }

  function renderResults(users, query) {
    clearResults();
    if (!users || !users.length) {
      showEmpty(`Пользователи по запросу "${query}" не найдены`);
      return;
    }

    users.forEach((rawUser) => {
      const user = {
        ...rawUser,
        user_id: rawUser.user_id || rawUser.id,
        id: rawUser.id || rawUser.user_id,
        username: rawUser.username || rawUser.nickname,
        nickname: rawUser.nickname || rawUser.username
      };

      const item = el("li", { class: "search-result-item" },
        avatar(user, 48),
        el("div", { class: "search-result-info" },
          el("span", { class: "search-result-name" }, user.name || user.username),
          el("span", { class: "search-result-nick" }, `@${user.username}`)
        ),
        el("button", { class: "btn-secondary search-chat-btn" }, "Написать")
      );

      const startChat = async () => {
        try {
          await saveContact({ ...user, user_id: user.user_id });
        } catch {
          // contact already saved or save failed, non-fatal
        }
        navigate(`#chat/${user.user_id}`);
      };

      item.addEventListener("click", startChat);
      item.querySelector(".search-chat-btn").addEventListener("click", (e) => {
        e.stopPropagation();
        startChat();
      });

      resultsEl.appendChild(item);
    });
  }

  async function doSearch(query) {
    if (!query) {
      clearResults();
      return;
    }

    clearResults();
    currentSpinner = spinner();
    statusEl.appendChild(currentSpinner);

    try {
      const res = await apiGet(`/users/search?q=${encodeURIComponent(query)}`);
      let users = Array.isArray(res) ? res : (res.users || []);
      
      const me = getCurrentUser();
      const myId = me && (me.id || me.user_id);
      if (myId) {
        users = users.filter(u => String(u.id) !== String(myId) && String(u.user_id) !== String(myId));
      }
      
      renderResults(users, query);
    } catch (err) {
      showEmpty("Ошибка поиска. Пожалуйста, попробуйте еще раз.");
      showToast(err.message || "Ошибка поиска", "error");
    }
  }

  searchInput.addEventListener("input", () => {
    const q = searchInput.value.trim();
    if (q === lastQuery) return;
    lastQuery = q;

    clearTimeout(debounceTimer);

    if (!q) {
      clearResults();
      return;
    }

    debounceTimer = setTimeout(() => doSearch(q), 300);
  });

  searchInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      clearTimeout(debounceTimer);
      doSearch(searchInput.value.trim());
    }
  });

  // Focus the input immediately
  requestAnimationFrame(() => searchInput.focus());
}
