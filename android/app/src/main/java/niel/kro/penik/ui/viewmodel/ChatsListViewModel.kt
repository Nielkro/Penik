package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.UserSearchResult
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.domain.usecase.HandleWebSocketEventUseCase
import niel.kro.penik.domain.usecase.LoadChatsUseCase
import niel.kro.penik.domain.usecase.LogoutUseCase
import niel.kro.penik.domain.usecase.SyncHistoryUseCase
import niel.kro.penik.data.repository.AuthRepository
import javax.inject.Inject

@HiltViewModel
class ChatsListViewModel @Inject constructor(
    private val loadChatsUseCase: LoadChatsUseCase,
    private val handleWebSocketEventUseCase: HandleWebSocketEventUseCase,
    private val syncHistoryUseCase: SyncHistoryUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val authRepository: AuthRepository,
    private val webSocketManager: WebSocketManager,
    private val apiService: ApiService
) : ViewModel() {

    val chats = loadChatsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionState = webSocketManager.connectionState

    private val _searchResults = MutableStateFlow<List<UserSearchResult>>(emptyList())
    val searchResults: StateFlow<List<UserSearchResult>> = _searchResults.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            webSocketManager.events.collect { event ->
                handleWebSocketEventUseCase(event)
            }
        }
        viewModelScope.launch {
            syncHistoryUseCase()
        }
        reconnectIfNeeded()
    }

    fun searchUsers(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            try {
                val response = apiService.searchUsers(query)
                if (response.isSuccessful) {
                    val myId = authRepository.getUserId()
                    _searchResults.value = response.body()?.filter { it.id != myId } ?: emptyList()
                }
            } catch (_: Exception) {}
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
    }

    private fun reconnectIfNeeded() {
        val token = authRepository.getToken() ?: return
        if (webSocketManager.connectionState.value != niel.kro.penik.data.network.websocket.ConnectionState.CONNECTED) {
            webSocketManager.connect("penik.dev.slavchat.ru", 443, token)
        }
    }

    fun logout(onLogout: () -> Unit) {
        logoutUseCase()
        onLogout()
    }
}
