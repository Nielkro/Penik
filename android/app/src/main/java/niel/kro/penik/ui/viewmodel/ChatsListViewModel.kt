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
import niel.kro.penik.data.network.websocket.ConnectionState
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.domain.usecase.LoadChatsUseCase
import niel.kro.penik.domain.usecase.LogoutUseCase
import niel.kro.penik.domain.usecase.SyncHistoryUseCase
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.data.repository.MessageRepository
import javax.inject.Inject

import niel.kro.penik.data.repository.GroupRepository
import niel.kro.penik.data.repository.ChatRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

sealed interface FeedItem {
    val id: Long
    val name: String
    val lastMessage: String?
    val lastMessageTimestamp: Long?
    val unreadCount: Int
    val isE2EE: Boolean
    val isArchived: Boolean

    data class ChatItem(
        override val id: Long,
        override val name: String,
        val nickname: String,
        override val lastMessage: String?,
        override val lastMessageTimestamp: Long?,
        override val unreadCount: Int,
        override val isE2EE: Boolean = false,
        override val isArchived: Boolean = false
    ) : FeedItem

    data class GroupItem(
        override val id: Long,
        override val name: String,
        override val lastMessage: String?,
        override val lastMessageTimestamp: Long?,
        override val unreadCount: Int,
        val status: String,
        override val isE2EE: Boolean = false,
        override val isArchived: Boolean = false
    ) : FeedItem
}

@HiltViewModel
class ChatsListViewModel @Inject constructor(
    private val loadChatsUseCase: LoadChatsUseCase,
    private val syncHistoryUseCase: SyncHistoryUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val authRepository: AuthRepository,
    private val webSocketManager: WebSocketManager,
    private val apiService: ApiService,
    private val groupRepository: GroupRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _isArchiveOpen = MutableStateFlow(false)
    val isArchiveOpen: StateFlow<Boolean> = _isArchiveOpen.asStateFlow()

    val archivedCount: StateFlow<Int> = combine(
        chatRepository.getArchivedCount(),
        groupRepository.getArchivedCount()
    ) { c, g -> c + g }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun openArchive() { _isArchiveOpen.value = true }
    fun closeArchive() { _isArchiveOpen.value = false }

    fun toggleChatArchived(userId: Long, isE2EE: Boolean, currentArchived: Boolean) {
        viewModelScope.launch {
            chatRepository.setArchived(userId, isE2EE, !currentArchived)
        }
    }

    fun toggleGroupArchived(groupId: Long, currentArchived: Boolean) {
        viewModelScope.launch {
            groupRepository.setArchived(groupId, !currentArchived)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val feed: StateFlow<List<FeedItem>> = _isArchiveOpen.flatMapLatest { showArchived ->
        val chatsFlow = if (showArchived) chatRepository.getArchivedChats() else chatRepository.getActiveChats()
        val groupsFlow = if (showArchived) groupRepository.observeArchivedGroups() else groupRepository.observeActiveGroups()

        combine(
            chatsFlow.map { list ->
                val myId = authRepository.getUserId() ?: 0L
                list.filter { it.userId != myId }
                    .map {
                        val displayName = it.name.ifBlank { it.nickname.ifBlank { "Пользователь ${it.userId}" } }
                        FeedItem.ChatItem(it.userId, displayName, it.nickname, it.lastMessage, it.lastMessageTimestamp, it.unreadCount, it.isE2EE, it.isArchived)
                    }
            },
            groupsFlow.flatMapLatest { groups ->
                if (groups.isEmpty()) return@flatMapLatest flowOf(emptyList<FeedItem.GroupItem>())
                val flows = groups.map { group ->
                    groupRepository.observeLastMessageForGroup(group.id).flatMapLatest { lastMsg ->
                        if (lastMsg == null) {
                            flowOf(
                                FeedItem.GroupItem(
                                    id = group.id,
                                    name = group.name,
                                    lastMessage = null,
                                    lastMessageTimestamp = null,
                                    unreadCount = 0,
                                    status = group.status,
                                    isE2EE = group.isE2EE,
                                    isArchived = group.isArchived
                                )
                            )
                        } else if (lastMsg.sentByMe) {
                            flowOf(
                                FeedItem.GroupItem(
                                    id = group.id,
                                    name = group.name,
                                    lastMessage = "Вы: ${lastMsg.text}",
                                    lastMessageTimestamp = lastMsg.createdAt * 1000,
                                    unreadCount = 0,
                                    status = group.status,
                                    isE2EE = group.isE2EE,
                                    isArchived = group.isArchived
                                )
                            )
                        } else {
                            groupRepository.observeMember(group.id, lastMsg.senderUserId).map { member ->
                                val senderName = member?.let { m ->
                                    m.name.ifBlank { m.nickname.ifBlank { "Пользователь ${lastMsg.senderUserId}" } }
                                } ?: "Пользователь ${lastMsg.senderUserId}"
                                FeedItem.GroupItem(
                                    id = group.id,
                                    name = group.name,
                                    lastMessage = "$senderName: ${lastMsg.text}",
                                    lastMessageTimestamp = lastMsg.createdAt * 1000,
                                    unreadCount = 0,
                                    status = group.status,
                                    isE2EE = group.isE2EE,
                                    isArchived = group.isArchived
                                )
                            }.onStart {
                                emit(
                                    FeedItem.GroupItem(
                                        id = group.id,
                                        name = group.name,
                                        lastMessage = lastMsg.text,
                                        lastMessageTimestamp = lastMsg.createdAt * 1000,
                                        unreadCount = 0,
                                        status = group.status,
                                        isE2EE = group.isE2EE,
                                        isArchived = group.isArchived
                                    )
                                )
                            }
                        }
                    }
                }
                combine(flows) { it.toList() }
            }
        ) { chatsList, groupsList ->
            val sorted = (chatsList + groupsList).sortedByDescending { it.lastMessageTimestamp ?: 0L }
            sorted
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionState = webSocketManager.connectionState
    val isOnline: StateFlow<Boolean> = webSocketManager.isOnline

    private val _searchResults = MutableStateFlow<List<UserSearchResult>>(emptyList())
    val searchResults: StateFlow<List<UserSearchResult>> = _searchResults.asStateFlow()

    private var searchJob: Job? = null

    val selfChatEntry: UserSearchResult?
        get() {
            val myId = authRepository.getUserId() ?: return null
            return UserSearchResult(id = myId, name = "Избранное", nickname = "")
        }

    /** Last message in the self-chat (Favourites), observed reactively. */
    val selfChatLastMessage: StateFlow<niel.kro.penik.data.local.entity.MessageEntity?> =
        kotlinx.coroutines.flow.flow {
            val myId = authRepository.getUserId()
            if (myId != null) {
                messageRepository.observeLastMessageForChat(myId).collect { emit(it) }
            } else {
                emit(null)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isInitialLoading: StateFlow<Boolean> = loadChatsUseCase()
        .map { false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    init {
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
                    val myId = authRepository.getUserId() ?: 0L
                    _searchResults.value = (response.body() ?: emptyList()).filter { it.id != myId }
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
        if (webSocketManager.connectionState.value == ConnectionState.DISCONNECTED) {
            webSocketManager.connect(niel.kro.penik.data.network.api.ApiConfig.HOST, niel.kro.penik.data.network.api.ApiConfig.PORT, token)
        }
    }

    fun logout(onLogout: () -> Unit) {
        logoutUseCase()
        onLogout()
    }

    fun startDirectChat(userId: Long, name: String, nickname: String, isE2EE: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            chatRepository.getOrCreateChat(userId = userId, nickname = nickname, name = name, isE2EE = isE2EE)
            onDone()
        }
    }
}
