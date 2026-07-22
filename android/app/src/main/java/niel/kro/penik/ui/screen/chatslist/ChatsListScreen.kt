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
import niel.kro.penik.ui.components.SearchUserItem
import niel.kro.penik.ui.theme.Background
import niel.kro.penik.ui.theme.Border
import niel.kro.penik.ui.theme.InputBg
import niel.kro.penik.ui.theme.TextMuted
import niel.kro.penik.ui.theme.TextPrimary
import niel.kro.penik.ui.viewmodel.ChatsListViewModel

private const val SELF_CHAT_NAME = "Избранное"
private const val SELF_CHAT_ICON = "\uD83D\uDCDD"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListContent(
    onChatClick: (Long, String) -> Unit,
    viewModel: ChatsListViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(searchQuery) {
        viewModel.searchUsers(searchQuery)
    }

    val filteredChats = if (searchQuery.isBlank()) {
        chats
    } else {
        chats.filter {
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
                        placeholder = { Text("Поиск людей...", color = TextMuted) },
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
                        text = "Чаты",
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

                if (filteredChats.isNotEmpty()) {
                    item {
                        Text(
                            text = "Сообщения",
                            color = TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(filteredChats, key = { it.userId }) { chat ->
                        ChatListItem(
                            name = chat.name.ifBlank { chat.nickname },
                            userId = chat.userId,
                            lastMessage = chat.lastMessage,
                            timestamp = chat.lastMessageTimestamp,
                            unreadCount = chat.unreadCount,
                            onClick = {
                                onChatClick(chat.userId, chat.name.ifBlank { chat.nickname })
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
                    text = if (filteredChats.isEmpty()) "Ничего не найдено" else "",
                    color = TextMuted,
                    fontSize = 16.sp
                )
            }
            if (filteredChats.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredChats, key = { it.userId }) { chat ->
                        ChatListItem(
                            name = chat.name.ifBlank { chat.nickname },
                            userId = chat.userId,
                            lastMessage = chat.lastMessage,
                            timestamp = chat.lastMessageTimestamp,
                            unreadCount = chat.unreadCount,
                            onClick = {
                                onChatClick(chat.userId, chat.name.ifBlank { chat.nickname })
                            }
                        )
                    }
                }
            }
        } else {
            if (filteredChats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Нет чатов",
                        color = TextMuted,
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "self_chat") {
                        SearchUserItem(
                            name = "$SELF_CHAT_ICON $SELF_CHAT_NAME",
                            userId = viewModel.selfChatEntry?.id ?: 0L,
                            nickname = "",
                            onClick = {
                                val myId = viewModel.selfChatEntry?.id ?: return@SearchUserItem
                                onChatClick(myId, SELF_CHAT_NAME)
                            }
                        )
                        HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    items(filteredChats, key = { it.userId }) { chat ->
                        ChatListItem(
                            name = chat.name.ifBlank { chat.nickname },
                            userId = chat.userId,
                            lastMessage = chat.lastMessage,
                            timestamp = chat.lastMessageTimestamp,
                            unreadCount = chat.unreadCount,
                            onClick = {
                                onChatClick(chat.userId, chat.name.ifBlank { chat.nickname })
                            }
                        )
                    }
                }
            }
        }
    }
}
