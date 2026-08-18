package niel.kro.penik.ui.notification

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var e2eeCrypto: E2EECrypto
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
        val replyToMsgId = intent.getStringExtra(AppNotificationManager.EXTRA_LAST_MSG_SERVER_ID)
        if (chatUserId <= 0) return

        val pendingResult = goAsync()
        runBlocking(Dispatchers.IO) {
            try {
                val clientMsgId = UUID.randomUUID().toString()
                val myId = tokenStorage.getUserId() ?: return@runBlocking
                val myPrivateIK = tokenStorage.getPrivateKey() ?: return@runBlocking

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

                if (payloads.isEmpty()) return@runBlocking

                val sendResp = apiService.sendMessageRest(
                    RestSendMessageRequest(
                        toUserId = chatUserId,
                        msgId = clientMsgId,
                        replyToMsgId = replyToMsgId,
                        devices = payloads
                    )
                )

                if (sendResp.isSuccessful) {
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
