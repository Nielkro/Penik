package niel.kro.penik.data.repository

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import niel.kro.penik.data.crypto.E2EECrypto
import niel.kro.penik.data.crypto.GroupCrypto
import niel.kro.penik.data.local.dao.GroupDao
import niel.kro.penik.data.local.entity.GroupEntity
import niel.kro.penik.data.local.entity.GroupKeyEntity
import niel.kro.penik.data.local.entity.GroupMemberEntity
import niel.kro.penik.data.local.entity.GroupMessageEntity
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.CreateGroupRequest
import niel.kro.penik.data.network.api.EnvelopeItem
import niel.kro.penik.data.network.api.InviteMemberRequest
import niel.kro.penik.data.network.api.UploadEnvelopesRequest
import niel.kro.penik.data.network.api.ChangeRoleRequest
import niel.kro.penik.data.network.api.UploadHistoryPacketsRequest
import niel.kro.penik.data.network.api.HistoryPacketItem
import niel.kro.penik.data.network.api.HistoryBlobMessage
import niel.kro.penik.data.network.api.HistoryBlob
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.data.network.api.RenameGroupRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import niel.kro.penik.data.crypto.IdentityPinStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group E2EE orchestration, mirroring the web client's groups.js.
 *
 * The server routes ciphertext and assigns sender identity; it never sees the
 * group key or plaintext. Each epoch has a 32-byte group key wrapped per device
 * with the pairwise X25519 shared secret.
 */
@Singleton
class GroupRepository @Inject constructor(
    private val api: ApiService,
    private val dao: GroupDao,
    private val tokenStorage: SecureTokenStorage,
    private val e2ee: E2EECrypto,
    private val groupCrypto: GroupCrypto,
    private val ws: WebSocketManager,
    private val identityPins: IdentityPinStore,
) {
    private val urlB64Flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    private val historySyncInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun observeGroups() = dao.observeGroups()
    fun observeActiveGroups() = dao.observeActiveGroups()
    fun observeArchivedGroups() = dao.observeArchivedGroups()
    fun getArchivedCount() = dao.getArchivedCount()
    suspend fun setArchived(groupId: Long, isArchived: Boolean) = dao.setArchived(groupId, isArchived)
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
    private fun myPrivateIK(): ByteArray =
        tokenStorage.getPrivateKey() ?: throw IllegalStateException("private identity key missing")

    private fun myPrivateSigningKey(): ByteArray {
        val priv = tokenStorage.getSigningPrivateKey()
        if (priv != null) return priv
        val raw = if (niel.kro.penik.data.crypto.RustCryptoCore.isAvailable()) {
            niel.kro.penik.data.crypto.RustCryptoCore.generateSigningKeyPair()
        } else null
        val (vk, sk) = if (raw != null && raw.size == 64) {
            Pair(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
        } else {
            Pair(ByteArray(32), ByteArray(32))
        }
        tokenStorage.saveSigningPrivateKey(sk)
        tokenStorage.saveSigningPublicKey(vk)
        return sk
    }

    /* ── Device enumeration + wrapping ── */

    private data class DeviceKey(val deviceId: Long, val ikPub: ByteArray, val signingKey: ByteArray? = null)

    // Short-lived per-user device-key cache. A single history sync decrypts many
    // messages across key versions; without this, each one re-fetches every
    // member's key bundle, producing a storm of /keys/bundle requests.
    //
    // Both are touched from unrelated coroutines (a history sync, an incoming
    // WebSocket frame, a push resolution in the FCM service), so plain
    // HashMap/HashSet risked a corrupted internal table, not just a lost entry.
    private val deviceKeyCache = java.util.concurrent.ConcurrentHashMap<Long, List<DeviceKey>>()
    private val failedKeyVersions: MutableSet<Pair<Long, Long>> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val chunkedSupportCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, Boolean>>()

    fun invalidateDeviceKeyCache() {
        deviceKeyCache.clear()
        failedKeyVersions.clear()
        chunkedSupportCache.clear()
    }

    private suspend fun fetchDeviceKeys(userIds: List<Long>): List<DeviceKey> {
        val out = mutableListOf<DeviceKey>()
        for (uid in userIds) {
            val cached = deviceKeyCache[uid]
            if (cached != null) {
                out.addAll(cached)
                continue
            }
            val myId = myUserId()
            val resp = runCatching {
                if (uid == myId) api.getKeyBundleSelf(uid) else api.getKeyBundle(uid)
            }.getOrNull()
            val devices = if (resp?.isSuccessful == true) resp.body()?.devices ?: emptyList() else emptyList()
            val keys = mutableListOf<DeviceKey>()
            for (d in devices) {
                val ik = runCatching { Base64.decode(d.identityKey, Base64.DEFAULT) }.getOrNull() ?: continue
                val signingKey = d.signingKey?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                // TOFU: a swapped group-member key would otherwise let the server
                // read every epoch key it wraps for that device.
                identityPins.verify(uid, d.deviceId, ik)
                keys.add(DeviceKey(d.deviceId, ik, signingKey))
            }
            deviceKeyCache[uid] = keys
            out.addAll(keys)
        }
        return out
    }

    private fun wrapKeyForDevices(groupKey: ByteArray, devices: List<DeviceKey>, groupId: Long, version: Long): List<EnvelopeItem> {
        val priv = myPrivateIK()
        return devices.map { dev ->
            val secret = e2ee.deriveSharedSecret(priv, dev.ikPub)
            val env = groupCrypto.wrapKeyForDevice(groupKey, secret, groupId, version)
            EnvelopeItem(
                deviceId = dev.deviceId,
                encryptedKey = Base64.encodeToString(env.ciphertext, urlB64Flags),
                salt = Base64.encodeToString(env.salt, urlB64Flags),
                nonce = Base64.encodeToString(env.nonce, urlB64Flags),
            )
        }
    }

    /* ── Key acquisition ── */

    /** Return the group key for a version, fetching + unwrapping the envelope if needed. */
    suspend fun ensureGroupKey(groupId: Long, version: Long, forceRefresh: Boolean = false): ByteArray? {
        val cacheKey = Pair(groupId, version)
        if (forceRefresh) {
            failedKeyVersions.remove(cacheKey)
            deviceKeyCache.clear()
        } else if (failedKeyVersions.contains(cacheKey)) return null

        dao.getGroupKey(groupId, version)?.let { return it.key }

        val env = runCatching { api.getGroupEnvelope(groupId, version) }.getOrNull()?.body()
        if (env == null) {
            failedKeyVersions.add(cacheKey)
            return null
        }
        val senderIK = fetchDeviceIK(groupId, env.senderDeviceId, env.senderUserId)
        if (senderIK == null) {
            failedKeyVersions.add(cacheKey)
            return null
        }
        val secret = e2ee.deriveSharedSecret(myPrivateIK(), senderIK)
        val key = runCatching {
            groupCrypto.unwrapKey(
                Base64.decode(env.encryptedKey, urlB64Flags), secret,
                Base64.decode(env.salt, urlB64Flags), Base64.decode(env.nonce, urlB64Flags),
                groupId, version
            )
        }.getOrNull()
        if (key == null) {
            failedKeyVersions.add(cacheKey)
            return null
        }
        dao.saveGroupKey(GroupKeyEntity(groupId, version, key))
        dao.getGroup(groupId)?.let { g ->
            if (version > g.currentKeyVersion) {
                dao.upsertGroup(g.copy(currentKeyVersion = version))
            }
        }
        return key
    }

    private suspend fun fetchDeviceIK(groupId: Long, deviceId: Long, senderUserId: Long? = null): ByteArray? {
        if (senderUserId != null && senderUserId > 0L) {
            val direct = fetchDeviceKeys(listOf(senderUserId)).find { it.deviceId == deviceId }?.ikPub
            if (direct != null) return direct
        }
        var members = dao.getMembers(groupId).map { it.userId }
        if (members.isEmpty()) {
            runCatching { refreshMembers(groupId) }
            members = dao.getMembers(groupId).map { it.userId }
        }
        val targetMembers = members.ifEmpty { listOf(myUserId()) }
        var ik = fetchDeviceKeys(targetMembers).find { it.deviceId == deviceId }?.ikPub
        if (ik == null) {
            runCatching { refreshMembers(groupId) }
            val freshMembers = dao.getMembers(groupId).map { it.userId }
            ik = fetchDeviceKeys(freshMembers.ifEmpty { listOf(myUserId()) }).find { it.deviceId == deviceId }?.ikPub
        }
        return ik
    }

    private suspend fun fetchDeviceSigningKey(groupId: Long, deviceId: Long, senderUserId: Long? = null): ByteArray? {
        if (senderUserId != null && senderUserId > 0L) {
            val direct = fetchDeviceKeys(listOf(senderUserId)).find { it.deviceId == deviceId }?.signingKey
            if (direct != null) return direct
        }
        var members = dao.getMembers(groupId).map { it.userId }
        if (members.isEmpty()) {
            runCatching { refreshMembers(groupId) }
            members = dao.getMembers(groupId).map { it.userId }
        }
        val targetMembers = members.ifEmpty { listOf(myUserId()) }
        var sk = fetchDeviceKeys(targetMembers).find { it.deviceId == deviceId }?.signingKey
        if (sk == null) {
            runCatching { refreshMembers(groupId) }
            val freshMembers = dao.getMembers(groupId).map { it.userId }
            sk = fetchDeviceKeys(freshMembers.ifEmpty { listOf(myUserId()) }).find { it.deviceId == deviceId }?.signingKey
        }
        return sk
    }

    /* ── Lifecycle ── */

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

        if (body.isE2EE) {
            val groupKey = groupCrypto.generateGroupKey()
            dao.saveGroupKey(GroupKeyEntity(body.id, 1, groupKey))
            val devices = fetchDeviceKeys(listOf(myUserId()))
            if (devices.isNotEmpty()) {
                api.uploadGroupEnvelopes(body.id, 1, UploadEnvelopesRequest(wrapKeyForDevices(groupKey, devices, body.id, 1)))
            }
        }
        refreshMembers(body.id)
        return entity
    }

    private var lastSyncGroupsTime: Long = 0L

    suspend fun syncGroups(force: Boolean = false): List<GroupEntity> {
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncGroupsTime < 5000L) {
            // Retrieve currently saved groups from database directly to avoid spamming the network
            return dao.observeGroups().firstOrNull() ?: emptyList()
        }
        val response = api.listGroups()
        if (!response.isSuccessful) return emptyList()
        val resp = response.body() ?: return emptyList()
        val entities = resp.groups.map {
            GroupEntity(it.id, it.name, it.ownerUserId, it.role, it.status, it.membershipVersion, it.currentKeyVersion, it.createdAt, it.isE2EE)
        }
        entities.forEach { dao.upsertGroup(it) }

        // The server list is authoritative for active and pending membership.
        // A removed member must not retain a usable local group, its messages,
        // or its old epoch keys after the membership has ended.
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
        failedKeyVersions.removeIf { it.first == groupId }
        deviceKeyCache.clear()
    }

    suspend fun refreshMembers(groupId: Long): List<GroupMemberEntity> {
        val resp = api.listGroupMembers(groupId).body() ?: return emptyList()
        val members = resp.members
            .filter { it.status != "removed" }
            .map { GroupMemberEntity(groupId, it.userId, it.role, it.status, it.joinedAt, it.name, it.nickname, it.online, it.lastSeen) }
        dao.clearMembers(groupId)
        dao.insertMembers(members)
        invalidateDeviceKeyCache()
        return members
    }

    suspend fun isChunkedEncryptionSupported(groupId: Long): Boolean {
        val now = System.currentTimeMillis()
        val cached = chunkedSupportCache[groupId]
        if (cached != null && cached.first > now) {
            return cached.second
        }
        return try {
            var memberIds = dao.getMembers(groupId).map { it.userId }
            if (memberIds.isEmpty()) {
                refreshMembers(groupId)
                memberIds = dao.getMembers(groupId).map { it.userId }
            }
            if (memberIds.isEmpty()) return false
            val myId = myUserId()
            var supported = true
            for (uid in memberIds) {
                val resp = runCatching {
                    if (uid == myId) api.getKeyBundleSelf(uid) else api.getKeyBundle(uid)
                }.getOrNull()
                val devices = if (resp?.isSuccessful == true) resp.body()?.devices ?: emptyList() else emptyList()
                if (devices.isEmpty() || devices.any { it.cryptoVersion < 2 }) {
                    supported = false
                    break
                }
            }
            chunkedSupportCache[groupId] = Pair(now + 2 * 60 * 1000L, supported)
            supported
        } catch (e: Exception) {
            android.util.Log.w("GroupRepo", "Failed to check chunked encryption support for group $groupId", e)
            false
        }
    }

    suspend fun acceptInvitation(groupId: Long) {
        val response = api.acceptGroupInvitation(groupId)
        if (!response.isSuccessful) {
            throw IllegalStateException("Не удалось принять приглашение: HTTP ${response.code()}")
        }

        // Re-entry is a new local membership epoch. Drop any data left from a
        // previous membership before accepting the new key and history packet;
        // otherwise the packet importer treats old rows as duplicates and the
        // old group key can make the new membership unusable.
        dao.clearMembers(groupId)
        dao.clearKeys(groupId)
        dao.clearMessages(groupId)
        invalidateDeviceKeyCache()
        failedKeyVersions.removeIf { it.first == groupId }

        // The regular sync cache can still contain the pending invitation. Force
        // a server reconciliation so sending is enabled immediately.
        syncGroups(force = true)
        refreshMembers(groupId)
        // Fetch the current epoch key before history: without it every server
        // message is skipped as undecryptable and the group looks empty.
        runCatching {
            val version = currentVersion(groupId)
            ensureGroupKey(groupId, version)
        }
        runCatching { pullHistoryPacket(groupId) }
        syncHistory(groupId)
    }

    suspend fun declineInvitation(groupId: Long) {
        api.declineGroupInvitation(groupId)
        // Drop every local trace of the declined group.
        dao.clearMembers(groupId)
        dao.clearKeys(groupId)
        dao.clearMessages(groupId)
        dao.deleteGroup(groupId)
    }

    suspend fun inviteMember(groupId: Long, userId: Long, shareHistory: Boolean = false) {
        api.inviteGroupMember(groupId, InviteMemberRequest(userId))
        refreshMembers(groupId)
        val newVersion = rotateAndDistribute(groupId)
        if (shareHistory && newVersion != null) {
            val devices = fetchDeviceKeys(listOf(userId))
            if (devices.isNotEmpty()) {
                runCatching { shareHistoryWithInvitee(groupId, userId, devices) }
            }
        }
    }

    suspend fun removeMember(groupId: Long, userId: Long) {
        api.removeGroupMember(groupId, userId)
        refreshMembers(groupId)
        rotateAndDistribute(groupId)
    }

    suspend fun changeMemberRole(groupId: Long, userId: Long, role: String) {
        api.changeGroupMemberRole(groupId, userId, ChangeRoleRequest(role))
        refreshMembers(groupId)
    }

    /**
     * Re-wrap the current group key for every active member device that is
     * still missing an envelope for it (e.g. a device created after the last
     * rotation, or a member who reinstalled without a backup). Owner/admin
     * only; returns the number of devices backfilled. Mirrors the web client.
     */
    suspend fun backfillCurrentKey(groupId: Long): Int {
        val group = dao.getGroup(groupId)
        val version = group?.currentKeyVersion ?: runCatching { currentVersion(groupId) }.getOrNull() ?: return 0
        val groupKey = ensureGroupKey(groupId, version, forceRefresh = true) ?: return 0

        var members = dao.getMembers(groupId)
        if (members.isEmpty()) {
            runCatching { refreshMembers(groupId) }
            members = dao.getMembers(groupId)
        }
        val activeUserIds = members.filter { it.status == "active" }.map { it.userId }.distinct()
        if (activeUserIds.isEmpty()) return 0
        val devices = fetchDeviceKeys(activeUserIds)
        if (devices.isEmpty()) return 0

        val covered = runCatching {
            api.listEnvelopeDevices(groupId, version).body()?.deviceIds?.toSet() ?: emptySet()
        }.getOrNull() ?: emptySet()
        val missing = devices.filter { it.deviceId !in covered }
        if (missing.isEmpty()) return 0

        val resp = runCatching {
            api.uploadGroupEnvelopes(groupId, version, UploadEnvelopesRequest(wrapKeyForDevices(groupKey, missing, groupId, version)))
        }.getOrNull()
        if (resp?.isSuccessful != true) return 0
        return missing.size
    }

    private suspend fun myRoleIn(groupId: Long): String? {
        val me = myUserId()
        dao.getMembers(groupId).find { it.userId == me }?.let { return it.role }
        return runCatching { refreshMembers(groupId) }.getOrNull()?.find { it.userId == me }?.role
    }

    /** Create a new key version and upload envelopes for all active devices. */
    suspend fun rotateAndDistribute(groupId: Long): Long? {
        invalidateDeviceKeyCache()
        val rotateResp = runCatching { api.rotateGroupKey(groupId) }.getOrNull()
        if (rotateResp?.isSuccessful != true) {
            Log.w("GroupRepo", "rotateGroupKey failed for group=$groupId code=${rotateResp?.code()}")
            return null
        }
        val resp = rotateResp.body() ?: return null
        val version = resp.keyVersion
        val groupKey = groupCrypto.generateGroupKey()
        dao.saveGroupKey(GroupKeyEntity(groupId, version, groupKey))

        val userIds = resp.devices.map { it.userId }.distinct()
        val devices = fetchDeviceKeys(userIds)
        if (devices.isNotEmpty()) {
            val upload = runCatching {
                api.uploadGroupEnvelopes(groupId, version, UploadEnvelopesRequest(wrapKeyForDevices(groupKey, devices, groupId, version)))
            }.getOrNull()
            if (upload?.isSuccessful != true) {
                Log.e("GroupRepo", "uploadGroupEnvelopes failed for group=$groupId v=$version code=${upload?.code()}")
                return null
            }
            // Verify the envelopes actually landed; a silent miss leaves the
            // rejoined device with no key and manual rotation "does nothing".
            val covered = runCatching {
                api.listEnvelopeDevices(groupId, version).body()?.deviceIds?.toSet() ?: emptySet()
            }.getOrNull() ?: emptySet()
            val missing = devices.map { it.deviceId }.filter { it !in covered }
            if (missing.isNotEmpty()) {
                Log.e("GroupRepo", "envelopes missing after upload for group=$groupId v=$version devices=$missing")
            }
        } else {
            Log.w("GroupRepo", "rotateAndDistribute: no device keys resolved for group=$groupId v=$version")
        }
        dao.getGroup(groupId)?.let { dao.upsertGroup(it.copy(currentKeyVersion = version)) }
        return version
    }

    /* ── Messaging ── */

    suspend fun insertOptimisticMessage(groupId: Long, messageId: String, text: String, replyToMsgId: String? = null) {
        val createdAt = niel.kro.penik.data.network.TimeSyncManager.currentTimeSec()
        val senderUserId = myUserId()
        val version = currentVersion(groupId)
        dao.upsertMessage(
            GroupMessageEntity(
                groupId = groupId, messageId = messageId, serverId = 0,
                senderUserId = senderUserId, senderDeviceId = myDeviceId(),
                keyVersion = version, text = text, createdAt = createdAt,
                sentByMe = true, delivered = false,
                replyToMsgId = replyToMsgId
            )
        )
    }

    suspend fun sendMessage(groupId: Long, text: String, replyToMsgId: String? = null, existingMessageId: String? = null): String? {
        val group = dao.getGroup(groupId)
        val isE2EE = group?.isE2EE ?: true
        val messageId = existingMessageId ?: UUID.randomUUID().toString()
        val createdAt = niel.kro.penik.data.network.TimeSyncManager.currentTimeSec()
        val senderUserId = myUserId()

        if (!isE2EE) {
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

        // Always query the server for the latest key version to avoid mismatch if offline during rotation.
        var version = currentVersion(groupId)
        var groupKey = ensureGroupKey(groupId, version)
        if (groupKey == null) {
            // No envelope for this device (reinstall, rejoin, missed rotation).
            // A privileged member repairs it by rotating; a plain member asks an
            // owner/admin to backfill via the missing-envelope path.
            val role = myRoleIn(groupId)
            if (role == "owner" || role == "admin") {
                Log.d("GroupRepo", "sendMessage: no key v=$version, auto-rotating as $role")
                val rotated = runCatching { rotateAndDistribute(groupId) }.getOrNull()
                if (rotated != null) {
                    version = rotated
                    groupKey = ensureGroupKey(groupId, version)
                }
            } else {
                Log.w("GroupRepo", "sendMessage: no key v=$version and role=$role cannot rotate")
            }
            if (groupKey == null) return null
        }

        val signingKey = myPrivateSigningKey()
        val enc = groupCrypto.encryptSignedMessage(
            text.toByteArray(Charsets.UTF_8), signingKey, groupKey, groupId, version, senderUserId, messageId, createdAt
        )

        dao.upsertMessage(
            GroupMessageEntity(
                groupId = groupId, messageId = messageId, serverId = 0,
                senderUserId = senderUserId, senderDeviceId = myDeviceId(),
                keyVersion = version, text = text, createdAt = createdAt,
                sentByMe = true, delivered = false,
                replyToMsgId = replyToMsgId
            )
        )
        ws.sendGroupMessage(groupId, messageId, version, enc.ciphertext, enc.salt, enc.nonce, createdAt, replyToMsgId)
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

    private suspend fun currentVersion(groupId: Long): Long {
        api.getGroup(groupId).body()?.let {
            dao.getGroup(groupId)?.let { g -> dao.upsertGroup(g.copy(currentKeyVersion = it.currentKeyVersion)) }
            return it.currentKeyVersion
        }
        val versions = api.listGroupKeyVersions(groupId).body()?.versions ?: emptyList()
        return versions.maxOrNull() ?: 1
    }

    /** Decrypt and persist an incoming group message. Returns null if dup/undecryptable. */
    suspend fun handleIncoming(
        groupId: Long, id: Long, messageId: String, senderUserId: Long, senderDeviceId: Long,
        keyVersion: Long, ciphertext: ByteArray, salt: ByteArray, nonce: ByteArray, createdAt: Long,
        replyToMsgId: String? = null, plaintext: String? = null
    ): GroupMessageEntity? {
        dao.getMessage(groupId, messageId)?.let { if (it.serverId != 0L) return null }

        val text = if (!plaintext.isNullOrEmpty()) {
            plaintext
        } else {
            val groupKey = ensureGroupKey(groupId, keyVersion)
            if (groupKey == null) {
                Log.w("GroupRepo", "key unavailable for group=$groupId v=$keyVersion")
                return null
            }
            val verifyingKey = fetchDeviceSigningKey(groupId, senderDeviceId, senderUserId)
            runCatching {
                String(
                    groupCrypto.decryptVerifiedMessage(ciphertext, verifyingKey, groupKey, salt, nonce, groupId, keyVersion, senderUserId, messageId, createdAt),
                    Charsets.UTF_8,
                )
            }.getOrElse {
                Log.e("GroupRepo", "decrypt/signature verification failed for group=$groupId msg=$messageId")
                return null
            }
        }
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

        val isE2EE = dao.getGroup(groupId)?.isE2EE ?: true
        val editedAt = niel.kro.penik.data.network.TimeSyncManager.currentTimeSec()

        if (!isE2EE) {
            dao.updateMessageText(groupId, messageId, finalPayload, editedAt * 1000)
            ws.sendGroupCloudEdit(groupId, messageId, finalPayload, editedAt)
            return
        }

        val version = currentVersion(groupId)
        val groupKey = ensureGroupKey(groupId, version) ?: return
        val senderUserId = myUserId()
        val signingKey = myPrivateSigningKey()
        val enc = groupCrypto.encryptSignedMessage(
            finalPayload.toByteArray(Charsets.UTF_8), signingKey, groupKey, groupId, version, senderUserId, messageId, editedAt
        )

        dao.updateMessageText(groupId, messageId, finalPayload, editedAt * 1000)
        ws.sendGroupMessageEdit(groupId, messageId, version, enc.ciphertext, enc.salt, enc.nonce, editedAt)
    }

    suspend fun handleIncomingEdit(event: WebSocketEvent.GroupMsgEditNotify) {
        if (!event.plaintext.isNullOrEmpty()) {
            dao.updateMessageText(event.groupId, event.messageId, event.plaintext, event.editedAt)
            return
        }
        val groupKey = ensureGroupKey(event.groupId, event.keyVersion) ?: return
        val verifyingKey = fetchDeviceSigningKey(event.groupId, event.senderDeviceId, event.senderUserId)
        val editedAtSec = event.editedAt / 1000
        val text = runCatching {
            String(
                groupCrypto.decryptVerifiedMessage(
                    event.ciphertext, verifyingKey, groupKey, event.salt, event.nonce,
                    event.groupId, event.keyVersion, event.senderUserId, event.messageId, editedAtSec
                ),
                Charsets.UTF_8
            )
        }.getOrNull() ?: return

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

    /* ── Offline history sync (ciphertext) ── */

    /** One decrypt pass over server history. Returns true if any message was skipped for a missing key. */
    private suspend fun syncHistoryPass(groupId: Long, availableVersions: Set<Long>): Boolean {
        var cursor: Long? = null
        var missingKey = false
        do {
            val page = api.getGroupHistory(groupId, 100, cursor).body() ?: return missingKey
            for (m in page.messages) {
                val existing = dao.getMessage(groupId, m.messageId)
                val isEdited = m.editedAt != null && (existing == null || existing.editedAt == null || (m.editedAt * 1000 > (existing.editedAt ?: 0L)))
                if (existing != null && !isEdited && existing.serverId != 0L) continue

                val text = if (!m.plaintext.isNullOrEmpty()) {
                    m.plaintext
                } else {
                    if (m.keyVersion !in availableVersions) continue
                    val groupKey = ensureGroupKey(groupId, m.keyVersion) ?: run { missingKey = true; continue }
                    val verifyingKey = fetchDeviceSigningKey(groupId, m.senderDeviceId, m.senderUserId)
                    val ts = m.editedAt ?: m.createdAt
                    runCatching {
                        String(
                            groupCrypto.decryptVerifiedMessage(
                                Base64.decode(m.ciphertext, urlB64Flags), verifyingKey, groupKey,
                                Base64.decode(m.salt, urlB64Flags),
                                Base64.decode(m.nonce, urlB64Flags),
                                groupId, m.keyVersion, m.senderUserId, m.messageId, ts
                            ),
                            Charsets.UTF_8
                        )
                    }.getOrNull() ?: continue
                }

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
        return missingKey
    }

    suspend fun syncHistory(groupId: Long) {
        if (!historySyncInFlight.add(groupId)) return
        try {
            // Decrypting envelopes needs the sender's identity key, resolved via the
            // member list. If members haven't been fetched yet, do it now so history
            // isn't silently dropped as undecryptable.
            if (dao.getMembers(groupId).isEmpty()) {
                runCatching { refreshMembers(groupId) }
            }
            val availableVersions = api.listGroupKeyVersions(groupId).body()?.versions.orEmpty().toSet()
            if (availableVersions.isEmpty()) return
            val missingKey = syncHistoryPass(groupId, availableVersions)
            // A privileged member repairs missing envelopes for the current epoch
            // so reinstalled/rejoined devices recover without a manual rotation.
            // Afterwards the poisoned failure cache is dropped and the pass is
            // retried once, so the just-backfilled key is actually used.
            if (missingKey) {
                val role = myRoleIn(groupId)
                if (role == "owner" || role == "admin") {
                    val healed = runCatching { backfillCurrentKey(groupId) }.getOrNull() ?: 0
                    if (healed > 0) {
                        failedKeyVersions.removeIf { it.first == groupId }
                        runCatching { syncHistoryPass(groupId, availableVersions) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GroupRepository", "Failed to sync group history", e)
        } finally {
            historySyncInFlight.remove(groupId)
        }
    }

    suspend fun pullHistoryPacketForRetry(groupId: Long) {
        pullHistoryPacket(groupId)
    }

    private suspend fun pullHistoryPacket(groupId: Long) {
        val resp = api.getGroupHistoryPacket(groupId)
        if (!resp.isSuccessful) return
        val packet = resp.body() ?: return

        val senderDeviceId = packet.senderDeviceId
        val senderIK = fetchDeviceIK(groupId, senderDeviceId) ?: return

        val secret = e2ee.deriveSharedSecret(myPrivateIK(), senderIK)
        val pt = e2ee.decrypt(
            Base64.decode(packet.encryptedHistory, urlB64Flags),
            secret,
            Base64.decode(packet.salt, urlB64Flags),
            Base64.decode(packet.nonce, urlB64Flags),
            "penik-pairwise-message-v1"
        )

        val jsonStr = String(pt, Charsets.UTF_8)
        val blob = Json.decodeFromString<HistoryBlob>(jsonStr)
        for (m in blob.messages) {
            val existing = dao.getMessage(groupId, m.messageId)
            if (existing != null && existing.serverId != 0L) continue
            dao.upsertMessage(
                GroupMessageEntity(
                    groupId = groupId,
                    messageId = m.messageId,
                    serverId = m.id,
                    senderUserId = m.senderUserId,
                    senderDeviceId = m.senderDeviceId,
                    keyVersion = m.keyVersion,
                    text = m.plaintext,
                    createdAt = m.createdAt,
                    sentByMe = m.senderUserId == myUserId(),
                    delivered = true
                )
            )
        }
    }

    private suspend fun shareHistoryWithInvitee(groupId: Long, userId: Long, devices: List<DeviceKey>) {
        val messages = dao.getMessages(groupId)
        if (messages.isEmpty() || devices.isEmpty()) return

        val blobMessageList = messages.map {
            HistoryBlobMessage(
                id = it.serverId,
                messageId = it.messageId,
                senderUserId = it.senderUserId,
                senderDeviceId = it.senderDeviceId,
                keyVersion = it.keyVersion,
                plaintext = it.text,
                createdAt = it.createdAt
            )
        }
        val jsonStr = Json.encodeToString(HistoryBlob(version = 1, messages = blobMessageList))
        val blobBytes = jsonStr.toByteArray(Charsets.UTF_8)

        val myPrivateIK = myPrivateIK()
        val packets = mutableListOf<HistoryPacketItem>()
        for (dev in devices) {
            val secret = e2ee.deriveSharedSecret(myPrivateIK, dev.ikPub)
            val enc = e2ee.encrypt(blobBytes, secret, "penik-pairwise-message-v1")
            packets.add(
                HistoryPacketItem(
                    deviceId = dev.deviceId,
                    encryptedHistory = Base64.encodeToString(enc.ciphertext, urlB64Flags),
                    salt = Base64.encodeToString(enc.salt, urlB64Flags),
                    nonce = Base64.encodeToString(enc.nonce, urlB64Flags)
                )
            )
        }
        api.uploadGroupHistoryPackets(groupId, UploadHistoryPacketsRequest(packets))
    }
}
