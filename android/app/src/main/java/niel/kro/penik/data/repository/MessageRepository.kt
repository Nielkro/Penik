package niel.kro.penik.data.repository

import kotlinx.coroutines.flow.Flow
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.entity.MessageEntity
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.data.crypto.E2EECrypto
import niel.kro.penik.data.crypto.PreKeyManager
import niel.kro.penik.data.network.websocket.E2EDevicePayload
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val apiService: ApiService,
    private val webSocketManager: WebSocketManager,
    private val tokenStorage: SecureTokenStorage,
    private val chatRepository: ChatRepository,
    private val e2eeCrypto: E2EECrypto,
    private val preKeyManager: PreKeyManager
) {

    fun getMessagesForChat(chatUserId: Long): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForChat(chatUserId)
    }

    suspend fun sendMessage(toUserId: Long, text: String): String {
        val clientMsgId = UUID.randomUUID().toString()
        val entity = MessageEntity(
            localId = clientMsgId,
            chatUserId = toUserId,
            senderId = tokenStorage.getUserId(),
            text = text,
            timestamp = System.currentTimeMillis(),
            sentByMe = true,
            delivered = false
        )
        messageDao.insertMessage(entity)

        val recipientBundles = try {
            val response = apiService.getKeyBundle(toUserId)
            if (response.isSuccessful) response.body()?.devices ?: emptyList() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val senderBundles = try {
            val response = apiService.getKeyBundle(tokenStorage.getUserId())
            if (response.isSuccessful) response.body()?.devices ?: emptyList() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val myDeviceId = tokenStorage.getDeviceId()
        val allDevices = (recipientBundles + senderBundles).filter { it.deviceId != myDeviceId }

        val myPrivateIK = tokenStorage.getPrivateKey()
            ?: throw Exception("Private Identity Key not found. Please log in again.")

        val payloads = allDevices.map { device ->
            val recipientIKPub = java.util.Base64.getDecoder().decode(device.identityKey)
            
            val secret = if (device.oneTimeKey != null && device.keyId != null) {
                val recipientOTPKPub = java.util.Base64.getDecoder().decode(device.oneTimeKey)
                val dh1 = e2eeCrypto.deriveSharedSecret(myPrivateIK, recipientOTPKPub)
                val dh2 = e2eeCrypto.deriveSharedSecret(myPrivateIK, recipientIKPub)
                
                val combined = ByteArray(dh1.size + dh2.size)
                System.arraycopy(dh1, 0, combined, 0, dh1.size)
                System.arraycopy(dh2, 0, combined, dh1.size, dh2.size)
                combined
            } else {
                e2eeCrypto.deriveSharedSecret(myPrivateIK, recipientIKPub)
            }

            val salt = ByteArray(16)
            java.security.SecureRandom().nextBytes(salt)
            val nonce = ByteArray(12)
            java.security.SecureRandom().nextBytes(nonce)
            
            val info = "PenikE2EE".toByteArray(Charsets.UTF_8)
            val derivedKey = e2eeCrypto.hkdf(secret, salt, info, 32)

            val ciphertext = e2eeCrypto.encrypt(text.toByteArray(Charsets.UTF_8), derivedKey, nonce)

            E2EDevicePayload(
                deviceId = device.deviceId,
                prekeyId = device.keyId,
                ciphertext = ciphertext,
                salt = salt,
                nonce = nonce
            )
        }

        webSocketManager.sendEncryptedMessage(toUserId, clientMsgId, payloads)
        return clientMsgId
    }

    suspend fun handleMsgAck(event: WebSocketEvent.MsgAck) {
        messageDao.acknowledgeMessage(event.clientMsgId, event.serverMsgId)
    }

    suspend fun handleMsgDelivered(event: WebSocketEvent.MsgDelivered) {
        messageDao.markDelivered(event.msgId)
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
                        messageDao.acknowledgeMessage(msg.clientMsgId, msg.msgId, msg.deliveredAt)
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
                            deliveredAt = msg.deliveredAt
                        ))
                    }
                }
            }
            messageDao.insertMessages(entities)

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
        val myPrivateIK = if (prekeyId != null) {
            val otpkPriv = tokenStorage.getPreKeyPrivate(prekeyId)
                ?: throw Exception("OTPK private key not found locally (id: $prekeyId)")
            tokenStorage.deletePreKeyPrivate(prekeyId)
            otpkPriv
        } else {
            tokenStorage.getPrivateKey()
                ?: throw Exception("Identity Key private key not found locally")
        }

        val secret = if (prekeyId != null) {
            val dh1 = e2eeCrypto.deriveSharedSecret(myPrivateIK, fromIdentityKey)
            
            val myIKPriv = tokenStorage.getPrivateKey()
                ?: throw Exception("Identity Key private key not found locally")
            val dh2 = e2eeCrypto.deriveSharedSecret(myIKPriv, fromIdentityKey)

            val combined = ByteArray(dh1.size + dh2.size)
            System.arraycopy(dh1, 0, combined, 0, dh1.size)
            System.arraycopy(dh2, 0, combined, dh1.size, dh2.size)
            combined
        } else {
            e2eeCrypto.deriveSharedSecret(myPrivateIK, fromIdentityKey)
        }

        val info = "PenikE2EE".toByteArray(Charsets.UTF_8)
        val derivedKey = e2eeCrypto.hkdf(secret, salt, info, 32)

        val plaintextBytes = e2eeCrypto.decrypt(ciphertext, derivedKey, nonce)
        return String(plaintextBytes, Charsets.UTF_8)
    }

    suspend fun deleteChatMessages(chatUserId: Long) {
        messageDao.deleteChatMessages(chatUserId)
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
