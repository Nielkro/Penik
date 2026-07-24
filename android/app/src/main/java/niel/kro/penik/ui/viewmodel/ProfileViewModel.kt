package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.domain.usecase.LogoutUseCase
import javax.inject.Inject

data class ProfileUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMsg: String? = null,
    val avatarUpdateKey: Long = System.currentTimeMillis()
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val logoutUseCase: LogoutUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    val userId: Long get() = authRepository.getUserId()
    val name: String get() = authRepository.getName()
    val nickname: String get() = authRepository.getNickname()

    fun uploadAvatar(bytes: ByteArray) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, successMsg = null)
            authRepository.uploadAvatar(bytes).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        successMsg = "Аватар обновлен!",
                        avatarUpdateKey = System.currentTimeMillis()
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = err.message ?: "Ошибка загрузки аватара"
                    )
                }
            )
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(error = null, successMsg = null)
    }

    fun logout(onLogout: () -> Unit) {
        logoutUseCase()
        onLogout()
    }
}
