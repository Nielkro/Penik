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

- `OpCallOffer` (`0x30`): клиент → сервер (инициация звонка)
- `OpCallIncoming` (`0x31`): сервер → **все устройства** получателя (уведомление о входящем звонке + JWT токен + URL-адреса)
- `OpCallAccept` (`0x32`): клиент → сервер (принятие звонка получателем)
- `OpCallAccepted` (`0x33`): сервер → вызывающий клиент (подтверждение принятия + JWT токен + URL-адреса)
- `OpCallReject` (`0x34`): клиент ↔ сервер (отклонение звонка или сброс из-за занятости)
- `OpCallEnd` (`0x35`): клиент ↔ сервер (завершение активного звонка)
- `OpCallTaken` (`0x36`): сервер → остальные устройства получателя (звонок уже обработан другим устройством, нужно просто перестать звонить)

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

// CallTaken (Server -> other devices of the callee)
type CallTaken struct {
	CallID string `msgpack:"call_id"`
	// "accepted" — другое устройство приняло звонок;
	// "declined" — другое устройство отклонило, но звонок ещё жив на остальных.
	Reason string `msgpack:"reason"`
}
```

---

## 3.1. Мультиустройственный звонок (Multi-device ringing)

Входящий звонок звонит на **всех** подключённых устройствах получателя одновременно, поэтому состояние звонка на сервере отслеживает конкретные устройства, а не только аккаунты (`activeCall.RingingDevices`, `activeCall.CallerDeviceID`, `activeCall.CalleeDeviceID` в `server/internal/ws/call.go`).

Правила переходов:

1. **Принятие.** Первое устройство, отправившее `CALL_ACCEPT`, становится владельцем стороны получателя (`CalleeDeviceID`). Остальные устройства получают `CALL_TAKEN` с `reason = "accepted"` и просто гасят звонок локально, не отправляя `CALL_REJECT`.
2. **Отклонение при нескольких звонящих устройствах.** `CALL_REJECT` от одного устройства, пока звонят другие, убирает только это устройство из `RingingDevices` и отвечает ему `CALL_TAKEN` с `reason = "declined"`. Вызывающий не уведомляется — пользователь всё ещё может ответить с другого устройства. Звонок завершается только когда отклонило последнее звонящее устройство.
3. **Чужое устройство не завершает звонок.** `CALL_REJECT` / `CALL_END` от устройства, которое не владеет своей стороной звонка (например, устаревший экран звонка после ответа на другом устройстве), игнорируется: проверка `activeCall.ownedByDevice`. Именно это не даёт «сбросить» уже принятый звонок нажатием отклонения на телефоне.
4. **Обрыв соединения.** `CleanupDeviceCalls` снимает только отключившееся устройство. Звонок в состоянии дозвона живёт, пока в `RingingDevices` остаётся хотя бы одно устройство; принятый звонок завершается только при обрыве у устройства-владельца. Переподключение, успевшее занять `deviceID` до завершения старого сокета, не даёт старому сокету сбросить живой звонок (`Hub.deviceReplaced`).
5. **Отмена вызывающим.** `CALL_REJECT` от вызывающего рассылается на все устройства получателя, гася звонок везде.

Клиенты (`client/js/call.js`, `android/.../CallManager.kt`) сверяют `call_id` каждого входящего кадра с тем звонком, в котором находятся (`_isCurrentCall` / `isCurrentCall`), и обрабатывают `CALL_TAKEN` как локальную остановку звонка без отправки чего-либо на сервер.


---

## 4. Клиентский отказоустойчивый алгоритм (Failover)

Клиент управления звонками (`client/js/call.js`) реализует автоматический переход на резервный LiveKit-сервер:

1. При получении события `OpCallIncoming` или `OpCallAccepted` клиент извлекает ссылки `livekit_url` (основная) и `livekit_fallback_url` (резервная).
2. Клиент предпринимает первую попытку подключения `room.connect(primaryUrl, token)`.
3. В случае сетевого сбоя или недоступности основного сервера клиент перехватывает ошибку, отключит неактивную комнату и пробует подключение к резервному эндпоинту `room.connect(fallbackUrl, token)`.
4. При успехе подключение переходит в состояние `ACTIVE`, включаются микрофон и камера (при видеозвонке).
5. При невозможности подключиться ни к одному из серверов клиент выводит уведомление об ошибке и очищает состояние звонка.

---

## 5. Качество медиа, кодеки и оптимизация (Quality & Codecs)

В веб-клиенте (`client/js/call.js`) активированы оптимальные настройки высокой четкости медиапотоков:

- **Кодек видео**: `VP9` (с фолбэком на `VP8` для устаревших устройств) обеспечивают максимальную четкость картинки при битрейте до 6 Mbps 1080p 60 FPS.
- **Simulcast & Dynacast**: Включены `simulcast: true` и `dynacast: true`, позволяющие динамически изменять разрешающую способность потока под качество канала связи.
- **Adaptive Stream**: Включен `adaptiveStream: { pixelDensity: 'screen' }`, предотвращающий сильное размытие и пикселизацию видеопотоков в маленьких контейнерах и окошках за счет использования плотности пикселей экрана.
- **HD Audio & RED**: Настроено HD аудио до 128 kbps с включенным эхоподавлением, шумоподавлением (`noiseSuppression: true`), автоусилением (`autoGainControl: true`) и защитой от потерь пакетов `red: true` (Redundant Audio Data).
