package niel.kro.penik.data.network.api

import android.content.Context
import android.content.SharedPreferences

object ApiConfig {
    const val PROD_HOST = "api.penik.ru"
    const val DEV_HOST = "web.dev.penik.ru"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("penik_api_config", Context.MODE_PRIVATE)
    }

    var HOST: String
        get() = prefs?.getString("api_host", PROD_HOST) ?: PROD_HOST
        set(value) {
            prefs?.edit()?.putString("api_host", value)?.apply()
        }

    var PORT: Int
        get() = prefs?.getInt("api_port", 443) ?: 443
        set(value) {
            prefs?.edit()?.putInt("api_port", value)?.apply()
        }

    var SCHEME: String
        get() = prefs?.getString("api_scheme", "https") ?: "https"
        set(value) {
            prefs?.edit()?.putString("api_scheme", value)?.apply()
        }

    val BASE_URL: String
        get() = if (PORT == 80 || PORT == 443) {
            "$SCHEME://$HOST/api/v1/"
        } else {
            "$SCHEME://$HOST:$PORT/api/v1/"
        }

    fun isDev(): Boolean = HOST == DEV_HOST

    fun isProd(): Boolean = HOST == PROD_HOST

    fun setServer(isDev: Boolean) {
        if (isDev) {
            HOST = DEV_HOST
            PORT = 443
            SCHEME = "https"
        } else {
            HOST = PROD_HOST
            PORT = 443
            SCHEME = "https"
        }
    }

    fun setCustom(host: String, port: Int = 443, scheme: String = "https") {
        HOST = host.trim()
        PORT = port
        SCHEME = scheme.trim()
    }

    fun getGroupAvatarUrl(groupId: Long, avatarKey: Any? = null): String {
        val base = if (PORT == 80 || PORT == 443) "$SCHEME://$HOST/api/v1/groups/$groupId/avatar" else "$SCHEME://$HOST:$PORT/api/v1/groups/$groupId/avatar"
        return if (avatarKey != null) "$base?t=$avatarKey" else base
    }

    fun getUserAvatarUrl(userId: Long, avatarKey: Any? = null): String {
        val base = if (PORT == 80 || PORT == 443) "$SCHEME://$HOST/api/v1/avatar/$userId" else "$SCHEME://$HOST:$PORT/api/v1/avatar/$userId"
        return if (avatarKey != null) "$base?t=$avatarKey" else base
    }

    fun getStickerFileUrl(packId: String, fileName: String): String {
        return if (PORT == 80 || PORT == 443) "$SCHEME://$HOST/api/v1/stickers/file/$packId/$fileName" else "$SCHEME://$HOST:$PORT/api/v1/stickers/file/$packId/$fileName"
    }

    fun getFullStickerUrl(urlOrPath: String): String {
        val prefix = if (PORT == 80 || PORT == 443) "$SCHEME://$HOST" else "$SCHEME://$HOST:$PORT"
        return if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            urlOrPath
        } else if (urlOrPath.startsWith("/")) {
            "$prefix$urlOrPath"
        } else {
            "$prefix/$urlOrPath"
        }
    }
}
