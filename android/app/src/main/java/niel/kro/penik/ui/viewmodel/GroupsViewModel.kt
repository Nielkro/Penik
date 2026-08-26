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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import niel.kro.penik.data.local.entity.GroupMemberEntity
import niel.kro.penik.data.local.entity.GroupMessageEntity
import niel.kro.penik.data.local.entity.GroupEntity
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.UserSearchResult
import niel.kro.penik.data.repository.AttachmentManager
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.data.repository.GroupRepository
import niel.kro.penik.data.repository.ChatRepository
import niel.kro.penik.data.repository.MessageRepository
import niel.kro.penik.data.local.entity.ChatEntity
import android.content.Context
import android.net.Uri
import javax.inject.Inject

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val authRepository: AuthRepository,
    private val apiService: ApiService,
) : ViewModel() {

    val groups = groupRepository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { groupRepository.syncGroups() }
        }
    }

    fun createGroup(name: String, onCreated: (Long, String) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val group = groupRepository.createGroup(name.trim(), emptyList())
                if (group != null) onCreated(group.id, group.name)
                else _error.value = "Не удалось создать группу"
            } catch (e: Exception) {
                _error.value = e.message ?: "Ошибка создания группы"
            } finally {
                _busy.value = false
            }
        }
    }

    fun acceptInvitation(groupId: Long) {
        viewModelScope.launch {
            try {
                groupRepository.acceptInvitation(groupId)
                groupRepository.syncGroups()
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось принять приглашение"
            }
        }
    }

    fun declineInvitation(groupId: Long) {
        viewModelScope.launch {
            try {
                groupRepository.declineInvitation(groupId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось отклонить приглашение"
            }
        }
    }

    fun clearError() { _error.value = null }
}

@HiltViewModel
class GroupChatViewModel @Inject constructor(
    val groupRepository: GroupRepository,
    val authRepository: AuthRepository,
    val apiService: ApiService,
    val chatRepository: ChatRepository,
    val messageRepository: MessageRepository,
    private val attachmentManager: AttachmentManager,
    val stickerRepository: niel.kro.penik.data.repository.StickerRepository,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val webSocketManager: niel.kro.penik.data.network.websocket.WebSocketManager
) : ViewModel() {

    val groupId: Long = savedStateHandle.get<Long>("groupId") ?: 0L

    val connectionState = webSocketManager.connectionState

    val myUserId: Long = authRepository.getUserId() ?: -1L

    val messages = groupRepository.observeMessages(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _members = MutableStateFlow<List<GroupMemberEntity>>(emptyList())
    val members: StateFlow<List<GroupMemberEntity>> = _members.asStateFlow()

    val contacts = chatRepository.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchResults = MutableStateFlow<List<UserSearchResult>>(emptyList())
    val searchResults: StateFlow<List<UserSearchResult>> = _searchResults.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { _members.value = groupRepository.refreshMembers(groupId) }
            runCatching { groupRepository.syncHistory(groupId) }
        }
    }

    val myRole: String
        get() = _members.value.find { it.userId == myUserId }?.role ?: "member"

    private val _editingMessage = MutableStateFlow<GroupMessageEntity?>(null)
    val editingMessage: StateFlow<GroupMessageEntity?> = _editingMessage.asStateFlow()

    fun startEditing(message: GroupMessageEntity) {
        _editingMessage.value = message
    }

    fun cancelEditing() {
        _editingMessage.value = null
    }

    fun edit(messageId: String, newText: String) {
        _editingMessage.value = null
        if (newText.isBlank()) return
        viewModelScope.launch {
            runCatching { groupRepository.editMessage(groupId, messageId, newText.trim()) }
                .onFailure { _error.value = "Не удалось изменить сообщение" }
        }
    }

    fun send(text: String, replyToMsgId: String? = null) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val id = runCatching { groupRepository.sendMessage(groupId, text.trim(), replyToMsgId) }.getOrNull()
            if (id == null) _error.value = "Не удалось отправить сообщение"
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
            val id = runCatching { groupRepository.sendMessage(groupId, payload, replyToMsgId) }.getOrNull()
            if (id == null) _error.value = "Не удалось отправить стикер"
        }
    }

    fun sendMediaFile(context: Context, uri: Uri, caption: String = "", onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            val clientMsgId = java.util.UUID.randomUUID().toString()
            val mediaInfo = runCatching {
                attachmentManager.prepareLocalMedia(context, uri, clientMsgId, caption)
            }.getOrElse { err ->
                onError(err.message ?: "Ошибка подготовки файла")
                return@launch
            }

            // 1. Immediately insert optimistic message into group
            groupRepository.insertOptimisticMessage(groupId, clientMsgId, mediaInfo.optimisticPayload, null)

            // 2. Upload and send with progress
            attachmentManager.uploadAndEncryptAttachment(context, mediaInfo, clientMsgId, caption)
                .onSuccess { finalJsonPayload ->
                    val id = runCatching { groupRepository.sendMessage(groupId, finalJsonPayload, null, existingMessageId = clientMsgId) }.getOrNull()
                    if (id == null) onError("Не удалось отправить файл")
                }
                .onFailure { err ->
                    onError(err.message ?: "Ошибка отправки файла")
                }
        }
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

    fun invite(userId: Long, shareHistory: Boolean = false) {
        viewModelScope.launch {
            try {
                groupRepository.inviteMember(groupId, userId, shareHistory)
                _members.value = groupRepository.refreshMembers(groupId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось пригласить"
            }
        }
    }

    fun removeMember(userId: Long) {
        viewModelScope.launch {
            try {
                groupRepository.removeMember(groupId, userId)
                _members.value = groupRepository.refreshMembers(groupId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось удалить"
            }
        }
    }

    fun changeMemberRole(userId: Long, role: String) {
        viewModelScope.launch {
            try {
                groupRepository.changeMemberRole(groupId, userId, role)
                _members.value = groupRepository.refreshMembers(groupId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось изменить роль"
            }
        }
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
                if (response.isSuccessful) _searchResults.value = response.body() ?: emptyList()
            } catch (_: Exception) {}
        }
    }

    val groupFlow = groupRepository.observeGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun acceptInvitation(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                groupRepository.acceptInvitation(groupId)
                groupRepository.syncGroups()
                _members.value = groupRepository.refreshMembers(groupId)
                onDone?.invoke()
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось принять приглашение"
            }
        }
    }

    fun declineInvitation(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                groupRepository.declineInvitation(groupId)
                onDone?.invoke()
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось отклонить приглашение"
            }
        }
    }

    fun clearError() { _error.value = null }
}
