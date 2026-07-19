package niel.kro.penik.data.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestBody(
    val nickname: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("ik_pub") val ikPub: String? = null,
    @SerialName("opk_list") val opkList: List<String>? = null
)

@Serializable
data class RegisterRequestBody(
    val name: String,
    val nickname: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("ik_pub") val ikPub: String? = null,
    @SerialName("opk_list") val opkList: List<String>? = null
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
    @SerialName("id") val msgId: Long,
    @SerialName("chat_id") val chatId: Long,
    @SerialName("sender_id") val senderId: Long,
    @SerialName("recipient_id") val recipientId: Long,
    @SerialName("chat_user_id") val chatUserId: Long,
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    val plaintext: String? = null,
    @SerialName("timestamp") val createdAt: Long,
    val delivered: Int,
    @SerialName("delivered_at") val deliveredAt: Long? = null,
    val read: Int = 0,
    val ciphertext: String? = null,
    @SerialName("encryption_salt") val encryptionSalt: String? = null,
    @SerialName("encryption_nonce") val encryptionNonce: String? = null,
    @SerialName("sender_device_id") val senderDeviceId: Long? = null,
    @SerialName("recipient_device_id") val recipientDeviceId: Long? = null,
    @SerialName("prekey_id") val prekeyId: Long? = null
)

@Serializable
data class ChangePasswordRequestBody(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String
)

@Serializable
data class DeviceBundle(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("one_time_key") val oneTimeKey: String? = null,
    @SerialName("key_id") val keyId: Long? = null
)

@Serializable
data class KeyBundleResponse(
    val devices: List<DeviceBundle>
)

@Serializable
data class PrekeyUploadItem(
    @SerialName("key_id") val keyId: Long,
    @SerialName("public_key") val publicKey: String
)

@Serializable
data class PrekeysUploadRequest(
    val prekeys: List<PrekeyUploadItem>
)

@Serializable
data class PreKeysStatusResponse(
    val available: Int,
    val total: Int
)
@Serializable
data class MessageStatusResponse(
    @SerialName("msg_id") val msgId: Long,
    val delivered: Boolean,
    val read: Boolean
)
