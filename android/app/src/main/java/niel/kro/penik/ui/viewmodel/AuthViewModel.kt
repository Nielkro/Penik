package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import niel.kro.penik.domain.usecase.LoginUseCase
import niel.kro.penik.domain.usecase.RegisterUseCase
import javax.inject.Inject

data class AuthUiState(
    val isLoginMode: Boolean = true,
    val name: String = "",
    val nickname: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val registerUseCase: RegisterUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun toggleMode() {
        _uiState.value = _uiState.value.copy(
            isLoginMode = !_uiState.value.isLoginMode,
            error = null
        )
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun updateNickname(nickname: String) {
        _uiState.value = _uiState.value.copy(nickname = nickname)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun submit(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.nickname.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(error = "Заполните все поля")
            return
        }
        if (!state.isLoginMode && state.name.isBlank()) {
            _uiState.value = state.copy(error = "Введите имя")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val deviceName = android.os.Build.MODEL
            val result = if (state.isLoginMode) {
                loginUseCase(state.nickname, state.password, deviceName)
            } else {
                registerUseCase(state.name, state.nickname, state.password, deviceName)
            }
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onSuccess()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Ошибка"
                    )
                }
            )
        }
    }
}
