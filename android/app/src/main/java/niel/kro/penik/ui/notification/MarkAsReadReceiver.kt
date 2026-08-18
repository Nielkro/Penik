package niel.kro.penik.ui.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.repository.ChatRepository
import javax.inject.Inject

@AndroidEntryPoint
class MarkAsReadReceiver : BroadcastReceiver() {

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var appNotificationManager: AppNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        val chatUserId = intent.getLongExtra(AppNotificationManager.EXTRA_CHAT_USER_ID, -1L)
        if (chatUserId <= 0) return

        val pendingResult = goAsync()
        runBlocking(Dispatchers.IO) {
            try {
                // Mark read locally and clear badge.
                chatRepository.clearUnread(chatUserId)
                appNotificationManager.cancelChatNotification(chatUserId)
                // Send read receipts to server so sender gets the ✓✓.
                apiService.markMessagesRead(chatUserId)
            } catch (_: Exception) {
                // Best-effort — local state is already cleared
            } finally {
                pendingResult.finish()
            }
        }
    }
}
