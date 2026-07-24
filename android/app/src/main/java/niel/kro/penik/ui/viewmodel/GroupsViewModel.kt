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
import niel.kro.penik.data.local.entity.GroupMemberEntity
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.UserSearchResult
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.data.repository.GroupRepository
import niel.kro.penik.data.repository.ChatRepository
import niel.kro.penik.data.local.entity.ChatEntity
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
    private val groupRepository: GroupRepository,
    private val authRepository: AuthRepository,
    private val apiService: ApiService,
    private val chatRepository: ChatRepository,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    val groupId: Long = savedStateHandle.get<Long>("groupId") ?: 0L

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

    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val id = runCatching { groupRepository.sendMessage(groupId, text.trim()) }.getOrNull()
            if (id == null) _error.value = "Не удалось отправить сообщение"
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

    fun clearError() { _error.value = null }
}
