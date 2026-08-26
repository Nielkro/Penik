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
import android.content.Context
import android.net.Uri
import niel.kro.penik.data.crypto.SafetyNumber
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.local.entity.MessageEntity
import niel.kro.penik.data.repository.AttachmentManager
import niel.kro.penik.data.repository.ChatRepository
import niel.kro.penik.data.repository.MessageRepository
import niel.kro.penik.data.repository.PresenceBus
import niel.kro.penik.data.repository.SecureTokenStorage
import niel.kro.penik.domain.usecase.DeleteMessageUseCase
import niel.kro.penik.domain.usecase.LoadMessagesUseCase
import niel.kro.penik.domain.usecase.SendMessageUseCase
import java.util.UUID
import javax.inject.Inject

import niel.kro.penik.data.repository.GroupRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@HiltViewModel
class ChatRoomViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val loadMessagesUseCase: LoadMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val deleteMessageUseCase: DeleteMessageUseCase,
    private val apiService: ApiService,
    private val tokenStorage: SecureTokenStorage,
    private val attachmentManager: AttachmentManager,
    private val callManager: niel.kro.penik.domain.call.CallManager,
    val messageRepository: MessageRepository,
    val chatRepository: ChatRepository,
    val groupRepository: GroupRepository,
    val stickerRepository: niel.kro.penik.data.repository.StickerRepository
) : ViewModel() {

    private val chatUserId: Long = savedStateHandle.get<Long>("chatUserId") ?: 0L
    private val chatName: String = savedStateHandle.get<String>("chatName") ?: ""

    val isSelfChat: Boolean = chatUserId == tokenStorage.getUserId()

    val callState = callManager.state

    fun startCall(isVideo: Boolean) {
        if (!isSelfChat && chatUserId > 0) {
            callManager.startCall(chatUserId, chatName, isVideo)
        }
    }

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
    private val _isPeerTyping = MutableStateFlow(false)
    val isPeerTyping: StateFlow<Boolean> = _isPeerTyping

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
            viewModelScope.launch {
                niel.kro.penik.data.repository.TypingBus.typing.collect { map ->
                    map[chatUserId]?.let { isTyping ->
                        _isPeerTyping.value = isTyping
                    }
                }
            }
        }
    }

    fun sendTyping(isTyping: Boolean) {
        if (!isSelfChat && chatUserId > 0) {
            messageRepository.sendTyping(chatUserId, isTyping)
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

    private val _editingMessage = MutableStateFlow<MessageEntity?>(null)
    val editingMessage: StateFlow<MessageEntity?> = _editingMessage

    fun startEditing(message: MessageEntity) {
        _editingMessage.value = message
    }

    fun cancelEditing() {
        _editingMessage.value = null
    }

    fun editMessage(clientMsgId: String, newText: String) {
        _editingMessage.value = null
        if (newText.isBlank()) return
        viewModelScope.launch {
            messageRepository.editMessage(chatUserId, clientMsgId, newText.trim())
            chatRepository.updateLastMessage(chatUserId, newText.trim(), System.currentTimeMillis(), name = chatName)
        }
    }

    fun sendMessage(text: String, replyToMsgId: String? = null) {
        if (text.isBlank()) return
        viewModelScope.launch {
            sendMessageUseCase(chatUserId, text, chatName, replyToMsgId)
        }
    }

    fun sendSticker(sticker: niel.kro.penik.data.network.api.StickerItemResponse, replyToMsgId: String? = null) {
        val fileName = sticker.fileName.ifBlank { "${sticker.id}.webp" }
        val payload = buildJsonObject {
            put("type", "sticker")
            put("pack_id", sticker.packId)
            put("sticker_id", sticker.id)
            put("emoji", sticker.emoji)
            put("url", "/api/v1/stickers/file/${sticker.packId}/$fileName")
        }.toString()
        viewModelScope.launch {
            sendMessageUseCase(chatUserId, payload, chatName, replyToMsgId)
        }
    }

    fun sendMediaFile(context: Context, uri: Uri, caption: String = "", onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            val clientMsgId = UUID.randomUUID().toString()
            val mediaInfo = runCatching {
                attachmentManager.prepareLocalMedia(context, uri, clientMsgId, caption)
            }.getOrElse { err ->
                onError(err.message ?: "Ошибка подготовки файла")
                return@launch
            }

            // 1. Immediately insert optimistic message into chat
            messageRepository.insertOptimisticMessage(chatUserId, clientMsgId, mediaInfo.optimisticPayload, null)
            chatRepository.updateLastMessage(chatUserId, mediaInfo.optimisticPayload, System.currentTimeMillis(), name = chatName)

            // 2. Upload and send with progress
            attachmentManager.uploadAndEncryptAttachment(context, mediaInfo, clientMsgId, caption)
                .onSuccess { finalJsonPayload ->
                    messageRepository.sendMessage(chatUserId, finalJsonPayload, null, existingClientMsgId = clientMsgId)
                    chatRepository.updateLastMessage(chatUserId, finalJsonPayload, System.currentTimeMillis(), name = chatName)
                }
                .onFailure { err ->
                    onError(err.message ?: "Ошибка отправки файла")
                }
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

        val keys1 = bundle1?.devices?.mapNotNull { dev ->
            dev.identityKey?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
        } ?: emptyList()
        val keys2 = bundle2?.devices?.mapNotNull { dev ->
            dev.identityKey?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
        } ?: emptyList()

        if (keys1.isEmpty() || keys2.isEmpty()) {
            throw Exception("Ключи устройств не найдены")
        }

        return SafetyNumber.compute(keys1, keys2)
    }

    fun forwardMessage(rawText: String, senderName: String, target: niel.kro.penik.ui.components.ForwardTargetItem, onDone: () -> Unit) {
        viewModelScope.launch {
            val forwardPayload = if (rawText.startsWith("{")) {
                runCatching {
                    val root = Json.parseToJsonElement(rawText).jsonObject
                    if (root["type"]?.jsonPrimitive?.content == "file") {
                        buildJsonObject {
                            root.forEach { (k, v) -> put(k, v) }
                            put("fwd_from", senderName)
                        }.toString()
                    } else if (root["type"]?.jsonPrimitive?.content == "fwd") {
                        buildJsonObject {
                            put("type", "fwd")
                            put("from", root["from"]?.jsonPrimitive?.content ?: senderName)
                            put("text", root["text"]?.jsonPrimitive?.content ?: rawText)
                        }.toString()
                    } else {
                        buildJsonObject {
                            put("type", "fwd")
                            put("from", senderName)
                            put("text", rawText)
                        }.toString()
                    }
                }.getOrElse {
                    buildJsonObject {
                        put("type", "fwd")
                        put("from", senderName)
                        put("text", rawText)
                    }.toString()
                }
            } else {
                buildJsonObject {
                    put("type", "fwd")
                    put("from", senderName)
                    put("text", rawText)
                }.toString()
            }

            if (target.isGroup) {
                groupRepository.sendMessage(target.id, forwardPayload)
            } else {
                messageRepository.sendMessage(target.id, forwardPayload)
            }
            onDone()
        }
    }
}
