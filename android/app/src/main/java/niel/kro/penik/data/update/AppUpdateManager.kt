package niel.kro.penik.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import niel.kro.penik.BuildConfig
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.AppVersionInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateStatus {
    object UpToDate : UpdateStatus
    data class SoftUpdateAvailable(val versionInfo: AppVersionInfo) : UpdateStatus
    data class ForceUpdateRequired(val versionInfo: AppVersionInfo) : UpdateStatus
}

sealed interface DownloadState {
    object Idle : DownloadState
    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadState
    data class ReadyToInstall(val apkFile: File) : DownloadState
    data class Error(val message: String) : DownloadState
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

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val downloadClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

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

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    fun startDownload(context: Context, url: String) {
        if (_downloadState.value is DownloadState.Downloading) return
        val targetUrl = url.ifBlank { "https://penik.ru" }
        _downloadState.value = DownloadState.Downloading(0f, 0L, 0L)

        val appContext = context.applicationContext
        downloadScope.launch {
            try {
                val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val targetFile = File(updatesDir, "penik-update.apk")
                if (targetFile.exists()) {
                    targetFile.delete()
                }

                val request = Request.Builder()
                    .url(targetUrl)
                    .build()

                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.message}")
                    }
                    val body = response.body ?: throw IOException("Empty response body")
                    val totalBytes = body.contentLength()
                    var bytesDownloaded = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var read: Int
                            var lastProgressUpdate = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesDownloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdate > 100 || bytesDownloaded == totalBytes) {
                                    lastProgressUpdate = now
                                    val progress = if (totalBytes > 0) {
                                        bytesDownloaded.toFloat() / totalBytes.toFloat()
                                    } else 0f
                                    _downloadState.value = DownloadState.Downloading(
                                        progress = progress.coerceIn(0f, 1f),
                                        bytesDownloaded = bytesDownloaded,
                                        totalBytes = totalBytes
                                    )
                                }
                            }
                            output.flush()
                        }
                    }
                }

                _downloadState.value = DownloadState.ReadyToInstall(targetFile)
                withContext(Dispatchers.Main) {
                    installDownloadedApk(appContext, targetFile)
                }
            } catch (e: Exception) {
                Log.e("AppUpdateManager", "Failed to download update APK", e)
                _downloadState.value = DownloadState.Error(e.message ?: "Ошибка скачивания")
            }
        }
    }

    fun installDownloadedApk(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Failed to launch package installer", e)
            _downloadState.value = DownloadState.Error(e.message ?: "Не удалось запустить установщик")
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
