package niel.kro.penik.ui.screen.chatroom

import niel.kro.penik.ui.theme.LocalAppColors

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
import androidx.compose.runtime.DisposableEffect
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
import niel.kro.penik.ui.notification.AppNotificationManager
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import niel.kro.penik.ui.viewmodel.ChatRoomViewModel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    chatUserId: Long,
    chatName: String,
    onBack: () -> Unit,
    viewModel: ChatRoomViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val safetyNumber by viewModel.safetyNumber.collectAsState()
    val showDialog by viewModel.showSafetyDialog.collectAsState()
    val showE2eeDialog by viewModel.showE2eeDialog.collectAsState()
    val isSelfChat = viewModel.isSelfChat
    var fullscreenAvatarUrl by remember { mutableStateOf<String?>(null) }

    DisposableEffect(chatUserId) {
        AppNotificationManager.setActiveChat("direct_$chatUserId")
        onDispose {
            AppNotificationManager.clearActiveChat("direct_$chatUserId")
        }
    }

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
    var isInitialScrollDone by remember(chatUserId) { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (!isInitialScrollDone || previousSize == 0) {
                listState.scrollToItem(messages.lastIndex, scrollOffset = 10000)
                kotlinx.coroutines.delay(60)
                listState.scrollToItem(messages.lastIndex, scrollOffset = 10000)
                isInitialScrollDone = true
            } else if (messages.size > previousSize) {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val isNearBottom = totalItems > 0 && (totalItems - 1 - lastVisibleIndex <= 5)
                if (isNearBottom) {
                    listState.animateScrollToItem(messages.lastIndex, scrollOffset = 10000)
                }
            }
            previousSize = messages.size
        }
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
            containerColor = LocalAppColors.current.panel,
            titleContentColor = LocalAppColors.current.textPrimary,
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
                        color = LocalAppColors.current.textPrimary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = "Ваше устройство и устройство собеседника обмениваются публичными ключами и с помощью специального математического алгоритма (Диффи-Хеллмана) независимо друг от друга вычисляют один и тот же секретный ключ. При этом этот ключ никогда не передаётся по сети. Им шифруется каждое сообщение, и расшифровать его можете только вы и ваш собеседник.",
                        fontSize = 13.sp,
                        color = LocalAppColors.current.textMuted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = "Код безопасности (Safety Number) — это общий цифровой отпечаток ваших ключей. Сверив его лично или в другом канале, вы убедитесь, что общаетесь напрямую без подмены ключей третьими лицами.",
                        fontSize = 12.sp,
                        color = LocalAppColors.current.textMuted,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissE2eeDialog() }) {
                    Text("Понятно", color = LocalAppColors.current.accent)
                }
            }
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSafetyDialog() },
            containerColor = LocalAppColors.current.panel,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Код безопасности ",
                        fontWeight = FontWeight.SemiBold,
                        color = LocalAppColors.current.textPrimary
                    )
                    Text(
                        text = "E2EE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalAppColors.current.accent,
                        modifier = Modifier.clickable { viewModel.onE2eeClick() }
                    )
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Сравните эти числа с числами на устройстве вашего собеседника. Если они совпадают, ваше сквозное шифрование на 100% защищено от перехвата.",
                        fontSize = 13.sp,
                        color = LocalAppColors.current.textMuted,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Text(
                        text = safetyNumber ?: "Загрузка...",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = LocalAppColors.current.success,
                        textAlign = TextAlign.Center,
                        letterSpacing = 2.sp,
                        lineHeight = 28.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LocalAppColors.current.panelSecondary, RoundedCornerShape(8.dp))
                            .padding(16.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSafetyDialog() }) {
                    Text("Закрыть", color = LocalAppColors.current.accent)
                }
            }
        )
    }

    var messageToForwardText by remember { mutableStateOf<String?>(null) }
    var messageToForwardSender by remember { mutableStateOf<String?>(null) }

    val contactsList by viewModel.chatRepository.getAllChats().collectAsState(initial = emptyList())
    val groupsList by viewModel.groupRepository.observeGroups().collectAsState(initial = emptyList())

    val forwardTargets = remember(contactsList, groupsList) {
        val list = mutableListOf<niel.kro.penik.ui.components.ForwardTargetItem>()
        for (c in contactsList) {
            list.add(niel.kro.penik.ui.components.ForwardTargetItem(
                id = c.userId,
                name = c.name.ifBlank { c.nickname },
                isGroup = false
            ))
        }
        for (g in groupsList) {
            list.add(niel.kro.penik.ui.components.ForwardTargetItem(
                id = g.id,
                name = g.name,
                isGroup = true
            ))
        }
        list
    }

    if (messageToForwardText != null) {
        niel.kro.penik.ui.components.ForwardTargetDialog(
            targets = forwardTargets,
            onSelectTarget = { target ->
                val rawText = messageToForwardText!!
                val senderName = messageToForwardSender!!
                messageToForwardText = null
                messageToForwardSender = null

                viewModel.forwardMessage(rawText, senderName, target) {
                    android.widget.Toast.makeText(context, "Сообщение переслано", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = {
                messageToForwardText = null
                messageToForwardSender = null
            }
        )
    }

    var messageToDeleteLocalId by remember { mutableStateOf<String?>(null) }
    var deleteForEveryoneChecked by remember { mutableStateOf(false) }

    messageToDeleteLocalId?.let { localId ->
        AlertDialog(
            onDismissRequest = { messageToDeleteLocalId = null },
            containerColor = LocalAppColors.current.panel,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = {
                Text(
                    text = "Удалить сообщение?",
                    fontWeight = FontWeight.SemiBold,
                    color = LocalAppColors.current.textPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Вы действительно хотите удалить это сообщение?",
                        fontSize = 13.sp,
                        color = LocalAppColors.current.textMuted,
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
                                uncheckedColor = LocalAppColors.current.textMuted
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Удалить также для собеседника",
                            fontSize = 13.sp,
                            color = LocalAppColors.current.textPrimary
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
                    Text("Отмена", color = LocalAppColors.current.textMuted)
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = LocalAppColors.current.background,
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
                                        tint = LocalAppColors.current.success,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(LocalAppColors.current.success.copy(alpha = 0.15f))
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
                                        color = LocalAppColors.current.accent
                                    )
                                } else {
                                    val presence = niel.kro.penik.ui.util.formatPresence(online, lastSeen)
                                    if (presence.isNotEmpty()) {
                                        Text(
                                            text = presence,
                                            fontSize = 12.sp,
                                            color = if (online) LocalAppColors.current.accent else LocalAppColors.current.textMuted
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
                            tint = LocalAppColors.current.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LocalAppColors.current.panel,
                    titleContentColor = LocalAppColors.current.textPrimary
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
                                    msgId = message.serverId?.toString() ?: message.localId,
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
                            },
                            onForward = {
                                messageToForwardText = message.text
                                messageToForwardSender = if (message.sentByMe) "Вы" else chatName
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
                        containerColor = LocalAppColors.current.panel,
                        contentColor = LocalAppColors.current.textPrimary,
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
                    .background(LocalAppColors.current.panel)
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
                                .background(LocalAppColors.current.accent)
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
                                color = LocalAppColors.current.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = replyInfo?.displayText ?: reply.text,
                                color = LocalAppColors.current.textMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { activeReply = null }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = LocalAppColors.current.textMuted
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
                    // Attachment picker button
                    val attachLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null) {
                            viewModel.sendMediaFile(context, uri)
                        }
                    }
                    IconButton(onClick = { attachLauncher.launch("*/*") }) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Прикрепить файл",
                            tint = LocalAppColors.current.textMuted
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = {
                            inputText = it
                            viewModel.sendTyping(it.isNotBlank())
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp)),
                        placeholder = { Text("Сообщение...", color = LocalAppColors.current.textMuted) },
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = LocalAppColors.current.inputBg,
                            unfocusedContainerColor = LocalAppColors.current.inputBg,
                            focusedBorderColor = LocalAppColors.current.border,
                            unfocusedBorderColor = LocalAppColors.current.border,
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary
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
                            tint = LocalAppColors.current.accent
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
