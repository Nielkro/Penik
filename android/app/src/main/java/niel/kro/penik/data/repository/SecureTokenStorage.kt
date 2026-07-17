package niel.kro.penik.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureTokenStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "penik_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_NAME = "user_name"
        private const val KEY_NICKNAME = "user_nickname"
        private const val KEY_IDENTITY_PRIVATE_KEY = "identity_private_key"
        private const val KEY_PREKEY_PREFIX = "prekey_"
    }

    fun saveAuth(token: String, userId: Long, deviceId: Long) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_USER_ID, userId)
            .putLong(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    fun saveUserProfile(name: String, nickname: String) {
        prefs.edit()
            .putString(KEY_NAME, name)
            .putString(KEY_NICKNAME, nickname)
            .apply()
    }

    fun savePrivateKey(privateKey: ByteArray) {
        val b64 = java.util.Base64.getEncoder().encodeToString(privateKey)
        prefs.edit().putString(KEY_IDENTITY_PRIVATE_KEY, b64).apply()
    }

    fun getPrivateKey(): ByteArray? {
        val b64 = prefs.getString(KEY_IDENTITY_PRIVATE_KEY, null) ?: return null
        return java.util.Base64.getDecoder().decode(b64)
    }

    fun savePreKeyPrivate(keyId: Long, privateKey: ByteArray) {
        val b64 = java.util.Base64.getEncoder().encodeToString(privateKey)
        prefs.edit().putString(KEY_PREKEY_PREFIX + keyId, b64).apply()
    }

    fun getPreKeyPrivate(keyId: Long): ByteArray? {
        val b64 = prefs.getString(KEY_PREKEY_PREFIX + keyId, null) ?: return null
        return java.util.Base64.getDecoder().decode(b64)
    }

    fun deletePreKeyPrivate(keyId: Long) {
        prefs.edit().remove(KEY_PREKEY_PREFIX + keyId).apply()
    }

    fun getAllPreKeyIds(): List<Long> {
        return prefs.all.keys
            .filter { it.startsWith(KEY_PREKEY_PREFIX) }
            .mapNotNull { it.substring(KEY_PREKEY_PREFIX.length).toLongOrNull() }
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun getUserId(): Long = prefs.getLong(KEY_USER_ID, -1)
    fun getDeviceId(): Long = prefs.getLong(KEY_DEVICE_ID, -1)
    fun getName(): String = prefs.getString(KEY_NAME, "") ?: ""
    fun getNickname(): String = prefs.getString(KEY_NICKNAME, "") ?: ""
    fun isLoggedIn(): Boolean = getToken() != null

    fun clear() {
        prefs.edit().clear().apply()
    }
}
