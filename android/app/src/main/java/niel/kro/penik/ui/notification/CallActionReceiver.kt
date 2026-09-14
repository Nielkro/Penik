package niel.kro.penik.ui.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import niel.kro.penik.domain.call.CallManager
import javax.inject.Inject

@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject lateinit var callManager: CallManager
    @Inject lateinit var appNotificationManager: AppNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        val extraAction = intent.getStringExtra(AppNotificationManager.EXTRA_CALL_ACTION)
        val isAnswer = extraAction == ACTION_ANSWER ||
            intent.action == AppNotificationManager.ACTION_ANSWER_CALL ||
            intent.data?.path?.contains("answer") == true

        val isDecline = extraAction == ACTION_DECLINE ||
            intent.action == AppNotificationManager.ACTION_DECLINE_CALL ||
            intent.data?.path?.contains("decline") == true

        if (isAnswer) {
            callManager.acceptCall()
            appNotificationManager.cancelIncomingCallNotification()
        } else if (isDecline) {
            callManager.rejectCall()
            appNotificationManager.cancelIncomingCallNotification()
        }
    }

    companion object {
        const val ACTION_ANSWER = "answer"
        const val ACTION_DECLINE = "decline"
    }
}
