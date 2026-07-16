package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    val chats = loadChatsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionState = webSocketManager.connectionState

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
