import { apiPost, apiGet, setToken, getUserById } from "../api.js";
import {
  generateIdentityKeyPair,
  generateSignedPreKey,
  generateOneTimeKeys,
  encodeKey,
  decodeKey,
  importX25519Priv,
  encryptIdentityEnvelope,
  decryptIdentityEnvelope,
} from "../crypto.js";
import { saveIdentity, saveOPKs, getIdentity, importIdentityData, saveContact, saveMessage } from "../storage.js";
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
    const ik  = await generateIdentityKeyPair();
    const spk = await generateSignedPreKey(ik.sigPrivateKey);
    const opks = await generateOneTimeKeys(100);

    const combinedIKPub = new Uint8Array(64);
    combinedIKPub.set(ik.pubRaw, 0);
    combinedIKPub.set(ik.sigPubRaw, 32);

    const ikPubB64  = encodeKey(combinedIKPub);
    const spkPubB64 = encodeKey(spk.pubRaw);
    const spkSigB64 = encodeKey(spk.sig);
    const opkList   = opks.map(o => encodeKey(o.pubRaw));

    const res = await apiPost("/register", {
      name,
      nickname,
      password,
      device_name: navigator.userAgent.slice(0, 60),
      ik_pub:   ikPubB64,
      spk_pub:  spkPubB64,
      spk_sig:  spkSigB64,
      opk_list: opkList,
    });

    await saveIdentity({
      ik_priv_jwk:  ik.privJwk,
      ik_pub_raw:   ik.pubRaw,
      sig_priv_jwk: ik.sigPrivJwk,
      sig_pub_raw:  ik.sigPubRaw,
      spk_priv_jwk: spk.privJwk,
      spk_pub_raw:  spk.pubRaw,
      spk_sig:      spk.sig,
      user_id:      res.user_id,
      device_id:    res.device_id,
    });
    await saveOPKs(opks);

    setToken(res.token);
    localStorage.setItem("user_id", String(res.user_id));
    localStorage.setItem("device_id", String(res.device_id));
    setCurrentUser({ id: res.user_id, user_id: res.user_id, name, nickname, username: nickname });

    try {
      const dataToBackup = {
        ik_priv_jwk:  ik.privJwk,
        ik_pub_raw:   Array.from(ik.pubRaw),
        sig_priv_jwk: ik.sigPrivJwk,
        sig_pub_raw:  Array.from(ik.sigPubRaw),
        spk_priv_jwk: spk.privJwk,
        spk_pub_raw:  Array.from(spk.pubRaw),
        spk_sig:      Array.from(spk.sig),
        user_id:      res.user_id,
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
    let ik, spk;
    let res;
    const existingIdentity = await getIdentity();
    if (existingIdentity && existingIdentity.ik_priv_jwk) {
      const privKey = await importX25519Priv(existingIdentity.ik_priv_jwk);
      ik = {
        privateKey: privKey,
        pubRaw: existingIdentity.ik_pub_raw,
        privJwk: existingIdentity.ik_priv_jwk,
      };
      spk = {
        pubRaw: existingIdentity.spk_pub_raw,
        privJwk: existingIdentity.spk_priv_jwk,
        sig: existingIdentity.spk_sig,
      };

      res = await apiPost("/login", {
        nickname,
        password,
        device_name: navigator.userAgent.slice(0, 60),
        ik_pub:  encodeKey(ik.pubRaw),
        spk_pub: encodeKey(spk.pubRaw),
        spk_sig: encodeKey(spk.sig),
      });

      setToken(res.token);
      localStorage.setItem("user_id", String(res.user_id));
      localStorage.setItem("device_id", String(res.device_id));

      await saveIdentity({
        ik_priv_jwk:  ik.privJwk,
        ik_pub_raw:   ik.pubRaw,
        spk_priv_jwk: spk.privJwk,
        spk_pub_raw:  spk.pubRaw,
        spk_sig:      spk.sig,
        user_id:      res.user_id,
        device_id:    res.device_id,
      });

    } else {
      res = await apiPost("/login", {
        nickname,
        password,
        device_name: navigator.userAgent.slice(0, 60),
        ik_pub:  "",
        spk_pub: "",
        spk_sig: "",
      });

      setToken(res.token);
      localStorage.setItem("user_id", String(res.user_id));
      localStorage.setItem("device_id", String(res.device_id));

      let restored = false;
      try {
        const backup = await apiGet("/keys/backup");
        if (backup && backup.encrypted_blob) {
          const combinedBlob = decodeKey(backup.encrypted_blob);
          const jsonStr = new TextDecoder().decode(combinedBlob);
          const backupData = JSON.parse(jsonStr);

          if (backupData.version !== 2) {
            throw new Error("Неподдерживаемая версия облачного бэкапа");
          }

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

          // Validate keys mathematically
          const ikPrivKey = await importX25519Priv(decrypted.ik_priv_jwk);
          const spkPrivKey = await importX25519Priv(decrypted.spk_priv_jwk);
          const sigPrivKey = await window.crypto.subtle.importKey(
            "jwk",
            decrypted.sig_priv_jwk,
            { name: "Ed25519" },
            true,
            ["sign"]
          );

          restored = true;

          ik = {
            privateKey: ikPrivKey,
            pubRaw: new Uint8Array(decrypted.ik_pub_raw),
            privJwk: decrypted.ik_priv_jwk,
            sigPrivateKey: sigPrivKey,
            sigPubRaw: new Uint8Array(decrypted.sig_pub_raw),
            sigPrivJwk: decrypted.sig_priv_jwk
          };
          spk = {
            pubRaw: new Uint8Array(decrypted.spk_pub_raw),
            privJwk: decrypted.spk_priv_jwk,
            sig: new Uint8Array(decrypted.spk_sig),
          };

          if (decrypted.contacts && Array.isArray(decrypted.contacts)) {
            for (const c of decrypted.contacts) {
              await saveContact(c);
            }
          }
          if (decrypted.messages && Array.isArray(decrypted.messages)) {
            for (const m of decrypted.messages) {
              await saveMessage(m);
            }
          }

          restored = true;
        }
      } catch (backupErr) {
        console.error("Не удалось восстановить бэкап с сервера. Детали ошибки:", backupErr);
      }

      if (restored) {
        await saveIdentity({
          ik_priv_jwk:  ik.privJwk,
          ik_pub_raw:   ik.pubRaw,
          sig_priv_jwk: ik.sigPrivJwk,
          sig_pub_raw:  ik.sigPubRaw,
          spk_priv_jwk: spk.privJwk,
          spk_pub_raw:  spk.pubRaw,
          spk_sig:      spk.sig,
          user_id:      res.user_id,
          device_id:    res.device_id,
        });

        const combinedIKPub = new Uint8Array(64);
        combinedIKPub.set(ik.pubRaw, 0);
        combinedIKPub.set(ik.sigPubRaw, 32);

        await apiPost("/keys/init", {
          ik_pub:  encodeKey(combinedIKPub),
          spk_pub: encodeKey(spk.pubRaw),
          spk_sig: encodeKey(spk.sig),
        });

        showToast("Ключи шифрования восстановлены из облачного бэкапа!", "success");
      } else {
        ik  = await generateIdentityKeyPair();
        spk = await generateSignedPreKey(ik.sigPrivateKey);

        await saveIdentity({
          ik_priv_jwk:  ik.privJwk,
          ik_pub_raw:   ik.pubRaw,
          sig_priv_jwk: ik.sigPrivJwk,
          sig_pub_raw:  ik.sigPubRaw,
          spk_priv_jwk: spk.privJwk,
          spk_pub_raw:  spk.pubRaw,
          spk_sig:      spk.sig,
          user_id:      res.user_id,
          device_id:    res.device_id,
        });

        const combinedIKPub = new Uint8Array(64);
        combinedIKPub.set(ik.pubRaw, 0);
        combinedIKPub.set(ik.sigPubRaw, 32);

        await apiPost("/keys/init", {
          ik_pub:  encodeKey(combinedIKPub),
          spk_pub: encodeKey(spk.pubRaw),
          spk_sig: encodeKey(spk.sig),
        });

        try {
          const dataToBackup = {
            ik_priv_jwk:  ik.privJwk,
            ik_pub_raw:   Array.from(ik.pubRaw),
            sig_priv_jwk: ik.sigPrivJwk,
            sig_pub_raw:  Array.from(ik.sigPubRaw),
            spk_priv_jwk: spk.privJwk,
            spk_pub_raw:  Array.from(spk.pubRaw),
            spk_sig:      Array.from(spk.sig),
            user_id:      res.user_id,
          };

          let backupPassword = password;
          let backupMethod = "password";

          const usePin = confirm(
            "На сервере не найден бэкап ключей шифрования. Хотите защитить создаваемую резервную копию ключей E2EE отдельным PIN-кодом/паролем?\n\n" +
            "Если вы выберете 'Отмена', резервная копия будет зашифрована вашим паролем аккаунта."
          );
          if (usePin) {
            let pin = "";
            while (pin.length < 6) {
              pin = await showPinModal("Придумайте PIN-код или пароль для резервной копии ключей (не менее 6 символов):", "PIN-код (от 6 символов)") || "";
              if (pin === "") {
                backupMethod = "password";
                backupPassword = password;
                break;
              }
              if (pin.length < 6) {
                alert("Слишком короткий пароль/PIN (минимум 6 символов)!");
              } else {
                backupMethod = "pin";
                backupPassword = pin;
              }
            }
          }

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

        showToast("Сгенерированы новые ключи шифрования E2EE!", "info");
      }
    }

    const opks = await generateOneTimeKeys(100);
    await saveOPKs(opks);
    const opkList = opks.map(o => encodeKey(o.pubRaw));
    await apiPost("/keys/otk", { opk_list: opkList }).catch(() => {});

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
