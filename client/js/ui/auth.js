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
    const ik = await KeyHelper.generateIdentityKeyPair();
    const registrationId = KeyHelper.generateRegistrationId();
    const spk = await KeyHelper.generateSignedPreKey(ik, 1);
    
    const opks = [];
    for (let i = 1; i <= 100; i++) {
      const o = await KeyHelper.generatePreKey(i);
      opks.push(o);
    }

    const ikPubB64  = encodeKey(new Uint8Array(ik.pubKey));
    const spkPubB64 = encodeKey(new Uint8Array(spk.keyPair.pubKey));
    const spkSigB64 = encodeKey(new Uint8Array(spk.signature));
    
    const opkList = opks.map(o => {
      const buf = new Uint8Array(37);
      const view = new DataView(buf.buffer);
      view.setUint32(0, o.keyId, false);
      buf.set(new Uint8Array(o.keyPair.pubKey), 4);
      return encodeKey(buf);
    });

    const res = await apiPost("/register", {
      name,
      nickname,
      password,
      device_name: getPersistentDeviceName(),
      registration_id: registrationId,
      ik_pub:   ikPubB64,
      spk_pub:  spkPubB64,
      spk_sig:  spkSigB64,
      opk_list: opkList,
    });

    await saveIdentity({
      identityKeyPair: ik,
      registrationId: registrationId,
      user_id: res.user_id,
      device_id: res.device_id,
    });

    await signalStore.storeSignedPreKey(1, spk.keyPair, spk.signature);
    for (const o of opks) {
      await signalStore.storePreKey(o.keyId, o.keyPair);
    }

    setToken(res.token);
    localStorage.setItem("user_id", String(res.user_id));
    localStorage.setItem("device_id", String(res.device_id));
    setCurrentUser({ id: res.user_id, user_id: res.user_id, name, nickname, username: nickname });

    try {
      const allDbData = await exportAllData();
      const dataToBackup = {
        db_dump: allDbData,
        user_id: res.user_id,
      };

      let backupPassword = "";
      const backupMethod = "pin";

      while (backupPassword.length < 6) {
        backupPassword = await showPinModal("Придумайте PIN-код или пароль для резервной копии ключей E2EE (не менее 6 символов):", "PIN-код (от 6 символов)") || "";
        if (backupPassword === "") {
          alert("PIN-код обязателен для создания резервной копии E2EE!");
          continue;
        }
        if (backupPassword.length < 6) {
          alert("Слишком короткий пароль/PIN (минимум 6 символов)!");
        }
      }
      sessionStorage.setItem("backup_pin", backupPassword);

      const env = await encryptIdentityEnvelope(dataToBackup, backupPassword);
      const backupWrapper = {
        version: 2,
        method: backupMethod,
        encrypted_dek_b64: encodeKey(env.encrypted_dek),
        iv_kek_b64: encodeKey(env.iv_kek),
        salt_kek_b64: encodeKey(env.salt_kek),
        encrypted_keys_b64: encodeKey(env.encrypted_keys),
        iv_dek_b64: encodeKey(env.iv_dek)
      };

      const jsonStr = JSON.stringify(backupWrapper);
      const encoder = new TextEncoder();
      const combinedBlob = encoder.encode(jsonStr);

      await apiPost("/keys/backup", {
        encrypted_blob: encodeKey(combinedBlob),
        kdf_salt: encodeKey(env.salt_kek)
      });
    } catch (backupErr) {
      console.warn("Не удалось создать резервную копию на сервере:", backupErr);
    }

    showToast("Аккаунт создан!", "success");
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
    let ik;
    let res;
    const existingIdentity = await getIdentity();
    if (existingIdentity && existingIdentity.identityKeyPair) {
      ik = existingIdentity.identityKeyPair;
      const spkKeyPair = await signalStore.loadSignedPreKey(1);
      const spkRecord = await getSignedPreKeyRecord(1);

      res = await apiPost("/login", {
        nickname,
        password,
        device_name: getPersistentDeviceName(),
        registration_id: existingIdentity.registrationId,
        ik_pub:  encodeKey(new Uint8Array(ik.pubKey)),
        spk_pub: spkKeyPair ? encodeKey(new Uint8Array(spkKeyPair.pubKey)) : "",
        spk_sig: spkRecord ? encodeKey(new Uint8Array(spkRecord.signature)) : "",
      });

      setToken(res.token);
      localStorage.setItem("user_id", String(res.user_id));
      localStorage.setItem("device_id", String(res.device_id));

      await saveIdentity({
        identityKeyPair: ik,
        registrationId: existingIdentity.registrationId,
        user_id: res.user_id,
        device_id: res.device_id,
      });

      // Generate and upload 100 fresh OPKs on repeat login
      const opks = [];
      for (let i = 1; i <= 100; i++) {
        const o = await KeyHelper.generatePreKey(i);
        opks.push(o);
      }
      for (const o of opks) {
        await signalStore.storePreKey(o.keyId, o.keyPair);
      }
      const opkList = opks.map(o => {
        const buf = new Uint8Array(37);
        const view = new DataView(buf.buffer);
        view.setUint32(0, o.keyId, false);
        buf.set(new Uint8Array(o.keyPair.pubKey), 4);
        return encodeKey(buf);
      });
      try {
        await apiPost("/keys/otk", { opk_list: opkList });
      } catch (otkErr) {
        console.error("Failed to upload repeat login OPKs:", otkErr);
        showToast("Предупреждение: Не удалось обновить ключи шифрования E2EE на сервере. Обратитесь к администратору.", "error");
      }

    } else {
      res = await apiPost("/login", {
        nickname,
        password,
        device_name: getPersistentDeviceName(),
        ik_pub:  "",
        spk_pub: "",
        spk_sig: "",
      });

      setToken(res.token);
      localStorage.setItem("user_id", String(res.user_id));
      localStorage.setItem("device_id", String(res.device_id));

      let restored = false;
      let backupPasswordForBackup = password;
      let backupMethod = "password";

      try {
        const backup = await apiGet("/keys/backup");
        if (backup && backup.encrypted_blob) {
          const combinedBlob = decodeKey(backup.encrypted_blob);
          const jsonStr = new TextDecoder().decode(combinedBlob);
          const backupData = JSON.parse(jsonStr);

          let backupPassword = "";
          while (backupPassword.length < 6) {
            backupPassword = await showPinModal("Введите PIN-код для расшифрования резервной копии E2EE:", "PIN-код бэкапа") || "";
            if (backupPassword === "") {
              alert("PIN-код обязателен для восстановления резервной копии!");
              continue;
            }
          }

          const envelope = {
            encrypted_dek: decodeKey(backupData.encrypted_dek_b64),
            iv_kek: decodeKey(backupData.iv_kek_b64),
            salt_kek: decodeKey(backupData.salt_kek_b64),
            encrypted_keys: decodeKey(backupData.encrypted_keys_b64),
            iv_dek: decodeKey(backupData.iv_dek_b64)
          };

          const decrypted = await decryptIdentityEnvelope(envelope, backupPassword);
          sessionStorage.setItem("backup_pin", backupPassword);
          backupPasswordForBackup = backupPassword;
          backupMethod = "pin";

          if (decrypted.db_dump) {
            await importAllData(decrypted.db_dump);
            restored = true;
          } else {
            console.warn("Legacy backup format detected, generating new identity...");
          }
        }
      } catch (backupErr) {
        console.error("Не удалось восстановить бэкап с сервера. Детали ошибки:", backupErr);
      }

      // Always generate new E2EE identity keys and signed prekeys for the new device
      ik = await KeyHelper.generateIdentityKeyPair();
      const registrationId = KeyHelper.generateRegistrationId();
      const spk = await KeyHelper.generateSignedPreKey(ik, 1);

      const opks = [];
      for (let i = 1; i <= 100; i++) {
        const o = await KeyHelper.generatePreKey(i);
        opks.push(o);
      }

      const ikPubB64  = encodeKey(new Uint8Array(ik.pubKey));
      const spkPubB64 = encodeKey(new Uint8Array(spk.keyPair.pubKey));
      const spkSigB64 = encodeKey(new Uint8Array(spk.signature));
      
      const opkList = opks.map(o => {
        const buf = new Uint8Array(37);
        const view = new DataView(buf.buffer);
        view.setUint32(0, o.keyId, false);
        buf.set(new Uint8Array(o.keyPair.pubKey), 4);
        return encodeKey(buf);
      });

      await saveIdentity({
        identityKeyPair: ik,
        registrationId: registrationId,
        user_id: res.user_id,
        device_id: res.device_id,
      });

      await signalStore.storeSignedPreKey(1, spk.keyPair, spk.signature);
      for (const o of opks) {
        await signalStore.storePreKey(o.keyId, o.keyPair);
      }

      await apiPost("/keys/init", {
        ik_pub:  ikPubB64,
        spk_pub: spkPubB64,
        spk_sig: spkSigB64,
        registration_id: registrationId,
        opk_list: opkList,
      });

      if (restored) {
        showToast("История переписки восстановлена из облачного бэкапа!", "success");
      } else {
        const usePin = confirm(
          "На сервере не найден бэкап переписки. Хотите защитить создаваемую резервную копию E2EE отдельным PIN-кодом/паролем?\n\n" +
          "Если вы выберете 'Отмена', резервная копия будет зашифрована вашим паролем аккаунта."
        );
        if (usePin) {
          let pin = "";
          while (pin.length < 6) {
            pin = await showPinModal("Придумайте PIN-код или пароль для резервной копии ключей (не менее 6 символов):", "PIN-код (от 6 символов)") || "";
            if (pin === "") {
              backupMethod = "password";
              backupPasswordForBackup = password;
              break;
            }
            if (pin.length < 6) {
              alert("Слишком короткий пароль/PIN (минимум 6 символов)!");
            } else {
              backupMethod = "pin";
              backupPasswordForBackup = pin;
            }
          }
        }
      }

      sessionStorage.setItem("backup_pin", backupPasswordForBackup);

      try {
        const allDbData = await exportAllData();
        const dataToBackup = {
          db_dump: allDbData,
          user_id: res.user_id,
        };

        const env = await encryptIdentityEnvelope(dataToBackup, backupPasswordForBackup);
        const backupWrapper = {
          version: 2,
          method: backupMethod,
          encrypted_dek_b64: encodeKey(env.encrypted_dek),
          iv_kek_b64: encodeKey(env.iv_kek),
          salt_kek_b64: encodeKey(env.salt_kek),
          encrypted_keys_b64: encodeKey(env.encrypted_keys),
          iv_dek_b64: encodeKey(env.iv_dek)
        };

        const jsonStr = JSON.stringify(backupWrapper);
        const encoder = new TextEncoder();
        const combinedBlob = encoder.encode(jsonStr);

        await apiPost("/keys/backup", {
          encrypted_blob: encodeKey(combinedBlob),
          kdf_salt: encodeKey(env.salt_kek)
        });
      } catch (backupErr) {
        console.warn("Не удалось создать резервную копию на сервере:", backupErr);
      }

      showToast("Сгенерированы новые ключи шифрования E2EE для устройства!", "info");
    }

    const user = await getUserById(res.user_id);
    if (user) {
      user.user_id = user.id;
      user.username = user.nickname;
      setCurrentUser(user);
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
