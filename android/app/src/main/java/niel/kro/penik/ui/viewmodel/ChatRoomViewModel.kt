package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.domain.usecase.HandleWebSocketEventUseCase
import niel.kro.penik.domain.usecase.LoadMessagesUseCase
import niel.kro.penik.domain.usecase.SendMessageUseCase
import javax.inject.Inject

@HiltViewModel
class ChatRoomViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val loadMessagesUseCase: LoadMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val handleWebSocketEventUseCase: HandleWebSocketEventUseCase,
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    private val chatUserId: Long = savedStateHandle.get<Long>("chatUserId") ?: 0L
    private val chatName: String = savedStateHandle.get<String>("chatName") ?: ""

    val messages = loadMessagesUseCase(chatUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            webSocketManager.events.collect { event ->
                handleWebSocketEventUseCase(event)
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            sendMessageUseCase(chatUserId, text, chatName)
        }
    }
}
