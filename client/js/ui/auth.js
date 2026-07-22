import { apiPost, setToken, getUserById } from "../api.js";
import { getPersistentDeviceName, saveIdentityKey, saveIKPrivate, saveIKPublic, getIKPrivate, getIKPublic } from "../storage.js";
import { navigate, setCurrentUser } from "../app.js";
import { el, showToast, spinner } from "./components.js";
import { generateKeyPair, encryptIdentityEnvelope } from "../crypto.js";


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

// The identity keypair must be STABLE for the life of this browser profile. The
// server does INSERT OR REPLACE on the uploaded ik_pub, so generating a fresh
// pair on every login silently rotates this device's identity key — after which
// every group-key envelope (wrapped by the sender for the OLD public key) and
// 1:1 session fails to decrypt. Reuse the persisted pair; only generate once.
async function resolveIdentityKeyPair() {
  const priv = await getIKPrivate();
  const pub = await getIKPublic();
  if (priv && pub) {
    return { publicKey: new Uint8Array(pub), privateKey: new Uint8Array(priv), existing: true };
  }
  const ik = await generateKeyPair();
  return { publicKey: ik.publicKey, privateKey: ik.privateKey, existing: false };
}

async function generateAndUploadKeys(password) {
  const ik = await resolveIdentityKeyPair();
  const envelope = await encryptIdentityEnvelope({ privateKey: ik.privateKey }, password);

  const ikPubB64 = btoa(String.fromCharCode(...ik.publicKey));

  return {
    ikPub: ikPubB64,
    saveKeys: async () => {
      await saveIdentityKey(envelope);
      await saveIKPrivate(ik.privateKey);
      await saveIKPublic(ik.publicKey);
    }
  };
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
    const keysData = await generateAndUploadKeys(password);

    const res = await apiPost("/register", {
      name,
      nickname,
      password,
      device_name: getPersistentDeviceName(),
      ik_pub: keysData.ikPub,
    });

    setToken(res.token);
    localStorage.setItem("user_id", String(res.user_id));
    localStorage.setItem("device_id", String(res.device_id));
    
    await keysData.saveKeys();
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
    const keysData = await generateAndUploadKeys(password);

    const res = await apiPost("/login", {
      nickname,
      password,
      device_name: getPersistentDeviceName(),
      ik_pub: keysData.ikPub,
    });

    setToken(res.token);
    localStorage.setItem("user_id", String(res.user_id));
    localStorage.setItem("device_id", String(res.device_id));

    await keysData.saveKeys();

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

  const title = el("h1", { class: "auth-title" }, isRegister ? "Регистрация" : "Вход");
  const card  = el("div", { class: "auth-card" }, title, form, switchEl);
  const wrap  = el("div", { class: "auth-wrap" }, card);

  container.appendChild(wrap);
}
