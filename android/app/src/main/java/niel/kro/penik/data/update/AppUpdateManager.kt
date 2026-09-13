package niel.kro.penik.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import niel.kro.penik.BuildConfig
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.AppVersionInfo
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateStatus {
    object UpToDate : UpdateStatus
    data class SoftUpdateAvailable(val versionInfo: AppVersionInfo) : UpdateStatus
    data class ForceUpdateRequired(val versionInfo: AppVersionInfo) : UpdateStatus
}

sealed interface UpdateCheckResult {
    object UpToDate : UpdateCheckResult
    data class Available(val versionInfo: AppVersionInfo, val isForce: Boolean) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

@Singleton
class AppUpdateManager @Inject constructor(
    private val apiService: ApiService
) {
    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.UpToDate)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    suspend fun checkForUpdates(): UpdateCheckResult {
        return try {
            val response = apiService.getVersion()
            if (!response.isSuccessful || response.body() == null) {
                Log.w("AppUpdateManager", "Failed to fetch version info: HTTP ${response.code()}")
                return UpdateCheckResult.Error("HTTP ${response.code()}")
            }
            val info = response.body()!!
            val currentCode = BuildConfig.VERSION_CODE

            val (status, result) = when {
                currentCode < info.minAndroidVersionCode -> {
                    UpdateStatus.ForceUpdateRequired(info) to UpdateCheckResult.Available(info, isForce = true)
                }
                currentCode < info.latestAndroidVersionCode -> {
                    UpdateStatus.SoftUpdateAvailable(info) to UpdateCheckResult.Available(info, isForce = false)
                }
                else -> {
                    UpdateStatus.UpToDate to UpdateCheckResult.UpToDate
                }
            }
            _updateStatus.value = status
            result
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Error checking for updates", e)
            UpdateCheckResult.Error(e.message ?: "Network error")
        }
    }

    fun dismissSoftUpdate() {
        if (_updateStatus.value is UpdateStatus.SoftUpdateAvailable) {
            _updateStatus.value = UpdateStatus.UpToDate
        }
    }

    fun openDownloadUrl(context: Context, url: String) {
        val target = url.ifBlank { "https://penik.ru" }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Failed to launch download URL: $target", e)
        }
    }
}
