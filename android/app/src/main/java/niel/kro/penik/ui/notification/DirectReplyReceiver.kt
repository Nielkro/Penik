package niel.kro.penik.ui.notification

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.repository.ChatRepository
import niel.kro.penik.data.repository.MessageRepository
import niel.kro.penik.data.repository.SecureTokenStorage
import javax.inject.Inject

@AndroidEntryPoint
class DirectReplyReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var tokenStorage: SecureTokenStorage
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var appNotificationManager: AppNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = remoteInput.getCharSequence(AppNotificationManager.KEY_TEXT_REPLY)
            ?.toString()?.trim() ?: return
        if (replyText.isBlank()) return

        val chatUserId = intent.getLongExtra(AppNotificationManager.EXTRA_CHAT_USER_ID, -1L)
        val chatName = intent.getStringExtra(AppNotificationManager.EXTRA_CHAT_NAME) ?: ""
        val rawReplyToMsgId = intent.getStringExtra(AppNotificationManager.EXTRA_LAST_MSG_SERVER_ID)
        val replyToMsgId = when {
            rawReplyToMsgId.isNullOrBlank() -> null
            rawReplyToMsgId.startsWith("server-") -> rawReplyToMsgId
            rawReplyToMsgId.toLongOrNull() != null -> rawReplyToMsgId
            else -> rawReplyToMsgId
        }
        if (chatUserId <= 0) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                messageRepository.sendMessage(
                    toUserId = chatUserId,
                    text = replyText,
                    replyToMsgId = replyToMsgId
                )
                chatRepository.updateLastMessage(chatUserId, replyText, System.currentTimeMillis(), name = chatName)
                chatRepository.clearUnread(chatUserId)
                runCatching { apiService.markMessagesRead(chatUserId) }
                appNotificationManager.onReplySent(chatUserId, replyText)
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }
}
