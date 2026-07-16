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
    private val tokenStorage: SecureTokenStorage
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

    suspend fun handleMsgRecv(event: WebSocketEvent.MsgRecv) {
        val entity = MessageEntity(
            localId = "server-${event.msgId}",
            serverId = event.msgId,
            chatUserId = event.chatUserId,
            senderId = event.fromUserId,
            text = event.text,
            timestamp = event.ts,
            sentByMe = false,
            delivered = true
        )
        messageDao.insertMessage(entity)
        webSocketManager.sendDelivered(event.msgId)
    }

    suspend fun handleOfflineBatch(event: WebSocketEvent.OfflineBatch) {
        val myId = tokenStorage.getUserId()
        val entities = event.msgs.map { msg ->
            MessageEntity(
                localId = "server-${msg.msgId}",
                serverId = msg.msgId,
                chatUserId = msg.chatUserId,
                senderId = msg.fromUserId,
                text = msg.text,
                timestamp = msg.ts,
                sentByMe = msg.fromUserId == myId,
                delivered = true
            )
        }
        messageDao.insertMessages(entities)
        event.msgs.forEach { webSocketManager.sendDelivered(it.msgId) }
    }

    suspend fun syncHistory() {
        val maxId = messageDao.getMaxServerId() ?: 0
        val response = apiService.getMessageHistory(limit = 100, afterId = maxId)
        if (response.isSuccessful) {
            val messages = response.body() ?: emptyList()
            val myId = tokenStorage.getUserId()
            val entities = messages.map { msg ->
                MessageEntity(
                    localId = "server-${msg.msgId}",
                    serverId = msg.msgId,
                    chatUserId = msg.chatId,
                    senderId = msg.senderId,
                    text = msg.plaintext,
                    timestamp = msg.createdAt,
                    sentByMe = msg.senderId == myId,
                    delivered = msg.delivered == 1
                )
            }
            messageDao.insertMessages(entities)
        }
    }

    suspend fun getMaxServerId(): Long? = messageDao.getMaxServerId()

    suspend fun deleteChatMessages(chatUserId: Long) {
        messageDao.deleteChatMessages(chatUserId)
    }
}
