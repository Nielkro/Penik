package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import niel.kro.penik.data.network.api.DeviceResponse
import niel.kro.penik.data.repository.AuthRepository
import javax.inject.Inject

data class DevicesUiState(
    val loading: Boolean = false,
    val devices: List<DeviceResponse> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            authRepository.listDevices().fold(
                onSuccess = { devices ->
                    _uiState.value = DevicesUiState(loading = false, devices = devices)
                },
                onFailure = { err ->
                    _uiState.value = DevicesUiState(loading = false, error = err.message ?: "Ошибка загрузки")
                }
            )
        }
    }
}
