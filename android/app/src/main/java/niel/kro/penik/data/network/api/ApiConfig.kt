package niel.kro.penik.data.network.api

object ApiConfig {
    const val HOST = "web.dev.penik.ru"
    const val PORT = 443
    const val SCHEME = "https"

    val BASE_URL: String
        get() = "$SCHEME://$HOST/api/v1/"

    fun getGroupAvatarUrl(groupId: Long, avatarKey: Any? = null): String {
        val base = "$SCHEME://$HOST/api/v1/groups/$groupId/avatar"
        return if (avatarKey != null) "$base?t=$avatarKey" else base
    }

    fun getUserAvatarUrl(userId: Long, avatarKey: Any? = null): String {
        val base = "$SCHEME://$HOST/api/v1/avatar/$userId"
        return if (avatarKey != null) "$base?t=$avatarKey" else base
    }
}
