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
import javax.inject.Inject

import niel.kro.penik.data.repository.GroupRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

sealed interface FeedItem {
    val id: Long
    val name: String
    val lastMessage: String?
    val lastMessageTimestamp: Long?
    val unreadCount: Int

    data class ChatItem(
        override val id: Long,
        override val name: String,
        val nickname: String,
        override val lastMessage: String?,
        override val lastMessageTimestamp: Long?,
        override val unreadCount: Int
    ) : FeedItem

    data class GroupItem(
        override val id: Long,
        override val name: String,
        override val lastMessage: String?,
        override val lastMessageTimestamp: Long?,
        override val unreadCount: Int,
        val status: String
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
    private val groupRepository: GroupRepository
) : ViewModel() {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val feed: StateFlow<List<FeedItem>> = combine(
        loadChatsUseCase().map { list ->
            list.map { FeedItem.ChatItem(it.userId, it.name, it.nickname, it.lastMessage, it.lastMessageTimestamp, it.unreadCount) }
        },
        groupRepository.observeGroups().flatMapLatest { groups ->
            if (groups.isEmpty()) return@flatMapLatest flowOf(emptyList<FeedItem.GroupItem>())
            val flows = groups.map { group ->
                groupRepository.observeLastMessageForGroup(group.id).map { lastMsg ->
                    FeedItem.GroupItem(
                        id = group.id,
                        name = group.name,
                        lastMessage = lastMsg?.text,
                        lastMessageTimestamp = lastMsg?.createdAt?.let { it * 1000 },
                        unreadCount = 0, // In Android E2EE, group unread counts are simplified
                        status = group.status
                    )
                }
            }
            combine(flows) { it.toList() }
        }
    ) { chatsList, groupsList ->
        (chatsList + groupsList).sortedByDescending { it.lastMessageTimestamp ?: 0L }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionState = webSocketManager.connectionState

    private val _searchResults = MutableStateFlow<List<UserSearchResult>>(emptyList())
    val searchResults: StateFlow<List<UserSearchResult>> = _searchResults.asStateFlow()

    private var searchJob: Job? = null

    val selfChatEntry: UserSearchResult?
        get() {
            val myId = authRepository.getUserId() ?: return null
            return UserSearchResult(id = myId, name = "Избранное", nickname = "")
        }

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
                    _searchResults.value = response.body() ?: emptyList()
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
            webSocketManager.connect("penik.dev.slavchat.ru", 443, token)
        }
    }

    fun logout(onLogout: () -> Unit) {
        logoutUseCase()
        onLogout()
    }
}
