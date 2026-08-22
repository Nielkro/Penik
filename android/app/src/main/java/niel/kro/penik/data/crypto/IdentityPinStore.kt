package niel.kro.penik.data.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TOFU (trust-on-first-use) pinning of peer devices' public identity keys.
 *
 * Android previously accepted whatever identity key the server handed out for a
 * peer device, on every single fetch. A server able to substitute one key could
 * therefore read the whole conversation without the user having any way to
 * notice, and the safety number would have looked "fine" because it is derived
 * from the same substituted key.
 *
 * The first key seen for a (user_id, device_id) pair is pinned. A later change is
 * legitimate often enough (reinstall, re-login, a new keypair) that blocking
 * delivery would be wrong, so this mirrors the web client: the pin is updated and
 * the change is reported once per pair per process via [changes], for the UI to
 * surface. Pins live in the Keystore-backed EncryptedSharedPreferences so they
 * cannot be rewritten by reading the app's plain preferences.
 */
@Singleton
class IdentityPinStore @Inject constructor(
    @ApplicationContext context: Context
) {

    enum class Result { NEW, OK, UPDATED }

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "penik_identity_pins",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _changes = MutableSharedFlow<Change>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    data class Change(val userId: Long, val deviceId: Long)

    private val warned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Pins [ikPub] for the pair, or reports that it replaced a different key. */
    fun verify(userId: Long, deviceId: Long, ikPub: ByteArray): Result {
        if (userId <= 0L || deviceId <= 0L || ikPub.isEmpty()) return Result.OK
        val id = "$userId:$deviceId"
        val presented = android.util.Base64.encodeToString(ikPub, android.util.Base64.NO_WRAP)

        val pinned = prefs.getString(id, null)
        if (pinned == null) {
            prefs.edit().putString(id, presented).apply()
            return Result.NEW
        }
        if (pinned == presented) return Result.OK

        prefs.edit().putString(id, presented).apply()
        if (warned.add(id)) {
            _changes.tryEmit(Change(userId, deviceId))
        }
        return Result.UPDATED
    }

    /** Drops every pin; used on logout, when the local identity is discarded. */
    fun clear() {
        prefs.edit().clear().apply()
        warned.clear()
    }
}
