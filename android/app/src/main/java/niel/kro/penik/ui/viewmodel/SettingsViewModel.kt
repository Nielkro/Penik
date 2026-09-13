package niel.kro.penik.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import niel.kro.penik.data.update.AppUpdateManager
import niel.kro.penik.data.update.DownloadState
import niel.kro.penik.data.update.UpdateCheckResult
import niel.kro.penik.data.update.UpdateStatus
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appUpdateManager: AppUpdateManager
) : ViewModel() {

    val updateStatus: StateFlow<UpdateStatus> = appUpdateManager.updateStatus
    val downloadState: StateFlow<DownloadState> = appUpdateManager.downloadState

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _manualCheckResult = MutableSharedFlow<UpdateCheckResult>()
    val manualCheckResult: SharedFlow<UpdateCheckResult> = _manualCheckResult.asSharedFlow()

    fun checkForUpdates(manual: Boolean = false) {
        viewModelScope.launch {
            if (manual) _isChecking.value = true
            val result = appUpdateManager.checkForUpdates()
            if (manual) {
                _isChecking.value = false
                _manualCheckResult.emit(result)
            }
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
}
