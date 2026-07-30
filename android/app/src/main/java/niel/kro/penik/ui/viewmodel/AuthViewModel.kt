package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.data.repository.MessageRepository
import niel.kro.penik.data.network.websocket.WebSocketManager
import javax.inject.Inject

enum class AuthMode {
    WELCOME, REGISTER, LOGIN
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.WELCOME,
    val step: Int = 0,
    val name: String = "",
    val nickname: String = "",
    val password: String = "",
    val e2eePassword: String = "",
    val avatarBytes: ByteArray? = null,
    val tempUserId: Long? = null,
    val tempName: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val webSocketManager: WebSocketManager,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun setMode(mode: AuthMode) {
        _uiState.value = AuthUiState(mode = mode)
    }

    fun goBack() {
        val currentState = _uiState.value
        if (currentState.step > 0) {
            _uiState.value = currentState.copy(step = currentState.step - 1, error = null)
        } else {
            _uiState.value = AuthUiState(mode = AuthMode.WELCOME)
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name, error = null)
    }

    fun updateNickname(nickname: String) {
        _uiState.value = _uiState.value.copy(nickname = nickname, error = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    fun updateE2eePassword(password: String) {
        _uiState.value = _uiState.value.copy(e2eePassword = password, error = null)
    }

    fun updateAvatar(bytes: ByteArray?) {
        _uiState.value = _uiState.value.copy(avatarBytes = bytes)
    }

    // --- REGISTRATION FLOW ACTIONS ---

    fun submitRegisterNickname() {
        val state = _uiState.value
        val nick = state.nickname.trim().removePrefix("@")
        if (nick.isBlank()) {
            _uiState.value = state.copy(error = "Введите никнейм")
            return
        }
        if (nick.length < 3) {
            _uiState.value = state.copy(error = "Никнейм должен быть не менее 3 символов")
            return
        }
        if (!nick.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            _uiState.value = state.copy(error = "Только латиница, цифры и символ _")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            authRepository.checkNickname(nick).fold(
                onSuccess = { available ->
                    if (available) {
                        _uiState.value = _uiState.value.copy(isLoading = false, nickname = nick, step = 1)
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Этот никнейм уже занят")
                    }
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Ошибка проверки никнейма")
                }
            )
        }
    }

    fun submitRegisterPassword() {
        val state = _uiState.value
        if (state.password.length < 6) {
            _uiState.value = state.copy(error = "Пароль должен быть не менее 6 символов")
            return
        }
        _uiState.value = state.copy(step = 2, error = null)
    }

    fun submitRegisterE2eePassword() {
        val state = _uiState.value
        if (state.e2eePassword.length < 6) {
            _uiState.value = state.copy(error = "Пароль должен быть не менее 6 символов")
            return
        }
        _uiState.value = state.copy(step = 3, error = null)
    }

    fun submitRegisterProfile(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.value = state.copy(error = "Введите имя")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val deviceName = android.os.Build.MODEL
            
            // 1. Register User
            val regResult = authRepository.register(state.name, state.nickname, state.password, deviceName)
            regResult.fold(
                onSuccess = { authResp ->
                    // 2. Setup E2EE key backup
                    val backupResult = authRepository.uploadKeyBackup(state.e2eePassword)
                    backupResult.fold(
                        onSuccess = {
                            // 3. Upload avatar if set
                            state.avatarBytes?.let { av ->
                                authRepository.uploadAvatar(av)
                            }
                            
                            // Connect WS
                            webSocketManager.connect("web.dev.penik.ru", 443, authResp.token)
                            _uiState.value = _uiState.value.copy(isLoading = false)
                            onSuccess()
                        },
                        onFailure = { e ->
                            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Ошибка бэкапа ключей")
                        }
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Ошибка регистрации")
                }
            )
        }
    }

    // --- LOGIN FLOW ACTIONS ---

    fun submitLoginNickname() {
        val state = _uiState.value
        val nick = state.nickname.trim().removePrefix("@")
        if (nick.isBlank()) {
            _uiState.value = state.copy(error = "Введите никнейм")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            authRepository.getPublicProfile(nick).fold(
                onSuccess = { profile ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        nickname = profile.nickname,
                        tempUserId = profile.id,
                        tempName = profile.name,
                        step = 1
                    )
                },
                onFailure = { e ->
                    val msg = if (e.message?.contains("404") == true || e.message?.contains("not found") == true) {
                        "Пользователь не найден"
                    } else {
                        e.message ?: "Ошибка загрузки профиля"
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, error = msg)
                }
            )
        }
    }

    fun confirmLoginProfile() {
        _uiState.value = _uiState.value.copy(step = 2, error = null)
    }

    fun submitLoginPassword(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.password.isBlank()) {
            _uiState.value = state.copy(error = "Введите пароль")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val deviceName = android.os.Build.MODEL
            authRepository.login(state.nickname, state.password, deviceName).fold(
                onSuccess = {
                    if (authRepository.hasKeyBackup()) {
                        _uiState.value = _uiState.value.copy(isLoading = false, step = 3)
                    } else {
                        authRepository.getToken()?.let { tok ->
                            webSocketManager.connect("web.dev.penik.ru", 443, tok)
                        }
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        onSuccess()
                    }
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Неверный пароль")
                }
            )
        }
    }

    fun submitLoginE2eePassword(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.e2eePassword.isBlank()) {
            _uiState.value = state.copy(error = "Введите e2ee-пароль")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            authRepository.restoreKeyBackup(state.e2eePassword).fold(
                onSuccess = {
                    // Sync message history and connect WS
                    messageRepository.syncHistory()
                    authRepository.getToken()?.let { tok ->
                        webSocketManager.connect("web.dev.penik.ru", 443, tok)
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onSuccess()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Неверный e2ee-пароль")
                }
            )
        }
    }

    fun submitLoginE2eeReset(newPass: String, onSuccess: () -> Unit) {
        val state = _uiState.value
        if (newPass.length < 6) {
            _uiState.value = state.copy(error = "Новый пароль должен быть не менее 6 символов")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            authRepository.resetKeyBackup(newPass).fold(
                onSuccess = {
                    // Connect WS and synchronize
                    messageRepository.syncHistory()
                    authRepository.getToken()?.let { tok ->
                        webSocketManager.connect("web.dev.penik.ru", 443, tok)
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onSuccess()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Ошибка сброса бэкапа ключей")
                }
            )
        }
    }

    fun skipE2eeBackup(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // Generate a unique fresh X25519 keypair for this specific device
                authRepository.generateAndSaveKeys()
                messageRepository.syncHistory()
                authRepository.getToken()?.let { tok ->
                    webSocketManager.connect("web.dev.penik.ru", 443, tok)
                }
                _uiState.value = _uiState.value.copy(isLoading = false)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Ошибка инициализации ключей устройства")
            }
        }
    }
}
