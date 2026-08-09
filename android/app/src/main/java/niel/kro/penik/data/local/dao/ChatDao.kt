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

    @Query("SELECT * FROM chats WHERE userId = :userId")
    suspend fun getChat(userId: Long): ChatEntity?

    @Query("DELETE FROM chats WHERE userId = :userId")
    suspend fun deleteChat(userId: Long)
}
