package niel.kro.penik.ui.screen.chatroom

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
import kotlinx.serialization.json.jsonPrimitive
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
                listState.animateScrollToItem(messages.lastIndex)
            }
        } else if (previousSize == 0 && messages.isNotEmpty()) {
            // First load — jump to the bottom immediately.
            listState.scrollToItem(messages.lastIndex)
        }
        previousSize = messages.size
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
                        text = "Ваши сообщения шифруются на вашем устройстве и расшифровываются только на устройстве получателя. Никто — даже сервер Penik — не может прочитать ваши сообщения.",
                        fontSize = 13.sp,
                        color = TextMuted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = "Как это работает:",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "• Каждое устройство генерирует пару ключей (Identity Key)\n" +
                                "• При отправке сообщения создаётся одноразовый общий секрет\n" +
                                "• Сообщение шифруется алгоритмом ChaCha20-Poly1305\n" +
                                "• Расшифровать может только получатель",
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = "Код безопасности (Safety Number) — это отпечаток ключей шифрования обоих собеседников. Сравнив его на устройствах, вы убедитесь, что между вами нет третьей стороны (защита от MitM-атак).",
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    val online by viewModel.online.collectAsState()
                    val lastSeen by viewModel.lastSeen.collectAsState()
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
                            onDelete = { viewModel.deleteMessage(message.localId) }
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
                                listState.animateScrollToItem(messages.lastIndex)
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = reply.sender,
                                color = Accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = reply.text,
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
                        onValueChange = { inputText = it },
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
}

data class ReplyInfo(
    val msgId: String,
    val text: String,
    val sender: String
)
