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
    val delivered: Boolean = false
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
