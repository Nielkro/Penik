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
        when (intent.getStringExtra(AppNotificationManager.EXTRA_CALL_ACTION)) {
            ACTION_ANSWER -> callManager.acceptCall()
            ACTION_DECLINE -> {
                callManager.rejectCall()
                appNotificationManager.cancelIncomingCallNotification()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "answer"
        const val ACTION_DECLINE = "decline"
    }
}
