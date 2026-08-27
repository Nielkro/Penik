package niel.kro.penik.ui.notification

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.FcmTokenRequestBody
import niel.kro.penik.data.repository.GroupRepository
import niel.kro.penik.data.repository.MessageRepository
import niel.kro.penik.data.repository.SecureTokenStorage
import niel.kro.penik.data.network.websocket.WebSocketEvent
import javax.inject.Inject

/**
 * Receives push notifications.
 *
 * Pushes are pointers, not payloads: FCM caps a data message at ~4 KB, so an
 * attachment or a long message used to be dropped by Google with no error the
 * user could see. The push now carries only an id, and the body is resolved over
 * REST and decrypted locally, which also means the ciphertext never sits in a
 * third-party notification queue.
 */
@AndroidEntryPoint
class PenikFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var appNotificationManager: AppNotificationManager

    @Inject
    lateinit var tokenStorage: SecureTokenStorage

    @Inject
    lateinit var apiService: ApiService

    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var groupRepository: GroupRepository

    @Inject
    lateinit var callManager: niel.kro.penik.domain.call.CallManager

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        runBlocking {
            tokenStorage.saveFcmToken(token)
            if (tokenStorage.isLoggedIn() && tokenStorage.getLastUploadedFcmToken() != token) {
                runCatching {
                    val resp = apiService.updateFcmToken(FcmTokenRequestBody(token))
                    if (resp.isSuccessful) {
                        tokenStorage.saveLastUploadedFcmToken(token)
                    }
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        if (data.isEmpty()) return

        runBlocking {
            val type = data["type"]
            val fallbackText = data["text"] ?: ""
            val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

            if (type == "group") {
                val groupId = data["group_id"]?.toLongOrNull() ?: return@runBlocking
                val senderUserId = data["sender_user_id"]?.toLongOrNull() ?: return@runBlocking
                val rowId = data["row_id"]?.toLongOrNull() ?: 0L

                val text = resolveGroupText(groupId, rowId) ?: fallbackText
                appNotificationManager.showGroupMessageNotification(
                    groupId = groupId,
                    senderUserId = senderUserId,
                    rawText = text,
                    timestamp = timestamp,
                    overrideGroupName = data["group_name"],
                    overrideSenderName = data["sender_name"]
                )
            } else if (type == "call") {
                val callId = data["call_id"] ?: return@runBlocking
                val fromUserId = data["from_user_id"]?.toLongOrNull() ?: return@runBlocking
                val isVideo = data["is_video"] == "true"
                val roomName = data["room_name"] ?: return@runBlocking
                val livekitUrl = data["livekit_url"] ?: return@runBlocking
                val livekitFallbackUrl = data["livekit_fallback_url"]
                val token = data["token"] ?: return@runBlocking

                val event = WebSocketEvent.CallIncoming(
                    callId = callId,
                    fromUserId = fromUserId,
                    isVideo = isVideo,
                    roomName = roomName,
                    livekitUrl = livekitUrl,
                    livekitFallbackUrl = livekitFallbackUrl,
                    token = token
                )
                callManager.onIncoming(event)
            } else {
                if (AppNotificationManager.isAppInForeground) {
                    // Suppress push notification when the app is actively running in the foreground
                    return@runBlocking
                }
                val chatUserId = data["chat_user_id"]?.toLongOrNull() ?: return@runBlocking
                val msgServerId = data["msg_id"]?.toLongOrNull() ?: 0L

                val text = runCatching { messageRepository.resolvePushMessage(msgServerId) }
                    .onFailure { Log.e("PenikFCM", "resolve direct push failed", it) }
                    .getOrNull()
                    ?: if (msgServerId > 0L) "[Сообщение не расшифровано]" else fallbackText

                appNotificationManager.showDirectMessageNotification(
                    chatUserId = chatUserId,
                    rawText = text,
                    timestamp = timestamp,
                    msgServerId = msgServerId,
                    overrideSenderName = data["sender_name"]
                )
            }
        }
    }

    /**
     * Resolves one group message by its row id.
     *
     * There is no by-id group endpoint; the history page is already scoped to this
     * device and ordered by id, so asking for a single row before `rowId + 1`
     * returns exactly the message the push referred to. GroupRepository then
     * decrypts, persists and acknowledges it on the usual path.
     */
    private suspend fun resolveGroupText(groupId: Long, rowId: Long): String? {
        if (rowId <= 0L) return null
        return runCatching {
            val page = apiService.getGroupHistory(groupId, 1, rowId + 1)
                .takeIf { it.isSuccessful }?.body() ?: return null
            val m = page.messages.firstOrNull { it.id == rowId } ?: return null
            groupRepository.handleIncoming(
                groupId = groupId,
                id = m.id,
                messageId = m.messageId,
                senderUserId = m.senderUserId,
                senderDeviceId = m.senderDeviceId,
                keyVersion = m.keyVersion,
                ciphertext = android.util.Base64.decode(m.ciphertext, GROUP_B64_FLAGS),
                salt = android.util.Base64.decode(m.salt, GROUP_B64_FLAGS),
                nonce = android.util.Base64.decode(m.nonce, GROUP_B64_FLAGS),
                createdAt = m.createdAt
            )?.text
        }.onFailure { Log.e("PenikFCM", "resolve group push failed", it) }.getOrNull()
    }

    private companion object {
        // Group envelopes travel url-safe, matching GroupRepository.
        const val GROUP_B64_FLAGS =
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
    }
}
