package niel.kro.penik.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
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
import niel.kro.penik.data.network.websocket.E2EDevicePayload
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
) {
    suspend fun importPairingHistory(encoded: String, secret: ByteArray) {
        val raw = decodeUrlBase64(encoded)
        val envelope = kotlinx.serialization.json.Json.parseToJsonElement(String(raw)).jsonObject
        val decoded = e2eeCrypto.decrypt(
            decodeUrlBase64(envelope["ciphertext"]!!.jsonPrimitive.content), secret,
            decodeUrlBase64(envelope["salt"]!!.jsonPrimitive.content),
            decodeUrlBase64(envelope["nonce"]!!.jsonPrimitive.content)
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

    suspend fun sendMessage(toUserId: Long, text: String): String {
        val clientMsgId = UUID.randomUUID().toString()
        val myId = tokenStorage.getUserId()
        val isSelfChat = toUserId == myId

        val entity = MessageEntity(
            localId = clientMsgId,
            chatUserId = toUserId,
            senderId = myId,
            text = text,
            timestamp = System.currentTimeMillis(),
            sentByMe = true,
            delivered = false
        )
        messageDao.insertMessage(entity)

        val recipientBundles = try {
            val response = if (isSelfChat) {
                apiService.getKeyBundleSelf(toUserId)
            } else {
                apiService.getKeyBundle(toUserId)
            }
            if (response.isSuccessful) response.body()?.devices ?: emptyList() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val senderBundles = try {
            val response = apiService.getKeyBundleSelf(myId)
            if (response.isSuccessful) response.body()?.devices ?: emptyList() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val myDeviceId = tokenStorage.getDeviceId()
        val allDevices = if (isSelfChat) {
            recipientBundles.filter { it.deviceId != myDeviceId }
        } else {
            (recipientBundles + senderBundles).filter { it.deviceId != myDeviceId }
        }

        val myPrivateIK = tokenStorage.getPrivateKey()
            ?: throw Exception("Private Identity Key not found. Please log in again.")

        val payloads = allDevices.map { device ->
            val recipientIKPub = java.util.Base64.getDecoder().decode(device.identityKey)
            
            val secret = e2eeCrypto.deriveSharedSecret(myPrivateIK, recipientIKPub)

            val encrypted = e2eeCrypto.encrypt(text.toByteArray(Charsets.UTF_8), secret)

            E2EDevicePayload(
                deviceId = device.deviceId,
                prekeyId = null,
                ciphertext = encrypted.ciphertext,
                salt = encrypted.salt,
                nonce = encrypted.nonce
            )
        }

        webSocketManager.sendEncryptedMessage(toUserId, clientMsgId, payloads)
        return clientMsgId
    }

    suspend fun handleMsgAck(event: WebSocketEvent.MsgAck) {
        messageDao.acknowledgeMessage(event.clientMsgId, event.serverMsgId)
    }

    suspend fun handleMsgDelivered(event: WebSocketEvent.MsgDelivered) {
        if (event.clientMsgId.isNotBlank()) messageDao.markDeliveredByClientId(event.clientMsgId)
        else messageDao.markDelivered(event.msgId)
    }

    suspend fun handleMsgRead(event: WebSocketEvent.MsgRead) {
        if (event.clientMsgId.isNotBlank()) messageDao.markReadByClientId(event.clientMsgId)
        else messageDao.markRead(event.msgId)
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
            timestamp = event.ts,
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
        val sentByMe = event.fromUserId == tokenStorage.getUserId()
        var decryptSuccess = true
        val decryptedText = try {
            decryptMessagePayload(
                myDeviceId = tokenStorage.getDeviceId(),
                fromIdentityKey = event.fromIdentityKey,
                prekeyId = event.prekeyId,
                ciphertext = event.ciphertext,
                salt = event.salt,
                nonce = event.nonce
            )
        } catch (e: Exception) {
            decryptSuccess = false
            "[Ошибка расшифрования сообщения: ${e.message}]"
        }

        if (messageDao.findLocalIdByServerId(event.msgId) != null) {
            if (!sentByMe && decryptSuccess) {
                webSocketManager.sendDelivered(event.msgId)
            }
            return Pair(decryptedText, !sentByMe)
        }

        val entity = MessageEntity(
            localId = "server-${event.msgId}",
            serverId = event.msgId,
            chatUserId = event.chatUserId,
            senderId = event.fromUserId,
            text = decryptedText,
            timestamp = event.ts,
            sentByMe = sentByMe,
            delivered = true
        )
        messageDao.insertMessage(entity)
        if (!sentByMe && decryptSuccess) {
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
                        timestamp = msg.ts,
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
                if (messageDao.findLocalIdByServerId(msg.msgId) == null) {
                    var decryptSuccess = true
                    val decryptedText = try {
                        decryptMessagePayload(
                            myDeviceId = tokenStorage.getDeviceId(),
                            fromIdentityKey = msg.fromIdentityKey,
                            prekeyId = msg.prekeyId,
                            ciphertext = msg.ciphertext,
                            salt = msg.salt,
                            nonce = msg.nonce
                        )
                    } catch (e: Exception) {
                        decryptSuccess = false
                        "[Ошибка расшифрования сообщения: ${e.message}]"
                    }
                    if (decryptSuccess) {
                        successMsgIds.add(msg.msgId)
                    }
                    decryptedList.add(DecryptedOfflineMsg(msg.chatUserId, decryptedText, msg.ts))
                    add(MessageEntity(
                        localId = "server-${msg.msgId}",
                        serverId = msg.msgId,
                        chatUserId = msg.chatUserId,
                        senderId = msg.fromUserId,
                        text = decryptedText,
                        timestamp = msg.ts,
                        sentByMe = msg.fromUserId == myId,
                        delivered = true
                    ))
                }
            }
        }
        messageDao.insertMessages(entities)
        successMsgIds.forEach { webSocketManager.sendDelivered(it) }
        return decryptedList
    }

    suspend fun syncHistory() {
        val response = apiService.getMessageHistory(limit = 100)
        if (response.isSuccessful) {
            val messages = response.body() ?: emptyList()
            val myId = tokenStorage.getUserId()
            val newMessages = mutableListOf<HistoryMsgDecrypted>()
            val entities = buildList {
                messages.forEach { msg ->
                    if (msg.senderId == myId && msg.clientMsgId != null) {
                        messageDao.acknowledgeMessage(msg.clientMsgId, msg.msgId)
                    }
                    if (messageDao.findLocalIdByServerId(msg.msgId) == null) {
                        val text = if (msg.plaintext != null) {
                            msg.plaintext
                        } else if (msg.ciphertext != null && msg.encryptionSalt != null && msg.encryptionNonce != null) {
                            try {
                                val ciphertextBytes = java.util.Base64.getDecoder().decode(msg.ciphertext)
                                val saltBytes = java.util.Base64.getDecoder().decode(msg.encryptionSalt)
                                val nonceBytes = java.util.Base64.getDecoder().decode(msg.encryptionNonce)
                                
                                val senderBundle = apiService.getKeyBundle(msg.senderId).body()
                                val senderDevice = senderBundle?.devices?.find { it.deviceId == msg.senderDeviceId }
                                val senderIK = java.util.Base64.getDecoder().decode(senderDevice?.identityKey ?: "")
                                
                                decryptMessagePayload(
                                    myDeviceId = tokenStorage.getDeviceId(),
                                    fromIdentityKey = senderIK,
                                    prekeyId = msg.prekeyId,
                                    ciphertext = ciphertextBytes,
                                    salt = saltBytes,
                                    nonce = nonceBytes
                                )
                            } catch (e: Exception) {
                                "[Ошибка расшифрования: ${e.message}]"
                            }
                        } else {
                            ""
                        }
                        
                        newMessages.add(HistoryMsgDecrypted(msg.chatUserId, text, msg.senderId, msg.createdAt * 1000))
                        add(MessageEntity(
                            localId = "server-${msg.msgId}",
                            serverId = msg.msgId,
                            chatUserId = msg.chatUserId,
                            senderId = msg.senderId,
                            text = text,
                            timestamp = msg.createdAt * 1000,
                            sentByMe = msg.senderId == myId,
                            delivered = msg.delivered == 1,
                            deliveredAt = msg.deliveredAt,
                            read = msg.read == 1
                        ))
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

            newMessages.groupBy { it.chatUserId }.forEach { (chatUserId, chatMessages) ->
                val latest = chatMessages.maxBy { it.createdAt }
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
                repeat(chatMessages.count { it.senderId != myId }) {
                    chatRepository.incrementUnread(chatUserId)
                }
            }
        }
    }

    private fun decryptMessagePayload(
        myDeviceId: Long,
        fromIdentityKey: ByteArray,
        prekeyId: Long?,
        ciphertext: ByteArray,
        salt: ByteArray,
        nonce: ByteArray
    ): String {
        val myPrivateIK = tokenStorage.getPrivateKey()
            ?: throw Exception("Identity Key private key not found locally")
        val secret = e2eeCrypto.deriveSharedSecret(myPrivateIK, fromIdentityKey)

        val plaintextBytes = e2eeCrypto.decrypt(ciphertext, secret, salt, nonce)

        return String(plaintextBytes, Charsets.UTF_8)
    }

    suspend fun deleteChatMessages(chatUserId: Long) {
        messageDao.deleteChatMessages(chatUserId)
    }

    suspend fun deleteMessage(localId: String) {
        messageDao.deleteMessageByLocalId(localId)
    }
}

data class DecryptedOfflineMsg(
    val chatUserId: Long,
    val text: String,
    val ts: Long
)

private data class HistoryMsgDecrypted(
    val chatUserId: Long,
    val text: String,
    val senderId: Long,
    val createdAt: Long
)
