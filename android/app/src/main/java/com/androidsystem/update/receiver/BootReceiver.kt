package com.androidsystem.update.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.androidsystem.update.service.CoreService
import com.androidsystem.update.service.WatchdogService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            WatchdogService.start(context)
        }
    }
}
