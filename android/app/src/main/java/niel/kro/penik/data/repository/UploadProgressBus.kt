package niel.kro.penik.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UploadProgress(
    val loaded: Long,
    val total: Long
)

object UploadProgressBus {
    private val _progress = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, UploadProgress>> = _progress.asStateFlow()

    fun update(msgId: String, loaded: Long, total: Long) {
        _progress.value = _progress.value + (msgId to UploadProgress(loaded, total))
    }

    fun remove(msgId: String) {
        _progress.value = _progress.value - msgId
    }
}
