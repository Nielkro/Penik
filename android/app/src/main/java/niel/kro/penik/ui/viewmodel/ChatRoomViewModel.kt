package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import niel.kro.penik.domain.usecase.LoadMessagesUseCase
import niel.kro.penik.domain.usecase.SendMessageUseCase
import javax.inject.Inject

@HiltViewModel
class ChatRoomViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val loadMessagesUseCase: LoadMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase
) : ViewModel() {

    private val chatUserId: Long = savedStateHandle.get<Long>("chatUserId") ?: 0L
    private val chatName: String = savedStateHandle.get<String>("chatName") ?: ""

    val messages = loadMessagesUseCase(chatUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            sendMessageUseCase(chatUserId, text, chatName)
        }
    }
}
