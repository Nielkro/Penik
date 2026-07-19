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
import niel.kro.penik.ui.viewmodel.StartupViewModel
import niel.kro.penik.ui.screen.pairing.PairingScannerScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    startupViewModel: StartupViewModel = hiltViewModel()
) {
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
                onLogout = {
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
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
            val chatName = backStackEntry.arguments?.getString("chatName") ?: ""
            ChatRoomScreen(
                chatUserId = chatUserId,
                chatName = chatName,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
