package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import niel.kro.penik.data.local.entity.GroupMemberEntity
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.data.repository.ChatRepository
import niel.kro.penik.data.repository.GroupRepository
import javax.inject.Inject

@HiltViewModel
class GroupSettingsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val groupId: Long = savedStateHandle.get<Long>("groupId") ?: 0L

    val myUserId: Long = authRepository.getUserId() ?: -1L

    val group = groupRepository.observeGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _members = MutableStateFlow<List<GroupMemberEntity>>(emptyList())
    val members: StateFlow<List<GroupMemberEntity>> = _members.asStateFlow()

    val contacts = chatRepository.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _avatarUpdateKey = MutableStateFlow(System.currentTimeMillis())
    val avatarUpdateKey: StateFlow<Long> = _avatarUpdateKey.asStateFlow()

    init {
        refreshMembers()
    }

    val myRole: String
        get() = _members.value.find { it.userId == myUserId }?.role ?: "member"

    fun refreshMembers() {
        viewModelScope.launch {
            try {
                _members.value = groupRepository.refreshMembers(groupId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось обновить список участников"
            }
        }
    }

    fun renameGroup(newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            groupRepository.renameGroup(groupId, newName.trim()).fold(
                onSuccess = {
                    _isLoading.value = false
                },
                onFailure = { err ->
                    _isLoading.value = false
                    _error.value = err.message ?: "Не удалось изменить название группы"
                }
            )
        }
    }

    fun uploadAvatar(bytes: ByteArray) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            groupRepository.uploadGroupAvatar(groupId, bytes).fold(
                onSuccess = {
                    _isLoading.value = false
                    _avatarUpdateKey.value = System.currentTimeMillis()
                },
                onFailure = { err ->
                    _isLoading.value = false
                    _error.value = err.message ?: "Ошибка загрузки аватара"
                }
            )
        }
    }

    fun invite(userId: Long, shareHistory: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                groupRepository.inviteMember(groupId, userId, shareHistory)
                _members.value = groupRepository.refreshMembers(groupId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось пригласить"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeMember(userId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                groupRepository.removeMember(groupId, userId)
                _members.value = groupRepository.refreshMembers(groupId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось удалить"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun changeMemberRole(userId: Long, role: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                groupRepository.changeMemberRole(groupId, userId, role)
                _members.value = groupRepository.refreshMembers(groupId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось изменить роль"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
