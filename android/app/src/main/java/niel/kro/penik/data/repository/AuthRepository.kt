package niel.kro.penik.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.DeviceResponse
import niel.kro.penik.data.network.api.KeysInitRequestBody
import niel.kro.penik.data.network.api.LoginRequestBody
import niel.kro.penik.data.network.api.RegisterRequestBody
import niel.kro.penik.domain.model.AuthResponse
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
    private val database: PenikDatabase,
    private val messageRepositoryProvider: Provider<MessageRepository>
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun generateAndSaveKeys(): Pair<ByteArray, ByteArray> {
        val raw = if (niel.kro.penik.data.crypto.RustCryptoCore.isAvailable()) {
            niel.kro.penik.data.crypto.RustCryptoCore.generateKeyPair()
        } else null
        val (priv, pub) = if (raw != null && raw.size == 64) {
            val sk = raw.copyOfRange(0, 32)
            val pk = raw.copyOfRange(32, 64)
            Pair(sk, pk)
        } else {
            val sk = ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }
            val pk = ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }
            Pair(sk, pk)
        }
        tokenStorage.savePrivateKey(priv)
        tokenStorage.savePublicKey(pub)
        return Pair(priv, pub)
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

    enum class OwnKeyStatus { OK, MISMATCH, UNKNOWN }

    /**
     * Make sure this device has identity keys and that the server advertises
     * exactly this public key for it.
     */
    suspend fun ensureDeviceKeysPublished(): Result<Unit> {
        return try {
            var pub = tokenStorage.getPublicKey()
            if (tokenStorage.getPrivateKey() == null || pub == null) {
                pub = generateAndSaveKeys().second
            }
            val signingPub = tokenStorage.getSigningPublicKey()
                ?: stableSigningKeyPair().second
            val resp = apiService.uploadIdentityKeys(
                KeysInitRequestBody(
                    ikPub = Base64.getEncoder().encodeToString(pub),
                    signingKey = Base64.getEncoder().encodeToString(signingPub),
                    cryptoVersion = 2
                )
            )
            if (resp.isSuccessful) {
                Result.success(Unit)
            } else if (resp.code() == 409) {
                Result.failure(Exception("Ключ устройства конфликтует с сервером. Выйдите из аккаунта и войдите заново"))
            } else {
                Result.failure(Exception(parseServerError(resp.code(), resp.errorBody()?.string())))
            }
        } catch (e: Exception) {
            Result.failure(Exception(mapException(e)))
        }
    }

    suspend fun verifyOwnKeyPublished(): OwnKeyStatus {
        val userId = tokenStorage.getUserId()
        val deviceId = tokenStorage.getDeviceId()
        val localPub = tokenStorage.getPublicKey() ?: return OwnKeyStatus.UNKNOWN
        if (userId <= 0L || deviceId <= 0L) return OwnKeyStatus.UNKNOWN
        return try {
            val devices = messageRepositoryProvider.get().getKeyBundleCached(userId, isSelf = true)
            val dev = devices.find { it.deviceId == deviceId }
                ?: return OwnKeyStatus.UNKNOWN
            val serverPub = runCatching {
                Base64.getDecoder().decode(dev.identityKey)
            }.getOrNull() ?: return OwnKeyStatus.UNKNOWN
            if (serverPub.contentEquals(localPub)) OwnKeyStatus.OK else OwnKeyStatus.MISMATCH
        } catch (_: Exception) {
            OwnKeyStatus.UNKNOWN
        }
    }

    private fun clientPlatform(): String {
        val release = android.os.Build.VERSION.RELEASE ?: ""
        return if (release.isBlank()) "Android" else "Android $release"
    }

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

                val prevUserId = tokenStorage.getUserId()
                if (prevUserId > 0L && prevUserId != body.userId) {
                    try { database.clearAllTables() } catch (_: Exception) {}
                }
                tokenStorage.saveAuth(body.token, body.userId, body.deviceId)
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

                Result.success(AuthResponse(body.token, body.userId, body.deviceId))
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                database.clearAllTables()
            } catch (_: Exception) {}
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
