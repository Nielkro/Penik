package niel.kro.penik.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.ImportTelegramStickersRequest
import niel.kro.penik.data.network.api.StickerItemResponse
import niel.kro.penik.data.network.api.StickerPackResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StickerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("penik_stickers_pref", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val _recentStickers = MutableStateFlow<List<StickerItemResponse>>(emptyList())
    val recentStickers: StateFlow<List<StickerItemResponse>> = _recentStickers.asStateFlow()

    init {
        loadRecents()
    }

    private fun loadRecents() {
        val raw = prefs.getString("recent_stickers", null) ?: return
        try {
            val list = json.decodeFromString<List<StickerItemResponse>>(raw)
            _recentStickers.value = list
        } catch (_: Exception) {}
    }

    fun addRecentSticker(sticker: StickerItemResponse) {
        val current = _recentStickers.value.toMutableList()
        current.removeAll { it.packId == sticker.packId && it.id == sticker.id }
        current.add(0, sticker)
        val trimmed = if (current.size > 32) current.take(32) else current
        _recentStickers.value = trimmed
        try {
            prefs.edit().putString("recent_stickers", json.encodeToString(trimmed)).apply()
        } catch (_: Exception) {}
    }

    private val packsMemoryCache = java.util.concurrent.ConcurrentHashMap<String, StickerPackResponse>()
    private var myPacksCache: List<StickerPackResponse>? = null

    suspend fun getMyPacks(forceRefresh: Boolean = false): Result<List<StickerPackResponse>> = withContext(Dispatchers.IO) {
        if (!forceRefresh && myPacksCache != null) {
            return@withContext Result.success(myPacksCache!!)
        }
        try {
            val resp = apiService.getMyStickers()
            if (resp.isSuccessful) {
                val list = resp.body() ?: emptyList()
                myPacksCache = list
                Result.success(list)
            } else {
                Result.failure(Exception("Ошибка загрузки стикерпаков (${resp.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val downloadingPacks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun getLocalStickerFile(packId: String, fileName: String): java.io.File? {
        if (packId.isBlank() || fileName.isBlank()) return null
        val packDir = java.io.File(context.cacheDir, "stickers/$packId")
        val file = java.io.File(packDir, fileName)
        if (file.exists() && file.length() > 0) return file
        val base = fileName.substringBeforeLast('.')
        for (ext in listOf(".webp", ".webm", ".png", ".tgs")) {
            val alt = java.io.File(packDir, base + ext)
            if (alt.exists() && alt.length() > 0) return alt
        }
        return null
    }

    suspend fun preloadPackBundle(packId: String) = withContext(Dispatchers.IO) {
        if (packId.isBlank()) return@withContext
        val packDir = java.io.File(context.cacheDir, "stickers/$packId")
        if (packDir.exists() && (packDir.list()?.isNotEmpty() == true)) {
            return@withContext
        }
        if (!downloadingPacks.add(packId)) {
            return@withContext
        }
        try {
            val resp = apiService.downloadStickerPackBundle(packId)
            if (resp.isSuccessful && resp.body() != null) {
                packDir.mkdirs()
                resp.body()!!.byteStream().use { stream ->
                    java.util.zip.ZipInputStream(stream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val cleanName = java.io.File(entry.name).name
                            if (!entry.isDirectory && cleanName.isNotBlank() && !cleanName.startsWith(".")) {
                                val outFile = java.io.File(packDir, cleanName)
                                outFile.outputStream().use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            downloadingPacks.remove(packId)
        }
    }

    suspend fun getPackDetails(packId: String, forceRefresh: Boolean = false): Result<StickerPackResponse> = withContext(Dispatchers.IO) {
        // Preload zip bundle in parallel
        preloadPackBundle(packId)

        if (!forceRefresh) {
            val cached = packsMemoryCache[packId]
            if (cached != null && cached.stickers.isNotEmpty()) {
                return@withContext Result.success(cached)
            }
        }
        try {
            val resp = apiService.getStickerPack(packId)
            if (resp.isSuccessful && resp.body() != null) {
                val pack = resp.body()!!
                packsMemoryCache[packId] = pack
                Result.success(pack)
            } else {
                Result.failure(Exception("Стикерпак не найден"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun installPack(packId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resp = apiService.installStickerPack(packId)
            if (resp.isSuccessful) {
                myPacksCache = null
                Result.success(Unit)
            } else {
                Result.failure(Exception("Не удалось установить стикерпак"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uninstallPack(packId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resp = apiService.uninstallStickerPack(packId)
            if (resp.isSuccessful) {
                myPacksCache = null
                packsMemoryCache.remove(packId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Не удалось удалить стикерпак"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importTelegramPack(url: String): Result<StickerPackResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = apiService.importTelegramStickerPack(ImportTelegramStickersRequest(url.trim()))
            if (resp.isSuccessful && resp.body() != null) {
                val pack = resp.body()!!
                myPacksCache = null
                packsMemoryCache[pack.id] = pack
                Result.success(pack)
            } else {
                Result.failure(Exception("Не удалось импортировать стикерпак из Telegram"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
