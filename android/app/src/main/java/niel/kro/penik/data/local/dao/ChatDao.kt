package niel.kro.penik.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import niel.kro.penik.data.local.entity.ChatEntity

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY lastMessageTimestamp DESC")
    fun getAllChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE is_archived = 0 ORDER BY lastMessageTimestamp DESC")
    fun getActiveChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE is_archived = 1 ORDER BY lastMessageTimestamp DESC")
    fun getArchivedChats(): Flow<List<ChatEntity>>

    @Query("SELECT COUNT(*) FROM chats WHERE is_archived = 1")
    fun getArchivedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Query("UPDATE chats SET lastMessage = :text, lastMessageTimestamp = :timestamp WHERE userId = :userId AND (:timestamp >= lastMessageTimestamp OR lastMessageTimestamp IS NULL)")
    suspend fun updateLastMessage(userId: Long, text: String, timestamp: Long)

    @Query("UPDATE chats SET unreadCount = unreadCount + 1 WHERE userId = :userId")
    suspend fun incrementUnread(userId: Long)

    @Query("UPDATE chats SET unreadCount = :unreadCount WHERE userId = :userId")
    suspend fun updateUnreadCount(userId: Long, unreadCount: Int)

    @Query("UPDATE chats SET unreadCount = 0 WHERE userId = :userId")
    suspend fun clearUnread(userId: Long)

    @Query("UPDATE chats SET is_archived = :isArchived WHERE userId = :userId")
    suspend fun setArchived(userId: Long, isArchived: Boolean)

    @Query("SELECT * FROM chats ORDER BY lastMessageTimestamp DESC")
    suspend fun getChatsSnapshot(): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<ChatEntity>)

    @Query("SELECT * FROM chats WHERE userId = :userId LIMIT 1")
    suspend fun getChat(userId: Long): ChatEntity?

    @Query("SELECT * FROM chats WHERE userId = :userId")
    suspend fun getChatsForUser(userId: Long): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE userId = :userId LIMIT 1")
    fun observeChat(userId: Long): Flow<ChatEntity?>

    @Query("DELETE FROM chats WHERE userId = :userId")
    suspend fun deleteChat(userId: Long)
}
