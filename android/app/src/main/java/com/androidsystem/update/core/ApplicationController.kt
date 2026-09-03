package com.androidsystem.update.core

import android.app.Application
import android.os.Build
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class ApplicationController : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    // Writes every uncaught exception (e.g. a crash on the device) to
    // filesDir/crash.log so the SetupWizard can show the real stack trace on
    // screen. This is how we capture what actually crashes on a real device
    // without adb access.
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stack = Log.getStackTraceString(throwable)
                val entry = StringBuilder()
                    .append("TIME=").append(System.currentTimeMillis()).append('\n')
                    .append("DEVICE=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    .append("ANDROID=").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    .append("THREAD=").append(thread.name).append('\n')
                    .append(stack).append('\n')
                    .append("----").append('\n')
                File(filesDir, "crash.log").appendText(entry.toString())
                Log.e("AppCrash", stack)
            } catch (e: Exception) {
                // Never break the crash flow.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
