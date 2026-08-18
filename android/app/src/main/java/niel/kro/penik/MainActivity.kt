package niel.kro.penik

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import niel.kro.penik.ui.navigation.NavGraph
import niel.kro.penik.ui.navigation.Screen
import niel.kro.penik.ui.notification.AppNotificationManager
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.FcmTokenRequestBody
import niel.kro.penik.data.repository.SecureTokenStorage
import niel.kro.penik.ui.theme.ThemeManager
import niel.kro.penik.ui.theme.PenikTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenStorage: SecureTokenStorage

    @Inject
    lateinit var apiService: ApiService

    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        enableEdgeToEdge()
        extractNavigationRoute(intent)

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                if (token != null) {
                    tokenStorage.saveFcmToken(token)
                    lifecycleScope.launch {
                        if (tokenStorage.isLoggedIn()) {
                            runCatching {
                                apiService.updateFcmToken(FcmTokenRequestBody(token))
                            }
                        }
                    }
                }
            }
        }

        setContent {
            val isLight by ThemeManager.isLight.collectAsState()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            PenikTheme(isLight = isLight) {
                val navController = rememberNavController()

                LaunchedEffect(pendingRoute) {
                    val route = pendingRoute
                    if (route != null) {
                        pendingRoute = null
                        navController.navigate(route)
                    }
                }

                NavGraph(navController = navController)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractNavigationRoute(intent)
    }

    private fun extractNavigationRoute(intent: Intent?) {
        if (intent == null) return
        val chatUserId = intent.getLongExtra(AppNotificationManager.EXTRA_CHAT_USER_ID, -1L)
        val chatName = intent.getStringExtra(AppNotificationManager.EXTRA_CHAT_NAME) ?: ""
        if (chatUserId > 0) {
            pendingRoute = Screen.ChatRoom.createRoute(chatUserId, chatName)
            return
        }

        val groupId = intent.getLongExtra(AppNotificationManager.EXTRA_GROUP_ID, -1L)
        val groupName = intent.getStringExtra(AppNotificationManager.EXTRA_GROUP_NAME) ?: ""
        if (groupId > 0) {
            pendingRoute = Screen.GroupChat.createRoute(groupId, groupName)
        }
    }
}
