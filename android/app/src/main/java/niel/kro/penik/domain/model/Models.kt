package niel.kro.penik.domain.model

data class User(
    val id: Long,
    val nickname: String,
    val name: String,
    val avatarUrl: String? = null
)

data class Chat(
    val userId: Long,
    val nickname: String,
    val name: String,
    val avatarUrl: String? = null,
    val lastMessage: String? = null,
    val lastMessageTimestamp: Long? = null,
    val unreadCount: Int = 0
)

data class Message(
    val localId: String,
    val serverId: Long? = null,
    val chatUserId: Long,
    val senderId: Long,
    val text: String,
    val timestamp: Long,
    val isSentByMe: Boolean,
    val delivered: Boolean = false
)

data class AuthResponse(
    val token: String,
    val userId: Long,
    val deviceId: Long
)

data class LoginRequest(
    val nickname: String,
    val password: String,
    val deviceName: String
)

data class RegisterRequest(
    val name: String,
    val nickname: String,
    val password: String,
    val deviceName: String
)

data class HistoryMessage(
    val msgId: Long,
    val chatId: Long,
    val senderId: Long,
    val plaintext: String,
    val createdAt: Long,
    val delivered: Int
)
