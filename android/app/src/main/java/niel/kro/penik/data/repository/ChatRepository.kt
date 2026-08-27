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
        val newName = name.takeIf { it.isNotBlank() } ?: existing?.name.orEmpty()
        val newNickname = nickname.takeIf { it.isNotBlank() } ?: existing?.nickname.orEmpty()
        val existingTs = existing?.lastMessageTimestamp ?: 0L
        val (finalText, finalTs) = if (existing != null && existingTs > timestamp && !existing.lastMessage.isNullOrBlank()) {
            Pair(existing.lastMessage, existingTs)
        } else {
            Pair(text, timestamp)
        }
        val entity = ChatEntity(
            userId = userId,
            nickname = newNickname,
            name = newName,
            avatarUrl = existing?.avatarUrl,
            lastMessage = finalText,
            lastMessageTimestamp = finalTs,
            unreadCount = existing?.unreadCount ?: 0
        )
        android.util.Log.d("PenikChatRepo", "updateLastMessage -> saving chat: userId=$userId, name=$newName, lastMsg='$finalText', ts=$finalTs, unread=${entity.unreadCount}")
        chatDao.insertChat(entity)
    }

    /** Applies a display-name change pushed over the WebSocket (opcode 0x0c). */
    suspend fun updateContactName(userId: Long, name: String) {
        if (name.isBlank()) return
        val existing = chatDao.getChat(userId) ?: return
        if (existing.name == name) return
        android.util.Log.d("PenikChatRepo", "updateContactName: userId=$userId, oldName=${existing.name}, newName=$name")
        chatDao.insertChat(existing.copy(name = name))
    }

    suspend fun incrementUnread(userId: Long) {
        android.util.Log.d("PenikChatRepo", "incrementUnread: userId=$userId")
        chatDao.incrementUnread(userId)
    }

    suspend fun updateUnreadCount(userId: Long, unreadCount: Int) {
        android.util.Log.d("PenikChatRepo", "updateUnreadCount: userId=$userId, unread=$unreadCount")
        chatDao.updateUnreadCount(userId, unreadCount)
    }

    suspend fun clearUnread(userId: Long) {
        android.util.Log.d("PenikChatRepo", "clearUnread: userId=$userId")
        chatDao.clearUnread(userId)
    }

    suspend fun getChat(userId: Long): ChatEntity? = chatDao.getChat(userId)

    suspend fun upsertContact(userId: Long, nickname: String, name: String, avatarUrl: String?) {
        val existing = chatDao.getChat(userId)
        val lastMsg = messageDao.getLastMessageForChat(userId)
        val finalLastMessage = existing?.lastMessage?.takeIf { it.isNotBlank() } ?: lastMsg?.text
        val finalTimestamp = existing?.lastMessageTimestamp?.takeIf { it > 0 } ?: lastMsg?.timestamp
        val entity = ChatEntity(
            userId = userId,
            nickname = nickname.takeIf { it.isNotBlank() } ?: existing?.nickname.orEmpty(),
            name = name.takeIf { it.isNotBlank() } ?: existing?.name.orEmpty(),
            avatarUrl = avatarUrl ?: existing?.avatarUrl,
            lastMessage = finalLastMessage,
            lastMessageTimestamp = finalTimestamp,
            unreadCount = existing?.unreadCount ?: 0
        )
        android.util.Log.d("PenikChatRepo", "upsertContact -> saving chat: userId=$userId, name=${entity.name}, lastMsg='${entity.lastMessage}', ts=${entity.lastMessageTimestamp}")
        chatDao.insertChat(entity)
    }

    suspend fun deleteChat(userId: Long) {
        android.util.Log.w("PenikChatRepo", "DELETE CHAT CALLED FOR userId=$userId", Throwable())
        chatDao.deleteChat(userId)
    }
}
