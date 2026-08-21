# LiveKit Calls & Signaling (`Docs/CALLS.md`)

Документация по архитектуре 1:1 аудио- и видеозвонков в **Penik Messenger** на базе **LiveKit**.

---

## 1. Обзор архитектуры

Служба звонков Penik Messenger интегрирована с медиа-сервером LiveKit. Сервер Penik Messenger выполняет роль сигнального сервера (Signaling Server) и генератора временных JWT-токенов доступа к комнатам LiveKit. 

Прямая передача медиапотоков (аудио/видео) осуществляется между веб-клиентом и серверами LiveKit по протоколу WebRTC.

```
[ Caller Client ] <--- WebSocket (MsgPack) ---> [ Penik Server ] <--- WebSocket (MsgPack) ---> [ Callee Client ]
        |                                                                                             |
        +====================== WebRTC (Media Tracks) ================================================+
        |                                                                                             |
        v                                                                                             v
 [ Primary LiveKit Server ]  <-------- (Automatic Failover on Error) -------->  [ Secondary/Fallback LiveKit Server ]
  (wss://livekit.home.penik.ru)                                                  (wss://call.api.penik.ru)
```

---

## 2. Переменные окружения и валидация

СерверPenik считывает параметры подключения к LiveKit из переменных окружения (включая файлы `.env` / `server/.env`).

| Переменная | Описание | Обязательность |
|------------|----------|----------------|
| `LIVEKIT_URL` | Основной URL WebSocket-сервера LiveKit (например, `wss://livekit.home.penik.ru`) | **Обязательно** |
| `LIVEKIT_FALLBACK_URL` | Резервный/дополнительный URL LiveKit (например, `wss://call.api.penik.ru`) | **Обязательно** |
| `LIVEKIT_API_KEY` | API-ключ для генерации LiveKit JWT токенов | **Обязательно** |
| `LIVEKIT_API_SECRET` | API-секрет для генерации LiveKit JWT токенов | **Обязательно** |

### Отказ при отсутствии конфигурации (Fail-Closed)
При запуске сервера выполняется проверка конфигурации `config.Validate()`. Если переменные `LIVEKIT_URL` или `LIVEKIT_FALLBACK_URL` не заданы или пустые, сервер аварийно завершает работу (`log.Fatalf`), предотвращая работу с некорректными или отсутствующими эндпоинтами.

---

## 3. Сигнализация звонков по WebSocket

Сигнализация звонков выполняется поверх основного бинарного WebSocket соединения в формате **MsgPack**.

### Опкоды сигнализации (`server/internal/ws/protocol.go`)

- `OpCallOffer` (`0x24`): клиент → сервер (инициация звонка)
- `OpCallIncoming` (`0x25`): сервер → целевой клиент (уведомление о входящем звонке + JWT токен + URL-адреса)
- `OpCallAccept` (`0x26`): клиент → сервер (принятие звонка получателем)
- `OpCallAccepted` (`0x27`): сервер → вызывающий клиент (подтверждение принятия + JWT токен + URL-адреса)
- `OpCallReject` (`0x28`): клиент ↔ сервер (отклонение звонка или сброс из-за занятости)
- `OpCallEnd` (`0x29`): клиент ↔ сервер (завершение активного звонка)

### Структуры данных MsgPack

```go
// CallOffer (Client -> Server)
type CallOffer struct {
	ToUserID int64 `msgpack:"to_user_id"`
	IsVideo  bool  `msgpack:"is_video"`
}

// CallIncoming (Server -> Callee Client)
type CallIncoming struct {
	CallID             string `msgpack:"call_id"`
	FromUserID         int64  `msgpack:"from_user_id"`
	IsVideo            bool   `msgpack:"is_video"`
	RoomName           string `msgpack:"room_name"`
	LiveKitURL         string `msgpack:"livekit_url"`
	LiveKitFallbackURL string `msgpack:"livekit_fallback_url,omitempty"`
	Token              string `msgpack:"token"`
}

// CallAccept (Callee Client -> Server)
type CallAccept struct {
	CallID string `msgpack:"call_id"`
}

// CallAccepted (Server -> Caller Client)
type CallAccepted struct {
	CallID             string `msgpack:"call_id"`
	ToUserID           int64  `msgpack:"to_user_id"`
	RoomName           string `msgpack:"room_name"`
	LiveKitURL         string `msgpack:"livekit_url"`
	LiveKitFallbackURL string `msgpack:"livekit_fallback_url,omitempty"`
	Token              string `msgpack:"token"`
}
```

---

## 4. Клиентский отказоустойчивый алгоритм (Failover)

Клиент управления звонками (`client/js/call.js`) реализует автоматический переход на резервный LiveKit-сервер:

1. При получении события `OpCallIncoming` или `OpCallAccepted` клиент извлекает ссылки `livekit_url` (основная) и `livekit_fallback_url` (резервная).
2. Клиент предпринимает первую попытку подключения `room.connect(primaryUrl, token)`.
3. В случае сетевого сбоя или недоступности основного сервера клиент перехватывает ошибку, отключит неактивную комнату и пробует подключение к резервному эндпоинту `room.connect(fallbackUrl, token)`.
4. При успехе подключение переходит в состояние `ACTIVE`, включаются микрофон и камера (при видеозвонке).
5. При невозможности подключиться ни к одному из серверов клиент выводит уведомление об ошибке и очищает состояние звонка.
