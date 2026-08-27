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
import niel.kro.penik.data.network.api.ApiConfig
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
        val clientMsgId: String? = null,
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
    /** A peer renamed themselves; opcode 0x0c. */
    data class UserProfileUpdate(val userId: Long, val name: String) : WebSocketEvent()
    data class PresenceUpdate(val userId: Long, val online: Boolean, val lastSeen: Long) : WebSocketEvent()
    data class TypingNotify(val fromUserId: Long, val isTyping: Boolean) : WebSocketEvent()
    data class GroupAvatarUpdate(val groupId: Long, val ts: Long) : WebSocketEvent()
    data class MsgDeleteNotify(val msgId: String, val chatId: Long, val deleteForEveryone: Boolean) : WebSocketEvent()
    data class MsgEditNotify(
        val fromUserId: Long,
        val fromDeviceId: Long,
        val fromIdentityKey: ByteArray,
        val chatUserId: Long,
        val msgId: Long,
        val clientMsgId: String,
        val ciphertext: ByteArray,
        val salt: ByteArray,
        val nonce: ByteArray,
        val editedAt: Long
    ) : WebSocketEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as MsgEditNotify
            return fromUserId == other.fromUserId && fromDeviceId == other.fromDeviceId &&
                fromIdentityKey.contentEquals(other.fromIdentityKey) && chatUserId == other.chatUserId &&
                msgId == other.msgId && clientMsgId == other.clientMsgId &&
                ciphertext.contentEquals(other.ciphertext) && salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce) && editedAt == other.editedAt
        }
        override fun hashCode(): Int = clientMsgId.hashCode()
    }
    data class GroupMsgEditNotify(
        val groupId: Long,
        val messageId: String,
        val senderUserId: Long,
        val senderDeviceId: Long,
        val keyVersion: Long,
        val ciphertext: ByteArray,
        val salt: ByteArray,
        val nonce: ByteArray,
        val editedAt: Long
    ) : WebSocketEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as GroupMsgEditNotify
            return groupId == other.groupId && messageId == other.messageId &&
                senderUserId == other.senderUserId && senderDeviceId == other.senderDeviceId &&
                keyVersion == other.keyVersion && ciphertext.contentEquals(other.ciphertext) &&
                salt.contentEquals(other.salt) && nonce.contentEquals(other.nonce) &&
                editedAt == other.editedAt
        }
        override fun hashCode(): Int = messageId.hashCode()
    }

    data class CallIncoming(
        val callId: String,
        val fromUserId: Long,
        val isVideo: Boolean,
        val roomName: String,
        val livekitUrl: String,
        val livekitFallbackUrl: String?,
        val token: String
    ) : WebSocketEvent()

    data class CallAccepted(
        val callId: String,
        val toUserId: Long,
        val roomName: String,
        val livekitUrl: String,
        val livekitFallbackUrl: String?,
        val token: String
    ) : WebSocketEvent()

    data class CallReject(val callId: String, val toUserId: Long, val reason: String) : WebSocketEvent()
    data class CallEnd(val callId: String, val toUserId: Long) : WebSocketEvent()

    /**
     * Another device of this account answered or declined the same incoming
     * call, so this device must stop ringing without ending the call.
     */
    data class CallTaken(val callId: String, val reason: String) : WebSocketEvent()
    data class CallLog(val event: niel.kro.penik.data.network.api.CallLogEvent) : WebSocketEvent()

    object Connected : WebSocketEvent()
    object Disconnected : WebSocketEvent()
    object Unauthorized : WebSocketEvent()
    object ServerShutdown : WebSocketEvent()
    object Pong : WebSocketEvent()
}

object Opcode {
    const val MSG_SEND: Byte = 0x01
    const val MSG_RECV: Byte = 0x02
    const val MSG_ACK: Byte = 0x03
    const val MSG_DELIVERED: Byte = 0x04
    const val MSG_READ: Byte = 0x18
    const val MSG_DELETE: Byte = 0x0a
    const val MSG_DELETE_NOTIFY: Byte = 0x0b
    const val USER_PROFILE_UPDATE: Byte = 0x0c
    const val MSG_EDIT: Byte = 0x0d
    const val MSG_EDIT_NOTIFY: Byte = 0x0e
    const val OFFLINE_BATCH: Byte = 0x05
    const val MSG_STATUS_BATCH: Byte = 0x1b
    const val USER_AVATAR_UPDATE: Byte = 0x1c
    const val PRESENCE_UPDATE: Byte = 0x1d
    const val SERVER_SHUTDOWN: Byte = 0x1e
    const val TYPING: Byte = 0x1f
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
    const val GROUP_MESSAGE_EDIT: Byte = 0x29
    const val GROUP_MESSAGE_EDIT_NOTIFY: Byte = 0x2a
    const val CALL_OFFER: Byte = 0x30
    const val CALL_INCOMING: Byte = 0x31
    const val CALL_ACCEPT: Byte = 0x32
    const val CALL_ACCEPTED: Byte = 0x33
    const val CALL_REJECT: Byte = 0x34
    const val CALL_END: Byte = 0x35
    const val CALL_TAKEN: Byte = 0x36
    const val CALL_LOG: Byte = 0x37
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
    CONNECTED,
    UNAUTHORIZED
}

@Singleton
class WebSocketManager @Inject constructor(
    private val tokenStorage: SecureTokenStorage
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Written from OkHttp's callback threads and read from callers, so every
    // field below needs explicit publication; a stale `token` or `reconnectAttempt`
    // meant reconnecting with a revoked token or resetting the backoff to zero.
    @Volatile
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _events = MutableSharedFlow<WebSocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var token: String = ""

    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var pingJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var manualDisconnect = false

    @Volatile
    private var connectHost: String = ""

    @Volatile
    private var connectPort: Int = 0

    @Volatile
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
        if (_connectionState.value != ConnectionState.DISCONNECTED && _connectionState.value != ConnectionState.UNAUTHORIZED) return
        reconnectJob?.cancel()
        reconnectAttempt = 0
        doConnect()
    }

    fun notifyUnauthorized() {
        handleUnauthorized()
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

    fun sendMsgDelete(msgId: String, chatId: Long, deleteForEveryone: Boolean) {
        try {
            val bos = java.io.ByteArrayOutputStream()
            val packer = MessagePack.newDefaultPacker(bos)
            packer.packMapHeader(3)
            packer.packString("msg_id"); packer.packString(msgId)
            packer.packString("chat_id"); packer.packLong(chatId)
            packer.packString("delete_for_everyone"); packer.packBoolean(deleteForEveryone)
            packer.close()
            sendFrame(Opcode.MSG_DELETE, bos.toByteArray())
        } catch (e: Exception) {
            Log.e("WS", "Failed to pack sendMsgDelete", e)
        }
    }

    private fun doConnect() {
        _connectionState.value = ConnectionState.CONNECTING
        try {
            // Derived from the REST scheme rather than the port: a non-443 port
            // (reverse proxy, staging) must still upgrade over TLS, and plaintext
            // ws:// would expose the session token carried in the protocol header.
            val scheme = if (ApiConfig.SCHEME == "https") "wss" else "ws"
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
                    Log.e("WS", "Failure: ${t.message}, code: ${response?.code}")
                    val is401 = response?.code == 401 || (t.message?.contains("401") == true)
                    if (is401) {
                        handleUnauthorized()
                    } else {
                        handleDisconnect()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("WS", "Failed to construct WebSocket", e)
            handleDisconnect()
        }
    }

    private fun handleUnauthorized() {
        _connectionState.value = ConnectionState.UNAUTHORIZED
        pingJob?.cancel()
        reconnectJob?.cancel()
        scope.launch { _events.emit(WebSocketEvent.Unauthorized) }
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
                delay(5_000)
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
            Opcode.MSG_DELETE_NOTIFY -> handleMsgDeleteNotify(payload)
            Opcode.MSG_EDIT_NOTIFY -> handleMsgEditNotify(payload)
            Opcode.OFFLINE_BATCH -> handleOfflineBatch(payload)
            Opcode.MSG_STATUS_BATCH -> handleMsgStatusBatch(payload)
            Opcode.PING -> sendPong()
            Opcode.PONG -> scope.launch { _events.emit(WebSocketEvent.Pong) }
            Opcode.CHAT_PURGE -> handleChatPurge(payload)
            Opcode.PAIRING_HISTORY_READY -> handlePairingHistoryReady(payload)
            Opcode.USER_AVATAR_UPDATE -> handleUserAvatarUpdate(payload)
            Opcode.USER_PROFILE_UPDATE -> handleUserProfileUpdate(payload)
            Opcode.PRESENCE_UPDATE -> handlePresenceUpdate(payload)
            Opcode.TYPING -> handleTyping(payload)
            Opcode.SERVER_SHUTDOWN -> scope.launch { _events.emit(WebSocketEvent.ServerShutdown) }
            Opcode.GROUP_MESSAGE_RECV -> handleGroupMessageRecv(payload)
            Opcode.GROUP_MESSAGE_ACK -> handleGroupMessageAck(payload)
            Opcode.GROUP_KEY_AVAILABLE -> handleGroupKeyAvailable(payload)
            Opcode.GROUP_MEMBER_CHANGED -> handleGroupMemberChanged(payload)
            Opcode.GROUP_AVATAR_UPDATE -> handleGroupAvatarUpdate(payload)
            Opcode.GROUP_MESSAGE_EDIT_NOTIFY -> handleGroupMessageEditNotify(payload)
            Opcode.CALL_INCOMING -> handleCallIncoming(payload)
            Opcode.CALL_ACCEPTED -> handleCallAccepted(payload)
            Opcode.CALL_REJECT -> handleCallReject(payload)
            Opcode.CALL_END -> handleCallEnd(payload)
            Opcode.CALL_TAKEN -> handleCallTaken(payload)
            Opcode.CALL_LOG -> handleCallLog(payload)
        }
    }

    private fun handleCallLog(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val callId = map["call_id"]?.toString().orEmpty()
            val callerId = (map["caller_id"] as? Number)?.toLong() ?: 0L
            val calleeId = (map["callee_id"] as? Number)?.toLong() ?: 0L
            val isVideo = map["is_video"] as? Boolean ?: false
            val status = map["status"]?.toString().orEmpty()
            val startedAt = (map["started_at"] as? Number)?.toLong() ?: 0L
            val answeredAt = (map["answered_at"] as? Number)?.toLong() ?: 0L
            val endedAt = (map["ended_at"] as? Number)?.toLong() ?: 0L
            val duration = (map["duration"] as? Number)?.toLong() ?: 0L

            scope.launch {
                _events.emit(
                    WebSocketEvent.CallLog(
                        niel.kro.penik.data.network.api.CallLogEvent(
                            callId = callId,
                            callerId = callerId,
                            calleeId = calleeId,
                            isVideo = isVideo,
                            status = status,
                            startedAt = startedAt,
                            answeredAt = answeredAt,
                            endedAt = endedAt,
                            duration = duration
                        )
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse CallLog frame", e)
        }
    }

    private fun handleUserProfileUpdate(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val userId = (map["user_id"] as? Number)?.toLong() ?: return
            val name = map["name"]?.toString().orEmpty()
            if (name.isBlank()) return
            scope.launch { _events.emit(WebSocketEvent.UserProfileUpdate(userId, name)) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse user profile update", e)
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

    private fun handleTyping(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val fromUserId = (map["from_user_id"] as? Number)?.toLong() ?: return
            val isTyping = map["is_typing"] as? Boolean ?: false
            scope.launch { _events.emit(WebSocketEvent.TypingNotify(fromUserId, isTyping)) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse Typing frame", e)
        }
    }

    fun sendTyping(toUserId: Long, isTyping: Boolean) {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(2)
        packer.packString("to_user_id"); packer.packLong(toUserId)
        packer.packString("is_typing"); packer.packBoolean(isTyping)
        packer.close()
        sendFrame(Opcode.TYPING, bos.toByteArray())
    }

    private fun handleCallIncoming(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val callId = map["call_id"] as? String ?: return
            val fromUserId = (map["from_user_id"] as? Number)?.toLong() ?: return
            scope.launch {
                _events.emit(
                    WebSocketEvent.CallIncoming(
                        callId = callId,
                        fromUserId = fromUserId,
                        isVideo = map["is_video"] as? Boolean ?: false,
                        roomName = map["room_name"] as? String ?: "",
                        livekitUrl = map["livekit_url"] as? String ?: "",
                        livekitFallbackUrl = map["livekit_fallback_url"] as? String,
                        token = map["token"] as? String ?: ""
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse CallIncoming frame", e)
        }
    }

    private fun handleCallAccepted(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val callId = map["call_id"] as? String ?: return
            scope.launch {
                _events.emit(
                    WebSocketEvent.CallAccepted(
                        callId = callId,
                        toUserId = (map["to_user_id"] as? Number)?.toLong() ?: 0L,
                        roomName = map["room_name"] as? String ?: "",
                        livekitUrl = map["livekit_url"] as? String ?: "",
                        livekitFallbackUrl = map["livekit_fallback_url"] as? String,
                        token = map["token"] as? String ?: ""
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse CallAccepted frame", e)
        }
    }

    private fun handleCallReject(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val callId = map["call_id"] as? String ?: return
            scope.launch {
                _events.emit(
                    WebSocketEvent.CallReject(
                        callId = callId,
                        toUserId = (map["to_user_id"] as? Number)?.toLong() ?: 0L,
                        reason = map["reason"] as? String ?: "declined"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse CallReject frame", e)
        }
    }

    private fun handleCallEnd(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val callId = map["call_id"] as? String ?: return
            scope.launch {
                _events.emit(
                    WebSocketEvent.CallEnd(
                        callId = callId,
                        toUserId = (map["to_user_id"] as? Number)?.toLong() ?: 0L
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse CallEnd frame", e)
        }
    }

    private fun handleCallTaken(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val callId = map["call_id"] as? String ?: return
            scope.launch {
                _events.emit(
                    WebSocketEvent.CallTaken(
                        callId = callId,
                        reason = map["reason"] as? String ?: "accepted"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse CallTaken frame", e)
        }
    }

    fun sendCallOffer(toUserId: Long, isVideo: Boolean) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(2)
        packer.packString("to_user_id"); packer.packLong(toUserId)
        packer.packString("is_video"); packer.packBoolean(isVideo)
        packer.close()
        sendFrame(Opcode.CALL_OFFER, bos.toByteArray())
    }

    fun sendCallAccept(callId: String) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(1)
        packer.packString("call_id"); packer.packString(callId)
        packer.close()
        sendFrame(Opcode.CALL_ACCEPT, bos.toByteArray())
    }

    fun sendCallReject(callId: String, toUserId: Long, reason: String) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(3)
        packer.packString("call_id"); packer.packString(callId)
        packer.packString("to_user_id"); packer.packLong(toUserId)
        packer.packString("reason"); packer.packString(reason)
        packer.close()
        sendFrame(Opcode.CALL_REJECT, bos.toByteArray())
    }

    fun sendCallEnd(callId: String, toUserId: Long) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(2)
        packer.packString("call_id"); packer.packString(callId)
        packer.packString("to_user_id"); packer.packLong(toUserId)
        packer.close()
        sendFrame(Opcode.CALL_END, bos.toByteArray())
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
    private fun handleMsgDeleteNotify(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val map = unpacker.readMsgRecvMap()
            unpacker.close()
            val msgId = (map["msg_id"] as? String) ?: (map["msg_id"] as? Number)?.toString() ?: ""
            val chatId = (map["chat_id"] as? Number)?.toLong() ?: 0L
            val deleteForEveryone = map["delete_for_everyone"] as? Boolean ?: true
            scope.launch { _events.emit(WebSocketEvent.MsgDeleteNotify(msgId, chatId, deleteForEveryone)) }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse MsgDeleteNotify frame", e)
        }
    }

    private fun handleMsgEditNotify(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val size = unpacker.unpackMapHeader()
            var fromUserId = 0L
            var fromDeviceId = 0L
            var fromIdentityKey = ByteArray(0)
            var chatUserId = 0L
            var msgId = 0L
            var clientMsgId = ""
            var ciphertext = ByteArray(0)
            var salt = ByteArray(0)
            var nonce = ByteArray(0)
            var editedAt = 0L

            for (i in 0 until size) {
                val key = unpacker.unpackString()
                if (unpacker.nextFormat == MessageFormat.NIL) { unpacker.unpackNil(); continue }
                when (key) {
                    "from_user_id" -> fromUserId = unpacker.unpackLong()
                    "from_device_id" -> fromDeviceId = unpacker.unpackLong()
                    "from_identity_key" -> { val len = unpacker.unpackBinaryHeader(); fromIdentityKey = unpacker.readPayload(len) }
                    "chat_user_id" -> chatUserId = unpacker.unpackLong()
                    "msg_id" -> msgId = unpacker.unpackLong()
                    "client_msg_id" -> clientMsgId = unpacker.unpackString()
                    "ciphertext" -> { val len = unpacker.unpackBinaryHeader(); ciphertext = unpacker.readPayload(len) }
                    "salt" -> { val len = unpacker.unpackBinaryHeader(); salt = unpacker.readPayload(len) }
                    "nonce" -> { val len = unpacker.unpackBinaryHeader(); nonce = unpacker.readPayload(len) }
                    "edited_at" -> editedAt = unpacker.unpackLong()
                    else -> unpacker.unpackValue()
                }
            }
            unpacker.close()
            scope.launch {
                _events.emit(
                    WebSocketEvent.MsgEditNotify(
                        fromUserId = fromUserId,
                        fromDeviceId = fromDeviceId,
                        fromIdentityKey = fromIdentityKey,
                        chatUserId = chatUserId,
                        msgId = msgId,
                        clientMsgId = clientMsgId,
                        ciphertext = ciphertext,
                        salt = salt,
                        nonce = nonce,
                        editedAt = editedAt * 1000
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse MsgEditNotify frame", e)
        }
    }

    private fun handleGroupMessageEditNotify(payload: ByteArray) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
            val size = unpacker.unpackMapHeader()
            var groupId = 0L
            var messageId = ""
            var senderUserId = 0L
            var senderDeviceId = 0L
            var keyVersion = 0L
            var ciphertext = ByteArray(0)
            var salt = ByteArray(0)
            var nonce = ByteArray(0)
            var editedAt = 0L

            for (i in 0 until size) {
                val key = unpacker.unpackString()
                if (unpacker.nextFormat == MessageFormat.NIL) { unpacker.unpackNil(); continue }
                when (key) {
                    "group_id" -> groupId = unpacker.unpackLong()
                    "message_id" -> messageId = unpacker.unpackString()
                    "sender_user_id" -> senderUserId = unpacker.unpackLong()
                    "sender_device_id" -> senderDeviceId = unpacker.unpackLong()
                    "key_version" -> keyVersion = unpacker.unpackLong()
                    "ciphertext" -> { val len = unpacker.unpackBinaryHeader(); ciphertext = unpacker.readPayload(len) }
                    "salt" -> { val len = unpacker.unpackBinaryHeader(); salt = unpacker.readPayload(len) }
                    "nonce" -> { val len = unpacker.unpackBinaryHeader(); nonce = unpacker.readPayload(len) }
                    "edited_at" -> editedAt = unpacker.unpackLong()
                    else -> unpacker.unpackValue()
                }
            }
            unpacker.close()
            scope.launch {
                _events.emit(
                    WebSocketEvent.GroupMsgEditNotify(
                        groupId = groupId,
                        messageId = messageId,
                        senderUserId = senderUserId,
                        senderDeviceId = senderDeviceId,
                        keyVersion = keyVersion,
                        ciphertext = ciphertext,
                        salt = salt,
                        nonce = nonce,
                        editedAt = editedAt * 1000
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("WS", "Failed to parse GroupMsgEditNotify frame", e)
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
        var clientMsgId: String? = null
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
                "client_msg_id" -> clientMsgId = unpackString()
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
            clientMsgId = clientMsgId,
            ciphertext = ciphertext,
            salt = salt,
            nonce = nonce,
            ts = ts * 1000,
            replyToMsgId = replyToMsgId
        )
    }

    fun sendEncryptedMessage(toUserId: Long, clientMsgId: String, devices: List<E2EDevicePayload>, replyToMsgId: String? = null, createdAt: Long = 0L) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        var mapSize = 3
        if (replyToMsgId != null) mapSize++
        if (createdAt > 0L) mapSize++
        packer.packMapHeader(mapSize)
        packer.packString("to_user_id")
        packer.packLong(toUserId)
        packer.packString("msg_id")
        packer.packString(clientMsgId)
        if (createdAt > 0L) {
            packer.packString("created_at")
            packer.packLong(createdAt)
        }
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

    fun sendEncryptedEdit(toUserId: Long, clientMsgId: String, devices: List<E2EDevicePayload>, editedAt: Long = 0L) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        var mapSize = 3
        if (editedAt > 0L) mapSize++
        packer.packMapHeader(mapSize)
        packer.packString("to_user_id")
        packer.packLong(toUserId)
        packer.packString("msg_id")
        packer.packString(clientMsgId)
        if (editedAt > 0L) {
            packer.packString("edited_at")
            packer.packLong(editedAt)
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
        frame[0] = Opcode.MSG_EDIT
        payload.copyInto(frame, 1)
        webSocket?.send(frame.toByteString(0, frame.size))
    }

    fun sendGroupMessageEdit(groupId: Long, messageId: String, keyVersion: Long, ciphertext: ByteArray, salt: ByteArray, nonce: ByteArray, editedAt: Long) {
        val bos = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(bos)
        packer.packMapHeader(7)
        packer.packString("group_id"); packer.packLong(groupId)
        packer.packString("message_id"); packer.packString(messageId)
        packer.packString("key_version"); packer.packLong(keyVersion)
        packer.packString("ciphertext"); packer.packBinaryHeader(ciphertext.size); packer.addPayload(ciphertext)
        packer.packString("salt"); packer.packBinaryHeader(salt.size); packer.addPayload(salt)
        packer.packString("nonce"); packer.packBinaryHeader(nonce.size); packer.addPayload(nonce)
        packer.packString("edited_at"); packer.packLong(editedAt)
        packer.close()

        val payload = bos.toByteArray()
        val frame = ByteArray(1 + payload.size)
        frame[0] = Opcode.GROUP_MESSAGE_EDIT
        payload.copyInto(frame, 1)
        webSocket?.send(frame.toByteString(0, frame.size))
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
