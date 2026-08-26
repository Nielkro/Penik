package niel.kro.penik.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.dao.GroupDao
import niel.kro.penik.data.local.entity.MessageEntity
import niel.kro.penik.data.local.entity.GroupEntity
import niel.kro.penik.data.local.entity.GroupMemberEntity
import niel.kro.penik.data.local.entity.GroupKeyEntity
import niel.kro.penik.data.local.entity.GroupMessageEntity
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.data.crypto.E2EECrypto
import niel.kro.penik.data.crypto.IdentityPinStore
import niel.kro.penik.data.network.websocket.E2EDevicePayload
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
    private val e2eeCrypto: E2EECrypto,
    private val identityPins: IdentityPinStore,
) {
    private val bundleCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, List<niel.kro.penik.data.network.api.DeviceBundle>>>()

    suspend fun getKeyBundleCached(userId: Long, isSelf: Boolean = false, forceRefresh: Boolean = false): List<niel.kro.penik.data.network.api.DeviceBundle> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = bundleCache[userId]
            if (cached != null && cached.first > now) {
                return cached.second
            }
        }
        val devices: List<niel.kro.penik.data.network.api.DeviceBundle> = try {
            val response = if (isSelf) apiService.getKeyBundleSelf(userId) else apiService.getKeyBundle(userId)
            if (response.isSuccessful) response.body()?.devices ?: emptyList() else emptyList()
        } catch (e: Exception) {
            Log.e("PenikMsg", "Failed to fetch key bundle for $userId", e)
            bundleCache[userId]?.second ?: emptyList()
        }
        if (devices.isNotEmpty()) {
            bundleCache[userId] = Pair(now + 2 * 60 * 1000L, devices)
        }
        return devices
    }

    suspend fun exportPairingHistory(secret: ByteArray): String {
        val myId = tokenStorage.getUserId()
        val messages = messageDao.getAllMessages().map { message ->
            buildJsonObject {
                message.serverId?.let { put("msg_id", it) } ?: put("msg_id", message.localId)
                put("chat_id", message.chatUserId)
                put("chat_user_id", message.chatUserId)
                put("sender_id", message.senderId)
                put("recipient_id", if (message.senderId == myId) message.chatUserId else myId)
                put("text", message.text)
                put("created_at", message.timestamp)
                put("delivered", message.delivered)
                put("delivered_at", message.deliveredAt ?: 0L)
                put("read", message.read)
                message.serverId?.let { put("server_id", it) }
                message.localId.takeIf { it.isNotBlank() }?.let { put("client_msg_id", it) }
            }
        }
        val contacts = chatRepository.getAllChats().first().map { chat ->
            buildJsonObject {
                put("user_id", chat.userId)
                put("nickname", chat.nickname)
                put("name", chat.name)
                chat.avatarUrl?.let { put("avatar_url", it) }
            }
        }
        val groups = groupDao.getAllGroups().map { group ->
            buildJsonObject {
                put("id", group.id)
                put("name", group.name)
                put("owner_user_id", group.ownerUserId)
                group.role?.let { put("role", it) }
                put("status", group.status)
                put("membership_version", group.membershipVersion)
                put("current_key_version", group.currentKeyVersion)
                put("created_at", group.createdAt)
            }
        }
        val members = groupDao.getAllMembers().map { member ->
            buildJsonObject {
                put("group_id", member.groupId)
                put("user_id", member.userId)
                put("role", member.role)
                put("status", member.status)
                put("joined_at", member.joinedAt)
                put("name", member.name)
                put("nickname", member.nickname)
                put("online", member.online)
                put("last_seen", member.lastSeen)
            }
        }
        val keys = groupDao.getAllKeys().map { key ->
            buildJsonObject {
                put("group_id", key.groupId)
                put("key_version", key.keyVersion)
                put("key", encodeUrlBase64(key.key))
            }
        }
        val groupMessages = groupDao.getAllMessages().map { message ->
            buildJsonObject {
                put("group_id", message.groupId)
                put("message_id", message.messageId)
                put("server_id", message.serverId)
                put("sender_user_id", message.senderUserId)
                put("sender_device_id", message.senderDeviceId)
                put("key_version", message.keyVersion)
                put("text", message.text)
                put("created_at", message.createdAt)
                put("sent_by_me", message.sentByMe)
                put("delivered", message.delivered)
            }
        }
        val payload = buildJsonObject {
            put("version", 2)
            putJsonArray("messages") { messages.forEach { add(it) } }
            putJsonArray("contacts") { contacts.forEach { add(it) } }
            putJsonArray("groups") { groups.forEach { add(it) } }
            putJsonArray("group_members") { members.forEach { add(it) } }
            putJsonArray("group_keys") { keys.forEach { add(it) } }
            putJsonArray("group_messages") { groupMessages.forEach { add(it) } }
        }
        val encrypted = e2eeCrypto.encrypt(
            payload.toString().toByteArray(Charsets.UTF_8), secret, "penik-pairing-history-v1"
        )
        val envelope = buildJsonObject {
            put("ciphertext", encodeUrlBase64(encrypted.ciphertext))
            put("salt", encodeUrlBase64(encrypted.salt))
            put("nonce", encodeUrlBase64(encrypted.nonce))
        }
        return encodeUrlBase64(envelope.toString().toByteArray(Charsets.UTF_8))
    }

    private fun encodeUrlBase64(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    suspend fun importPairingHistory(encoded: String, secret: ByteArray) {
        val raw = decodeUrlBase64(encoded)
        val envelope = kotlinx.serialization.json.Json.parseToJsonElement(String(raw)).jsonObject
        val decoded = e2eeCrypto.decrypt(
            decodeUrlBase64(envelope["ciphertext"]!!.jsonPrimitive.content), secret,
            decodeUrlBase64(envelope["salt"]!!.jsonPrimitive.content),
            decodeUrlBase64(envelope["nonce"]!!.jsonPrimitive.content),
            "penik-pairing-history-v1"
        )
        val decodedJson = kotlinx.serialization.json.Json.parseToJsonElement(String(decoded)).jsonObject
        
        // 1. Import 1:1 messages
        val messages = decodedJson["messages"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        val imported = messages.mapNotNull {
            val o = it.jsonObject
            val chatId = o["chat_id"]!!.jsonPrimitive.content.toLong()
            val senderId = o["sender_id"]!!.jsonPrimitive.content.toLong()
            val timestamp = o["created_at"]!!.jsonPrimitive.content.toLong()
            val text = (o["text"] ?: o["plaintext"])!!.jsonPrimitive.content
            val serverId = (o["server_id"] ?: o["msg_id"])?.jsonPrimitive?.content?.toLongOrNull()
            
            // Delete any existing undecrypted message in this chat at this timestamp
            messageDao.deleteUndecryptedMessagesAt(chatId, serverId, timestamp)
            
            if (messageDao.findMatchingMessage(chatId, senderId, timestamp, text) != null) return@mapNotNull null
            
            MessageEntity(
                localId = o["msg_id"]!!.jsonPrimitive.content,
                serverId = serverId,
                chatUserId = chatId,
                senderId = senderId,
                text = text,
                timestamp = timestamp,
                sentByMe = o["sender_id"]!!.jsonPrimitive.content.toLong() == tokenStorage.getUserId(),
                delivered = o["delivered"]?.asBoolean() ?: false,
                read = o["read"]?.asBoolean() ?: false
            )
        }
        messageDao.insertMessages(imported)

        // 1.5 Import Contacts (Chats)
        val contacts = decodedJson["contacts"]?.jsonArray
        contacts?.forEach {
            val o = it.jsonObject
            val userId = o["user_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["userId"]!!.jsonPrimitive.content.toLong()
            val nickname = o["nickname"]?.jsonPrimitive?.content ?: ""
            val name = o["name"]?.jsonPrimitive?.content ?: ""
            val avatarUrl = o["avatarUrl"]?.jsonPrimitive?.content ?: o["avatar_url"]?.jsonPrimitive?.content
            
            chatRepository.getOrCreateChat(userId, nickname, name, avatarUrl)
            
            val lastMsg = o["last_message"]?.jsonPrimitive?.content ?: o["lastMessage"]?.jsonPrimitive?.content
            val lastTs = o["last_ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["lastMessageTimestamp"]?.jsonPrimitive?.content?.toLongOrNull()
            if (lastMsg != null && lastTs != null) {
                chatRepository.updateLastMessage(userId, lastMsg, lastTs, name, nickname)
            }
        }

        // 2. Import Groups
        val groups = decodedJson["groups"]?.jsonArray
        val importedGroups = groups?.map {
            val o = it.jsonObject
            GroupEntity(
                id = o["id"]!!.jsonPrimitive.content.toLong(),
                name = o["name"]!!.jsonPrimitive.content,
                ownerUserId = o["ownerUserId"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["owner_user_id"]!!.jsonPrimitive.content.toLong(),
                role = o["role"]?.jsonPrimitive?.content,
                status = o["status"]?.jsonPrimitive?.content ?: "active",
                membershipVersion = o["membershipVersion"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["membership_version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1L,
                currentKeyVersion = o["currentKeyVersion"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["current_key_version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1L,
                createdAt = o["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["created_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            )
        }.orEmpty()
        for (g in importedGroups) {
            groupDao.upsertGroup(g)
        }

        // 3. Import Group Members
        val groupMembers = decodedJson["group_members"]?.jsonArray
        val importedMembers = groupMembers?.map {
            val o = it.jsonObject
            GroupMemberEntity(
                groupId = o["groupId"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["group_id"]!!.jsonPrimitive.content.toLong(),
                userId = o["userId"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["user_id"]!!.jsonPrimitive.content.toLong(),
                role = o["role"]!!.jsonPrimitive.content,
                status = o["status"]!!.jsonPrimitive.content,
                joinedAt = o["joinedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["joined_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                name = o["name"]?.jsonPrimitive?.content ?: "",
                nickname = o["nickname"]?.jsonPrimitive?.content ?: ""
            )
        }.orEmpty()
        if (importedMembers.isNotEmpty()) {
            groupDao.insertMembers(importedMembers)
        }

        // 4. Import Group Keys
        val groupKeys = decodedJson["group_keys"]?.jsonArray
        val importedKeys = groupKeys?.map {
            val o = it.jsonObject
            GroupKeyEntity(
                groupId = o["group_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["groupId"]!!.jsonPrimitive.content.toLong(),
                keyVersion = o["key_version"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["keyVersion"]!!.jsonPrimitive.content.toLong(),
                key = decodeUrlBase64(o["key"]!!.jsonPrimitive.content)
            )
        }.orEmpty()
        for (k in importedKeys) {
            groupDao.saveGroupKey(k)
        }

        // 5. Import Group Messages
        val groupMessages = decodedJson["group_messages"]?.jsonArray
        val importedGroupMessages = groupMessages?.map {
            val o = it.jsonObject
            val text = (o["text"] ?: o["plaintext"])!!.jsonPrimitive.content
            GroupMessageEntity(
                groupId = o["group_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["groupId"]!!.jsonPrimitive.content.toLong(),
                messageId = (o["message_id"] ?: o["messageId"])!!.jsonPrimitive.content,
                serverId = o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["serverId"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                senderUserId = o["sender_user_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["senderUserId"]!!.jsonPrimitive.content.toLong(),
                senderDeviceId = o["sender_device_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["senderDeviceId"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                keyVersion = o["key_version"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["keyVersion"]!!.jsonPrimitive.content.toLong(),
                text = text,
                createdAt = o["created_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["createdAt"]!!.jsonPrimitive.content.toLong(),
                sentByMe = (o["sender_user_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: o["senderUserId"]!!.jsonPrimitive.content.toLong()) == tokenStorage.getUserId(),
                delivered = o["delivered"]?.asBoolean() ?: false
            )
        }.orEmpty()
        for (gm in importedGroupMessages) {
            groupDao.upsertMessage(gm)
        }

        val importedChatIds = imported.map { it.chatUserId }.distinct()
        for (chatId in importedChatIds) {
            updateChatLastMessage(chatId)
        }
    }

    private fun decodeUrlBase64(value: String): ByteArray = java.util.Base64.getUrlDecoder().decode(
        value.trim().replace('+', '-').replace('/', '_').let { it + "=".repeat((4 - it.length % 4) % 4) }
    )

    private fun kotlinx.serialization.json.JsonElement.asBoolean(): Boolean = when {
        jsonPrimitive.isString -> jsonPrimitive.content.toBooleanStrictOrNull() ?: false
        else -> jsonPrimitive.content == "1" || jsonPrimitive.content.equals("true", ignoreCase = true)
    }

    fun getMessagesForChat(chatUserId: Long): Flow<List<MessageEntity>> {
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

    suspend fun sendMessage(toUserId: Long, text: String, replyToMsgId: String? = null, existingClientMsgId: String? = null): String {
        val clientMsgId = existingClientMsgId ?: UUID.randomUUID().toString()
        val myId = tokenStorage.getUserId()
        val isSelfChat = toUserId == myId

        Log.d("PenikMsg", "sendMessage: clientMsgId=$clientMsgId, toUserId=$toUserId, isSelfChat=$isSelfChat, textLength=${text.length}")
        // Match web client reply logic: use parent's clientMsgId (UUID) or serverId
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
                replyToMsgId = resolvedReplyToMsgId
            )
            messageDao.insertMessage(entity)
        } else {
            messageDao.updateMessageText(clientMsgId, null, text, 0L)
        }

        val recipientBundles: List<niel.kro.penik.data.network.api.DeviceBundle> = getKeyBundleCached(toUserId, isSelf = isSelfChat)
        val senderBundles: List<niel.kro.penik.data.network.api.DeviceBundle> = if (isSelfChat) emptyList() else getKeyBundleCached(myId, isSelf = true)

        val myDeviceId = tokenStorage.getDeviceId()
        val allDevices: List<niel.kro.penik.data.network.api.DeviceBundle> = if (isSelfChat) {
            recipientBundles.filter { it.deviceId != myDeviceId }
        } else {
            (recipientBundles + senderBundles).filter { it.deviceId != myDeviceId }
        }

        Log.d("PenikMsg", "sendMessage: myDeviceId=$myDeviceId, encrypted for ${allDevices.size} target devices (excluding self)")

        val myPrivateIK = tokenStorage.getPrivateKey()
            ?: throw Exception("Private Identity Key not found. Please log in again.")

        // A key bundle does not name the device's owner, so it is recovered from
        // which bundle the device came out of; pins are per (user, device).
        val deviceOwners: Map<Long, Long> = buildMap {
            senderBundles.forEach { put(it.deviceId, myId) }
            recipientBundles.forEach { put(it.deviceId, toUserId) }
        }

        val nowSec = System.currentTimeMillis() / 1000
        val aad = e2eeCrypto.buildPairwiseAad(myId, toUserId, clientMsgId, nowSec)

        val payloads = allDevices.map { device ->
            val recipientIKPub = java.util.Base64.getDecoder().decode(device.identityKey)
            val targetUserId = deviceOwners[device.deviceId] ?: toUserId
            val pinResult = identityPins.verify(targetUserId, device.deviceId, recipientIKPub)
            if (pinResult == niel.kro.penik.data.crypto.IdentityPinStore.Result.UPDATED) {
                val sysEntity = niel.kro.penik.data.local.entity.MessageEntity(
                    localId = "sys-keychange-${System.currentTimeMillis()}-$targetUserId",
                    chatUserId = targetUserId,
                    senderId = 0,
                    text = "⚠️ Код безопасности изменился!",
                    timestamp = System.currentTimeMillis() - 1,
                    sentByMe = false,
                    delivered = true
                )
                messageDao.insertMessage(sysEntity)
            }
            
            val secret = e2eeCrypto.deriveSharedSecret(myPrivateIK, recipientIKPub)

            val encrypted = e2eeCrypto.encrypt(text.toByteArray(Charsets.UTF_8), secret, aad = aad)

            E2EDevicePayload(
                deviceId = device.deviceId,
                ciphertext = encrypted.ciphertext,
                salt = encrypted.salt,
                nonce = encrypted.nonce
            )
        }

        webSocketManager.sendEncryptedMessage(toUserId, clientMsgId, payloads, resolvedReplyToMsgId, createdAt = nowSec)
        return clientMsgId
    }

    suspend fun handleMsgAck(event: WebSocketEvent.MsgAck) {
        Log.d("PenikMsg", "handleMsgAck: clientMsgId=${event.clientMsgId} -> serverMsgId=${event.serverMsgId}")
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
        val isSelfChat = sentByMe && event.chatUserId == myId

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
        if (existing != null) {
            val text = existing.text
            val isFailed = text.startsWith("[Ошибка") || text.startsWith("[Сообщение не расшифровано")
            if (!isFailed) {
                if (!sentByMe) {
                    webSocketManager.sendDelivered(event.msgId)
                }
                return Pair(text, !sentByMe)
            }
        }

        val pinResult = identityPins.verify(event.fromUserId, event.fromDeviceId, event.fromIdentityKey)
        if (pinResult == niel.kro.penik.data.crypto.IdentityPinStore.Result.UPDATED) {
            val sysEntity = niel.kro.penik.data.local.entity.MessageEntity(
                localId = "sys-keychange-${System.currentTimeMillis()}-${event.fromUserId}",
                chatUserId = event.chatUserId,
                senderId = 0,
                text = "⚠️ Код безопасности изменился!",
                timestamp = toMs(event.ts) - 1,
                sentByMe = false,
                delivered = true
            )
            messageDao.insertMessage(sysEntity)
        }

        var decryptSuccess = true
        val decryptedText = try {
            decryptMessagePayload(
                myDeviceId = tokenStorage.getDeviceId(),
                fromIdentityKey = event.fromIdentityKey,
                ciphertext = event.ciphertext,
                salt = event.salt,
                nonce = event.nonce,
                senderUserId = event.fromUserId,
                recipientUserId = if (sentByMe) event.chatUserId else myId,
                clientMsgId = event.clientMsgId ?: "",
                timestamp = event.ts
            )
        } catch (e: Exception) {
            decryptSuccess = false
            if (isSelfChat) {
                return Pair("", false)
            }
            "[Ошибка расшифрования сообщения: ${e.message}]"
        }

        if (existing != null) {
            if (decryptSuccess) {
                val updated = existing.copy(text = decryptedText)
                messageDao.insertMessage(updated)
            }
            if (!sentByMe) {
                webSocketManager.sendDelivered(event.msgId)
            }
            return Pair(decryptedText, !sentByMe)
        }

        val entity = MessageEntity(
            localId = if (!event.clientMsgId.isNullOrBlank()) event.clientMsgId else "server-${event.msgId}",
            serverId = event.msgId,
            chatUserId = event.chatUserId,
            senderId = event.fromUserId,
            text = decryptedText,
            timestamp = toMs(event.ts),
            sentByMe = sentByMe,
            delivered = true,
            replyToMsgId = event.replyToMsgId
        )
        messageDao.insertMessage(entity)
        if (!sentByMe) {
            webSocketManager.sendDelivered(event.msgId)
        }
        return Pair(decryptedText, !sentByMe)
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
        val successMsgIds = mutableListOf<Long>()
        val entities = buildList {
            event.msgs.forEach { msg ->
                val isSelfChat = msg.fromUserId == myId && msg.chatUserId == myId
                val existing = messageDao.findMessageByServerId(msg.msgId)
                if (existing == null) {
                    var decryptSuccess = true
                    val decryptedText = try {
                        decryptMessagePayload(
                            myDeviceId = tokenStorage.getDeviceId(),
                            fromIdentityKey = msg.fromIdentityKey,
                            ciphertext = msg.ciphertext,
                            salt = msg.salt,
                            nonce = msg.nonce,
                            senderUserId = msg.fromUserId,
                            recipientUserId = if (msg.fromUserId == myId) msg.chatUserId else myId,
                            clientMsgId = msg.clientMsgId ?: "",
                            timestamp = msg.ts
                        )
                    } catch (e: Exception) {
                        val fallback = tryFallbackDecrypt(msg, myId)
                        if (fallback != null) {
                            fallback
                        } else {
                            decryptSuccess = false
                            if (isSelfChat) {
                                // Encrypted for another device of ours — skip silently.
                                return@forEach
                            }
                            "[Ошибка расшифрования сообщения: ${e.message}]"
                        }
                    }
                    if (decryptSuccess) {
                        successMsgIds.add(msg.msgId)
                    }
                    // Only expose self-chat messages that we successfully decrypted.
                    if (!isSelfChat) {
                        decryptedList.add(DecryptedOfflineMsg(
                            chatUserId = msg.chatUserId,
                            text = decryptedText,
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
                        text = decryptedText,
                        timestamp = toMs(msg.ts),
                        sentByMe = msg.fromUserId == myId,
                        delivered = true,
                        replyToMsgId = msg.replyToMsgId
                    ))
                } else {
                    var text = existing.text
                    val isFailed = text.startsWith("[Ошибка") || text.startsWith("[Сообщение не расшифровано")
                    if (isFailed) {
                        try {
                            val recoveredText = try {
                                decryptMessagePayload(
                                    myDeviceId = tokenStorage.getDeviceId(),
                                    fromIdentityKey = msg.fromIdentityKey,
                                    ciphertext = msg.ciphertext,
                                    salt = msg.salt,
                                    nonce = msg.nonce,
                                    senderUserId = msg.fromUserId,
                                    recipientUserId = if (msg.fromUserId == myId) msg.chatUserId else myId,
                                    clientMsgId = msg.clientMsgId ?: "",
                                    timestamp = msg.ts
                                )
                            } catch (_: Exception) {
                                tryFallbackDecrypt(msg, myId) ?: throw Exception("Fallback decryption failed")
                            }
                            messageDao.insertMessage(existing.copy(text = recoveredText))
                            text = recoveredText
                        } catch (_: Exception) {}
                    }
                    if (!text.startsWith("[Ошибка") && !text.startsWith("[Сообщение не расшифровано") && !isSelfChat) {
                        decryptedList.add(DecryptedOfflineMsg(
                            chatUserId = msg.chatUserId,
                            text = text,
                            ts = msg.ts,
                            isIncoming = msg.fromUserId != myId,
                            msgId = msg.msgId
                        ))
                    }
                }
            }
        }
        messageDao.insertMessages(entities)
        // Send delivery receipts only for messages from other users.
        event.msgs.forEach { msg ->
            if (msg.fromUserId != myId) {
                webSocketManager.sendDelivered(msg.msgId)
            }
        }
        return decryptedList
    }

    suspend fun syncHistory(chatUserId: Long? = null, beforeId: Long? = null, limit: Int = 500) {
        try {
            val maxServerId = if (chatUserId == null && beforeId == null) {
                messageDao.getAllMessages().mapNotNull { it.serverId }.maxOrNull()
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
                val bundleCache = mutableMapOf<Long, niel.kro.penik.data.network.api.KeyBundleResponse?>()
                Log.d("PenikMsg", "syncHistory: received ${messages.size} history items from server (afterId=$maxServerId, beforeId=$beforeId, chatUserId=$chatUserId)")
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
                        Log.d("PenikMsg", "syncHistory item: msgId=${msg.msgId}, clientMsgId=${msg.clientMsgId}, senderId=${msg.senderId}, senderDeviceId=${msg.senderDeviceId}, existingLocalId=${existing?.localId}")
                        val isEdited = msg.editedAt != null && (existing == null || existing.editedAt == null || (msg.editedAt * 1000 > (existing.editedAt ?: 0L)))
                        if (existing == null || isEdited) {
                            var isDecryptFailed = false
                            val text = if (msg.plaintext != null) {
                                msg.plaintext
                            } else if (msg.ciphertext != null && msg.encryptionSalt != null && msg.encryptionNonce != null) {
                                try {
                                    val ciphertextBytes = java.util.Base64.getDecoder().decode(msg.ciphertext)
                                    val saltBytes = java.util.Base64.getDecoder().decode(msg.encryptionSalt)
                                    val nonceBytes = java.util.Base64.getDecoder().decode(msg.encryptionNonce)
                                    
                                    val senderBundle = bundleCache.getOrPut(msg.senderId) {
                                        apiService.getKeyBundle(msg.senderId).body()
                                    }
                                    val senderDevice = senderBundle?.devices?.find { it.deviceId == msg.senderDeviceId }
                                    val senderIK = java.util.Base64.getDecoder().decode(senderDevice?.identityKey ?: "")
                                    
                                    val decryptTs = msg.editedAt ?: msg.createdAt
                                    decryptMessagePayload(
                                        myDeviceId = tokenStorage.getDeviceId(),
                                        fromIdentityKey = senderIK,
                                        ciphertext = ciphertextBytes,
                                        salt = saltBytes,
                                        nonce = nonceBytes,
                                        senderUserId = msg.senderId,
                                        recipientUserId = if (msg.senderId == myId) msg.chatUserId else myId,
                                        clientMsgId = msg.clientMsgId ?: "",
                                        timestamp = decryptTs
                                    )
                                } catch (e: Exception) {
                                    Log.e("PenikMsg", "FAILED TO DECRYPT HISTORY MSG msgId=${msg.msgId}, senderId=${msg.senderId}, senderDeviceId=${msg.senderDeviceId}, clientMsgId=${msg.clientMsgId}", e)
                                    isDecryptFailed = true
                                    existing?.text ?: ""
                                }
                            } else {
                                existing?.text ?: ""
                            }
                            
                            val editedAtMs = msg.editedAt?.let { it * 1000 }
                            if (existing != null) {
                                if (editedAtMs != null && !isDecryptFailed && text.isNotBlank()) {
                                    messageDao.updateMessageText(existing.localId, msg.msgId, text, editedAtMs)
                                }
                            } else if (!isDecryptFailed && text.isNotBlank()) {
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
                                    editedAt = editedAtMs
                                ))
                            }
                        }
                        if (existing != null) {
                            // Update existing message status if changed
                            if (existing.delivered != (msg.delivered == 1) || existing.read != (msg.read == 1)) {
                                messageDao.updateStatus(
                                    serverId = msg.msgId,
                                    delivered = msg.delivered == 1,
                                    read = msg.read == 1,
                                    deliveredAt = msg.deliveredAt ?: System.currentTimeMillis()
                                )
                            }
                            val text = existing.text
                            val isFailed = text.startsWith("[Ошибка") || text.startsWith("[Сообщение не расшифровано")
                            if (!isFailed && text.isNotBlank()) {
                                newMessages.add(HistoryMsgDecrypted(msg.chatUserId, text, msg.senderId, msg.createdAt * 1000))
                            }
                        }
                    }
                }
                messageDao.insertMessages(entities)

                // WebSocket delivery notifications can be missed while Android is
                // offline. Reconcile sent-message state from the REST endpoint.
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

                // Ensure all contacts exist in chat list
                val allChatUserIds = messages.map { it.chatUserId }.distinct()
                for (peerId in allChatUserIds) {
                    val profile = try {
                        apiService.getUserProfile(peerId).body()
                    } catch (_: Exception) {
                        null
                    }
                    chatRepository.upsertContact(
                        userId = peerId,
                        nickname = profile?.nickname.orEmpty(),
                        name = profile?.name.orEmpty(),
                        avatarUrl = null
                    )
                }

                newMessages.groupBy { it.chatUserId }.forEach { (chatUserId, chatMessages) ->
                    val latest = chatMessages.maxByOrNull { it.createdAt }
                    if (latest != null && !latest.text.startsWith("[Ошибка") && !latest.text.startsWith("[Сообщение не расшифровано")) {
                        val profile = try {
                            apiService.getUserProfile(chatUserId).body()
                        } catch (_: Exception) {
                            null
                        }
                        chatRepository.updateLastMessage(
                            userId = chatUserId,
                            text = latest.text,
                            timestamp = latest.createdAt,
                            name = profile?.name.orEmpty(),
                            nickname = profile?.nickname.orEmpty()
                        )
                    }
                }

                // Recalculate unread counts strictly from actual unread incoming messages in DB
                val allEntities = messageDao.getAllMessages()
                allEntities.groupBy { it.chatUserId }.forEach { (chatUserId, msgs) ->
                    val unreadCount = msgs.count { !it.sentByMe && !it.read && it.text != "[DELETED]" }
                    chatRepository.updateUnreadCount(chatUserId, unreadCount)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "Failed to sync history", e)
        }
    }

    private fun decryptMessagePayload(
        myDeviceId: Long,
        fromIdentityKey: ByteArray,
        ciphertext: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
        senderUserId: Long = 0L,
        recipientUserId: Long = 0L,
        clientMsgId: String = "",
        timestamp: Long = 0L
    ): String {
        val myPrivateIK = tokenStorage.getPrivateKey()
            ?: throw Exception("Identity Key private key not found locally")
        val secret = e2eeCrypto.deriveSharedSecret(myPrivateIK, fromIdentityKey)

        val tsSec = if (timestamp > 100_000_000_000L) timestamp / 1000 else timestamp

        val candidates = buildList {
            if (senderUserId != 0L || recipientUserId != 0L) {
                val offsets = listOf(0L, -1L, 1L, -2L, 2L, -3L, 3L, -4L, 4L, -5L, 5L)
                for (offset in offsets) {
                    add(e2eeCrypto.buildPairwiseAad(senderUserId, recipientUserId, clientMsgId, tsSec + offset))
                }
                if (timestamp > 100_000_000_000L) {
                    add(e2eeCrypto.buildPairwiseAad(senderUserId, recipientUserId, clientMsgId, timestamp))
                }
                if (clientMsgId.isNotEmpty()) {
                    for (offset in listOf(0L, -1L, 1L)) {
                        add(e2eeCrypto.buildPairwiseAad(senderUserId, recipientUserId, "", tsSec + offset))
                    }
                }
                if (recipientUserId != 0L && senderUserId != 0L && recipientUserId != senderUserId) {
                    for (offset in listOf(0L, -1L, 1L)) {
                        add(e2eeCrypto.buildPairwiseAad(recipientUserId, senderUserId, clientMsgId, tsSec + offset))
                        if (clientMsgId.isNotEmpty()) {
                            add(e2eeCrypto.buildPairwiseAad(recipientUserId, senderUserId, "", tsSec + offset))
                        }
                    }
                }
            }
            add(null) // Fallback for legacy messages or empty AAD
        }

        var lastEx: Exception? = null
        for (candidate in candidates) {
            try {
                val plaintextBytes = e2eeCrypto.decrypt(ciphertext, secret, salt, nonce, aad = candidate)
                return String(plaintextBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                lastEx = e
            }
        }

        Log.e(
            "PenikE2EE",
            "DECRYPTION FAILED: senderUserId=$senderUserId, recipientUserId=$recipientUserId, clientMsgId=$clientMsgId, tsSec=$tsSec, " +
            "fromIK=${android.util.Base64.encodeToString(fromIdentityKey, android.util.Base64.NO_WRAP)}, " +
            "ctLen=${ciphertext.size}, saltLen=${salt.size}, nonceLen=${nonce.size}, candidatesTried=${candidates.size}",
            lastEx
        )

        throw lastEx ?: Exception("Decryption failed")
    }

    private suspend fun tryFallbackDecrypt(msg: WebSocketEvent.MsgRecvEncrypted, myId: Long): String? {
        val bundle = runCatching { apiService.getKeyBundle(msg.fromUserId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body() ?: return null

        val myDeviceId = tokenStorage.getDeviceId()
        val recipientUserId = if (msg.fromUserId == myId) msg.chatUserId else myId

        val targetDevices = if (msg.fromDeviceId > 0) {
            bundle.devices.filter { it.deviceId == msg.fromDeviceId } + bundle.devices.filter { it.deviceId != msg.fromDeviceId }
        } else {
            bundle.devices
        }

        for (device in targetDevices) {
            val ik = runCatching { android.util.Base64.decode(device.identityKey, android.util.Base64.DEFAULT) }.getOrNull() ?: continue
            identityPins.verify(msg.fromUserId, device.deviceId, ik)
            val text = runCatching {
                decryptMessagePayload(
                    myDeviceId = myDeviceId,
                    fromIdentityKey = ik,
                    ciphertext = msg.ciphertext,
                    salt = msg.salt,
                    nonce = msg.nonce,
                    senderUserId = msg.fromUserId,
                    recipientUserId = recipientUserId,
                    clientMsgId = msg.clientMsgId ?: "",
                    timestamp = msg.ts
                )
            }.getOrNull() ?: continue
            return text
        }
        return null
    }

    /**
     * Resolves a message referenced by a push notification.
     *
     * The push only carries the row id, so the envelope is fetched over REST,
     * decrypted with the sender's identity key and persisted like any incoming
     * message. Returns the plaintext for the notification body, or null when the
     * row is gone or cannot be decrypted on this device.
     */
    suspend fun resolvePushMessage(msgId: Long): String? {
        if (msgId <= 0L) return null
        messageDao.findMessageByServerId(msgId)?.let { return it.text }

        val body = runCatching { apiService.getMessageById(msgId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body() ?: return null

        body.plaintext?.takeIf { it.isNotBlank() }?.let { plain ->
            persistPushMessage(body, plain)
            return plain
        }

        val ciphertext = body.ciphertext ?: return null
        val saltB64 = body.encryptionSalt ?: return null
        val nonceB64 = body.encryptionNonce ?: return null
        val ct = runCatching { android.util.Base64.decode(ciphertext, android.util.Base64.DEFAULT) }.getOrNull() ?: return null
        val salt = runCatching { android.util.Base64.decode(saltB64, android.util.Base64.DEFAULT) }.getOrNull() ?: return null
        val nonce = runCatching { android.util.Base64.decode(nonceB64, android.util.Base64.DEFAULT) }.getOrNull() ?: return null

        // The row records the sender device id but not its public key, so every
        // key in the sender's bundle is tried; only one can produce a valid tag.
        val bundle = runCatching { apiService.getKeyBundle(body.senderId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body() ?: return null

        val myId = tokenStorage.getUserId()
        val myDeviceId = tokenStorage.getDeviceId()
        val recipientUserId = if (body.senderId == myId) body.chatUserId else myId

        val targetDevices = if (body.senderDeviceId != null && body.senderDeviceId > 0) {
            bundle.devices.filter { it.deviceId == body.senderDeviceId } + bundle.devices.filter { it.deviceId != body.senderDeviceId }
        } else {
            bundle.devices
        }

        for (device in targetDevices) {
            val ik = runCatching { android.util.Base64.decode(device.identityKey, android.util.Base64.DEFAULT) }.getOrNull() ?: continue
            identityPins.verify(body.senderId, device.deviceId, ik)
            val text = runCatching {
                decryptMessagePayload(
                    myDeviceId = myDeviceId,
                    fromIdentityKey = ik,
                    ciphertext = ct,
                    salt = salt,
                    nonce = nonce,
                    senderUserId = body.senderId,
                    recipientUserId = recipientUserId,
                    clientMsgId = body.clientMsgId ?: "",
                    timestamp = body.createdAt
                )
            }.getOrNull() ?: continue
            persistPushMessage(body, text)
            return text
        }
        return null
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
        val myId = tokenStorage.getUserId()
        val sentByMe = event.fromUserId == myId
        val myDeviceId = tokenStorage.getDeviceId()

        val decryptedText = try {
            decryptMessagePayload(
                myDeviceId = myDeviceId,
                fromIdentityKey = event.fromIdentityKey,
                ciphertext = event.ciphertext,
                salt = event.salt,
                nonce = event.nonce,
                senderUserId = event.fromUserId,
                recipientUserId = if (sentByMe) event.chatUserId else myId,
                clientMsgId = event.clientMsgId,
                timestamp = event.editedAt
            )
        } catch (e: Exception) {
            Log.e("PenikMsg", "Failed to decrypt MsgEditNotify", e)
            return
        }

        val editedAtMs = toMs(event.editedAt)
        messageDao.updateMessageText(
            clientMsgId = event.clientMsgId,
            serverId = event.msgId,
            newText = decryptedText,
            editedAt = editedAtMs
        )
        updateChatLastMessage(event.chatUserId)
    }

    suspend fun editMessage(chatUserId: Long, clientMsgId: String, newText: String) {
        val myId = tokenStorage.getUserId()
        val nowSec = System.currentTimeMillis() / 1000
        val editedAtMs = nowSec * 1000

        // 1. Update Room DB immediately
        messageDao.updateMessageText(
            clientMsgId = clientMsgId,
            serverId = clientMsgId.toLongOrNull(),
            newText = newText,
            editedAt = editedAtMs
        )
        updateChatLastMessage(chatUserId)

        // 2. Fetch peer + self devices & encrypt
        val myDeviceId = tokenStorage.getDeviceId()
        var peerDevices = getKeyBundleCached(chatUserId)
        if (peerDevices.isEmpty()) {
            peerDevices = getKeyBundleCached(chatUserId, forceRefresh = true)
        }
        val selfDevices = getKeyBundleCached(myId, isSelf = true)
        val allDevices = if (chatUserId == myId) {
            peerDevices.filter { it.deviceId != myDeviceId }
        } else {
            (peerDevices + selfDevices).filter { it.deviceId != myDeviceId }
        }

        val myPrivateIK = tokenStorage.getPrivateKey() ?: return
        val encryptedDevices = allDevices.mapNotNull { dev ->
            try {
                val peerIK = java.util.Base64.getDecoder().decode(dev.identityKey)
                val targetUserId = if (peerDevices.any { it.deviceId == dev.deviceId }) chatUserId else myId
                val pinResult = identityPins.verify(targetUserId, dev.deviceId, peerIK)
                val secret = e2eeCrypto.deriveSharedSecret(myPrivateIK, peerIK)
                val aad = e2eeCrypto.buildPairwiseAad(myId, chatUserId, clientMsgId, nowSec)
                val enc = e2eeCrypto.encrypt(newText.toByteArray(Charsets.UTF_8), secret, aad = aad)
                E2EDevicePayload(dev.deviceId, enc.ciphertext, enc.salt, enc.nonce)
            } catch (e: Exception) {
                Log.e("PenikMsg", "Failed to encrypt edit for device ${dev.deviceId}", e)
                null
            }
        }

        if (encryptedDevices.isNotEmpty()) {
            webSocketManager.sendEncryptedEdit(
                toUserId = chatUserId,
                clientMsgId = clientMsgId,
                devices = encryptedDevices,
                editedAt = nowSec
            )
        }
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
