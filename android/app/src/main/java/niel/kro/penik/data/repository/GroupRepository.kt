package niel.kro.penik.data.repository

import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import niel.kro.penik.data.local.dao.GroupDao
import niel.kro.penik.data.local.entity.GroupEntity
import niel.kro.penik.data.local.entity.GroupMemberEntity
import niel.kro.penik.data.local.entity.GroupMessageEntity
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.CreateGroupRequest
import niel.kro.penik.data.network.api.InviteMemberRequest
import niel.kro.penik.data.network.api.ChangeRoleRequest
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.data.network.api.RenameGroupRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val api: ApiService,
    private val dao: GroupDao,
    private val tokenStorage: SecureTokenStorage,
    private val ws: WebSocketManager,
) {
    private val historySyncInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun observeGroups() = dao.observeGroups()
    fun observeActiveGroups() = dao.observeActiveGroups()
    fun observeArchivedGroups() = dao.observeArchivedGroups()
    fun getArchivedCount() = dao.getArchivedCount()
    suspend fun setArchived(groupId: Long, isArchived: Boolean) = dao.setArchived(groupId, isArchived)
    suspend fun archiveLegacyE2EEGroups() = dao.archiveLegacyE2EEGroups()
    fun observeGroup(groupId: Long) = dao.observeGroup(groupId)
    fun observeMessages(groupId: Long) = dao.observeMessages(groupId)
    fun observeLastMessageForGroup(groupId: Long) = dao.observeLastMessageForGroup(groupId)
    suspend fun getLastMessageForGroup(groupId: Long) = dao.getLastMessageForGroup(groupId)
    fun observeMember(groupId: Long, userId: Long) = dao.observeMember(groupId, userId)

    suspend fun renameGroup(groupId: Long, newName: String): Result<Unit> {
        return try {
            val response = api.renameGroup(groupId, RenameGroupRequest(newName))
            if (response.isSuccessful) {
                dao.getGroup(groupId)?.let {
                    dao.upsertGroup(it.copy(name = newName))
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Не удалось переименовать группу"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadGroupAvatar(groupId: Long, avatarBytes: ByteArray): Result<Unit> {
        return try {
            val requestFile = avatarBytes.toRequestBody("image/webp".toMediaTypeOrNull())
            val body = okhttp3.MultipartBody.Part.createFormData("avatar", "avatar.webp", requestFile)
            val response = api.uploadGroupAvatar(groupId, body)
            if (response.isSuccessful) {
                AvatarCacheBus.bumpGroup(groupId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Не удалось загрузить аватар группы"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun myUserId() = tokenStorage.getUserId()
    private fun myDeviceId() = tokenStorage.getDeviceId()

    suspend fun createGroup(name: String, memberUserIds: List<Long>, isE2EE: Boolean = false): GroupEntity? {
        val resp = api.createGroup(CreateGroupRequest(name, memberUserIds, isE2EE))
        val body = resp.body() ?: return null
        val entity = GroupEntity(
            id = body.id, name = body.name, ownerUserId = body.ownerUserId,
            role = body.role ?: "owner", membershipVersion = body.membershipVersion,
            currentKeyVersion = body.currentKeyVersion, createdAt = body.createdAt,
            isE2EE = body.isE2EE
        )
        dao.upsertGroup(entity)
        refreshMembers(body.id)
        return entity
    }

    private var lastSyncGroupsTime: Long = 0L

    suspend fun syncGroups(force: Boolean = false): List<GroupEntity> {
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncGroupsTime < 5000L) {
            return dao.observeGroups().firstOrNull() ?: emptyList()
        }
        val response = api.listGroups()
        if (!response.isSuccessful) return emptyList()
        val resp = response.body() ?: return emptyList()
        val existingGroups = dao.getAllGroups().associateBy { it.id }
        val entities = resp.groups.map {
            val existing = existingGroups[it.id]
            val isArchived = existing?.isArchived ?: it.isE2EE
            GroupEntity(
                id = it.id,
                name = it.name,
                ownerUserId = it.ownerUserId,
                role = it.role,
                status = it.status,
                membershipVersion = it.membershipVersion,
                currentKeyVersion = it.currentKeyVersion,
                createdAt = it.createdAt,
                isE2EE = it.isE2EE,
                isArchived = isArchived
            )
        }
        entities.forEach { dao.upsertGroup(it) }

        val serverGroupIds = entities.map { it.id }.toSet()
        dao.getAllGroups()
            .asSequence()
            .map { it.id }
            .filter { it !in serverGroupIds }
            .forEach { forgetGroup(it) }
        lastSyncGroupsTime = now
        return entities
    }

    suspend fun forgetGroup(groupId: Long) {
        dao.clearMembers(groupId)
        dao.clearKeys(groupId)
        dao.clearMessages(groupId)
        dao.deleteGroup(groupId)
    }

    suspend fun refreshMembers(groupId: Long): List<GroupMemberEntity> {
        val resp = api.listGroupMembers(groupId).body() ?: return emptyList()
        val members = resp.members
            .filter { it.status != "removed" }
            .map { GroupMemberEntity(groupId, it.userId, it.role, it.status, it.joinedAt, it.name, it.nickname, it.online, it.lastSeen) }
        dao.clearMembers(groupId)
        dao.insertMembers(members)
        return members
    }

    suspend fun acceptInvitation(groupId: Long) {
        val response = api.acceptGroupInvitation(groupId)
        if (!response.isSuccessful) {
            throw IllegalStateException("Не удалось принять приглашение: HTTP ${response.code()}")
        }
        dao.clearMembers(groupId)
        dao.clearKeys(groupId)
        dao.clearMessages(groupId)
        syncGroups(force = true)
        refreshMembers(groupId)
        syncHistory(groupId)
    }

    suspend fun declineInvitation(groupId: Long) {
        api.declineGroupInvitation(groupId)
        dao.clearMembers(groupId)
        dao.clearKeys(groupId)
        dao.clearMessages(groupId)
        dao.deleteGroup(groupId)
    }

    suspend fun inviteMember(groupId: Long, userId: Long, shareHistory: Boolean = false) {
        api.inviteGroupMember(groupId, InviteMemberRequest(userId))
        refreshMembers(groupId)
    }

    suspend fun removeMember(groupId: Long, userId: Long) {
        api.removeGroupMember(groupId, userId)
        refreshMembers(groupId)
    }

    suspend fun changeMemberRole(groupId: Long, userId: Long, role: String) {
        api.changeGroupMemberRole(groupId, userId, ChangeRoleRequest(role))
        refreshMembers(groupId)
    }

    suspend fun insertOptimisticMessage(groupId: Long, messageId: String, text: String, replyToMsgId: String? = null) {
        val createdAt = niel.kro.penik.data.network.TimeSyncManager.currentTimeSec()
        val senderUserId = myUserId()
        dao.upsertMessage(
            GroupMessageEntity(
                groupId = groupId, messageId = messageId, serverId = 0,
                senderUserId = senderUserId, senderDeviceId = myDeviceId(),
                keyVersion = 0, text = text, createdAt = createdAt,
                sentByMe = true, delivered = false,
                replyToMsgId = replyToMsgId
            )
        )
    }

    suspend fun sendMessage(groupId: Long, text: String, replyToMsgId: String? = null, existingMessageId: String? = null): String? {
        val messageId = existingMessageId ?: UUID.randomUUID().toString()
        val createdAt = niel.kro.penik.data.network.TimeSyncManager.currentTimeSec()
        val senderUserId = myUserId()

        dao.upsertMessage(
            GroupMessageEntity(
                groupId = groupId, messageId = messageId, serverId = 0,
                senderUserId = senderUserId, senderDeviceId = myDeviceId(),
                keyVersion = 0, text = text, createdAt = createdAt,
                sentByMe = true, delivered = false,
                replyToMsgId = replyToMsgId
            )
        )
        ws.sendGroupCloudMessage(groupId, messageId, text, createdAt, replyToMsgId)
        return messageId
    }

    suspend fun retryPendingMessages() {
        val pending = dao.getPendingMessages()
        if (pending.isEmpty()) return
        Log.d("GroupRepo", "retryPendingMessages: found ${pending.size} pending group messages")
        for (msg in pending) {
            runCatching {
                sendMessage(
                    groupId = msg.groupId,
                    text = msg.text,
                    replyToMsgId = msg.replyToMsgId,
                    existingMessageId = msg.messageId
                )
            }.onFailure { e ->
                Log.e("GroupRepo", "Failed to retry pending group message ${msg.messageId}", e)
            }
        }
    }

    suspend fun retryMessage(groupId: Long, messageId: String): Boolean {
        val msg = dao.getMessage(groupId, messageId) ?: return false
        return runCatching {
            sendMessage(
                groupId = groupId,
                text = msg.text,
                replyToMsgId = msg.replyToMsgId,
                existingMessageId = msg.messageId
            ) != null
        }.getOrDefault(false)
    }

    suspend fun deleteMessage(groupId: Long, messageId: String) {
        dao.deleteMessage(groupId, messageId)
        ws.sendGroupMessageDelete(groupId, messageId)
    }

    suspend fun handleIncomingDelete(groupId: Long, messageId: String) {
        dao.deleteMessage(groupId, messageId)
    }

    suspend fun handleIncoming(
        groupId: Long, id: Long, messageId: String, senderUserId: Long, senderDeviceId: Long,
        keyVersion: Long, ciphertext: ByteArray, salt: ByteArray, nonce: ByteArray, createdAt: Long,
        replyToMsgId: String? = null, plaintext: String? = null
    ): GroupMessageEntity? {
        dao.getMessage(groupId, messageId)?.let { if (it.serverId != 0L) return null }

        val text = plaintext ?: ""
        val entity = GroupMessageEntity(
            groupId = groupId, messageId = messageId, serverId = id,
            senderUserId = senderUserId, senderDeviceId = senderDeviceId,
            keyVersion = keyVersion, text = text, createdAt = createdAt,
            sentByMe = senderUserId == myUserId(), delivered = true,
            replyToMsgId = replyToMsgId
        )
        dao.upsertMessage(entity)
        ws.sendGroupDelivered(id)
        return entity
    }

    suspend fun editMessage(groupId: Long, messageId: String, newText: String) {
        val existingMsg = dao.getMessage(groupId, messageId)
        val finalPayload = if (existingMsg != null && existingMsg.text.startsWith("{")) {
            runCatching {
                val root = org.json.JSONObject(existingMsg.text)
                if (root.optString("type") == "file" || root.has("file")) {
                    root.put("text", newText)
                    root.toString()
                } else {
                    newText
                }
            }.getOrElse { newText }
        } else {
            newText
        }

        if (finalPayload.isBlank()) return
        val editedAt = niel.kro.penik.data.network.TimeSyncManager.currentTimeSec()

        dao.updateMessageText(groupId, messageId, finalPayload, editedAt * 1000)
        ws.sendGroupCloudEdit(groupId, messageId, finalPayload, editedAt)
    }

    suspend fun handleIncomingEdit(event: WebSocketEvent.GroupMsgEditNotify) {
        val text = event.plaintext ?: ""
        dao.updateMessageText(event.groupId, event.messageId, text, event.editedAt)
    }

    suspend fun getMessage(groupId: Long, messageId: String): GroupMessageEntity? {
        return dao.getMessage(groupId, messageId)
    }

    suspend fun updateMessageLocalText(groupId: Long, messageId: String, text: String) {
        dao.updateMessageText(groupId, messageId, text, System.currentTimeMillis())
    }

    suspend fun onAck(groupId: Long, messageId: String, serverId: Long) {
        dao.acknowledgeMessage(groupId, messageId, serverId)
    }

    private suspend fun syncHistoryPass(groupId: Long): Boolean {
        var cursor: Long? = null
        do {
            val page = api.getGroupHistory(groupId, 100, cursor).body() ?: return false
            for (m in page.messages) {
                val existing = dao.getMessage(groupId, m.messageId)
                val isEdited = m.editedAt != null && (existing == null || existing.editedAt == null || (m.editedAt * 1000 > (existing.editedAt ?: 0L)))
                if (existing != null && !isEdited && existing.serverId != 0L) continue

                val text = m.plaintext ?: ""

                if (existing != null) {
                    if (m.editedAt != null) {
                        dao.updateMessageText(groupId, m.messageId, text, m.editedAt * 1000)
                    }
                } else {
                    dao.upsertMessage(
                        GroupMessageEntity(
                            groupId = groupId,
                            messageId = m.messageId,
                            serverId = m.id,
                            replyToMsgId = m.replyToMsgId,
                            senderUserId = m.senderUserId,
                            senderDeviceId = m.senderDeviceId,
                            keyVersion = m.keyVersion,
                            text = text,
                            createdAt = m.createdAt,
                            sentByMe = m.senderUserId == myUserId(),
                            delivered = true,
                            editedAt = m.editedAt?.let { it * 1000 }
                        )
                    )
                }
            }
            cursor = page.nextCursor?.toLongOrNull()
        } while (cursor != null)
        return false
    }

    suspend fun syncHistory(groupId: Long) {
        if (!historySyncInFlight.add(groupId)) return
        try {
            if (dao.getMembers(groupId).isEmpty()) {
                runCatching { refreshMembers(groupId) }
            }
            syncHistoryPass(groupId)
        } catch (e: Exception) {
            Log.e("GroupRepository", "Failed to sync group history", e)
        } finally {
            historySyncInFlight.remove(groupId)
        }
    }
}
