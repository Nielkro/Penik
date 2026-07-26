package niel.kro.penik.ui.screen.groups

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.ui.components.GroupAvatar
import niel.kro.penik.ui.components.UserAvatar
import niel.kro.penik.ui.theme.Accent
import niel.kro.penik.ui.theme.Background
import niel.kro.penik.ui.theme.Border
import niel.kro.penik.ui.theme.Danger
import niel.kro.penik.ui.theme.InputBg
import niel.kro.penik.ui.theme.Panel
import niel.kro.penik.ui.theme.TextMuted
import niel.kro.penik.ui.theme.TextPrimary
import niel.kro.penik.ui.viewmodel.GroupSettingsViewModel

private fun formatDateTime(timestamp: Long): String {
    val ms = if (timestamp < 99999999999L) timestamp * 1000 else timestamp
    val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(ms))
}

private fun roleLabel(role: String): String = when (role) {
    "owner" -> "владелец"
    "admin" -> "админ"
    "member" -> "участник"
    else -> role
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(
    groupId: Long,
    onBack: () -> Unit,
    viewModel: GroupSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val group by viewModel.group.collectAsState()
    val members by viewModel.members.collectAsState()
    val contactsList by viewModel.contacts.collectAsState()
    val error by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val groupAvatarKeys by niel.kro.penik.data.repository.AvatarCacheBus.groupAvatarKeys.collectAsState()
    val avatarUpdateKey = groupAvatarKeys[groupId]

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInputText by remember { mutableStateOf("") }

    var showInviteDialog by remember { mutableStateOf(false) }
    var inviteSearchQuery by remember { mutableStateOf("") }
    var shareHistory by remember { mutableStateOf(false) }

    var selectedMemberForProfile by remember { mutableStateOf<niel.kro.penik.data.local.entity.GroupMemberEntity?>(null) }
    var memberToRemove by remember { mutableStateOf<niel.kro.penik.data.local.entity.GroupMemberEntity?>(null) }

    val myUserId = viewModel.myUserId
    val myRole = members.find { it.userId == myUserId }?.role ?: "member"
    val isMeOwner = myRole == "owner"
    val isMePrivileged = myRole == "owner" || myRole == "admin"

    val groupName = group?.name ?: ""

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val bytes = stream.readBytes()
                    viewModel.uploadAvatar(bytes)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Настройки группы", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = TextPrimary)
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
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))

                    // Group Avatar
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .clickable(enabled = isMePrivileged) { imagePickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        GroupAvatar(
                            groupId = groupId,
                            name = groupName,
                            size = 96.dp,
                            avatarKey = avatarUpdateKey
                        )

                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Accent,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    if (isMePrivileged) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Нажмите на аватар для смены",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Group Name and Info Card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Panel)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = groupName.ifEmpty { "Группа без названия" },
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Создана: " + (group?.createdAt?.let { formatDateTime(it) } ?: ""),
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }

                        if (isMePrivileged) {
                            IconButton(onClick = {
                                renameInputText = groupName
                                showRenameDialog = true
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Редактировать название", tint = Accent)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Members Title Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Участники (${members.size})",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (isMePrivileged) {
                            TextButton(onClick = { showInviteDialog = true }) {
                                Text("＋ Добавить", color = Accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    HorizontalDivider(color = Border)
                }

                // Members list
                val sortedMembers = members.sortedWith(compareBy {
                    when (it.role) {
                        "owner" -> 0
                        "admin" -> 1
                        "member" -> 2
                        else -> 3
                    }
                })

                itemsIndexed(sortedMembers) { index, member ->
                    val isMe = member.userId == myUserId

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Panel)
                            .clickable { selectedMemberForProfile = member }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                            val roleRu = roleLabel(member.role) + if (member.status == "pending") " · приглашён" else ""
                            Text(displayNameWithMe, color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(roleRu, color = TextMuted, fontSize = 12.sp)
                        }
                    }

                    if (index < sortedMembers.lastIndex) {
                        HorizontalDivider(color = Border)
                    }
                }
            }
        }
    }

    // Rename dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = Panel,
            titleContentColor = TextPrimary,
            title = { Text("Переименовать группу", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = renameInputText,
                    onValueChange = { renameInputText = it },
                    label = { Text("Название группы") },
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
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameInputText.isNotBlank()) {
                        viewModel.renameGroup(renameInputText)
                        showRenameDialog = false
                    }
                }) {
                    Text("Сохранить", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Отмена", color = TextPrimary)
                }
            }
        )
    }

    // Member Profile Dialog: avatar, name, @nickname, role, id, and — for privileged
    // viewers — actions to change role / remove the member.
    selectedMemberForProfile?.let { member ->
        val displayName = member.name.ifEmpty { member.nickname.ifEmpty { "#${member.userId}" } }
        val isMe = member.userId == myUserId
        val canManageRow = isMeOwner && member.role != "owner" && !isMe
        val canRemoveRow = isMePrivileged && member.role != "owner" && !isMe

        AlertDialog(
            onDismissRequest = { selectedMemberForProfile = null },
            containerColor = Panel,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
            title = {},
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    UserAvatar(userId = member.userId, name = displayName, size = 96.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(displayName, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (member.nickname.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("@${member.nickname}", color = TextMuted, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Border)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Роль", color = TextMuted, fontSize = 14.sp)
                        Text(roleLabel(member.role), color = TextPrimary, fontSize = 14.sp)
                    }
                    HorizontalDivider(color = Border)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ID", color = TextMuted, fontSize = 14.sp)
                        Text("${member.userId}", color = TextPrimary, fontSize = 14.sp)
                    }
                    HorizontalDivider(color = Border)

                    if (canManageRow || canRemoveRow) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    if (canManageRow) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val newRole = if (member.role == "admin") "member" else "admin"
                                viewModel.changeMemberRole(member.userId, newRole)
                                selectedMemberForProfile = null
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
                                selectedMemberForProfile = null
                            }
                        ) {
                            Text("Удалить из группы", color = Danger, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMemberForProfile = null }) {
                    Text("Закрыть", color = Accent)
                }
            }
        )
    }

    // Remove Confirmation Dialog
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

    // Invite dialog
    if (showInviteDialog) {
        val existingMemberIds = remember(members) { members.map { it.userId }.toSet() }
        val inviteableContacts = remember(contactsList, existingMemberIds, inviteSearchQuery) {
            contactsList.filter {
                it.userId !in existingMemberIds &&
                (inviteSearchQuery.isBlank() ||
                  it.nickname.contains(inviteSearchQuery, ignoreCase = true) ||
                  it.name.contains(inviteSearchQuery, ignoreCase = true))
            }
        }

        AlertDialog(
            onDismissRequest = { showInviteDialog = false; inviteSearchQuery = "" },
            containerColor = Panel,
            titleContentColor = TextPrimary,
            title = { Text("Пригласить участника", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = inviteSearchQuery,
                        onValueChange = { inviteSearchQuery = it },
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
                                        inviteSearchQuery = ""
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
                TextButton(onClick = { showInviteDialog = false; inviteSearchQuery = "" }) {
                    Text("Закрыть", color = Accent)
                }
            }
        )
    }
}
