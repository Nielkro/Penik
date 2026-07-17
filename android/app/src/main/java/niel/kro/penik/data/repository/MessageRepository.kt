package niel.kro.penik.data.repository

import kotlinx.coroutines.flow.Flow
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.entity.MessageEntity
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val apiService: ApiService,
    private val webSocketManager: WebSocketManager,
    private val tokenStorage: SecureTokenStorage,
    private val chatRepository: ChatRepository
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
        webSocketManager.sendMessage(toUserId, text, clientMsgId)
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

    suspend fun syncHistory() {
        val response = apiService.getMessageHistory(limit = 100)
        if (response.isSuccessful) {
            val messages = response.body() ?: emptyList()
            val myId = tokenStorage.getUserId()
            val newMessages = mutableListOf<niel.kro.penik.data.network.api.HistoryMessageResponse>()
            val entities = buildList {
                messages.forEach { msg ->
                    if (msg.senderId == myId && msg.clientMsgId != null) {
                        messageDao.acknowledgeMessage(msg.clientMsgId, msg.msgId, msg.deliveredAt)
                    }
                    if (messageDao.findLocalIdByServerId(msg.msgId) == null) {
                        newMessages.add(msg)
                        add(MessageEntity(
                            localId = "server-${msg.msgId}",
                            serverId = msg.msgId,
                            chatUserId = msg.chatUserId,
                            senderId = msg.senderId,
                            text = msg.plaintext,
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
                    text = latest.plaintext,
                    timestamp = latest.createdAt * 1000,
                    name = profile?.name.orEmpty(),
                    nickname = profile?.nickname.orEmpty()
                )
                repeat(chatMessages.count { it.senderId != myId }) {
                    chatRepository.incrementUnread(chatUserId)
                }
            }
        }
    }

    suspend fun deleteChatMessages(chatUserId: Long) {
        messageDao.deleteChatMessages(chatUserId)
    }
}
