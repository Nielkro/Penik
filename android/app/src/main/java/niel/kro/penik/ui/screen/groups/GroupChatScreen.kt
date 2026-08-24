package niel.kro.penik.ui.screen.groups

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import niel.kro.penik.ui.components.StickerPickerBottomSheet
import niel.kro.penik.ui.components.StickerPackDetailDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import niel.kro.penik.ui.screen.chatroom.ReplyInfo
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import niel.kro.penik.ui.components.FullscreenImageViewer
import niel.kro.penik.ui.components.GroupAvatar
import niel.kro.penik.ui.components.MessageBubble
import niel.kro.penik.ui.components.UserAvatar
import niel.kro.penik.ui.components.avatarUrlFor
import niel.kro.penik.ui.notification.AppNotificationManager
import niel.kro.penik.ui.viewmodel.GroupChatViewModel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AttachFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupId: Long,
    groupName: String,
    onBack: () -> Unit,
    onGroupSettingsClick: (Long) -> Unit,
    viewModel: GroupChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val members by viewModel.members.collectAsState()
    val groupEntity by viewModel.groupFlow.collectAsState()

    DisposableEffect(groupId) {
        AppNotificationManager.setActiveChat("group_$groupId")
        onDispose {
            AppNotificationManager.clearActiveChat("group_$groupId")
        }
    }
    val searchResults by viewModel.searchResults.collectAsState()
    val error by viewModel.error.collectAsState()
    val groupAvatarKeys by niel.kro.penik.data.repository.AvatarCacheBus.groupAvatarKeys.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showMembersDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var activeReply by remember { mutableStateOf<ReplyInfo?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var memberToRemove by remember { mutableStateOf<niel.kro.penik.data.local.entity.GroupMemberEntity?>(null) }
    var selectedMemberForActions by remember { mutableStateOf<niel.kro.penik.data.local.entity.GroupMemberEntity?>(null) }
    var fullscreenAvatarUrl by remember { mutableStateOf<String?>(null) }
    var messageToForwardText by remember { mutableStateOf<String?>(null) }
    var messageToForwardSender by remember { mutableStateOf<String?>(null) }
    var showStickerPicker by remember { mutableStateOf(false) }
    var selectedStickerPackId by remember { mutableStateOf<String?>(null) }

    val contactsList by viewModel.contacts.collectAsState(initial = emptyList())
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

    // Show the button only when the last message is not visible.
    val showScrollDown by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf false
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex < totalItems - 1
        }
    }

    val myRole = members.find { it.userId == viewModel.myUserId }?.role ?: "member"
    val canManage = myRole in listOf("owner", "admin")

    var previousSize by remember { mutableStateOf(0) }
    var isInitialScrollDone by remember(groupId) { mutableStateOf(false) }

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

    LaunchedEffect(error) {
        if (error != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }

    LaunchedEffect(searchQuery) {
        viewModel.searchUsers(searchQuery)
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onGroupSettingsClick(groupId) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.clickable {
                                fullscreenAvatarUrl = avatarUrlFor(true, groupId, groupAvatarKeys[groupId])
                            }
                        ) {
                            GroupAvatar(
                                groupId = groupId,
                                name = groupName,
                                size = 36.dp,
                                modifier = Modifier.padding(end = 10.dp),
                                avatarKey = groupAvatarKeys[groupId]
                            )
                        }
                        Column {
                            Text(groupName, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text("${members.size} участников", color = LocalAppColors.current.textMuted, fontSize = 12.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = LocalAppColors.current.textPrimary)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Меню", tint = LocalAppColors.current.textPrimary)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(LocalAppColors.current.panel)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Настройки группы", color = LocalAppColors.current.textPrimary) },
                                onClick = { showMenu = false; onGroupSettingsClick(groupId) }
                            )
                            DropdownMenuItem(
                                text = { Text("Участники", color = LocalAppColors.current.textPrimary) },
                                onClick = { showMenu = false; showMembersDialog = true }
                            )
                            if (canManage) {
                                DropdownMenuItem(
                                    text = { Text("Пригласить", color = LocalAppColors.current.textPrimary) },
                                    onClick = { showMenu = false; showInviteDialog = true }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalAppColors.current.panel)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                niel.kro.penik.ui.components.TelegramDoodleBackground()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    reverseLayout = false
                ) {
                    items(messages) { msg ->
                        val isOwn = msg.sentByMe
                        val senderMember = members.find { it.userId == msg.senderUserId }
                        val displayName = senderMember?.let {
                            it.name.ifEmpty { it.nickname.ifEmpty { "#${it.userId}" } }
                        } ?: "#${msg.senderUserId}"
                        
                        val parentMsg = msg.replyToMsgId?.let { parentId ->
                            messages.find { it.messageId == parentId }
                        }
                        val replyText = parentMsg?.text
                        val replySender = parentMsg?.let {
                            val parentSenderMember = members.find { m -> m.userId == it.senderUserId }
                            parentSenderMember?.let { m ->
                                m.name.ifEmpty { m.nickname.ifEmpty { "#${m.userId}" } }
                            } ?: "#${it.senderUserId}"
                        }

                        MessageBubble(
                            text = msg.text,
                            isSentByMe = isOwn,
                            // createdAt is stored in seconds (used as AAD in group crypto);
                            // Date() expects milliseconds.
                            timestamp = msg.createdAt * 1000,
                            delivered = if (isOwn) msg.delivered else false,
                            senderName = displayName,
                            senderUserId = msg.senderUserId,
                            replyToMsgId = msg.replyToMsgId,
                            replySender = replySender,
                            replyText = replyText,
                            onReply = {
                                activeReply = ReplyInfo(
                                    msgId = msg.messageId,
                                    text = msg.text,
                                    sender = displayName
                                )
                            },
                            onReplyClick = { parentId ->
                                val index = messages.indexOfFirst { it.messageId == parentId }
                                if (index >= 0) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(index)
                                    }
                                }
                            },
                            onForward = {
                                messageToForwardText = msg.text
                                messageToForwardSender = displayName
                            },
                            onStickerClick = { packId ->
                                selectedStickerPackId = packId
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

            HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)
            if (groupEntity?.status == "pending") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalAppColors.current.panelSecondary)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Вас пригласили в эту группу", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { viewModel.acceptInvitation() },
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.accent)
                        ) {
                            Text("Принять", color = LocalAppColors.current.textPrimary)
                        }
                        OutlinedButton(
                            onClick = { viewModel.declineInvitation(onDone = onBack) }
                        ) {
                            Text("Отклонить", color = LocalAppColors.current.textMuted)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalAppColors.current.panel)
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
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
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

                    // Sticker picker button
                    IconButton(onClick = { showStickerPicker = true }) {
                        Icon(
                            imageVector = Icons.Default.SentimentSatisfiedAlt,
                            contentDescription = "Стикеры",
                            tint = LocalAppColors.current.textMuted
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Сообщение", color = LocalAppColors.current.textMuted) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = LocalAppColors.current.inputBg,
                            unfocusedContainerColor = LocalAppColors.current.inputBg,
                            focusedBorderColor = LocalAppColors.current.accent,
                            unfocusedBorderColor = LocalAppColors.current.border,
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary
                        ),
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4
                    )
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                val currentReply = activeReply
                                activeReply = null
                                viewModel.send(inputText, currentReply?.msgId)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить",
                            tint = if (inputText.isNotBlank()) LocalAppColors.current.accent else LocalAppColors.current.textMuted
                        )
                    }
                }
            }
        }
        }

        if (error != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.BottomCenter
            ) {
                Card(
                    modifier = Modifier.padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.panel)
                ) {
                    Text(error!!, color = LocalAppColors.current.textPrimary, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }

    if (showMembersDialog) {
        val sortedMembers = remember(members) {
            members.sortedWith(compareBy {
                when (it.role) {
                    "owner" -> 0
                    "admin" -> 1
                    "member" -> 2
                    else -> 3
                }
            })
        }
        AlertDialog(
            onDismissRequest = { showMembersDialog = false },
            containerColor = LocalAppColors.current.panel,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = { Text("Участники", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        itemsIndexed(sortedMembers) { index, member ->
                            val isMe = member.userId == viewModel.myUserId
                            val isPrivileged = myRole == "owner" || myRole == "admin"
                            val canManageRow = myRole == "owner" && member.role != "owner" && !isMe
                            val canRemoveRow = isPrivileged && member.role != "owner" && !isMe

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canManageRow || canRemoveRow) {
                                        selectedMemberForActions = member
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val displayName = member.name.ifEmpty { member.nickname.ifEmpty { "#${member.userId}" } }
                                UserAvatar(
                                    userId = member.userId,
                                    name = displayName,
                                    size = 40.dp,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                                Column {
                                    val displayNameWithMe = displayName + if (isMe) " (вы)" else ""
                                    val roleRu = when (member.role) {
                                        "owner" -> "владелец"
                                        "admin" -> "админ"
                                        "member" -> "участник"
                                        else -> member.role
                                    } + if (member.status == "pending") " · приглашён" else ""
                                    Text(displayNameWithMe, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Medium)
                                    Text(roleRu, color = LocalAppColors.current.textMuted, fontSize = 12.sp)
                                }
                            }
                            if (index < sortedMembers.lastIndex) {
                                HorizontalDivider(color = LocalAppColors.current.border)
                            }
                        }
                    }
                    if (canManage) {
                        HorizontalDivider(color = LocalAppColors.current.border, modifier = Modifier.padding(vertical = 4.dp))
                        TextButton(
                            onClick = {
                                showMembersDialog = false
                                showInviteDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("＋ Добавить участника", color = LocalAppColors.current.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMembersDialog = false }) {
                    Text("Закрыть", color = LocalAppColors.current.accent)
                }
            }
        )
    }

    selectedMemberForActions?.let { member ->
        val isMe = member.userId == viewModel.myUserId
        val isPrivileged = myRole == "owner" || myRole == "admin"
        val canManageRow = myRole == "owner" && member.role != "owner" && !isMe
        val canRemoveRow = isPrivileged && member.role != "owner" && !isMe

        AlertDialog(
            onDismissRequest = { selectedMemberForActions = null },
            containerColor = LocalAppColors.current.panel,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = { Text(member.name.ifEmpty { member.nickname.ifEmpty { "#${member.userId}" } }) },
            text = {
                Column {
                    if (canManageRow) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val newRole = if (member.role == "admin") "member" else "admin"
                                viewModel.changeMemberRole(member.userId, newRole)
                                selectedMemberForActions = null
                            }
                        ) {
                            Text(
                                if (member.role == "admin") "Снять роль админа" else "Сделать админом",
                                color = LocalAppColors.current.accent,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (canRemoveRow) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                memberToRemove = member
                                selectedMemberForActions = null
                            }
                        ) {
                            Text("Удалить из группы", color = LocalAppColors.current.accent, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedMemberForActions = null }) {
                    Text("Отмена", color = LocalAppColors.current.textPrimary)
                }
            }
        )
    }

    memberToRemove?.let { member ->
        val displayName = member.name.ifEmpty { member.nickname.ifEmpty { "#${member.userId}" } }
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            containerColor = LocalAppColors.current.panel,
            titleContentColor = LocalAppColors.current.textPrimary,
            textContentColor = LocalAppColors.current.textPrimary,
            title = { Text("Удалить участника", fontWeight = FontWeight.SemiBold) },
            text = { Text("Вы действительно хотите удалить участника $displayName из группы?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMember(member.userId)
                    memberToRemove = null
                }) {
                    Text("Удалить", color = LocalAppColors.current.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToRemove = null }) {
                    Text("Отмена", color = LocalAppColors.current.textPrimary)
                }
            }
        )
    }

    if (showInviteDialog) {
        val contactsList by viewModel.contacts.collectAsState()
        var shareHistory by remember { mutableStateOf(false) }
        val existingMemberIds = remember(members) { members.map { it.userId }.toSet() }
        val inviteableContacts = remember(contactsList, existingMemberIds, searchQuery) {
            contactsList.filter {
                it.userId !in existingMemberIds &&
                (searchQuery.isBlank() ||
                 it.nickname.contains(searchQuery, ignoreCase = true) ||
                 it.name.contains(searchQuery, ignoreCase = true))
            }
        }

        AlertDialog(
            onDismissRequest = { showInviteDialog = false; searchQuery = "" },
            containerColor = LocalAppColors.current.panel,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = { Text("Пригласить участника", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Поиск контактов") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = LocalAppColors.current.inputBg,
                            unfocusedContainerColor = LocalAppColors.current.inputBg,
                            focusedBorderColor = LocalAppColors.current.accent,
                            unfocusedBorderColor = LocalAppColors.current.border,
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedLabelColor = LocalAppColors.current.accent,
                            unfocusedLabelColor = LocalAppColors.current.textMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { shareHistory = !shareHistory }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = shareHistory,
                            onCheckedChange = { shareHistory = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = LocalAppColors.current.accent,
                                uncheckedColor = LocalAppColors.current.textMuted,
                                checkmarkColor = LocalAppColors.current.textPrimary
                            )
                        )
                        Text(
                            text = "Поделиться историей чата до вступления",
                            color = LocalAppColors.current.textPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    HorizontalDivider(color = LocalAppColors.current.border)
                    LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).weight(1f, fill = false)) {
                        items(inviteableContacts) { contact ->
                            val displayName = contact.name.ifEmpty { contact.nickname.ifEmpty { "#${contact.userId}" } }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.invite(contact.userId, shareHistory)
                                        showInviteDialog = false
                                        searchQuery = ""
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(
                                    userId = contact.userId,
                                    name = displayName,
                                    size = 40.dp,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(displayName, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Medium)
                                    if (contact.nickname.isNotEmpty()) {
                                        Text("@${contact.nickname}", color = LocalAppColors.current.textMuted, fontSize = 12.sp)
                                    }
                                }
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = LocalAppColors.current.accent, modifier = Modifier.size(20.dp))
                            }
                            HorizontalDivider(color = LocalAppColors.current.border)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInviteDialog = false; searchQuery = "" }) {
                    Text("Закрыть", color = LocalAppColors.current.accent)
                }
            }
        )
    }

    if (showStickerPicker) {
        StickerPickerBottomSheet(
            stickerRepository = viewModel.stickerRepository,
            onDismiss = { showStickerPicker = false },
            onStickerSelect = { sticker ->
                val currentReply = activeReply
                activeReply = null
                viewModel.sendSticker(sticker, currentReply?.msgId)
            },
            onOpenPack = { packId ->
                selectedStickerPackId = packId
            }
        )
    }

    selectedStickerPackId?.let { packId ->
        StickerPackDetailDialog(
            packId = packId,
            stickerRepository = viewModel.stickerRepository,
            onDismiss = { selectedStickerPackId = null },
            onStickerSelect = { sticker ->
                val currentReply = activeReply
                activeReply = null
                viewModel.sendSticker(sticker, currentReply?.msgId)
            }
        )
    }

    FullscreenImageViewer(url = fullscreenAvatarUrl, onDismiss = { fullscreenAvatarUrl = null })
}
