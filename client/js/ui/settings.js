import { el, showToast, spinner, formatFullTime, avatar, showConfirmModal, formatDate, formatTime } from "./components.js";
import { getTheme, setTheme } from "../theme.js";
import {
  listDevices,
  apiPatch,
  apiPost,
  apiGet,
  createPairingSession,
  getPairingSession,
  uploadPairingHistory,
  getApiOrigin
} from "../api.js";
import {
  getCurrentUser,
  navigate,
  logout,
  backupE2EEKeys,
  restoreE2EEKeys,
  getDevicesBackTarget,
  setDevicesBackTarget
} from "../app.js";
import {
  deriveSharedSecret,
  encryptPairingHistory,
  generateKeyPair
} from "../crypto.js";
import { importPairingHistory } from "../pairing.js";
import {
  getAllMessages,
  getAllContacts,
  getAllGroups,
  getAllGroupMembers,
  getAllGroupKeysPlain,
  getAllGroupMessages
} from "../storage.js";
import { ws, OP } from "../ws.js";
import QRCode from "qrcode";
import {
  exportHistoryToBackup,
  importHistoryFromBackup,
  downloadBackupFile,
  readBackupFile
} from "../backup.js";
import { generateMnemonicPhrase, RUSSIAN_DICTIONARY } from "../wordcoder.js";

const decodeB64Url = s => {
  const normalized = String(s).trim().replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, c => c.charCodeAt(0));
};

const encodeB64Url = b => {
  const bytes = b instanceof Uint8Array ? b : new Uint8Array(b);
  let binary = "";
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
};

const pack = ({ ciphertext, salt, nonce }) =>
  new TextEncoder().encode(JSON.stringify({
    ciphertext: encodeB64Url(ciphertext),
    salt: encodeB64Url(salt),
    nonce: encodeB64Url(nonce)
  }));

const createSection = (titleText, ...children) => {
  return el("div", { style: "display:flex;flex-direction:column;gap:8px;margin-top:16px;" },
    el("span", { style: "font-size:12px;font-weight:600;text-transform:uppercase;color:var(--text-muted);letter-spacing:0.5px;padding-left:4px;" }, titleText),
    ...children
  );
};

function showPassphraseWizardModal({ title, description, confirmLabel, onConfirmed }) {
  const modal = el("div", { style: "position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;padding:20px;" });
  const close = () => modal.remove();
  modal.addEventListener("click", event => { if (event.target === modal) close(); });

  const container = el("div", { style: "width:min(440px,100%);background:var(--panel);border:1px solid var(--border);border-radius:16px;padding:24px;text-align:left;box-shadow:0 12px 40px rgba(0,0,0,.45);max-height:90vh;overflow-y:auto;" });
  modal.appendChild(container);

  let currentStep = "select"; // 'select' | 'password' | 'mnemonic' | 'verify'
  let mnemonicPhrase = "";
  let mnemonicWords = [];
  let quizIdx1 = 0;
  let quizIdx2 = 1;
  let quizOptions1 = [];
  let quizOptions2 = [];
  let quizSelected1 = null;
  let quizSelected2 = null;

  function renderStep() {
    container.innerHTML = "";

    if (currentStep === "select") {
      const header = el("h3", { style: "margin:0 0 8px;color:var(--text);font-size:18px;" }, title);
      const desc = el("p", { style: "margin:0 0 16px;color:var(--text-muted);font-size:13px;line-height:1.4;" }, description);

      const passCard = el("div", {
        style: "flex:1;background:var(--bg);border:1px solid var(--border);border-radius:14px;padding:16px;cursor:pointer;display:flex;flex-direction:column;justify-content:space-between;height:120px;transition:border-color .15s;"
      },
        el("span", { style: "font-size:26px;" }, "🔑"),
        el("div", {},
          el("div", { style: "color:var(--text);font-weight:700;font-size:15px;margin-bottom:2px;" }, "Свой пароль"),
          el("div", { style: "color:var(--text-muted);font-size:11px;" }, "Личный пароль")
        )
      );
      passCard.addEventListener("click", () => {
        currentStep = "password";
        renderStep();
      });

      const mnemonicCard = el("div", {
        style: "flex:1;background:var(--bg);border:1px solid var(--border);border-radius:14px;padding:16px;cursor:pointer;display:flex;flex-direction:column;justify-content:space-between;height:120px;transition:border-color .15s;"
      },
        el("span", { style: "font-size:26px;" }, "🎲"),
        el("div", {},
          el("div", { style: "color:var(--text);font-weight:700;font-size:15px;margin-bottom:2px;" }, "12 слов"),
          el("div", { style: "color:var(--text-muted);font-size:11px;" }, "Seed-фраза")
        )
      );
      mnemonicCard.addEventListener("click", () => {
        mnemonicPhrase = generateMnemonicPhrase(12);
        mnemonicWords = mnemonicPhrase.split(" ").filter(Boolean);
        quizIdx1 = Math.floor(Math.random() * 6);
        quizIdx2 = 6 + Math.floor(Math.random() * 6);

        const decoys1 = RUSSIAN_DICTIONARY.filter(w => w !== mnemonicWords[quizIdx1]).sort(() => Math.random() - 0.5).slice(0, 3);
        quizOptions1 = [...decoys1, mnemonicWords[quizIdx1]].sort(() => Math.random() - 0.5);

        const decoys2 = RUSSIAN_DICTIONARY.filter(w => w !== mnemonicWords[quizIdx2]).sort(() => Math.random() - 0.5).slice(0, 3);
        quizOptions2 = [...decoys2, mnemonicWords[quizIdx2]].sort(() => Math.random() - 0.5);

        quizSelected1 = null;
        quizSelected2 = null;

        currentStep = "mnemonic";
        renderStep();
      });

      const cardsRow = el("div", { style: "display:flex;gap:12px;margin-bottom:16px;" }, passCard, mnemonicCard);
      const cancelBtn = el("button", { class: "btn-ghost", style: "width:100%;cursor:pointer;", onclick: close }, "Отмена");

      container.append(header, desc, cardsRow, cancelBtn);

    } else if (currentStep === "password") {
      const header = el("h3", { style: "margin:0 0 8px;color:var(--text);font-size:18px;" }, "Свой пароль");
      const desc = el("p", { style: "margin:0 0 16px;color:var(--text-muted);font-size:13px;line-height:1.4;" },
        "Придумайте надёжный пароль (минимум 6 символов) для шифрования."
      );

      const passInput = el("input", {
        type: "password",
        placeholder: "Пароль",
        class: "profile-input",
        style: "width:100%;padding:10px 12px;border-radius:var(--r);background:var(--bg);border:1px solid var(--border);color:var(--text);font-size:14px;box-sizing:border-box;"
      });

      const saveBtn = el("button", { class: "btn-primary", style: "width:100%;margin-top:16px;cursor:pointer;" }, confirmLabel);
      saveBtn.addEventListener("click", async () => {
        const pass = passInput.value.trim();
        if (!pass || pass.length < 6) {
          showToast("Пароль должен содержать минимум 6 символов", "error");
          return;
        }
        await handleExecute(pass, saveBtn);
      });

      const backBtn = el("button", { class: "btn-ghost", style: "width:100%;margin-top:8px;cursor:pointer;" }, "Назад");
      backBtn.addEventListener("click", () => {
        currentStep = "select";
        renderStep();
      });

      container.append(header, desc, passInput, saveBtn, backBtn);

    } else if (currentStep === "mnemonic") {
      const header = el("h3", { style: "margin:0 0 8px;color:var(--text);font-size:18px;" }, "12 слов (Мнемоника)");
      const desc = el("p", { style: "margin:0 0 12px;color:var(--text-muted);font-size:13px;line-height:1.4;" },
        "Запишите эти 12 слов в точном порядке и сохраните в надёжном месте. Они понадобятся для восстановления:"
      );

      const wordGrid = el("div", { style: "display:grid;grid-template-columns:repeat(3, 1fr);gap:6px;margin-bottom:12px;" },
        ...mnemonicWords.map((w, idx) => el("div", {
          style: "background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:6px 8px;font-size:12px;display:flex;align-items:center;gap:4px;"
        },
          el("span", { style: "color:var(--accent);font-size:11px;font-weight:700;" }, `${idx + 1}.`),
          el("span", { style: "color:var(--text);font-weight:500;" }, w)
        ))
      );

      const copyBtn = el("button", { class: "btn-secondary", style: "width:100%;font-size:12px;padding:8px;margin-bottom:12px;cursor:pointer;" }, "📋 Скопировать фразу");
      copyBtn.addEventListener("click", () => {
        navigator.clipboard.writeText(mnemonicPhrase);
        showToast("Мнемоническая фраза скопирована в буфер!", "success");
      });

      const verifyBtn = el("button", { class: "btn-secondary", style: "flex:1;cursor:pointer;padding:10px;" }, "Проверить слова");
      verifyBtn.addEventListener("click", () => {
        currentStep = "verify";
        renderStep();
      });

      const directSaveBtn = el("button", { class: "btn-primary", style: "flex:1;cursor:pointer;padding:10px;" }, confirmLabel);
      directSaveBtn.addEventListener("click", async () => {
        await handleExecute(mnemonicPhrase, directSaveBtn);
      });

      const actionRow = el("div", { style: "display:flex;gap:8px;margin-bottom:8px;" }, verifyBtn, directSaveBtn);
      const backBtn = el("button", { class: "btn-ghost", style: "width:100%;cursor:pointer;" }, "Назад");
      backBtn.addEventListener("click", () => {
        currentStep = "select";
        renderStep();
      });

      container.append(header, desc, wordGrid, copyBtn, actionRow, backBtn);

    } else if (currentStep === "verify") {
      const header = el("h3", { style: "margin:0 0 8px;color:var(--text);font-size:18px;" }, "Проверка мнемоники");
      const desc = el("p", { style: "margin:0 0 12px;color:var(--text-muted);font-size:13px;line-height:1.4;" },
        "Подтвердите сохранность фразы. Выберите указанные слова из предложенных вариантов:"
      );

      const quiz1Container = el("div", { style: "margin-bottom:12px;" },
        el("div", { style: "font-size:13px;font-weight:700;color:var(--text);margin-bottom:6px;" }, `Слово #${quizIdx1 + 1}:`)
      );
      const chipsRow1 = el("div", { style: "display:grid;grid-template-columns:repeat(2, 1fr);gap:6px;" });
      quizOptions1.forEach(word => {
        const isSel = (quizSelected1 === word);
        const chip = el("button", {
          style: `padding:8px 6px;border-radius:8px;border:1px solid ${isSel ? 'var(--accent)' : 'var(--border)'};background:${isSel ? 'var(--accent)' : 'var(--bg)'};color:${isSel ? '#fff' : 'var(--text)'};font-size:12px;cursor:pointer;font-weight:500;`
        }, word);
        chip.addEventListener("click", () => {
          quizSelected1 = word;
          renderStep();
        });
        chipsRow1.appendChild(chip);
      });
      quiz1Container.appendChild(chipsRow1);

      const quiz2Container = el("div", { style: "margin-bottom:16px;" },
        el("div", { style: "font-size:13px;font-weight:700;color:var(--text);margin-bottom:6px;" }, `Слово #${quizIdx2 + 1}:`)
      );
      const chipsRow2 = el("div", { style: "display:grid;grid-template-columns:repeat(2, 1fr);gap:6px;" });
      quizOptions2.forEach(word => {
        const isSel = (quizSelected2 === word);
        const chip = el("button", {
          style: `padding:8px 6px;border-radius:8px;border:1px solid ${isSel ? 'var(--accent)' : 'var(--border)'};background:${isSel ? 'var(--accent)' : 'var(--bg)'};color:${isSel ? '#fff' : 'var(--text)'};font-size:12px;cursor:pointer;font-weight:500;`
        }, word);
        chip.addEventListener("click", () => {
          quizSelected2 = word;
          renderStep();
        });
        chipsRow2.appendChild(chip);
      });
      quiz2Container.appendChild(chipsRow2);

      const isCorrect = (quizSelected1 === mnemonicWords[quizIdx1]) && (quizSelected2 === mnemonicWords[quizIdx2]);

      const doneBtn = el("button", {
        class: "btn-primary",
        style: `width:100%;margin-bottom:8px;cursor:${isCorrect ? 'pointer' : 'not-allowed'};opacity:${isCorrect ? '1' : '0.5'};`
      }, `Готово, ${confirmLabel.toLowerCase()}`);
      doneBtn.disabled = !isCorrect;
      doneBtn.addEventListener("click", async () => {
        if (!isCorrect) return;
        await handleExecute(mnemonicPhrase, doneBtn);
      });

      const backBtn = el("button", { class: "btn-ghost", style: "width:100%;cursor:pointer;" }, "Назад к фразе");
      backBtn.addEventListener("click", () => {
        currentStep = "mnemonic";
        renderStep();
      });

      container.append(header, desc, quiz1Container, quiz2Container, doneBtn, backBtn);
    }
  }

  async function handleExecute(passphrase, button) {
    button.disabled = true;
    const origText = button.textContent;
    button.textContent = "";
    button.appendChild(spinner());
    try {
      await onConfirmed(passphrase);
      close();
    } catch (e) {
      showToast("Ошибка: " + e.message, "error");
    } finally {
      button.disabled = false;
      button.textContent = origText;
    }
  }

  renderStep();
  document.body.appendChild(modal);
}

function showExportBackupModal() {
  showPassphraseWizardModal({
    title: "Экспорт всей истории",
    description: "История чатов, сообщений, групп и ключи шифрования будут сохранены в зашифрованный файл .penikbackup (AES-256-GCM / PBKDF2 600,000).",
    confirmLabel: "Сохранить файл",
    onConfirmed: async (passphrase) => {
      const json = await exportHistoryToBackup(passphrase);
      downloadBackupFile(json);
      showToast("Файл резервной копии .penikbackup успешно сохранён!", "success");
    }
  });
}

function showCloudBackupModal() {
  showPassphraseWizardModal({
    title: "Резервная копия ключей в облаке",
    description: "Зашифруйте ваши ключи E2EE и эпохи групп паролем или мнемонической фразой (12 слов) для безопасного хранения на сервере.",
    confirmLabel: "Создать копию",
    onConfirmed: async (passphrase) => {
      await backupE2EEKeys(passphrase);
      showToast("Резервная копия ключей успешно создана на сервере!", "success");
    }
  });
}

function showCloudRestoreModal() {
  const modal = el("div", { style: "position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;padding:20px;" });
  const close = () => modal.remove();
  modal.addEventListener("click", event => { if (event.target === modal) close(); });

  let selectedBackupId = null;
  const pickerContainer = el("div", { style: "margin-top:12px;display:flex;flex-direction:column;gap:8px;" });

  const passInput = el("input", {
    type: "password",
    placeholder: "Пароль или 12 слов",
    class: "profile-input",
    style: "width:100%;margin-top:12px;padding:10px 12px;border-radius:var(--r);background:var(--bg);border:1px solid var(--border);color:var(--text);font-size:14px;box-sizing:border-box;"
  });

  const restoreBtn = el("button", { class: "btn-primary", style: "width:100%;margin-top:16px;cursor:pointer;" }, "Восстановить ключи");
  restoreBtn.addEventListener("click", async () => {
    const pass = passInput.value.trim();
    if (!pass) {
      showToast("Введите пароль или мнемонику", "error");
      return;
    }
    restoreBtn.disabled = true;
    restoreBtn.textContent = "";
    restoreBtn.appendChild(spinner());
    try {
      await restoreE2EEKeys(pass, selectedBackupId);
      showToast("Ключи шифрования успешно восстановлены!", "success");
      close();
    } catch (e) {
      showToast("Ошибка восстановления: " + e.message, "error");
    } finally {
      restoreBtn.disabled = false;
      restoreBtn.textContent = "Восстановить ключи";
    }
  });

  // Fetch list of available device backups
  apiGet("/keys/backups").then(backups => {
    if (Array.isArray(backups) && backups.length > 0) {
      selectedBackupId = backups[0].id;
      if (backups.length > 1) {
        pickerContainer.appendChild(el("div", { style: "font-size:12px;color:var(--text-muted);font-weight:500;margin-bottom:2px;" }, "Выберите копию устройства:"));
        const renderList = () => {
          pickerContainer.querySelectorAll(".backup-pick-item").forEach(e => e.remove());
          backups.forEach(b => {
            const isSelected = b.id === selectedBackupId;
            const isMobile = b.platform === "android" || b.platform === "ios" || (b.device_name && /android|iphone|phone|pixel|samsung|xiaomi/i.test(b.device_name));
            const icon = isMobile ? "📱" : "💻";
            const ts = b.updated_at || b.created_at;
            const timeText = ts ? `${formatDate(ts)} в ${formatTime(ts)}` : "";

            const item = el("div", {
              class: "backup-pick-item",
              style: `padding:8px 10px;border-radius:8px;cursor:pointer;display:flex;align-items:center;justify-content:space-between;border:1px solid ${isSelected ? "var(--accent)" : "var(--border)"};background:${isSelected ? "rgba(59,130,246,0.12)" : "var(--bg)"};`
            }, [
              el("div", { style: "display:flex;align-items:center;gap:8px;overflow:hidden;" }, [
                el("span", { style: "font-size:16px;" }, icon),
                el("div", { style: "display:flex;flex-direction:column;min-width:0;" }, [
                  el("div", { style: "font-weight:600;font-size:12px;color:var(--text);text-overflow:ellipsis;overflow:hidden;white-space:nowrap;" }, b.device_name || b.platform || "Устройство"),
                  timeText ? el("div", { style: "font-size:10px;color:var(--text-muted);" }, timeText) : null
                ])
              ]),
              el("input", { type: "radio", name: "settings_backup_pick", checked: isSelected, style: "accent-color:var(--accent);cursor:pointer;" })
            ]);

            item.addEventListener("click", () => {
              selectedBackupId = b.id;
              renderList();
            });
            pickerContainer.appendChild(item);
          });
        };
        renderList();
      }
    }
  }).catch(() => {});

  modal.appendChild(el("div", { style: "width:min(400px,100%);background:var(--panel);border:1px solid var(--border);border-radius:16px;padding:24px;text-align:left;box-shadow:0 12px 40px rgba(0,0,0,.45);" },
    el("h3", { style: "margin:0 0 8px;color:var(--text);" }, "Восстановление ключей из облака"),
    el("p", { style: "margin:0 0 16px;color:var(--text-muted);font-size:13px;line-height:1.4;" }, "Введите пароль или мнемоническую фразу (12 слов), которая использовалась при создании резервной копии ключей на сервере."),
    pickerContainer,
    passInput,
    restoreBtn,
    el("button", { class: "btn-ghost", style: "width:100%;margin-top:8px;cursor:pointer;", onclick: close }, "Отмена")
  ));

  document.body.appendChild(modal);
}

function showImportBackupModal() {
  const modal = el("div", { style: "position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;padding:20px;" });
  const close = () => modal.remove();
  modal.addEventListener("click", event => { if (event.target === modal) close(); });

  let selectedFile = null;

  const fileInput = el("input", {
    type: "file",
    accept: ".penikbackup,.json",
    style: "display:none;"
  });

  const fileSelectBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;padding:12px;cursor:pointer;font-size:13px;border-radius:var(--r);text-align:center;"
  }, "📁 Выбрать файл .penikbackup");

  fileSelectBtn.addEventListener("click", () => fileInput.click());

  fileInput.addEventListener("change", () => {
    if (fileInput.files && fileInput.files[0]) {
      selectedFile = fileInput.files[0];
      fileSelectBtn.textContent = `📄 ${selectedFile.name} (${Math.round(selectedFile.size / 1024)} КБ)`;
    }
  });

  const passInput = el("input", {
    type: "password",
    placeholder: "Пароль или мнемоническая фраза файла",
    class: "profile-input",
    style: "width:100%;margin-top:12px;padding:10px 12px;border-radius:var(--r);background:var(--bg);border:1px solid var(--border);color:var(--text);font-size:14px;box-sizing:border-box;"
  });

  const importBtn = el("button", { class: "btn-primary", style: "width:100%;margin-top:16px;cursor:pointer;" }, "Расшифровать и импортировать");
  importBtn.addEventListener("click", async () => {
    if (!selectedFile) {
      showToast("Сначала выберите файл .penikbackup", "error");
      return;
    }
    const pass = passInput.value.trim();
    if (!pass) {
      showToast("Введите пароль или мнемонику для расшифровки", "error");
      return;
    }

    importBtn.disabled = true;
    importBtn.textContent = "";
    importBtn.appendChild(spinner());

    try {
      const fileText = await readBackupFile(selectedFile);
      const res = await importHistoryFromBackup(fileText, pass);
      showToast(`Импорт успешен! Чатов: ${res.chatsCount}, Сообщений: ${res.messagesCount}, Групп: ${res.groupsCount}`, "success");
      close();
      window.location.reload();
    } catch (e) {
      showToast("Ошибка импорта (неверный пароль или повреждённый файл): " + e.message, "error");
    } finally {
      importBtn.disabled = false;
      importBtn.textContent = "Расшифровать и импортировать";
    }
  });

  modal.appendChild(el("div", { style: "width:min(400px,100%);background:var(--panel);border:1px solid var(--border);border-radius:16px;padding:24px;text-align:left;box-shadow:0 12px 40px rgba(0,0,0,.45);" },
    el("h3", { style: "margin:0 0 8px;color:var(--text);" }, "Импорт истории из файла"),
    el("p", { style: "margin:0 0 16px;color:var(--text-muted);font-size:13px;line-height:1.4;" }, "Выберите сохранённый файл .penikbackup и введите пароль или мнемоническую фразу, использованную при экспорте."),
    fileInput,
    fileSelectBtn,
    passInput,
    importBtn,
    el("button", { class: "btn-ghost", style: "width:100%;margin-top:8px;cursor:pointer;", onclick: close }, "Отмена")
  ));

  document.body.appendChild(modal);
}

export function renderSettings(container) {
  container.innerHTML = "";

  const user = getCurrentUser();
  if (!user) {
    navigate("#login");
    return;
  }

  const userId = user.user_id || user.id || "";
  const currentAvatarUser = { ...user };
  if (!currentAvatarUser.avatar_url && userId) {
    const origin = getApiOrigin();
    currentAvatarUser.avatar_url = `${origin}/api/v1/avatar/${userId}?t=${Date.now()}`;
  }

  // --- Header with Back Button ---
  const header = el("div", { class: "profile-header", style: "display:flex;align-items:center;gap:12px;padding:14px 16px;background:var(--panel);border-bottom:1px solid var(--border);position:sticky;top:0;z-index:10;" },
    el("button", { class: "icon-btn", onclick: () => navigate("#chats"), title: "Назад" }, "←"),
    el("h2", { class: "profile-title", style: "margin:0;font-size:18px;font-weight:700;" }, "Настройки")
  );

  // --- Profile Card Header ---
  const userAvatarEl = avatar(currentAvatarUser, 56);
  const profileInfo = el("div", { style: "display:flex;flex-direction:column;flex:1;overflow:hidden;margin-left:14px;" },
    el("span", { style: "font-size:17px;font-weight:600;color:var(--text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;" }, user.name || "Пользователь"),
    el("span", { style: "font-size:13px;color:var(--text-muted);margin-top:2px;" }, `@${user.username || user.nickname || ""}`),
    el("span", { style: "font-size:12px;color:var(--text-muted);opacity:0.8;margin-top:2px;" }, `ID: ${userId}`)
  );
  const editProfileArrow = el("span", { style: "color:var(--text-muted);font-size:18px;padding-right:4px;" }, "›");

  const profileCard = el("div", {
    style: "display:flex;align-items:center;padding:16px 18px;background:var(--panel);border:1px solid var(--border);border-radius:var(--r);cursor:pointer;transition:background 0.15s ease;",
    title: "Редактировать профиль",
    onclick: () => navigate("#profile")
  }, userAvatarEl, profileInfo, editProfileArrow);

  // --- 1. Appearance Section ---
  const currentTheme = getTheme();
  const themeSub = el("span", { style: "font-size:12px;color:var(--text-muted);" }, currentTheme === "light" ? "Светлая" : "Тёмная");
  const themeTextCol = el("div", { style: "display:flex;flex-direction:column;gap:2px;" },
    el("span", { style: "font-size:15px;color:var(--text);font-weight:500;" }, "Тема"),
    themeSub
  );

  const themeToggle = el("button", {
    class: "btn-secondary",
    style: "min-width:110px;cursor:pointer;padding:8px 14px;font-size:13px;"
  }, currentTheme === "light" ? "🌙 Тёмная" : "☀️ Светлая");

  function syncThemeState() {
    const isLight = getTheme() === "light";
    themeSub.textContent = isLight ? "Светлая" : "Тёмная";
    themeToggle.textContent = isLight ? "🌙 Тёмная" : "☀️ Светлая";
  }

  themeToggle.addEventListener("click", () => {
    setTheme(getTheme() === "light" ? "dark" : "light");
    syncThemeState();
  });

  const themeRow = el("div", {
    style: "display:flex;align-items:center;justify-content:space-between;padding:14px 18px;background:var(--panel);border:1px solid var(--border);border-radius:var(--r);"
  }, themeTextCol, themeToggle);

  const appearanceSection = createSection("Оформление", themeRow);

  // --- 2. Security & Account Section ---
  const changePasswordBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:12px 16px;cursor:pointer;font-size:14px;"
  },
    el("span", { style: "color:var(--text);" }, "Сменить пароль"),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );

  const oldPwInput = el("input", {
    type: "password",
    placeholder: "Текущий пароль",
    class: "profile-input hidden",
    style: "width:100%;margin-bottom:8px;padding:10px 12px;border-radius:var(--r);background:var(--bg);border:1px solid var(--border);color:var(--text);font-size:14px;"
  });
  const newPwInput = el("input", {
    type: "password",
    placeholder: "Новый пароль (от 6 символов)",
    class: "profile-input hidden",
    style: "width:100%;margin-bottom:8px;padding:10px 12px;border-radius:var(--r);background:var(--bg);border:1px solid var(--border);color:var(--text);font-size:14px;"
  });
  const submitPwBtn = el("button", {
    class: "btn-primary hidden",
    style: "margin-right:8px;padding:8px 14px;font-size:13px;cursor:pointer;"
  }, "Сохранить новый пароль");
  const cancelPwBtn = el("button", {
    class: "btn-ghost hidden",
    style: "padding:8px 14px;font-size:13px;cursor:pointer;"
  }, "Отмена");

  const revokeCheckbox = el("input", { type: "checkbox", id: "pw-revoke-others", style: "margin:0;cursor:pointer;" });
  const revokeLabel = el("label", {
    class: "hidden",
    for: "pw-revoke-others",
    style: "display:flex;align-items:center;gap:8px;margin-bottom:8px;font-size:12px;color:var(--text-muted);cursor:pointer;",
  }, revokeCheckbox, "Отозвать все сессии кроме текущей");

  changePasswordBtn.addEventListener("click", () => {
    oldPwInput.classList.remove("hidden");
    newPwInput.classList.remove("hidden");
    revokeLabel.classList.remove("hidden");
    submitPwBtn.classList.remove("hidden");
    cancelPwBtn.classList.remove("hidden");
    changePasswordBtn.classList.add("hidden");
    oldPwInput.focus();
  });

  cancelPwBtn.addEventListener("click", () => {
    oldPwInput.value = "";
    newPwInput.value = "";
    revokeCheckbox.checked = false;
    oldPwInput.classList.add("hidden");
    newPwInput.classList.add("hidden");
    revokeLabel.classList.add("hidden");
    submitPwBtn.classList.add("hidden");
    cancelPwBtn.classList.add("hidden");
    changePasswordBtn.classList.remove("hidden");
  });

  submitPwBtn.addEventListener("click", async () => {
    const oldPassword = oldPwInput.value;
    const newPassword = newPwInput.value;
    if (!oldPassword || !newPassword) {
      showToast("Заполните оба поля пароля", "error");
      return;
    }
    if (newPassword.length < 6) {
      showToast("Новый пароль должен быть не менее 6 символов", "error");
      return;
    }

    submitPwBtn.disabled = true;
    const origText = submitPwBtn.textContent;
    submitPwBtn.textContent = "";
    submitPwBtn.appendChild(spinner());

    try {
      const pwRes = await apiPatch("/users/me/password", {
        old_password: oldPassword,
        new_password: newPassword,
        revoke_other_sessions: revokeCheckbox.checked,
      });

      showToast("Пароль успешно изменен!", "success");
      if (revokeCheckbox.checked) {
        if (pwRes && pwRes.revoked_other_sessions) {
          showToast("Остальные сессии отозваны", "success");
        } else if (pwRes && pwRes.revoke_skipped_reason === "session_too_recent") {
          showToast("Сессии не отозваны: этот сеанс младше 24 часов", "info");
        } else {
          showToast("Не удалось отозвать остальные сессии", "error");
        }
      }
      cancelPwBtn.click();
    } catch (err) {
      showToast("Не удалось изменить пароль: " + err.message, "error");
    } finally {
      submitPwBtn.disabled = false;
      submitPwBtn.textContent = origText;
    }
  });

  const revokeSessionsBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 18px;cursor:pointer;"
  },
    el("div", { style: "display:flex;flex-direction:column;align-items:flex-start;gap:2px;" },
      el("span", { style: "color:var(--text);font-size:15px;font-weight:500;" }, "Отозвать все сессии"),
      el("span", { style: "color:var(--text-muted);font-size:12px;" }, "Кроме текущей сессии")
    ),
    el("span", { style: "color:var(--text-muted);font-size:18px;" }, "›")
  );
  revokeSessionsBtn.addEventListener("click", async () => {
    const confirmed = await showConfirmModal(
      "Отозвать все сессии?",
      "Вы действительно хотите завершить все активные сессии на остальных устройствах? На текущем устройстве вы останетесь в аккаунте.",
      "Отозвать",
      "Отмена",
      true
    );
    if (!confirmed) return;

    revokeSessionsBtn.disabled = true;
    const origLabel = revokeSessionsBtn.innerHTML;
    revokeSessionsBtn.textContent = "";
    revokeSessionsBtn.appendChild(spinner());
    try {
      await apiPost("/logout/all");
      showToast("Остальные сессии отозваны", "success");
    } catch (err) {
      showToast(
        err && err.status === 403
          ? "Этот сеанс младше 24 часов — отзыв пока недоступен"
          : (err?.message || "Не удалось отозвать сессии"),
        err && err.status === 403 ? "info" : "error"
      );
    } finally {
      revokeSessionsBtn.disabled = false;
      revokeSessionsBtn.innerHTML = origLabel;
    }
  });

  const securityBox = el("div", {
    style: "background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:4px;display:flex;flex-direction:column;gap:4px;"
  },
    changePasswordBtn,
    oldPwInput,
    newPwInput,
    revokeLabel,
    el("div", { style: "display:flex;padding:0 8px 8px;" }, submitPwBtn, cancelPwBtn),
    revokeSessionsBtn
  );

  const securitySection = createSection("Безопасность и вход", securityBox);

  // --- 3. E2EE Key & History Backup Section ---
  const backupBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 18px;cursor:pointer;"
  },
    el("div", { style: "display:flex;flex-direction:column;align-items:flex-start;gap:2px;" },
      el("span", { style: "color:var(--text);font-size:15px;font-weight:500;" }, "Резервное копирование и ключи"),
      el("span", { style: "color:var(--text-muted);font-size:12px;" }, "Облачные ключи, экспорт и импорт истории")
    ),
    el("span", { style: "color:var(--text-muted);font-size:18px;" }, "›")
  );
  backupBtn.addEventListener("click", () => navigate("#backup"));

  const backupBox = el("div", {
    style: "background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:4px;display:flex;flex-direction:column;"
  }, backupBtn);

  const backupSection = createSection("Резервные копии", backupBox);


  // --- 4. Devices & Pairing Section ---
  const devicesBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:12px 16px;cursor:pointer;font-size:14px;"
  },
    el("span", { style: "color:var(--text);" }, "Мои устройства"),

    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  devicesBtn.addEventListener("click", () => {
    setDevicesBackTarget("#settings");
    navigate("#devices");
  });

  const pairingBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:12px 16px;cursor:pointer;font-size:14px;"
  },
    el("span", { style: "color:var(--text);" }, "Передать историю на устройство (QR)"),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  pairingBtn.addEventListener("click", () => startSendHistoryPairing(pairingBtn));

  const receiveHistoryBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:12px 16px;cursor:pointer;font-size:14px;"
  },
    el("span", { style: "color:var(--text);" }, "Запросить историю с устройства (QR)"),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  receiveHistoryBtn.addEventListener("click", () => startReceiveHistoryPairing(receiveHistoryBtn));

  const devicesBox = el("div", {
    style: "background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:4px;display:flex;flex-direction:column;gap:4px;"
  },
    devicesBtn,
    pairingBtn,
    receiveHistoryBtn
  );

  const devicesSection = createSection("Устройства и синхронизация", devicesBox);

  // --- 5. Logout Section ---
  const logoutBtn = el("button", {
    class: "btn-danger",
    style: "width:100%;padding:14px 18px;cursor:pointer;margin-top:20px;font-size:15px;border-radius:var(--r);font-weight:500;"
  }, "Выйти из аккаунта");
  logoutBtn.addEventListener("click", async () => {
    const confirmed = await showConfirmModal(
      "Выйти из аккаунта?",
      "Вы действительно хотите выйти из текущего аккаунта?",
      "Выйти",
      "Отмена",
      true
    );
    if (!confirmed) return;
    logout();
    showToast("Вы вышли из системы", "info");
  });

  const content = el("div", { style: "display:flex;flex-direction:column;gap:10px;padding:20px;max-width:860px;margin:0 auto;width:100%;box-sizing:border-box;" },
    profileCard,
    appearanceSection,
    securitySection,
    backupSection,
    devicesSection,
    logoutBtn
  );

  const scrollWrapper = el("div", { style: "flex:1;overflow-y:auto;overflow-x:hidden;" }, content);

  const wrap = el("div", { class: "settings-wrap", style: "display:flex;flex-direction:column;height:100%;overflow:hidden;width:100%;" },
    header,
    scrollWrapper
  );

  container.appendChild(wrap);
}

export async function startSendHistoryPairing(btn) {
  if (btn) btn.disabled = true;
  try {
    const kp = await generateKeyPair();
    const keyText = encodeB64Url(kp.publicKey);
    const session = await createPairingSession({ ephemeral_public_key: keyText });
    const payload = `penik-pair-v1:${session.session_id}:${session.token}:${session.ephemeral_public_key}`;
    const canvas = document.createElement("canvas");
    await QRCode.toCanvas(canvas, payload, { width: 280, margin: 2 });
    const modal = el("div", { style: "position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;padding:20px;" });
    const close = () => modal.remove();
    modal.addEventListener("click", event => { if (event.target === modal) close(); });
    modal.appendChild(el("div", { style: "width:min(360px,100%);background:var(--panel);border:1px solid var(--border);border-radius:16px;padding:24px;text-align:center;box-shadow:0 12px 40px rgba(0,0,0,.45);" },
      el("h3", { style: "margin:0 0 10px;color:var(--text);" }, "Передать историю"),
      el("p", { style: "margin:0 0 16px;color:var(--text-muted);font-size:13px;line-height:1.4;" }, "Отсканируйте этот QR-код вторым устройством (телефоном или планшетом) для передачи истории."),
      canvas,
      el("p", { style: "margin:14px 0;color:var(--text-muted);font-size:11px;word-break:break-all;" }, `Сессия: ${session.session_id}`),
      el("button", { class: "btn-ghost", style: "width:100%;cursor:pointer;", onclick: close }, "Закрыть")
    ));
    document.body.appendChild(modal);
    const state = await new Promise((resolve, reject) => {
      let finished = false;
      const finish = value => { if (finished) return; finished = true; clearTimeout(timer); clearInterval(poller); unsubscribe(); resolve(value); };
      const timer = setTimeout(() => { if (!finished) { finished = true; clearInterval(poller); unsubscribe(); reject(new Error("Второе устройство не подтвердило подключение")); } }, 5 * 60 * 1000);
      const unsubscribe = ws.on(OP.PAIRING_CLAIMED, event => {
        if (event.session_id === session.session_id) finish(event);
      });
      const poller = setInterval(async () => {
        try {
          const current = await getPairingSession(session.session_id);
          if (current.claimed && current.public_key) finish(current);
        } catch (_) {}
      }, 1000);
    });
    if (state.public_key) {
      const secret = await deriveSharedSecret(kp.privateKey, decodeB64Url(state.public_key));
      const messages = await getAllMessages();
      const contacts = await getAllContacts();
      const groups = await getAllGroups();
      const groupMembers = await getAllGroupMembers();
      const rawGroupKeys = await getAllGroupKeysPlain();
      const groupKeys = rawGroupKeys.map(k => ({
        ...k,
        key: encodeB64Url(k.key)
      }));
      const groupMessages = await getAllGroupMessages();

      const blob = await encryptPairingHistory({
        messages,
        contacts,
        groups,
        group_members: groupMembers,
        group_keys: groupKeys,
        group_messages: groupMessages
      }, secret);
      const messageIds = (messages || [])
        .map(m => Number(m.msg_id))
        .filter(id => !isNaN(id) && id > 0);

      await uploadPairingHistory(session.session_id, {
        encrypted_history: encodeB64Url(pack(blob)),
        message_ids: messageIds
      });
      close();
      showToast("История успешно передана на устройство!", "success");
    }
  } catch (err) {
    showToast(err.message || "Не удалось создать сессию передачи", "error");
  } finally {
    if (btn) btn.disabled = false;
  }
}

export async function startReceiveHistoryPairing(btn) {
  if (btn) btn.disabled = true;
  try {
    const kp = await generateKeyPair();
    const session = await createPairingSession({
      ephemeral_public_key: encodeB64Url(kp.publicKey),
      transfer_direction: "phone_to_web"
    });
    const payload = `penik-pair-v1:${session.session_id}:${session.token}:${session.ephemeral_public_key}`;
    const canvas = document.createElement("canvas");
    await QRCode.toCanvas(canvas, payload, { width: 280, margin: 2 });
    const modal = el("div", { style: "position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.72);display:flex;align-items:center;justify-content:center;padding:20px;" });
    const close = () => modal.remove();
    modal.appendChild(el("div", { style: "width:min(360px,100%);background:var(--panel);border:1px solid var(--border);border-radius:16px;padding:24px;text-align:center;box-shadow:0 12px 40px rgba(0,0,0,.45);" },
      el("h3", { style: "margin:0 0 10px;color:var(--text);" }, "Запросить историю"),
      el("p", { style: "margin:0 0 16px;color:var(--text-muted);font-size:13px;line-height:1.4;" }, "Отсканируйте QR-код телефоном со всей историей. После сканирования история переписок будет передана и сохранена здесь."),
      canvas,
      el("button", { class: "btn-ghost", style: "width:100%;cursor:pointer;", onclick: close }, "Закрыть")
    ));
    document.body.appendChild(modal);

    await new Promise((resolve, reject) => {
      let finished = false;
      const finish = value => { if (finished) return; finished = true; clearTimeout(timer); clearInterval(poller); unsubscribe(); resolve(value); };
      const unsubscribe = ws.on(OP.PAIRING_CLAIMED, event => {
        if (event.session_id === session.session_id) finish(event);
      });
      const poller = setInterval(async () => {
        try {
          const current = await getPairingSession(session.session_id);
          if (current.claimed && current.public_key) finish(current);
        } catch (_) {}
      }, 1000);
      const timer = setTimeout(() => {
        if (finished) return;
        finished = true;
        clearInterval(poller);
        unsubscribe();
        reject(new Error("Второе устройство не подтвердило передачу"));
      }, 5 * 60 * 1000);
    });

    let state = await getPairingSession(session.session_id);
    const deadline = Date.now() + 5 * 60 * 1000;
    while (!state.encrypted_history && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 1000));
      state = await getPairingSession(session.session_id);
    }
    if (!state.encrypted_history) throw new Error("Устройство не передало историю");
    const secret = await deriveSharedSecret(kp.privateKey, decodeB64Url(state.public_key));
    await importPairingHistory(state.encrypted_history, secret);
    close();
    showToast("История переписок успешно получена и импортирована!", "success");
  } catch (err) {
    showToast(err.message || "Не удалось получить историю", "error");
  } finally {
    if (btn) btn.disabled = false;
  }
}

// renderDevices renders the dedicated devices screen listing the user's devices and history transfer actions.
export function renderDevices(container) {
  const backBtn = el("button", { class: "icon-btn", style: "cursor:pointer;", title: "Назад" }, "←");
  backBtn.addEventListener("click", () => navigate(getDevicesBackTarget()));

  const header = el("div", { class: "profile-header", style: "display:flex;align-items:center;gap:12px;padding:14px 16px;background:var(--panel);border-bottom:1px solid var(--border);" },
    backBtn,
    el("h2", { class: "profile-title", style: "margin:0;font-size:18px;font-weight:700;" }, "Мои устройства")
  );

  // History sync action buttons
  const sendBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 16px;cursor:pointer;font-size:14px;border-radius:var(--r);"
  },
    el("div", { style: "display:flex;flex-direction:column;gap:2px;text-align:left;" },
      el("span", { style: "color:var(--text);font-weight:500;" }, "📤  Передать историю на другое устройство"),
      el("span", { style: "color:var(--text-muted);font-size:12px;" }, "Создать QR-код для безопасной передачи истории на новое устройство")
    ),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  sendBtn.addEventListener("click", () => startSendHistoryPairing(sendBtn));

  const receiveBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 16px;cursor:pointer;font-size:14px;border-radius:var(--r);"
  },
    el("div", { style: "display:flex;flex-direction:column;gap:2px;text-align:left;" },
      el("span", { style: "color:var(--text);font-weight:500;" }, "📥  Запросить историю с другого устройства"),
      el("span", { style: "color:var(--text-muted);font-size:12px;" }, "Показать QR-код для импорта истории со смартфона или другого клиента")
    ),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  receiveBtn.addEventListener("click", () => startReceiveHistoryPairing(receiveBtn));

  const syncBox = el("div", {
    style: "background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:6px;display:flex;flex-direction:column;gap:6px;"
  },
    sendBtn,
    receiveBtn
  );

  const syncSection = createSection("Синхронизация и обмен историей", syncBox);

  // Devices list
  const list = el("div", { style: "display:flex;flex-direction:column;gap:10px;" }, spinner());

  function render(devices) {
    list.innerHTML = "";
    if (!devices || devices.length === 0) {
      list.appendChild(el("div", { style: "color:var(--text-muted);font-size:14px;padding:16px;text-align:center;" }, "Нет подключенных устройств"));
      return;
    }
    for (const d of devices) {
      const title = el("div", { style: "font-size:15px;color:var(--text);font-weight:600;" },
        d.platform || d.device_name || "Устройство",
        d.is_current ? el("span", { style: "margin-left:8px;font-size:12px;color:var(--success);font-weight:normal;" }, "· это устройство") : ""
      );
      const locationLine = el("div", { style: "font-size:13px;color:var(--text-muted);margin-top:4px;" },
        d.location ? `📍 ${d.location}` : "📍 Местоположение неизвестно"
      );
      const meta = el("div", { style: "font-size:12px;color:var(--text-muted);margin-top:4px;" },
        `Активно: ${formatFullTime(d.last_seen * 1000)}`,
        d.is_online
          ? el("span", { style: "margin-left:8px;color:var(--success);" }, "в сети")
          : (d.has_session
              ? el("span", { style: "margin-left:8px;color:var(--text-muted);" }, "не в сети")
              : el("span", { style: "margin-left:8px;color:var(--text-muted);" }, "нет активной сессии"))
      );
      list.appendChild(el("div", {
        style: "padding:14px 18px;background:var(--panel);border:1px solid var(--border);border-radius:var(--r);"
      }, title, locationLine, meta));
    }
  }

  listDevices()
    .then(render)
    .catch(() => {
      list.innerHTML = "";
      list.appendChild(el("div", { style: "color:var(--danger);font-size:13px;padding:12px;" }, "Не удалось загрузить устройства"));
    });

  const devicesListSection = createSection("Подключенные устройства", list);

  const content = el("div", { style: "display:flex;flex-direction:column;gap:12px;padding:20px;max-width:860px;margin:0 auto;width:100%;box-sizing:border-box;" },
    syncSection,
    devicesListSection
  );
  const scrollWrapper = el("div", { style: "flex:1;overflow-y:auto;overflow-x:hidden;" }, content);

  const wrap = el("div", { class: "settings-wrap", style: "display:flex;flex-direction:column;height:100%;overflow:hidden;width:100%;" },
    header,
    scrollWrapper
  );
  container.appendChild(wrap);
}

// renderBackup renders the dedicated backup screen with Cloud Key Backup, File Export/Import, and Mnemonic Phrase.
export function renderBackup(container) {
  const backBtn = el("button", { class: "icon-btn", style: "cursor:pointer;", title: "Назад" }, "←");
  backBtn.addEventListener("click", () => navigate("#settings"));

  const header = el("div", { class: "profile-header", style: "display:flex;align-items:center;gap:12px;padding:14px 16px;background:var(--panel);border-bottom:1px solid var(--border);" },
    backBtn,
    el("h2", { class: "profile-title", style: "margin:0;font-size:18px;font-weight:700;" }, "Резервное копирование")
  );

  // --- 1. Cloud Key Backup ---
  const cloudBackupBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 16px;cursor:pointer;font-size:14px;border-radius:var(--r);"
  },
    el("div", { style: "display:flex;flex-direction:column;gap:2px;text-align:left;" },
      el("span", { style: "color:var(--text);font-weight:500;" }, "☁️  Резервная копия ключей в облаке"),
      el("span", { style: "color:var(--text-muted);font-size:12px;" }, "Зашифровать ключи паролем или 12 словами и сохранить на сервере")
    ),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  cloudBackupBtn.addEventListener("click", () => showCloudBackupModal());

  const cloudRestoreBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 16px;cursor:pointer;font-size:14px;border-radius:var(--r);"
  },
    el("div", { style: "display:flex;flex-direction:column;gap:2px;text-align:left;" },
      el("span", { style: "color:var(--text);font-weight:500;" }, "🔄  Восстановить ключи из облака"),
      el("span", { style: "color:var(--text-muted);font-size:12px;" }, "Восстановить ключи E2EE по паролю или 12 словам")
    ),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  cloudRestoreBtn.addEventListener("click", () => showCloudRestoreModal());

  const cloudBox = el("div", {
    style: "background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:6px;display:flex;flex-direction:column;gap:6px;"
  }, cloudBackupBtn, cloudRestoreBtn);
  const cloudSection = createSection("Облачное хранилище", cloudBox);

  // --- 2. Local History Files ---
  const exportBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 16px;cursor:pointer;font-size:14px;border-radius:var(--r);"
  },
    el("div", { style: "display:flex;flex-direction:column;gap:2px;text-align:left;" },
      el("span", { style: "color:var(--text);font-weight:500;" }, "💾  Экспорт всей истории в файл (.penikbackup)"),
      el("span", { style: "color:var(--text-muted);font-size:12px;" }, "Сохранить зашифрованный файл со всеми чатами, группами и ключами")
    ),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  exportBtn.addEventListener("click", () => showExportBackupModal());

  const importBtn = el("button", {
    class: "btn-secondary",
    style: "width:100%;display:flex;align-items:center;justify-content:space-between;padding:14px 16px;cursor:pointer;font-size:14px;border-radius:var(--r);"
  },
    el("div", { style: "display:flex;flex-direction:column;gap:2px;text-align:left;" },
      el("span", { style: "color:var(--text);font-weight:500;" }, "📂  Импорт истории из файла (.penikbackup)"),
      el("span", { style: "color:var(--text-muted);font-size:12px;" }, "Восстановить историю переписок из локального файла .penikbackup")
    ),
    el("span", { style: "color:var(--text-muted);" }, "›")
  );
  importBtn.addEventListener("click", () => showImportBackupModal());

  const localBox = el("div", {
    style: "background:var(--panel);border:1px solid var(--border);border-radius:var(--r);padding:6px;display:flex;flex-direction:column;gap:6px;"
  }, exportBtn, importBtn);
  const localSection = createSection("Локальная история", localBox);

  const content = el("div", { style: "display:flex;flex-direction:column;gap:12px;padding:20px;max-width:860px;margin:0 auto;width:100%;box-sizing:border-box;" },
    cloudSection,
    localSection
  );
  const scrollWrapper = el("div", { style: "flex:1;overflow-y:auto;overflow-x:hidden;" }, content);

  const wrap = el("div", { class: "settings-wrap", style: "display:flex;flex-direction:column;height:100%;overflow:hidden;width:100%;" },
    header,
    scrollWrapper
  );
  container.appendChild(wrap);
}

