package niel.kro.penik.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.LoginRequestBody
import niel.kro.penik.data.network.api.RegisterRequestBody
import niel.kro.penik.domain.model.AuthResponse
import niel.kro.penik.data.crypto.E2EECrypto
import java.util.Base64

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class ErrorBody(val message: String? = null, val error: String? = null)

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val tokenStorage: SecureTokenStorage,
             private val e2eeCrypto: E2EECrypto,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(nickname: String, password: String, deviceName: String): Result<AuthResponse> {
        return try {
            val (privateKey, publicKey) = e2eeCrypto.generateX25519KeyPair()
            val ikPubBase64 = Base64.getEncoder().encodeToString(publicKey)

            val response = apiService.login(
                LoginRequestBody(
                    nickname = nickname,
                    password = password,
                    deviceName = deviceName,
                    ikPub = ikPubBase64
                )
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                tokenStorage.saveAuth(body.token, body.userId, body.deviceId)
                fetchAndSaveUserProfile(body.userId)
                
                // Save keys locally
                tokenStorage.savePrivateKey(privateKey)
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
            val (privateKey, publicKey) = e2eeCrypto.generateX25519KeyPair()
            val ikPubBase64 = Base64.getEncoder().encodeToString(publicKey)

            val response = apiService.register(
                RegisterRequestBody(
                    name = name,
                    nickname = nickname,
                    password = password,
                    deviceName = deviceName,
                    ikPub = ikPubBase64
                )
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                tokenStorage.saveAuth(body.token, body.userId, body.deviceId)
                tokenStorage.saveUserProfile(name, nickname)
                
                // Save keys locally
                tokenStorage.savePrivateKey(privateKey)
                Result.success(AuthResponse(body.token, body.userId, body.deviceId))
            } else {
                val msg = parseServerError(response.code(), response.errorBody()?.string())
                Result.failure(Exception(msg))
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
    }

    private fun parseServerError(code: Int, body: String?): String {
        val serverMsg = body?.let {
            try {
                val error = json.decodeFromString<ErrorBody>(it)
                error.message ?: error.error
            } catch (_: Exception) {
                null
            }
        }
        if (!serverMsg.isNullOrBlank()) return serverMsg

        return when (code) {
            400 -> "Неверный запрос. Проверьте введённые данные"
            401 -> "Неверный никнейм или пароль"
            403 -> "Доступ запрещён"
            404 -> "Сервер не найден"
            409 -> "Пользователь уже существует"
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
