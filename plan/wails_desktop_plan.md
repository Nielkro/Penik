# План: Desktop-клиент на Wails (Go + WebView2/WebKitGTK) — Windows + Linux

**Status: implemented**

## 1. Цель
Упаковать существующий веб-клиент `client/` в нативное desktop-приложение через Wails v2.
Windows собирается с WebView2, Linux — с WebKitGTK, фронт общий на 100% (чаты, группы, E2EE через `penik-crypto.wasm`, звонки через `livekit-client`).
Go пишется только как тонкий бридж: окно, трей, уведомления, файловые диалоги, конфиг сервера.

Почему Wails, а не Tauri: на машине разработчика уже есть Go (`server/go.mod`, go 1.27) и Node (`client/package.json`).
Wails добавляет только CLI (~десятки МБ), тогда как Tauri тянет Rust toolchain + `target/` на несколько ГБ.

## 2. Границы
- НЕ переписываем UI и НЕ портируем крипту: `client/js/crypto.js` + WASM остаются как есть.
- НЕ трогаем протокол: REST (`Docs/REST_API.md`) и WS MsgPack (`Docs/WEBSOCKET.md`, опкоды `0x01-0x39`) используются фронтом напрямую.
- Новый код живет только в `desktop/`, существующие `server/`, `client/`, `android/`, `rust/` не меняются (кроме `wails.json` ссылки на `../client` и CI).

## 3. Структура
```
desktop/
  go.mod              # module penik-desktop, go 1.27, отдельно от messenger/server
  main.go             # wails.Run: окно, WebView2, инжект __PENIK_API_ORIGIN__
  app.go              # бридж-методы, доступные из JS
  wails.json          # frontend dir: ../client, build: npm run build (vite + build-sw.js)
  build/windows/      # icon.ico (из logo_fixed.webp), installer.nsi, метаданные exe
```

## 4. Этапы
### Этап 0. Подготовка (0.5 дня)
- Windows: MSVC Build Tools, WebView2 Runtime, Go 1.27, Node.
- Linux (для сборки/проверки): `gcc`, `libgtk-3-dev`, `libwebkit2gtk-4.1-dev`, `pkg-config`, Go 1.27, Node.
- `go install github.com/wailsapp/wails/v2/cmd/wails@latest && wails doctor` (на обеих ОС).

### Этап 1. Каркас (день 1)
- `wails init` в `desktop/`, `wails.json` привязать к `../client` (билд `vite build && node scripts/build-sw.js` из `client/package.json`).
- `main.go`: окно 1200x800, минимум 360x600, WebView2, devtools только в debug.
- Критерий: `wails dev` показывает текущий веб-чат в нативном окне.

### Этап 2. Конфиг сервера (день 1-2)
- Фронт уже умеет `window.__PENIK_API_ORIGIN__` (`client/js/api.js`) — десктоп его задает.
- Go: `GetServerURL / SetServerURL`, хранение `%APPDATA%/penik/config.json` на Windows и `~/.config/penik/config.json` на Linux (путь через `os.UserConfigDir()` — один код на обе ОС).
- `device_name`: `"Windows Desktop"` / `"Linux Desktop"` при register/login (аналог `ApiConfig.kt` / `ApiService.kt` на Android).
- Экран первого запуска: ввод адреса сервера.
- Проверка против `GET /api/v1/health` и `GET /api/v1/time`.

### Этап 3. Нативный бридж (день 2-4)
Минимальный `app.go`:
- `Notify(title, body)` — Win Toast; клик разворачивает окно (`WindowUnminimise + Show`).
- `OpenFileDialog / SaveFileDialog` — вложения и `.penikbackup` (`client/js/backup.js`, `AttachmentManager.kt` как референс формата).
- `GetVersion()` — сверка с `GET /api/v1/version` + `version.json` (политика как в `AppUpdateManager.kt`).
- Трей: свернуть в трей, бейдж непрочитанных, автозапуск через `HKCU\...\Run`.
- Звонки отдельно не реализуем: `client/js/call.js` + `livekit-client` работают в WebView2, только разрешить mic/camera.

### Этап 4. Упаковка (день 4-5)
- Windows: `wails build -platform windows/amd64 -nsis` → `penik-setup.exe` (плюс portable `.exe` рядом).
- Linux: `wails build -platform linux/amd64` → `.deb` / AppImage; иконка из `logo_fixed.webp`, `.desktop`-файл.
- CI `.github/workflows/desktop.yml` по образцу `android.yml`: две job (windows-latest, ubuntu-latest), SHA256, релиз, версия из `version.json`.

### Этап 5. Проверка (день 5-7)
- `scripts/run_e2e.py` / `tests/e2e/test_runner.py` — REST+WS+E2EE десктопа обязаны совпасть с вебом (фронт тот же).
- Ручное: логин, личка, группы + ротация ключей, вложения (upload + Range-скачивание), экспорт/импорт `.penikbackup`, звонок 1-1.

## 5. Риски
- WebView2 старый на машине пользователя → бандлить Evergreen Runtime или проверять при установке.
- Файловые пики WebView2 отличаются от Electron — покрыть `OpenFileDialog/SaveFileDialog` бриджем, а не полагаться на `<input type=file>` везде.
- Антивирусы любят ругаться на свежие NSIS-инсталлеры без подписи — релизы подписывать либо отдавать portable `.exe` рядом.

## 6. Оценка
MVP (этапы 0-4): 4-5 дней. Полировка + CI + ручное тестирование (этап 5): до 7 дней.
