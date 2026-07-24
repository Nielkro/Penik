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
import niel.kro.penik.data.network.websocket.WebSocketManager
import java.util.UUID
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
) {
    private val urlB64Flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun observeGroups() = dao.observeGroups()
    fun observeMessages(groupId: Long) = dao.observeMessages(groupId)
    fun observeLastMessageForGroup(groupId: Long) = dao.observeLastMessageForGroup(groupId)
    suspend fun getLastMessageForGroup(groupId: Long) = dao.getLastMessageForGroup(groupId)

    private fun myUserId() = tokenStorage.getUserId()
    private fun myDeviceId() = tokenStorage.getDeviceId()
    private fun myPrivateIK(): ByteArray =
        tokenStorage.getPrivateKey() ?: throw IllegalStateException("private identity key missing")

    /* ── Device enumeration + wrapping ── */

    private data class DeviceKey(val deviceId: Long, val ikPub: ByteArray)

    // Short-lived per-user device-key cache. A single history sync decrypts many
    // messages across key versions; without this, each one re-fetches every
    // member's key bundle, producing a storm of /keys/bundle requests.
    private val deviceKeyCache = mutableMapOf<Long, List<DeviceKey>>()

    fun invalidateDeviceKeyCache() = deviceKeyCache.clear()

    private suspend fun fetchDeviceKeys(userIds: List<Long>): List<DeviceKey> {
        val out = mutableListOf<DeviceKey>()
        for (uid in userIds) {
            deviceKeyCache[uid]?.let { out.addAll(it); continue }
            val resp = runCatching { api.getKeyBundle(uid) }.getOrNull() ?: continue
            val devices = resp.body()?.devices ?: continue
            val keys = mutableListOf<DeviceKey>()
            for (d in devices) {
                val ik = runCatching { Base64.decode(d.identityKey, Base64.DEFAULT) }.getOrNull() ?: continue
                keys.add(DeviceKey(d.deviceId, ik))
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
    suspend fun ensureGroupKey(groupId: Long, version: Long): ByteArray? {
        dao.getGroupKey(groupId, version)?.let { return it.key }

        val env = runCatching { api.getGroupEnvelope(groupId, version) }.getOrNull()?.body() ?: return null
        val senderIK = fetchDeviceIK(groupId, env.senderDeviceId) ?: return null
        val secret = e2ee.deriveSharedSecret(myPrivateIK(), senderIK)
        val key = runCatching {
            groupCrypto.unwrapKey(
                Base64.decode(env.encryptedKey, urlB64Flags), secret,
                Base64.decode(env.salt, urlB64Flags), Base64.decode(env.nonce, urlB64Flags),
                groupId, version
            )
        }.getOrNull() ?: return null
        dao.saveGroupKey(GroupKeyEntity(groupId, version, key))
        return key
    }

    private suspend fun fetchDeviceIK(groupId: Long, deviceId: Long): ByteArray? {
        val members = dao.getMembers(groupId).map { it.userId }.ifEmpty { listOf(myUserId()) }
        return fetchDeviceKeys(members).find { it.deviceId == deviceId }?.ikPub
    }

    /* ── Lifecycle ── */

    suspend fun createGroup(name: String, memberUserIds: List<Long>): GroupEntity? {
        val resp = api.createGroup(CreateGroupRequest(name, memberUserIds))
        val body = resp.body() ?: return null
        val entity = GroupEntity(
            id = body.id, name = body.name, ownerUserId = body.ownerUserId,
            role = body.role ?: "owner", membershipVersion = body.membershipVersion,
            currentKeyVersion = body.currentKeyVersion, createdAt = body.createdAt,
        )
        dao.upsertGroup(entity)

        val groupKey = groupCrypto.generateGroupKey()
        dao.saveGroupKey(GroupKeyEntity(body.id, 1, groupKey))
        val devices = fetchDeviceKeys(listOf(myUserId()))
        if (devices.isNotEmpty()) {
            api.uploadGroupEnvelopes(body.id, 1, UploadEnvelopesRequest(wrapKeyForDevices(groupKey, devices, body.id, 1)))
        }
        refreshMembers(body.id)
        return entity
    }

    private var lastSyncGroupsTime: Long = 0L

    suspend fun syncGroups(): List<GroupEntity> {
        val now = System.currentTimeMillis()
        if (now - lastSyncGroupsTime < 5000L) {
            // Retrieve currently saved groups from database directly to avoid spamming the network
            return dao.observeGroups().firstOrNull() ?: emptyList()
        }
        val resp = api.listGroups().body() ?: return emptyList()
        val entities = resp.groups.map {
            GroupEntity(it.id, it.name, it.ownerUserId, it.role, it.status, it.membershipVersion, it.currentKeyVersion, it.createdAt)
        }
        entities.forEach { dao.upsertGroup(it) }
        lastSyncGroupsTime = now
        return entities
    }

    suspend fun refreshMembers(groupId: Long): List<GroupMemberEntity> {
        val resp = api.listGroupMembers(groupId).body() ?: return emptyList()
        val members = resp.members
            .filter { it.status != "removed" }
            .map { GroupMemberEntity(groupId, it.userId, it.role, it.status, it.joinedAt, it.name, it.nickname) }
        dao.clearMembers(groupId)
        dao.insertMembers(members)
        invalidateDeviceKeyCache()
        return members
    }

    suspend fun acceptInvitation(groupId: Long) {
        api.acceptGroupInvitation(groupId)
        refreshMembers(groupId)
        runCatching { pullHistoryPacket(groupId) }
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

    /** Create a new key version and upload envelopes for all active devices. */
    suspend fun rotateAndDistribute(groupId: Long): Long? {
        invalidateDeviceKeyCache()
        val resp = api.rotateGroupKey(groupId).body() ?: return null
        val version = resp.keyVersion
        val groupKey = groupCrypto.generateGroupKey()
        dao.saveGroupKey(GroupKeyEntity(groupId, version, groupKey))

        val userIds = resp.devices.map { it.userId }.distinct()
        val devices = fetchDeviceKeys(userIds)
        if (devices.isNotEmpty()) {
            api.uploadGroupEnvelopes(groupId, version, UploadEnvelopesRequest(wrapKeyForDevices(groupKey, devices, groupId, version)))
        }
        dao.getGroup(groupId)?.let { dao.upsertGroup(it.copy(currentKeyVersion = version)) }
        return version
    }

    /* ── Messaging ── */

    suspend fun sendMessage(groupId: Long, text: String): String? {
        val group = dao.getGroup(groupId)
        val version = group?.currentKeyVersion ?: currentVersion(groupId)
        val groupKey = ensureGroupKey(groupId, version) ?: return null

        val messageId = UUID.randomUUID().toString()
        // created_at is bound into the AAD and must match what the server persists
        // and relays. Server and web work in Unix seconds — sending milliseconds
        // here made the recipient's AAD mismatch and decryption fail.
        val createdAt = System.currentTimeMillis() / 1000
        val enc = groupCrypto.encryptMessage(
            text.toByteArray(Charsets.UTF_8), groupKey, groupId, version, messageId, createdAt
        )

        dao.upsertMessage(
            GroupMessageEntity(
                groupId = groupId, messageId = messageId, serverId = 0,
                senderUserId = myUserId(), senderDeviceId = myDeviceId(),
                keyVersion = version, text = text, createdAt = createdAt,
                sentByMe = true, delivered = false,
            )
        )
        ws.sendGroupMessage(groupId, messageId, version, enc.ciphertext, enc.salt, enc.nonce, createdAt)
        return messageId
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
    ): GroupMessageEntity? {
        dao.getMessage(groupId, messageId)?.let { if (it.serverId != 0L) return null }

        val groupKey = ensureGroupKey(groupId, keyVersion)
        if (groupKey == null) {
            Log.w("GroupRepo", "key unavailable for group=$groupId v=$keyVersion")
            return null
        }
        val text = runCatching {
            String(
                groupCrypto.decryptMessage(ciphertext, groupKey, salt, nonce, groupId, keyVersion, messageId, createdAt),
                Charsets.UTF_8,
            )
        }.getOrElse {
            Log.e("GroupRepo", "decrypt/AAD failed for group=$groupId msg=$messageId")
            return null
        }
        val entity = GroupMessageEntity(
            groupId = groupId, messageId = messageId, serverId = id,
            senderUserId = senderUserId, senderDeviceId = senderDeviceId,
            keyVersion = keyVersion, text = text, createdAt = createdAt,
            sentByMe = senderUserId == myUserId(), delivered = true,
        )
        dao.upsertMessage(entity)
        ws.sendGroupDelivered(id)
        return entity
    }

    suspend fun onAck(groupId: Long, messageId: String, serverId: Long) {
        dao.acknowledgeMessage(groupId, messageId, serverId)
    }

    /* ── Offline history sync (ciphertext) ── */

    suspend fun syncHistory(groupId: Long) {
        // Decrypting envelopes needs the sender's identity key, resolved via the
        // member list. If members haven't been fetched yet, do it now so history
        // isn't silently dropped as undecryptable.
        if (dao.getMembers(groupId).isEmpty()) {
            runCatching { refreshMembers(groupId) }
        }
        var cursor: Long? = null
        do {
            val page = api.getGroupHistory(groupId, 100, cursor).body() ?: return
            for (m in page.messages) {
                dao.getMessage(groupId, m.messageId)?.let { if (it.serverId != 0L) return@let }
                handleIncoming(
                    groupId, m.id, m.messageId, m.senderUserId, m.senderDeviceId, m.keyVersion,
                    Base64.decode(m.ciphertext, urlB64Flags),
                    Base64.decode(m.salt, urlB64Flags),
                    Base64.decode(m.nonce, urlB64Flags),
                    m.createdAt,
                )
            }
            cursor = page.nextCursor?.toLongOrNull()
        } while (cursor != null)
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
