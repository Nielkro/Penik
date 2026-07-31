package niel.kro.penik.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import niel.kro.penik.data.local.entity.GroupEntity
import niel.kro.penik.data.local.entity.GroupKeyEntity
import niel.kro.penik.data.local.entity.GroupMemberEntity
import niel.kro.penik.data.local.entity.GroupMessageEntity

@Dao
interface GroupDao {

    // Groups
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    @Query("SELECT * FROM groups ORDER BY id")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY id")
    suspend fun getAllGroups(): List<GroupEntity>

    @Query("SELECT * FROM group_members ORDER BY groupId, userId")
    suspend fun getAllMembers(): List<GroupMemberEntity>

    @Query("SELECT * FROM group_keys ORDER BY groupId, keyVersion")
    suspend fun getAllKeys(): List<GroupKeyEntity>

    @Query("SELECT * FROM group_messages ORDER BY groupId, createdAt")
    suspend fun getAllMessages(): List<GroupMessageEntity>

    @Query("SELECT * FROM groups WHERE id = :groupId")
    suspend fun getGroup(groupId: Long): GroupEntity?

    @Query("SELECT * FROM groups WHERE id = :groupId")
    fun observeGroup(groupId: Long): Flow<GroupEntity?>

    @Query("DELETE FROM groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long)

    @Query("DELETE FROM group_keys WHERE groupId = :groupId")
    suspend fun clearKeys(groupId: Long)

    @Query("DELETE FROM group_messages WHERE groupId = :groupId")
    suspend fun clearMessages(groupId: Long)

    // Members
    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<GroupMemberEntity>)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembers(groupId: Long): List<GroupMemberEntity>

    // Keys
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGroupKey(key: GroupKeyEntity)

    @Query("SELECT * FROM group_keys WHERE groupId = :groupId AND keyVersion = :keyVersion LIMIT 1")
    suspend fun getGroupKey(groupId: Long, keyVersion: Long): GroupKeyEntity?

    // Messages
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: GroupMessageEntity)

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY serverId, createdAt")
    fun observeMessages(groupId: Long): Flow<List<GroupMessageEntity>>

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY serverId, createdAt")
    suspend fun getMessages(groupId: Long): List<GroupMessageEntity>

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId AND messageId = :messageId LIMIT 1")
    suspend fun getMessage(groupId: Long, messageId: String): GroupMessageEntity?

    @Query("UPDATE group_messages SET serverId = :serverId, delivered = 1 WHERE groupId = :groupId AND messageId = :messageId")
    suspend fun acknowledgeMessage(groupId: Long, messageId: String, serverId: Long)

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY createdAt DESC LIMIT 1")
    fun observeLastMessageForGroup(groupId: Long): Flow<GroupMessageEntity?>

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastMessageForGroup(groupId: Long): GroupMessageEntity?

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND userId = :userId LIMIT 1")
    fun observeMember(groupId: Long, userId: Long): Flow<GroupMemberEntity?>
}
