import { apiPost, apiGet, setToken, getUserById } from "../api.js";
import {
  encodeKey,
  decodeKey,
  encryptIdentityEnvelope,
  decryptIdentityEnvelope,
} from "../crypto.js";
import { 
  saveIdentity, 
  getIdentity, 
  saveContact, 
  saveMessage, 
  KeyHelper, 
  getPersistentDeviceName, 
  replacer, 
  reviver,
  signalStore,
  exportAllData,
  importAllData,
  getSignedPreKeyRecord
} from "../storage.js";
import { navigate, setCurrentUser } from "../app.js";
import { el, showToast, spinner, showPinModal } from "./components.js";

function authErr(errEl, msg) {
  errEl.textContent = msg;
  errEl.classList.remove("hidden");
  showToast(msg, "error");
}

function buildForm(fields, submitLabel) {
  const inputs = {};
  const fieldEls = fields.map(({ name, type, placeholder }) => {
    const input = document.createElement("input");
    input.type = type || "text";
    input.placeholder = placeholder || name;
    input.name = name;
    input.autocomplete = name === "password" ? "current-password" : "off";
    inputs[name] = input;
    return el("div", { class: "auth-field" }, input);
  });
  const btn = el("button", { type: "submit", class: "btn-primary" }, submitLabel);
  const errEl = el("p", { class: "auth-error hidden" });
  const form = el("form", { class: "auth-form" }, ...fieldEls, errEl, btn);
  return { form, inputs, btn, errEl };
}

async function handleRegister(inputs, btn, errEl) {
  const name     = inputs.name.value.trim();
  const nickname = inputs.nickname.value.trim().replace(/^@/, "");
  const password = inputs.password.value;

  if (!name || !nickname || !password) {
    authErr(errEl, "Заполните все поля.");
    return;
  }
  if (nickname.length < 3) {
    authErr(errEl, "Никнейм минимум 3 символа.");
    return;
  }
  if (!/^[a-zA-Z0-9_]+$/.test(nickname)) {
    authErr(errEl, "Никнейм: только буквы a-z A-Z, цифры и _.");
    return;
  }
  if (password.length < 6) {
    authErr(errEl, "Пароль минимум 6 символов.");
    return;
  }

  const origText = btn.textContent;
  btn.disabled = true;
  btn.textContent = "";
  btn.appendChild(spinner());
  errEl.classList.add("hidden");

  try {
    const res = await apiPost("/register", {
      name,
      nickname,
      password,
      device_name: getPersistentDeviceName(),
    });

    setToken(res.token);
    localStorage.setItem("user_id", String(res.user_id));
    localStorage.setItem("device_id", String(res.device_id));
    setCurrentUser({ id: res.user_id, user_id: res.user_id, name, nickname, username: nickname });

    showToast("Регистрация успешна!", "success");
    navigate("#chats");
  } catch (err) {
    authErr(errEl, err.message || "Ошибка регистрации.");
  } finally {
    btn.disabled = false;
    btn.textContent = origText;
  }
}

async function handleLogin(inputs, btn, errEl) {
  const nickname = inputs.nickname.value.trim().replace(/^@/, "");
  const password = inputs.password.value;

  if (!nickname || !password) {
    authErr(errEl, "Заполните никнейм и пароль.");
    return;
  }

  const origText = btn.textContent;
  btn.disabled = true;
  btn.textContent = "";
  btn.appendChild(spinner());
  errEl.classList.add("hidden");

  try {
    const res = await apiPost("/login", {
      nickname,
      password,
      device_name: getPersistentDeviceName(),
    });

    setToken(res.token);
    localStorage.setItem("user_id", String(res.user_id));
    localStorage.setItem("device_id", String(res.device_id));

    const user = await getUserById(res.user_id);
    if (user) {
      user.user_id = user.id;
      user.username = user.nickname;
      setCurrentUser(user.user || user);
    }

    showToast("Добро пожаловать!", "success");
    navigate("#chats");
  } catch (err) {
    authErr(errEl, err.message || "Ошибка входа.");
  } finally {
    btn.disabled = false;
    btn.textContent = origText;
  }
}

export function renderAuth(container, mode = "login") {
  container.innerHTML = "";
  const isRegister = mode === "register";

  const fields = isRegister
    ? [
        { name: "name",     placeholder: "Имя (например Иван Петров)" },
        { name: "nickname", placeholder: "Никнейм (латиница, цифры, _)" },
        { name: "password", type: "password", placeholder: "Пароль" },
      ]
    : [
        { name: "nickname", placeholder: "@никнейм" },
        { name: "password", type: "password", placeholder: "Пароль" },
      ];

  const { form, inputs, btn, errEl } = buildForm(
    fields,
    isRegister ? "Создать аккаунт" : "Войти"
  );

  form.addEventListener("submit", e => {
    e.preventDefault();
    isRegister
      ? handleRegister(inputs, btn, errEl)
      : handleLogin(inputs, btn, errEl);
  });

  btn.addEventListener("click", e => {
    e.preventDefault();
    isRegister
      ? handleRegister(inputs, btn, errEl)
      : handleLogin(inputs, btn, errEl);
  });

  const switchLink = el(
    "a",
    { href: isRegister ? "#login" : "#register", class: "auth-switch-link" },
    isRegister ? "Войти" : "Создать аккаунт"
  );
  const switchEl = el("p", { class: "auth-switch" },
    isRegister ? "Уже есть аккаунт? " : "Нет аккаунта? ",
    switchLink
  );

  const importInput = el("input", { type: "file", accept: ".json", style: "display:none;" });
  importInput.addEventListener("change", async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      const reader = new FileReader();
      reader.onload = async (event) => {
        try {
          const data = JSON.parse(event.target.result);
          if (!data.version || (!data.ciphertext_b64 && !data.encrypted_keys_b64)) {
            throw new Error("Неверный формат или файл не зашифрован");
          }

          const password = await showPinModal("Введите пароль/PIN для расшифрования файла ключей:", "Пароль/PIN-код");
          if (!password) return;

          let decrypted;
          if (data.version === 2) {
            const envelope = {
              encrypted_dek: decodeKey(data.encrypted_dek_b64),
              iv_kek: decodeKey(data.iv_kek_b64),
              salt_kek: decodeKey(data.salt_kek_b64),
              encrypted_keys: decodeKey(data.encrypted_keys_b64),
              iv_dek: decodeKey(data.iv_dek_b64)
            };
            decrypted = await decryptIdentityEnvelope(envelope, password);
          } else if (data.version === 1) {
            decrypted = await decryptIdentityWithPassphrase(
              decodeKey(data.ciphertext_b64),
              decodeKey(data.iv_b64),
              decodeKey(data.salt_b64),
              password
            );
          } else {
            throw new Error("Неподдерживаемая версия файла");
          }

          await importIdentityData(decrypted);
          showToast("Резервная копия ключей успешно импортирована! Войдите в аккаунт.", "success");
        } catch (err) {
          showToast("Неверный формат резервной копии или неверный пароль: " + err.message, "error");
        }
      };
      reader.readAsText(file);
    } catch (err) {
      showToast(err.message || "Ошибка импорта ключей", "error");
    }
  });

  const importBtn = el("button", { class: "btn-secondary auth-import-btn", style: "margin-top:12px;width:100%;" }, "Импортировать ключи (.json)");
  importBtn.addEventListener("click", (e) => {
    e.preventDefault();
    importInput.click();
  });

  const title = el("h1", { class: "auth-title" }, isRegister ? "Регистрация" : "Вход");
  const card  = el("div", { class: "auth-card" }, title, form, switchEl, importBtn, importInput);
  const wrap  = el("div", { class: "auth-wrap" }, card);

  container.appendChild(wrap);
}
