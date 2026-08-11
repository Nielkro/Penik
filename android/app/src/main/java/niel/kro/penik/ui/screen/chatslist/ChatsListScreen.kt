package niel.kro.penik.ui.screen.chatslist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import niel.kro.penik.ui.theme.Background
import niel.kro.penik.ui.theme.Border
import niel.kro.penik.ui.theme.InputBg
import niel.kro.penik.ui.theme.TextMuted
import niel.kro.penik.ui.theme.TextPrimary
import niel.kro.penik.ui.viewmodel.ChatsListViewModel

import niel.kro.penik.ui.viewmodel.FeedItem

private const val SELF_CHAT_NAME = "Избранное"
private const val SELF_CHAT_ICON = "\uD83D\uDCDD"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListContent(
    onChatClick: (Long, String) -> Unit,
    onGroupClick: (Long, String) -> Unit,
    viewModel: ChatsListViewModel = hiltViewModel()
) {
    val feed by viewModel.feed.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val selfChatLastMessage by viewModel.selfChatLastMessage.collectAsState()
    val groupAvatarKeys by niel.kro.penik.data.repository.AvatarCacheBus.groupAvatarKeys.collectAsState()
    val userAvatarKeys by niel.kro.penik.data.repository.AvatarCacheBus.userAvatarKeys.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
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
                        placeholder = { Text("Поиск...", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = InputBg,
                            unfocusedContainerColor = InputBg,
                            focusedBorderColor = Border,
                            unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                } else {
                    Text(
                        text = "Чаты и группы",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
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
                            tint = TextPrimary
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
                            tint = TextPrimary
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Background,
                titleContentColor = TextPrimary
            )
        )

        ConnectionStatusBar(connectionState = connectionState)

        if (isSearching && searchResults.isNotEmpty()) {
            Text(
                text = "Люди",
                color = TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(searchResults, key = { it.id }) { user ->
                    SearchUserItem(
                        name = user.name,
                        userId = user.id,
                        nickname = user.nickname,
                        onClick = {
                            onChatClick(user.id, user.name.ifBlank { user.nickname })
                        }
                    )
                    HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 16.dp))
                }

                if (filteredFeed.isNotEmpty()) {
                    item {
                        Text(
                            text = "Чаты и группы",
                            color = TextMuted,
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
                            onClick = {
                                if (item is FeedItem.GroupItem) {
                                    onGroupClick(item.id, item.name)
                                } else {
                                    onChatClick(item.id, item.name)
                                }
                            }
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
                    color = TextMuted,
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
                                    onChatClick(item.id, item.name)
                                }
                            }
                        )
                    }
                }
            }
        } else {
            if (filteredFeed.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Нет переписок",
                        color = TextMuted,
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "self_chat") {
                        SearchUserItem(
                            name = SELF_CHAT_NAME,
                            userId = viewModel.selfChatEntry?.id ?: 0L,
                            nickname = "",
                            lastMessage = selfChatLastMessage?.text,
                            timestamp = selfChatLastMessage?.timestamp,
                            onClick = {
                                val myId = viewModel.selfChatEntry?.id ?: return@SearchUserItem
                                onChatClick(myId, SELF_CHAT_NAME)
                            }
                        )
                        HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 16.dp))
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
                            onClick = {
                                if (item is FeedItem.GroupItem) {
                                    onGroupClick(item.id, item.name)
                                } else {
                                    onChatClick(item.id, item.name)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    FullscreenImageViewer(url = fullscreenAvatarUrl, onDismiss = { fullscreenAvatarUrl = null })
}
