package niel.kro.penik.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PresenceState(val online: Boolean, val lastSeen: Long)

/**
 * App-wide live presence cache, keyed by user id. Bumped by PRESENCE_UPDATE
 * websocket events so any screen showing a user's online/last-seen status
 * updates instantly instead of waiting on the periodic REST poll.
 */
object PresenceBus {
    private val _presence = MutableStateFlow<Map<Long, PresenceState>>(emptyMap())
    val presence: StateFlow<Map<Long, PresenceState>> = _presence.asStateFlow()

    fun update(userId: Long, online: Boolean, lastSeen: Long) {
        _presence.value = _presence.value + (userId to PresenceState(online, lastSeen))
    }
}
