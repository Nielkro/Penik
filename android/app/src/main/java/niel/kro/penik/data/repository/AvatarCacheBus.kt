package niel.kro.penik.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide cache-busting key for avatars, keyed by group/user id.
 * Bumped after a local upload and on GROUP_AVATAR_UPDATE / USER_AVATAR_UPDATE
 * websocket events, so every screen showing that avatar (not just the one
 * that triggered the change) re-fetches it instead of using a cached image.
 */
object AvatarCacheBus {
    private val _groupAvatarKeys = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val groupAvatarKeys: StateFlow<Map<Long, Long>> = _groupAvatarKeys.asStateFlow()

    private val _userAvatarKeys = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val userAvatarKeys: StateFlow<Map<Long, Long>> = _userAvatarKeys.asStateFlow()

    fun bumpGroup(groupId: Long, ts: Long = System.currentTimeMillis()) {
        _groupAvatarKeys.value = _groupAvatarKeys.value + (groupId to ts)
    }

    fun bumpUser(userId: Long, ts: Long = System.currentTimeMillis()) {
        _userAvatarKeys.value = _userAvatarKeys.value + (userId to ts)
    }
}
