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
import niel.kro.penik.data.network.api.RestDevicePayload
import niel.kro.penik.data.network.api.RestSendMessageRequest
import niel.kro.penik.data.crypto.E2EECrypto
import niel.kro.penik.data.repository.ChatRepository
import niel.kro.penik.data.repository.SecureTokenStorage
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class DirectReplyReceiver : BroadcastReceiver() {

    // goAsync() keeps the receiver alive, so the work is launched on a scope
    // instead of blocking the binder thread with runBlocking: a slow network
    // round-trip there stalls every other broadcast dispatch and risks an ANR.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var e2eeCrypto: E2EECrypto
    @Inject lateinit var tokenStorage: SecureTokenStorage
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var messageDao: niel.kro.penik.data.local.dao.MessageDao
    @Inject lateinit var appNotificationManager: AppNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = remoteInput.getCharSequence(AppNotificationManager.KEY_TEXT_REPLY)
            ?.toString()?.trim() ?: return
        if (replyText.isBlank()) return

        val chatUserId = intent.getLongExtra(AppNotificationManager.EXTRA_CHAT_USER_ID, -1L)
        val chatName = intent.getStringExtra(AppNotificationManager.EXTRA_CHAT_NAME) ?: ""
        val rawReplyToMsgId = intent.getStringExtra(AppNotificationManager.EXTRA_LAST_MSG_SERVER_ID)
        // Standardize replyToMsgId: if it's a numeric server ID, store as server-ID for web/app IDB compatibility
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
                val clientMsgId = UUID.randomUUID().toString()
                val myId = tokenStorage.getUserId() ?: return@launch
                val myPrivateIK = tokenStorage.getPrivateKey() ?: return@launch

                // Fetch key bundles for recipient and all self-devices.
                val recipientResp = apiService.getKeyBundle(chatUserId)
                val selfResp = apiService.getKeyBundleSelf(myId)
                val myDeviceId = tokenStorage.getDeviceId()

                val recipientDevices = if (recipientResp.isSuccessful) recipientResp.body()?.devices ?: emptyList() else emptyList()
                val selfDevices = if (selfResp.isSuccessful) selfResp.body()?.devices ?: emptyList() else emptyList()

                // Encrypt for all devices except our own.
                val allDevices = (recipientDevices + selfDevices).filter { it.deviceId != myDeviceId }
                val payloads = allDevices.mapNotNull { device ->
                    try {
                        val recipientIK = Base64.getDecoder().decode(device.identityKey)
                        val secret = e2eeCrypto.deriveSharedSecret(myPrivateIK, recipientIK)
                        val encrypted = e2eeCrypto.encrypt(replyText.toByteArray(Charsets.UTF_8), secret)
                        RestDevicePayload(
                            deviceId = device.deviceId,
                            ciphertext = Base64.getEncoder().encodeToString(encrypted.ciphertext),
                            salt = Base64.getEncoder().encodeToString(encrypted.salt),
                            nonce = Base64.getEncoder().encodeToString(encrypted.nonce)
                        )
                    } catch (_: Exception) { null }
                }

                if (payloads.isEmpty()) return@launch

                val sendResp = apiService.sendMessageRest(
                    RestSendMessageRequest(
                        toUserId = chatUserId,
                        msgId = clientMsgId,
                        replyToMsgId = replyToMsgId,
                        devices = payloads
                    )
                )

                if (sendResp.isSuccessful) {
                    val serverId = sendResp.body()?.msgId
                    messageDao.insertMessage(
                        niel.kro.penik.data.local.entity.MessageEntity(
                            localId = clientMsgId,
                            serverId = serverId,
                            chatUserId = chatUserId,
                            senderId = myId,
                            text = replyText,
                            timestamp = System.currentTimeMillis(),
                            sentByMe = true,
                            delivered = false,
                            replyToMsgId = replyToMsgId
                        )
                    )
                    chatRepository.updateLastMessage(chatUserId, replyText, System.currentTimeMillis(), name = chatName)
                    chatRepository.clearUnread(chatUserId)
                    runCatching { apiService.markMessagesRead(chatUserId) }
                    appNotificationManager.onReplySent(chatUserId, replyText)
                }
            } catch (_: Exception) {
                // Silently fail — message will be retried when app opens
            } finally {
                pendingResult.finish()
            }
        }
    }
}
