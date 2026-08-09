package niel.kro.penik.data.repository

import kotlinx.coroutines.flow.Flow
import niel.kro.penik.data.local.dao.ChatDao
import niel.kro.penik.data.local.entity.ChatEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao
) {

    fun getAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    suspend fun getOrCreateChat(userId: Long, nickname: String, name: String, avatarUrl: String?): ChatEntity {
        val existing = chatDao.getChat(userId)
        if (existing != null) return existing
        val chat = ChatEntity(
            userId = userId,
            nickname = nickname,
            name = name,
            avatarUrl = avatarUrl
        )
        chatDao.insertChat(chat)
        return chat
    }

    suspend fun updateLastMessage(userId: Long, text: String, timestamp: Long, name: String = "", nickname: String = "") {
        val existing = chatDao.getChat(userId)
        if (existing == null) {
            chatDao.insertChat(ChatEntity(
                userId = userId,
                nickname = nickname,
                name = name,
                lastMessage = text,
                lastMessageTimestamp = timestamp
            ))
        } else if (timestamp >= (existing.lastMessageTimestamp ?: 0L)) {
            chatDao.updateLastMessage(userId, text, timestamp)
        }
    }

    suspend fun incrementUnread(userId: Long) {
        chatDao.incrementUnread(userId)
    }

    suspend fun updateUnreadCount(userId: Long, unreadCount: Int) {
        chatDao.updateUnreadCount(userId, unreadCount)
    }

    suspend fun clearUnread(userId: Long) {
        chatDao.clearUnread(userId)
    }

    suspend fun getChat(userId: Long): ChatEntity? = chatDao.getChat(userId)

    suspend fun upsertContact(userId: Long, nickname: String, name: String, avatarUrl: String?) {
        val existing = chatDao.getChat(userId)
        if (existing == null) {
            chatDao.insertChat(ChatEntity(userId = userId, nickname = nickname, name = name, avatarUrl = avatarUrl))
        } else {
            chatDao.insertChat(existing.copy(nickname = nickname, name = name, avatarUrl = avatarUrl ?: existing.avatarUrl))
        }
    }

    suspend fun deleteChat(userId: Long) {
        chatDao.deleteChat(userId)
    }
}
