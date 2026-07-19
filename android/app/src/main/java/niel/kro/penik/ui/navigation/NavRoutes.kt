package niel.kro.penik.ui.navigation

sealed class Screen(val route: String) {
    object Auth : Screen("auth")
    object Main : Screen("main")
    object PairingScanner : Screen("pairing/scanner")
    object ChatRoom : Screen("chat/{chatUserId}/{chatName}") {
        fun createRoute(chatUserId: Long, chatName: String) = "chat/$chatUserId/$chatName"
    }
}
