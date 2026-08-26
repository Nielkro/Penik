package niel.kro.penik.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.data.repository.AuthRepository
import javax.inject.Inject

@HiltViewModel
class StartupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    val unauthorizedEvents: Flow<WebSocketEvent.Unauthorized> =
        webSocketManager.events.filterIsInstance<WebSocketEvent.Unauthorized>()

    fun isLoggedIn(): Boolean = authRepository.isLoggedIn()

    fun logout() {
        webSocketManager.disconnect()
        authRepository.logout()
    }
}
