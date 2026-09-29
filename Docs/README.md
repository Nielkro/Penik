# Penik Messenger — Документация (Documentation)

Добро пожаловать в документацию проекта **Penik Messenger**. 

В данном разделе собрано полное описание архитектуры, REST API эндпоинтов, бинарного WebSocket протокола и механизмов системы.

---

## Разделы документации

1. [**REST API Specification (`Docs/REST_API.md`)**](./REST_API.md)
   - Подробное описание всех HTTP эндпоинтов сервера (`/api/v1/*`).
   - Форматы JSON запросов и ответов.
   - Требования к аутентификации и ограничения частоты запросов (Rate Limits).

2. [**WebSocket Protocol Specification (`Docs/WEBSOCKET.md`)**](./WEBSOCKET.md)
   - Бинарный формат фреймов (`[Opcode 1 byte][MessagePack Payload]`).
   - Полный справочник опкодов (`0x01` – `0x39`).
   - Протоколы личных сообщений, групповых чатов, статусов доставки, присутствия и сигнализации звонков.

3. [**System Architecture (`Docs/ARCHITECTURE.md`)**](./ARCHITECTURE.md)
   - Архитектура быстрых облачных личных и групповых чатов.
   - Сквозное E2EE-шифрование 1:1 аудио- и видеозвонков (Direct Ephemeral X25519 DH + Safety Words).
   - Мульти-девайс и управление сессиями.
   - Архитектура хранения данных (SQLite, Room, IndexedDB).
   - Загрузка и стриминг вложений через серверное хранилище (HTTP Range).

4. [**LiveKit Calls & Signaling (`Docs/CALLS.md`)**](./CALLS.md)
   - Архитектура 1:1 аудио- и видеозвонков на базе LiveKit.
   - Описание сигнализации (опкоды `0x30` – `0x39`).
   - Прямой эфемерный Diffie-Hellman (X25519) и Safety Words.
   - Алгоритм клиентского переключения при сбое основного сервера (Failover).

---

## Быстрый старт для разработчиков

- **Сервер (Go):** исходный код в папке [`server/`](../server/)
- **Веб-клиент (Vite + JS):** исходный код в папке [`client/`](../client/)
- **Android-клиент (Kotlin + Compose):** исходный код в папке [`android/`](../android/)
- **Проектный индекс файлов:** [`PROJECT_MAP.md`](../PROJECT_MAP.md)
