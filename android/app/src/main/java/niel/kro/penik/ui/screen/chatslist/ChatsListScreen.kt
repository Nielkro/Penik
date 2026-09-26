package niel.kro.penik.ui.screen.chatslist

import niel.kro.penik.ui.theme.LocalAppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.ui.components.ChatListItem
import niel.kro.penik.ui.components.ConnectionStatusBar
import niel.kro.penik.ui.components.FullscreenImageViewer
import niel.kro.penik.ui.components.SearchUserItem
import niel.kro.penik.ui.theme.AppIconManager
import niel.kro.penik.ui.viewmodel.ChatsListViewModel

import niel.kro.penik.ui.viewmodel.FeedItem

private const val SELF_CHAT_NAME = "Избранное"
private const val SELF_CHAT_ICON = "\uD83D\uDCDD"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListContent(
    onChatClick: (Long, String, Boolean) -> Unit,
    onGroupClick: (Long, String) -> Unit,
    onSettings: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ChatsListViewModel = hiltViewModel()
) {
    val isArchiveOpen by viewModel.isArchiveOpen.collectAsState()

    androidx.activity.compose.BackHandler(enabled = isArchiveOpen) {
        viewModel.closeArchive()
    }
    val archivedCount by viewModel.archivedCount.collectAsState()
    val feed by viewModel.feed.collectAsState()
    val isInitialLoading by viewModel.isInitialLoading.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val selfChatLastMessage by viewModel.selfChatLastMessage.collectAsState()
    val groupAvatarKeys by niel.kro.penik.data.repository.AvatarCacheBus.groupAvatarKeys.collectAsState()
    val userAvatarKeys by niel.kro.penik.data.repository.AvatarCacheBus.userAvatarKeys.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedItemForMenu by remember { mutableStateOf<FeedItem?>(null) }
    var fullscreenAvatarUrl by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(searchQuery) {
        viewModel.searchUsers(searchQuery)
    }

    val filteredFeed = if (searchQuery.isBlank()) {
        feed
    } else {
        feed.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.lastMessage?.contains(searchQuery, ignoreCase = true) == true
        }
    }

    val isSearching = searchQuery.isNotBlank()
    val currentVariant by AppIconManager.currentVariant.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (isSearchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("Поиск...", color = LocalAppColors.current.textMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = LocalAppColors.current.inputBg,
                            unfocusedContainerColor = LocalAppColors.current.inputBg,
                            focusedBorderColor = LocalAppColors.current.border,
                            unfocusedBorderColor = LocalAppColors.current.border,
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary
                        )
                    )
                } else if (isArchiveOpen) {
                    Text(
                        text = "Архив чатов",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                } else {
                    Column {
                        Text(
                            text = currentVariant.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                        if (!isOnline) {
                            Text(
                                text = "ожидание сети",
                                fontSize = 12.sp,
                                color = LocalAppColors.current.textMuted
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                if (isSearchActive) {
                    IconButton(onClick = {
                        isSearchActive = false
                        searchQuery = ""
                        viewModel.clearSearch()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Закрыть поиск",
                            tint = LocalAppColors.current.textPrimary
                        )
                    }
                } else if (isArchiveOpen) {
                    IconButton(onClick = { viewModel.closeArchive() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = LocalAppColors.current.textPrimary
                        )
                    }
                } else if (onOpenDrawer != null) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Меню",
                            tint = LocalAppColors.current.textPrimary
                        )
                    }
                }
            },
            actions = {
                if (!isSearchActive) {
                    IconButton(onClick = { isSearchActive = true }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Поиск",
                            tint = LocalAppColors.current.textPrimary
                        )
                    }
                    if (onOpenDrawer == null) {
                        IconButton(onClick = onSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Настройки",
                                tint = LocalAppColors.current.textPrimary
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = LocalAppColors.current.background,
                titleContentColor = LocalAppColors.current.textPrimary
            )
        )

        ConnectionStatusBar(connectionState = connectionState, isOnline = isOnline)

        if (isSearching && searchResults.isNotEmpty()) {
            Text(
                text = "Люди",
                color = LocalAppColors.current.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(searchResults, key = { it.id }) { user ->
                    val peerName = user.name.ifBlank { user.nickname }
                    SearchUserItem(
                        name = user.name,
                        userId = user.id,
                        nickname = user.nickname,
                        avatarKey = userAvatarKeys[user.id],
                        onClick = {
                            viewModel.startDirectChat(user.id, peerName, user.nickname, isE2EE = false) {
                                onChatClick(user.id, peerName, false)
                            }
                        }
                    )
                    HorizontalDivider(color = LocalAppColors.current.border, modifier = Modifier.padding(horizontal = 16.dp))
                }

                if (filteredFeed.isNotEmpty()) {
                    item {
                        Text(
                            text = "Чаты и группы",
                            color = LocalAppColors.current.textMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(filteredFeed, key = { "${if (it is FeedItem.ChatItem) "chat" else "group"}-${it.id}" }) { item ->
                        ChatListItem(
                            name = item.name,
                            userId = item.id,
                            lastMessage = item.lastMessage,
                            timestamp = item.lastMessageTimestamp,
                            unreadCount = item.unreadCount,
                            isGroup = item is FeedItem.GroupItem,
                            avatarKey = if (item is FeedItem.GroupItem) groupAvatarKeys[item.id] else userAvatarKeys[item.id],
                            isE2EE = item.isE2EE,
                            onClick = {
                                if (item is FeedItem.GroupItem) {
                                    onGroupClick(item.id, item.name)
                                } else {
                                    onChatClick(item.id, item.name, item.isE2EE)
                                }
                            },
                            onAvatarClick = { url -> fullscreenAvatarUrl = url }
                        )
                    }
                }
            }
        } else if (isSearching && searchResults.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (filteredFeed.isEmpty()) "Ничего не найдено" else "",
                    color = LocalAppColors.current.textMuted,
                    fontSize = 16.sp
                )
            }
            if (filteredFeed.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredFeed, key = { "${if (it is FeedItem.ChatItem) "chat" else "group"}-${it.id}" }) { item ->
                        ChatListItem(
                            name = item.name,
                            userId = item.id,
                            lastMessage = item.lastMessage,
                            timestamp = item.lastMessageTimestamp,
                            unreadCount = item.unreadCount,
                            isGroup = item is FeedItem.GroupItem,
                            avatarKey = if (item is FeedItem.GroupItem) groupAvatarKeys[item.id] else userAvatarKeys[item.id],
                            onClick = {
                                if (item is FeedItem.GroupItem) {
                                    onGroupClick(item.id, item.name)
                                } else {
                                    onChatClick(item.id, item.name, item.isE2EE)
                                }
                            },
                            onAvatarClick = { url -> fullscreenAvatarUrl = url }
                        )
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (isArchiveOpen) {
                    item(key = "archive_info_banner") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .background(LocalAppColors.current.panelSecondary, RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔒 Здесь сохранены ваши заархивированные E2EE чаты.\n\n⚡ Новые быстрые облачные чаты 1v1 доступны на главном экране и через поиск пользователей.",
                                fontSize = 12.sp,
                                color = LocalAppColors.current.textMuted,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
                if (!isArchiveOpen) {
                    item(key = "self_chat") {
                        SearchUserItem(
                            name = SELF_CHAT_NAME,
                            userId = viewModel.selfChatEntry?.id ?: 0L,
                            nickname = "",
                            lastMessage = selfChatLastMessage?.text,
                            timestamp = selfChatLastMessage?.timestamp,
                            onClick = {
                                val myId = viewModel.selfChatEntry?.id ?: return@SearchUserItem
                                onChatClick(myId, SELF_CHAT_NAME, false)
                            }
                        )
                        HorizontalDivider(color = LocalAppColors.current.border, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    if (archivedCount > 0) {
                        item(key = "archive_folder") {
                            Row(
                                modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openArchive() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(LocalAppColors.current.panelSecondary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📁", fontSize = 22.sp)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Архив чатов",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp,
                                        color = LocalAppColors.current.textPrimary
                                    )
                                    val chatWord = if (archivedCount % 10 == 1 && archivedCount % 100 != 11) "чат" else if (archivedCount % 10 in 2..4 && (archivedCount % 100 !in 12..14)) "чата" else "чатов"
                                    Text(
                                        text = "$archivedCount $chatWord",
                                        fontSize = 13.sp,
                                        color = LocalAppColors.current.textMuted
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .background(LocalAppColors.current.accent.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "$archivedCount",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LocalAppColors.current.accent
                                    )
                                }
                            }
                            HorizontalDivider(color = LocalAppColors.current.border, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
                if (filteredFeed.isEmpty()) {
                    if (!isInitialLoading) {
                        item(key = "empty_placeholder") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isArchiveOpen) "Архив пуст" else "Нет переписок",
                                    color = LocalAppColors.current.textMuted,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                } else {
                    items(filteredFeed, key = { "${if (it is FeedItem.ChatItem) "chat" else "group"}-${it.id}" }) { item ->
                        ChatListItem(
                            name = item.name,
                            userId = item.id,
                            lastMessage = item.lastMessage,
                            timestamp = item.lastMessageTimestamp,
                            unreadCount = item.unreadCount,
                            isGroup = item is FeedItem.GroupItem,
                            avatarKey = if (item is FeedItem.GroupItem) groupAvatarKeys[item.id] else userAvatarKeys[item.id],
                            isE2EE = item.isE2EE,
                            onClick = {
                                if (item is FeedItem.GroupItem) {
                                    onGroupClick(item.id, item.name)
                                } else {
                                    onChatClick(item.id, item.name, item.isE2EE)
                                }
                            },
                            onLongClick = {
                                selectedItemForMenu = item
                            },
                            onAvatarClick = { url -> fullscreenAvatarUrl = url }
                        )
                    }
                }
            }
        }
    }

    val itemToArchive = selectedItemForMenu
    if (itemToArchive != null) {
        val isArchived = itemToArchive.isArchived
        AlertDialog(
            onDismissRequest = { selectedItemForMenu = null },
            title = {
                Text(
                    text = itemToArchive.name,
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isArchived) "Вернуть из архива на главный экран?" else "Переместить в архив?",
                    color = LocalAppColors.current.textMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (itemToArchive is FeedItem.ChatItem) {
                            viewModel.toggleChatArchived(itemToArchive.id, itemToArchive.isE2EE, isArchived)
                        } else {
                            viewModel.toggleGroupArchived(itemToArchive.id, isArchived)
                        }
                        selectedItemForMenu = null
                    }
                ) {
                    Text(
                        text = if (isArchived) "Извлечь" else "В архив",
                        color = LocalAppColors.current.accent
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedItemForMenu = null }) {
                    Text("Отмена", color = LocalAppColors.current.textMuted)
                }
            },
            containerColor = LocalAppColors.current.panel,
            shape = RoundedCornerShape(16.dp)
        )
    }

    FullscreenImageViewer(url = fullscreenAvatarUrl, onDismiss = { fullscreenAvatarUrl = null })
}
