package niel.kro.penik.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.data.update.AppUpdateManager
import niel.kro.penik.data.update.DownloadState
import niel.kro.penik.data.update.UpdateStatus
import java.io.File
import javax.inject.Inject

@HiltViewModel
class StartupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val webSocketManager: WebSocketManager,
    private val appUpdateManager: AppUpdateManager
) : ViewModel() {

    val unauthorizedEvents: Flow<WebSocketEvent.Unauthorized> =
        webSocketManager.events.filterIsInstance<WebSocketEvent.Unauthorized>()

    val updateStatus: StateFlow<UpdateStatus> = appUpdateManager.updateStatus
    val downloadState: StateFlow<DownloadState> = appUpdateManager.downloadState

    fun checkForUpdates() {
        viewModelScope.launch {
            appUpdateManager.checkForUpdates()
        }
    }

    fun dismissSoftUpdate() {
        appUpdateManager.dismissSoftUpdate()
        appUpdateManager.resetDownloadState()
    }

    fun startDownload(context: Context, url: String) {
        appUpdateManager.startDownload(context, url)
    }

    fun installDownloadedApk(context: Context, apkFile: File) {
        appUpdateManager.installDownloadedApk(context, apkFile)
    }

    fun openDownloadUrl(context: Context, url: String) {
        appUpdateManager.openDownloadUrl(context, url)
    }

    fun isLoggedIn(): Boolean = authRepository.isLoggedIn()

    fun logout() {
        webSocketManager.disconnect()
        authRepository.logout()
    }
}
