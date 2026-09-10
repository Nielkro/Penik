# Технический план: Микро-ядро криптографии Penik на Rust (`penik-crypto`)

## 1. Цели и границы проекта

### 1.1 Цель
Создать единое, высокопроизводительное и безопасное микро-ядро криптографии на **Rust** (`penik-crypto`), которое заменит дублирующиеся реализации криптографии в:
- **Web-клиенте** (сейчас: `libsodium-wrappers` + `crypto.subtle` в `client/js/crypto.js`) через **WebAssembly (WASM)**.
- **Android-клиенте** (сейчас: Java Crypto / BouncyCastle в `E2EECrypto.kt` и `SafetyNumber.kt`) через **UniFFI** (автоматические Kotlin-биндинги + нативные `.so`).

### 1.2 Ключевые требования
1. **100% обратная совместимость по Wire Format:** Старые клиенты (Android на Java, Web на JS, Python E2E-тесты) обязаны без сбоев читать сообщения, зашифрованные новым ядром на Rust, и наоборот.
2. **Идентичность Safety Numbers:** Хэши и 5-значные цифровые блоки кодов безопасности должны побайтово совпадать между старыми и новыми клиентами.
3. **Безопасность памяти (Zeroize):** Автоматическое затирание нулями приватных ключей и промежуточных секретов (`ZeroizeOnDrop`) при выходе из области видимости.
4. **Легковесность:** WASM-бандл для веба не более 150–250 КБ; `.so` для Android не более 1–2 МБ на архитектуру.
5. **Изоляция ответственности:** Хранение ключей на диске (Android KeyStore, IndexedDB) и сетевой транспорт остаются за нативными платформами. Ядро отвечает только за чистые криптографические трансформации в памяти.

---

## 2. Анализ текущего криптографического протокола Penik (V1)

Для обеспечения совместимости Rust-ядро должно реализовать следующие параметры:

| Компонент | Текущая спецификация Penik V1 | Крейт Rust |
|---|---|---|
| **Асимметричные ключи** | Curve25519 (X25519), 32 байта сырых данных | `x25519-dalek = { version = "2", features = ["zeroize", "reusable_secrets"] }` |
| **Симметричный шифр** | ChaCha20-Poly1305 (RFC 8439, 12 байт nonce, 16 байт Poly1305 tag) | `chacha20poly1305 = "0.10"` |
| **Деривация ключей** | HKDF-SHA256 (соль 32 байта, `info = "penik-pairwise-message-v1"`, ключ 32 байта) | `hkdf = "0.12"`, `sha2 = "0.10"` |
| **Структура AAD** | Канонический префикс длин (Big-Endian `u32` length + UTF-8 payload) для 5 полей: `["1", sender_id, recipient_id, client_msg_id, timestamp]` | `byteorder = "1.5"` или встроенный `to_be_bytes()` |
| **Коды безопасности** | Лексикографическая сортировка ключей `min(k1, k2) + max(k1, k2)` → SHA-256 → пять 5-значных чисел `(chunk[0] << 8 | chunk[1]) % 100000` | `sha2 = "0.10"` |
| **Легаси-форматы ключей** | Поддержка ключей 32 байта (raw), 44 байта (Base64 ASCII), 33 байта (префикс `0x05`) | Встроенная логика нормализации (`keys::normalize_public_key`) |

---

## 3. Архитектура крейта `rust/penik-crypto`

### 3.1 Структура каталогов

```
rust/
└── penik-crypto/
    ├── Cargo.toml
    ├── build.rs                  # Для кодогенерации UniFFI
    ├── tests/
    │   ├── wire_compat_tests.rs  # Тесты на совместимость с известными векторами Penik
    │   └── safety_tests.rs       # Проверка совпадения Safety Numbers
    └── src/
        ├── lib.rs                # Корневой модуль, pub API
        ├── errors.rs             # Единые типизированные ошибки CryptoError
        ├── keys.rs               # Генерация X25519, нормализация, DH Shared Secret
        ├── kdf.rs                # HKDF-SHA256 для pairwise сообщений и файлов
        ├── aad.rs                # Каноническая сборка AAD (Big-Endian длины)
        ├── cipher.rs             # ChaCha20-Poly1305 шифрование/дешифрование
        ├── safety.rs             # Расчет Safety Numbers (хэш, блоки, QR-данные)
        ├── wasm.rs               # #[wasm_bindgen] интерфейсы для Web (JS/TS)
        └── ffi.rs                # #[uniffi::export] интерфейсы для Android (Kotlin)
```

### 3.2 Зависимости `Cargo.toml`

```toml
[package]
name = "penik-crypto"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib", "rlib"]

[features]
default = []
wasm = ["wasm-bindgen", "getrandom/js"]
uniffi-bindings = ["uniffi"]

[dependencies]
x25519-dalek = { version = "2.0", features = ["static_secrets", "zeroize"] }
chacha20poly1305 = { version = "0.10", features = ["zeroize"] }
hkdf = "0.12"
sha2 = "0.10"
rand_core = { version = "0.6", features = ["getrandom"] }
zeroize = { version = "1.8", features = ["derive", "zeroize_derive"] }
thiserror = "2.0"
base64 = "0.22"

# Опционально для Web
wasm-bindgen = { version = "0.2", optional = true }
getrandom = { version = "0.2", optional = true }

# Опционально для Android
uniffi = { version = "0.28", optional = true }

[build-dependencies]
uniffi = { version = "0.28", features = ["build"], optional = true }
```

---

## 4. Пошаговый план реализации

### Этап 1: Реализация чистого Rust-ядра и тестовых векторов
- [ ] Инициализировать крейт `rust/penik-crypto`.
- [ ] Реализовать `keys.rs`: генерация ключевой пары X25519, нормализация 32/33/44-байтных ключей, скалярное умножение (Diffie-Hellman).
- [ ] Реализовать `aad.rs`: сборка канонического AAD со строгим совпадением логики `E2EECrypto.kt:115` и `crypto.js`.
- [ ] Реализовать `kdf.rs` и `cipher.rs`: HKDF с `info="penik-pairwise-message-v1"` и шифрование ChaCha20-Poly1305.
- [ ] Реализовать `safety.rs`: вычисление блоков Safety Number и отпечатка.
- [ ] Написать интеграционные тесты с фиксированными тестовыми векторами (Alice/Bob ключи и сообщения из `tests/e2e/test_runner.py`), чтобы гарантировать побайтовое совпадение с текущей системой.

### Этап 2: Интеграция с Web (WASM)
- [ ] Настроить модуль `wasm.rs` с экспортом структур `JsEncryptedPayload` и функций `encrypt_direct_message`, `decrypt_direct_message`, `get_safety_number`.
- [ ] Настроить скрипт сборки через `wasm-pack build --target web --out-dir ../../client/pkg/penik-crypto-wasm`.
- [ ] Подключить WASM в `client/js/crypto.js` (с сохранением прежнего интерфейса функций `encryptDirectMessage`, `decryptDirectMessage` и `computeSafetyNumber`).
- [ ] Запустить сборку и проверку типов: `npm run typecheck && npm run build` в каталоге `client/`.

### Этап 3: Интеграция с Android (UniFFI)
- [ ] Настроить экспорт UniFFI в `ffi.rs`.
- [ ] Настроить `cargo-ndk` для генерации `.so` под `arm64-v8a`, `armeabi-v7a`, `x86_64` в `android/app/src/main/jniLibs/`.
- [ ] Сгенерировать Kotlin-биндинги в `android/app/src/main/java/niel/kro/penik/data/crypto/rust/`.
- [ ] Добавить вызов Rust-ядра в `E2EECrypto.kt` и `SafetyNumber.kt`.
- [ ] Проверить сборку Android: `bash ./gradlew compileDebugKotlin` из каталога `android/`.

### Этап 4: Сквозное тестирование совместимости (E2E)
- [ ] Запустить полный E2E раннер: `python3 scripts/run_e2e.py`.
- [ ] Убедиться, что все 34 теста (включая E2EE direct messages, offline batching, attachments, safety numbers) проходят успешно.
- [ ] Обновить `PROJECT_MAP.md`.
- [ ] Зафиксировать результат коммитом по правилам Conventional Commits.

---

## 5. Риски и стратегии их снижения

1. **Риск: расхождение в AAD при дешифровании.**
   - *Решение:* Модульный тест в Rust с точной копией байтовой последовательности, сформированной Android-клиентом.
2. **Риск: асинхронность загрузки WASM в браузере.**
   - *Решение:* В Penik уже реализован шаблон ожидания готовности (`_sodiumReady` в `client/js/crypto.js`). WASM инициализируется аналогично при старте приложения без блокировки интерфейса.
3. **Риск: увеличение времени сборки Android.**
   - *Решение:* Сборка `.so` выносится в отдельный скрипт/таску Gradle, запускаемую только при изменении исходников в `rust/penik-crypto/`.
