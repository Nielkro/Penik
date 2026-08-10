package niel.kro.penik.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import niel.kro.penik.ui.theme.Accent
import niel.kro.penik.ui.theme.Background
import niel.kro.penik.ui.theme.Danger
import niel.kro.penik.ui.theme.PanelSecondary
import niel.kro.penik.ui.theme.SentMessageBg
import niel.kro.penik.ui.theme.SentMessageText
import niel.kro.penik.ui.theme.TextMuted
import niel.kro.penik.ui.theme.TextPrimary
import niel.kro.penik.ui.theme.Warning
import niel.kro.penik.data.network.websocket.ConnectionState
import niel.kro.penik.data.crypto.E2EECrypto
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun initialsColor(id: Long, name: String): Color {
    val hue = if (id != 0L) {
        (id * 137) % 360
    } else {
        name.fold(0L) { acc, ch -> acc + ch.code } % 360
    }
    return Color.hsl(hue.toFloat(), 0.55f, 0.50f)
}

private fun initialsText(name: String): String {
    val cleanName = name.replace(Regex("[^\\p{L}\\p{N}\\s]"), "").trim()
    if (cleanName.isBlank()) return "?"
    return cleanName.split(" ")
        .mapNotNull { it.firstOrNull() }
        .take(2)
        .joinToString("")
        .uppercase()
}

// avatarUrlFor builds the same avatar URL UserAvatar/GroupAvatar load, so
// callers (e.g. a tap-to-view-fullscreen handler) can reference the exact
// image being displayed without duplicating the URL scheme.
fun avatarUrlFor(isGroup: Boolean, id: Long, avatarKey: Any? = null): String {
    return if (isGroup) {
        niel.kro.penik.data.network.api.ApiConfig.getGroupAvatarUrl(id, avatarKey)
    } else {
        niel.kro.penik.data.network.api.ApiConfig.getUserAvatarUrl(id, avatarKey)
    }
}

// FullscreenImageViewer shows `url` full-screen in a dialog, dismissed by
// tapping anywhere outside the image or the close button.
@Composable
fun FullscreenImageViewer(url: String?, onDismiss: () -> Unit) {
    if (url == null) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
                contentScale = ContentScale.Fit
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
            }
        }
    }
}

@Composable
fun UserAvatar(
    userId: Long,
    name: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    avatarKey: Any? = null
) {
    if (name == "Избранное") {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0xFF5FA8DF)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(size * 0.45f)) {
                val w = this.size.width
                val h = this.size.height
                val strokeWidth = w * 0.12f
                val path = Path().apply {
                    moveTo(w * 0.2f, h * 0.1f)
                    lineTo(w * 0.8f, h * 0.1f)
                    lineTo(w * 0.8f, h * 0.9f)
                    lineTo(w * 0.5f, h * 0.65f)
                    lineTo(w * 0.2f, h * 0.9f)
                    close()
                }
                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
        return
    }

    val avatarUrl = avatarUrlFor(isGroup = false, id = userId, avatarKey = avatarKey)

    SubcomposeAsyncImage(
        model = avatarUrl,
        contentDescription = "Avatar",
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        contentScale = ContentScale.Crop
    ) {
        val state = painter.state
        if (state is coil.compose.AsyncImagePainter.State.Loading || state is coil.compose.AsyncImagePainter.State.Error) {
            InitialsAvatar(name = name, id = userId, size = size)
        } else {
            SubcomposeAsyncImageContent()
        }
    }
}

@Composable
fun GroupAvatar(
    groupId: Long,
    name: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    avatarKey: Any? = null
) {
    val avatarUrl = avatarUrlFor(isGroup = true, id = groupId, avatarKey = avatarKey)

    Box(modifier = modifier.size(size)) {
        SubcomposeAsyncImage(
            model = avatarUrl,
            contentDescription = "Group Avatar",
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        ) {
            val state = painter.state
            if (state is coil.compose.AsyncImagePainter.State.Loading || state is coil.compose.AsyncImagePainter.State.Error) {
                InitialsAvatar(name = name, id = groupId, size = size)
            } else {
                SubcomposeAsyncImageContent()
            }
        }

        val badgeSize = size * 0.4f
        Box(
            modifier = Modifier
                .size(badgeSize)
                .align(Alignment.BottomEnd)
                .clip(CircleShape)
                .background(Color(0xFF00E676)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Group,
                contentDescription = null,
                tint = Color(0xFF121214),
                modifier = Modifier.size(badgeSize * 0.62f)
            )
        }
    }
}

@Composable
fun InitialsAvatar(
    name: String,
    id: Long = 0L,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    if (name.contains("Избранное")) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0xFF5FA8DF)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(size * 0.45f)) {
                val w = this.size.width
                val h = this.size.height
                val strokeWidth = w * 0.12f
                val path = Path().apply {
                    moveTo(w * 0.2f, h * 0.1f)
                    lineTo(w * 0.8f, h * 0.1f)
                    lineTo(w * 0.8f, h * 0.9f)
                    lineTo(w * 0.5f, h * 0.65f)
                    lineTo(w * 0.2f, h * 0.9f)
                    close()
                }
                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
        return
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(initialsColor(id, name)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initialsText(name),
            color = Color.White,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// Collapse newlines and runs of whitespace into single spaces so a multiline
// message renders as one flowing line in a maxLines=1 preview.
private val WHITESPACE_RUN = Regex("\\s+")
fun messagePreview(text: String): String {
    val attachment = parseFileAttachment(text)
    if (attachment != null) {
        if (attachment.caption.isNotBlank()) return attachment.caption.replace(WHITESPACE_RUN, " ").trim()
        if (attachment.mime.startsWith("image/")) return "📷 Фото"
        if (attachment.mime.startsWith("video/")) return "🎬 Видео"
        return "📎 ${attachment.name}"
    }
    return text.replace(WHITESPACE_RUN, " ").trim()
}

@Composable
fun ChatListItem(
    name: String,
    userId: Long,
    lastMessage: String?,
    timestamp: Long?,
    unreadCount: Int,
    isGroup: Boolean = false,
    avatarKey: Any? = null,
    onClick: () -> Unit,
    onAvatarClick: ((String) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val avatarModifier = if (onAvatarClick != null) {
            Modifier.clickable { onAvatarClick(avatarUrlFor(isGroup, userId, avatarKey)) }
        } else {
            Modifier
        }
        Box(modifier = avatarModifier) {
            if (isGroup) {
                GroupAvatar(groupId = userId, name = name, size = 48.dp, avatarKey = avatarKey)
            } else {
                UserAvatar(userId = userId, name = name, size = 48.dp, avatarKey = avatarKey)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (lastMessage != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = messagePreview(lastMessage),
                    color = TextMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (unreadCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SearchUserItem(
    name: String,
    userId: Long,
    nickname: String,
    lastMessage: String? = null,
    timestamp: Long? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InitialsAvatar(name = name, id = userId, size = 48.dp)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name.ifBlank { nickname },
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (lastMessage != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = messagePreview(lastMessage),
                    color = TextMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (nickname.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "@$nickname",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            }
        }

        if (timestamp != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatTime(timestamp),
                color = TextMuted,
                fontSize = 12.sp
            )
        }
    }
}

/** Normalise a timestamp that may be in seconds or milliseconds to milliseconds. */
private fun toMs(timestamp: Long): Long =
    if (timestamp in 1L..9_999_999_999L) timestamp * 1000L else timestamp

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(toMs(timestamp)))
}

private fun formatFullTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("d MMMM yyyy, HH:mm:ss", Locale("ru"))
    return sdf.format(Date(toMs(timestamp)))
}

private val URL_REGEX = Regex("""https?://[^\s<>"']+""")

/** Build an AnnotatedString where http(s) URLs become clickable spans. */
private fun buildLinkedText(text: String, linkColor: Color): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var lastEnd = 0
        for (match in URL_REGEX.findAll(text)) {
            val start = match.range.first
            val rawUrl = match.value
            // Strip trailing punctuation that likely isn't part of the URL.
            var url = rawUrl
            var trail = ""
            while (url.isNotEmpty() && url.last() in ".,;:!?)]") {
                trail = url.last() + trail
                url = url.dropLast(1)
            }
            if (start > lastEnd) {
                append(text.substring(lastEnd, start))
            }
            if (url.isNotEmpty()) {
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(url)
                }
                pop()
            }
            if (trail.isNotEmpty()) {
                append(trail)
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) {
            append(text.substring(lastEnd))
        }
    }
}

private data class FileAttachment(
    val url: String,
    val name: String,
    val size: Long?,
    val mime: String,
    val key: String,
    val thumb: String?,
    val caption: String
)

private fun parseFileAttachment(text: String): FileAttachment? = runCatching {
    val root = Json.parseToJsonElement(text).jsonObject
    if (root["type"]?.jsonPrimitive?.content != "file") return null
    val file = root["file"]?.jsonObject ?: return null
    val url = file["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
    val key = file["key"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
    FileAttachment(
        url = url,
        name = file["name"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Файл" },
        size = file["size"]?.jsonPrimitive?.content?.toLongOrNull(),
        mime = file["mime"]?.jsonPrimitive?.content.orEmpty(),
        key = key,
        thumb = file["thumb"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
        caption = root["text"]?.jsonPrimitive?.content.orEmpty()
    )
}.getOrNull()

internal data class ReplyParsedInfo(
    val displayText: String,
    val thumbBase64: String?
)

internal fun parseReplyContent(rawText: String?): ReplyParsedInfo? {
    if (rawText.isNullOrBlank()) return null
    val attachment = parseFileAttachment(rawText)
    if (attachment != null) {
        val isImage = attachment.mime.startsWith("image/")
        val isVideo = attachment.mime.startsWith("video/")
        val label = when {
            isImage -> if (attachment.caption.isNotBlank()) attachment.caption else "Фотография"
            isVideo -> if (attachment.caption.isNotBlank()) attachment.caption else "Видео"
            else -> if (attachment.caption.isNotBlank()) attachment.caption else attachment.name.ifBlank { "Файл" }
        }
        return ReplyParsedInfo(displayText = label, thumbBase64 = attachment.thumb)
    }
    return ReplyParsedInfo(displayText = rawText, thumbBase64 = null)
}

private fun formatFileSize(size: Long?): String = when {
    size == null -> ""
    size < 1024 -> "$size Б"
    size < 1024 * 1024 -> "${size / 1024} КБ"
    else -> String.format(Locale.getDefault(), "%.1f МБ", size / (1024f * 1024f))
}

private suspend fun downloadAndDecryptAttachment(context: Context, attachment: FileAttachment): File =
    withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val extension = attachment.name.substringAfterLast('.', "").take(16)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((attachment.url + attachment.key).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val output = File(cacheDir, "$digest${if (extension.isBlank()) "" else ".$extension"}")
        if (output.isFile && output.length() > 0) return@withContext output

        val encrypted = (URL(attachment.url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            inputStream.use { it.readBytes() }
        }
        val key = Base64.getDecoder().decode(attachment.key)
        val plaintext = E2EECrypto().decryptFileChaCha20(encrypted, key)
        val temporary = File(cacheDir, "${output.name}.tmp")
        temporary.outputStream().use { it.write(plaintext) }
        if (!temporary.renameTo(output)) {
            temporary.delete()
            throw IllegalStateException("Unable to cache attachment")
        }
        output
    }

@Composable
private fun LocalVideoPlayer(file: File, contentDescription: String, onOpenFullscreen: () -> Unit) {
    val context = LocalContext.current
    var videoSize by remember(file) { mutableStateOf(VideoSize.UNKNOWN) }
    var isHovered by remember { mutableStateOf(false) }

    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.pause()
                    player.seekTo(0)
                }
            }
        }
        player.addListener(listener)
        videoSize = player.videoSize
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(isHovered) {
        if (isHovered) {
            player.seekTo(0)
            player.play()
            kotlinx.coroutines.delay(5000)
            player.pause()
            player.seekTo(0)
        } else {
            player.pause()
            player.seekTo(0)
        }
    }

    val calculatedRatio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio /
        videoSize.height.coerceAtLeast(1).toFloat()).takeIf { it > 0f } ?: (16f / 9f)
    val aspectRatio = calculatedRatio.coerceIn(0.6f, 2.5f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF14141C))
            .clickable(onClick = onOpenFullscreen)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            androidx.compose.ui.input.pointer.PointerEventType.Enter -> isHovered = true
                            androidx.compose.ui.input.pointer.PointerEventType.Exit -> isHovered = false
                        }
                    }
                }
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    this.player = player
                    useController = false
                    controllerAutoShow = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    this.contentDescription = contentDescription
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.player = player
                it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        )

        // Overlay play icon when idle/not playing preview
        if (!isHovered) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Воспроизвести",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

private fun formatVideoTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}

@Composable
private fun LocalVideoViewer(file: File, contentDescription: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekProgress by remember { mutableStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastUserActivity by remember { mutableStateOf(System.currentTimeMillis()) }

    val showControls: () -> Unit = {
        controlsVisible = true
        lastUserActivity = System.currentTimeMillis()
    }

    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playWhenReady = true
            prepare()
        }
    }

    val togglePlay = {
        showControls()
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration.coerceAtLeast(0L)
                } else if (playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                    controlsVisible = true
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, isSeeking) {
        while (!isSeeking) {
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            kotlinx.coroutines.delay(200)
        }
    }

    // Auto-hide controls after 2 seconds of inactivity when playing
    LaunchedEffect(lastUserActivity, isPlaying, isSeeking) {
        if (isPlaying && !isSeeking) {
            kotlinx.coroutines.delay(2000)
            controlsVisible = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (!controlsVisible) {
                        showControls()
                    } else {
                        onDismiss()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.90f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = {
                            PlayerView(it).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                this.player = player
                                useController = false
                                controllerAutoShow = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                this.contentDescription = contentDescription
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (controlsVisible) {
                                    togglePlay()
                                } else {
                                    showControls()
                                }
                            },
                        update = {
                            it.player = player
                            it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Custom control bar styled like web showFullscreenMedia with auto-hide animation
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlsVisible,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 540.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xE614141C))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { togglePlay() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Text(
                            text = formatVideoTime(if (isSeeking) (seekProgress * duration).toLong() else currentPosition),
                            color = Color(0xFFE2E2E9),
                            fontSize = 12.sp,
                            modifier = Modifier.widthIn(min = 36.dp)
                        )

                        androidx.compose.material3.Slider(
                            value = if (isSeeking) seekProgress else (if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f),
                            onValueChange = {
                                showControls()
                                isSeeking = true
                                seekProgress = it
                            },
                            onValueChangeFinished = {
                                player.seekTo((seekProgress * duration).toLong())
                                isSeeking = false
                                showControls()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = Color(0xFF22C55E),
                                activeTrackColor = Color(0xFF22C55E),
                                inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                            )
                        )

                        Text(
                            text = formatVideoTime(duration),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            modifier = Modifier.widthIn(min = 36.dp)
                        )

                        IconButton(
                            onClick = {
                                showControls()
                                isMuted = !isMuted
                                player.volume = if (isMuted) 0f else 1f
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = if (isMuted) "Включить звук" else "Выключить звук",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun LocalImageViewer(file: File, contentDescription: String, onDismiss: () -> Unit) {
    var scale by remember(file) { mutableStateOf(1f) }
    var offsetX by remember(file) { mutableStateOf(0f) }
    var offsetY by remember(file) { mutableStateOf(0f) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = file,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(file) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale == 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        }
                    }
                    .offset { androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()) }
                    .scale(scale),
                contentScale = ContentScale.Fit
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
            }
        }
    }
}

@Composable
private fun FileAttachmentContent(attachment: FileAttachment, textColor: Color) {
    val context = LocalContext.current
    val isImage = attachment.mime.startsWith("image/")
    val isVideo = attachment.mime.startsWith("video/")
    var localFile by remember(attachment.url, attachment.key) { mutableStateOf<File?>(null) }
    var loadError by remember(attachment.url, attachment.key) { mutableStateOf(false) }
    var showImageViewer by remember(attachment.url, attachment.key) { mutableStateOf(false) }
    var showVideoViewer by remember(attachment.url, attachment.key) { mutableStateOf(false) }

    LaunchedEffect(attachment.url, attachment.key) {
        runCatching { downloadAndDecryptAttachment(context, attachment) }
            .onSuccess { localFile = it }
            .onFailure { loadError = true }
    }

    val openFile: () -> Unit = {
        localFile?.let { file ->
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, attachment.mime.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }
    Column(modifier = if (isImage || isVideo) Modifier else Modifier.clickable(enabled = localFile != null, onClick = openFile)) {
        when {
            isVideo && localFile != null -> LocalVideoPlayer(localFile!!, attachment.name, onOpenFullscreen = { showVideoViewer = true })
            isImage -> AsyncImage(
                model = localFile,
                contentDescription = attachment.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = localFile != null) { showImageViewer = true },
                contentScale = ContentScale.FillWidth
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = textColor)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(attachment.name, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val size = formatFileSize(attachment.size)
                    if (size.isNotEmpty()) Text(size, color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        if (localFile == null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(if (loadError) "Не удалось загрузить файл" else "Загрузка…", color = TextMuted, fontSize = 12.sp)
        }
        if (attachment.caption.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(attachment.caption, color = textColor, fontSize = 15.sp)
        }
    }

    if (showImageViewer && localFile != null) {
        LocalImageViewer(file = localFile!!, contentDescription = attachment.name) {
            showImageViewer = false
        }
    }

    if (showVideoViewer && localFile != null) {
        LocalVideoViewer(file = localFile!!, contentDescription = attachment.name) {
            showVideoViewer = false
        }
    }
}

@Composable
fun MessageBubble(
    text: String,
    timestamp: Long,
    isSentByMe: Boolean,
    delivered: Boolean,
    deliveredAt: Long? = null,
    read: Boolean = false,
    senderName: String? = null,
    senderUserId: Long? = null,
    isSelfChat: Boolean = false,
    replyToMsgId: String? = null,
    replySender: String? = null,
    replyText: String? = null,
    onReply: (() -> Unit)? = null,
    onReplyClick: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val isFailed = text.startsWith("[Ошибка расшифрования") || text.startsWith("[Сообщение не расшифровано")
    var isExpanded by remember { mutableStateOf(false) }
    var showFullTime by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val attachment = remember(text) { parseFileAttachment(text) }
    val parsedText = attachment?.caption ?: text

    val bgColor = if (isFailed) {
        Color(0x26EF5350)
    } else if (isSentByMe) {
        SentMessageBg
    } else {
        PanelSecondary
    }

    val textColor = if (isFailed) {
        Color(0xFFEF5350)
    } else if (isSentByMe) {
        SentMessageText
    } else {
        TextPrimary
    }

    val linkColor = if (isSentByMe) {
        Color(0xFFB8D4FF)
    } else {
        Accent
    }

    val alignment = if (isSentByMe) Alignment.End else Alignment.Start

    val doCopy: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("message", parsedText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    var offsetX by remember { mutableStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }

    val isMediaAttachment = attachment?.mime?.let { it.startsWith("image/") || it.startsWith("video/") } == true
    val bubbleModifier = Modifier.widthIn(max = 280.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        val startPadding = if (isSentByMe) 10.dp else 14.dp
        val endPadding = if (isSentByMe) 10.dp else 10.dp
        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.toInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (triggered && onReply != null) {
                                onReply()
                            }
                            offsetX = 0f
                            triggered = false
                        },
                        onDragCancel = {
                            offsetX = 0f
                            triggered = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (dragAmount < 0 || offsetX < 0) {
                                val newOffset = (offsetX + dragAmount * 0.5f).coerceIn(-160f, 0f)
                                offsetX = newOffset
                                if (newOffset <= -100f && !triggered) {
                                    triggered = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        }
                    )
                }
                .then(bubbleModifier)
                .clip(BubbleShape(isSentByMe))
                .background(bgColor)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true
                    }
                )
                .padding(start = startPadding, top = 5.dp, end = endPadding, bottom = 5.dp)
        ) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(PanelSecondary)
            ) {
                DropdownMenuItem(
                    text = { Text("Копировать", color = TextPrimary) },
                    onClick = {
                        doCopy()
                        showMenu = false
                    }
                )
                if (onReply != null && !isFailed) {
                    DropdownMenuItem(
                        text = { Text("Ответить", color = TextPrimary) },
                        onClick = {
                            onReply()
                            showMenu = false
                        }
                    )
                }
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("Удалить", color = Color(0xFFEF5350)) },
                        onClick = {
                            onDelete()
                            showMenu = false
                        }
                    )
                }
            }
            Column {
                if (!isSentByMe && senderName != null) {
                    val hue = if (senderUserId != null && senderUserId > 0) (senderUserId * 137) % 360 else 0L
                    val nameColor = Color.hsl(hue.toFloat(), 0.65f, 0.65f)
                    Text(
                        text = senderName,
                        color = nameColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                // Reply preview inside message bubble
                if (replySender != null && replyText != null) {
                    val replyInfo = remember(replyText) { parseReplyContent(replyText) }
                    val replyThumbBitmap = remember(replyInfo?.thumbBase64) {
                        replyInfo?.thumbBase64?.let { thumbStr ->
                            runCatching {
                                val base64Data = if (thumbStr.contains(",")) thumbStr.substringAfter(",") else thumbStr
                                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }.getOrNull()
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .background(Color(0x0DFFFFFF), shape = RoundedCornerShape(4.dp))
                            .height(IntrinsicSize.Max)
                            .clickable(enabled = onReplyClick != null && replyToMsgId != null) {
                                replyToMsgId?.let { onReplyClick?.invoke(it) }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(Accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (replyThumbBitmap != null) {
                            Image(
                                bitmap = replyThumbBitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .padding(vertical = 4.dp)
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 5.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = replySender!!,
                                color = if (isSentByMe) Color(0xFFB8D4FF) else Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = replyInfo?.displayText ?: replyText!!,
                                color = TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (attachment != null) {
                    FileAttachmentContent(attachment = attachment, textColor = textColor)
                } else {
                    val isSingleLineShort = !isFailed && !parsedText.contains('\n') && parsedText.length <= 25

                    if (isSingleLineShort) {
                    val annotated = remember(parsedText) { buildLinkedText(parsedText, linkColor) }
                    Text(
                        text = annotated,
                        color = textColor,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showMenu = true
                                }
                            )
                    )
                    } else {
                    if (isFailed) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔒 Не удалось расшифровать",
                                color = textColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (onDelete != null) {
                                Text(
                                    text = "🗑",
                                    color = Color(0xFFEF5350),
                                    fontSize = 16.sp,
                                    modifier = Modifier
                                        .clickable { onDelete() }
                                        .padding(start = 8.dp, end = 4.dp)
                                )
                            }
                        }
                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = parsedText,
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isExpanded) "Свернуть" else "Раскрыть",
                            color = Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { isExpanded = !isExpanded }
                                .padding(vertical = 2.dp)
                        )
                    } else {
                        val annotated = remember(parsedText) { buildLinkedText(parsedText, linkColor) }
                        Text(
                            text = annotated,
                            color = textColor,
                            fontSize = 15.sp,
                            modifier = Modifier.combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showMenu = true
                                }
                            )
                        )
                    }
                }

                    if (!isMediaAttachment || attachment?.caption?.isNotBlank() == true) {
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val timeLabel = if (showFullTime) formatFullTime(timestamp) else formatTime(timestamp)
                            Text(
                                text = timeLabel,
                                color = TextMuted,
                                fontSize = if (showFullTime) 9.sp else 10.sp,
                                modifier = Modifier
                                    .clickable { showFullTime = !showFullTime }
                                    .padding(end = 4.dp),
                                maxLines = 1
                            )
                            if (isSentByMe && !isFailed && !isSelfChat) {
                                val statusColor = if (read) Accent else TextMuted
                                if (read || delivered) {
                                    Box(modifier = Modifier.width(16.dp)) {
                                        Text(
                                            text = "✓",
                                            color = statusColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.offset(x = 0.dp)
                                        )
                                        Text(
                                            text = "✓",
                                            color = statusColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.offset(x = 5.dp)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "✓",
                                        color = statusColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Telegram-style overlay timestamp & status badge for media without caption
            if (isMediaAttachment && attachment?.caption.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 8.dp, end = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val timeLabel = if (showFullTime) formatFullTime(timestamp) else formatTime(timestamp)
                    Text(
                        text = timeLabel,
                        color = Color.White,
                        fontSize = if (showFullTime) 9.sp else 10.sp,
                        modifier = Modifier
                            .clickable { showFullTime = !showFullTime }
                            .padding(end = 4.dp),
                        maxLines = 1
                    )
                    if (isSentByMe && !isFailed && !isSelfChat) {
                        val statusColor = if (read) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.8f)
                        if (read || delivered) {
                            Box(modifier = Modifier.width(16.dp)) {
                                Text(
                                    text = "✓",
                                    color = statusColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.offset(x = 0.dp)
                                )
                                Text(
                                    text = "✓",
                                    color = statusColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.offset(x = 5.dp)
                                )
                            }
                        } else {
                            Text(
                                text = "✓",
                                color = statusColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusBar(connectionState: ConnectionState) {
    val text = when (connectionState) {
        ConnectionState.CONNECTING -> "Устанавливается соединение..."
        ConnectionState.DISCONNECTED -> "Нет соединения"
        ConnectionState.CONNECTED -> return
    }
    val color = when (connectionState) {
        ConnectionState.CONNECTING -> Warning
        else -> Danger
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f))
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 13.sp
        )
    }
}

class BubbleShape(private val isSentByMe: Boolean) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path().apply {
            val width = size.width
            val height = size.height
            val radius = with(density) { 14.dp.toPx() }
            val tailWidth = with(density) { 6.dp.toPx() }
            val ctrlOffset = with(density) { 2.dp.toPx() }

            if (isSentByMe) {
                moveTo(radius, 0f)
                lineTo(width - tailWidth - radius, 0f)
                quadraticTo(width - tailWidth, 0f, width - tailWidth, radius)
                lineTo(width - tailWidth, height - radius)
                quadraticTo(width - tailWidth, height - ctrlOffset, width, height)
                quadraticTo(width - tailWidth + ctrlOffset, height, width - tailWidth - radius, height)
                lineTo(radius, height)
                quadraticTo(0f, height, 0f, height - radius)
                lineTo(0f, radius)
                quadraticTo(0f, 0f, radius, 0f)
            } else {
                moveTo(tailWidth + radius, 0f)
                lineTo(width - radius, 0f)
                quadraticTo(width, 0f, width, radius)
                lineTo(width, height - radius)
                quadraticTo(width, height, width - radius, height)
                lineTo(tailWidth + radius, height)
                quadraticTo(tailWidth - ctrlOffset, height, 0f, height)
                quadraticTo(tailWidth, height - ctrlOffset, tailWidth, height - radius)
                lineTo(tailWidth, radius)
                quadraticTo(tailWidth, 0f, tailWidth + radius, 0f)
            }
            close()
        }
        return Outline.Generic(path)
    }
}
