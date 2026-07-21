package niel.kro.penik.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import niel.kro.penik.data.local.entity.MessageEntity

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatUserId = :chatUserId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatUserId: Long): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE chatUserId = :chatUserId AND senderId = :senderId AND timestamp = :timestamp AND text = :text LIMIT 1")
    suspend fun findMatchingMessage(chatUserId: Long, senderId: Long, timestamp: Long, text: String): MessageEntity?

    @Query("DELETE FROM messages WHERE chatUserId = :chatUserId AND timestamp = :timestamp AND text LIKE '[Ошибка%'")
    suspend fun deleteUndecryptedMessagesAt(chatUserId: Long, timestamp: Long)

    @Query("UPDATE messages SET serverId = :serverId WHERE localId = :clientMsgId")
    suspend fun acknowledgeMessage(clientMsgId: String, serverId: Long)

    @Query("UPDATE messages SET delivered = 1, deliveredAt = :deliveredAt WHERE serverId = :serverId")
    suspend fun markDelivered(serverId: Long, deliveredAt: Long? = System.currentTimeMillis())

    @Query("UPDATE messages SET delivered = 1, deliveredAt = :deliveredAt WHERE localId = :clientMsgId")
    suspend fun markDeliveredByClientId(clientMsgId: String, deliveredAt: Long = System.currentTimeMillis())

    @Query("UPDATE messages SET read = 1 WHERE serverId = :serverId")
    suspend fun markRead(serverId: Long)

    @Query("UPDATE messages SET read = 1 WHERE localId = :clientMsgId")
    suspend fun markReadByClientId(clientMsgId: String)

    @Query("UPDATE messages SET delivered = :delivered, read = :read, deliveredAt = CASE WHEN :delivered = 1 THEN COALESCE(deliveredAt, :deliveredAt) ELSE deliveredAt END WHERE serverId = :serverId")
    suspend fun updateStatus(serverId: Long, delivered: Boolean, read: Boolean, deliveredAt: Long = System.currentTimeMillis())

    @Query("SELECT localId FROM messages WHERE serverId = :serverId LIMIT 1")
    suspend fun findLocalIdByServerId(serverId: Long): String?

    @Query("DELETE FROM messages WHERE chatUserId = :chatUserId")
    suspend fun deleteChatMessages(chatUserId: Long)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessage(): Flow<MessageEntity?>
}
