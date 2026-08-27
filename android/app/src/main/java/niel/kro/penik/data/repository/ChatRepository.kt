package niel.kro.penik.data.repository

import kotlinx.coroutines.flow.Flow
import niel.kro.penik.data.local.dao.ChatDao
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.entity.ChatEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao
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
            return
        }
        if (timestamp >= (existing.lastMessageTimestamp ?: 0L)) {
            chatDao.updateLastMessage(userId, text, timestamp)
        }
        // Callers pass the freshly fetched profile alongside the message, but this
        // branch used to drop it: a contact kept whatever display name it had at
        // first contact, so a rename never showed up on Android at all. Blank
        // values still mean "caller has nothing newer", so they never overwrite.
        val freshName = name.takeIf { it.isNotBlank() && it != existing.name }
        val freshNickname = nickname.takeIf { it.isNotBlank() && it != existing.nickname }
        if (freshName != null || freshNickname != null) {
            chatDao.insertChat(
                existing.copy(
                    name = freshName ?: existing.name,
                    nickname = freshNickname ?: existing.nickname
                )
            )
        }
    }

    /** Applies a display-name change pushed over the WebSocket (opcode 0x0c). */
    suspend fun updateContactName(userId: Long, name: String) {
        if (name.isBlank()) return
        val existing = chatDao.getChat(userId) ?: return
        if (existing.name == name) return
        chatDao.insertChat(existing.copy(name = name))
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
        val lastMsg = messageDao.getLastMessageForChat(userId)
        if (existing == null) {
            chatDao.insertChat(
                ChatEntity(
                    userId = userId,
                    nickname = nickname,
                    name = name,
                    avatarUrl = avatarUrl,
                    lastMessage = lastMsg?.text,
                    lastMessageTimestamp = lastMsg?.timestamp
                )
            )
        } else {
            chatDao.insertChat(
                existing.copy(
                    nickname = if (nickname.isNotBlank()) nickname else existing.nickname,
                    name = if (name.isNotBlank()) name else existing.name,
                    avatarUrl = avatarUrl ?: existing.avatarUrl,
                    lastMessage = existing.lastMessage ?: lastMsg?.text,
                    lastMessageTimestamp = existing.lastMessageTimestamp ?: lastMsg?.timestamp
                )
            )
        }
    }

    suspend fun deleteChat(userId: Long) {
        chatDao.deleteChat(userId)
    }
}
