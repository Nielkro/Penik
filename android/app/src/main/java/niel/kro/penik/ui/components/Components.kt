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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.TextLayoutResult
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import niel.kro.penik.ui.theme.Accent
import niel.kro.penik.ui.theme.Background
import niel.kro.penik.ui.theme.Border
import niel.kro.penik.ui.theme.Danger
import niel.kro.penik.ui.theme.InputBg
import niel.kro.penik.ui.theme.Panel
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
    val fwdInfo = parseForwardedInfo(text)
    if (fwdInfo != null) {
        val subPreview = messagePreview(fwdInfo.text)
        return "↪ ${fwdInfo.from}: $subPreview"
    }
    val attachment = parseFileAttachment(text)
    if (attachment != null) {
        val fwdSender = parseForwardedFileSender(text)
        val prefix = if (!fwdSender.isNullOrBlank()) "↪ $fwdSender: " else ""
        if (attachment.caption.isNotBlank()) return prefix + attachment.caption.replace(WHITESPACE_RUN, " ").trim()
        if (attachment.mime.startsWith("image/")) return prefix + "📷 Фото"
        if (attachment.mime.startsWith("video/")) return prefix + "🎬 Видео"
        return prefix + "📎 ${attachment.name}"
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

private val URL_REGEX = Regex("""(https?://|www\.)[^\s<>"']+\.[^\s<>"']+|https?://[^\s<>"']+""")

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
                val targetUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "https://$url"
                } else {
                    url
                }
                pushStringAnnotation(tag = "URL", annotation = targetUrl)
                withStyle(SpanStyle(color = linkColor)) {
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

@Composable
private fun ClickableLinkedText(
    text: String,
    textColor: Color,
    linkColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(text, linkColor) { buildLinkedText(text, linkColor) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val haptic = LocalHapticFeedback.current

    Text(
        text = annotated,
        color = textColor,
        fontSize = fontSize,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures(
                onLongPress = {
                    if (onLongClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                },
                onTap = { offset ->
                    layoutResult?.let { textLayoutResult ->
                        val position = textLayoutResult.getOffsetForPosition(offset)
                        annotated.getStringAnnotations(tag = "URL", start = position, end = position)
                            .firstOrNull()?.let { annotation ->
                                try {
                                    uriHandler.openUri(annotation.item)
                                } catch (_: Exception) {}
                            }
                    }
                }
            )
        }
    )
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

internal data class ForwardedInfo(
    val from: String,
    val text: String
)

internal fun parseForwardedInfo(rawText: String): ForwardedInfo? = runCatching {
    if (!rawText.startsWith("{")) return null
    val root = Json.parseToJsonElement(rawText).jsonObject
    if (root["type"]?.jsonPrimitive?.content != "fwd") return null
    val from = root["from"]?.jsonPrimitive?.content.orEmpty().ifBlank { "неизвестного" }
    val text = root["text"]?.jsonPrimitive?.content.orEmpty()
    ForwardedInfo(from = from, text = text)
}.getOrNull()

internal fun parseForwardedFileSender(rawText: String): String? = runCatching {
    if (!rawText.startsWith("{")) return null
    val root = Json.parseToJsonElement(rawText).jsonObject
    root["fwd_from"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
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
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.contentDescription = contentDescription
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.player = player
                it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onReset = { view -> view.player = null },
            onRelease = { view -> view.player = null }
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

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
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
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.pause()
            player.stop()
            player.clearMediaItems()
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
                        },
                        onReset = { view -> view.player = null },
                        onRelease = { view -> view.player = null }
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
            isVideo -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 560.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (localFile != null) {
                    LocalVideoPlayer(localFile!!, attachment.name, onOpenFullscreen = { showVideoViewer = true })
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x33000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (loadError) "Ошибка загрузки" else "Загрузка видео…", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
            isImage -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 560.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                AsyncImage(
                    model = localFile,
                    contentDescription = attachment.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .heightIn(min = 160.dp, max = 560.dp)
                        .clickable(enabled = localFile != null) { showImageViewer = true },
                    contentScale = ContentScale.Fit
                )
                if (localFile == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x22FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (loadError) "Ошибка загрузки" else "Загрузка фото…", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
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
        if (localFile == null && !isImage && !isVideo) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(if (loadError) "Не удалось загрузить файл" else "Загрузка…", color = TextMuted, fontSize = 12.sp)
        }
        if (attachment.caption.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            ClickableLinkedText(
                text = attachment.caption,
                textColor = textColor,
                linkColor = if (textColor == SentMessageText) Color(0xFF64B5F6) else Color(0xFF409CFF),
                fontSize = 15.sp
            )
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
fun MessageTicks(
    delivered: Boolean,
    read: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val isDouble = delivered || read
    if (isDouble) {
        Box(
            modifier = modifier.width(13.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "✓",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = "✓",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.offset(x = 3.5.dp)
            )
        }
    } else {
        Text(
            text = "✓",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = modifier
        )
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
    onDelete: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null
) {
    val isFailed = text.startsWith("[Ошибка расшифрования") || text.startsWith("[Сообщение не расшифровано")
    var isExpanded by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val fwdInfo = remember(text) { parseForwardedInfo(text) }
    val fwdFileSender = remember(text) { parseForwardedFileSender(text) }
    val fwdSenderName = fwdInfo?.from ?: fwdFileSender

    val attachment = remember(text) { parseFileAttachment(text) }
    val parsedText = fwdInfo?.text ?: (attachment?.caption ?: text)

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
        Color(0xFF64B5F6)
    } else {
        Color(0xFF409CFF)
    }

    val boxAlignment = if (isSentByMe) Alignment.CenterEnd else Alignment.CenterStart
    val alignment = if (isSentByMe) Alignment.End else Alignment.Start

    val doCopy: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("message", parsedText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    var offsetX by remember { mutableStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        contentAlignment = boxAlignment
    ) {
        val hasHeader = (!isSentByMe && senderName != null) || fwdSenderName != null || (replySender != null && replyText != null)
        val isMediaNoCaption = attachment != null && (attachment.mime.startsWith("image/") || attachment.mime.startsWith("video/")) && attachment.caption.isNullOrBlank() && !hasHeader
        val maxBubbleWidth = if (attachment != null && (attachment.mime.startsWith("image/") || attachment.mime.startsWith("video/"))) 300.dp else 280.dp

        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.toInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            offsetX = 0f
                            triggered = false
                        },
                        onDragEnd = {
                            if (triggered && onReply != null && !isFailed) {
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
                .widthIn(max = maxBubbleWidth)
                .clip(BubbleShape(isSentByMe = isSentByMe))
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
                .padding(
                    start = if (isMediaNoCaption) 0.dp else 10.dp,
                    end = if (isMediaNoCaption) 0.dp else 10.dp,
                    top = if (isMediaNoCaption) 0.dp else 6.dp,
                    bottom = if (isMediaNoCaption) 0.dp else 6.dp
                )
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
                if (onForward != null && !isFailed) {
                    DropdownMenuItem(
                        text = { Text("Переслать", color = TextPrimary) },
                        onClick = {
                            onForward()
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
            val columnModifier = if (replySender != null && replyText != null) Modifier.width(IntrinsicSize.Max) else Modifier
            Column(modifier = columnModifier) {
                if (fwdSenderName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 2.dp)
                    ) {
                        Text(
                            text = "↪ Переслано от $fwdSenderName",
                            color = Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (!isSentByMe && senderName != null) {
                    val hue = if (senderUserId != null && senderUserId > 0) (senderUserId * 137) % 360 else 0L
                    val nameColor = Color.hsl(hue.toFloat(), 0.65f, 0.65f)
                    Text(
                        text = senderName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = nameColor,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

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
                                .weight(1f, fill = false)
                                .padding(vertical = 5.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = replySender,
                                color = if (isSentByMe) Color(0xFFB8D4FF) else Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = replyInfo?.displayText ?: replyText,
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
                    if (!isMediaNoCaption) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(
                                text = formatTime(timestamp),
                                fontSize = 10.sp,
                                color = if (isSentByMe) SentMessageText else TextMuted
                            )
                            if (isSentByMe) {
                                Spacer(modifier = Modifier.width(4.dp))
                                MessageTicks(
                                    delivered = delivered,
                                    read = read,
                                    color = if (read) Accent else TextMuted
                                )
                            }
                        }
                    }
                } else {
                    val isSingleLineShort = !isFailed && !parsedText.contains('\n') && parsedText.length <= 35
                    if (isSingleLineShort) {
                        val hasReply = replySender != null && replyText != null
                        Row(
                            modifier = if (hasReply) {
                                Modifier.fillMaxWidth().padding(top = 1.dp)
                            } else {
                                Modifier.padding(top = 1.dp)
                            },
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = if (hasReply) Arrangement.SpaceBetween else Arrangement.Start
                        ) {
                            ClickableLinkedText(
                                text = parsedText,
                                textColor = textColor,
                                linkColor = linkColor,
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(end = 8.dp),
                                onLongClick = {
                                    showMenu = true
                                }
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .align(Alignment.Bottom)
                                    .offset(y = 2.5.dp)
                            ) {
                                Text(
                                    text = formatTime(timestamp),
                                    fontSize = 10.sp,
                                    color = if (isSentByMe) SentMessageText else TextMuted
                                )
                                if (isSentByMe) {
                                    Spacer(modifier = Modifier.width(3.dp))
                                    MessageTicks(
                                        delivered = delivered,
                                        read = read,
                                        color = if (read) Accent else TextMuted
                                    )
                                }
                            }
                        }
                    } else {
                        ClickableLinkedText(
                            text = parsedText,
                            textColor = textColor,
                            linkColor = linkColor,
                            fontSize = 15.sp,
                            onLongClick = {
                                showMenu = true
                            }
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(
                                text = formatTime(timestamp),
                                fontSize = 10.sp,
                                color = if (isSentByMe) SentMessageText else TextMuted
                            )
                            if (isSentByMe) {
                                Spacer(modifier = Modifier.width(4.dp))
                                MessageTicks(
                                    delivered = delivered,
                                    read = read,
                                    color = if (read) Accent else TextMuted
                                )
                            }
                        }
                    }
                }
            }

            if (isMediaNoCaption) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 6.dp, end = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(timestamp),
                        color = Color.White,
                        fontSize = 10.sp
                    )
                    if (isSentByMe) {
                        Spacer(modifier = Modifier.width(3.dp))
                        val statusColor = if (read) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.85f)
                        MessageTicks(
                            delivered = delivered,
                            read = read,
                            color = statusColor
                        )
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

@Composable
fun TelegramDoodleBackground(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFF0E1621),
    patternColor: Color = Color(0x0EFFFFFF)
) {
    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxSize().background(backgroundColor)) {
        val cellStep = 45.dp.toPx()
        val cols = (size.width / cellStep).toInt() + 2
        val rows = (size.height / cellStep).toInt() + 2
        val strokeWidth = 1.1.dp.toPx()
        val strokeStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)

        fun pseudoRandom(r: Int, c: Int, seed: Int): Float {
            val hash = (r * 73856093) xor (c * 19349663) xor (seed * 83492791)
            return ((hash and 0x7FFFFFFF) % 1000) / 1000f
        }

        for (r in -1..rows) {
            val rowOffset = if (r % 2 != 0) cellStep / 2f else 0f
            for (c in -1..cols) {
                if (pseudoRandom(r, c, 3) < 0.12f) continue

                val randomOffsetX = (pseudoRandom(r, c, 1) - 0.5f) * cellStep * 0.35f
                val randomOffsetY = (pseudoRandom(r, c, 2) - 0.5f) * cellStep * 0.35f

                val cx = c * cellStep + rowOffset + cellStep / 2f + randomOffsetX
                val cy = r * cellStep + cellStep / 2f + randomOffsetY

                val iconType = Math.abs((r * 7 + c + (pseudoRandom(r, c, 4) * 10).toInt())) % 16
                when (iconType) {
                    0 -> {
                        val pPath = Path().apply {
                            moveTo(cx - 5.dp.toPx(), cy + 7.dp.toPx())
                            lineTo(cx - 5.dp.toPx(), cy - 7.dp.toPx())
                            lineTo(cx + 1.dp.toPx(), cy - 7.dp.toPx())
                            quadraticTo(cx + 7.dp.toPx(), cy - 7.dp.toPx(), cx + 7.dp.toPx(), cy - 2.dp.toPx())
                            quadraticTo(cx + 7.dp.toPx(), cy + 3.dp.toPx(), cx + 1.dp.toPx(), cy + 3.dp.toPx())
                            lineTo(cx - 5.dp.toPx(), cy + 3.dp.toPx())
                        }
                        drawPath(pPath, patternColor, style = strokeStyle)
                    }
                    1 -> {
                        val starPath = Path().apply {
                            val rOuter = 6.dp.toPx()
                            val rInner = 2.5.dp.toPx()
                            for (i in 0 until 5) {
                                val aOut = Math.toRadians((i * 72 - 18).toDouble())
                                val aIn = Math.toRadians((i * 72 + 18).toDouble())
                                val xo = cx + rOuter * Math.cos(aOut).toFloat()
                                val yo = cy + rOuter * Math.sin(aOut).toFloat()
                                val xi = cx + rInner * Math.cos(aIn).toFloat()
                                val yi = cy + rInner * Math.sin(aIn).toFloat()
                                if (i == 0) moveTo(xo, yo) else lineTo(xo, yo)
                                lineTo(xi, yi)
                            }
                            close()
                        }
                        drawPath(starPath, patternColor, style = strokeStyle)
                    }
                    2 -> {
                        val heartPath = Path().apply {
                            moveTo(cx, cy - 3.dp.toPx())
                            cubicTo(cx - 8.dp.toPx(), cy - 10.dp.toPx(), cx - 8.dp.toPx(), cy + 3.dp.toPx(), cx, cy + 8.dp.toPx())
                            cubicTo(cx + 8.dp.toPx(), cy + 3.dp.toPx(), cx + 8.dp.toPx(), cy - 10.dp.toPx(), cx, cy - 3.dp.toPx())
                        }
                        drawPath(heartPath, patternColor, style = strokeStyle)
                    }
                    3 -> {
                        drawRoundRect(
                            color = patternColor,
                            topLeft = androidx.compose.ui.geometry.Offset(cx - 7.dp.toPx(), cy - 5.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(14.dp.toPx(), 9.dp.toPx()),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            style = strokeStyle
                        )
                        val tail = Path().apply {
                            moveTo(cx - 3.dp.toPx(), cy + 4.dp.toPx())
                            lineTo(cx - 6.dp.toPx(), cy + 7.dp.toPx())
                            lineTo(cx, cy + 4.dp.toPx())
                        }
                        drawPath(tail, patternColor, style = strokeStyle)
                    }
                    4 -> {
                        drawRoundRect(
                            color = patternColor,
                            topLeft = androidx.compose.ui.geometry.Offset(cx - 5.dp.toPx(), cy - 4.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(10.dp.toPx(), 11.dp.toPx()),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            style = strokeStyle
                        )
                        drawArc(
                            color = patternColor,
                            startAngle = -90f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(cx + 5.dp.toPx(), cy - 2.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(4.dp.toPx(), 6.dp.toPx()),
                            style = strokeStyle
                        )
                    }
                    5 -> {
                        val bolt = Path().apply {
                            moveTo(cx + 2.dp.toPx(), cy - 7.dp.toPx())
                            lineTo(cx - 4.dp.toPx(), cy)
                            lineTo(cx, cy)
                            lineTo(cx - 3.dp.toPx(), cy + 7.dp.toPx())
                            lineTo(cx + 4.dp.toPx(), cy - 1.dp.toPx())
                            lineTo(cx, cy - 1.dp.toPx())
                            close()
                        }
                        drawPath(bolt, patternColor, style = strokeStyle)
                    }
                    6 -> {
                        drawCircle(
                            color = patternColor,
                            radius = 4.5.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(cx, cy),
                            style = strokeStyle
                        )
                        drawOval(
                            color = patternColor,
                            topLeft = androidx.compose.ui.geometry.Offset(cx - 8.dp.toPx(), cy - 2.5.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(16.dp.toPx(), 5.dp.toPx()),
                            style = strokeStyle
                        )
                    }
                    7 -> {
                        drawCircle(
                            color = patternColor,
                            radius = 2.5.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(cx - 3.dp.toPx(), cy + 4.dp.toPx()),
                            style = strokeStyle
                        )
                        drawLine(
                            color = patternColor,
                            start = androidx.compose.ui.geometry.Offset(cx - 0.5.dp.toPx(), cy + 4.dp.toPx()),
                            end = androidx.compose.ui.geometry.Offset(cx - 0.5.dp.toPx(), cy - 5.dp.toPx()),
                            strokeWidth = strokeWidth
                        )
                        drawLine(
                            color = patternColor,
                            start = androidx.compose.ui.geometry.Offset(cx - 0.5.dp.toPx(), cy - 5.dp.toPx()),
                            end = androidx.compose.ui.geometry.Offset(cx + 4.dp.toPx(), cy - 3.dp.toPx()),
                            strokeWidth = strokeWidth
                        )
                    }
                    8 -> {
                        drawRoundRect(
                            color = patternColor,
                            topLeft = androidx.compose.ui.geometry.Offset(cx - 5.dp.toPx(), cy - 1.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(10.dp.toPx(), 8.dp.toPx()),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            style = strokeStyle
                        )
                        drawArc(
                            color = patternColor,
                            startAngle = 180f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(cx - 3.5.dp.toPx(), cy - 6.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(7.dp.toPx(), 8.dp.toPx()),
                            style = strokeStyle
                        )
                    }
                    9 -> {
                        val diamond = Path().apply {
                            moveTo(cx, cy - 6.dp.toPx())
                            lineTo(cx + 6.dp.toPx(), cy)
                            lineTo(cx, cy + 6.dp.toPx())
                            lineTo(cx - 6.dp.toPx(), cy)
                            close()
                        }
                        drawPath(diamond, patternColor, style = strokeStyle)
                    }
                    10 -> {
                        val cat = Path().apply {
                            moveTo(cx - 5.dp.toPx(), cy - 2.dp.toPx())
                            lineTo(cx - 6.dp.toPx(), cy - 6.dp.toPx())
                            lineTo(cx - 2.dp.toPx(), cy - 4.dp.toPx())
                            lineTo(cx + 2.dp.toPx(), cy - 4.dp.toPx())
                            lineTo(cx + 6.dp.toPx(), cy - 6.dp.toPx())
                            lineTo(cx + 5.dp.toPx(), cy - 2.dp.toPx())
                        }
                        drawPath(cat, patternColor, style = strokeStyle)
                        drawCircle(color = patternColor, radius = 5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx, cy + 1.dp.toPx()), style = strokeStyle)
                    }
                    11 -> {
                        val sparkle = Path().apply {
                            moveTo(cx, cy - 6.dp.toPx())
                            quadraticTo(cx, cy, cx + 6.dp.toPx(), cy)
                            quadraticTo(cx, cy, cx, cy + 6.dp.toPx())
                            quadraticTo(cx, cy, cx - 6.dp.toPx(), cy)
                            quadraticTo(cx, cy, cx, cy - 6.dp.toPx())
                        }
                        drawPath(sparkle, patternColor, style = strokeStyle)
                    }
                    12 -> {
                        val appleBody = Path().apply {
                            moveTo(cx, cy - 3.dp.toPx())
                            cubicTo(cx - 3.dp.toPx(), cy - 6.dp.toPx(), cx - 7.dp.toPx(), cy - 2.dp.toPx(), cx - 6.dp.toPx(), cy + 2.dp.toPx())
                            cubicTo(cx - 5.dp.toPx(), cy + 6.dp.toPx(), cx - 2.dp.toPx(), cy + 7.dp.toPx(), cx, cy + 5.5.dp.toPx())
                            cubicTo(cx + 2.dp.toPx(), cy + 7.dp.toPx(), cx + 5.dp.toPx(), cy + 6.dp.toPx(), cx + 6.dp.toPx(), cy + 2.dp.toPx())
                            cubicTo(cx + 7.dp.toPx(), cy - 2.dp.toPx(), cx + 3.dp.toPx(), cy - 6.dp.toPx(), cx, cy - 3.dp.toPx())
                        }
                        drawPath(appleBody, patternColor, style = strokeStyle)
                        val leaf = Path().apply {
                            moveTo(cx, cy - 3.dp.toPx())
                            quadraticTo(cx + 2.dp.toPx(), cy - 6.dp.toPx(), cx + 3.dp.toPx(), cy - 7.dp.toPx())
                        }
                        drawPath(leaf, patternColor, style = strokeStyle)
                    }
                    13 -> {
                        val cloud = Path().apply {
                            moveTo(cx - 5.dp.toPx(), cy + 2.dp.toPx())
                            cubicTo(cx - 7.dp.toPx(), cy - 1.dp.toPx(), cx - 3.dp.toPx(), cy - 5.dp.toPx(), cx - 1.dp.toPx(), cy - 3.dp.toPx())
                            cubicTo(cx + 1.dp.toPx(), cy - 5.dp.toPx(), cx + 5.dp.toPx(), cy - 3.dp.toPx(), cx + 4.dp.toPx(), cy + 1.dp.toPx())
                            quadraticTo(cx + 6.dp.toPx(), cy + 3.dp.toPx(), cx + 3.dp.toPx(), cy + 3.dp.toPx())
                            lineTo(cx - 3.dp.toPx(), cy + 3.dp.toPx())
                            quadraticTo(cx - 6.dp.toPx(), cy + 3.dp.toPx(), cx - 5.dp.toPx(), cy + 2.dp.toPx())
                        }
                        drawPath(cloud, patternColor, style = strokeStyle)
                    }
                    14 -> {
                        drawCircle(color = patternColor, radius = 2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx - 2.5.dp.toPx(), cy + 2.5.dp.toPx()), style = strokeStyle)
                        drawCircle(color = patternColor, radius = 2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx + 2.5.dp.toPx(), cy + 1.dp.toPx()), style = strokeStyle)
                        drawLine(color = patternColor, start = androidx.compose.ui.geometry.Offset(cx - 0.5.dp.toPx(), cy + 2.5.dp.toPx()), end = androidx.compose.ui.geometry.Offset(cx - 0.5.dp.toPx(), cy - 4.dp.toPx()), strokeWidth = strokeWidth)
                        drawLine(color = patternColor, start = androidx.compose.ui.geometry.Offset(cx + 4.5.dp.toPx(), cy + 1.dp.toPx()), end = androidx.compose.ui.geometry.Offset(cx + 4.5.dp.toPx(), cy - 5.5.dp.toPx()), strokeWidth = strokeWidth)
                        drawLine(color = patternColor, start = androidx.compose.ui.geometry.Offset(cx - 0.5.dp.toPx(), cy - 4.dp.toPx()), end = androidx.compose.ui.geometry.Offset(cx + 4.5.dp.toPx(), cy - 5.5.dp.toPx()), strokeWidth = strokeWidth)
                    }
                    15 -> {
                        drawCircle(
                            color = patternColor,
                            radius = 3.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(cx, cy - 3.5.dp.toPx()),
                            style = strokeStyle
                        )
                        val shoulders = Path().apply {
                            moveTo(cx - 5.5.dp.toPx(), cy + 5.dp.toPx())
                            quadraticTo(cx - 5.5.dp.toPx(), cy + 0.5.dp.toPx(), cx - 3.dp.toPx(), cy + 0.5.dp.toPx())
                            lineTo(cx + 3.dp.toPx(), cy + 0.5.dp.toPx())
                            quadraticTo(cx + 5.5.dp.toPx(), cy + 0.5.dp.toPx(), cx + 5.5.dp.toPx(), cy + 5.dp.toPx())
                        }
                        drawPath(shoulders, patternColor, style = strokeStyle)
                    }
                }
            }
        }
    }
}

data class ForwardTargetItem(
    val id: Long,
    val name: String,
    val isGroup: Boolean,
    val avatarKey: Any? = null
)

@Composable
fun ForwardTargetDialog(
    targets: List<ForwardTargetItem>,
    onSelectTarget: (ForwardTargetItem) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(searchQuery, targets) {
        if (searchQuery.isBlank()) targets
        else targets.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Panel)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Переслать сообщение",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск чата...", color = TextMuted, fontSize = 14.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = InputBg,
                    unfocusedContainerColor = InputBg,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Чаты не найдены", color = TextMuted, fontSize = 14.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { "${if (it.isGroup) "g" else "u"}_${it.id}" }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTarget(item) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.isGroup) {
                                GroupAvatar(groupId = item.id, name = item.name, size = 40.dp, avatarKey = item.avatarKey)
                            } else {
                                UserAvatar(userId = item.id, name = item.name, size = 40.dp, avatarKey = item.avatarKey)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = item.name,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
