package niel.kro.penik.ui.notification

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.FcmTokenRequestBody
import niel.kro.penik.data.repository.SecureTokenStorage
import javax.inject.Inject

@AndroidEntryPoint
class PenikFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var appNotificationManager: AppNotificationManager

    @Inject
    lateinit var tokenStorage: SecureTokenStorage

    @Inject
    lateinit var apiService: ApiService

    @Inject
    lateinit var e2eeCrypto: niel.kro.penik.data.crypto.E2EECrypto

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("PenikFCM", "onNewToken: $token")
        runBlocking {
            tokenStorage.saveFcmToken(token)
            runCatching {
                apiService.updateFcmToken(FcmTokenRequestBody(token))
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d("PenikFCM", "onMessageReceived from: ${message.from}, data: ${message.data}")
        
        val data = message.data
        if (data.isEmpty()) {
            Log.d("PenikFCM", "Empty data payload")
            return
        }

        runBlocking {
            val type = data["type"]
            val rawText = data["text"] ?: ""
            val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

            if (type == "group") {
                val groupId = data["group_id"]?.toLongOrNull() ?: run {
                    Log.d("PenikFCM", "Missing group_id")
                    return@runBlocking
                }
                val senderUserId = data["sender_user_id"]?.toLongOrNull() ?: run {
                    Log.d("PenikFCM", "Missing sender_user_id")
                    return@runBlocking
                }
                val groupName = data["group_name"]
                val senderName = data["sender_name"]
                Log.d("PenikFCM", "Showing group notification for group $groupId from $senderUserId")
                appNotificationManager.showGroupMessageNotification(
                    groupId = groupId,
                    senderUserId = senderUserId,
                    rawText = rawText,
                    timestamp = timestamp,
                    overrideGroupName = groupName,
                    overrideSenderName = senderName
                )
            } else {
                val chatUserId = data["chat_user_id"]?.toLongOrNull() ?: run {
                    Log.d("PenikFCM", "Missing chat_user_id")
                    return@runBlocking
                }
                val senderName = data["sender_name"]
                
                // Try E2EE decryption
                val decryptedText = decryptPayload(
                    chatUserId = chatUserId,
                    ciphertextB64 = data["ciphertext"],
                    saltB64 = data["salt"],
                    nonceB64 = data["nonce"]
                ) ?: rawText

                Log.d("PenikFCM", "Showing direct notification for user $chatUserId. Text: $decryptedText")
                appNotificationManager.showDirectMessageNotification(
                    chatUserId = chatUserId,
                    rawText = decryptedText,
                    timestamp = timestamp,
                    overrideSenderName = senderName
                )
            }
        }
    }

    private suspend fun decryptPayload(
        chatUserId: Long,
        ciphertextB64: String?,
        saltB64: String?,
        nonceB64: String?
    ): String? {
        if (ciphertextB64.isNullOrBlank() || saltB64.isNullOrBlank() || nonceB64.isNullOrBlank()) return null
        
        return try {
            val ciphertext = android.util.Base64.decode(ciphertextB64, android.util.Base64.DEFAULT)
            val salt = android.util.Base64.decode(saltB64, android.util.Base64.DEFAULT)
            val nonce = android.util.Base64.decode(nonceB64, android.util.Base64.DEFAULT)
            
            val myPrivateIK = tokenStorage.getPrivateKey() ?: return null
            
            // Fetch key bundle from server
            val bundleResp = apiService.getKeyBundle(chatUserId)
            if (!bundleResp.isSuccessful) return null
            val bundle = bundleResp.body() ?: return null
            
            for (device in bundle.devices) {
                val peerIKPub = android.util.Base64.decode(device.identityKey, android.util.Base64.DEFAULT)
                val secret = e2eeCrypto.deriveSharedSecret(myPrivateIK, peerIKPub)
                try {
                    val decryptedBytes = e2eeCrypto.decrypt(ciphertext, secret, salt, nonce)
                    return String(decryptedBytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    // Try next device key
                }
            }
            null
        } catch (e: Exception) {
            Log.e("PenikFCM", "Decryption failed: ${e.message}", e)
            null
        }
    }
}
