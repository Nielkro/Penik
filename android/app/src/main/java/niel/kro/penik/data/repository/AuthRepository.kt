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

    suspend fun login(nickname: String, password: String, deviceName: String): Result<AuthResponse> {        return try {
            val (privateKey, publicKey) = stableIdentityKeyPair()
            val ikPubBase64 = Base64.getEncoder().encodeToString(publicKey)

            val response = apiService.login(
                LoginRequestBody(
                    nickname = nickname,
                    password = password,
                    deviceName = deviceName,
                    platform = clientPlatform(),
                    location = clientLocation(),
                    ikPub = ikPubBase64
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
            val ikPubBase64 = Base64.getEncoder().encodeToString(publicKey)

            val response = apiService.register(
                RegisterRequestBody(
                    name = name,
                    nickname = nickname,
                    password = password,
                    deviceName = deviceName,
                    platform = clientPlatform(),
                    location = clientLocation(),
                    ikPub = ikPubBase64
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
            val backup = e2eeCrypto.encryptKeyBackup(privateKey, passphrase)
            val b64Blob = Base64.getEncoder().encodeToString(backup.encryptedBlob)
            val b64Salt = Base64.getEncoder().encodeToString(backup.salt)
            val b64Iv = Base64.getEncoder().encodeToString(backup.iv)

            val response = apiService.uploadKeyBackup(
                niel.kro.penik.data.network.api.KeyBackupRequest(
                    encryptedBlob = b64Blob,
                    salt = b64Salt,
                    iv = b64Iv
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

    suspend fun restoreKeyBackup(passphrase: String): Result<Unit> {
        return try {
            val response = apiService.getKeyBackup()
            if (response.isSuccessful) {
                val body = response.body()!!
                val blob = Base64.getDecoder().decode(body.encryptedBlob)
                val salt = Base64.getDecoder().decode(body.salt)
                val iv = Base64.getDecoder().decode(body.iv)

                val decryptedPrivKey = e2eeCrypto.decryptKeyBackup(blob, salt, iv, passphrase)
                val derivedPubKey = e2eeCrypto.derivePublicKey(decryptedPrivKey)

                tokenStorage.savePrivateKey(decryptedPrivKey)
                tokenStorage.savePublicKey(derivedPubKey)

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

            val backup = e2eeCrypto.encryptKeyBackup(privateKey, newPassphrase)
            val b64Blob = Base64.getEncoder().encodeToString(backup.encryptedBlob)
            val b64Salt = Base64.getEncoder().encodeToString(backup.salt)
            val b64Iv = Base64.getEncoder().encodeToString(backup.iv)

            val response = apiService.uploadKeyBackup(
                niel.kro.penik.data.network.api.KeyBackupRequest(
                    encryptedBlob = b64Blob,
                    salt = b64Salt,
                    iv = b64Iv
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
