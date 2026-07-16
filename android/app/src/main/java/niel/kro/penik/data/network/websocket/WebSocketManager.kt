package niel.kro.penik.data.network.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class WebSocketEvent {
    data class MsgRecv(
        val fromUserId: Long,
        val chatUserId: Long,
        val text: String,
        val msgId: Long,
        val ts: Long
    ) : WebSocketEvent()

    data class MsgAck(
        val serverMsgId: Long,
        val clientMsgId: String
    ) : WebSocketEvent()

    data class OfflineBatch(
        val msgs: List<WebSocketEvent.MsgRecv>
    ) : WebSocketEvent()

    data class ChatPurge(val peerId: Long) : WebSocketEvent()

    object Connected : WebSocketEvent()
    object Disconnected : WebSocketEvent()
    object Pong : WebSocketEvent()
}

object Opcode {
    const val MSG_SEND: Byte = 0x01
    const val MSG_RECV: Byte = 0x02
    const val MSG_ACK: Byte = 0x03
    const val MSG_DELIVERED: Byte = 0x04
    const val OFFLINE_BATCH: Byte = 0x05
    const val PING: Byte = 0x06
    const val PONG: Byte = 0x07
    const val CHAT_PURGE: Byte = 0x08
    const val CHAT_PURGE_ACK: Byte = 0x09
}

private fun MessageUnpacker.readMsgRecvMap(): Map<String, Any?> {
    val size = unpackMapHeader()
    val map = mutableMapOf<String, Any?>()
    for (i in 0 until size) {
        val key = unpackString()
        val value: Any? = try {
            when (key) {
                "from_user_id", "chat_user_id", "msg_id", "ts" -> unpackLong()
                "plaintext" -> unpackString()
                else -> {
                    try { unpackLong() } catch (_: Exception) {
                        try { unpackString() } catch (_: Exception) {
                            try { unpackBoolean() } catch (_: Exception) {
                                unpackNil(); null
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            unpackNil(); null
        }
        map[key] = value
    }
    return map
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

@Singleton
class WebSocketManager @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _events = MutableSharedFlow<WebSocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var token: String = ""
    private var reconnectAttempt = 0
    private var pingJob: kotlinx.coroutines.Job? = null

    private var connectHost: String = ""
    private var connectPort: Int = 0

    fun connect(host: String, port: Int, token: String) {
        this.connectHost = host
        this.connectPort = port
        this.token = token
        reconnectAttempt = 0
        doConnect()
    }

    fun disconnect() {
        pingJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun doConnect() {
        _connectionState.value = ConnectionState.CONNECTING
        val scheme = if (connectPort == 443) "wss" else "ws"
        val request = Request.Builder()
            .url("$scheme://$connectHost:$connectPort/api/v1/ws")
            .header("Sec-WebSocket-Protocol", "access_token, $token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WS", "Connected")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempt = 0
                scope.launch { _events.emit(WebSocketEvent.Connected) }
                startPingLoop()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryFrame(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WS", "Closed: $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WS", "Failure: ${t.message}")
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        pingJob?.cancel()
        scope.launch { _events.emit(WebSocketEvent.Disconnected) }
        reconnectWithBackoff()
    }

    private fun reconnectWithBackoff() {
        reconnectAttempt++
        val delayMs = minOf(1000L * (1 shl (reconnectAttempt - 1)), 30_000L)
        Log.d("WS", "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
        scope.launch {
            delay(delayMs)
            doConnect()
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(25_000)
                sendPing()
            }
        }
    }

    private fun handleBinaryFrame(data: ByteArray) {
        if (data.isEmpty()) return
        val opcode = data[0]
        val payload = data.copyOfRange(1, data.size)

        when (opcode) {
            Opcode.MSG_RECV -> handleMsgRecv(payload)
            Opcode.MSG_ACK -> handleMsgAck(payload)
            Opcode.OFFLINE_BATCH -> handleOfflineBatch(payload)
            Opcode.PING -> sendPong()
            Opcode.PONG -> scope.launch { _events.emit(WebSocketEvent.Pong) }
            Opcode.CHAT_PURGE -> handleChatPurge(payload)
        }
    }

    private fun handleMsgRecv(payload: ByteArray) {
        val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
        val map = unpacker.readMsgRecvMap()
        unpacker.close()
        val event = WebSocketEvent.MsgRecv(
            fromUserId = (map["from_user_id"] as? Number)?.toLong() ?: 0,
            chatUserId = (map["chat_user_id"] as? Number)?.toLong() ?: 0,
            text = (map["plaintext"] as? String) ?: "",
            msgId = (map["msg_id"] as? Number)?.toLong() ?: 0,
            ts = (map["ts"] as? Number)?.toLong() ?: 0
        )
        scope.launch { _events.emit(event) }
    }

    private fun handleMsgAck(payload: ByteArray) {
        val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
        val map = unpacker.readMsgRecvMap()
        unpacker.close()
        val event = WebSocketEvent.MsgAck(
            serverMsgId = (map["msg_id"] as? Number)?.toLong() ?: 0,
            clientMsgId = (map["client_msg_id"] as? String) ?: ""
        )
        scope.launch { _events.emit(event) }
    }

    private fun handleOfflineBatch(payload: ByteArray) {
        val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))

        val outerSize = unpacker.unpackMapHeader()
        var msgsCount = 0
        for (i in 0 until outerSize) {
            val key = unpacker.unpackString()
            if (key == "msgs") {
                msgsCount = unpacker.unpackArrayHeader()
            } else {
                unpacker.unpackValue()
            }
        }

        val messages = mutableListOf<WebSocketEvent.MsgRecv>()
        for (i in 0 until msgsCount) {
            val map = unpacker.readMsgRecvMap()
            messages.add(WebSocketEvent.MsgRecv(
                fromUserId = (map["from_user_id"] as? Number)?.toLong() ?: 0,
                chatUserId = (map["chat_user_id"] as? Number)?.toLong() ?: 0,
                text = (map["plaintext"] as? String) ?: "",
                msgId = (map["msg_id"] as? Number)?.toLong() ?: 0,
                ts = (map["ts"] as? Number)?.toLong() ?: 0
            ))
        }
        unpacker.close()
        scope.launch { _events.emit(WebSocketEvent.OfflineBatch(messages)) }
    }

    private fun handleChatPurge(payload: ByteArray) {
        val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
        val map = unpacker.readMsgRecvMap()
        unpacker.close()
        val event = WebSocketEvent.ChatPurge(peerId = (map["peer_id"] as? Number)?.toLong() ?: 0)
        scope.launch { _events.emit(event) }
    }

    fun sendMessage(toUserId: Long, text: String, clientMsgId: String = UUID.randomUUID().toString()) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(3)
        packer.packString("to_user_id")
        packer.packLong(toUserId)
        packer.packString("plaintext")
        packer.packString(text)
        packer.packString("msg_id")
        packer.packString(clientMsgId)
        packer.close()

        val payload = bos.toByteArray()
        val frame = ByteArray(1 + payload.size)
        frame[0] = Opcode.MSG_SEND
        payload.copyInto(frame, 1)
        webSocket?.send(frame.toByteString(0, frame.size))
    }

    fun sendDelivered(msgId: Long) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(1)
        packer.packString("msg_id")
        packer.packLong(msgId)
        packer.close()

        val payload = bos.toByteArray()
        val frame = ByteArray(1 + payload.size)
        frame[0] = Opcode.MSG_DELIVERED
        payload.copyInto(frame, 1)
        webSocket?.send(frame.toByteString(0, frame.size))
    }

    fun sendChatPurgeAck(peerId: Long) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(1)
        packer.packString("peer_id")
        packer.packLong(peerId)
        packer.close()

        val payload = bos.toByteArray()
        val frame = ByteArray(1 + payload.size)
        frame[0] = Opcode.CHAT_PURGE_ACK
        payload.copyInto(frame, 1)
        webSocket?.send(frame.toByteString(0, frame.size))
    }

    private fun sendPing() {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(0)
        packer.close()

        val payload = bos.toByteArray()
        val frame = ByteArray(1 + payload.size)
        frame[0] = Opcode.PING
        payload.copyInto(frame, 1)
        webSocket?.send(frame.toByteString(0, frame.size))
    }

    private fun sendPong() {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(0)
        packer.close()

        val payload = bos.toByteArray()
        val frame = ByteArray(1 + payload.size)
        frame[0] = Opcode.PONG
        payload.copyInto(frame, 1)
        webSocket?.send(frame.toByteString(0, frame.size))
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
