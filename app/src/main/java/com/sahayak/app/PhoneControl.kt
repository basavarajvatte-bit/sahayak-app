package com.sahayak.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sahayak ke haath: app kholna, tap, type, scroll.
 * Har kaam se pehle secret ya payment screen check hoti hai, aur wahan kaam ruk jata hai.
 */
class PhoneControl : AccessibilityService() {
    private var elements: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun run(name: String, args: JSONObject): JSONObject {
        val blocked = sensitiveReason()
        if (name != "get_screen" && blocked != null) {
            return JSONObject().put("status", "stopped").put("reason", blocked)
        }
        val before = screenText()
        val done = when (name) {
            "get_screen" -> true
            "wait" -> {
                Thread.sleep(args.optInt("seconds", 2).coerceIn(1, 10) * 1_000L)
                true
            }
            "open_url" -> openUrl(args.optString("url"))
            "tap_text" -> tapText(args.optString("text"))
            "open_app" -> openApp(args.optString("name"))
            "tap" -> tapElement(args.optInt("element", -1))
            "tap_position" -> tapPosition(args.optInt("x", -1), args.optInt("y", -1))
            "type_text" -> {
                val target = typeTarget(args.optInt("element", -1))
                if (target?.isPassword == true) {
                    return JSONObject().put("status", "password_field").put("screen", readScreen())
                }
                target != null && typeInto(target, args.optString("text"))
            }
            "press_enter" -> pressEnter()
            "scroll" -> scroll(args.optString("direction") != "up")
            "go_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> false
        }
        if (name != "get_screen") waitUntilSettled(before)
        val after = sensitiveReason()
        val result = JSONObject().put("status", if (done) "ok" else "failed")
            .put("app", rootInActiveWindow?.packageName?.toString().orEmpty())
            .put("screen", readScreen())
        if (after != null) result.put("status", "stopped").put("reason", after)
        else if (name == "type_text" && done) result.put("next", "Text is typed but not sent. Submit it now.")
        return result
    }

    private fun screenText(): String = visibleNodes().joinToString("|") { label(it) }

    /** Screen shaant hone tak ruko, taaki Sahayak adhuri screen na padhe. */
    private fun waitUntilSettled(before: String) {
        Thread.sleep(MIN_SETTLE_MS)
        var last = screenText()
        var stable = 0
        var waited = MIN_SETTLE_MS
        while (waited < MAX_SETTLE_MS) {
            Thread.sleep(SETTLE_STEP_MS)
            waited += SETTLE_STEP_MS
            val now = screenText()
            stable = if (now == last) stable + 1 else 0
            last = now
            if (stable >= 2 && (now != before || waited >= LOAD_GRACE_MS)) return
        }
    }

    private fun openUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun tapText(text: String): Boolean {
        val wanted = text.trim().lowercase()
        if (wanted.isEmpty()) return false
        val nodes = visibleNodes()
        val node = nodes.firstOrNull { label(it).lowercase() == wanted }
            ?: nodes.firstOrNull { label(it).lowercase().contains(wanted) }
            ?: return false
        return tapNode(node)
    }

    private fun openApp(name: String): Boolean {
        val wanted = name.lowercase().filter { it.isLetterOrDigit() }
        if (wanted.isEmpty()) return false
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcher, 0).firstOrNull {
            val label = it.loadLabel(packageManager).toString().lowercase().filter { c -> c.isLetterOrDigit() }
            label.contains(wanted) || wanted.contains(label)
        } ?: return false
        val intent = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun tapElement(index: Int): Boolean {
        val node = elements.getOrNull(index) ?: return false
        return tapNode(node)
    }

    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val box = Rect()
        node.getBoundsInScreen(box)
        return gesture(box.exactCenterX(), box.exactCenterY(), box.exactCenterX(), box.exactCenterY(), 60)
    }

    private fun tapPosition(x: Int, y: Int): Boolean {
        if (x !in 0..1000 || y !in 0..1000) return false
        val metrics = resources.displayMetrics
        val px = metrics.widthPixels * x / 1000f
        val py = metrics.heightPixels * y / 1000f
        return gesture(px, py, px, py, 60)
    }

    private fun typeTarget(index: Int): AccessibilityNodeInfo? {
        val chosen = elements.getOrNull(index)
        chosen?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        chosen?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return if (chosen?.isEditable == true) chosen
        else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        Thread.sleep(TYPE_CHECK_MS)
        node.refresh()
        if (node.text?.toString()?.contains(text) == true) return true
        // Kuch websites seedha text nahi letin, wahan paste karke dekho
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Sahayak", text))
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Thread.sleep(TYPE_CHECK_MS)
        node.refresh()
        return node.text?.toString()?.contains(text) == true
    }

    private fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    private fun scroll(down: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val bottom = metrics.heightPixels * 0.75f
        val top = metrics.heightPixels * 0.25f
        return if (down) gesture(x, bottom, x, top, 300) else gesture(x, top, x, bottom, 300)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        val latch = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null
        )
        if (!sent) return false
        latch.await(GESTURE_WAIT_MS, TimeUnit.MILLISECONDS)
        return ok
    }

    private fun visibleNodes(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_SCAN) {
            val node = queue.removeFirst()
            seen++
            if (node.isVisibleToUser) out.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun label(node: AccessibilityNodeInfo): String =
        (node.text ?: node.contentDescription ?: node.hintText ?: "").toString().trim()

    private fun readScreen(): JSONArray {
        val list = visibleNodes().filter {
            label(it).isNotEmpty() || it.isClickable || it.isEditable
        }.take(MAX_ELEMENTS)
        elements = list
        val items = JSONArray()
        val box = Rect()
        val metrics = resources.displayMetrics
        list.forEachIndexed { index, node ->
            node.getBoundsInScreen(box)
            val item = JSONObject().put("element", index)
                .put("x", box.centerX() * 1000 / metrics.widthPixels)
                .put("y", box.centerY() * 1000 / metrics.heightPixels)
            if (!node.isPassword) item.put("text", label(node).take(60))
            if (node.isEditable) item.put("input", true)
            if (node.isPassword) item.put("password", true)
            items.put(item)
        }
        return items
    }

    private fun sensitiveReason(): String? {
        val words = visibleNodes().joinToString(" ") { label(it) }.lowercase()
        return if (STOP_WORDS.any { it in words }) "payment_or_secret_screen" else null
    }

    companion object {
        @Volatile var instance: PhoneControl? = null
            private set

        private const val MIN_SETTLE_MS = 300L
        private const val TYPE_CHECK_MS = 250L
        private const val MAX_SETTLE_MS = 4_000L
        private const val SETTLE_STEP_MS = 200L
        private const val LOAD_GRACE_MS = 1_200L
        private const val GESTURE_WAIT_MS = 3_000L
        private const val MAX_SCAN = 1_500
        private const val MAX_ELEMENTS = 80
        private val STOP_WORDS = listOf(
            "pay now", "pay ₹", "pay rs", "proceed to pay", "continue to pay", "make payment",
            "payment method", "payment options", "select payment", "upi id", "upi pin",
            "net banking", "netbanking", "debit card", "credit card", "cvv", "enter otp",
            "one time password", "enter pin"
        )
    }
}
