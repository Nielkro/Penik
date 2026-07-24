import { apiPost, apiGet, setToken, getUserById, getToken } from "../api.js";
import { getPersistentDeviceName, saveIdentityKey, saveIKPrivate, saveIKPublic, getIKPrivate, getIKPublic } from "../storage.js";
import { navigate, setCurrentUser, restoreE2EEKeys, backupE2EEKeys } from "../app.js";
import { el, showToast, spinner, avatar, showConfirmModal, showPinModal } from "./components.js";
import { generateKeyPair, encryptIdentityEnvelope, derivePublicKey } from "../crypto.js";
import { ws, OP } from "../ws.js";

function authErr(errEl, msg) {
  errEl.textContent = msg;
  errEl.classList.remove("hidden");
  showToast(msg, "error");
}

async function resolveIdentityKeyPair() {
  const priv = await getIKPrivate();
  const pub = await getIKPublic();
  if (priv && pub) {
    return { publicKey: new Uint8Array(pub), privateKey: new Uint8Array(priv), existing: true };
  }
  const ik = await generateKeyPair();
  return { publicKey: ik.publicKey, privateKey: ik.privateKey, existing: false };
}

async function generateAndUploadKeys(e2eePassword) {
  const ik = await resolveIdentityKeyPair();
  const envelope = await encryptIdentityEnvelope({ privateKey: ik.privateKey }, e2eePassword);
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

function convertFileToWebP(file) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      const canvas = document.createElement("canvas");
      let width = img.naturalWidth || img.width;
      let height = img.naturalHeight || img.height;
      const maxDim = 512;
      if (width > maxDim || height > maxDim) {
        if (width > height) {
          height = Math.round((height * maxDim) / width);
          width = maxDim;
        } else {
          width = Math.round((width * maxDim) / height);
          height = maxDim;
        }
      }
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext("2d");
      ctx.drawImage(img, 0, 0, width, height);
      canvas.toBlob((blob) => {
        if (blob) resolve(blob);
        else reject(new Error("Ошибка конвертации в WebP."));
      }, "image/webp", 0.85);
    };
    img.onerror = (err) => {
      URL.revokeObjectURL(url);
      reject(new Error("Не удалось прочитать файл изображения."));
    };
    img.src = url;
  });
}

async function uploadAvatarFile(file) {
  const token = getToken();
  const headers = {};
  if (token) headers['Authorization'] = `Bearer ${token}`;

  let webpBlob = file;
  try {
    webpBlob = await convertFileToWebP(file);
  } catch (err) {
    console.warn("WebP conversion fallback:", err);
  }

  const formData = new FormData();
  formData.append("avatar", webpBlob, "avatar.webp");

  const res = await fetch(`${window.location.protocol}//${window.location.host}/api/v1/avatar`, {
    method: "PUT",
    headers,
    body: formData
  });

  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }
}

export function renderAuth(container, initialMode = "welcome") {
  container.innerHTML = "";

  let mode = initialMode; // "welcome", "register", "login"
  let step = 0;

  // Wizard state:
  const state = {
    nickname: "",
    password: "",
    e2eePassword: "",
    name: "",
    avatarFile: null,
    avatarUrl: null,
    tempUserId: null,
    tempName: null
  };

  const card = el("div", { class: "auth-card", style: "position:relative; overflow:hidden; min-height: 380px; display:flex; flex-direction:column; justify-content:center;" });
  const wrap = el("div", { class: "auth-wrap" }, card);
  container.appendChild(wrap);

  const errEl = el("p", { class: "auth-error hidden", style: "margin-bottom:16px; margin-top: 16px;" });

  function showErr(msg) {
    errEl.textContent = msg;
    errEl.classList.remove("hidden");
    showToast(msg, "error");
  }

  function clearErr() {
    errEl.textContent = "";
    errEl.classList.add("hidden");
  }

  function renderStep() {
    card.innerHTML = "";
    clearErr();

    // Back button
    if (mode !== "welcome") {
      const backBtn = el("button", {
        class: "icon-btn",
        style: "position:absolute; left:16px; top:16px; background:none; border:none; color:#aaa; font-size:22px; cursor:pointer; padding: 4px; display:flex; align-items:center; justify-content:center;",
        onclick: () => {
          if (step > 0) {
            step--;
            renderStep();
          } else {
            mode = "welcome";
            step = 0;
            renderStep();
          }
        }
      }, "←");
      card.appendChild(backBtn);
    }

    card.appendChild(errEl);

    // Page indicator
    if (mode !== "welcome") {
      const maxSteps = mode === "register" ? 5 : 4;
      const progressWrap = el("div", { style: "display:flex; gap:4px; margin-top:8px; margin-bottom:24px; width:100%; height:4px; background:rgba(255,255,255,0.05); border-radius:2px;" });
      for (let i = 0; i < maxSteps; i++) {
        const active = i <= step;
        const bar = el("div", { style: `flex:1; height:100%; border-radius:2px; background:${active ? "var(--accent)" : "transparent"}; transition: background 0.3s;` });
        progressWrap.appendChild(bar);
      }
      card.appendChild(progressWrap);
    }

    if (mode === "welcome") {
      renderWelcome();
    } else if (mode === "register") {
      renderRegisterStep();
    } else if (mode === "login") {
      renderLoginStep();
    }
  }

  function renderWelcome() {
    const logoImg = el("img", {
      src: "assets/apple-touch-icon.png",
      style: "width: 80px; height: 80px; border-radius: 20px; margin: 0 auto 20px; display: block; object-fit: cover; box-shadow: 0 8px 24px rgba(0,0,0,0.2);"
    });
    const title = el("h1", { class: "auth-title", style: "margin-top: 0;" }, "Penik");
    const subtitle = el("p", { style: "text-align:center; color:#aaa; font-size:14px; margin-bottom:32px; font-weight: 500;" }, "Защищенный мессенджер с E2EE");
    
    const regBtn = el("button", { class: "btn-primary", style: "margin-bottom:12px; cursor:pointer;" }, "Регистрация");
    const loginBtn = el("button", { class: "btn-secondary", style: "width:100%; padding:13px; font-size:15px; border-radius:var(--r-sm); cursor:pointer;" }, "Войти");

    regBtn.addEventListener("click", () => {
      mode = "register";
      step = 0;
      renderStep();
    });

    loginBtn.addEventListener("click", () => {
      mode = "login";
      step = 0;
      renderStep();
    });

    card.appendChild(logoImg);
    card.appendChild(title);
    card.appendChild(subtitle);
    card.appendChild(regBtn);
    card.appendChild(loginBtn);
  }

  // --- REGISTRATION FLOW ---
  async function renderRegisterStep() {
    if (step === 0) {
      // Step 1: Nickname Input
      const title = el("h1", { class: "auth-title" }, "Выберите никнейм");
      const subtitle = el("p", { style: "text-align:center; color:#aaa; font-size:13px; margin-bottom:20px; line-height:1.4;" }, "Укажнее никнейм для поиска в мессенджере");
      
      const input = el("input", { type: "text", placeholder: "@username", class: "profile-input", value: state.nickname, style: "width:100%; margin-bottom:16px; padding:12px; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#fff;" });
      const nextBtn = el("button", { class: "btn-primary", style: "cursor:pointer;" }, "Продолжить");

      const handleNext = async () => {
        const nickname = input.value.trim().replace(/^@/, "");
        if (!nickname) return showErr("Введите никнейм.");
        if (nickname.length < 3) return showErr("Никнейм должен быть не менее 3 символов.");
        if (!/^[a-zA-Z0-9_]+$/.test(nickname)) return showErr("Только латиница, цифры и символ _");

        nextBtn.disabled = true;
        nextBtn.innerHTML = "";
        nextBtn.appendChild(spinner());
        clearErr();

        try {
          const res = await apiGet(`/users/check?nickname=${encodeURIComponent(nickname)}`);
          if (!res.available) {
            showErr("Этот никнейм уже занят.");
            return;
          }
          state.nickname = nickname;
          step = 1;
          renderStep();
        } catch (err) {
          showErr(err.message || "Ошибка проверки никнейма.");
        } finally {
          nextBtn.disabled = false;
          nextBtn.textContent = "Продолжить";
        }
      };

      nextBtn.addEventListener("click", handleNext);
      input.addEventListener("keydown", e => { if (e.key === "Enter") handleNext(); });

      card.appendChild(title);
      card.appendChild(subtitle);
      card.appendChild(input);
      card.appendChild(nextBtn);
      input.focus();
    } 
    else if (step === 1) {
      // Step 2: Account Password
      const title = el("h1", { class: "auth-title" }, "Создайте пароль");
      const subtitle = el("p", { style: "text-align:center; color:#aaa; font-size:13px; margin-bottom:20px; line-height:1.4;" }, "Используется для входа в ваш аккаунт");

      const input = el("input", { type: "password", placeholder: "Пароль", class: "profile-input", value: state.password, style: "width:100%; margin-bottom:16px; padding:12px; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#fff;" });
      const nextBtn = el("button", { class: "btn-primary", style: "cursor:pointer;" }, "Продолжить");

      const handleNext = () => {
        const val = input.value;
        if (!val || val.length < 6) return showErr("Пароль должен быть не менее 6 символов.");
        state.password = val;
        step = 2;
        renderStep();
      };

      nextBtn.addEventListener("click", handleNext);
      input.addEventListener("keydown", e => { if (e.key === "Enter") handleNext(); });

      card.appendChild(title);
      card.appendChild(subtitle);
      card.appendChild(input);
      card.appendChild(nextBtn);
      input.focus();
    } 
    else if (step === 2) {
      // Step 3: E2EE Password
      const title = el("h1", { class: "auth-title" }, "Создайте e2ee-пароль");
      const subtitle = el("p", { style: "text-align:center; color:#aaa; font-size:13px; margin-bottom:20px; line-height:1.4;" }, "Этот ключ используется для шифрования ваших переписок. Знаете его только вы.");

      const input = el("input", { type: "password", placeholder: "Надежный e2ee-пароль", class: "profile-input", value: state.e2eePassword, style: "width:100%; padding:12px; padding-right:36px; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#fff;" });
      
      const toggleBtn = el("button", { type: "button", style: "position:absolute; right:12px; top:50%; transform:translateY(-50%); background:none; border:none; color:#aaa; cursor:pointer; font-size:16px; padding:4px;" }, "👁️");
      toggleBtn.addEventListener("click", () => {
        if (input.type === "password") {
          input.type = "text";
          toggleBtn.textContent = "🙈";
        } else {
          input.type = "password";
          toggleBtn.textContent = "👁️";
        }
      });

      const wrapper = el("div", { style: "position:relative; width:100%; margin-bottom:16px;" }, input, toggleBtn);
      const nextBtn = el("button", { class: "btn-primary", style: "cursor:pointer;" }, "Сохранить и продолжить");

      const handleNext = () => {
        const val = input.value;
        if (!val || val.length < 6) return showErr("Пароль должен быть не менее 6 символов.");
        state.e2eePassword = val;
        step = 3;
        renderStep();
      };

      nextBtn.addEventListener("click", handleNext);
      input.addEventListener("keydown", e => { if (e.key === "Enter") handleNext(); });

      card.appendChild(title);
      card.appendChild(subtitle);
      card.appendChild(wrapper);
      card.appendChild(nextBtn);
      input.focus();
    } 
    else if (step === 3) {
      // Step 4: Name and Avatar Setup
      const title = el("h1", { class: "auth-title" }, "Ваша аватарка и имя");
      const subtitle = el("p", { style: "text-align:center; color:#aaa; font-size:13px; margin-bottom:20px; line-height:1.4;" }, "Загрузите фото и укажите, как вас будут видеть друзья");

      const fileInput = el("input", { type: "file", accept: "image/*", style: "display:none;" });
      const avatarContainer = el("div", { 
        style: "width:88px; height:88px; border-radius:50%; background:rgba(255,255,255,0.05); border:2px dashed rgba(255,255,255,0.2); display:flex; align-items:center; justify-content:center; cursor:pointer; margin:0 auto 20px; overflow:hidden; position:relative;" 
      }, el("span", { style: "font-size:32px; color:#aaa;" }, "+"));

      if (state.avatarUrl) {
        avatarContainer.innerHTML = "";
        avatarContainer.appendChild(el("img", { src: state.avatarUrl, style: "width:100%; height:100%; object-fit:cover;" }));
      }

      avatarContainer.addEventListener("click", () => fileInput.click());
      fileInput.addEventListener("change", () => {
        const file = fileInput.files[0];
        if (file) {
          state.avatarFile = file;
          state.avatarUrl = URL.createObjectURL(file);
          avatarContainer.innerHTML = "";
          avatarContainer.appendChild(el("img", { src: state.avatarUrl, style: "width:100%; height:100%; object-fit:cover;" }));
        }
      });

      const input = el("input", { type: "text", placeholder: "Ваше имя", class: "profile-input", value: state.name, style: "width:100%; margin-bottom:16px; padding:12px; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#fff;" });
      const nextBtn = el("button", { class: "btn-primary", style: "cursor:pointer;" }, "Завершить регистрацию");

      const handleFinish = async () => {
        const name = input.value.trim();
        if (!name) return showErr("Введите имя.");
        state.name = name;

        nextBtn.disabled = true;
        nextBtn.innerHTML = "";
        nextBtn.appendChild(spinner());
        clearErr();

        try {
          // 1. Generate keys encrypted with E2EE password
          const keysData = await generateAndUploadKeys(state.e2eePassword);

          // 2. Register on server
          const res = await apiPost("/register", {
            name: state.name,
            nickname: state.nickname,
            password: state.password,
            device_name: getPersistentDeviceName(),
            ik_pub: keysData.ikPub,
          });

          setToken(res.token);
          localStorage.setItem("user_id", String(res.user_id));
          localStorage.setItem("device_id", String(res.device_id));

          // 3. Save key pair locally
          await keysData.saveKeys();

          // 4. Upload avatar if selected
          if (state.avatarFile) {
            try {
              await uploadAvatarFile(state.avatarFile);
            } catch (avErr) {
              console.warn("Avatar upload failed:", avErr);
              showToast("Аватар не загружен, но аккаунт успешно создан", "warning");
            }
          }

          setCurrentUser({ id: res.user_id, user_id: res.user_id, name: state.name, nickname: state.nickname, username: state.nickname });
          showToast("Регистрация успешна!", "success");
          navigate("#chats");
        } catch (err) {
          showErr(err.message || "Ошибка регистрации.");
        } finally {
          nextBtn.disabled = false;
          nextBtn.textContent = "Завершить регистрацию";
        }
      };

      nextBtn.addEventListener("click", handleFinish);
      input.addEventListener("keydown", e => { if (e.key === "Enter") handleFinish(); });

      card.appendChild(title);
      card.appendChild(subtitle);
      card.appendChild(fileInput);
      card.appendChild(avatarContainer);
      card.appendChild(input);
      card.appendChild(nextBtn);
      input.focus();
    }
  }

  // --- LOGIN FLOW ---
  async function renderLoginStep() {
    if (step === 0) {
      // Step 1: Nickname Input
      const title = el("h1", { class: "auth-title" }, "Введите никнейм");
      const subtitle = el("p", { style: "text-align:center; color:#aaa; font-size:13px; margin-bottom:20px; line-height:1.4;" }, "Укажите ваш никнейм для входа");

      const input = el("input", { type: "text", placeholder: "@username", class: "profile-input", value: state.nickname, style: "width:100%; margin-bottom:16px; padding:12px; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#fff;" });
      const nextBtn = el("button", { class: "btn-primary", style: "cursor:pointer;" }, "Продолжить");

      const handleNext = async () => {
        const nickname = input.value.trim().replace(/^@/, "");
        if (!nickname) return showErr("Введите никнейм.");
        if (nickname.length < 3) return showErr("Никнейм должен состоять не менее чем из 3 символов.");
        if (!/^[a-zA-Z0-9_]+$/.test(nickname)) return showErr("Никнейм может содержать только латинские буквы, цифры и _");

        nextBtn.disabled = true;
        nextBtn.innerHTML = "";
        nextBtn.appendChild(spinner());
        clearErr();

        try {
          const profile = await apiGet(`/users/${encodeURIComponent(nickname)}/profile`);
          state.nickname = profile.nickname;
          state.tempUserId = profile.id;
          state.tempName = profile.name;
          step = 1;
          renderStep();
        } catch (err) {
          showErr(err.status === 404 ? "Пользователь не найден." : (err.message || "Ошибка загрузки профиля."));
        } finally {
          nextBtn.disabled = false;
          nextBtn.textContent = "Продолжить";
        }
      };

      nextBtn.addEventListener("click", handleNext);
      input.addEventListener("keydown", e => { if (e.key === "Enter") handleNext(); });

      card.appendChild(title);
      card.appendChild(subtitle);
      card.appendChild(input);
      card.appendChild(nextBtn);
      input.focus();
    } 
    else if (step === 1) {
      // Step 2: Confirm Profile ("Это ваш аккаунт?")
      const title = el("h1", { class: "auth-title" }, "Это ваш аккаунт?");
      const subtitle = el("p", { style: "text-align:center; color:#aaa; font-size:13px; margin-bottom:24px; line-height:1.4;" }, "Проверьте данные перед входом");

      const avNode = avatar({ id: state.tempUserId, name: state.tempName, nickname: state.nickname }, 80);
      avNode.style.margin = "0 auto 16px";

      const nameLabel = el("h3", { style: "font-size:18px; font-weight:600; text-align:center; margin-bottom:4px; color:#fff;" }, state.tempName);
      const nickLabel = el("p", { style: "font-size:14px; text-align:center; margin-bottom:32px; color:#aaa;" }, `@${state.nickname}`);

      const confirmBtn = el("button", { class: "btn-primary", style: "margin-bottom:12px; cursor:pointer;" }, "Да, это я");
      const switchBackLink = el("a", { class: "auth-switch-link", style: "display:block; text-align:center; font-size:13px; cursor:pointer;" }, "Войти в другой аккаунт");

      confirmBtn.addEventListener("click", () => {
        step = 2;
        renderStep();
      });

      switchBackLink.addEventListener("click", () => {
        step = 0;
        renderStep();
      });

      card.appendChild(title);
      card.appendChild(subtitle);
      card.appendChild(avNode);
      card.appendChild(nameLabel);
      card.appendChild(nickLabel);
      card.appendChild(confirmBtn);
      card.appendChild(switchBackLink);
    } 
    else if (step === 2) {
      // Step 3: Account Password
      const title = el("h1", { class: "auth-title" }, "Введите пароль");
      const subtitle = el("p", { style: "text-align:center; color:#aaa; font-size:13px; margin-bottom:20px; line-height:1.4;" }, `Пароль от вашего аккаунта Penik`);

      const input = el("input", { type: "password", placeholder: "Пароль", class: "profile-input", value: state.password, style: "width:100%; margin-bottom:16px; padding:12px; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#fff;" });
      const nextBtn = el("button", { class: "btn-primary", style: "cursor:pointer;" }, "Войти");

      const handleNext = async () => {
        const password = input.value;
        if (!password) return showErr("Введите пароль.");

        nextBtn.disabled = true;
        nextBtn.innerHTML = "";
        nextBtn.appendChild(spinner());
        clearErr();

        try {
          const loginPayload = {
            nickname: state.nickname,
            password: password,
            device_name: getPersistentDeviceName(),
          };

          // If device already has local key pair, include it
          const existingPub = await getIKPublic();
          if (existingPub && existingPub.length === 32) {
            loginPayload.ik_pub = btoa(String.fromCharCode(...existingPub));
          }

          const res = await apiPost("/login", loginPayload);

          setToken(res.token);
          localStorage.setItem("user_id", String(res.user_id));
          localStorage.setItem("device_id", String(res.device_id));

          state.password = password;
          step = 3;
          renderStep();
        } catch (err) {
          showErr(err.message || "Неверный пароль.");
        } finally {
          nextBtn.disabled = false;
          nextBtn.textContent = "Войти";
        }
      };

      nextBtn.addEventListener("click", handleNext);
      input.addEventListener("keydown", e => { if (e.key === "Enter") handleNext(); });

      card.appendChild(title);
      card.appendChild(subtitle);
      card.appendChild(input);
      card.appendChild(nextBtn);
      input.focus();
    } 
    else if (step === 3) {
      // Step 4: E2EE Password / Restore / Reset
      const title = el("h1", { class: "auth-title" }, "Восстановление ключей");
      const subtitle = el("p", { style: "text-align:center; color:#aaa; font-size:13px; margin-bottom:20px; line-height:1.4;" }, "Введите e2ee-пароль для расшифрования сообщений");

      const input = el("input", { type: "password", placeholder: "Ваш e2ee-пароль", class: "profile-input", value: state.e2eePassword, style: "width:100%; padding:12px; padding-right:36px; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#fff;" });
      
      const toggleBtn = el("button", { type: "button", style: "position:absolute; right:12px; top:50%; transform:translateY(-50%); background:none; border:none; color:#aaa; cursor:pointer; font-size:16px; padding:4px;" }, "👁️");
      toggleBtn.addEventListener("click", () => {
        if (input.type === "password") {
          input.type = "text";
          toggleBtn.textContent = "🙈";
        } else {
          input.type = "password";
          toggleBtn.textContent = "👁️";
        }
      });

      const wrapper = el("div", { style: "position:relative; width:100%; margin-bottom:16px;" }, input, toggleBtn);
      const restoreBtn = el("button", { class: "btn-primary", style: "margin-bottom:12px; cursor:pointer;" }, "Восстановить переписку");
      
      const resetLink = el("a", { class: "auth-switch-link", style: "display:block; text-align:center; font-size:13px; cursor:pointer;" }, "Забыли e2ee-пароль? (Начать с чистого листа)");

      // Action 1: Restore backup
      const handleRestore = async () => {
        const passphrase = input.value;
        if (!passphrase) return showErr("Введите e2ee-пароль.");

        restoreBtn.disabled = true;
        restoreBtn.innerHTML = "";
        restoreBtn.appendChild(spinner());
        clearErr();

        try {
          await restoreE2EEKeys(passphrase);

          // Get profile
          const user = await getUserById(state.tempUserId);
          if (user) {
            user.user_id = user.id;
            user.username = user.nickname;
            setCurrentUser(user.user || user);
          }

          showToast("Связка ключей успешно восстановлена!", "success");
          navigate("#chats");
        } catch (err) {
          showErr(err.message || "Неверный e2ee-пароль или ошибка восстановления.");
        } finally {
          restoreBtn.disabled = false;
          restoreBtn.textContent = "Восстановить переписку";
        }
      };

      // Action 2: Reset backup (Forgotten E2EE password)
      const handleReset = async () => {
        const confirmed = await showConfirmModal(
          "Сброс E2EE ключей",
          "Вы уверены? Старые сообщения на этом устройстве не смогут быть расшифрованы. Все новые сообщения будут зашифрованы новым ключом.",
          "Сбросить ключи",
          "Отмена",
          true
        );
        if (!confirmed) return;

        const newPass = await showPinModal("Придумайте новый e2ee-пароль", "Минимум 6 символов");
        if (!newPass) return;
        if (newPass.length < 6) {
          showToast("Новый пароль должен быть не менее 6 символов", "error");
          return;
        }

        clearErr();
        try {
          // Generate a fresh keypair and encrypt with the new E2EE password
          const ik = await generateKeyPair();
          const envelope = await encryptIdentityEnvelope({ privateKey: ik.privateKey }, newPass);

          // Save keys locally
          await saveIdentityKey(envelope);
          await saveIKPrivate(ik.privateKey);
          await saveIKPublic(ik.publicKey);

          // Upload backup to server
          await backupE2EEKeys(newPass);

          // Upload public key to database (server will register this)
          // ws handles key publish on connect, so navigate will trigger connection and publish it automatically.
          const user = await getUserById(state.tempUserId);
          if (user) {
            user.user_id = user.id;
            user.username = user.nickname;
            setCurrentUser(user.user || user);
          }

          showToast("Бэкап ключей сброшен. Начато с чистого листа!", "success");
          navigate("#chats");
        } catch (err) {
          showErr(err.message || "Ошибка сброса бэкапа ключей.");
        }
      };

      restoreBtn.addEventListener("click", handleRestore);
      input.addEventListener("keydown", e => { if (e.key === "Enter") handleRestore(); });
      resetLink.addEventListener("click", handleReset);

      card.appendChild(title);
      card.appendChild(subtitle);
      card.appendChild(wrapper);
      card.appendChild(restoreBtn);
      card.appendChild(resetLink);
      input.focus();
    }
  }

  renderStep();
}
