package niel.kro.penik.domain.call

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.EglBase
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.websocket.ConnectionState
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.ui.notification.AppNotificationManager
import javax.inject.Inject
import javax.inject.Singleton

enum class CallPhase { IDLE, DIALING, INCOMING, CONNECTING, ACTIVE }

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val peerUserId: Long = 0L,
    val peerName: String = "",
    val isVideo: Boolean = false,
    val isOutgoing: Boolean = false,
    val micMuted: Boolean = false,
    val cameraOff: Boolean = false,
    val hasRemoteVideo: Boolean = false,
    val elapsed: String = ""
)

private const val TAG = "CallManager"
private const val RING_TIMEOUT_MS = 30_000L
private const val INCOMING_RING_TIMEOUT_MS = 45_000L
private const val LIVEKIT_CONNECT_TIMEOUT_MS = 15_000L

@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocketManager: WebSocketManager,
    private val apiService: ApiService,
    private val notificationManager: AppNotificationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    val eglBase: EglBase by lazy { EglBase.create() }

    private var room: Room? = null
    private var eventsJob: Job? = null
    private var timerJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var ringtone: Ringtone? = null
    private var livekitUrl: String = ""
    private var livekitFallbackUrl: String? = null
    private var token: String = ""
    private var currentCallId: String = ""

    private val ui get() = _state.value

    init {
        webSocketManager.connectionState
            .drop(1)
            .onEach { state ->
                if (state == ConnectionState.DISCONNECTED && ui.phase != CallPhase.IDLE) {
                    toast("Соединение потеряно, звонок завершен")
                    cleanup()
                }
            }
            .launchIn(scope)
    }

    // --- Outgoing ---

    fun startCall(peerUserId: Long, peerName: String, isVideo: Boolean) {
        if (ui.phase != CallPhase.IDLE) {
            toast("Уже есть активный звонок")
            return
        }
        _state.value = CallUiState(
            phase = CallPhase.DIALING,
            peerUserId = peerUserId,
            peerName = peerName.ifBlank { "Пользователь #$peerUserId" },
            isVideo = isVideo,
            isOutgoing = true
        )
        webSocketManager.sendCallOffer(peerUserId, isVideo)
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (ui.phase == CallPhase.DIALING) {
                webSocketManager.sendCallReject(currentCallId, ui.peerUserId, "declined")
                toast("Нет ответа")
                cleanup()
            }
        }
    }

    // --- Incoming ---

    private var callIdOfIncoming: String = ""

    fun onIncoming(event: WebSocketEvent.CallIncoming) {
        if (ui.phase != CallPhase.IDLE) {
            webSocketManager.sendCallReject(event.callId, event.fromUserId, "busy")
            return
        }
        callIdOfIncoming = event.callId
        livekitUrl = event.livekitUrl
        livekitFallbackUrl = event.livekitFallbackUrl
        token = event.token
        _state.value = CallUiState(
            phase = CallPhase.INCOMING,
            peerUserId = event.fromUserId,
            peerName = "Пользователь #${event.fromUserId}",
            isVideo = event.isVideo,
            isOutgoing = false
        )
        startRinger()
        notificationManager.showIncomingCallNotification(
            peerUserId = event.fromUserId,
            peerName = ui.peerName,
            isVideo = event.isVideo
        )
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(INCOMING_RING_TIMEOUT_MS)
            if (ui.phase == CallPhase.INCOMING) {
                webSocketManager.sendCallReject(callIdOfIncoming, ui.peerUserId, "declined")
                stopRinger()
                notificationManager.cancelIncomingCallNotification()
                cleanup()
            }
        }
        scope.launch { resolvePeerName(event.fromUserId) }
    }

    fun acceptCall() {
        if (ui.phase != CallPhase.INCOMING) return
        ringTimeoutJob?.cancel()
        stopRinger()
        notificationManager.cancelIncomingCallNotification()
        currentCallId = callIdOfIncoming
        _state.value = ui.copy(phase = CallPhase.CONNECTING)
        webSocketManager.sendCallAccept(callIdOfIncoming)
        scope.launch { connectLiveKit() }
    }

    fun rejectCall() {
        if (ui.phase != CallPhase.INCOMING) return
        webSocketManager.sendCallReject(callIdOfIncoming, ui.peerUserId, "declined")
        stopRinger()
        notificationManager.cancelIncomingCallNotification()
        cleanup()
    }

    // --- Peer responses ---

    fun onAccepted(event: WebSocketEvent.CallAccepted) {
        if (ui.phase != CallPhase.DIALING) return
        ringTimeoutJob?.cancel()
        currentCallId = event.callId
        livekitUrl = event.livekitUrl
        livekitFallbackUrl = event.livekitFallbackUrl
        token = event.token
        _state.value = ui.copy(phase = CallPhase.CONNECTING)
        scope.launch { connectLiveKit() }
    }

    fun onReject(event: WebSocketEvent.CallReject) {
        if (ui.phase == CallPhase.IDLE) return
        when (event.reason) {
            "busy" -> toast("Пользователь занят")
            "offline" -> toast("Пользователь не в сети")
            else -> toast("Звонок отклонен")
        }
        cleanup()
    }

    fun onEnd(event: WebSocketEvent.CallEnd) {
        if (ui.phase == CallPhase.IDLE) return
        if (ui.phase == CallPhase.INCOMING) {
            stopRinger()
            notificationManager.cancelIncomingCallNotification()
            toast("Звонок отменен")
        }
        cleanup()
    }

    // --- Media controls ---

    fun toggleMic() {
        val room = room ?: return
        val next = !ui.micMuted
        scope.launch {
            try {
                room.localParticipant.setMicrophoneEnabled(!next)
                _state.value = ui.copy(micMuted = next)
            } catch (e: Exception) {
                Log.e(TAG, "toggleMic failed", e)
                toast("Не удалось переключить микрофон")
            }
        }
    }

    fun toggleCamera() {
        val room = room ?: return
        val next = !ui.cameraOff
        if (next && ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            toast("Нет разрешения на камеру")
            return
        }
        scope.launch {
            try {
                room.localParticipant.setCameraEnabled(!next)
                _state.value = ui.copy(cameraOff = next)
                publishLocalVideoTrack()
            } catch (e: Exception) {
                Log.e(TAG, "toggleCamera failed", e)
                toast("Не удалось переключить камеру")
            }
        }
    }

    fun endCall() {
        when (ui.phase) {
            CallPhase.ACTIVE, CallPhase.CONNECTING ->
                webSocketManager.sendCallEnd(currentCallId, ui.peerUserId)
            CallPhase.DIALING ->
                webSocketManager.sendCallReject(currentCallId, ui.peerUserId, "declined")
            CallPhase.INCOMING -> {
                rejectCall()
                return
            }
            CallPhase.IDLE -> return
        }
        cleanup()
    }

    // --- LiveKit ---

    private suspend fun connectLiveKit() {
        val urls = listOfNotNull(livekitUrl, livekitFallbackUrl).distinct().filter { it.isNotBlank() }
        for (url in urls) {
            var candidate: Room? = null
            try {
                candidate = createRoom()
                withTimeout(LIVEKIT_CONNECT_TIMEOUT_MS) { candidate.connect(url, token) }
                room = candidate
                onRoomConnected()
                return
            } catch (e: Exception) {
                Log.e(TAG, "LiveKit connect failed to $url", e)
                // Stop collecting events before disconnecting: otherwise the
                // Disconnected handler fires for our own retry teardown and
                // ends the call before the fallback URL is tried.
                eventsJob?.cancel()
                eventsJob = null
                try { candidate?.disconnect() } catch (_: Exception) {}
                try { candidate?.release() } catch (_: Exception) {}
            }
        }
        toast("Ошибка подключения к серверу звонка")
        // Tell the server the call is over so both users leave the busy state.
        webSocketManager.sendCallReject(currentCallId, ui.peerUserId, "declined")
        cleanup()
    }

    private fun createRoom(): Room {
        val r = LiveKit.create(
            context,
            RoomOptions(adaptiveStream = true, dynacast = true),
            LiveKitOverrides(eglBase = eglBase)
        )
        eventsJob?.cancel()
        eventsJob = scope.launch { collectRoomEvents(r) }
        return r
    }

    private suspend fun collectRoomEvents(room: Room) {
        room.events.events.collect { event ->
            when (event) {
                is RoomEvent.TrackSubscribed -> {
                    val track = event.track
                    if (track is VideoTrack) {
                        _remoteVideoTrack.value = track
                        _state.value = ui.copy(hasRemoteVideo = true)
                    }
                }
                is RoomEvent.TrackUnsubscribed -> {
                    if (event.track is VideoTrack) {
                        _remoteVideoTrack.value = null
                        _state.value = ui.copy(hasRemoteVideo = false)
                    }
                }
                is RoomEvent.TrackMuted -> {
                    if (event.publication.source == Track.Source.CAMERA) {
                        _remoteVideoTrack.value = null
                        _state.value = ui.copy(hasRemoteVideo = false)
                    }
                }
                is RoomEvent.TrackUnmuted -> {
                    if (event.publication.source == Track.Source.CAMERA) {
                        val t = event.publication.track
                        if (t is VideoTrack) {
                            _remoteVideoTrack.value = t
                            _state.value = ui.copy(hasRemoteVideo = true)
                        }
                    }
                }
                is RoomEvent.TrackPublished -> {
                    if (event.participant == room.localParticipant) publishLocalVideoTrack()
                }
                is RoomEvent.LocalTrackSubscribed -> {
                    val t = event.publication.track
                    if (t is VideoTrack) _localVideoTrack.value = t
                }
                is RoomEvent.TrackUnpublished -> {
                    if (event.participant == room.localParticipant) _localVideoTrack.value = null
                }
                is RoomEvent.Disconnected -> {
                    // A failed initial connect also emits Disconnected before
                    // connect() throws; the failover loop owns CONNECTING.
                    // Only an ACTIVE room dropping is a real call end.
                    if (ui.phase == CallPhase.ACTIVE) {
                        if (currentCallId.isNotEmpty()) {
                            webSocketManager.sendCallEnd(currentCallId, ui.peerUserId)
                        }
                        cleanup()
                    }
                }
                else -> Unit
            }
        }
    }

    private fun publishLocalVideoTrack() {
        val room = room ?: return
        val pub = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
        _localVideoTrack.value = pub?.track as? VideoTrack
    }

    private suspend fun onRoomConnected() {
        val room = room ?: return
        _state.value = ui.copy(phase = CallPhase.ACTIVE)
        startTimer()
        try {
            room.localParticipant.setMicrophoneEnabled(true)
            if (ui.isVideo) {
                room.localParticipant.setCameraEnabled(true)
                _state.value = ui.copy(cameraOff = false)
                publishLocalVideoTrack()
            } else {
                _state.value = ui.copy(cameraOff = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish local tracks", e)
        }
    }

    // --- Ringer / timer / misc ---

    private fun startRinger() {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ringtone", e)
        }
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = ContextCompat.getSystemService(context, VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800), 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vibration", e)
        }
    }

    private fun stopRinger() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getSystemService(context, VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.cancel()
    }

    private fun startTimer() {
        timerJob?.cancel()
        val startMs = System.currentTimeMillis()
        timerJob = scope.launch {
            while (true) {
                val secs = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                _state.value = ui.copy(elapsed = "%02d:%02d".format(secs / 60, secs % 60))
                delay(1000)
            }
        }
    }

    private suspend fun resolvePeerName(userId: Long) {
        try {
            val profile = apiService.getUserProfile(userId).body()
            val name = profile?.name?.ifBlank { profile.nickname }.orEmpty()
            if (ui.phase == CallPhase.INCOMING && ui.peerUserId == userId) {
                _state.value = ui.copy(peerName = name.ifBlank { "Пользователь #$userId" })
                notificationManager.showIncomingCallNotification(userId, ui.peerName, ui.isVideo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve caller name", e)
        }
    }

    private fun toast(text: String) {
        _toasts.tryEmit(text)
    }

    private fun cleanup() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        timerJob?.cancel()
        timerJob = null
        stopRinger()
        notificationManager.cancelIncomingCallNotification()
        eventsJob?.cancel()
        eventsJob = null
        val r = room
        room = null
        if (r != null) {
            scope.launch {
                try { r.disconnect() } catch (_: Exception) {}
                try { r.release() } catch (_: Exception) {}
            }
        }
        _remoteVideoTrack.value = null
        _localVideoTrack.value = null
        livekitUrl = ""
        livekitFallbackUrl = null
        token = ""
        callIdOfIncoming = ""
        currentCallId = ""
        _state.value = CallUiState()
    }
}
