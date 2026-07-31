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
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessageUnpacker
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import niel.kro.penik.data.repository.SecureTokenStorage

sealed class WebSocketEvent {
    data class MsgRecv(
        val fromUserId: Long,
        val chatUserId: Long,
        val text: String,
        val msgId: Long,
        val ts: Long
    ) : WebSocketEvent()

    data class MsgRecvEncrypted(
        val fromUserId: Long,
        val fromDeviceId: Long,
        val fromIdentityKey: ByteArray,
        val chatUserId: Long,
        val msgId: Long,
        val ciphertext: ByteArray,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ts: Long,
        val replyToMsgId: String? = null
    ) : WebSocketEvent()

    data class MsgAck(
        val serverMsgId: Long,
        val clientMsgId: String
    ) : WebSocketEvent()

    data class MsgDelivered(
        val msgId: Long,
        val clientMsgId: String = ""
    ) : WebSocketEvent()
    data class MsgRead(val msgId: Long, val clientMsgId: String = "") : WebSocketEvent()

    data class OfflineBatch(
        val msgs: List<WebSocketEvent.MsgRecv>
    ) : WebSocketEvent()

    data class OfflineBatchEncrypted(
        val msgs: List<WebSocketEvent.MsgRecvEncrypted>
    ) : WebSocketEvent()

    data class ChatPurge(val peerId: Long) : WebSocketEvent()
    data class PairingHistoryReady(val sessionId: String) : WebSocketEvent()

    data class GroupMessageRecv(
        val groupId: Long,
        val id: Long,
        val messageId: String,
        val senderUserId: Long,
        val senderDeviceId: Long,
        val keyVersion: Long,
        val ciphertext: ByteArray,
        val salt: ByteArray,
        val nonce: ByteArray,
        val createdAt: Long,
        val replyToMsgId: String? = null
    ) : WebSocketEvent()

    data class GroupMessageAck(
        val groupId: Long,
        val messageId: String,
        val id: Long
    ) : WebSocketEvent()

    data class GroupKeyAvailable(val groupId: Long, val keyVersion: Long) : WebSocketEvent()
    data class GroupMemberChanged(val groupId: Long, val membershipVersion: Long) : WebSocketEvent()

    data class MsgStatusItem(
        val msgId: Long,
        val clientMsgId: String,
        val delivered: Boolean,
        val deliveredAt: Long?,
        val read: Boolean
    )
    data class MsgStatusBatch(val statuses: List<MsgStatusItem>) : WebSocketEvent()
    data class UserAvatarUpdate(val userId: Long, val ts: Long) : WebSocketEvent()
    data class PresenceUpdate(val userId: Long, val online: Boolean, val lastSeen: Long) : WebSocketEvent()
    data class GroupAvatarUpdate(val groupId: Long, val ts: Long) : WebSocketEvent()

    object Connected : WebSocketEvent()
    object Disconnected : WebSocketEvent()
    object ServerShutdown : WebSocketEvent()
    object Pong : WebSocketEvent()
}

object Opcode {
    const val MSG_SEND: Byte = 0x01
    const val MSG_RECV: Byte = 0x02
    const val MSG_ACK: Byte = 0x03
    const val MSG_DELIVERED: Byte = 0x04
    const val MSG_READ: Byte = 0x18
    const val OFFLINE_BATCH: Byte = 0x05
    const val MSG_STATUS_BATCH: Byte = 0x1b
    const val USER_AVATAR_UPDATE: Byte = 0x1c
    const val PRESENCE_UPDATE: Byte = 0x1d
    const val SERVER_SHUTDOWN: Byte = 0x1e
    const val PING: Byte = 0x06
    const val PONG: Byte = 0x07
    const val CHAT_PURGE: Byte = 0x08
    const val CHAT_PURGE_ACK: Byte = 0x09
    const val PAIRING_HISTORY_READY: Byte = 0x19
    const val KEY_PUBLISH: Byte = 0x12
    const val GROUP_MESSAGE_SEND: Byte = 0x20
    const val GROUP_MESSAGE_RECV: Byte = 0x21
    const val GROUP_MESSAGE_ACK: Byte = 0x22
    const val GROUP_KEY_AVAILABLE: Byte = 0x23
    const val GROUP_MEMBER_CHANGED: Byte = 0x24
    const val GROUP_MESSAGE_DELIVERED: Byte = 0x25
    const val GROUP_MESSAGE_READ: Byte = 0x26
    const val GROUP_AVATAR_UPDATE: Byte = 0x28
}

private fun MessageUnpacker.readMsgRecvMap(): Map<String, Any?> {
    val size = unpackMapHeader()
    val map = mutableMapOf<String, Any?>()
    for (i in 0 until size) {
        val key = when (nextFormat.valueType) {
            org.msgpack.value.ValueType.INTEGER -> unpackLong().toString()
            org.msgpack.value.ValueType.STRING  -> unpackString()
            else -> unpackValue().toString()
        }
        val value: Any? = if (tryUnpackNil()) {
            null
        } else {
            when (nextFormat.valueType) {
                org.msgpack.value.ValueType.INTEGER -> unpackLong()
                org.msgpack.value.ValueType.BOOLEAN -> unpackBoolean()
                org.msgpack.value.ValueType.STRING  -> unpackString()
                org.msgpack.value.ValueType.FLOAT   -> unpackDouble()
                org.msgpack.value.ValueType.BINARY  -> { val len = unpackBinaryHeader(); readPayload(len) }
                else -> try { unpackValue().toString() } catch (_: Exception) { null }
            }
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
class WebSocketManager @Inject constructor(
    private val tokenStorage: SecureTokenStorage
) {

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
    private var manualDisconnect = false

    private var connectHost: String = ""
    private var connectPort: Int = 0
    private var reconnectJob: kotlinx.coroutines.Job? = null

    fun connect(host: String, port: Int, token: String) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        this.connectHost = host
        this.connectPort = port
        this.token = token
        manualDisconnect = false
        reconnectAttempt = 0
        doConnect()
    }

    /**
     * Called by the REST layer whenever any API request succeeds. If the WebSocket
     * is currently disconnected (e.g. after a 1006 close) and waiting on backoff,
     * this is a signal the server is reachable, so reconnect immediately instead
     * of waiting out the remaining delay.
     */
    fun notifyRestSuccess() {
        if (manualDisconnect) return
        if (connectHost.isEmpty() || connectPort == 0) return
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        reconnectJob?.cancel()
        reconnectAttempt = 0
        doConnect()
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Closes the socket after a server-initiated shutdown notice, without
     * setting manualDisconnect — the server closes the connection on its own
     * right after this anyway, so this just avoids waiting for that round trip.
     * The normal onClosed -> handleDisconnect -> reconnectWithBackoff path
     * still runs, so the client keeps retrying (and notifyRestSuccess still
     * reconnects immediately once REST calls start succeeding again).
     */
    fun closeForServerShutdown() {
        pingJob?.cancel()
        webSocket?.close(1000, "Server shutdown")
    }

    private fun doConnect() {
        _connectionState.value = ConnectionState.CONNECTING
        try {
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
                    
                    // Publish current local public identity key
                    tokenStorage.getPublicKey()?.let { pubKey ->
                        sendKeyPublish(pubKey)
                    }

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
        } catch (e: Exception) {
            Log.e("WS", "Failed to construct WebSocket", e)
            handleDisconnect()
        }
    }

    private fun handleDisconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        pingJob?.cancel()
        scope.launch { _events.emit(WebSocketEvent.Disconnected) }
        if (!manualDisconnect) reconnectWithBackoff()
    }

    private fun reconnectWithBackoff() {
        reconnectAttempt++
        val delayMs = minOf(1000L * (1 shl (reconnectAttempt - 1)), 30_000L)
        Log.d("WS", "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
        reconnectJob = scope.launch {
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
            Opcode.MSG_DELIVERED -> handleMsgDelivered(payload)
            Opcode.MSG_READ -> handleMsgRead(payload)
            Opcode.OFFLINE_BATCH -> handleOfflineBatch(payload)
            Opcode.MSG_STATUS_BATCH -> handleMsgStatusBatch(payload)
            Opcode.PING -> sendPong()
            Opcode.PONG -> scope.launch { _events.emit(WebSocketEvent.Pong) }
            Opcode.CHAT_PURGE -> handleChatPurge(payload)
            Opcode.PAIRING_HISTORY_READY -> handlePairingHistoryReady(payload)
            Opcode.USER_AVATAR_UPDATE -> handleUserAvatarUpdate(payload)
            Opcode.PRESENCE_UPDATE -> handlePresenceUpdate(payload)
            Opcode.SERVER_SHUTDOWN -> scope.launch { _events.emit(WebSocketEvent.ServerShutdown) }
            Opcode.GROUP_MESSAGE_RECV -> handleGroupMessageRecv(payload)
            Opcode.GROUP_MESSAGE_ACK -> handleGroupMessageAck(payload)
            Opcode.GROUP_KEY_AVAILABLE -> handleGroupKeyAvailable(payload)
            Opcode.GROUP_MEMBER_CHANGED -> handleGroupMemberChanged(payload)
            Opcode.GROUP_AVATAR_UPDATE -> handleGroupAvatarUpdate(payload)
        }
    }

    private fun handleUserAvatarUpdate(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val userId = (map["user_id"] as? Number)?.toLong() ?: return
            val ts = (map["ts"] as? Number)?.toLong() ?: System.currentTimeMillis()
            scope.launch { _events.emit(WebSocketEvent.UserAvatarUpdate(userId, ts)) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse UserAvatarUpdate frame", e)
        }
    }

    private fun handlePresenceUpdate(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val userId = (map["user_id"] as? Number)?.toLong() ?: return
            val online = map["online"] as? Boolean ?: false
            val lastSeen = (map["last_seen"] as? Number)?.toLong() ?: 0L
            scope.launch { _events.emit(WebSocketEvent.PresenceUpdate(userId, online, lastSeen)) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse PresenceUpdate frame", e)
        }
    }

    private fun handleGroupAvatarUpdate(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val groupId = (map["group_id"] as? Number)?.toLong() ?: return
            val ts = (map["ts"] as? Number)?.toLong() ?: System.currentTimeMillis()
            scope.launch { _events.emit(WebSocketEvent.GroupAvatarUpdate(groupId, ts)) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse GroupAvatarUpdate frame", e)
        }
    }

    private fun handlePairingHistoryReady(payload: ByteArray) {
        val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
        val size = unpacker.unpackMapHeader(); var session = ""
        repeat(size) { if (unpacker.unpackString() == "session_id") session = unpacker.unpackString() else unpacker.unpackValue() }
        unpacker.close(); scope.launch { _events.emit(WebSocketEvent.PairingHistoryReady(session)) }
    }

    private fun handleMsgRecv(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val event = unpacker.readMsgRecvEncrypted()
            unpacker.close()
            scope.launch { _events.emit(event) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse MsgRecv frame", e)
        }
    }

    private fun handleMsgAck(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val event = WebSocketEvent.MsgAck(
                serverMsgId = (map["msg_id"] as? Number)?.toLong() ?: 0,
                clientMsgId = (map["client_msg_id"] as? String) ?: ""
            )
            scope.launch { _events.emit(event) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse MsgAck frame", e)
        }
    }

    private fun handleMsgDelivered(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val event = WebSocketEvent.MsgDelivered(
                msgId = (map["msg_id"] as? Number)?.toLong() ?: 0,
                clientMsgId = map["client_msg_id"] as? String ?: ""
            )
            scope.launch { _events.emit(event) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse MsgDelivered frame", e)
        }
    }

    private fun handleMsgRead(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            scope.launch { _events.emit(WebSocketEvent.MsgRead((map["msg_id"] as? Number)?.toLong() ?: 0, map["client_msg_id"] as? String ?: "")) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse MsgRead frame", e)
        }
    }

    private fun handleOfflineBatch(payload: ByteArray) {
        try {
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
            val messages = mutableListOf<WebSocketEvent.MsgRecvEncrypted>()
            for (i in 0 until msgsCount) {
                try {
                    messages.add(unpacker.readMsgRecvEncrypted())
                } catch (e: Exception) {
                    Log.e("WS", "Failed to parse offline batch item", e)
                }
            }
            unpacker.close()
            scope.launch { _events.emit(WebSocketEvent.OfflineBatchEncrypted(messages)) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse OfflineBatch frame", e)
        }
    }

    private fun handleMsgStatusBatch(payload: ByteArray) {
        val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
        val outerSize = unpacker.unpackMapHeader()
        var statusesCount = 0
        for (i in 0 until outerSize) {
            val key = unpacker.unpackString()
            if (key == "statuses") {
                statusesCount = unpacker.unpackArrayHeader()
            } else {
                unpacker.unpackValue()
            }
        }

        val statuses = mutableListOf<WebSocketEvent.MsgStatusItem>()
        for (i in 0 until statusesCount) {
            val itemSize = unpacker.unpackMapHeader()
            var msgId = 0L
            var clientMsgId = ""
            var delivered = false
            var deliveredAt: Long? = null
            var read = false

            for (j in 0 until itemSize) {
                val key = unpacker.unpackString()
                if (unpacker.tryUnpackNil()) {
                    continue
                }
                when (key) {
                    "msg_id" -> msgId = unpacker.unpackLong()
                    "client_msg_id" -> clientMsgId = unpacker.unpackString()
                    "delivered" -> delivered = unpacker.unpackBoolean()
                    "delivered_at" -> deliveredAt = unpacker.unpackLong()
                    "read" -> read = unpacker.unpackBoolean()
                    else -> unpacker.unpackValue()
                }
            }
            statuses.add(WebSocketEvent.MsgStatusItem(msgId, clientMsgId, delivered, deliveredAt, read))
        }
        unpacker.close()
        scope.launch { _events.emit(WebSocketEvent.MsgStatusBatch(statuses)) }
    }

    private fun handleChatPurge(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val event = WebSocketEvent.ChatPurge(
                peerId = (map["chat_user_id"] as? Number)?.toLong() ?: 0
            )
            scope.launch { _events.emit(event) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse ChatPurge frame", e)
        }
    }

    private fun handleGroupMessageRecv(payload: ByteArray) {
        val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
        val size = unpacker.unpackMapHeader()
        var groupId = 0L; var id = 0L; var messageId = ""; var senderUserId = 0L
        var senderDeviceId = 0L; var keyVersion = 0L
        var ciphertext = ByteArray(0); var salt = ByteArray(0); var nonce = ByteArray(0); var createdAt = 0L
        var replyToMsgId: String? = null
        for (i in 0 until size) {
            val key = unpacker.unpackString()
            if (unpacker.nextFormat == MessageFormat.NIL) { unpacker.unpackNil(); continue }
            when (key) {
                "group_id" -> groupId = unpacker.unpackLong()
                "id" -> id = unpacker.unpackLong()
                "message_id" -> messageId = unpacker.unpackString()
                "reply_to_msg_id" -> replyToMsgId = unpacker.unpackString()
                "sender_user_id" -> senderUserId = unpacker.unpackLong()
                "sender_device_id" -> senderDeviceId = unpacker.unpackLong()
                "key_version" -> keyVersion = unpacker.unpackLong()
                "ciphertext" -> { val len = unpacker.unpackBinaryHeader(); ciphertext = unpacker.readPayload(len) }
                "salt" -> { val len = unpacker.unpackBinaryHeader(); salt = unpacker.readPayload(len) }
                "nonce" -> { val len = unpacker.unpackBinaryHeader(); nonce = unpacker.readPayload(len) }
                "created_at" -> createdAt = unpacker.unpackLong()
                else -> unpacker.unpackValue()
            }
        }
        unpacker.close()
        scope.launch {
            _events.emit(
                WebSocketEvent.GroupMessageRecv(
                    groupId, id, messageId, senderUserId, senderDeviceId,
                    keyVersion, ciphertext, salt, nonce, createdAt, replyToMsgId
                )
            )
        }
    }

    private fun handleGroupMessageAck(payload: ByteArray) {
        val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
        val size = unpacker.unpackMapHeader()
        var groupId = 0L; var messageId = ""; var id = 0L
        for (i in 0 until size) {
            val key = unpacker.unpackString()
            if (unpacker.nextFormat == MessageFormat.NIL) { unpacker.unpackNil(); continue }
            when (key) {
                "group_id" -> groupId = unpacker.unpackLong()
                "message_id" -> messageId = unpacker.unpackString()
                "id" -> id = unpacker.unpackLong()
                else -> unpacker.unpackValue()
            }
        }
        unpacker.close()
        scope.launch { _events.emit(WebSocketEvent.GroupMessageAck(groupId, messageId, id)) }
    }

    private fun handleGroupKeyAvailable(payload: ByteArray) {
        val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
        val map = unpacker.readMsgRecvMap(); unpacker.close()
        scope.launch {
            _events.emit(
                WebSocketEvent.GroupKeyAvailable(
                    (map["group_id"] as? Number)?.toLong() ?: 0,
                    (map["key_version"] as? Number)?.toLong() ?: 0
                )
            )
        }
    }

    private fun handleGroupMemberChanged(payload: ByteArray) {
        val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
        val map = unpacker.readMsgRecvMap(); unpacker.close()
        scope.launch {
            _events.emit(
                WebSocketEvent.GroupMemberChanged(
                    (map["group_id"] as? Number)?.toLong() ?: 0,
                    (map["membership_version"] as? Number)?.toLong() ?: 0
                )
            )
        }
    }

    /** Send an encrypted group message. Sender identity is assigned by the server. */
    fun sendGroupMessage(
        groupId: Long,
        messageId: String,
        keyVersion: Long,
        ciphertext: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
        createdAt: Long,
        replyToMsgId: String? = null
    ) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        val size = if (replyToMsgId != null) 8 else 7
        packer.packMapHeader(size)
        packer.packString("group_id"); packer.packLong(groupId)
        packer.packString("message_id"); packer.packString(messageId)
        if (replyToMsgId != null) {
            packer.packString("reply_to_msg_id"); packer.packString(replyToMsgId)
        }
        packer.packString("key_version"); packer.packLong(keyVersion)
        packer.packString("ciphertext"); packer.packBinaryHeader(ciphertext.size); packer.addPayload(ciphertext)
        packer.packString("salt"); packer.packBinaryHeader(salt.size); packer.addPayload(salt)
        packer.packString("nonce"); packer.packBinaryHeader(nonce.size); packer.addPayload(nonce)
        packer.packString("created_at"); packer.packLong(createdAt)
        packer.close()
        sendFrame(Opcode.GROUP_MESSAGE_SEND, bos.toByteArray())
    }

    fun sendGroupDelivered(id: Long) = sendGroupReceipt(Opcode.GROUP_MESSAGE_DELIVERED, id)
    fun sendGroupRead(id: Long) = sendGroupReceipt(Opcode.GROUP_MESSAGE_READ, id)

    private fun sendGroupReceipt(opcode: Byte, id: Long) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(1); packer.packString("id"); packer.packLong(id); packer.close()
        sendFrame(opcode, bos.toByteArray())
    }

    private fun sendFrame(opcode: Byte, payload: ByteArray) {
        val frame = ByteArray(1 + payload.size)
        frame[0] = opcode
        payload.copyInto(frame, 1)
        webSocket?.send(frame.toByteString(0, frame.size))
    }

    private fun MessageUnpacker.readMsgRecvEncrypted(): WebSocketEvent.MsgRecvEncrypted {
        val size = unpackMapHeader()
        var fromUserId = 0L
        var fromDeviceId = 0L
        var fromIdentityKey = ByteArray(0)
        var chatUserId = 0L
        var msgId = 0L
        var ciphertext = ByteArray(0)
        var salt = ByteArray(0)
        var nonce = ByteArray(0)
        var ts = 0L

        var replyToMsgId: String? = null

        for (i in 0 until size) {
            val key = unpackString()
            if (nextFormat == MessageFormat.NIL) {
                unpackNil()
                continue
            }
            when (key) {
                "from_user_id" -> fromUserId = unpackLong()
                "from_device_id" -> fromDeviceId = unpackLong()
                "from_identity_key" -> {
                    val len = unpackBinaryHeader()
                    fromIdentityKey = readPayload(len)
                }
                "chat_user_id" -> chatUserId = unpackLong()
                "msg_id" -> msgId = unpackLong()
                "reply_to_msg_id" -> replyToMsgId = unpackString()
                "ciphertext" -> {
                    val len = unpackBinaryHeader()
                    ciphertext = readPayload(len)
                }
                "salt" -> {
                    val len = unpackBinaryHeader()
                    salt = readPayload(len)
                }
                "nonce" -> {
                    val len = unpackBinaryHeader()
                    nonce = readPayload(len)
                }
                "ts" -> ts = unpackLong()
                else -> unpackValue()
            }
        }

        return WebSocketEvent.MsgRecvEncrypted(
            fromUserId = fromUserId,
            fromDeviceId = fromDeviceId,
            fromIdentityKey = fromIdentityKey,
            chatUserId = chatUserId,
            msgId = msgId,
            ciphertext = ciphertext,
            salt = salt,
            nonce = nonce,
            ts = ts * 1000,
            replyToMsgId = replyToMsgId
        )
    }

    fun sendEncryptedMessage(toUserId: Long, clientMsgId: String, devices: List<E2EDevicePayload>, replyToMsgId: String? = null) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        val mapSize = if (replyToMsgId != null) 4 else 3
        packer.packMapHeader(mapSize)
        packer.packString("to_user_id")
        packer.packLong(toUserId)
        packer.packString("msg_id")
        packer.packString(clientMsgId)
        if (replyToMsgId != null) {
            packer.packString("reply_to_msg_id")
            packer.packString(replyToMsgId)
        }
        packer.packString("devices")
        packer.packArrayHeader(devices.size)
        for (dev in devices) {
            packer.packMapHeader(4)
            packer.packString("device_id")
            packer.packLong(dev.deviceId)
            packer.packString("ciphertext")
            packer.packBinaryHeader(dev.ciphertext.size)
            packer.addPayload(dev.ciphertext)
            packer.packString("salt")
            packer.packBinaryHeader(dev.salt.size)
            packer.addPayload(dev.salt)
            packer.packString("nonce")
            packer.packBinaryHeader(dev.nonce.size)
            packer.addPayload(dev.nonce)
        }
        packer.close()

        val payload = bos.toByteArray()
        val frame = ByteArray(1 + payload.size)
        frame[0] = Opcode.MSG_SEND
        payload.copyInto(frame, 1)
        webSocket?.send(frame.toByteString(0, frame.size))
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

    fun sendRead(msgId: Long) {
        val bos = ByteArrayOutputStream(); val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(1); packer.packString("msg_id"); packer.packLong(msgId); packer.close()
        val payload = bos.toByteArray(); val frame = ByteArray(1 + payload.size); frame[0] = Opcode.MSG_READ
        payload.copyInto(frame, 1); webSocket?.send(frame.toByteString(0, frame.size))
    }

    fun sendChatPurgeAck(peerId: Long) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(1)
        packer.packString("chat_user_id")
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

    fun sendKeyPublish(publicKey: ByteArray) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(1)
        packer.packString("x25519_pub")
        packer.packBinaryHeader(publicKey.size)
        packer.addPayload(publicKey)
        packer.close()
        sendFrame(Opcode.KEY_PUBLISH, bos.toByteArray())
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}

data class E2EDevicePayload(
    val deviceId: Long,
    val ciphertext: ByteArray,
    val salt: ByteArray,
    val nonce: ByteArray
)
