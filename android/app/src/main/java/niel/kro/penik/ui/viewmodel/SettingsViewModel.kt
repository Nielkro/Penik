package niel.kro.penik.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.data.repository.BackupManager
import niel.kro.penik.data.update.AppUpdateManager
import niel.kro.penik.data.update.DownloadState
import niel.kro.penik.data.update.UpdateCheckResult
import niel.kro.penik.data.update.UpdateStatus
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appUpdateManager: AppUpdateManager,
    private val authRepository: AuthRepository,
    private val backupManager: BackupManager
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

    fun generateMnemonicPhrase(wordCount: Int = 12): String {
        return backupManager.generateMnemonicPhrase(wordCount)
    }

    fun exportHistoryToFile(
        passphrase: String,
        uri: Uri,
        context: Context,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            val exportResult = backupManager.exportHistory(passphrase)
            exportResult.fold(
                onSuccess = { backupJson ->
                    val writeResult = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                                    writer.write(backupJson)
                                }
                            } ?: throw IllegalStateException("Не удалось открыть файл для записи")
                        }
                    }
                    onResult(writeResult)
                },
                onFailure = { err ->
                    onResult(Result.failure(err))
                }
            )
        }
    }

    fun importHistoryFromFile(
        uri: Uri,
        passphrase: String,
        context: Context,
        onResult: (Result<BackupManager.ImportSummary>) -> Unit
    ) {
        viewModelScope.launch {
            val readResult = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw IllegalStateException("Не удалось прочитать файл")
                }
            }

            readResult.fold(
                onSuccess = { backupJson ->
                    val importResult = backupManager.importHistory(backupJson, passphrase)
                    onResult(importResult)
                },
                onFailure = { err ->
                    onResult(Result.failure(err))
                }
            )
        }
    }

    fun uploadKeyBackup(passphrase: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val res = authRepository.uploadKeyBackup(passphrase)
            onResult(res)
        }
    }

    fun restoreKeyBackup(passphrase: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val res = authRepository.restoreKeyBackup(passphrase)
            onResult(res)
        }
    }
}
