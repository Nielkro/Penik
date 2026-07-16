package niel.kro.penik.data.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestBody(
    val nickname: String,
    val password: String,
    @SerialName("device_name") val deviceName: String
)

@Serializable
data class RegisterRequestBody(
    val name: String,
    val nickname: String,
    val password: String,
    @SerialName("device_name") val deviceName: String
)

@Serializable
data class AuthResponseBody(
    val token: String,
    @SerialName("user_id") val userId: Long,
    @SerialName("device_id") val deviceId: Long
)

@Serializable
data class UserSearchResult(
    val id: Long,
    val nickname: String,
    val name: String,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class HistoryMessageResponse(
    @SerialName("msg_id") val msgId: Long,
    @SerialName("chat_id") val chatId: Long,
    @SerialName("sender_id") val senderId: Long,
    val plaintext: String,
    @SerialName("created_at") val createdAt: Long,
    val delivered: Int
)

@Serializable
data class ChangePasswordRequestBody(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String
)
