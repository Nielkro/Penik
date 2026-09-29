package niel.kro.penik.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.async
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.dao.GroupDao
import niel.kro.penik.data.local.entity.MessageEntity
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val groupDao: GroupDao,
    private val apiService: ApiService,
    private val webSocketManager: WebSocketManager,
    private val tokenStorage: SecureTokenStorage,
    private val chatRepository: ChatRepository,
) {
    private val bundleCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, List<niel.kro.penik.data.network.api.DeviceBundle>>>()
    private val bundleForceAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val bundleInflight = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Deferred<List<niel.kro.penik.data.network.api.DeviceBundle>>>()
    private val bundleScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private val historySyncInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun clearHopelessFor(userId: Long) {
    }

    private fun isPlaceholderName(name: String): Boolean {
        if (name.isBlank() || name == "Неизвестный") return true
        val rest = name.removePrefix("Пользователь").trim().removePrefix("#").trim()
        return name.startsWith("Пользователь") && rest.all { it.isDigit() } && rest.isNotEmpty()
    }

    suspend fun getKeyBundleCached(userId: Long, isSelf: Boolean = false, forceRefresh: Boolean = false): List<niel.kro.penik.data.network.api.DeviceBundle> {
        val now = System.currentTimeMillis()
        if (forceRefresh && now - (bundleForceAt[userId] ?: 0L) < 10_000L) {
            return bundleCache[userId]?.second ?: emptyList()
        }
        if (forceRefresh) bundleForceAt[userId] = now
        if (!forceRefresh) {
            val cached = bundleCache[userId]
            if (cached != null && cached.first > now) {
                return cached.second
            }
        }
        val deferred: kotlinx.coroutines.Deferred<List<niel.kro.penik.data.network.api.DeviceBundle>>
        val owner: Boolean
        synchronized(bundleInflight) {
            val existing = bundleInflight[userId]
            if (existing != null) {
                deferred = existing
                owner = false
            } else {
                deferred = bundleScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    fetchKeyBundle(userId, isSelf, System.currentTimeMillis())
                }
                bundleInflight[userId] = deferred
                owner = true
            }
        }
        if (owner) deferred.start()
        return try {
            deferred.await()
        } finally {
            if (owner) bundleInflight.remove(userId, deferred)
        }
    }

    private suspend fun fetchKeyBundle(userId: Long, isSelf: Boolean, now: Long): List<niel.kro.penik.data.network.api.DeviceBundle> {
        try {
            val response = if (isSelf) apiService.getKeyBundleSelf(userId) else apiService.getKeyBundle(userId)
            if (response.isSuccessful) {
                val devices = response.body()?.devices ?: emptyList()
                val ttl = if (devices.isEmpty()) 10_000L else if (isSelf) 30 * 1000L else 2 * 60 * 1000L
                bundleCache[userId] = Pair(now + ttl, devices)
                return devices
            }
            Log.w("PenikMsg", "Key bundle fetch for $userId failed: HTTP ${response.code()}")
        } catch (e: Exception) {
            Log.e("PenikMsg", "Failed to fetch key bundle for $userId", e)
        }
        return bundleCache[userId]?.second ?: emptyList()
    }

    fun invalidateKeyBundle(userId: Long) {
        bundleCache.remove(userId)
        bundleForceAt.remove(userId)
    }

    fun getMessagesForChat(chatUserId: Long, isE2EE: Boolean? = null): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForChat(chatUserId)
    }

    fun observeLastMessageForChat(chatUserId: Long) = messageDao.observeLastMessageForChat(chatUserId)

    suspend fun insertOptimisticMessage(toUserId: Long, clientMsgId: String, text: String, replyToMsgId: String? = null) {
        val myId = tokenStorage.getUserId()
        val resolvedReplyToMsgId = if (!replyToMsgId.isNullOrBlank()) {
            val parentObj = messageDao.findMessageByLocalId(replyToMsgId) 
                ?: messageDao.findMessageByServerId(replyToMsgId.toLongOrNull() ?: -1L)
            parentObj?.localId ?: parentObj?.serverId?.toString() ?: replyToMsgId
        } else null

        val entity = MessageEntity(
            localId = clientMsgId,
            chatUserId = toUserId,
            senderId = myId,
            text = text,
            timestamp = System.currentTimeMillis(),
            sentByMe = true,
            delivered = false,
            replyToMsgId = resolvedReplyToMsgId
        )
        messageDao.insertMessage(entity)
    }

    suspend fun sendMessage(
        toUserId: Long,
        text: String,
        replyToMsgId: String? = null,
        existingClientMsgId: String? = null,
        isE2EE: Boolean? = null
    ): String {
        val clientMsgId = existingClientMsgId ?: UUID.randomUUID().toString()
        val myId = tokenStorage.getUserId()

        val resolvedReplyToMsgId = if (!replyToMsgId.isNullOrBlank()) {
            val parentObj = messageDao.findMessageByLocalId(replyToMsgId) 
                ?: messageDao.findMessageByServerId(replyToMsgId.toLongOrNull() ?: -1L)
            parentObj?.localId ?: parentObj?.serverId?.toString() ?: replyToMsgId
        } else null

        val existing = messageDao.findMessageByLocalId(clientMsgId)
        if (existing == null) {
            val entity = MessageEntity(
                localId = clientMsgId,
                chatUserId = toUserId,
                senderId = myId,
                text = text,
                timestamp = System.currentTimeMillis(),
                sentByMe = true,
                delivered = false,
                replyToMsgId = resolvedReplyToMsgId,
                isE2EE = false
            )
            messageDao.insertMessage(entity)
        } else {
            messageDao.updateMessageText(clientMsgId, null, text, 0L)
        }

        val nowSec = niel.kro.penik.data.network.TimeSyncManager.currentTimeSec()
        webSocketManager.sendMessage(toUserId, text, clientMsgId, resolvedReplyToMsgId, createdAt = nowSec)
        return clientMsgId
    }

    suspend fun retryPendingMessages() {
        val pending = messageDao.getPendingMessages()
        if (pending.isEmpty()) return
        for (msg in pending) {
            runCatching {
                sendMessage(
                    toUserId = msg.chatUserId,
                    text = msg.text,
                    replyToMsgId = msg.replyToMsgId,
                    existingClientMsgId = msg.localId
                )
            }.onFailure { e ->
                Log.e("PenikMsg", "Failed to retry pending message ${msg.localId}", e)
            }
        }
    }

    suspend fun retryMessage(localId: String): Boolean {
        val msg = messageDao.findMessageByLocalId(localId) ?: return false
        return runCatching {
            sendMessage(
                toUserId = msg.chatUserId,
                text = msg.text,
                replyToMsgId = msg.replyToMsgId,
                existingClientMsgId = msg.localId
            )
            true
        }.getOrDefault(false)
    }

    suspend fun handleMsgAck(event: WebSocketEvent.MsgAck) {
        messageDao.acknowledgeMessage(event.clientMsgId, event.serverMsgId)
    }

    suspend fun handleMsgDelivered(event: WebSocketEvent.MsgDelivered) {
        if (event.clientMsgId.isNotBlank()) {
            messageDao.markDeliveredByClientId(event.clientMsgId)
        }
        messageDao.markDelivered(event.msgId)
    }

    suspend fun handleMsgRead(event: WebSocketEvent.MsgRead) {
        if (event.clientMsgId.isNotBlank()) {
            messageDao.markReadByClientId(event.clientMsgId)
        }
        messageDao.markRead(event.msgId)
    }

    suspend fun markMessageAsRead(serverId: Long) {
        val existing = messageDao.findMessageByServerId(serverId)
        if (existing != null) {
            val isFailed = existing.text.startsWith("[Ошибка")
            if (isFailed) return
        }
        messageDao.markRead(serverId)
        webSocketManager.sendRead(serverId)
    }

    fun sendRead(serverId: Long) {
        webSocketManager.sendRead(serverId)
    }

    fun sendTyping(toUserId: Long, isTyping: Boolean) {
        webSocketManager.sendTyping(toUserId, isTyping)
    }

    suspend fun handleMsgRecv(event: WebSocketEvent.MsgRecv): Boolean {
        val sentByMe = event.fromUserId == tokenStorage.getUserId()
        if (messageDao.findLocalIdByServerId(event.msgId) != null) {
            if (!sentByMe) {
                webSocketManager.sendDelivered(event.msgId)
            }
            return !sentByMe
        }
        val entity = MessageEntity(
            localId = "server-${event.msgId}",
            serverId = event.msgId,
            chatUserId = event.chatUserId,
            senderId = event.fromUserId,
            text = event.text,
            timestamp = toMs(event.ts),
            sentByMe = sentByMe,
            delivered = true
        )
        messageDao.insertMessage(entity)
        if (!sentByMe) {
            webSocketManager.sendDelivered(event.msgId)
        }
        return !sentByMe
    }

    suspend fun handleMsgRecvEncrypted(event: WebSocketEvent.MsgRecvEncrypted): Pair<String, Boolean> {
        val myId = tokenStorage.getUserId()
        val sentByMe = event.fromUserId == myId

        var existing = messageDao.findMessageByServerId(event.msgId)
        if (existing == null && !event.clientMsgId.isNullOrBlank()) {
            existing = messageDao.findMessageByLocalId(event.clientMsgId)
            if (existing != null) {
                messageDao.acknowledgeMessage(existing.localId, event.msgId)
            }
        }
        if (existing == null && sentByMe) {
            existing = messageDao.findClosestUnacknowledgedMessage(event.chatUserId, myId, event.ts)
            if (existing != null) {
                messageDao.acknowledgeMessage(existing.localId, event.msgId)
            }
        }

        val text = event.plaintext ?: ""
        if (existing != null) {
            val updated = existing.copy(text = text, isE2EE = false)
            messageDao.insertMessage(updated)
            if (!sentByMe) {
                webSocketManager.sendDelivered(event.msgId)
            }
            return Pair(text, !sentByMe)
        }

        val entity = MessageEntity(
            localId = if (!event.clientMsgId.isNullOrBlank()) event.clientMsgId else "server-${event.msgId}",
            serverId = event.msgId,
            chatUserId = event.chatUserId,
            senderId = event.fromUserId,
            text = text,
            timestamp = toMs(event.ts),
            sentByMe = sentByMe,
            delivered = true,
            replyToMsgId = event.replyToMsgId,
            isE2EE = false
        )
        messageDao.insertMessage(entity)
        if (!sentByMe) {
            webSocketManager.sendDelivered(event.msgId)
        }
        return Pair(text, !sentByMe)
    }

    suspend fun handleOfflineBatch(event: WebSocketEvent.OfflineBatch) {
        val myId = tokenStorage.getUserId()
        val entities = buildList {
            event.msgs.forEach { msg ->
                if (messageDao.findLocalIdByServerId(msg.msgId) == null) {
                    add(MessageEntity(
                        localId = "server-${msg.msgId}",
                        serverId = msg.msgId,
                        chatUserId = msg.chatUserId,
                        senderId = msg.fromUserId,
                        text = msg.text,
                        timestamp = toMs(msg.ts),
                        sentByMe = msg.fromUserId == myId,
                        delivered = true
                    ))
                }
            }
        }
        messageDao.insertMessages(entities)
        event.msgs.forEach { webSocketManager.sendDelivered(it.msgId) }
    }

    suspend fun handleOfflineBatchEncrypted(event: WebSocketEvent.OfflineBatchEncrypted): List<DecryptedOfflineMsg> {
        val myId = tokenStorage.getUserId()
        val decryptedList = mutableListOf<DecryptedOfflineMsg>()
        val entities = buildList {
            event.msgs.forEach { msg ->
                val isSelfChat = msg.fromUserId == myId && msg.chatUserId == myId
                val existing = messageDao.findMessageByServerId(msg.msgId)
                val text = ""
                if (existing == null) {
                    if (!isSelfChat) {
                        decryptedList.add(DecryptedOfflineMsg(
                            chatUserId = msg.chatUserId,
                            text = text,
                            ts = msg.ts,
                            isIncoming = msg.fromUserId != myId,
                            msgId = msg.msgId
                        ))
                    }
                    add(MessageEntity(
                        localId = if (!msg.clientMsgId.isNullOrBlank()) msg.clientMsgId else "server-${msg.msgId}",
                        serverId = msg.msgId,
                        chatUserId = msg.chatUserId,
                        senderId = msg.fromUserId,
                        text = text,
                        timestamp = toMs(msg.ts),
                        sentByMe = msg.fromUserId == myId,
                        delivered = true,
                        replyToMsgId = msg.replyToMsgId
                    ))
                }
            }
        }
        messageDao.insertMessages(entities)
        event.msgs.forEach { msg ->
            if (msg.fromUserId != myId) {
                webSocketManager.sendDelivered(msg.msgId)
            }
        }
        return decryptedList
    }

    suspend fun reconcileLocalChats() {
        try {
            val allEntities = messageDao.getAllMessages()
            if (allEntities.isNotEmpty()) {
                allEntities.groupBy { it.chatUserId }.forEach { (chatUserId, msgs) ->
                    val unreadCount = msgs.count { !it.sentByMe && !it.read && it.text != "[DELETED]" }
                    chatRepository.updateUnreadCount(chatUserId, unreadCount)

                    val existingChat = chatRepository.getChat(chatUserId)
                    val latestMsg = msgs.filter { it.text != "[DELETED]" }.maxByOrNull { it.timestamp }
                    if (latestMsg != null && (existingChat == null || existingChat.lastMessage.isNullOrBlank() || existingChat.name.isBlank())) {
                        val name = existingChat?.name.orEmpty()
                        val nickname = existingChat?.nickname.orEmpty()
                        chatRepository.updateLastMessage(
                            userId = chatUserId,
                            text = latestMsg.text,
                            timestamp = latestMsg.timestamp,
                            name = name,
                            nickname = nickname
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MessageRepository", "Failed to reconcile local chats", e)
        }
    }

    suspend fun syncHistory(chatUserId: Long? = null, beforeId: Long? = null, limit: Int = 500) {
        val syncKey = "$chatUserId:$beforeId"
        if (!historySyncInFlight.add(syncKey)) return
        try {
            reconcileLocalChats()
            val allLocal = messageDao.getAllMessages()
            val maxServerId = if (chatUserId == null && beforeId == null) {
                allLocal.mapNotNull { it.serverId }.maxOrNull()
            } else null

            val response = apiService.getMessageHistory(
                limit = limit,
                beforeId = beforeId,
                afterId = maxServerId,
                chatUserId = chatUserId
            )
            if (response.isSuccessful) {
                val messages = response.body() ?: emptyList()
                val myId = tokenStorage.getUserId()
                val newMessages = mutableListOf<HistoryMsgDecrypted>()
                val entities = buildList {
                    messages.forEach { msg ->
                        if (msg.senderId == myId && !msg.clientMsgId.isNullOrBlank()) {
                            messageDao.acknowledgeMessage(msg.clientMsgId, msg.msgId)
                        }
                        var existing = messageDao.findMessageByServerId(msg.msgId)
                        if (existing == null && !msg.clientMsgId.isNullOrBlank()) {
                            existing = messageDao.findMessageByLocalId(msg.clientMsgId)
                            if (existing != null) {
                                messageDao.acknowledgeMessage(existing.localId, msg.msgId)
                            }
                        }
                        if (existing == null && msg.senderId == myId) {
                            existing = messageDao.findClosestUnacknowledgedMessage(msg.chatUserId, myId, msg.createdAt * 1000)
                            if (existing != null) {
                                messageDao.acknowledgeMessage(existing.localId, msg.msgId)
                            }
                        }
                        val isEdited = msg.editedAt != null && (existing == null || existing.editedAt == null || (msg.editedAt * 1000 > (existing.editedAt ?: 0L)))
                        if (existing == null || isEdited) {
                            val text = msg.plaintext ?: ""
                            val editedAtMs = msg.editedAt?.let { it * 1000 }
                            if (existing != null) {
                                messageDao.updateMessageText(existing.localId, msg.msgId, text, editedAtMs ?: existing.editedAt ?: 0L)
                            } else {
                                newMessages.add(HistoryMsgDecrypted(msg.chatUserId, text, msg.senderId, msg.createdAt * 1000))
                                add(MessageEntity(
                                    localId = msg.clientMsgId ?: "server-${msg.msgId}",
                                    serverId = msg.msgId,
                                    chatUserId = msg.chatUserId,
                                    senderId = msg.senderId,
                                    text = text,
                                    timestamp = msg.createdAt * 1000,
                                    sentByMe = msg.senderId == myId,
                                    delivered = msg.delivered == 1,
                                    deliveredAt = msg.deliveredAt,
                                    read = msg.read == 1,
                                    replyToMsgId = msg.replyToMsgId,
                                    editedAt = editedAtMs,
                                    isE2EE = false
                                ))
                            }
                        }
                        if (existing != null) {
                            if (existing.delivered != (msg.delivered == 1) || existing.read != (msg.read == 1)) {
                                messageDao.updateStatus(
                                    serverId = msg.msgId,
                                    delivered = msg.delivered == 1,
                                    read = msg.read == 1,
                                    deliveredAt = msg.deliveredAt ?: System.currentTimeMillis()
                                )
                            }
                            newMessages.add(HistoryMsgDecrypted(msg.chatUserId, existing.text, msg.senderId, msg.createdAt * 1000))
                        }
                    }
                }
                messageDao.insertMessages(entities)

                messages.filter { it.senderId == myId }
                    .map { it.chatUserId }
                    .distinct()
                    .forEach { peerId ->
                        val statusResponse = apiService.getMessageStatuses(peerId)
                        if (statusResponse.isSuccessful) {
                            statusResponse.body().orEmpty().forEach { status ->
                                messageDao.updateStatus(status.msgId, status.delivered, status.read)
                            }
                        }
                    }

                val allChatUserIds = messages.map { it.chatUserId }.distinct()
                for (peerId in allChatUserIds) {
                    val existing = chatRepository.getChat(peerId)
                    val profile = if (existing == null || existing.name.isBlank()) {
                        try {
                            apiService.getUserProfile(peerId).body()
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                    chatRepository.upsertContact(
                        userId = peerId,
                        nickname = profile?.nickname ?: existing?.nickname.orEmpty(),
                        name = profile?.name ?: existing?.name.orEmpty(),
                        avatarUrl = existing?.avatarUrl
                    )
                }

                newMessages.groupBy { it.chatUserId }.forEach { (chatUserId, chatMessages) ->
                    val latest = chatMessages.maxByOrNull { it.createdAt }
                    if (latest != null) {
                        val existing = chatRepository.getChat(chatUserId)
                        val profile = if (existing == null || existing.name.isBlank() || isPlaceholderName(existing.name)) {
                            try {
                                apiService.getUserProfile(chatUserId).body()
                            } catch (_: Exception) {
                                null
                            }
                        } else null
                        chatRepository.updateLastMessage(
                            userId = chatUserId,
                            text = latest.text,
                            timestamp = latest.createdAt,
                            name = profile?.name ?: existing?.name.orEmpty(),
                            nickname = profile?.nickname ?: existing?.nickname.orEmpty()
                        )
                    }
                }

                val allEntities = messageDao.getAllMessages()
                allEntities.groupBy { it.chatUserId }.forEach { (chatUserId, msgs) ->
                    val unreadCount = msgs.count { !it.sentByMe && !it.read && it.text != "[DELETED]" }
                    chatRepository.updateUnreadCount(chatUserId, unreadCount)

                    val existingChat = chatRepository.getChat(chatUserId)
                    val latestMsg = msgs.filter { it.text != "[DELETED]" }.maxByOrNull { it.timestamp }
                    if (latestMsg != null) {
                        val profile = if (existingChat == null || existingChat.name.isBlank() || isPlaceholderName(existingChat.name)) {
                            try {
                                apiService.getUserProfile(chatUserId).body()
                            } catch (_: Exception) { null }
                        } else null
                        val existingName = existingChat?.name?.takeIf { it.isNotBlank() && !isPlaceholderName(it) }
                        val name = profile?.name?.ifBlank { profile.nickname } ?: existingName ?: "Пользователь $chatUserId"
                        val nickname = profile?.nickname ?: existingChat?.nickname.orEmpty()
                        chatRepository.updateLastMessage(
                            userId = chatUserId,
                            text = latestMsg.text,
                            timestamp = latestMsg.timestamp,
                            name = name,
                            nickname = nickname
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "Failed to sync history", e)
        } finally {
            historySyncInFlight.remove(syncKey)
        }
    }

    suspend fun resolvePushMessage(msgId: Long): String? {
        if (msgId <= 0L) return null
        messageDao.findMessageByServerId(msgId)?.let { return it.text }

        val body = runCatching { apiService.getMessageById(msgId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body() ?: return null

        val plain = body.plaintext ?: ""
        persistPushMessage(body, plain)
        return plain
    }

    private suspend fun persistPushMessage(body: niel.kro.penik.data.network.api.HistoryMessageResponse, text: String) {
        val myId = tokenStorage.getUserId()
        val sentByMe = body.senderId == myId
        messageDao.insertMessage(
            MessageEntity(
                localId = if (!body.clientMsgId.isNullOrBlank()) body.clientMsgId!! else "server-${body.msgId}",
                serverId = body.msgId,
                chatUserId = body.chatUserId,
                senderId = body.senderId,
                text = text,
                timestamp = toMs(body.createdAt),
                sentByMe = sentByMe,
                delivered = true,
                replyToMsgId = body.replyToMsgId
            )
        )
        chatRepository.updateLastMessage(body.chatUserId, text, toMs(body.createdAt))
        if (!sentByMe) {
            webSocketManager.sendDelivered(body.msgId)
        }
    }

    suspend fun handleMsgEditNotify(event: WebSocketEvent.MsgEditNotify) {
        val text = event.plaintext ?: ""
        val editedAtMs = toMs(event.editedAt)
        messageDao.updateMessageText(
            clientMsgId = event.clientMsgId,
            serverId = event.msgId,
            newText = text,
            editedAt = editedAtMs
        )
        updateChatLastMessage(event.chatUserId)
    }

    suspend fun editMessage(chatUserId: Long, clientMsgId: String, newText: String) {
        val nowSec = niel.kro.penik.data.network.TimeSyncManager.currentTimeSec()
        val editedAtMs = nowSec * 1000

        val existingMsg = messageDao.findMessageByLocalId(clientMsgId)
            ?: (clientMsgId.toLongOrNull()?.let { messageDao.findMessageByServerId(it) })
        val finalPayload = if (existingMsg != null && existingMsg.text.startsWith("{")) {
            runCatching {
                val root = org.json.JSONObject(existingMsg.text)
                if (root.optString("type") == "file" || root.has("file")) {
                    root.put("text", newText)
                    root.toString()
                } else {
                    newText
                }
            }.getOrElse { newText }
        } else {
            newText
        }

        if (finalPayload.isBlank()) return

        messageDao.updateMessageText(
            clientMsgId = clientMsgId,
            serverId = clientMsgId.toLongOrNull(),
            newText = finalPayload,
            editedAt = editedAtMs
        )
        updateChatLastMessage(chatUserId)

        webSocketManager.sendEdit(
            toUserId = chatUserId,
            clientMsgId = clientMsgId,
            newText = finalPayload,
            editedAt = nowSec
        )
    }

    suspend fun deleteChatMessages(chatUserId: Long) {
        messageDao.deleteChatMessages(chatUserId)
    }

    suspend fun deleteMessage(localId: String, chatUserId: Long) {
        messageDao.deleteMessageByServerOrLocalId(localId, localId.toLongOrNull())
        updateChatLastMessage(chatUserId)
    }

    suspend fun deleteMessageByServerOrLocalId(msgIdStr: String, chatUserId: Long) {
        val serverId = msgIdStr.toLongOrNull()
        messageDao.deleteMessageByServerOrLocalId(msgIdStr, serverId)
        updateChatLastMessage(chatUserId)
    }

    suspend fun handleMsgStatusBatch(event: WebSocketEvent.MsgStatusBatch) {
        event.statuses.forEach { item ->
            if (item.clientMsgId.isNotBlank()) {
                if (item.delivered) {
                    messageDao.markDeliveredByClientId(item.clientMsgId, item.deliveredAt ?: System.currentTimeMillis())
                }
                if (item.read) {
                    messageDao.markReadByClientId(item.clientMsgId)
                }
            }
            if (item.msgId != 0L) {
                if (item.delivered) {
                    messageDao.markDelivered(item.msgId, item.deliveredAt)
                }
                if (item.read) {
                    messageDao.markRead(item.msgId)
                }
            }
        }
    }

    suspend fun updateChatLastMessage(chatUserId: Long) {
        val lastMsg = messageDao.getLastMessageForChat(chatUserId)
        if (lastMsg != null) {
            chatRepository.updateLastMessage(chatUserId, lastMsg.text, lastMsg.timestamp)
        } else {
            chatRepository.updateLastMessage(chatUserId, "", 0)
        }
    }

    suspend fun findMessageByLocalId(localId: String): MessageEntity? {
        return messageDao.findMessageByLocalId(localId)
    }

    suspend fun updateMessageText(clientMsgId: String, serverId: Long?, newText: String, editedAt: Long = 0L) {
        messageDao.updateMessageText(clientMsgId, serverId, newText, editedAt)
    }

    suspend fun handleMsgRetryReq(msgId: Long, requesterDeviceId: Long) {
    }
}

data class DecryptedOfflineMsg(
    val chatUserId: Long,
    val text: String,
    val ts: Long,
    val isIncoming: Boolean,
    val msgId: Long
)

private data class HistoryMsgDecrypted(
    val chatUserId: Long,
    val text: String,
    val senderId: Long,
    val createdAt: Long
)

fun toMs(timestamp: Long): Long {
    return if (timestamp in 1L..9_999_999_999L) timestamp * 1000L else timestamp
}
