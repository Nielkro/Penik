package niel.kro.penik.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import niel.kro.penik.ui.components.UserAvatar
import niel.kro.penik.ui.screen.calls.CallsListScreen
import niel.kro.penik.ui.screen.chatslist.ChatsListContent
import niel.kro.penik.ui.screen.profile.ProfileScreen
import niel.kro.penik.ui.theme.LocalAppColors
import niel.kro.penik.ui.theme.NavigationStyle
import niel.kro.penik.ui.theme.NavigationStyleManager
import niel.kro.penik.ui.theme.ThemeManager
import niel.kro.penik.ui.viewmodel.GroupsViewModel
import niel.kro.penik.ui.viewmodel.ProfileViewModel

@Composable
fun MainScreen(
    onChatClick: (Long, String) -> Unit,
    onGroupClick: (Long, String) -> Unit,
    onLogout: () -> Unit,
    onPairingScanner: () -> Unit,
    onSettings: () -> Unit,
    profileViewModel: ProfileViewModel = hiltViewModel(),
    groupsViewModel: GroupsViewModel = hiltViewModel()
) {
    val colors = LocalAppColors.current
    val navigationStyle by NavigationStyleManager.navigationStyle.collectAsState()
    val isDrawerMode = navigationStyle == NavigationStyle.DRAWER

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val displayName = profileViewModel.name.ifBlank { profileViewModel.nickname }
    val nickname = profileViewModel.nickname
    val isLight by ThemeManager.isLight.collectAsState()
    val userAvatarKeys by niel.kro.penik.data.repository.AvatarCacheBus.userAvatarKeys.collectAsState()

    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    val isGroupBusy by groupsViewModel.busy.collectAsState()

    BackHandler(enabled = isDrawerMode && (drawerState.isOpen || selectedTab != 0)) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (selectedTab != 0) {
            selectedTab = 0
        }
    }

    val mainContent = @Composable {
        Scaffold(
            containerColor = colors.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!isDrawerMode) {
                    NavigationBar(
                        containerColor = colors.panel,
                        contentColor = colors.textPrimary
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Chat, contentDescription = "Чаты") },
                            label = { Text("Чаты") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.accent,
                                selectedTextColor = colors.accent,
                                unselectedIconColor = colors.textMuted,
                                unselectedTextColor = colors.textMuted,
                                indicatorColor = colors.accent.copy(alpha = 0.12f)
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.Call, contentDescription = "Звонки") },
                            label = { Text("Звонки") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.accent,
                                selectedTextColor = colors.accent,
                                unselectedIconColor = colors.textMuted,
                                unselectedTextColor = colors.textMuted,
                                indicatorColor = colors.accent.copy(alpha = 0.12f)
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.Person, contentDescription = "Профиль") },
                            label = { Text("Профиль") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.accent,
                                selectedTextColor = colors.accent,
                                unselectedIconColor = colors.textMuted,
                                unselectedTextColor = colors.textMuted,
                                indicatorColor = colors.accent.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    0 -> ChatsListContent(
                        onChatClick = onChatClick,
                        onGroupClick = onGroupClick,
                        onSettings = onSettings,
                        onOpenDrawer = if (isDrawerMode) {
                            { scope.launch { drawerState.open() } }
                        } else null
                    )
                    1 -> CallsListScreen(
                        onChatClick = onChatClick,
                        onBack = if (isDrawerMode) {
                            { selectedTab = 0 }
                        } else null
                    )
                    2 -> ProfileScreen(
                        onLogout = onLogout,
                        onPairingScanner = onPairingScanner,
                        onBack = if (isDrawerMode) {
                            { selectedTab = 0 }
                        } else null,
                        viewModel = profileViewModel
                    )
                }
            }
        }
    }

    if (isDrawerMode) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = colors.panel,
                    drawerContentColor = colors.textPrimary,
                    modifier = Modifier.width(300.dp)
                ) {
                    // Telegram-style Header
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.panelSecondary)
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            UserAvatar(
                                userId = profileViewModel.userId,
                                name = displayName,
                                size = 64.dp,
                                avatarKey = userAvatarKeys[profileViewModel.userId]
                            )
                            IconButton(
                                onClick = { ThemeManager.toggle() },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Text(
                                    text = if (isLight) "☀️" else "🌙",
                                    fontSize = 22.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = colors.textPrimary
                        )
                        if (nickname.isNotBlank()) {
                            Text(
                                text = "@$nickname",
                                fontSize = 13.5.sp,
                                color = colors.textMuted
                            )
                        }
                    }

                    HorizontalDivider(color = colors.border, thickness = 0.5.dp)

                    Spacer(modifier = Modifier.height(8.dp))

                    DrawerItem(
                        icon = Icons.Default.Person,
                        label = "Мой профиль",
                        onClick = {
                            scope.launch { drawerState.close() }
                            selectedTab = 2
                        }
                    )
                    DrawerItem(
                        icon = Icons.Default.GroupAdd,
                        label = "Создать группу",
                        onClick = {
                            scope.launch { drawerState.close() }
                            showCreateGroupDialog = true
                        }
                    )
                    DrawerItem(
                        icon = Icons.Default.Call,
                        label = "Звонки",
                        onClick = {
                            scope.launch { drawerState.close() }
                            selectedTab = 1
                        }
                    )
                    DrawerItem(
                        icon = Icons.Default.Settings,
                        label = "Настройки",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onSettings()
                        }
                    )
                    DrawerItem(
                        icon = Icons.Default.QrCodeScanner,
                        label = "Связать устройство",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onPairingScanner()
                        }
                    )
                }
            }
        ) {
            mainContent()
        }
    } else {
        mainContent()
    }

    if (showCreateGroupDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateGroupDialog = false
                newGroupName = ""
            },
            containerColor = colors.panel,
            titleContentColor = colors.textPrimary,
            title = { Text("Создать группу", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("Название группы") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.inputBg,
                        unfocusedContainerColor = colors.inputBg,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedLabelColor = colors.accent,
                        unfocusedLabelColor = colors.textMuted
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val nameToCreate = newGroupName.trim()
                        if (nameToCreate.isNotBlank()) {
                            groupsViewModel.createGroup(nameToCreate) { id, name ->
                                showCreateGroupDialog = false
                                newGroupName = ""
                                onGroupClick(id, name)
                            }
                        }
                    },
                    enabled = newGroupName.isNotBlank() && !isGroupBusy
                ) {
                    Text("Создать", color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateGroupDialog = false
                    newGroupName = ""
                }) {
                    Text("Отмена", color = colors.textMuted)
                }
            }
        )
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.textMuted,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = label,
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
