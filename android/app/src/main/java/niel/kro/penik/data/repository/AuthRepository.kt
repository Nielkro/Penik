package niel.kro.penik.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.DeviceResponse
import niel.kro.penik.data.network.api.LoginRequestBody
import niel.kro.penik.data.network.api.RegisterRequestBody
import niel.kro.penik.domain.model.AuthResponse
import niel.kro.penik.data.crypto.E2EECrypto
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

import niel.kro.penik.data.local.database.PenikDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Serializable
private data class ErrorBody(val message: String? = null, val error: String? = null)

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val tokenStorage: SecureTokenStorage,
    private val e2eeCrypto: E2EECrypto,
    private val identityPins: niel.kro.penik.data.crypto.IdentityPinStore,
    private val database: PenikDatabase,
    private val groupDao: niel.kro.penik.data.local.dao.GroupDao,
    private val messageRepositoryProvider: Provider<MessageRepository>
) {
    private val json = Json { ignoreUnknownKeys = true }

    // The identity keypair must be stable for the life of the install. The server
    // does INSERT OR REPLACE on the uploaded public key, so regenerating it on
    // every login silently rotates this device's identity key — after which every
    // group-key envelope (wrapped by the sender for the OLD public key) and 1:1
    // session fails to decrypt. Reuse the persisted pair; only generate once.
    fun generateAndSaveKeys(): Pair<ByteArray, ByteArray> {
        val generated = e2eeCrypto.generateX25519KeyPair()
        tokenStorage.savePrivateKey(generated.first)
        tokenStorage.savePublicKey(generated.second)
        return generated
    }

    private fun stableIdentityKeyPair(): Pair<ByteArray, ByteArray> {
        val priv = tokenStorage.getPrivateKey()
        val pub = tokenStorage.getPublicKey()
        if (priv != null && pub != null) return Pair(priv, pub)
        return generateAndSaveKeys()
    }

    fun generateAndSaveSigningKeys(): Pair<ByteArray, ByteArray> {
        val raw = if (niel.kro.penik.data.crypto.RustCryptoCore.isAvailable()) {
            niel.kro.penik.data.crypto.RustCryptoCore.generateSigningKeyPair()
        } else null
        val (vk, sk) = if (raw != null && raw.size == 64) {
            val v = raw.copyOfRange(0, 32)
            val s = raw.copyOfRange(32, 64)
            Pair(v, s)
        } else {
            Pair(ByteArray(32), ByteArray(32))
        }
        tokenStorage.saveSigningPrivateKey(sk)
        tokenStorage.saveSigningPublicKey(vk)
        return Pair(sk, vk)
    }

    fun stableSigningKeyPair(): Pair<ByteArray, ByteArray> {
        val priv = tokenStorage.getSigningPrivateKey()
        val pub = tokenStorage.getSigningPublicKey()
        if (priv != null && pub != null) return Pair(priv, pub)
        return generateAndSaveSigningKeys()
    }

    // clientPlatform reports the Android OS version, e.g. "Android 14", so the
    // devices screen can show a readable platform instead of a raw model code.
    private fun clientPlatform(): String {
        val release = android.os.Build.VERSION.RELEASE ?: ""
        return if (release.isBlank()) "Android" else "Android $release"
    }

    // clientLocation derives a coarse location from the device time zone,
    // e.g. "Europe/Moscow" becomes "Moscow", avoiding a location permission
    // while still giving a recognizable place hint.
    private fun clientLocation(): String {
        val tz = java.util.TimeZone.getDefault().id ?: ""
        if (tz.isBlank()) return ""
        return tz.substringAfterLast('/').replace('_', ' ')
    }

    suspend fun login(nickname: String, password: String, deviceName: String): Result<AuthResponse> {
        return try {
            val (privateKey, publicKey) = stableIdentityKeyPair()
            val (signingPriv, signingPub) = stableSigningKeyPair()
            val ikPubBase64 = Base64.getEncoder().encodeToString(publicKey)
            val signingKeyBase64 = Base64.getEncoder().encodeToString(signingPub)

            val response = apiService.login(
                LoginRequestBody(
                    nickname = nickname,
                    password = password,
                    deviceName = deviceName,
                    platform = clientPlatform(),
                    location = clientLocation(),
                    cryptoVersion = 2,
                    ikPub = ikPubBase64,
                    signingKey = signingKeyBase64
                )
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                var finalDeviceId = body.deviceId

                if (body.rebindRequired && body.targetDeviceId > 0L) {
                    try {
                        tokenStorage.saveAuth(body.token, body.userId, body.deviceId)

                        val challengeResp = apiService.deviceChallenge(
                            niel.kro.penik.data.network.api.DeviceChallengeRequest(
                                targetDeviceId = body.targetDeviceId,
                                ikPub = ikPubBase64
                            )
                        )
                        if (challengeResp.isSuccessful) {
                            val challenge = challengeResp.body()!!
                            val ephPubBytes = Base64.getDecoder().decode(challenge.ephPub)
                            val nonceBytes = Base64.getDecoder().decode(challenge.nonce)

                            val proofBytes = if (niel.kro.penik.data.crypto.RustCryptoCore.isAvailable()) {
                                niel.kro.penik.data.crypto.RustCryptoCore.computeDeviceRebindProof(
                                    privateKey,
                                    ephPubBytes,
                                    nonceBytes,
                                    body.userId,
                                    body.targetDeviceId
                                )
                            } else null

                            if (proofBytes != null && proofBytes.size == 32) {
                                val proofB64 = Base64.getEncoder().encodeToString(proofBytes)
                                val rebindResp = apiService.deviceRebind(
                                    niel.kro.penik.data.network.api.DeviceRebindRequest(
                                        deviceId = body.targetDeviceId,
                                        nonce = challenge.nonce,
                                        proof = proofB64
                                    )
                                )
                                if (rebindResp.isSuccessful && rebindResp.body()?.success == true) {
                                    finalDeviceId = rebindResp.body()!!.deviceId
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AuthRepository", "Device rebind failed: ${e.message}")
                    }
                }

                val prevUserId = tokenStorage.getUserId()
                if (prevUserId > 0L && prevUserId != body.userId) {
                    try { database.clearAllTables() } catch (_: Exception) {}
                }
                tokenStorage.saveAuth(body.token, body.userId, finalDeviceId)
                fetchAndSaveUserProfile(body.userId)
                
                // Upload FCM token if exists
                tokenStorage.getFcmToken()?.let { fcmToken ->
                    if (tokenStorage.getLastUploadedFcmToken() != fcmToken) {
                        runCatching {
                            val resp = apiService.updateFcmToken(niel.kro.penik.data.network.api.FcmTokenRequestBody(fcmToken))
                            if (resp.isSuccessful) {
                                tokenStorage.saveLastUploadedFcmToken(fcmToken)
                            }
                        }
                    }
                }

                Result.success(AuthResponse(body.token, body.userId, finalDeviceId))
            } else {
                val msg = parseServerError(response.code(), response.errorBody()?.string())
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    suspend fun register(name: String, nickname: String, password: String, deviceName: String): Result<AuthResponse> {
        return try {
            val (privateKey, publicKey) = stableIdentityKeyPair()
            val (signingPriv, signingPub) = stableSigningKeyPair()
            val ikPubBase64 = Base64.getEncoder().encodeToString(publicKey)
            val signingKeyBase64 = Base64.getEncoder().encodeToString(signingPub)

            val response = apiService.register(
                RegisterRequestBody(
                    name = name,
                    nickname = nickname,
                    password = password,
                    deviceName = deviceName,
                    platform = clientPlatform(),
                    location = clientLocation(),
                    cryptoVersion = 2,
                    ikPub = ikPubBase64,
                    signingKey = signingKeyBase64
                )
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                val prevUserId = tokenStorage.getUserId()
                if (prevUserId > 0L && prevUserId != body.userId) {
                    try { database.clearAllTables() } catch (_: Exception) {}
                }
                tokenStorage.saveAuth(body.token, body.userId, body.deviceId)
                tokenStorage.saveUserProfile(name, nickname)

                // Upload FCM token if exists
                tokenStorage.getFcmToken()?.let { fcmToken ->
                    if (tokenStorage.getLastUploadedFcmToken() != fcmToken) {
                        runCatching {
                            val resp = apiService.updateFcmToken(niel.kro.penik.data.network.api.FcmTokenRequestBody(fcmToken))
                            if (resp.isSuccessful) {
                                tokenStorage.saveLastUploadedFcmToken(fcmToken)
                            }
                        }
                    }
                }

                Result.success(AuthResponse(body.token, body.userId, body.deviceId))
            } else {
                val msg = parseServerError(response.code(), response.errorBody()?.string())
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    // listDevices returns the authenticated user's devices, ordered by last seen.
    suspend fun listDevices(): Result<List<DeviceResponse>> {
        return try {
            val response = apiService.listDevices()
            if (response.isSuccessful) {
                Result.success(response.body() ?: emptyList())
            } else {
                Result.failure(Exception(parseServerError(response.code(), response.errorBody()?.string())))
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    private suspend fun fetchAndSaveUserProfile(userId: Long) {
        try {
            val response = apiService.getMe()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    tokenStorage.saveUserProfile(body.name, body.nickname)
                    return
                }
            }
        } catch (_: Exception) {}

        try {
            val response = apiService.getUserProfile(userId)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    tokenStorage.saveUserProfile(body.name, body.nickname)
                }
            }
        } catch (_: Exception) {}
    }

    fun getName(): String = tokenStorage.getName()
    fun getNickname(): String = tokenStorage.getNickname()
    fun isLoggedIn(): Boolean = tokenStorage.isLoggedIn()
    fun getToken(): String? = tokenStorage.getToken()
    fun getUserId(): Long = tokenStorage.getUserId()

    fun logout() {
        tokenStorage.clear()
        // Pins are trust in peers as seen by *this* identity; keeping them past a
        // logout would warn about a "changed" key on every fresh login.
        identityPins.clear()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                database.clearAllTables()
            } catch (_: Exception) {}
        }
    }

    suspend fun uploadKeyBackup(passphrase: String): Result<Unit> {
        return try {
            val privateKey = tokenStorage.getPrivateKey() ?: return Result.failure(Exception("Локальный приватный ключ не найден"))
            val allGroupKeys = groupDao.getAllKeys()
            val groupKeysArr = org.json.JSONArray().apply {
                allGroupKeys.forEach { gk ->
                    put(org.json.JSONObject().apply {
                        put("group_id", gk.groupId)
                        put("version", gk.keyVersion)
                        put("key", Base64.getEncoder().encodeToString(gk.key))
                    })
                }
            }
            val allGroupMessages = groupDao.getAllMessages()
            val groupMessagesArr = org.json.JSONArray().apply {
                allGroupMessages
                    .sortedByDescending { it.createdAt }
                    .take(3000)
                    .forEach { gm ->
                        put(org.json.JSONObject().apply {
                            put("group_id", gm.groupId)
                            put("message_id", gm.messageId)
                            put("server_id", gm.serverId)
                            put("sender_user_id", gm.senderUserId)
                            put("sender_device_id", gm.senderDeviceId)
                            put("key_version", gm.keyVersion)
                            put("text", gm.text)
                            put("created_at", gm.createdAt)
                            put("sent_by_me", gm.sentByMe)
                            put("delivered", gm.delivered)
                            if (gm.replyToMsgId != null) put("reply_to_msg_id", gm.replyToMsgId)
                            if (gm.editedAt != null) put("edited_at", gm.editedAt)
                        })
                    }
            }
            val currentDeviceId = tokenStorage.getDeviceId()
            val payloadBytes = org.json.JSONObject().apply {
                put("version", 3)
                if (currentDeviceId > 0L) {
                    put("device_id", currentDeviceId)
                }
                put("identity_key", Base64.getEncoder().encodeToString(privateKey))
                put("group_keys", groupKeysArr)
                put("group_messages", groupMessagesArr)
            }.toString().toByteArray(Charsets.UTF_8)

            val backup = e2eeCrypto.encryptKeyBackup(payloadBytes, passphrase)
            val b64Blob = Base64.getEncoder().encodeToString(backup.encryptedBlob)
            val b64Salt = Base64.getEncoder().encodeToString(backup.salt)
            val b64Iv = Base64.getEncoder().encodeToString(backup.iv)

            val response = apiService.uploadKeyBackup(
                niel.kro.penik.data.network.api.KeyBackupRequest(
                    encryptedBlob = b64Blob,
                    salt = b64Salt,
                    iv = b64Iv,
                    deviceName = niel.kro.penik.ui.util.DeviceUtils.getDeviceMarketingName(),
                    platform = clientPlatform()
                )
            )
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(parseServerError(response.code(), response.errorBody()?.string())))
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    suspend fun hasKeyBackup(): Boolean {
        return try {
            val response = apiService.getKeyBackup()
            response.isSuccessful && response.body()?.encryptedBlob?.isNotBlank() == true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun listKeyBackups(): Result<List<niel.kro.penik.data.network.api.KeyBackupSummaryResponse>> {
        return try {
            val response = apiService.listKeyBackups()
            if (response.isSuccessful) {
                Result.success(response.body().orEmpty())
            } else {
                Result.failure(Exception(parseServerError(response.code(), response.errorBody()?.string())))
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    suspend fun getKeyBackup(backupId: Long? = null, deviceId: Long? = null): Result<niel.kro.penik.data.network.api.KeyBackupResponse> {
        return try {
            val response = apiService.getKeyBackup(id = backupId, deviceId = deviceId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                if (response.code() == 404) {
                    Result.failure(Exception("Резервная копия ключей не найдена на сервере"))
                } else {
                    Result.failure(Exception(parseServerError(response.code(), response.errorBody()?.string())))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    suspend fun restoreKeyBackup(passphrase: String, backupId: Long? = null, deviceId: Long? = null): Result<Unit> {
        return try {
            val response = apiService.getKeyBackup(id = backupId, deviceId = deviceId)
            if (response.isSuccessful) {
                val body = response.body()!!
                val blob = Base64.getDecoder().decode(body.encryptedBlob)
                val salt = Base64.getDecoder().decode(body.salt)
                val iv = Base64.getDecoder().decode(body.iv)

                val decryptedBytes = e2eeCrypto.decryptKeyBackup(blob, salt, iv, passphrase)
                val isJson = decryptedBytes.isNotEmpty() && decryptedBytes[0] == '{'.code.toByte()
                var jsonDeviceId: Long? = null
                val privKey = if (isJson) {
                    val root = org.json.JSONObject(String(decryptedBytes, Charsets.UTF_8))
                    if (root.has("device_id") && !root.isNull("device_id")) {
                        jsonDeviceId = root.optLong("device_id", 0L).takeIf { it > 0L }
                    }
                    val idKeyB64 = root.optString("identity_key")
                    val k = Base64.getDecoder().decode(idKeyB64)
                    val groupKeysArr = root.optJSONArray("group_keys")
                    if (groupKeysArr != null) {
                        val keysToInsert = mutableListOf<niel.kro.penik.data.local.entity.GroupKeyEntity>()
                        for (i in 0 until groupKeysArr.length()) {
                            val gkObj = groupKeysArr.optJSONObject(i) ?: continue
                            val gId = gkObj.optLong("group_id")
                            val gVer = gkObj.optLong("version")
                            val gKeyB64 = gkObj.optString("key")
                            if (gId > 0 && gVer > 0 && gKeyB64.isNotBlank()) {
                                val gKeyBytes = Base64.getDecoder().decode(gKeyB64)
                                keysToInsert.add(niel.kro.penik.data.local.entity.GroupKeyEntity(gId, gVer, gKeyBytes))
                            }
                        }
                        if (keysToInsert.isNotEmpty()) {
                            groupDao.saveGroupKeys(keysToInsert)
                        }
                    }
                    val groupMessagesArr = root.optJSONArray("group_messages")
                    if (groupMessagesArr != null) {
                        val msgsToInsert = mutableListOf<niel.kro.penik.data.local.entity.GroupMessageEntity>()
                        for (i in 0 until groupMessagesArr.length()) {
                            val gmObj = groupMessagesArr.optJSONObject(i) ?: continue
                            val gId = gmObj.optLong("group_id")
                            val mId = gmObj.optString("message_id")
                            if (gId > 0 && mId.isNotBlank()) {
                                msgsToInsert.add(
                                    niel.kro.penik.data.local.entity.GroupMessageEntity(
                                        groupId = gId,
                                        messageId = mId,
                                        serverId = gmObj.optLong("server_id", 0L),
                                        senderUserId = gmObj.optLong("sender_user_id"),
                                        senderDeviceId = gmObj.optLong("sender_device_id", 0L),
                                        keyVersion = gmObj.optLong("key_version", 1L),
                                        text = gmObj.optString("text"),
                                        createdAt = gmObj.optLong("created_at"),
                                        sentByMe = gmObj.optBoolean("sent_by_me", false),
                                        delivered = gmObj.optBoolean("delivered", true),
                                        replyToMsgId = if (gmObj.has("reply_to_msg_id") && !gmObj.isNull("reply_to_msg_id")) gmObj.optString("reply_to_msg_id") else null,
                                        editedAt = if (gmObj.has("edited_at") && !gmObj.isNull("edited_at")) gmObj.optLong("edited_at") else null
                                    )
                                )
                            }
                        }
                        if (msgsToInsert.isNotEmpty()) {
                            groupDao.insertGroupMessages(msgsToInsert)
                        }
                    }
                    k
                } else {
                    decryptedBytes
                }

                val derivedPubKey = e2eeCrypto.derivePublicKey(privKey)

                tokenStorage.savePrivateKey(privKey)
                tokenStorage.savePublicKey(derivedPubKey)

                // Rebind session to original device_id if restored identity key belongs to an existing device
                val currentDeviceId = tokenStorage.getDeviceId()
                val userId = tokenStorage.getUserId()
                val derivedPubKeyB64 = Base64.getEncoder().encodeToString(derivedPubKey)

                if (userId > 0L) {
                    try {
                        val requestedTargetId = (body.deviceId?.takeIf { it > 0L }) ?: (jsonDeviceId?.takeIf { it > 0L }) ?: 0L
                        val challengeResp = apiService.deviceChallenge(
                            niel.kro.penik.data.network.api.DeviceChallengeRequest(
                                targetDeviceId = requestedTargetId,
                                ikPub = derivedPubKeyB64
                            )
                        )
                        if (challengeResp.isSuccessful && challengeResp.body() != null) {
                            val challenge = challengeResp.body()!!
                            val resolvedTargetDeviceId = if (challenge.targetDeviceId > 0L) challenge.targetDeviceId else requestedTargetId

                            if (resolvedTargetDeviceId > 0L && resolvedTargetDeviceId != currentDeviceId) {
                                val ephPubBytes = Base64.getDecoder().decode(challenge.ephPub)
                                val nonceBytes = Base64.getDecoder().decode(challenge.nonce)

                                val proofBytes = if (niel.kro.penik.data.crypto.RustCryptoCore.isAvailable()) {
                                    niel.kro.penik.data.crypto.RustCryptoCore.computeDeviceRebindProof(
                                        privKey,
                                        ephPubBytes,
                                        nonceBytes,
                                        userId,
                                        resolvedTargetDeviceId
                                    )
                                } else null

                                if (proofBytes != null && proofBytes.size == 32) {
                                    val proofB64 = Base64.getEncoder().encodeToString(proofBytes)
                                    val rebindResp = apiService.deviceRebind(
                                        niel.kro.penik.data.network.api.DeviceRebindRequest(
                                            deviceId = resolvedTargetDeviceId,
                                            nonce = challenge.nonce,
                                            proof = proofB64
                                        )
                                    )
                                    if (rebindResp.isSuccessful && rebindResp.body()?.success == true) {
                                        val token = tokenStorage.getToken() ?: ""
                                        val remappedDeviceId = rebindResp.body()?.deviceId ?: resolvedTargetDeviceId
                                        tokenStorage.saveAuth(token, userId, remappedDeviceId)
                                        android.util.Log.i("AuthRepository", "Device successfully re-bound to $remappedDeviceId")
                                    } else {
                                        android.util.Log.w("AuthRepository", "Device rebind rejected by server: ${rebindResp.code()} ${rebindResp.errorBody()?.string()}")
                                    }
                                } else {
                                    android.util.Log.w("AuthRepository", "Failed to compute device rebind proof")
                                }
                            }
                        } else {
                            android.util.Log.w("AuthRepository", "Device challenge request failed: ${challengeResp.code()} ${challengeResp.errorBody()?.string()}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AuthRepository", "Device rebind after restore failed: ${e.message}", e)
                    }
                }

                // Pull message history for the re-bound device
                try {
                    database.messageDao().deleteAllUndecryptedMessages()
                    messageRepositoryProvider.get().syncHistory()
                } catch (e: Exception) {
                    android.util.Log.w("AuthRepository", "Failed to sync message history after restore: ${e.message}")
                }

                Result.success(Unit)
            } else {
                if (response.code() == 404) {
                    Result.failure(Exception("Резервная копия ключей не найдена на сервере"))
                } else {
                    Result.failure(Exception(parseServerError(response.code(), response.errorBody()?.string())))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    suspend fun resetKeyBackup(newPassphrase: String): Result<Unit> {
        return try {
            val generated = e2eeCrypto.generateX25519KeyPair()
            val privateKey = generated.first
            val publicKey = generated.second

            val allGroupKeys = groupDao.getAllKeys()
            val groupKeysArr = org.json.JSONArray().apply {
                allGroupKeys.forEach { gk ->
                    put(org.json.JSONObject().apply {
                        put("group_id", gk.groupId)
                        put("version", gk.keyVersion)
                        put("key", Base64.getEncoder().encodeToString(gk.key))
                    })
                }
            }
            val allGroupMessages = groupDao.getAllMessages()
            val groupMessagesArr = org.json.JSONArray().apply {
                allGroupMessages
                    .sortedByDescending { it.createdAt }
                    .take(3000)
                    .forEach { gm ->
                        put(org.json.JSONObject().apply {
                            put("group_id", gm.groupId)
                            put("message_id", gm.messageId)
                            put("server_id", gm.serverId)
                            put("sender_user_id", gm.senderUserId)
                            put("sender_device_id", gm.senderDeviceId)
                            put("key_version", gm.keyVersion)
                            put("text", gm.text)
                            put("created_at", gm.createdAt)
                            put("sent_by_me", gm.sentByMe)
                            put("delivered", gm.delivered)
                            if (gm.replyToMsgId != null) put("reply_to_msg_id", gm.replyToMsgId)
                            if (gm.editedAt != null) put("edited_at", gm.editedAt)
                        })
                    }
            }
            val currentDeviceId = tokenStorage.getDeviceId()
            val payloadBytes = org.json.JSONObject().apply {
                put("version", 3)
                if (currentDeviceId > 0L) {
                    put("device_id", currentDeviceId)
                }
                put("identity_key", Base64.getEncoder().encodeToString(privateKey))
                put("group_keys", groupKeysArr)
                put("group_messages", groupMessagesArr)
            }.toString().toByteArray(Charsets.UTF_8)

            val backup = e2eeCrypto.encryptKeyBackup(payloadBytes, newPassphrase)
            val b64Blob = Base64.getEncoder().encodeToString(backup.encryptedBlob)
            val b64Salt = Base64.getEncoder().encodeToString(backup.salt)
            val b64Iv = Base64.getEncoder().encodeToString(backup.iv)

            val response = apiService.uploadKeyBackup(
                niel.kro.penik.data.network.api.KeyBackupRequest(
                    encryptedBlob = b64Blob,
                    salt = b64Salt,
                    iv = b64Iv,
                    deviceName = niel.kro.penik.ui.util.DeviceUtils.getDeviceMarketingName(),
                    platform = clientPlatform()
                )
            )
            if (response.isSuccessful) {
                tokenStorage.savePrivateKey(privateKey)
                tokenStorage.savePublicKey(publicKey)
                Result.success(Unit)
            } else {
                Result.failure(Exception(parseServerError(response.code(), response.errorBody()?.string())))
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    suspend fun checkNickname(nickname: String): Result<Boolean> {
        return try {
            val response = apiService.checkNickname(nickname)
            if (response.isSuccessful) {
                Result.success(response.body()?.available ?: false)
            } else {
                Result.failure(Exception(parseServerError(response.code(), response.errorBody()?.string())))
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    suspend fun getPublicProfile(nickname: String): Result<niel.kro.penik.data.network.api.PublicProfileResponse> {
        return try {
            val response = apiService.getPublicProfile(nickname)
            if (response.isSuccessful) {
                Result.success(response.body()!!)
            } else {
                if (response.code() == 404) {
                    Result.failure(Exception("Пользователь с никнеймом @$nickname не найден"))
                } else {
                    Result.failure(Exception(parseServerError(response.code(), response.errorBody()?.string())))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    suspend fun uploadAvatar(avatarBytes: ByteArray): Result<Unit> {
        return try {
            val requestFile = avatarBytes.toRequestBody("image/webp".toMediaTypeOrNull())
            val body = okhttp3.MultipartBody.Part.createFormData("avatar", "avatar.webp", requestFile)
            val response = apiService.uploadAvatar(body)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(parseServerError(response.code(), response.errorBody()?.string())))
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    private fun parseServerError(code: Int, body: String?): String {
        val trimmedBody = body?.trim()
        val serverMsg = trimmedBody?.let {
            try {
                val error = json.decodeFromString<ErrorBody>(it)
                error.message ?: error.error
            } catch (_: Exception) {
                if (it.isNotEmpty() && !it.startsWith("{") && !it.startsWith("<")) {
                    it
                } else {
                    null
                }
            }
        }

        if (!serverMsg.isNullOrBlank()) {
            val normalized = serverMsg.lowercase().trim()
            return when {
                normalized == "backup_not_found" -> "Резервная копия ключей не найдена на сервере"
                normalized.contains("user not found") || normalized.contains("recipient user not found") -> "Пользователь с таким никнеймом не найден"
                normalized.contains("invalid password") || normalized.contains("invalid credentials") -> "Неверный никнейм или пароль"
                normalized.contains("nickname already taken") -> "Никнейм уже занят"
                else -> serverMsg
            }
        }

        return when (code) {
            400 -> "Неверный запрос. Проверьте введённые данные"
            401 -> "Неверный никнейм или пароль"
            403 -> "Доступ запрещён"
            404 -> "Пользователь с таким никнеймом не найден"
            409 -> "Никнейм уже занят"
            422 -> "Некорректные данные"
            429 -> "Слишком много попыток. Подождите немного"
            in 500..599 -> "Ошибка сервера. Попробуйте позже"
            else -> "Ошибка авторизации ($code)"
        }
    }

    private fun mapException(e: Exception): String = when (e) {
        is UnknownHostException -> "Сервер недоступен. Проверьте подключение к интернету"
        is ConnectException -> "Не удалось подключиться к серверу"
        is SocketTimeoutException -> "Превышено время ожидания. Проверьте подключение к интернету"
        else -> e.message ?: "Неизвестная ошибка"
    }
}
