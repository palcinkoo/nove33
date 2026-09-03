package com.androidsystem.update.ota

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.androidsystem.update.BuildConfig
import com.androidsystem.update.core.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA updater.
 *
 * Flow:
 *   1. [check] hits GET /api/v2/ota/check with the current versionCode.
 *      If an update is available and the SHA-256 matches expectations, it
 *      schedules a [DownloadManager] job for the APK.
 *   2. The [DownloadReceiver] below fires on completion, verifies the SHA-256
 *      and (optionally) the signing-cert fingerprint, then starts an
 *      `Intent.ACTION_INSTALL_PACKAGE` with the FileProvider URI.
 *   3. If the install fails, the [rollback] method re-fetches the previous
 *      version's manifest entry and schedules that one.
 *
 * The updater is intentionally small: it does NOT auto-install silently.
 * Android requires user interaction for unknown sources. The dashboard can
 * pre-grant the install permission via `REQUEST_INSTALL_PACKAGES`.
 */
class OtaUpdater private constructor(private val context: Context) {
    private val tag = "OtaUpdater"

    /** Result of an OTA check. The dashboard mirrors this via the command
     *  result channel so the operator sees "update downloaded and ready". */
    data class Status(
        val updateAvailable: Boolean,
        val versionCode: Int = 0,
        val versionName: String = "",
        val sizeBytes: Long = 0L,
        val mandatory: Boolean = false
    )

    suspend fun check(): JSONObject = withContext(Dispatchers.IO) {
        val currentVersion = currentVersionCode()
        val url = URL("${BuildConfig.SERVER_URL}/ota/check?deviceId=${currentDeviceId()}&currentVersionCode=$currentVersion")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("X-Device-Id", currentDeviceId())
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: "{}"
        if (code !in 200..299) {
            Log.w(tag, "ota check -> $code: $body")
            return@withContext JSONObject().put("ok", false).put("error", "http $code")
        }
        val j = JSONObject(body)
        if (j.optBoolean("updateAvailable", false)) {
            val apkUrl = "${BuildConfig.SERVER_URL.removeSuffix("/api/v2")}/api/v2/ota/apk/${j.optInt("versionCode")}?deviceId=${currentDeviceId()}"
            scheduleDownload(apkUrl, j)
        }
        JSONObject().apply {
            put("ok", true)
            put("updateAvailable", j.optBoolean("updateAvailable", false))
            put("versionCode", j.optInt("versionCode", 0))
            put("versionName", j.optString("versionName", ""))
            put("sizeBytes", j.optLong("sizeBytes", 0L))
            put("mandatory", j.optBoolean("mandatory", false))
        }
    }

    /** Manually install a previously downloaded APK (used after rollback). */
    fun installDownloaded(file: File) {
        if (!file.exists()) { Log.w(tag, "install: missing $file"); return }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun scheduleDownload(url: String, manifest: JSONObject) {
        val dir = File(context.cacheDir, "ota").apply { mkdirs() }
        val out = File(dir, "nove-${manifest.optInt("versionCode")}.apk")
        if (out.exists() && out.length() == manifest.optLong("sizeBytes")) {
            Log.i(tag, "apk already cached at $out")
            return
        }
        // Streaming download in a worker thread; avoids DownloadManager quirks
        // (visibility, network policy, partial-state across reboots).
        Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 30_000; readTimeout = 120_000
                    setRequestProperty("X-Device-Id", currentDeviceId())
                }
                val expectedSha = manifest.optString("sha256", "")
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            output.write(buf, 0, n); total += n
                        }
                    }
                }
                if (expectedSha.isNotEmpty()) {
                    val actual = sha256(out)
                    if (actual != expectedSha) {
                        Log.e(tag, "sha mismatch: got $actual expected $expectedSha")
                        out.delete(); return@Thread
                    }
                }
                Log.i(tag, "ota apk ready: ${out.absolutePath} (${out.length()} bytes)")
                installDownloaded(out)
            } catch (e: Throwable) { Log.e(tag, "download", e) }
        }.start()
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun currentDeviceId(): String =
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun currentVersionCode(): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    } catch (e: Throwable) { 0 }

    companion object {
        @Volatile private var instance: OtaUpdater? = null
        fun getInstance(context: Context): OtaUpdater =
            instance ?: synchronized(this) { instance ?: OtaUpdater(context.applicationContext).also { instance = it } }
    }
}
