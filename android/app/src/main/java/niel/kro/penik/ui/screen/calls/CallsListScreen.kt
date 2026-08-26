package niel.kro.penik.ui.screen.calls

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.data.network.api.CallLogItemResponse
import niel.kro.penik.ui.components.UserAvatar
import niel.kro.penik.ui.theme.LocalAppColors
import niel.kro.penik.ui.viewmodel.CallsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsListScreen(
    onChatClick: (Long, String) -> Unit,
    viewModel: CallsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val calls by viewModel.calls.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val userAvatarKeys by niel.kro.penik.data.repository.AvatarCacheBus.userAvatarKeys.collectAsState()

    var pendingCallUser by remember { mutableStateOf<Triple<Long, String, Boolean>?>(null) }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val userCall = pendingCallUser
        pendingCallUser = null
        if (userCall == null) return@rememberLauncherForActivityResult
        val (userId, name, isVideo) = userCall
        val micGranted = granted[android.Manifest.permission.RECORD_AUDIO] == true
        val camGranted = !isVideo || granted[android.Manifest.permission.CAMERA] == true
        if (micGranted && camGranted) {
            viewModel.startCall(userId, name, isVideo)
        } else {
            android.widget.Toast.makeText(context, "Нет разрешений для звонка", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun startCallWithPermissions(userId: Long, name: String, isVideo: Boolean) {
        val needed = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (isVideo) needed.add(android.Manifest.permission.CAMERA)
        val missing = needed.filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            viewModel.startCall(userId, name, isVideo)
        } else {
            pendingCallUser = Triple(userId, name, isVideo)
            callPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "Звонки",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = LocalAppColors.current.panel,
                titleContentColor = LocalAppColors.current.textPrimary
            )
        )

        if (isLoading && calls.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = LocalAppColors.current.accent)
            }
        } else if (calls.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📞",
                        fontSize = 48.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Text(
                        text = "Здесь будут ваши звонки",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = LocalAppColors.current.textPrimary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Совершайте аудио и видеозвонки в высоком качестве прямо из личных чатов.",
                        color = LocalAppColors.current.textMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(calls, key = { it.callId }) { call ->
                    CallHistoryItemRow(
                        call = call,
                        avatarKey = userAvatarKeys[call.peerId],
                        onItemClick = {
                            val name = call.peerName.ifBlank { call.peerNickname.ifBlank { "Пользователь" } }
                            onChatClick(call.peerId, name)
                        },
                        onAudioCall = {
                            val name = call.peerName.ifBlank { call.peerNickname.ifBlank { "Пользователь" } }
                            startCallWithPermissions(call.peerId, name, false)
                        },
                        onVideoCall = {
                            val name = call.peerName.ifBlank { call.peerNickname.ifBlank { "Пользователь" } }
                            startCallWithPermissions(call.peerId, name, true)
                        }
                    )
                    HorizontalDivider(color = LocalAppColors.current.border.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun CallHistoryItemRow(
    call: CallLogItemResponse,
    avatarKey: Long?,
    onItemClick: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    val isOutgoing = call.isOutgoing
    val isMissed = when (call.status) {
        "missed" -> !isOutgoing
        "declined" -> isOutgoing
        "cancelled" -> !isOutgoing
        else -> false
    }

    val durationStr = if (call.duration > 0) {
        val m = call.duration / 60
        val s = call.duration % 60
        if (m == 0L) " сек" else " мин" + (if (s > 0) "  сек" else "")
    } else ""

    val statusTitle = when (call.status) {
        "completed" -> (if (isOutgoing) "Исходящий" else "Входящий") + (if (durationStr.isNotEmpty()) " ()" else "")
        "missed" -> if (isOutgoing) "Не отвечен" else "Пропущенный"
        "declined" -> if (isOutgoing) "Отклонен" else "Отклоненный"
        "cancelled" -> if (isOutgoing) "Отмененный" else "Пропущенный"
        "busy" -> if (isOutgoing) "Занято" else "Пропущенный (занято)"
        else -> if (isOutgoing) "Исходящий" else "Входящий"
    }

    val timeFormatted = remember(call.startedAt) {
        val date = Date(call.startedAt * 1000L)
        val now = Date()
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date) == SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        if (sameDay) {
            "Сегодня, "
        } else {
            SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(date)
        }
    }

    val peerName = call.peerName.ifBlank { call.peerNickname.ifBlank { "Пользователь" } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            userId = call.peerId,
            name = peerName,
            size = 48.dp,
            avatarKey = avatarKey
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peerName,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = if (isMissed) Color(0xFFEF5350) else LocalAppColors.current.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        isMissed -> Icons.Default.PhoneMissed
                        isOutgoing -> Icons.Default.PhoneForwarded
                        else -> Icons.Default.PhoneCallback
                    },
                    contentDescription = null,
                    tint = if (isMissed) Color(0xFFEF5350) else LocalAppColors.current.textMuted,
                    modifier = Modifier.size(14.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = " · ",
                    fontSize = 13.sp,
                    color = if (isMissed) Color(0xFFEF5350) else LocalAppColors.current.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onAudioCall) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = "Позвонить",
                tint = LocalAppColors.current.accent,
                modifier = Modifier.size(22.dp)
            )
        }

        IconButton(onClick = onVideoCall) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = "Видеозвонок",
                tint = LocalAppColors.current.accent,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
