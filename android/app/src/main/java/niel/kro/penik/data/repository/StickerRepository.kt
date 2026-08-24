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

    suspend fun getMyPacks(): Result<List<StickerPackResponse>> = withContext(Dispatchers.IO) {
        try {
            val resp = apiService.getMyStickers()
            if (resp.isSuccessful) {
                Result.success(resp.body() ?: emptyList())
            } else {
                Result.failure(Exception("Ошибка загрузки стикерпаков (${resp.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPackDetails(packId: String): Result<StickerPackResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = apiService.getStickerPack(packId)
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
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
                Result.success(resp.body()!!)
            } else {
                Result.failure(Exception("Не удалось импортировать стикерпак из Telegram"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
