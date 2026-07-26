package niel.kro.penik.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val localId: String,
    val serverId: Long? = null,
    val chatUserId: Long,
    val senderId: Long,
    val text: String,
    val timestamp: Long,
    val sentByMe: Boolean,
    val delivered: Boolean = false,
    val deliveredAt: Long? = null,
    val read: Boolean = false
)

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val userId: Long,
    val nickname: String,
    val name: String,
    val avatarUrl: String? = null,
    val lastMessage: String? = null,
    val lastMessageTimestamp: Long? = null,
    val unreadCount: Int = 0
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val ownerUserId: Long,
    val role: String? = null,
    val status: String = "active",
    val membershipVersion: Long = 1,
    val currentKeyVersion: Long = 1,
    val createdAt: Long = 0
)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "userId"])
data class GroupMemberEntity(
    val groupId: Long,
    val userId: Long,
    val role: String,
    val status: String,
    val joinedAt: Long = 0,
    val name: String = "",
    val nickname: String = "",
    val online: Boolean = false,
    val lastSeen: Long = 0
)

// Group keys are stored per epoch. The key bytes are protected by the OS-level
// app sandbox / keystore-wrapped DB; never logged.
@Entity(tableName = "group_keys", primaryKeys = ["groupId", "keyVersion"])
data class GroupKeyEntity(
    val groupId: Long,
    val keyVersion: Long,
    val key: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GroupKeyEntity
        return groupId == other.groupId && keyVersion == other.keyVersion && key.contentEquals(other.key)
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + keyVersion.hashCode()
        result = 31 * result + key.contentHashCode()
        return result
    }
}

@Entity(tableName = "group_messages", primaryKeys = ["groupId", "messageId"])
data class GroupMessageEntity(
    val groupId: Long,
    val messageId: String,
    val serverId: Long = 0,
    val senderUserId: Long,
    val senderDeviceId: Long = 0,
    val keyVersion: Long,
    val text: String,
    val createdAt: Long,
    val sentByMe: Boolean = false,
    val delivered: Boolean = false
)
