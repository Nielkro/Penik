package niel.kro.penik.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val base = if (isGroup) {
        "https://web.dev.penik.ru/api/v1/groups/$id/avatar"
    } else {
        "https://web.dev.penik.ru/api/v1/avatar/$id"
    }
    return if (avatarKey != null) "$base?t=$avatarKey" else base
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
                    text = lastMessage,
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
                    text = lastMessage,
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

    val parsedText = text

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        val startPadding = if (isSentByMe) 12.dp else 18.dp
        val endPadding = if (isSentByMe) 18.dp else 12.dp
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
                            if (dragAmount > 0 || offsetX > 0) {
                                val newOffset = (offsetX + dragAmount * 0.5f).coerceIn(0f, 160f)
                                offsetX = newOffset
                                if (newOffset >= 100f && !triggered) {
                                    triggered = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        }
                    )
                }
                .widthIn(max = 280.dp)
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
                .padding(start = startPadding, top = 8.dp, end = endPadding, bottom = 8.dp)
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
                    Row(
                        modifier = Modifier
                            .widthIn(min = 70.dp)
                            .padding(bottom = 6.dp)
                            .background(Color(0x0DFFFFFF), shape = RoundedCornerShape(4.dp))
                            .height(IntrinsicSize.Max)
                            .clickable(enabled = onReplyClick != null && replyToMsgId != null) {
                                replyToMsgId?.let { onReplyClick?.invoke(it) }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(Accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(
                            modifier = Modifier
                                .padding(vertical = 5.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = replySender!!,
                                color = if (isSentByMe) Color(0xFFB8D4FF) else Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = replyText!!,
                                color = TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

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

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSentByMe && !isFailed && !isSelfChat) {
                        val statusText = if (read || delivered) "✓✓" else "✓"
                        val statusColor = if (read) Accent else TextMuted
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    val timeLabel = if (showFullTime) formatFullTime(timestamp) else formatTime(timestamp)
                    Text(
                        text = timeLabel,
                        color = TextMuted,
                        fontSize = if (showFullTime) 9.sp else 10.sp,
                        modifier = Modifier
                            .clickable { showFullTime = !showFullTime }
                            .padding(horizontal = 2.dp),
                        maxLines = 1
                    )
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
