package niel.kro.penik.ui.screen.groups

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.ui.components.GroupAvatar
import niel.kro.penik.ui.components.MessageBubble
import niel.kro.penik.ui.components.UserAvatar
import niel.kro.penik.ui.theme.Accent
import niel.kro.penik.ui.theme.Background
import niel.kro.penik.ui.theme.Border
import niel.kro.penik.ui.theme.InputBg
import niel.kro.penik.ui.theme.Panel
import niel.kro.penik.ui.theme.TextMuted
import niel.kro.penik.ui.theme.TextPrimary
import niel.kro.penik.ui.viewmodel.GroupChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupId: Long,
    groupName: String,
    onBack: () -> Unit,
    onGroupSettingsClick: (Long) -> Unit,
    viewModel: GroupChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val members by viewModel.members.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val error by viewModel.error.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showMembersDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var memberToRemove by remember { mutableStateOf<niel.kro.penik.data.local.entity.GroupMemberEntity?>(null) }
    var selectedMemberForActions by remember { mutableStateOf<niel.kro.penik.data.local.entity.GroupMemberEntity?>(null) }

    val canManage = viewModel.myRole in listOf("owner", "admin")

    var previousSize by remember { mutableStateOf(0) }
    LaunchedEffect(messages.size) {
        if (messages.size > previousSize) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.lastIndex)
            }
        }
        previousSize = messages.size
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
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.clickable { onGroupSettingsClick(groupId) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GroupAvatar(
                            groupId = groupId,
                            name = groupName,
                            size = 36.dp,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        Column {
                            Text(groupName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("${members.size} участников", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = TextPrimary)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Меню", tint = TextPrimary)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(Panel)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Настройки группы", color = TextPrimary) },
                                onClick = { showMenu = false; onGroupSettingsClick(groupId) }
                            )
                            DropdownMenuItem(
                                text = { Text("Участники", color = TextPrimary) },
                                onClick = { showMenu = false; showMembersDialog = true }
                            )
                            if (canManage) {
                                DropdownMenuItem(
                                    text = { Text("Пригласить", color = TextPrimary) },
                                    onClick = { showMenu = false; showInviteDialog = true }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Panel)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                reverseLayout = false
            ) {
                items(messages) { msg ->
                    val isOwn = msg.sentByMe
                    val senderMember = members.find { it.userId == msg.senderUserId }
                    val displayName = senderMember?.let {
                        it.name.ifEmpty { it.nickname.ifEmpty { "#${it.userId}" } }
                    } ?: "#${msg.senderUserId}"
                    
                    MessageBubble(
                        text = msg.text,
                        isSentByMe = isOwn,
                        timestamp = msg.createdAt,
                        delivered = if (isOwn) msg.delivered else false,
                        senderName = displayName,
                        senderUserId = msg.senderUserId
                    )
                }
            }

            HorizontalDivider(color = Border, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Сообщение", color = TextMuted) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = InputBg,
                        unfocusedContainerColor = InputBg,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.send(inputText)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить",
                        tint = if (inputText.isNotBlank()) Accent else TextMuted
                    )
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
                    colors = CardDefaults.cardColors(containerColor = Panel)
                ) {
                    Text(error!!, color = TextPrimary, modifier = Modifier.padding(12.dp))
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
            containerColor = Panel,
            titleContentColor = TextPrimary,
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
                            val isPrivileged = viewModel.myRole == "owner" || viewModel.myRole == "admin"
                            val canManageRow = viewModel.myRole == "owner" && member.role != "owner" && !isMe
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
                                    Text(displayNameWithMe, color = TextPrimary, fontWeight = FontWeight.Medium)
                                    Text(roleRu, color = TextMuted, fontSize = 12.sp)
                                }
                            }
                            if (index < sortedMembers.lastIndex) {
                                HorizontalDivider(color = Border)
                            }
                        }
                    }
                    if (canManage) {
                        HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 4.dp))
                        TextButton(
                            onClick = {
                                showMembersDialog = false
                                showInviteDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("＋ Добавить участника", color = Accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMembersDialog = false }) {
                    Text("Закрыть", color = Accent)
                }
            }
        )
    }

    selectedMemberForActions?.let { member ->
        val isMe = member.userId == viewModel.myUserId
        val isPrivileged = viewModel.myRole == "owner" || viewModel.myRole == "admin"
        val canManageRow = viewModel.myRole == "owner" && member.role != "owner" && !isMe
        val canRemoveRow = isPrivileged && member.role != "owner" && !isMe

        AlertDialog(
            onDismissRequest = { selectedMemberForActions = null },
            containerColor = Panel,
            titleContentColor = TextPrimary,
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
                                color = Accent,
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
                            Text("Удалить из группы", color = Accent, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedMemberForActions = null }) {
                    Text("Отмена", color = TextPrimary)
                }
            }
        )
    }

    memberToRemove?.let { member ->
        val displayName = member.name.ifEmpty { member.nickname.ifEmpty { "#${member.userId}" } }
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            containerColor = Panel,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
            title = { Text("Удалить участника", fontWeight = FontWeight.SemiBold) },
            text = { Text("Вы действительно хотите удалить участника $displayName из группы?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMember(member.userId)
                    memberToRemove = null
                }) {
                    Text("Удалить", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToRemove = null }) {
                    Text("Отмена", color = TextPrimary)
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
            containerColor = Panel,
            titleContentColor = TextPrimary,
            title = { Text("Пригласить участника", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Поиск контактов") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = InputBg,
                            unfocusedContainerColor = InputBg,
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = Accent,
                            unfocusedLabelColor = TextMuted
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
                                checkedColor = Accent,
                                uncheckedColor = TextMuted,
                                checkmarkColor = TextPrimary
                            )
                        )
                        Text(
                            text = "Поделиться историей чата до вступления",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    HorizontalDivider(color = Border)
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
                                    Text(displayName, color = TextPrimary, fontWeight = FontWeight.Medium)
                                    if (contact.nickname.isNotEmpty()) {
                                        Text("@${contact.nickname}", color = TextMuted, fontSize = 12.sp)
                                    }
                                }
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                            }
                            HorizontalDivider(color = Border)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInviteDialog = false; searchQuery = "" }) {
                    Text("Закрыть", color = Accent)
                }
            }
        )
    }
}
