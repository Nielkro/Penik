package niel.kro.penik.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import niel.kro.penik.ui.screen.auth.AuthScreen
import niel.kro.penik.ui.screen.chatroom.ChatRoomScreen
import niel.kro.penik.ui.screen.groups.GroupChatScreen
import niel.kro.penik.ui.screen.groups.GroupSettingsScreen
import niel.kro.penik.ui.screen.settings.SettingsScreen
import niel.kro.penik.ui.screen.settings.DevicesScreen
import niel.kro.penik.ui.viewmodel.StartupViewModel
import niel.kro.penik.ui.screen.pairing.PairingScannerScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    startupViewModel: StartupViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        startupViewModel.unauthorizedEvents.collect {
            startupViewModel.logout()
            android.widget.Toast.makeText(
                context,
                "Сессия завершена или отозвана (401). Пожалуйста, войдите снова.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            navController.navigate(Screen.Auth.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val startDestination = remember {
        if (startupViewModel.isLoggedIn()) Screen.Main.route
        else Screen.Auth.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Auth.route) {
            AuthScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Main.route) {
            MainScreen(
                onChatClick = { userId, name ->
                    navController.navigate(Screen.ChatRoom.createRoute(userId, name))
                },
                onGroupClick = { groupId, name ->
                    navController.navigate(Screen.GroupChat.createRoute(groupId, name))
                },
                onLogout = {
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onPairingScanner = { navController.navigate(Screen.PairingScanner.route) },
                onSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onDevices = { navController.navigate(Screen.Devices.route) }
            )
        }

        composable(Screen.Devices.route) {
            DevicesScreen(
                onBack = { navController.popBackStack() },
                onPairingScanner = { navController.navigate(Screen.PairingScanner.route) }
            )
        }

        composable(Screen.PairingScanner.route) {
            PairingScannerScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Screen.ChatRoom.route,
            arguments = listOf(
                navArgument("chatUserId") { type = NavType.LongType },
                navArgument("chatName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val chatUserId = backStackEntry.arguments?.getLong("chatUserId") ?: return@composable
            val chatName = android.net.Uri.decode(backStackEntry.arguments?.getString("chatName") ?: "")
            ChatRoomScreen(
                chatUserId = chatUserId,
                chatName = chatName,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.GroupChat.route,
            arguments = listOf(
                navArgument("groupId") { type = NavType.LongType },
                navArgument("groupName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getLong("groupId") ?: return@composable
            val groupName = android.net.Uri.decode(backStackEntry.arguments?.getString("groupName") ?: "")
            GroupChatScreen(
                groupId = groupId,
                groupName = groupName,
                onBack = { navController.popBackStack() },
                onGroupSettingsClick = { id ->
                    navController.navigate(Screen.GroupSettings.createRoute(id))
                }
            )
        }

        composable(
            route = Screen.GroupSettings.route,
            arguments = listOf(
                navArgument("groupId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getLong("groupId") ?: return@composable
            GroupSettingsScreen(
                groupId = groupId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
