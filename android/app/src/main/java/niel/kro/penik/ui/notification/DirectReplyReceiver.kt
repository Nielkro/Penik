package niel.kro.penik.ui.notification

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import niel.kro.penik.domain.usecase.SendMessageUseCase
import javax.inject.Inject

@AndroidEntryPoint
class DirectReplyReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sendMessageUseCase: SendMessageUseCase

    @Inject
    lateinit var appNotificationManager: AppNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = remoteInput.getCharSequence(AppNotificationManager.KEY_TEXT_REPLY)?.toString()
        if (replyText.isNullOrBlank()) return

        val chatUserId = intent.getLongExtra(AppNotificationManager.EXTRA_CHAT_USER_ID, -1L)
        val chatName = intent.getStringExtra(AppNotificationManager.EXTRA_CHAT_NAME) ?: ""

        if (chatUserId > 0) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sendMessageUseCase(chatUserId, replyText, chatName)
                    appNotificationManager.onReplySent(chatUserId, replyText)
                } catch (e: Exception) {
                    // Log error if any
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
