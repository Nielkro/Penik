package niel.kro.penik.ui.call

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import niel.kro.penik.domain.call.CallManager
import niel.kro.penik.domain.call.CallPhase
import niel.kro.penik.ui.components.UserAvatar

private val CallBackground = Color(0xFF101418)
private val AcceptGreen = Color(0xFF2ECC71)
private val HangupRed = Color(0xFFE74C3C)

@Composable
fun CallOverlay(callManager: CallManager) {
    val state by callManager.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        callManager.toasts.collect { text ->
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    if (state.phase == CallPhase.IDLE) return

    Box(modifier = Modifier.fillMaxSize().background(CallBackground)) {
        when (state.phase) {
            CallPhase.INCOMING -> IncomingCallView(callManager)
            CallPhase.DIALING, CallPhase.CONNECTING -> DialingView(callManager, state.phase == CallPhase.CONNECTING)
            CallPhase.ACTIVE -> ActiveCallView(callManager)
            CallPhase.IDLE -> Unit
        }
    }
}

@Composable
private fun IncomingCallView(callManager: CallManager) {
    val state by callManager.state.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> callManager.acceptCall() }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        UserAvatar(userId = state.peerUserId, name = state.peerName, size = 112.dp)
        Spacer(Modifier.height(24.dp))
        Text(state.peerName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.isVideo) "Входящий видеозвонок" else "Входящий звонок",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp
        )
        Spacer(Modifier.height(56.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(72.dp)) {
            CircleButton(icon = Icons.Default.CallEnd, background = HangupRed, label = "Отклонить") {
                callManager.rejectCall()
            }
            CircleButton(icon = Icons.Default.Call, background = AcceptGreen, label = "Ответить") {
                val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (state.isVideo) needed.add(Manifest.permission.CAMERA)
                val missing = needed.filter {
                    context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) callManager.acceptCall() else permissionLauncher.launch(missing.toTypedArray())
            }
        }
    }
}

@Composable
private fun DialingView(callManager: CallManager, connecting: Boolean) {
    val state by callManager.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        UserAvatar(userId = state.peerUserId, name = state.peerName, size = 112.dp)
        Spacer(Modifier.height(24.dp))
        Text(state.peerName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (connecting) "Подключение..." else "Вызов...",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp
        )
        Spacer(Modifier.height(56.dp))
        CircleButton(icon = Icons.Default.CallEnd, background = HangupRed, label = "Отменить") {
            callManager.endCall()
        }
    }
}

@Composable
private fun ActiveCallView(callManager: CallManager) {
    val state by callManager.state.collectAsState()
    val remoteTrack by callManager.remoteVideoTrack.collectAsState()
    val localTrack by callManager.localVideoTrack.collectAsState()
    var swapped by remember { mutableStateOf(false) }

    val primary = if (swapped) localTrack else remoteTrack
    val pip = if (swapped) remoteTrack else localTrack
    val showLocalPip = pip != null && !(state.cameraOff && !swapped)

    Box(modifier = Modifier.fillMaxSize()) {
        if (primary != null) {
            VideoRenderer(track = primary, eglBase = callManager.eglBase, mirror = swapped, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                UserAvatar(userId = state.peerUserId, name = state.peerName, size = 112.dp)
                Spacer(Modifier.height(16.dp))
                if (!state.isVideo) {
                    Text("Аудиозвонок", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(state.peerName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                state.elapsed.ifEmpty { "Подключение..." },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
        }

        if (showLocalPip) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 120.dp)
                    .width(108.dp)
                    .height(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { swapped = !swapped }
            ) {
                VideoRenderer(
                    track = pip,
                    eglBase = callManager.eglBase,
                    mirror = !swapped,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlButton(
                icon = if (state.micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                active = state.micMuted,
                label = "Микрофон"
            ) { callManager.toggleMic() }
            ControlButton(
                icon = if (state.cameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                active = state.cameraOff,
                label = "Камера"
            ) { callManager.toggleCamera() }
            CircleButton(icon = Icons.Default.CallEnd, background = HangupRed, label = "Завершить") {
                callManager.endCall()
            }
        }
    }
}

@Composable
private fun VideoRenderer(track: VideoTrack?, eglBase: EglBase, mirror: Boolean, modifier: Modifier = Modifier) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setZOrderMediaOverlay(true)
                setMirror(mirror)
                renderer = this
            }
        },
        update = { view -> view.setMirror(mirror) }
    )

    DisposableEffect(track, renderer) {
        val r = renderer
        if (track != null && r != null) {
            track.addRenderer(r)
        }
        onDispose {
            if (track != null && r != null) {
                track.removeRenderer(r)
            }
        }
    }
}

@Composable
private fun CircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(background)
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val background = if (active) Color.White else Color.White.copy(alpha = 0.15f)
    val tint = if (active) Color.Black else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(background)
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}
