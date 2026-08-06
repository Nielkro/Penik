package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.repository.ChatRepository
import niel.kro.penik.data.repository.MessageRepository
import niel.kro.penik.data.repository.PresenceBus
import niel.kro.penik.data.repository.SecureTokenStorage
import niel.kro.penik.domain.usecase.DeleteMessageUseCase
import niel.kro.penik.domain.usecase.LoadMessagesUseCase
import niel.kro.penik.domain.usecase.SendMessageUseCase
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class ChatRoomViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val loadMessagesUseCase: LoadMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val deleteMessageUseCase: DeleteMessageUseCase,
    private val apiService: ApiService,
    private val tokenStorage: SecureTokenStorage,
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val chatUserId: Long = savedStateHandle.get<Long>("chatUserId") ?: 0L
    private val chatName: String = savedStateHandle.get<String>("chatName") ?: ""

    val isSelfChat: Boolean = chatUserId == tokenStorage.getUserId()

    val messages = loadMessagesUseCase(chatUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _safetyNumber = MutableStateFlow<String?>(null)
    val safetyNumber: StateFlow<String?> = _safetyNumber

    private val _showSafetyDialog = MutableStateFlow(false)
    val showSafetyDialog: StateFlow<Boolean> = _showSafetyDialog

    private val _showE2eeDialog = MutableStateFlow(false)
    val showE2eeDialog: StateFlow<Boolean> = _showE2eeDialog

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    private val _lastSeen = MutableStateFlow(0L)
    val lastSeen: StateFlow<Long> = _lastSeen

    init {
        loadSafetyNumber()
        viewModelScope.launch {
            messages.collect { list ->
                clearUnread()
                val unreadIncoming = list.filter { !it.sentByMe && !it.read && it.serverId != null }
                if (unreadIncoming.isNotEmpty()) {
                    unreadIncoming.forEach { msg ->
                        messageRepository.markMessageAsRead(msg.serverId!!)
                    }
                }
            }
        }
        if (!isSelfChat) {
            // Fetch once on open, then rely on PRESENCE_UPDATE websocket pushes.
            viewModelScope.launch { refreshPresence() }
            viewModelScope.launch {
                PresenceBus.presence.collect { map ->
                    map[chatUserId]?.let { state ->
                        _online.value = state.online
                        _lastSeen.value = state.lastSeen
                    }
                }
            }
        }
    }

    private suspend fun refreshPresence() {
        try {
            val profile = apiService.getUserProfile(chatUserId).body() ?: return
            _online.value = profile.online
            _lastSeen.value = profile.lastSeen
            // Update name/nickname in case the contact was imported without it
            val existing = chatRepository.getChat(chatUserId)
            if (existing != null && (existing.name.isBlank() && existing.nickname.isBlank())) {
                chatRepository.upsertContact(chatUserId, profile.nickname, profile.name, null)
            }
        } catch (_: Exception) {
            // Keep showing whatever was last known.
        }
    }

    private fun clearUnread() {
        viewModelScope.launch {
            chatRepository.clearUnread(chatUserId)
        }
    }

    fun sendMessage(text: String, replyToMsgId: String? = null) {
        if (text.isBlank()) return
        viewModelScope.launch {
            sendMessageUseCase(chatUserId, text, chatName, replyToMsgId)
        }
    }

    fun deleteMessage(localId: String, deleteForEveryone: Boolean = false) {
        viewModelScope.launch {
            deleteMessageUseCase(localId, chatUserId, deleteForEveryone)
        }
    }

    fun onSafetyClick() {
        _showSafetyDialog.value = true
    }

    fun dismissSafetyDialog() {
        _showSafetyDialog.value = false
    }

    fun onE2eeClick() {
        _showE2eeDialog.value = true
    }

    fun dismissE2eeDialog() {
        _showE2eeDialog.value = false
    }

    private fun loadSafetyNumber() {
        viewModelScope.launch {
            try {
                val number = withContext(Dispatchers.IO) { calculateSafetyNumber() }
                _safetyNumber.value = number
            } catch (e: Exception) {
                _safetyNumber.value = "Ошибка загрузки"
            }
        }
    }

    private suspend fun calculateSafetyNumber(): String {
        val myId = tokenStorage.getUserId()
        val bundle1 = apiService.getKeyBundle(myId).body()
        val bundle2 = apiService.getKeyBundle(chatUserId).body()

        val ik1 = bundle1?.devices?.firstOrNull()?.identityKey
            ?: throw Exception("Bundle 1 not found")
        val ik2 = bundle2?.devices?.firstOrNull()?.identityKey
            ?: throw Exception("Bundle 2 not found")

        val bytes1 = android.util.Base64.decode(ik1, android.util.Base64.DEFAULT)
        val bytes2 = android.util.Base64.decode(ik2, android.util.Base64.DEFAULT)

        val sorted = listOf(bytes1, bytes2).sortedWith { a, b ->
            for (i in 0 until minOf(a.size, b.size)) {
                if (a[i] != b[i]) return@sortedWith (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            }
            a.size - b.size
        }

        val concat = ByteArray(64)
        System.arraycopy(sorted[0], 0, concat, 0, 32)
        System.arraycopy(sorted[1], 0, concat, 32, 32)

        val hash = MessageDigest.getInstance("SHA-256").digest(concat)

        val numStr = buildString {
            var i = 0
            while (i < hash.size - 1 && length < 25) {
                val value = ((hash[i].toInt() and 0xFF) shl 8) or (hash[i + 1].toInt() and 0xFF)
                append(value.toString().padStart(5, '0').take(5))
                i += 2
            }
        }

        return numStr.chunked(5).joinToString(" ")
    }
}
