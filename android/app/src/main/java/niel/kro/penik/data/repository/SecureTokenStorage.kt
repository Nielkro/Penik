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
        private const val KEY_IDENTITY_PUBLIC_KEY = "identity_public_key"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
    }

    /**
     * Returns the SQLCipher passphrase for the Room database, generating and
     * persisting one on first use. The passphrase is the Base64 encoding of 32
     * random bytes (~192 bits, ASCII only so it is safe to embed in SQL string
     * literals during migration). It lives only in the Keystore-backed
     * EncryptedSharedPreferences, never inside the database itself.
     */
    fun getOrCreateDatabasePassphrase(): String {
        prefs.getString(KEY_DB_PASSPHRASE, null)?.let { return it }
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val passphrase = java.util.Base64.getEncoder().encodeToString(raw)
        prefs.edit().putString(KEY_DB_PASSPHRASE, passphrase).apply()
        return passphrase
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

    fun savePublicKey(publicKey: ByteArray) {
        val b64 = java.util.Base64.getEncoder().encodeToString(publicKey)
        prefs.edit().putString(KEY_IDENTITY_PUBLIC_KEY, b64).apply()
    }

    fun getPublicKey(): ByteArray? {
        val b64 = prefs.getString(KEY_IDENTITY_PUBLIC_KEY, null) ?: return null
        return java.util.Base64.getDecoder().decode(b64)
    }



    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun getUserId(): Long = prefs.getLong(KEY_USER_ID, -1)
    fun getDeviceId(): Long = prefs.getLong(KEY_DEVICE_ID, -1)
    fun getName(): String = prefs.getString(KEY_NAME, "") ?: ""
    fun getNickname(): String = prefs.getString(KEY_NICKNAME, "") ?: ""
    fun isLoggedIn(): Boolean = getToken() != null

    fun saveFcmToken(token: String) {
        prefs.edit().putString("fcm_token", token).apply()
    }

    fun getFcmToken(): String? {
        return prefs.getString("fcm_token", null)
    }

    fun saveLastUploadedFcmToken(token: String) {
        prefs.edit().putString("fcm_token_uploaded", token).apply()
    }

    fun getLastUploadedFcmToken(): String? {
        return prefs.getString("fcm_token_uploaded", null)
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_NAME)
            .remove(KEY_NICKNAME)
            .remove(KEY_IDENTITY_PRIVATE_KEY)
            .remove(KEY_IDENTITY_PUBLIC_KEY)
            .remove("fcm_token_uploaded")
            .apply()
    }
}
