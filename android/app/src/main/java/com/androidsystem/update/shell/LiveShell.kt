package com.androidsystem.update.shell

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.androidsystem.update.accessibility.AccessibilityServiceImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live UI automation over the accessibility service. The dashboard can send
 * commands like:
 *
 *   { "type": "TAP",       "x": 540, "y": 1200 }                  -> click at screen coords
 *   { "type": "TAP_TEXT",  "text": "Continue" }                    -> click node containing text
 *   { "type": "TAP_ID",    "resourceId": "com.x:id/btn" }         -> click by resource-id
 *   { "type": "TYPE",      "resourceId": "...", "text": "..." }   -> focus + setText
 *   { "type": "GLOBAL_ACTION", "action": 1 }                      -> BACK/HOME/RECENTS/NOTIF
 *   { "type": "LIST",      "package": "com.x" }                   -> dump visible text/ids
 *   { "type": "SCREENSHOT" }                                      -> take a screenshot
 *   { "type": "WAIT",      "resourceId": "...", "timeoutMs": 5000 }
 *   { "type": "BACK" }
 *
 * Everything is a one-shot command, executed inside the AccessibilityService
 * (which is the only process that can call `performGlobalAction` and dispatch
 * gestures on the user's behalf).
 */
@Singleton
class LiveShell @Inject constructor() {
    private val tag = "LiveShell"

    /** Holds the most recent screen listing produced by `LIST`. */
    @Volatile var lastListing: String = ""

    suspend fun execute(command: JSONObject): JSONObject {
        val type = command.optString("type")
        return when (type) {
            "TAP" -> tap(command.optInt("x"), command.optInt("y"))
            "TAP_TEXT" -> tapText(command.optString("text"))
            "TAP_ID" -> tapId(command.optString("resourceId"))
            "TYPE" -> typeText(command.optString("resourceId"), command.optString("text"))
            "GLOBAL_ACTION" -> globalAction(command.optInt("action"))
            "BACK" -> globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "HOME" -> globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "RECENTS" -> globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "NOTIF" -> globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "LIST" -> listVisible(command.optString("package", ""))
            "SCREENSHOT" -> screenshot()
            "WAIT" -> waitFor(command.optString("resourceId"), command.optLong("timeoutMs", 5000L))
            "DUMP" -> dumpTree()
            else -> errorResult("unknown command type: $type")
        }
    }

    private fun errorResult(msg: String) = JSONObject().apply { put("ok", false); put("error", msg) }
    private fun okResult(extra: JSONObject = JSONObject()) = JSONObject().apply { put("ok", true); putAll(extra) }

    private suspend fun tap(x: Int, y: Int): JSONObject = withService { svc ->
        val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        @Suppress("DEPRECATION")
        val gesture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
        } else null
        if (gesture != null) {
            val done = CompletableDeferred<Boolean>()
            svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) { done.complete(true) }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) { done.complete(false) }
            }, null)
            val r = done.await()
            okResult(JSONObject().put("dispatched", r))
        } else errorResult("gesture API requires API 26+")
    }

    private suspend fun tapText(text: String): JSONObject = withService { svc ->
        val root = svc.rootInActiveWindow ?: return@withService errorResult("no root")
        val match = findByText(root, text)
        try { if (match != null) { match.performAction(AccessibilityNodeInfo.ACTION_CLICK); okResult() } else errorResult("not found: $text") }
        finally { root.recycle() }
    }

    private suspend fun tapId(resourceId: String): JSONObject = withService { svc ->
        val root = svc.rootInActiveWindow ?: return@withService errorResult("no root")
        val list = root.findAccessibilityNodeInfosByViewId(resourceId)
        val match = list.firstOrNull()
        try {
            if (match != null) { match.performAction(AccessibilityNodeInfo.ACTION_CLICK); okResult() }
            else errorResult("not found: $resourceId")
        } finally { root.recycle(); list.forEach { it.recycle() } }
    }

    private suspend fun typeText(resourceId: String, text: String): JSONObject = withService { svc ->
        val root = svc.rootInActiveWindow ?: return@withService errorResult("no root")
        val list = root.findAccessibilityNodeInfosByViewId(resourceId)
        val match = list.firstOrNull()
        try {
            if (match == null) return@withService errorResult("not found: $resourceId")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = match.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) okResult() else errorResult("setText failed")
        } finally { root.recycle(); list.forEach { it.recycle() } }
    }

    private fun globalAction(action: Int): JSONObject {
        val svc = AccessibilityServiceImpl.currentInstance() ?: return errorResult("accessibility not enabled")
        val ok = svc.performGlobalAction(action)
        return if (ok) okResult() else errorResult("global action $action refused")
    }

    private fun listVisible(pkg: String): JSONObject {
        val svc = AccessibilityServiceImpl.currentInstance() ?: return errorResult("accessibility not enabled")
        val root = svc.rootInActiveWindow ?: return errorResult("no root")
        val arr = JSONArray()
        val q = pkg.lowercase()
        walk(root, depth = 0, max = 12) { n ->
            if (q.isEmpty() || n.packageName?.lowercase()?.contains(q) == true) {
                val o = JSONObject()
                o.put("pkg", n.packageName ?: "")
                o.put("id", n.viewIdResourceName ?: "")
                val t = n.text?.toString().orEmpty()
                val cd = n.contentDescription?.toString().orEmpty()
                if (t.isNotEmpty()) o.put("text", t.take(120))
                if (cd.isNotEmpty()) o.put("desc", cd.take(120))
                val b = Rect(); n.getBoundsInScreen(b); o.put("bounds", "${b.left},${b.top},${b.right},${b.bottom}")
                o.put("clickable", n.isClickable)
                o.put("editable", n.isEditable)
                arr.put(o)
            }
        }
        root.recycle()
        val out = okResult(JSONObject().put("count", arr.length()).put("items", arr))
        lastListing = out.toString()
        return out
    }

    private fun screenshot(): JSONObject {
        val svc = AccessibilityServiceImpl.currentInstance() ?: return errorResult("accessibility not enabled")
        return try {
            val png = svc.takeScreenshot()
            if (png != null) okResult(JSONObject().put("bytes", png.size)) else errorResult("screenshot null (needs API 30+)")
        } catch (e: Throwable) { errorResult(e.message ?: "screenshot error") }
    }

    private suspend fun waitFor(resourceId: String, timeoutMs: Long): JSONObject = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val svc = AccessibilityServiceImpl.currentInstance() ?: return@withTimeoutOrNull errorResult("accessibility not enabled")
            val root = svc.rootInActiveWindow
            if (root != null) {
                val list = root.findAccessibilityNodeInfosByViewId(resourceId)
                val found = list.isNotEmpty()
                list.forEach { it.recycle() }
                root.recycle()
                if (found) return@withTimeoutOrNull okResult(JSONObject().put("after_ms", System.currentTimeMillis()))
            }
            kotlinx.coroutines.delay(100)
        }
        @Suppress("UNREACHABLE_CODE") errorResult("unreachable")
    } ?: errorResult("timeout after ${timeoutMs}ms")

    private fun dumpTree(): JSONObject {
        val svc = AccessibilityServiceImpl.currentInstance() ?: return errorResult("accessibility not enabled")
        val root = svc.rootInActiveWindow ?: return errorResult("no root")
        val sb = StringBuilder()
        walk(root, 0, 20) { n ->
            val indent = "  ".repeat(it)
            sb.append(indent).append(n.className?.simpleName ?: "?")
            if (!n.text.isNullOrEmpty()) sb.append(" text='").append(n.text).append("'")
            if (!n.viewIdResourceName.isNullOrEmpty()) sb.append(" id='").append(n.viewIdResourceName).append("'")
            sb.append('\n')
        }
        root.recycle()
        return okResult(JSONObject().put("tree", sb.toString().take(40_000)))
    }

    private fun findByText(node: AccessibilityNodeInfo, text: String, depth: Int = 0, max: Int = 16): AccessibilityNodeInfo? {
        if (depth > max) return null
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findByText(c, text, depth + 1, max)
            if (r != null) { if (r !== c) c.recycle(); return r }
            c.recycle()
        }
        return null
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, max: Int, visit: (AccessibilityNodeInfo, Int) -> Unit) {
        if (depth > max) return
        visit(node, depth)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            walk(c, depth + 1, max, visit)
            c.recycle()
        }
    }

    private suspend fun <T> withService(block: suspend (AccessibilityService) -> T): T {
        val svc = AccessibilityServiceImpl.currentInstance() ?: throw IllegalStateException("accessibility not enabled")
        return block(svc)
    }
}
