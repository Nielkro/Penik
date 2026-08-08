# Penik Messenger — Документация (Documentation)

Добро пожаловать в документацию проекта **Penik Messenger**. 

В данном разделе собрано полное описание архитектуры, REST API эндпоинтов, бинарного WebSocket протокола и криптографических механизмов системы.

---

## Разделы документации

1. [**REST API Specification (`Docs/REST_API.md`)**](./REST_API.md)
   - Подробное описание всех HTTP эндпоинтов сервера (`/api/v1/*`).
   - Форматы JSON запросов и ответов.
   - Требования к аутентификации и ограничения частоты запросов (Rate Limits).

2. [**WebSocket Protocol Specification (`Docs/WEBSOCKET.md`)**](./WEBSOCKET.md)
   - Бинарный формат фреймов (`[Opcode 1 byte][MessagePack Payload]`).
   - Полный справочник всех 40 опкодов (`0x01` – `0x28`).
   - Протоколы личных сообщений, групповых чатов, статусов доставки и присутствия.

3. [**System Architecture & Cryptography (`Docs/ARCHITECTURE.md`)**](./ARCHITECTURE.md)
   - Схема End-to-End шифрования (E2EE): X3DH, X25519, ChaCha20-Poly1305.
   - Эпохальные ключи групповых чатов (Epoch Group Keys & Envelopes).
   - Пейринг устройств через QR-коды и безопасная передача истории.
   - Архитектура хранения данных (SQLite, Room+SQLCipher, IndexedDB).
   - Загрузка и проксирование зашифрованных вложений через VK CDN.

---

## Быстрый старт для разработчиков

- **Сервер (Go):** исходный код в папке [`server/`](../server/)
- **Веб-клиент (Vite + JS):** исходный код в папке [`client/`](../client/)
- **Android-клиент (Kotlin + Compose):** исходный код в папке [`android/`](../android/)
- **Проектный индекс файлов:** [`PROJECT_MAP.md`](../PROJECT_MAP.md)
