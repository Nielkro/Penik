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

    @Query("UPDATE messages SET serverId = :serverId, delivered = 1, deliveredAt = :deliveredAt WHERE localId = :clientMsgId")
    suspend fun acknowledgeMessage(clientMsgId: String, serverId: Long, deliveredAt: Long? = System.currentTimeMillis())

    @Query("UPDATE messages SET delivered = 1, deliveredAt = :deliveredAt WHERE serverId = :serverId")
    suspend fun markDelivered(serverId: Long, deliveredAt: Long? = System.currentTimeMillis())

    @Query("SELECT localId FROM messages WHERE serverId = :serverId LIMIT 1")
    suspend fun findLocalIdByServerId(serverId: Long): String?

    @Query("DELETE FROM messages WHERE chatUserId = :chatUserId")
    suspend fun deleteChatMessages(chatUserId: Long)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessage(): Flow<MessageEntity?>
}
