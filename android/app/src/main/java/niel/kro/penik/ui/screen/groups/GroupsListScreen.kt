package niel.kro.penik.ui.screen.groups

import niel.kro.penik.ui.theme.LocalAppColors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.ui.components.FullscreenImageViewer
import niel.kro.penik.ui.components.GroupAvatar
import niel.kro.penik.ui.components.avatarUrlFor
import niel.kro.penik.ui.viewmodel.GroupsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsListScreen(
    onGroupClick: (Long, String) -> Unit,
    viewModel: GroupsViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val groupAvatarKeys by niel.kro.penik.data.repository.AvatarCacheBus.groupAvatarKeys.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var fullscreenAvatarUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(error) {
        if (error != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            TopAppBar(
                title = { Text("Группы", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalAppColors.current.panel)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = LocalAppColors.current.accent,
                contentColor = LocalAppColors.current.background
            ) {
                Icon(Icons.Default.Add, contentDescription = "Создать группу")
            }
        }
    ) { padding ->
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Нет групп", color = LocalAppColors.current.textMuted, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(groups) { group ->
                    val isPending = group.status == "pending"
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .then(
                                if (isPending) Modifier
                                else Modifier.clickable { onGroupClick(group.id, group.name) }
                            ),
                        colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.panel),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.clickable {
                                    fullscreenAvatarUrl = avatarUrlFor(true, group.id, groupAvatarKeys[group.id])
                                }
                            ) {
                                GroupAvatar(
                                    groupId = group.id,
                                    name = group.name,
                                    size = 44.dp,
                                    modifier = Modifier.padding(end = 12.dp),
                                    avatarKey = groupAvatarKeys[group.id]
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(group.name, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                                Text(
                                    if (isPending) "Приглашение в группу" else "Группа",
                                    color = LocalAppColors.current.textMuted,
                                    fontSize = 14.sp
                                )
                            }
                            if (isPending) {
                                TextButton(onClick = { viewModel.acceptInvitation(group.id) }) {
                                    Text("Принять", color = LocalAppColors.current.accent)
                                }
                                TextButton(onClick = { viewModel.declineInvitation(group.id) }) {
                                    Text("Отклонить", color = LocalAppColors.current.textMuted)
                                }
                            }
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

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newGroupName = "" },
            containerColor = LocalAppColors.current.panel,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = { Text("Создать группу", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("Название группы") },
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
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createGroup(newGroupName) { id, name ->
                            showCreateDialog = false
                            newGroupName = ""
                            onGroupClick(id, name)
                        }
                    },
                    enabled = newGroupName.isNotBlank() && !busy
                ) {
                    Text("Создать", color = LocalAppColors.current.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newGroupName = "" }) {
                    Text("Отмена", color = LocalAppColors.current.textMuted)
                }
            }
        )
    }

    FullscreenImageViewer(url = fullscreenAvatarUrl, onDismiss = { fullscreenAvatarUrl = null })
}
