package niel.kro.penik.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import niel.kro.penik.data.crypto.E2EECrypto
import niel.kro.penik.data.crypto.SafetyNumber
import niel.kro.penik.data.local.dao.ChatDao
import niel.kro.penik.data.local.dao.GroupDao
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.entity.ChatEntity
import niel.kro.penik.data.local.entity.GroupEntity
import niel.kro.penik.data.local.entity.GroupKeyEntity
import niel.kro.penik.data.local.entity.GroupMemberEntity
import niel.kro.penik.data.local.entity.GroupMessageEntity
import niel.kro.penik.data.local.entity.MessageEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    private val tokenStorage: SecureTokenStorage,
    private val e2eeCrypto: E2EECrypto,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val groupDao: GroupDao
) {
    data class ImportSummary(
        val chatsCount: Int,
        val messagesCount: Int,
        val groupsCount: Int,
        val groupMessagesCount: Int
    )

    fun generateMnemonicPhrase(wordCount: Int = 12): String {
        return SafetyNumber.generateMnemonicPhrase(wordCount)
    }

    suspend fun exportHistory(passphrase: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPassphrase = passphrase.trim()
            if (normalizedPassphrase.isBlank()) {
                throw IllegalArgumentException("Пароль или мнемоническая фраза не могут быть пустыми")
            }

            val userId = tokenStorage.getUserId()
            val privKey = tokenStorage.getPrivateKey()
            val pubKey = tokenStorage.getPublicKey()

            val chats = chatDao.getChatsSnapshot()
            val messages = messageDao.getAllMessages()
            val groups = groupDao.getAllGroups()
            val members = groupDao.getAllMembers()
            val groupKeys = groupDao.getAllKeys()
            val groupMessages = groupDao.getAllMessages()

            val innerData = JSONObject().apply {
                put("user_id", userId)
                put("exported_at", System.currentTimeMillis())
                if (privKey != null) {
                    put("private_key", Base64.getEncoder().encodeToString(privKey))
                }
                if (pubKey != null) {
                    put("public_key", Base64.getEncoder().encodeToString(pubKey))
                }

                // Chats
                val chatsArr = JSONArray()
                chats.forEach { c ->
                    chatsArr.put(JSONObject().apply {
                        put("user_id", c.userId)
                        put("name", c.name)
                        put("nickname", c.nickname)
                        put("last_message", c.lastMessage.orEmpty())
                        put("last_message_timestamp", c.lastMessageTimestamp ?: 0L)
                        put("unread_count", c.unreadCount)
                        put("avatar_url", c.avatarUrl.orEmpty())
                    })
                }
                put("chats", chatsArr)

                // Messages
                val msgsArr = JSONArray()
                messages.forEach { m ->
                    msgsArr.put(JSONObject().apply {
                        put("local_id", m.localId)
                        put("server_id", m.serverId ?: 0L)
                        put("chat_user_id", m.chatUserId)
                        put("sender_id", m.senderId)
                        put("text", m.text)
                        put("timestamp", m.timestamp)
                        put("edited_at", m.editedAt ?: 0L)
                        put("delivered", m.delivered)
                        put("read", m.read)
                        put("sent_by_me", m.sentByMe)
                        put("reply_to_msg_id", m.replyToMsgId.orEmpty())
                    })
                }
                put("messages", msgsArr)

                // Groups
                val groupsArr = JSONArray()
                groups.forEach { g ->
                    groupsArr.put(JSONObject().apply {
                        put("id", g.id)
                        put("name", g.name)
                        put("owner_user_id", g.ownerUserId)
                        put("role", g.role.orEmpty())
                        put("status", g.status)
                        put("membership_version", g.membershipVersion)
                        put("current_key_version", g.currentKeyVersion)
                        put("created_at", g.createdAt)
                    })
                }
                put("groups", groupsArr)

                // Group Members
                val membersArr = JSONArray()
                members.forEach { mem ->
                    membersArr.put(JSONObject().apply {
                        put("group_id", mem.groupId)
                        put("user_id", mem.userId)
                        put("role", mem.role)
                        put("status", mem.status)
                        put("joined_at", mem.joinedAt)
                        put("name", mem.name)
                        put("nickname", mem.nickname)
                        put("online", mem.online)
                        put("last_seen", mem.lastSeen)
                    })
                }
                put("group_members", membersArr)

                // Group Keys
                val keysArr = JSONArray()
                groupKeys.forEach { gk ->
                    keysArr.put(JSONObject().apply {
                        put("group_id", gk.groupId)
                        put("key_version", gk.keyVersion)
                        put("key_bytes", Base64.getEncoder().encodeToString(gk.key))
                    })
                }
                put("group_keys", keysArr)

                // Group Messages
                val gMsgsArr = JSONArray()
                groupMessages.forEach { gm ->
                    gMsgsArr.put(JSONObject().apply {
                        put("group_id", gm.groupId)
                        put("message_id", gm.messageId)
                        put("server_id", gm.serverId)
                        put("sender_user_id", gm.senderUserId)
                        put("sender_device_id", gm.senderDeviceId)
                        put("key_version", gm.keyVersion)
                        put("text", gm.text)
                        put("created_at", gm.createdAt)
                        put("delivered", gm.delivered)
                        put("sent_by_me", gm.sentByMe)
                        put("reply_to_msg_id", gm.replyToMsgId.orEmpty())
                        put("edited_at", gm.editedAt ?: 0L)
                    })
                }
                put("group_messages", gMsgsArr)
            }

            val rawBytes = innerData.toString().toByteArray(Charsets.UTF_8)
            val backup = e2eeCrypto.encryptKeyBackup(rawBytes, normalizedPassphrase)

            val outerBackup = JSONObject().apply {
                put("penik_backup_version", 1)
                put("created_at", System.currentTimeMillis())
                put("platform", "Android")
                put("salt", Base64.getEncoder().encodeToString(backup.salt))
                put("iv", Base64.getEncoder().encodeToString(backup.iv))
                put("encrypted_data", Base64.getEncoder().encodeToString(backup.encryptedBlob))
            }

            outerBackup.toString(2)
        }
    }

    suspend fun importHistory(backupJsonString: String, passphrase: String): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPassphrase = passphrase.trim()
            if (normalizedPassphrase.isBlank()) {
                throw IllegalArgumentException("Пароль или мнемоническая фраза не могут быть пустыми")
            }

            val outer = JSONObject(backupJsonString)
            val saltB64 = outer.optString("salt")
            val ivB64 = outer.optString("iv")
            val dataB64 = outer.optString("encrypted_data")

            if (saltB64.isBlank() || ivB64.isBlank() || dataB64.isBlank()) {
                throw IllegalArgumentException("Неверный формат файла резервной копии Penik")
            }

            val salt = Base64.getDecoder().decode(saltB64)
            val iv = Base64.getDecoder().decode(ivB64)
            val encData = Base64.getDecoder().decode(dataB64)

            val decryptedBytes = e2eeCrypto.decryptKeyBackup(encData, salt, iv, normalizedPassphrase)
            val innerJson = JSONObject(String(decryptedBytes, Charsets.UTF_8))

            // Restore identity key if not present
            val privKeyB64 = innerJson.optString("private_key")
            if (privKeyB64.isNotBlank() && tokenStorage.getPrivateKey() == null) {
                val privKey = Base64.getDecoder().decode(privKeyB64)
                val pubKey = e2eeCrypto.derivePublicKey(privKey)
                tokenStorage.savePrivateKey(privKey)
                tokenStorage.savePublicKey(pubKey)
            }

            // Restore Chats
            val chatsArr = innerJson.optJSONArray("chats")
            val chatsList = mutableListOf<ChatEntity>()
            if (chatsArr != null) {
                for (i in 0 until chatsArr.length()) {
                    val o = chatsArr.optJSONObject(i) ?: continue
                    chatsList.add(
                        ChatEntity(
                            userId = o.getLong("user_id"),
                            name = o.optString("name"),
                            nickname = o.optString("nickname"),
                            lastMessage = o.optString("last_message").takeIf { it.isNotBlank() },
                            lastMessageTimestamp = o.optLong("last_message_timestamp").takeIf { it > 0 },
                            unreadCount = o.optInt("unread_count", 0),
                            avatarUrl = o.optString("avatar_url").takeIf { it.isNotBlank() }
                        )
                    )
                }
                if (chatsList.isNotEmpty()) {
                    chatDao.insertChats(chatsList)
                }
            }

            // Restore Messages
            val msgsArr = innerJson.optJSONArray("messages")
            val msgsList = mutableListOf<MessageEntity>()
            if (msgsArr != null) {
                for (i in 0 until msgsArr.length()) {
                    val o = msgsArr.optJSONObject(i) ?: continue
                    msgsList.add(
                        MessageEntity(
                            localId = o.optString("local_id").ifBlank { java.util.UUID.randomUUID().toString() },
                            serverId = o.optLong("server_id").takeIf { it > 0 },
                            chatUserId = o.getLong("chat_user_id"),
                            senderId = o.getLong("sender_id"),
                            text = o.optString("text"),
                            timestamp = o.getLong("timestamp"),
                            editedAt = o.optLong("edited_at").takeIf { it > 0 },
                            delivered = o.optBoolean("delivered", true),
                            read = o.optBoolean("read", true),
                            sentByMe = o.optBoolean("sent_by_me", false),
                            replyToMsgId = o.optString("reply_to_msg_id").takeIf { it.isNotBlank() }
                        )
                    )
                }
                if (msgsList.isNotEmpty()) {
                    messageDao.insertMessages(msgsList)
                }
            }

            // Restore Groups
            val groupsArr = innerJson.optJSONArray("groups")
            val groupsList = mutableListOf<GroupEntity>()
            if (groupsArr != null) {
                for (i in 0 until groupsArr.length()) {
                    val o = groupsArr.optJSONObject(i) ?: continue
                    val gId = o.getLong("id")
                    if (gId > 0) {
                        groupsList.add(
                            GroupEntity(
                                id = gId,
                                name = o.optString("name"),
                                ownerUserId = o.optLong("owner_user_id", tokenStorage.getUserId()),
                                role = o.optString("role").takeIf { it.isNotBlank() },
                                status = o.optString("status", "active"),
                                membershipVersion = o.optLong("membership_version", 1L),
                                currentKeyVersion = o.optLong("current_key_version", 1L),
                                createdAt = o.optLong("created_at", System.currentTimeMillis())
                            )
                        )
                    }
                }
                if (groupsList.isNotEmpty()) {
                    groupDao.insertGroups(groupsList)
                }
            }

            // Restore Group Members
            val membersArr = innerJson.optJSONArray("group_members")
            val membersList = mutableListOf<GroupMemberEntity>()
            if (membersArr != null) {
                for (i in 0 until membersArr.length()) {
                    val o = membersArr.optJSONObject(i) ?: continue
                    val gId = o.getLong("group_id")
                    val uId = o.getLong("user_id")
                    if (gId > 0 && uId > 0) {
                        membersList.add(
                            GroupMemberEntity(
                                groupId = gId,
                                userId = uId,
                                role = o.optString("role", "member"),
                                status = o.optString("status", "active"),
                                joinedAt = o.optLong("joined_at", 0L),
                                name = o.optString("name"),
                                nickname = o.optString("nickname"),
                                online = o.optBoolean("online", false),
                                lastSeen = o.optLong("last_seen", 0L)
                            )
                        )
                    }
                }
                if (membersList.isNotEmpty()) {
                    groupDao.insertMembers(membersList)
                }
            }

            // Restore Group Keys
            val keysArr = innerJson.optJSONArray("group_keys")
            val keysList = mutableListOf<GroupKeyEntity>()
            if (keysArr != null) {
                for (i in 0 until keysArr.length()) {
                    val o = keysArr.optJSONObject(i) ?: continue
                    val gId = o.getLong("group_id")
                    val ver = o.getLong("key_version")
                    val kb = o.optString("key_bytes")
                    if (gId > 0 && ver > 0 && kb.isNotBlank()) {
                        keysList.add(
                            GroupKeyEntity(
                                groupId = gId,
                                keyVersion = ver,
                                key = Base64.getDecoder().decode(kb)
                            )
                        )
                    }
                }
                if (keysList.isNotEmpty()) {
                    groupDao.saveGroupKeys(keysList)
                }
            }

            // Restore Group Messages
            val gMsgsArr = innerJson.optJSONArray("group_messages")
            val gMsgsList = mutableListOf<GroupMessageEntity>()
            if (gMsgsArr != null) {
                for (i in 0 until gMsgsArr.length()) {
                    val o = gMsgsArr.optJSONObject(i) ?: continue
                    val gId = o.getLong("group_id")
                    if (gId > 0) {
                        gMsgsList.add(
                            GroupMessageEntity(
                                groupId = gId,
                                messageId = o.optString("message_id").ifBlank { java.util.UUID.randomUUID().toString() },
                                serverId = o.optLong("server_id", 0L),
                                senderUserId = o.getLong("sender_user_id"),
                                senderDeviceId = o.optLong("sender_device_id", 0L),
                                keyVersion = o.optLong("key_version", 1L),
                                text = o.optString("text"),
                                createdAt = o.getLong("created_at"),
                                delivered = o.optBoolean("delivered", true),
                                sentByMe = o.optBoolean("sent_by_me", false),
                                replyToMsgId = o.optString("reply_to_msg_id").takeIf { it.isNotBlank() },
                                editedAt = o.optLong("edited_at").takeIf { it > 0 }
                            )
                        )
                    }
                }
                if (gMsgsList.isNotEmpty()) {
                    groupDao.insertGroupMessages(gMsgsList)
                }
            }

            ImportSummary(
                chatsCount = chatsList.size,
                messagesCount = msgsList.size,
                groupsCount = groupsList.size,
                groupMessagesCount = gMsgsList.size
            )
        }
    }
}
