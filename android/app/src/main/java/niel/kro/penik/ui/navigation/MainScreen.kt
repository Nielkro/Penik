package niel.kro.penik.ui.navigation

import niel.kro.penik.ui.theme.LocalAppColors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import niel.kro.penik.ui.screen.chatslist.ChatsListContent
import niel.kro.penik.ui.screen.profile.ProfileScreen

@Composable
fun MainScreen(
    onChatClick: (Long, String) -> Unit,
    onGroupClick: (Long, String) -> Unit,
    onLogout: () -> Unit,
    onPairingScanner: () -> Unit,
    onSettings: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        bottomBar = {
            NavigationBar(
                containerColor = LocalAppColors.current.panel,
                contentColor = LocalAppColors.current.textPrimary
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Chat, contentDescription = "Чаты") },
                    label = { androidx.compose.material3.Text("Чаты") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = LocalAppColors.current.accent,
                        selectedTextColor = LocalAppColors.current.accent,
                        unselectedIconColor = LocalAppColors.current.textMuted,
                        unselectedTextColor = LocalAppColors.current.textMuted,
                        indicatorColor = LocalAppColors.current.accent.copy(alpha = 0.12f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Person, contentDescription = "Профиль") },
                    label = { androidx.compose.material3.Text("Профиль") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = LocalAppColors.current.accent,
                        selectedTextColor = LocalAppColors.current.accent,
                        unselectedIconColor = LocalAppColors.current.textMuted,
                        unselectedTextColor = LocalAppColors.current.textMuted,
                        indicatorColor = LocalAppColors.current.accent.copy(alpha = 0.12f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ChatsListContent(onChatClick = onChatClick, onGroupClick = onGroupClick, onSettings = onSettings)
                1 -> ProfileScreen(onLogout = onLogout, onPairingScanner = onPairingScanner)
            }
        }
    }
}
