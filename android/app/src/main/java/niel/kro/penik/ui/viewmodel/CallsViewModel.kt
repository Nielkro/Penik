package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.CallLogItemResponse
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.domain.call.CallManager
import javax.inject.Inject

@HiltViewModel
class CallsViewModel @Inject constructor(
    private val apiService: ApiService,
    private val webSocketManager: WebSocketManager,
    private val callManager: CallManager
) : ViewModel() {

    private val _calls = MutableStateFlow<List<CallLogItemResponse>>(emptyList())
    val calls: StateFlow<List<CallLogItemResponse>> = _calls.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val callState = callManager.state

    init {
        loadCalls()
        viewModelScope.launch {
            webSocketManager.events.collect { event ->
                if (event is WebSocketEvent.CallLog) {
                    loadCalls()
                }
            }
        }
    }

    fun loadCalls() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = apiService.listCalls(limit = 50, offset = 0)
                if (resp.isSuccessful) {
                    _calls.value = resp.body() ?: emptyList()
                }
            } catch (_: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startCall(userId: Long, name: String, isVideo: Boolean) {
        callManager.startCall(userId, name, isVideo)
    }
}
