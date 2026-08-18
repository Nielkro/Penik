package niel.kro.penik.ui.notification

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("PenikFCM", "onNewToken: $token")
        scope.launch {
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

        scope.launch {
            val type = data["type"]
            val text = data["text"] ?: ""
            val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

            if (type == "group") {
                val groupId = data["group_id"]?.toLongOrNull() ?: run {
                    Log.d("PenikFCM", "Missing group_id")
                    return@launch
                }
                val senderUserId = data["sender_user_id"]?.toLongOrNull() ?: run {
                    Log.d("PenikFCM", "Missing sender_user_id")
                    return@launch
                }
                val groupName = data["group_name"]
                val senderName = data["sender_name"]
                Log.d("PenikFCM", "Showing group notification for group $groupId from $senderUserId")
                appNotificationManager.showGroupMessageNotification(
                    groupId = groupId,
                    senderUserId = senderUserId,
                    rawText = text,
                    timestamp = timestamp,
                    overrideGroupName = groupName,
                    overrideSenderName = senderName
                )
            } else {
                val chatUserId = data["chat_user_id"]?.toLongOrNull() ?: run {
                    Log.d("PenikFCM", "Missing chat_user_id")
                    return@launch
                }
                val senderName = data["sender_name"]
                Log.d("PenikFCM", "Showing direct notification for user $chatUserId")
                appNotificationManager.showDirectMessageNotification(
                    chatUserId = chatUserId,
                    rawText = text,
                    timestamp = timestamp,
                    overrideSenderName = senderName
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
