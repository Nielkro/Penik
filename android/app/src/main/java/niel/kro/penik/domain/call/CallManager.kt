package niel.kro.penik.domain.call

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import io.livekit.android.util.LoggingLevel
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsCollectorCallback
import livekit.org.webrtc.RTCStatsReport
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.websocket.ConnectionState
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.ui.notification.AppNotificationManager
import kotlinx.coroutines.flow.first
import niel.kro.penik.data.crypto.RustCryptoCore
import niel.kro.penik.data.crypto.SafetyNumber
import niel.kro.penik.data.repository.SecureTokenStorage
import java.security.MessageDigest
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
    val elapsed: String = "",
    // True while LiveKit is re-establishing the session after a network change.
    val isReconnecting: Boolean = false,
    // False while the peer's signaling link is inside the server grace window.
    val peerOnline: Boolean = true,
    // True when the call is End-to-End Encrypted via WebRTC FrameCryptor.
    val isE2EE: Boolean = false,
    val isE2EEVerified: Boolean = false,
    val safetyWords: List<String> = emptyList()
)

private const val TAG = "CallManager"

// An outgoing call held until the user confirms they want to dial despite VPN.
private data class PendingOutgoingCall(
    val peerUserId: Long,
    val peerName: String,
    val isVideo: Boolean,
    val callKey: String
)

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

    // Emits whenever a call is started/accepted while an active VPN tunnel
    // is detected. UI should surface this as a dialog, not a toast: media
    // rides the tunnel interface and typically fails, so the user should be
    // prompted to disable the VPN for the call.
    private val _vpnWarning = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val vpnWarning: SharedFlow<Unit> = _vpnWarning.asSharedFlow()

    val eglBase: EglBase by lazy { EglBase.create() }

    private var room: Room? = null
    private var eventsJob: Job? = null
    private var statsJob: Job? = null
    private var timerJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null
    private var livekitUrl: String = ""
    private var livekitFallbackUrl: String? = null
    private var token: String = ""
    private var currentCallId: String = ""
    private var callKey: String = ""
    private var derivedMasterKey: String = ""
    private var myEphemeralPub: ByteArray? = null
    private var myEphemeralPriv: ByteArray? = null
    private var authSecret: ByteArray? = null
    private var isE2EEVerified: Boolean = false
    private var safetyWords: List<String> = emptyList()
    // Pending outgoing call that was held until the user confirms despite VPN.
    private var pendingOutgoingCall: PendingOutgoingCall? = null
    // True while an incoming call accept is held pending the VPN confirmation.
    private var pendingAccept: Boolean = false

    private val ui get() = _state.value

    init {
        // LiveKit manages WebRTC media connectivity directly. Temporary WebSocket
        // disconnects (e.g. backgrounding, network switch) should not instantly drop an active call.

        // Call connectivity diagnostics: SDK traces plus native WebRTC/ICE logs
        // land in logcat (tags "LKLog", "CallManager", webrtc). Filter with:
        //   adb logcat -s CallManager LKLog
        LiveKit.loggingLevel = LoggingLevel.DEBUG
        LiveKit.enableWebRTCLogging = true
        ensureWebRtcLoaded()
    }

    private fun ensureWebRtcLoaded() {
        try {
            System.loadLibrary("lkjingle_peerconnection_so")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load lkjingle_peerconnection_so: ${e.message}")
        }
        try {
            LiveKit.init(context)
        } catch (e: Throwable) {
            Log.w(TAG, "LiveKit.init failed: ${e.message}")
        }
    }

    // --- Crypto Helpers for Signed Ephemeral DH (Option 3) ---

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun cleanKey(raw: ByteArray): ByteArray {
        return if (raw.size == 33 && raw[0] == 0x05.toByte()) {
            raw.copyOfRange(1, 33)
        } else {
            raw
        }
    }

    private fun generateEphemeralKeyPair(): Pair<ByteArray, ByteArray>? {
        if (RustCryptoCore.isAvailable()) {
            val kp = RustCryptoCore.generateKeyPair()
            if (kp != null && kp.size == 64) {
                val pubKey = kp.copyOfRange(0, 32)
                val privKey = kp.copyOfRange(32, 64)
                return Pair(pubKey, privKey)
            }
        }
        return try {
            val kpg = java.security.KeyPairGenerator.getInstance("X25519")
            val kp = kpg.generateKeyPair()
            val fullPriv = kp.private.encoded
            val fullPub = kp.public.encoded
            val priv = fullPriv.copyOfRange(fullPriv.size - 32, fullPriv.size)
            val pub = fullPub.copyOfRange(fullPub.size - 32, fullPub.size)
            Pair(pub, priv)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate ephemeral key pair", e)
            null
        }
    }

    private fun diffieHellman(privKey: ByteArray, pubKey: ByteArray): ByteArray? {
        val cleanPub = cleanKey(pubKey)
        if (RustCryptoCore.isAvailable()) {
            val shared = RustCryptoCore.diffieHellman(privKey, cleanPub)
            if (shared != null) return shared
        }
        return try {
            val kf = java.security.KeyFactory.getInstance("X25519")
            val pkcs8Header = byteArrayOf(
                0x30, 0x2E, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
                0x03, 0x2B, 0x65, 0x6E, 0x04, 0x22, 0x04, 0x20
            )
            val fullPriv = ByteArray(16 + privKey.size)
            System.arraycopy(pkcs8Header, 0, fullPriv, 0, 16)
            System.arraycopy(privKey, 0, fullPriv, 16, privKey.size)

            val x509Header = byteArrayOf(
                0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65,
                0x6E, 0x03, 0x21, 0x00
            )
            val fullPub = ByteArray(12 + cleanPub.size)
            System.arraycopy(x509Header, 0, fullPub, 0, 12)
            System.arraycopy(cleanPub, 0, fullPub, 12, cleanPub.size)

            val privateKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(fullPriv))
            val publicKey = kf.generatePublic(java.security.spec.X509EncodedKeySpec(fullPub))
            val ka = javax.crypto.KeyAgreement.getInstance("X25519")
            ka.init(privateKey)
            ka.doPhase(publicKey, true)
            ka.generateSecret()
        } catch (e: Exception) {
            Log.e(TAG, "DiffieHellman fallback failed", e)
            null
        }
    }

    private fun computeAuthTag(secret: ByteArray, prefix: String, ekPubHex: String): String {
        val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
        val ekBytes = ekPubHex.toByteArray(Charsets.UTF_8)
        val input = ByteArray(secret.size + prefixBytes.size + ekBytes.size)
        System.arraycopy(secret, 0, input, 0, secret.size)
        System.arraycopy(prefixBytes, 0, input, secret.size, prefixBytes.size)
        System.arraycopy(ekBytes, 0, input, secret.size + prefixBytes.size, ekBytes.size)
        return bytesToHex(sha256(input))
    }

    private fun deriveMediaKeyAndWords(sharedDh: ByteArray, secret: ByteArray?): Pair<String, List<String>> {
        val contextBytes = (if (secret != null) "penik-livekit-call-v2" else "penik-livekit-call-v1").toByteArray(Charsets.UTF_8)
        val totalLen = sharedDh.size + (secret?.size ?: 0) + contextBytes.size
        val input = ByteArray(totalLen)
        var offset = 0
        System.arraycopy(sharedDh, 0, input, offset, sharedDh.size)
        offset += sharedDh.size
        if (secret != null) {
            System.arraycopy(secret, 0, input, offset, secret.size)
            offset += secret.size
        }
        System.arraycopy(contextBytes, 0, input, offset, contextBytes.size)
        val masterBytes = sha256(input)
        val masterHex = bytesToHex(masterBytes)
        val words = (0 until 4).map { i ->
            val idx = masterBytes[i].toInt() and 0xFF
            SafetyNumber.RUSSIAN_WORDS[idx]
        }
        return Pair(masterHex, words)
    }

    private suspend fun fetchPeerIdentityKey(userId: Long): ByteArray? {
        return try {
            val resp = apiService.getKeyBundle(userId)
            if (resp.isSuccessful) {
                val dev = resp.body()?.devices?.firstOrNull { it.identityKey.isNotBlank() }
                dev?.identityKey?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch peer identity key for $userId: ${e.message}")
            null
        }
    }

    // --- Outgoing ---

    fun startCall(peerUserId: Long, peerName: String, isVideo: Boolean) {
        if (ui.phase != CallPhase.IDLE) {
            toast("Уже есть активный звонок")
            return
        }
        val kp = generateEphemeralKeyPair()
        if (kp == null) {
            toast("Ошибка инициализации шифрования звонка")
            return
        }
        myEphemeralPub = kp.first
        myEphemeralPriv = kp.second
        val myEkPubHex = bytesToHex(kp.first)

        _state.value = CallUiState(
            phase = CallPhase.DIALING,
            peerUserId = peerUserId,
            peerName = peerName.ifBlank { "Пользователь #$peerUserId" },
            isVideo = isVideo,
            isOutgoing = true,
            isE2EE = true,
            isE2EEVerified = false
        )
        startDialingTone()

        scope.launch {
            val myIkPriv = tokenStorage.getPrivateKey()
            val peerIkPub = fetchPeerIdentityKey(peerUserId)
            var outgoingKey = "dh:1:$myEkPubHex"
            if (myIkPriv != null && peerIkPub != null) {
                val secret = diffieHellman(myIkPriv, peerIkPub)
                if (secret != null) {
                    authSecret = secret
                    val tag = computeAuthTag(secret, "CALL_OFFER:", myEkPubHex)
                    outgoingKey = "dh:2:$myEkPubHex:$tag"
                }
            }
            callKey = outgoingKey

            if (isVpnActive()) {
                pendingOutgoingCall = PendingOutgoingCall(peerUserId, peerName, isVideo, outgoingKey)
                _vpnWarning.tryEmit(Unit)
                return@launch
            }
            startDialTimeout(peerUserId)
            webSocketManager.sendCallOffer(peerUserId, isVideo, outgoingKey)
        }
    }

    private fun startDialTimeout(peerUserId: Long) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (ui.phase == CallPhase.DIALING) {
                webSocketManager.sendCallReject(currentCallId, peerUserId, "declined")
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
        callKey = event.callKey.orEmpty()
        isE2EEVerified = false
        safetyWords = emptyList()

        _state.value = CallUiState(
            phase = CallPhase.INCOMING,
            peerUserId = event.fromUserId,
            peerName = "Пользователь #${event.fromUserId}",
            isVideo = event.isVideo,
            isOutgoing = false,
            isE2EE = callKey.isNotBlank(),
            isE2EEVerified = false
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
        scope.launch { prepareIncomingAuth(event.fromUserId, callKey) }
    }

    private suspend fun prepareIncomingAuth(peerUserId: Long, offerKey: String) {
        if (!offerKey.startsWith("dh:")) return
        try {
            val parts = offerKey.split(":")
            if (parts.size >= 3) {
                val version = parts[1]
                val callerEkPubHex = parts[2]
                val callerTag = if (parts.size > 3) parts[3] else null

                val myIkPriv = tokenStorage.getPrivateKey()
                val peerIkPub = fetchPeerIdentityKey(peerUserId)
                if (myIkPriv != null && peerIkPub != null) {
                    val secret = diffieHellman(myIkPriv, peerIkPub)
                    if (secret != null) {
                        authSecret = secret
                        if (version == "2" && callerTag != null) {
                            val expectedTag = computeAuthTag(secret, "CALL_OFFER:", callerEkPubHex)
                            if (expectedTag.equals(callerTag, ignoreCase = true)) {
                                isE2EEVerified = true
                                if (ui.phase == CallPhase.INCOMING) {
                                    _state.value = ui.copy(isE2EEVerified = true)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "prepareIncomingAuth error", e)
        }
    }

    fun acceptCall() {
        if (ui.phase != CallPhase.INCOMING) return
        ringTimeoutJob?.cancel()
        stopAllTones()
        notificationManager.cancelIncomingCallNotification()
        currentCallId = callIdOfIncoming
        // Ask to disable VPN (if active) BEFORE connecting so media does not
        // try to collect candidates against the tunnel interface.
        if (isVpnActive()) {
            pendingAccept = true
            _vpnWarning.tryEmit(Unit)
            return
        }
        proceedAcceptCall()
    }

    /** Called by the UI when the user confirms they want to continue despite the VPN. */
    fun proceedCallAfterVpnWarning() {
        // Re-check: if the VPN is still active, do NOT proceed — media will fail;
        // notify the user and re-trigger the warning so they know it is still active.
        if (isVpnActive()) {
            Log.w(TAG, "VPN still active on continue; re-requesting confirmation")
            _toasts.tryEmit("VPN всё ещё включен. Отключите его для звонка")
            _vpnWarning.tryEmit(Unit)
            return
        }
        val pending = pendingOutgoingCall
        if (pending != null) {
            pendingOutgoingCall = null
            startDialing()
            return
        }
        if (pendingAccept) {
            pendingAccept = false
            proceedAcceptCall()
        }
    }

    /** Called by the UI when the user cancels the VPN warning dialog to abort the call attempt. */
    fun cancelCallAfterVpnWarning() {
        if (pendingOutgoingCall != null || ui.phase == CallPhase.DIALING) {
            pendingOutgoingCall = null
            cleanup()
            return
        }
        if (pendingAccept) {
            pendingAccept = false
            rejectCall()
            return
        }
        cleanup()
    }

    // --- Peer responses ---

    private fun proceedAcceptCall() {
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

            var acceptCallKey: String? = null
            if (callKey.startsWith("dh:")) {
                try {
                    val parts = callKey.split(":")
                    if (parts.size >= 3) {
                        val version = parts[1]
                        val callerEkPubHex = parts[2]
                        val callerTag = if (parts.size > 3) parts[3] else null

                        val kp = generateEphemeralKeyPair()
                        if (kp != null) {
                            myEphemeralPub = kp.first
                            myEphemeralPriv = kp.second
                            val myEkPubHex = bytesToHex(kp.first)

                            if (authSecret == null) {
                                val myIkPriv = tokenStorage.getPrivateKey()
                                val peerIkPub = fetchPeerIdentityKey(ui.peerUserId)
                                if (myIkPriv != null && peerIkPub != null) {
                                    authSecret = diffieHellman(myIkPriv, peerIkPub)
                                }
                            }

                            if (authSecret != null && version == "2" && callerTag != null) {
                                val expectedTag = computeAuthTag(authSecret!!, "CALL_OFFER:", callerEkPubHex)
                                isE2EEVerified = expectedTag.equals(callerTag, ignoreCase = true)
                            }

                            acceptCallKey = if (authSecret != null) {
                                val tag = computeAuthTag(authSecret!!, "CALL_ACCEPT:", myEkPubHex)
                                "dh:2:$myEkPubHex:$tag"
                            } else {
                                "dh:1:$myEkPubHex"
                            }

                            val callerEkPub = hexToBytes(callerEkPubHex)
                            val sharedDh = diffieHellman(myEphemeralPriv!!, callerEkPub)
                            if (sharedDh != null) {
                                val (masterHex, words) = deriveMediaKeyAndWords(sharedDh, if (isE2EEVerified) authSecret else null)
                                derivedMasterKey = masterHex
                                safetyWords = words
                                _state.value = ui.copy(
                                    isE2EE = true,
                                    isE2EEVerified = isE2EEVerified,
                                    safetyWords = words
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to derive E2EE accept key", e)
                }
            } else if (callKey.isNotBlank()) {
                derivedMasterKey = callKey
                _state.value = ui.copy(isE2EE = true)
            }

            webSocketManager.sendCallAccept(callIdOfIncoming, acceptCallKey)
            connectLiveKit()
        }
    }

    private fun startDialing() {
        if (ui.phase != CallPhase.DIALING) return
        val peerUserId = ui.peerUserId
        val isVideo = ui.isVideo
        val outgoingKey = pendingOutgoingCall?.callKey ?: callKey
        callKey = outgoingKey
        startDialTimeout(peerUserId)
        webSocketManager.sendCallOffer(peerUserId, isVideo, outgoingKey)
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

        val peerCallKey = event.callKey.orEmpty()
        if (peerCallKey.startsWith("dh:")) {
            try {
                val parts = peerCallKey.split(":")
                if (parts.size >= 3) {
                    val version = parts[1]
                    val calleeEkPubHex = parts[2]
                    val calleeTag = if (parts.size > 3) parts[3] else null

                    if (version == "2" && authSecret != null && calleeTag != null) {
                        val expectedTag = computeAuthTag(authSecret!!, "CALL_ACCEPT:", calleeEkPubHex)
                        isE2EEVerified = expectedTag.equals(calleeTag, ignoreCase = true)
                    } else {
                        isE2EEVerified = false
                    }

                    val calleeEkPub = hexToBytes(calleeEkPubHex)
                    if (myEphemeralPriv != null) {
                        val sharedDh = diffieHellman(myEphemeralPriv!!, calleeEkPub)
                        if (sharedDh != null) {
                            val (masterHex, words) = deriveMediaKeyAndWords(sharedDh, if (isE2EEVerified) authSecret else null)
                            derivedMasterKey = masterHex
                            safetyWords = words
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to derive E2EE key in onAccepted", e)
            }
        } else if (peerCallKey.isNotBlank()) {
            derivedMasterKey = peerCallKey
        } else if (callKey.isNotBlank() && !callKey.startsWith("dh:")) {
            derivedMasterKey = callKey
        }

        _state.value = ui.copy(
            phase = CallPhase.CONNECTING,
            isE2EE = derivedMasterKey.isNotBlank(),
            isE2EEVerified = isE2EEVerified,
            safetyWords = safetyWords
        )
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
     * The server replays the call this device still owns right after the
     * signaling socket comes back. A Wi-Fi to mobile handover kills the socket
     * for a few seconds while the media session survives, so all this has to do
     * is re-sync the call id and keep the timer continuous.
     */
    fun onCallState(event: WebSocketEvent.CallState) {
        if (event.callId.isEmpty()) return
        if (ui.phase == CallPhase.IDLE) {
            // Nothing left locally to rejoin (process death, room released), so
            // release the call instead of leaving the peer talking to nobody.
            if (event.accepted) {
                webSocketManager.sendCallEnd(event.callId, event.peerUserId)
            }
            return
        }
        currentCallId = event.callId
        if (callKey.isEmpty() && !event.callKey.isNullOrEmpty()) {
            callKey = event.callKey
            _state.value = ui.copy(isE2EE = true)
        }
        if (event.answeredAt > 0L) {
            resumeTimer(event.answeredAt * 1000L)
        }
    }

    /** The peer's signaling link dropped or came back mid-call. */
    fun onPeerLinkState(event: WebSocketEvent.CallPeerState) {
        if (ui.phase != CallPhase.ACTIVE && ui.phase != CallPhase.CONNECTING) return
        if (!isCurrentCall(event.callId)) return
        if (ui.peerOnline == event.online) return
        _state.value = ui.copy(peerOnline = event.online)
        toast(if (event.online) "Собеседник снова в сети" else "Собеседник теряет связь…")
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
        val turningOff = !ui.cameraOff
        if (!turningOff && ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            toast("Нет разрешения на камеру")
            return
        }
        scope.launch {
            try {
                room.localParticipant.setCameraEnabled(!turningOff)
            } catch (e: Exception) {
                Log.e(TAG, "toggleCamera failed", e)
                toast("Не удалось переключить камеру")
            }
            // Always re-derive from the publication: on failure the flag must
            // show what is really being sent, not what was requested.
            publishLocalVideoTrack()
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
            Log.i(TAG, "LiveKit connect attempt ${index + 1}/${urls.size}: $url")
            try {
                candidate = createRoom()
                // NOTE: do NOT bind the process to a physical network here.
                // WebRTC still gathers candidates against the tun0 interface and
                // changing the network mid-ICE breaks the handshake. Handle VPN by
                // warning the user before the call instead.
                withTimeout(LIVEKIT_CONNECT_TIMEOUT_MS) { candidate.connect(url, token) }
                room = candidate
                Log.i(TAG, "LiveKit connected via $url (state=${candidate.state})")
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

    /** Returns true when the active network is a VPN tunnel (no NOT_VPN capability). */
    private fun isVpnActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    private fun createRoom(): Room {
        ensureWebRtcLoaded()
        val mediaKey = derivedMasterKey.ifEmpty {
            if (!callKey.startsWith("dh:")) callKey else ""
        }
        val e2eeOptions = if (mediaKey.isNotBlank()) {
            try {
                val keyProvider = io.livekit.android.e2ee.BaseKeyProvider()
                keyProvider.setSharedKey(mediaKey)
                io.livekit.android.e2ee.E2EEOptions(keyProvider = keyProvider)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize E2EE KeyProvider: ${t.message}", t)
                null
            }
        } else {
            null
        }
        val roomOptions = RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            e2eeOptions = e2eeOptions
        )
        val r = LiveKit.create(
            context,
            roomOptions,
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
                is RoomEvent.Connected ->
                    Log.i(TAG, "LiveKit room connected")
                is RoomEvent.FailedToConnect ->
                    Log.e(TAG, "LiveKit room connect failed", event.error)
                is RoomEvent.ParticipantConnected ->
                    Log.i(TAG, "Peer entered room: identity=${event.participant.identity}")
                is RoomEvent.ParticipantDisconnected ->
                    Log.i(TAG, "Peer left room: identity=${event.participant.identity}")
                is RoomEvent.ConnectionQualityChanged ->
                    Log.i(TAG, "Connection quality ${event.quality} (identity=${event.participant.identity})")
                is RoomEvent.TrackSubscriptionFailed ->
                    Log.w(TAG, "Track subscription failed sid=${event.sid}", event.exception)
                is RoomEvent.Reconnecting -> {
                    // Media is re-negotiating after a network change. The call is
                    // still alive server-side, so only surface the state.
                    Log.w(TAG, "LiveKit reconnecting, dumping last ICE state")
                    logIceStats("reconnecting")
                    _state.value = ui.copy(isReconnecting = true)
                }
                is RoomEvent.Reconnected -> {
                    // A full reconnect unpublishes and republishes every local
                    // track under a new SID, and the per-track events for what
                    // already existed are not replayed. Rebuild from the room
                    // instead of waiting for notifications that never arrive.
                    Log.i(TAG, "LiveKit reconnected")
                    val wantCamera = !ui.cameraOff
                    _state.value = ui.copy(isReconnecting = false)
                    updateRemoteVideoTrack(room)
                    publishLocalVideoTrack()
                    scope.launch { restoreCameraIfNeeded(wantCamera) }
                }
                is RoomEvent.Disconnected -> {
                    // A failed initial connect also emits Disconnected before
                    // connect() throws; the failover loop owns CONNECTING.
                    // Only an ACTIVE room dropping is a real call end.
                    Log.i(TAG, "LiveKit room disconnected (reason=${event.reason})")
                    logIceStats("disconnected")
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

    /**
     * Mirrors the local camera publication into the exposed track and the UI
     * flag.
     *
     * cameraOff used to be a plain toggle flag, so after a reconnect that failed
     * to republish the camera the button still claimed it was on while no track
     * existed at all.
     */
    private fun publishLocalVideoTrack() {
        val room = room ?: return
        val pub = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
        val track = pub?.track as? VideoTrack
        val live = track != null && pub?.muted != true
        _localVideoTrack.value = if (live) track else null
        if (ui.phase == CallPhase.ACTIVE) {
            _state.value = ui.copy(cameraOff = !live)
        }
    }

    /**
     * Re-enables the camera if it was meant to be on but no track survived the
     * reconnect. LiveKit's republish silently swallows a failed capture restart,
     * so without this retry the camera stays gone for the rest of the call.
     */
    private suspend fun restoreCameraIfNeeded(wantCamera: Boolean) {
        if (!wantCamera) return
        val room = room ?: return
        val pub = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
        if (pub?.track != null && pub.muted != true) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            room.localParticipant.setCameraEnabled(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore camera after reconnect", e)
            toast("Не удалось восстановить камеру")
        }
        publishLocalVideoTrack()
    }

    private suspend fun onRoomConnected() {
        val room = room ?: return
        _state.value = ui.copy(
            phase = CallPhase.ACTIVE,
            isReconnecting = false,
            isE2EE = derivedMasterKey.isNotBlank() || callKey.isNotBlank(),
            isE2EEVerified = isE2EEVerified,
            safetyWords = safetyWords
        )
        playConnectedTone()
        startTimer()
        updateRemoteVideoTrack(room)
        scheduleIceStatsSampling()
        try {
            room.localParticipant.setMicrophoneEnabled(true)
            if (ui.isVideo) {
                room.localParticipant.setCameraEnabled(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish local tracks", e)
        }
        // Derives cameraOff from the actual publication, so a failed camera
        // start cannot leave the button claiming the camera is live.
        publishLocalVideoTrack()
    }

    // --- ICE / media diagnostics ---

    /**
     * Samples WebRTC stats a few times after the room is up. A succeeded
     * candidate pair with frozen byte counters means "connected but no media
     * flows"; the pair addresses show which path was actually selected
     * (tunnel interface vs public path).
     */
    private fun scheduleIceStatsSampling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            val marks = listOf(3_000L, 10_000L, 30_000L, 90_000L)
            var prev = 0L
            for (mark in marks) {
                delay(mark - prev)
                prev = mark
                if (room != null) logIceStats("t+${mark / 1000}s")
            }
        }
    }

    private fun logIceStats(stage: String) {
        val r = room ?: return
        try {
            r.getSubscriberRTCStats(RTCStatsCollectorCallback { report ->
                dumpIceStats(stage, "subscriber", report)
            })
            r.getPublisherRTCStats(RTCStatsCollectorCallback { report ->
                dumpIceStats(stage, "publisher", report)
            })
        } catch (e: Exception) {
            Log.w(TAG, "ICE stats collection failed at $stage", e)
        }
    }

    private fun dumpIceStats(stage: String, pc: String, report: RTCStatsReport) {
        try {
            val stats = report.statsMap.values

            // All candidates gathered so far (local host/srflx/prflx and remote).
            val localCands = stats.filter { it.type == "local-candidate" }
            val remoteCands = stats.filter { it.type == "remote-candidate" }
            if (localCands.isNotEmpty() || remoteCands.isNotEmpty()) {
                Log.i(TAG, "ICE[$pc $stage] candidates:")
                for (c in localCands) {
                    Log.i(
                        TAG,
                        "  LOCAL  ${fmtCandidate(c)}"
                    )
                }
                for (c in remoteCands) {
                    Log.i(
                        TAG,
                        "  REMOTE ${fmtCandidate(c)}"
                    )
                }
            }

            // All candidate pairs with their state (failed / inprogress / succeeded / cancelled).
            val pairs = stats.filter { it.type == "candidate-pair" }
            if (pairs.isNotEmpty()) {
                Log.i(TAG, "ICE[$pc $stage] pairs:")
                val byId = stats.associateBy { it.id }
                for (pair in pairs) {
                    val local = byId[pair.members["localCandidateId"] as? String]
                    val remote = byId[pair.members["remoteCandidateId"] as? String]
                    Log.i(
                        TAG,
                        "  ${pair.members["state"]} selected=${pair.members["selected"]} " +
                            "nominated=${pair.members["nominated"]} " +
                            "local=[${fmtCandidate(local)}] remote=[${fmtCandidate(remote)}] " +
                            "bytesSent=${pair.members["bytesSent"]} bytesReceived=${pair.members["bytesReceived"]} " +
                            "rtt=${pair.members["currentRoundTripTime"]}"
                    )
                }
            }

            val succeeded = pairs.any {
                it.members["state"] == "succeeded" && it.members["selected"] == true
            }
            if (pairs.isEmpty() || !succeeded) {
                Log.w(TAG, "ICE[$pc $stage]: no succeeded selected candidate pair")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ICE stats parsing failed at $stage/$pc", e)
        }
    }

    private fun fmtCandidate(s: RTCStats?): String {
        if (s == null) return "?"
        val m = s.members
        val type = m["candidateType"]
        val addr = m["address"] ?: m["ip"]
        val port = m["port"]
        val proto = m["protocol"]
        val net = m["networkType"]
        return "$type $addr:$port $proto net=$net"
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
        startTimerFrom(System.currentTimeMillis())
    }

    /**
     * Continues the call timer from the server-provided answer time so a
     * reconnect mid-call does not restart the duration from 00:00.
     */
    private fun resumeTimer(startMs: Long) {
        if (startMs <= 0L) return
        startTimerFrom(startMs)
    }

    private fun startTimerFrom(startMs: Long) {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                val secs = ((System.currentTimeMillis() - startMs) / 1000).toInt().coerceAtLeast(0)
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
        statsJob?.cancel()
        statsJob = null
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
        callKey = ""
        derivedMasterKey = ""
        isE2EEVerified = false
        safetyWords = emptyList()
        myEphemeralPub = null
        if (myEphemeralPriv != null) {
            if (RustCryptoCore.isAvailable()) RustCryptoCore.zeroize(myEphemeralPriv!!)
            myEphemeralPriv = null
        }
        if (authSecret != null) {
            if (RustCryptoCore.isAvailable()) RustCryptoCore.zeroize(authSecret!!)
            authSecret = null
        }
        callIdOfIncoming = ""
        currentCallId = ""
        pendingOutgoingCall = null
        pendingAccept = false
        _state.value = CallUiState()
    }
}
