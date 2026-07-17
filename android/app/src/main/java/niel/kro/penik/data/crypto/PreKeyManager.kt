package niel.kro.penik.data.crypto

import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.PrekeyUploadItem
import niel.kro.penik.data.network.api.PrekeysUploadRequest
import niel.kro.penik.data.repository.SecureTokenStorage
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreKeyManager @Inject constructor(
    private val apiService: ApiService,
    private val tokenStorage: SecureTokenStorage,
    private val e2eeCrypto: E2EECrypto
) {
    private val poolSize = 20
    private val minPool = 5

    suspend fun ensurePool() {
        if (!tokenStorage.isLoggedIn()) return
        
        try {
            val response = apiService.getPreKeysStatus()
            if (response.isSuccessful) {
                val status = response.body() ?: return
                if (status.available < minPool) {
                    replenishKeys(poolSize - status.available)
                }
            }
        } catch (e: Exception) {
            // Ignore network errors during background check
        }
    }

    private suspend fun replenishKeys(count: Int) {
        val prekeys = generatePreKeys(count)
        
        val items = prekeys.map { key ->
            PrekeyUploadItem(
                keyId = key.keyId,
                publicKey = Base64.getEncoder().encodeToString(key.publicKey)
            )
        }
        
        val response = apiService.uploadPreKeys(PrekeysUploadRequest(items))
        if (response.isSuccessful) {
            prekeys.forEach { key ->
                tokenStorage.savePreKeyPrivate(key.keyId, key.privateKey)
            }
        }
    }

    fun generateInitialPreKeys(count: Int): List<PreKey> {
        return generatePreKeys(count)
    }

    private fun generatePreKeys(count: Int): List<PreKey> {
        val secureRandom = SecureRandom()
        return (1..count).map {
            val (privateKey, publicKey) = e2eeCrypto.generateX25519KeyPair()
            val keyId = secureRandom.nextLong() and Long.MAX_VALUE
            PreKey(keyId, publicKey, privateKey)
        }
    }
}

data class PreKey(
    val keyId: Long,
    val publicKey: ByteArray,
    val privateKey: ByteArray
)
