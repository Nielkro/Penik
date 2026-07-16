package niel.kro.penik.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.domain.usecase.HandleWebSocketEventUseCase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketEventCoordinator @Inject constructor(
    private val webSocketManager: WebSocketManager,
    private val handleWebSocketEvent: HandleWebSocketEventUseCase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true

        // Subscribe synchronously so an immediate OFFLINE_BATCH cannot beat the collector.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            webSocketManager.events.collect { event ->
                try {
                    handleWebSocketEvent(event)
                } catch (error: Exception) {
                    Log.e("WS", "Failed to process ${event::class.simpleName}", error)
                }
            }
        }
    }
}
