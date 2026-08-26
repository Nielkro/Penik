package niel.kro.penik.domain.call

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
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
import kotlinx.coroutines.flow.first
import niel.kro.penik.data.repository.SecureTokenStorage
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
    private val notificationManager: AppNotificationManager,
    private val tokenStorage: SecureTokenStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _remoteScreenShareTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteScreenShareTrack: StateFlow<VideoTrack?> = _remoteScreenShareTrack.asStateFlow()

    private val _remoteCameraTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteCameraTrack: StateFlow<VideoTrack?> = _remoteCameraTrack.asStateFlow()

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
    private var toneGenerator: ToneGenerator? = null
    private var livekitUrl: String = ""
    private var livekitFallbackUrl: String? = null
    private var token: String = ""
    private var currentCallId: String = ""

    private val ui get() = _state.value

    init {
        // LiveKit manages WebRTC media connectivity directly. Temporary WebSocket
        // disconnects (e.g. backgrounding, network switch) should not instantly drop an active call.
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
        startDialingTone()
        webSocketManager.sendCallOffer(peerUserId, isVideo)
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (ui.phase == CallPhase.DIALING) {
                webSocketManager.sendCallReject(currentCallId, ui.peerUserId, "declined")
                playBusyTone()
                toast("Нет ответа")
                cleanup()
            }
        }
    }

    // --- Incoming ---

    private var callIdOfIncoming: String = ""

    fun onIncoming(event: WebSocketEvent.CallIncoming) {
        if (ui.phase != CallPhase.IDLE) {
            if (event.callId == callIdOfIncoming) {
                return
            }
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
        stopAllTones()
        notificationManager.cancelIncomingCallNotification()
        currentCallId = callIdOfIncoming
        _state.value = ui.copy(phase = CallPhase.CONNECTING)
        scope.launch {
            val token = tokenStorage.getToken()
            if (token != null && webSocketManager.connectionState.value == ConnectionState.DISCONNECTED) {
                Log.d(TAG, "acceptCall: WebSocket disconnected. Reconnecting...")
                webSocketManager.connect(
                    niel.kro.penik.data.network.api.ApiConfig.HOST,
                    niel.kro.penik.data.network.api.ApiConfig.PORT,
                    token
                )
            }
            if (webSocketManager.connectionState.value != ConnectionState.CONNECTED) {
                Log.d(TAG, "acceptCall: WebSocket connecting. Waiting...")
                runCatching {
                    withTimeout(6000) {
                        webSocketManager.connectionState.first { it == ConnectionState.CONNECTED }
                    }
                }
            }
            Log.d(TAG, "acceptCall: WebSocket is connected. Sending CallAccept.")
            webSocketManager.sendCallAccept(callIdOfIncoming)
            connectLiveKit()
        }
    }

    fun rejectCall() {
        if (ui.phase != CallPhase.INCOMING) return
        stopAllTones()
        notificationManager.cancelIncomingCallNotification()
        val callId = callIdOfIncoming
        val peerId = ui.peerUserId
        cleanup()
        scope.launch {
            val token = tokenStorage.getToken()
            if (token != null && webSocketManager.connectionState.value == ConnectionState.DISCONNECTED) {
                webSocketManager.connect(
                    niel.kro.penik.data.network.api.ApiConfig.HOST,
                    niel.kro.penik.data.network.api.ApiConfig.PORT,
                    token
                )
            }
            if (webSocketManager.connectionState.value != ConnectionState.CONNECTED) {
                runCatching {
                    withTimeout(4000) {
                        webSocketManager.connectionState.first { it == ConnectionState.CONNECTED }
                    }
                }
            }
            webSocketManager.sendCallReject(callId, peerId, "declined")
        }
    }

    // --- Peer responses ---

    fun onAccepted(event: WebSocketEvent.CallAccepted) {
        if (ui.phase != CallPhase.DIALING) return
        ringTimeoutJob?.cancel()
        stopAllTones()
        currentCallId = event.callId
        livekitUrl = event.livekitUrl
        livekitFallbackUrl = event.livekitFallbackUrl
        token = event.token
        _state.value = ui.copy(phase = CallPhase.CONNECTING)
        scope.launch { connectLiveKit() }
    }

    fun onReject(event: WebSocketEvent.CallReject) {
        if (ui.phase == CallPhase.IDLE) return
        if (!isCurrentCall(event.callId)) return
        playBusyTone()
        when (event.reason) {
            "busy" -> toast("Пользователь занят")
            "offline" -> toast("Пользователь не в сети")
            else -> toast("Звонок отклонен")
        }
        cleanup()
    }

    fun onEnd(event: WebSocketEvent.CallEnd) {
        if (ui.phase == CallPhase.IDLE) return
        if (!isCurrentCall(event.callId)) return
        if (ui.phase == CallPhase.INCOMING) {
            stopAllTones()
            notificationManager.cancelIncomingCallNotification()
            toast("Звонок отменен")
        } else {
            playEndedTone()
            toast("Звонок завершен")
        }
        cleanup()
    }

    /**
     * Another device of this account answered or declined the same incoming
     * call. Only stop ringing locally: sending a reject here would hang up on
     * the device that actually picked up.
     */
    fun onTaken(event: WebSocketEvent.CallTaken) {
        if (ui.phase != CallPhase.INCOMING && ui.phase != CallPhase.DIALING) return
        if (!isCurrentCall(event.callId)) return
        stopAllTones()
        notificationManager.cancelIncomingCallNotification()
        toast(
            if (event.reason == "declined") "Звонок отклонен на другом устройстве"
            else "Звонок принят на другом устройстве"
        )
        cleanup()
    }

    /**
     * The server rings every device of the callee, so a frame must be matched
     * against the call this device is actually in before it is allowed to change
     * any state.
     */
    private fun isCurrentCall(callId: String): Boolean {
        val known = currentCallId.ifEmpty { callIdOfIncoming }
        if (callId.isEmpty() || known.isEmpty()) return true
        return callId == known
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
                if (next) {
                    _localVideoTrack.value = null
                } else {
                    publishLocalVideoTrack()
                }
            } catch (e: Exception) {
                Log.e(TAG, "toggleCamera failed", e)
                toast("Не удалось переключить камеру")
            }
        }
    }

    fun endCall() {
        when (ui.phase) {
            CallPhase.ACTIVE, CallPhase.CONNECTING -> {
                playEndedTone()
                webSocketManager.sendCallEnd(currentCallId, ui.peerUserId)
            }
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
        for ((index, url) in urls.withIndex()) {
            var candidate: Room? = null
            try {
                candidate = createRoom()
                withTimeout(LIVEKIT_CONNECT_TIMEOUT_MS) { candidate.connect(url, token) }
                room = candidate
                if (index > 0) {
                    toast("Подключено к резервному серверу — качество может быть хуже")
                }
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
                        updateRemoteVideoTrack(room)
                    }
                }
                is RoomEvent.TrackUnsubscribed -> {
                    if (event.track is VideoTrack) {
                        updateRemoteVideoTrack(room)
                    }
                }
                is RoomEvent.TrackMuted -> {
                    if (event.publication.track is VideoTrack || event.publication.source == Track.Source.CAMERA || event.publication.source == Track.Source.SCREEN_SHARE) {
                        updateRemoteVideoTrack(room)
                    }
                }
                is RoomEvent.TrackUnmuted -> {
                    if (event.publication.track is VideoTrack || event.publication.source == Track.Source.CAMERA || event.publication.source == Track.Source.SCREEN_SHARE) {
                        updateRemoteVideoTrack(room)
                    }
                }
                is RoomEvent.TrackPublished -> {
                    if (event.participant == room.localParticipant) {
                        publishLocalVideoTrack()
                    } else {
                        updateRemoteVideoTrack(room)
                    }
                }
                is RoomEvent.LocalTrackSubscribed -> {
                    val t = event.publication.track
                    if (t is VideoTrack) publishLocalVideoTrack()
                }
                is RoomEvent.TrackUnpublished -> {
                    if (event.participant == room.localParticipant) {
                        publishLocalVideoTrack()
                    } else {
                        updateRemoteVideoTrack(room)
                    }
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

    private fun updateRemoteVideoTrack(room: Room) {
        var screenTrack: VideoTrack? = null
        var camTrack: VideoTrack? = null

        for (participant in room.remoteParticipants.values) {
            val screenPub = participant.getTrackPublication(Track.Source.SCREEN_SHARE)
            if (screenPub?.track is VideoTrack && screenPub.muted != true) {
                screenTrack = screenPub.track as VideoTrack
            }

            val camPub = participant.getTrackPublication(Track.Source.CAMERA)
            if (camPub?.track is VideoTrack && camPub.muted != true) {
                camTrack = camPub.track as VideoTrack
            }

            if (camTrack == null) {
                val unknownPub = participant.getTrackPublication(Track.Source.UNKNOWN)
                if (unknownPub?.track is VideoTrack && unknownPub.muted != true) {
                    camTrack = unknownPub.track as VideoTrack
                }
            }
        }

        _remoteScreenShareTrack.value = screenTrack
        _remoteCameraTrack.value = camTrack
        val primaryTrack = screenTrack ?: camTrack
        _remoteVideoTrack.value = primaryTrack
        _state.value = ui.copy(hasRemoteVideo = primaryTrack != null)
    }

    private fun publishLocalVideoTrack() {
        val room = room ?: return
        val pub = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
        _localVideoTrack.value = pub?.track as? VideoTrack
    }

    private suspend fun onRoomConnected() {
        val room = room ?: return
        _state.value = ui.copy(phase = CallPhase.ACTIVE)
        playConnectedTone()
        startTimer()
        updateRemoteVideoTrack(room)
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

    private fun startDialingTone() {
        try {
            toneGenerator?.release()
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start dialing tone", e)
        }
    }

    private fun playBusyTone() {
        try {
            stopAllTones()
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 75)
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_BUSY, 1500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play busy tone", e)
        }
    }

    private fun playConnectedTone() {
        try {
            stopAllTones()
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 75)
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play connected tone", e)
        }
    }

    private fun playEndedTone() {
        try {
            stopAllTones()
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 75)
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ended tone", e)
        }
    }

    private fun stopAllTones() {
        stopRinger()
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null
    }

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
        stopAllTones()
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
        _remoteScreenShareTrack.value = null
        _remoteCameraTrack.value = null
        _localVideoTrack.value = null
        livekitUrl = ""
        livekitFallbackUrl = null
        token = ""
        callIdOfIncoming = ""
        currentCallId = ""
        _state.value = CallUiState()
    }
}
