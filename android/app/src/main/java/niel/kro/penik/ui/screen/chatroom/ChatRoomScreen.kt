package niel.kro.penik.ui.screen.chatroom

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import android.util.Base64
import niel.kro.penik.ui.components.parseReplyContent
import niel.kro.penik.ui.components.ReplyParsedInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import niel.kro.penik.ui.components.MessageBubble
import niel.kro.penik.ui.theme.Accent
import niel.kro.penik.ui.theme.Background
import niel.kro.penik.ui.theme.Border
import niel.kro.penik.ui.theme.InputBg
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import niel.kro.penik.ui.theme.Panel
import niel.kro.penik.ui.theme.PanelSecondary
import niel.kro.penik.ui.theme.Success
import niel.kro.penik.ui.theme.TextMuted
import niel.kro.penik.ui.theme.TextPrimary
import niel.kro.penik.ui.viewmodel.ChatRoomViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    chatUserId: Long,
    chatName: String,
    onBack: () -> Unit,
    viewModel: ChatRoomViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val safetyNumber by viewModel.safetyNumber.collectAsState()
    val showDialog by viewModel.showSafetyDialog.collectAsState()
    val showE2eeDialog by viewModel.showE2eeDialog.collectAsState()
    val isSelfChat = viewModel.isSelfChat
    var fullscreenAvatarUrl by remember { mutableStateOf<String?>(null) }

    // Show the button when the last message is not fully visible.
    val showScrollDown by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf false
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex < totalItems - 1
        }
    }

    var previousSize by remember { mutableStateOf(0) }
    var activeReply by remember { mutableStateOf<ReplyInfo?>(null) }
    LaunchedEffect(messages.size) {
        if (messages.size > previousSize && previousSize > 0) {
            // Auto-scroll when user is at the bottom or within 5 messages from the bottom.
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val isNearBottom = totalItems > 0 && (totalItems - 1 - lastVisibleIndex <= 5)
            if (isNearBottom && messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.lastIndex, scrollOffset = 10000)
            }
        } else if (previousSize == 0 && messages.isNotEmpty()) {
            // First load — jump to the bottom immediately.
            listState.scrollToItem(messages.lastIndex, scrollOffset = 10000)
        }
        previousSize = messages.size
    }

    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(imeBottomPadding) {
        if (messages.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val isNearBottom = totalItems > 0 && (totalItems - 1 - lastVisibleIndex <= 3)
            if (isNearBottom) {
                listState.scrollToItem(messages.lastIndex, scrollOffset = 10000)
            }
        }
    }

    if (showE2eeDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissE2eeDialog() },
            containerColor = Panel,
            titleContentColor = TextPrimary,
            title = {
                Text(
                    text = "Что такое E2EE?",
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    Text(
                        text = "E2EE (End-to-End Encryption) — сквозное шифрование.",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = "Ваше устройство и устройство собеседника обмениваются публичными ключами и с помощью специального математического алгоритма (Диффи-Хеллмана) независимо друг от друга вычисляют один и тот же секретный ключ. При этом этот ключ никогда не передаётся по сети. Им шифруется каждое сообщение, и расшифровать его можете только вы и ваш собеседник.",
                        fontSize = 13.sp,
                        color = TextMuted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = "Код безопасности (Safety Number) — это общий цифровой отпечаток ваших ключей. Сверив его лично или в другом канале, вы убедитесь, что общаетесь напрямую без подмены ключей третьими лицами.",
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissE2eeDialog() }) {
                    Text("Понятно", color = Accent)
                }
            }
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSafetyDialog() },
            containerColor = Panel,
            titleContentColor = TextPrimary,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Код безопасности ",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "E2EE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Accent,
                        modifier = Modifier.clickable { viewModel.onE2eeClick() }
                    )
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Сравните эти числа с числами на устройстве вашего собеседника. Если они совпадают, ваше сквозное шифрование на 100% защищено от перехвата.",
                        fontSize = 13.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Text(
                        text = safetyNumber ?: "Загрузка...",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Success,
                        textAlign = TextAlign.Center,
                        letterSpacing = 2.sp,
                        lineHeight = 28.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PanelSecondary, RoundedCornerShape(8.dp))
                            .padding(16.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSafetyDialog() }) {
                    Text("Закрыть", color = Accent)
                }
            }
        )
    }

    var messageToDeleteLocalId by remember { mutableStateOf<String?>(null) }
    var deleteForEveryoneChecked by remember { mutableStateOf(false) }

    messageToDeleteLocalId?.let { localId ->
        AlertDialog(
            onDismissRequest = { messageToDeleteLocalId = null },
            containerColor = Panel,
            titleContentColor = TextPrimary,
            title = {
                Text(
                    text = "Удалить сообщение?",
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Вы действительно хотите удалить это сообщение?",
                        fontSize = 13.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteForEveryoneChecked = !deleteForEveryoneChecked }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = deleteForEveryoneChecked,
                            onCheckedChange = { deleteForEveryoneChecked = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFFEF5350),
                                uncheckedColor = TextMuted
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Удалить также для собеседника",
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val forEveryone = deleteForEveryoneChecked
                        messageToDeleteLocalId = null
                        viewModel.deleteMessage(localId, forEveryone)
                    }
                ) {
                    Text("Удалить", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { messageToDeleteLocalId = null }) {
                    Text("Отмена", color = TextMuted)
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    val online by viewModel.online.collectAsState()
                    val lastSeen by viewModel.lastSeen.collectAsState()
                    val userAvatarKeys by niel.kro.penik.data.repository.AvatarCacheBus.userAvatarKeys.collectAsState()
                    val avatarKey = userAvatarKeys[chatUserId]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        niel.kro.penik.ui.components.UserAvatar(
                            userId = chatUserId,
                            name = chatName,
                            size = 36.dp,
                            avatarKey = avatarKey,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clickable {
                                    fullscreenAvatarUrl = niel.kro.penik.ui.components.avatarUrlFor(
                                        isGroup = false,
                                        id = chatUserId,
                                        avatarKey = avatarKey
                                    )
                                }
                        )
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = chatName,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (!isSelfChat) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Код безопасности E2EE",
                                        tint = Success,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Success.copy(alpha = 0.15f))
                                            .clickable { viewModel.onSafetyClick() }
                                            .padding(4.dp)
                                            .size(16.dp)
                                    )
                                }
                            }
                            if (!isSelfChat) {
                                val isPeerTyping by viewModel.isPeerTyping.collectAsState()
                                if (isPeerTyping) {
                                    Text(
                                        text = "печатает...",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Accent
                                    )
                                } else {
                                    val presence = niel.kro.penik.ui.util.formatPresence(online, lastSeen)
                                    if (presence.isNotEmpty()) {
                                        Text(
                                            text = presence,
                                            fontSize = 12.sp,
                                            color = if (online) Accent else TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Panel,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                niel.kro.penik.ui.components.TelegramDoodleBackground()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    items(messages, key = { it.localId }) { message ->
                        val parentMsg = message.replyToMsgId?.let { parentId ->
                            messages.find { it.localId == parentId || it.serverId?.toString() == parentId }
                        }
                        val replyText = parentMsg?.text
                        val replySender = parentMsg?.let {
                            if (it.sentByMe) "Вы" else chatName
                        }

                        MessageBubble(
                            text = message.text,
                            timestamp = message.timestamp,
                            isSentByMe = message.sentByMe,
                            delivered = message.delivered,
                            deliveredAt = message.deliveredAt,
                            read = message.read,
                            isSelfChat = isSelfChat,
                            replyToMsgId = message.replyToMsgId,
                            replySender = replySender,
                            replyText = replyText,
                            onReply = {
                                activeReply = ReplyInfo(
                                    msgId = message.localId,
                                    text = message.text,
                                    sender = if (message.sentByMe) "Вы" else chatName
                                )
                            },
                            onReplyClick = { parentId ->
                                val index = messages.indexOfFirst { it.localId == parentId || it.serverId?.toString() == parentId }
                                if (index >= 0) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(index)
                                    }
                                }
                            },
                            onDelete = {
                                deleteForEveryoneChecked = false
                                messageToDeleteLocalId = message.localId
                            }
                        )
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showScrollDown,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 8.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(messages.lastIndex, scrollOffset = 10000)
                            }
                        },
                        containerColor = Panel,
                        contentColor = TextPrimary,
                        elevation = FloatingActionButtonDefaults.elevation(4.dp),
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "К последним",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .imePadding()
            ) {
                activeReply?.let { reply ->
                    val replyInfo = remember(reply.text) { parseReplyContent(reply.text) }
                    val replyThumbBitmap: ImageBitmap? = remember(replyInfo?.thumbBase64) {
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
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(36.dp)
                                .background(Accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (replyThumbBitmap != null) {
                            Image(
                                bitmap = replyThumbBitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = reply.sender,
                                color = Accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = replyInfo?.displayText ?: reply.text,
                                color = TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { activeReply = null }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = TextMuted
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = {
                            inputText = it
                            viewModel.sendTyping(it.isNotBlank())
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp)),
                        placeholder = { Text("Сообщение...", color = TextMuted) },
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = InputBg,
                            unfocusedContainerColor = InputBg,
                            focusedBorderColor = Border,
                            unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendTyping(false)
                                val currentReply = activeReply
                                activeReply = null
                                viewModel.sendMessage(inputText, currentReply?.msgId)
                                inputText = ""
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить",
                            tint = Accent
                        )
                    }
                }
            }
        }
    }

    niel.kro.penik.ui.components.FullscreenImageViewer(
        url = fullscreenAvatarUrl,
        onDismiss = { fullscreenAvatarUrl = null }
    )
}

data class ReplyInfo(
    val msgId: String,
    val text: String,
    val sender: String
)
