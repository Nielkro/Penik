package niel.kro.penik.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Auth : Screen("auth")
    object Main : Screen("main")
    object PairingScanner : Screen("pairing/scanner")
    object ChatRoom : Screen("chat/{chatUserId}/{chatName}") {
        fun createRoute(chatUserId: Long, chatName: String): String {
            val encodedName = Uri.encode(chatName.ifBlank { "Пользователь" })
            return "chat/$chatUserId/$encodedName"
        }
    }
    object GroupChat : Screen("group/{groupId}/{groupName}") {
        fun createRoute(groupId: Long, groupName: String): String {
            val encodedName = Uri.encode(groupName.ifBlank { "Группа" })
            return "group/$groupId/$encodedName"
        }
    }
    object GroupSettings : Screen("group_settings/{groupId}") {
        fun createRoute(groupId: Long) = "group_settings/$groupId"
    }
}
