package niel.kro.penik.data.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestBody(
    val nickname: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "",
    val location: String = "",
    @SerialName("ik_pub") val ikPub: String? = null
)

@Serializable
data class RegisterRequestBody(
    val name: String,
    val nickname: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "",
    val location: String = "",
    @SerialName("ik_pub") val ikPub: String? = null
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
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long = 0
)

@Serializable
data class HistoryMessageResponse(
    @SerialName("id") val msgId: Long,
    @SerialName("chat_id") val chatId: Long,
    @SerialName("sender_id") val senderId: Long,
    @SerialName("recipient_id") val recipientId: Long,
    @SerialName("chat_user_id") val chatUserId: Long,
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    @SerialName("reply_to_msg_id") val replyToMsgId: String? = null,
    val plaintext: String? = null,
    @SerialName("timestamp") val createdAt: Long,
    val delivered: Int,
    @SerialName("delivered_at") val deliveredAt: Long? = null,
    val read: Int = 0,
    val ciphertext: String? = null,
    @SerialName("encryption_salt") val encryptionSalt: String? = null,
    @SerialName("encryption_nonce") val encryptionNonce: String? = null,
    @SerialName("sender_device_id") val senderDeviceId: Long? = null,
    @SerialName("recipient_device_id") val recipientDeviceId: Long? = null
)

@Serializable
data class ChangePasswordRequestBody(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String
)

@Serializable
data class DeviceBundle(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("identity_key") val identityKey: String
)

@Serializable
data class KeyBundleResponse(
    val devices: List<DeviceBundle>
)


@Serializable
data class MessageStatusResponse(
    @SerialName("msg_id") val msgId: Long,
    val delivered: Boolean,
    val read: Boolean
)

@Serializable
data class PairingClaimRequest(
    @SerialName("session_id") val sessionId: String,
    val token: String,
    @SerialName("public_key") val publicKey: String
)

@Serializable
data class PairingHistoryUploadRequest(
    @SerialName("encrypted_history") val encryptedHistory: String,
    @SerialName("message_ids") val messageIds: List<Long> = emptyList()
)

@Serializable
data class PairingClaimResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("ephemeral_public_key") val ephemeralPublicKey: String,
    @SerialName("encrypted_history") val encryptedHistory: String = "",
    @SerialName("owner_user_id") val ownerUserId: Long,
    @SerialName("transfer_direction") val transferDirection: String = "web_to_phone",
    @SerialName("expires_at") val expiresAt: Long
)
@Serializable data class PairingStateResponse(@SerialName("claimed") val claimed: Boolean, @SerialName("public_key") val publicKey: String = "", @SerialName("encrypted_history") val encryptedHistory: String = "", @SerialName("transfer_direction") val transferDirection: String = "web_to_phone")

/* ── Groups ── */

@Serializable
data class CreateGroupRequest(
    val name: String,
    @SerialName("member_user_ids") val memberUserIds: List<Long> = emptyList()
)

@Serializable
data class GroupResponse(
    val id: Long,
    val name: String,
    @SerialName("owner_user_id") val ownerUserId: Long,
    val role: String? = null,
    val status: String = "active",
    @SerialName("membership_version") val membershipVersion: Long = 1,
    @SerialName("current_key_version") val currentKeyVersion: Long = 1,
    @SerialName("created_at") val createdAt: Long = 0
)

@Serializable
data class GroupListResponse(val groups: List<GroupResponse>)

@Serializable
data class GroupMemberResponse(
    @SerialName("user_id") val userId: Long,
    val role: String,
    val status: String,
    @SerialName("joined_at") val joinedAt: Long = 0,
    val name: String = "",
    val nickname: String = "",
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long = 0
)

@Serializable
data class GroupMembersResponse(val members: List<GroupMemberResponse>)

@Serializable
data class InviteMemberRequest(@SerialName("user_id") val userId: Long)

@Serializable
data class ChangeRoleRequest(val role: String)

@Serializable
data class RenameGroupRequest(val name: String)

@Serializable
data class RotateKeyResponse(
    @SerialName("key_version") val keyVersion: Long,
    @SerialName("membership_version") val membershipVersion: Long,
    val devices: List<RotateDevice> = emptyList()
)

@Serializable
data class RotateDevice(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("user_id") val userId: Long
)

@Serializable
data class EnvelopeItem(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("encrypted_key") val encryptedKey: String,
    val salt: String,
    val nonce: String
)

@Serializable
data class UploadEnvelopesRequest(val envelopes: List<EnvelopeItem>)

@Serializable
data class HistoryPacketItem(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("encrypted_history") val encryptedHistory: String,
    val salt: String,
    val nonce: String
)

@Serializable
data class UploadHistoryPacketsRequest(val packets: List<HistoryPacketItem>)

@Serializable
data class HistoryPacketResponse(
    @SerialName("encrypted_history") val encryptedHistory: String,
    val salt: String,
    val nonce: String,
    @SerialName("sender_device_id") val senderDeviceId: Long
)

@Serializable
data class HistoryBlobMessage(
    val id: Long,
    @SerialName("message_id") val messageId: String,
    @SerialName("sender_user_id") val senderUserId: Long,
    @SerialName("sender_device_id") val senderDeviceId: Long,
    @SerialName("key_version") val keyVersion: Long,
    val plaintext: String,
    @SerialName("created_at") val createdAt: Long
)

@Serializable
data class HistoryBlob(
    val version: Int,
    val messages: List<HistoryBlobMessage>
)

@Serializable
data class GroupEnvelopeResponse(
    @SerialName("key_version") val keyVersion: Long,
    @SerialName("encrypted_key") val encryptedKey: String,
    val salt: String,
    val nonce: String,
    @SerialName("sender_device_id") val senderDeviceId: Long
)

@Serializable
data class GroupKeyVersionsResponse(val versions: List<Long> = emptyList())

@Serializable
data class GroupHistoryMessage(
    val id: Long,
    @SerialName("message_id") val messageId: String,
    @SerialName("sender_user_id") val senderUserId: Long,
    @SerialName("sender_device_id") val senderDeviceId: Long,
    @SerialName("key_version") val keyVersion: Long,
    val ciphertext: String,
    val salt: String,
    val nonce: String,
    @SerialName("created_at") val createdAt: Long
)

@Serializable
data class GroupHistoryResponse(
    val messages: List<GroupHistoryMessage> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null
)

@Serializable
data class NicknameCheckResponse(
    val available: Boolean
)

@Serializable
data class PublicProfileResponse(
    val id: Long,
    val name: String,
    val nickname: String
)

@Serializable
data class KeyBackupRequest(
    @SerialName("encrypted_blob") val encryptedBlob: String,
    val salt: String,
    val iv: String
)

@Serializable
data class KeyBackupResponse(
    @SerialName("encrypted_blob") val encryptedBlob: String,
    val salt: String,
    val iv: String
)

@Serializable
data class VkUploadResponse(
    val url: String
)

@Serializable
data class DeviceResponse(
    val id: Long,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "",
    val location: String = "",
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_seen") val lastSeen: Long,
    @SerialName("is_current") val isCurrent: Boolean = false,
    @SerialName("has_session") val hasSession: Boolean = false,
    @SerialName("sessions_count") val sessionsCount: Int = 0
)
