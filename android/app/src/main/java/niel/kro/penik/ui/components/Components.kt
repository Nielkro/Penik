package niel.kro.penik.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        "https://penik.dev.slavchat.ru/api/v1/groups/$id/avatar"
    } else {
        "https://penik.dev.slavchat.ru/api/v1/avatar/$id"
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
            if (nickname.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "@$nickname",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
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
    onDelete: (() -> Unit)? = null
) {
    val isFailed = text.startsWith("[Ошибка расшифрования") || text.startsWith("[Сообщение не расшифровано")
    var isExpanded by remember { mutableStateOf(false) }

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

    val alignment = if (isSentByMe) Alignment.End else Alignment.Start

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
                .widthIn(max = 280.dp)
                .clip(BubbleShape(isSentByMe))
                .background(bgColor)
                .padding(start = startPadding, top = 8.dp, end = endPadding, bottom = 8.dp)
        ) {
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
                            text = text,
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
                    Text(
                        text = text,
                        color = textColor,
                        fontSize = 15.sp
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSentByMe && !isFailed) {
                        val statusText = if (read || delivered) "✓✓" else "✓"
                        val statusColor = if (read) Accent else TextMuted
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = formatTime(timestamp),
                        color = TextMuted,
                        fontSize = 10.sp
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
