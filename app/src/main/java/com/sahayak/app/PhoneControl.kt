package com.sahayak.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.ContactsContract
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private var teachName = ""
    private var teachSteps: ArrayList<String>? = null
    private var lastApp = ""
    private var teachBar: Button? = null

    /** Sikhana shuru: ab aapke tap aur type steps ban jate hain. */
    fun startTeaching(name: String) {
        stopBar()
        teachName = name
        teachSteps = ArrayList()
        lastApp = ""
        val manager = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        val bar = Button(this).apply {
            text = "Finish teaching"
            isAllCaps = false
            setOnClickListener { finishTeaching() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, (52 * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = (56 * density).toInt()
        }
        manager.addView(bar, params)
        teachBar = bar
    }

    private fun stopBar() {
        teachBar?.let { getSystemService(WindowManager::class.java).removeView(it) }
        teachBar = null
    }

    private fun finishTeaching() {
        val steps = teachSteps
        teachSteps = null
        stopBar()
        if (steps.isNullOrEmpty()) {
            Toast.makeText(this, "No steps were recorded", Toast.LENGTH_LONG).show()
            return
        }
        Skills.addDemo(this, teachName, steps)
        Toast.makeText(this, "Saved: $teachName", Toast.LENGTH_SHORT).show()
    }

    private fun addStep(step: String) {
        val steps = teachSteps ?: return
        if (steps.size < MAX_TEACH_STEPS && steps.lastOrNull() != step) steps.add(step)
    }

    private fun recordStep(event: AccessibilityEvent) {
        val steps = teachSteps ?: return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName || pkg == "com.android.systemui" || isFinance(pkg)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (pkg != lastApp && "launcher" !in pkg) {
                lastApp = pkg
                val app = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    pkg
                }
                addStep("open app $app")
            } else if (pkg == lastApp) {
                val shown = visibleNodes().asSequence().map { label(it) }.filter { it.isNotEmpty() }
                    .take(4).joinToString(", ") { it.take(30) }
                if (shown.isNotEmpty()) addStep("screen shows: $shown")
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text.joinToString(" ").ifBlank { event.contentDescription?.toString().orEmpty() }.trim()
                if (text.isNotEmpty()) {
                    addStep("tap \"" + text.take(60) + "\"")
                } else {
                    val box = Rect()
                    event.source?.getBoundsInScreen(box)
                    val metrics = resources.displayMetrics
                    addStep("tap the icon at x=" + box.centerX() * 1000 / metrics.widthPixels +
                        " y=" + box.centerY() * 1000 / metrics.heightPixels)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (!event.isPassword) {
                val text = event.text.joinToString(" ").take(200)
                if (steps.lastOrNull()?.startsWith("type ") == true) steps.removeAt(steps.size - 1)
                if (text.isNotEmpty()) addStep("type \"$text\"")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) recordStep(event)
    }

    override fun onInterrupt() = Unit

    fun run(name: String, args: JSONObject): JSONObject {
        val current = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (isFinance(current)) {
            return JSONObject().put("status", "stopped").put("reason", "finance_app")
        }
        if (name == "open_whatsapp_chat") return openWhatsApp(args)
        val blocked = sensitiveReason()
        if (name != "get_screen" && blocked != null) {
            return JSONObject().put("status", "stopped").put("reason", blocked)
        }
        val before = screenText()
        val done = when (name) {
            "get_screen" -> true
            "read_full_text" -> {
                val words = visibleNodes().filter { !it.isPassword }.map { label(it) }
                    .filter { it.isNotEmpty() }.joinToString("\n").take(MAX_READ)
                return JSONObject().put("status", "ok").put("text", words)
            }
            "wait" -> {
                Thread.sleep(args.optInt("seconds", 2).coerceIn(1, 10) * 1_000L)
                true
            }
            "open_url" -> openUrl(args.optString("url"))
            "tap_text" -> tapText(args.optString("text"))
            "open_app" -> {
                if (financeMatch(args.optString("name"))) {
                    return JSONObject().put("status", "stopped").put("reason", "finance_app")
                }
                openApp(args.optString("name"))
            }
            "compose_email" -> composeEmail(args)
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
            "quick_action" -> quickAction(args.optString("action"), args.optString("value"))
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
        else if (name == "compose_email" && done) result.put("next", "Check the fields, then tap the send button now.")
        return result
    }

    /** AI ke bina seedha kaam. true matlab kaam ho gaya, false matlab AI ko do. */
    fun fast(command: LocalCommands.Command): Boolean {
        val current = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (isFinance(current)) return false
        return when (command) {
            is LocalCommands.Command.Open -> !financeMatch(command.app) && openApp(command.app)
            is LocalCommands.Command.Quick -> quickAction(command.action, command.value, command.extra)
            is LocalCommands.Command.WhatsApp -> {
                val args = JSONObject().put("contact", command.contact).put("message", command.message)
                val opened = openWhatsApp(args)
                opened.optString("status") == "ok" && tapWhatsAppSend(opened.optString("contact"))
            }
        }
    }

    /** Tay samay wala kaam, bina AI ke. */
    fun runWork(item: JSONObject): Boolean {
        if (isFinance(rootInActiveWindow?.packageName?.toString().orEmpty())) performGlobalAction(GLOBAL_ACTION_HOME)
        return when (item.optString("kind")) {
            "whatsapp" -> {
                val opened = openWhatsApp(JSONObject().put("contact", item.optString("to")).put("message", item.optString("message")))
                opened.optString("status") == "ok" &&
                    (item.optString("to").none { it.isLetter() } || contactShown(opened.optString("contact"))) &&
                    pressWhatsAppSend()
            }
            "email" -> {
                val args = JSONObject().put("to", item.optString("to")).put("subject", item.optString("subject"))
                    .put("body", item.optString("message"))
                composeEmail(args) && run {
                    waitUntilSettled("")
                    sendNode(listOf("com.google.android.gm:id/send"))?.let { tapNode(it) } == true
                }
            }
            else -> false
        }
    }

    private fun tapWhatsAppSend(contact: String): Boolean =
        contactShown(contact) && pressWhatsAppSend()

    private fun contactShown(contact: String): Boolean = visibleNodes().any {
        val text = label(it)
        text.isNotEmpty() && (text.contains(contact, ignoreCase = true) || contact.contains(text, ignoreCase = true))
    }

    private fun sendNode(ids: List<String>): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        ids.forEach { id -> root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isVisibleToUser }?.let { return it } }
        return visibleNodes().firstOrNull { node ->
            (node.contentDescription?.toString()?.trim()?.lowercase() ?: "") in SEND_WORDS
        }
    }

    private fun whatsAppBox(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")?.firstOrNull()

    /** Send dhoondhkar dabao, phir check karo ki likhne ka box khali hua. */
    private fun pressWhatsAppSend(): Boolean {
        repeat(SEND_TRIES) {
            val box = whatsAppBox()
            val send = sendNode(WHATSAPP_SEND_IDS)
            val tapped = when {
                send != null -> tapNode(send)
                box != null -> {
                    // Button na mile to box ke theek daayin taraf dabao
                    val rect = Rect()
                    box.getBoundsInScreen(rect)
                    val x = resources.displayMetrics.widthPixels - 40 * resources.displayMetrics.density
                    gesture(x, rect.exactCenterY(), x, rect.exactCenterY(), 60)
                }
                else -> false
            }
            if (tapped) {
                Thread.sleep(SEND_CHECK_MS)
                val left = whatsAppBox()
                left?.refresh()
                val text = left?.text?.toString().orEmpty()
                if (left != null && (text.isEmpty() || text == left.hintText?.toString())) return true
            }
            Thread.sleep(SEND_CHECK_MS)
        }
        return false
    }

    @Volatile var abort = false

    data class Replay(val done: Boolean, val failedAt: Int, val reason: String)

    /** Sikhaye steps seedhe chalata hai. Ruke to batata hai kis step par. */
    fun replay(steps: List<String>): Replay {
        abort = false
        steps.forEachIndexed { index, step ->
            if (abort) return Replay(false, index, "stopped")
            val current = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (isFinance(current)) return Replay(false, index, "finance_app")
            val reason = sensitiveReason()
            if (reason != null) return Replay(false, index, reason)
            if (!replayStep(step)) return Replay(false, index, "step_failed")
        }
        return Replay(true, -1, "")
    }

    private fun replayStep(step: String): Boolean {
        val before = screenText()
        val done = OPEN_STEP.find(step)?.let { openApp(it.groupValues[1]) }
            ?: QUOTED_TAP.find(step)?.let { match -> retry { tapText(match.groupValues[1]) } }
            ?: POINT_TAP.find(step)?.let { tapPosition(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            ?: QUOTED_TYPE.find(step)?.let { match -> retry { typeFocused(match.groupValues[1]) } }
            ?: when {
                step.startsWith("press enter") -> pressEnter()
                step.startsWith("wait") -> {
                    Thread.sleep(REPLAY_WAIT_MS)
                    true
                }
                step.startsWith("screen shows") -> true
                else -> false
            }
        if (done) waitUntilSettled(before)
        return done
    }

    private fun retry(action: () -> Boolean): Boolean {
        repeat(REPLAY_TRIES) {
            if (abort) return false
            if (action()) return true
            Thread.sleep(REPLAY_RETRY_MS)
        }
        return false
    }

    private fun typeFocused(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: visibleNodes().firstOrNull { it.isEditable } ?: return false
        return !node.isPassword && typeInto(node, text)
    }

    private fun launch(intent: Intent, pkg: String? = null): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) {
            try {
                startActivity(Intent(intent).setPackage(pkg))
                return true
            } catch (e: ActivityNotFoundException) {
                // app nahi hai to system apna rasta chune
            }
        }
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun torch(on: Boolean): Boolean {
        val camera = getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return false
        return try {
            camera.setTorchMode(id, on)
            true
        } catch (e: CameraAccessException) {
            false
        }
    }

    private fun mediaKey(code: Int): Boolean {
        val audio = getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return true
    }

    private fun numberFor(value: String): String {
        val typed = value.filter { it.isDigit() || it == '+' }
        var number = if (typed.length >= 3 && value.none { it.isLetter() }) typed else ""
        if (number.isEmpty() && checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(value))
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { row ->
                if (row.count == 1 && row.moveToFirst()) number = row.getString(0).orEmpty()
            }
        }
        return number
    }

    private fun dialNumber(value: String): Boolean {
        val number = numberFor(value)
        if (number.isEmpty()) return false
        val allowed = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        return launch(Intent(if (allowed) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
    }

    private fun smsTo(value: String): Boolean {
        val parts = value.split("|", limit = 2)
        val number = numberFor(parts.getOrElse(0) { "" })
        if (number.isEmpty()) return false
        val opened = launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", parts.getOrElse(1) { "" }))
        if (!opened) return false
        return retry { sendNode(emptyList())?.let { tapNode(it) } == true }
    }

    private fun ringer(mode: Int): Boolean = try {
        getSystemService(AudioManager::class.java).ringerMode = mode
        true
    } catch (e: SecurityException) {
        false
    }

    private fun web(url: String, pkg: String? = null) = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)), pkg)

    /** Jawab wale shortcuts: AI ke bina turant. */
    fun quickAnswer(action: String, value: String): String? = when (action) {
        "answer" -> value
        "storage" -> {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.availableBytes / 1_000_000_000L).toString() + " GB free"
        }
        "device" -> Build.MANUFACTURER + " " + Build.MODEL
        "android" -> "Android " + Build.VERSION.RELEASE
        "time" -> SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())
        "date" -> SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(Date())
        "battery" -> getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString() + "% battery"
        "calculate" -> {
            val parts = value.split("|")
            val a = parts[0].toDouble()
            val b = parts[2].toDouble()
            val result = when (parts[1]) {
                "plus", "+" -> a + b
                "minus", "-" -> a - b
                "divided by", "/" -> if (b == 0.0) null else a / b
                else -> a * b
            }
            result?.let { if (it == Math.rint(it)) it.toLong().toString() else String.format(Locale.ENGLISH, "%.2f", it) }
                ?: "Cannot divide by zero"
        }
        else -> null
    }

    /** Direct system shortcuts. Tap ki zaroorat nahi. */
    fun quickAction(action: String, value: String, extra: String = ""): Boolean = when (action) {
        "url" -> web(value, extra.substringBefore("|").ifEmpty { null })
        "open_settings" -> launch(Intent(value))
        "app_category" -> openApp(extra) || launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, value))
        "open_named" -> !financeMatch(value) && (openApp(value) ||
            (ShortcutBook.WEBSITES[value.lowercase()]?.let { web(it) } ?: false))
        "browser" -> web("https://www.google.com")
        "new_email" -> launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), GMAIL)
        "compose_email" -> {
            val parts = value.split("|", limit = 2)
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(parts[0])))
                .putExtra(Intent.EXTRA_SUBJECT, parts.getOrElse(1) { "" }), GMAIL)
        }
        "add_contact" -> launch(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI))
        "new_note" -> web("https://keep.google.com")
        "my_location" -> web("geo:0,0?q=my+location", "com.google.android.apps.maps")
        "show_alarms" -> launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        "show_timers" -> launch(Intent(AlarmClock.ACTION_SHOW_TIMERS))
        "dismiss_alarm" -> launch(Intent(AlarmClock.ACTION_DISMISS_ALARM))
        "snooze_alarm" -> launch(Intent(AlarmClock.ACTION_SNOOZE_ALARM))
        "copy" -> {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sahayak", value))
            true
        }
        "note" -> Memory.add(this, value)
        "reminder" -> {
            WorkList.add(this, "reminder", value.toLong(), extra.take(60), "", "", extra)
            true
        }
        "scroll_down" -> scroll(true)
        "scroll_up" -> scroll(false)
        "swipe_left", "swipe_right" -> {
            val metrics = resources.displayMetrics
            val y = metrics.heightPixels / 2f
            val right = metrics.widthPixels * 0.85f
            val left = metrics.widthPixels * 0.15f
            if (action == "swipe_left") gesture(right, y, left, y, 250) else gesture(left, y, right, y, 250)
        }
        "enter" -> pressEnter()
        "tap_words" -> sensitiveReason() == null && tapText(value)
        "type_words" -> sensitiveReason() == null && typeFocused(value)
        "last_app" -> {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            Thread.sleep(LAST_APP_MS)
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
        "split_screen" -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        "dismiss_shade" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else false
        "media_stop" -> mediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
        "media_forward" -> mediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        "media_rewind" -> mediaKey(KeyEvent.KEYCODE_MEDIA_REWIND)
        "youtube_search" -> web("https://www.youtube.com/results?search_query=" + Uri.encode(value), "com.google.android.youtube")
        "camera" -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        "video" -> launch(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))
        "sms" -> smsTo(value)
        "calendar" -> launch(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, value))
        "maps_search", "search_maps" -> web("geo:0,0?q=" + Uri.encode(value), "com.google.android.apps.maps")
        "search_play_store" -> web("market://search?q=" + Uri.encode(value), "com.android.vending")
        "search_amazon" -> web("https://www.amazon.in/s?k=" + Uri.encode(value), "in.amazon.mShop.android.shopping")
        "search_flipkart" -> web("https://www.flipkart.com/search?q=" + Uri.encode(value), "com.flipkart.android")
        "search_youtube_music" -> web("https://music.youtube.com/search?q=" + Uri.encode(value), "com.google.android.apps.youtube.music")
        "search_spotify" -> web("spotify:search:" + Uri.encode(value), "com.spotify.music")
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "power_menu" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "ringer_silent" -> ringer(AudioManager.RINGER_MODE_SILENT)
        "ringer_vibrate" -> ringer(AudioManager.RINGER_MODE_VIBRATE)
        "ringer_normal" -> ringer(AudioManager.RINGER_MODE_NORMAL)
        "volume_mute", "volume_unmute" -> {
            val direction = if (action == "volume_mute") AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            true
        }
        "web_search" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(value))))
        "navigate" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(value))), "com.google.android.apps.maps")
        "dial" -> dialNumber(value)
        "alarm" -> {
            val parts = value.split(":")
            launch(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, parts.getOrNull(0)?.toIntOrNull() ?: 0)
                .putExtra(AlarmClock.EXTRA_MINUTES, parts.getOrNull(1)?.toIntOrNull() ?: 0)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        }
        "timer" -> launch(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, value.toIntOrNull() ?: 0)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        "settings" -> launch(Intent(when (value) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "mobile data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            else -> Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
        }))
        "torch_on" -> torch(true)
        "torch_off" -> torch(false)
        "volume_up", "volume_down" -> {
            val direction = if (action == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            true
        }
        "media_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        "media_resume" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        "media_next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        "media_previous" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        else -> false
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

    private fun isFinance(pkg: String): Boolean =
        pkg in FINANCE_PACKAGES || FINANCE_PARTS.any { it in pkg.lowercase() }

    private fun financeMatch(name: String): Boolean {
        val wanted = name.lowercase()
        return FINANCE_NAMES.any { it in wanted }
    }

    private fun composeEmail(args: JSONObject): Boolean {
        val to = args.optString("to").trim()
        if (!to.contains("@")) return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to)))
            .putExtra(Intent.EXTRA_SUBJECT, args.optString("subject"))
            .putExtra(Intent.EXTRA_TEXT, args.optString("body"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.setPackage(GMAIL).resolveActivity(packageManager) == null) intent.setPackage(null)
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Contact se seedha WhatsApp chat. Do milte naam hon to pehle puchhna hoga. */
    private fun openWhatsApp(args: JSONObject): JSONObject {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().put("status", "no_contacts_permission")
        }
        val name = args.optString("contact").trim()
        if (name.isEmpty()) return JSONObject().put("status", "failed")
        val typedNumber = name.filter { it.isDigit() }
        if (typedNumber.length >= 10 && name.none { it.isLetter() }) {
            return openWhatsAppNumber(typedNumber, name, args.optString("message"))
        }
        val found = LinkedHashMap<String, String>()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        contentResolver.query(uri, columns, null, null, null)?.use { row ->
            while (row.moveToNext()) {
                val label = row.getString(0).orEmpty()
                if (label.isNotEmpty() && label !in found) found[label] = row.getString(1).orEmpty()
            }
        }
        val exact = found.keys.filter { it.equals(name, ignoreCase = true) }
        val chosen = when {
            exact.size == 1 -> exact[0]
            found.size == 1 -> found.keys.first()
            found.isEmpty() -> return JSONObject().put("status", "contact_not_found")
            else -> return JSONObject().put("status", "choose_contact").put("names", JSONArray(found.keys.take(5)))
        }
        return openWhatsAppNumber(found[chosen].orEmpty().filter { it.isDigit() }, chosen, args.optString("message"))
    }

    private fun openWhatsAppNumber(digits: String, shownName: String, message: String): JSONObject {
        val phone = if (digits.length == 10) "91$digits" else digits.trimStart('0')
        val link = "https://wa.me/" + phone + "?text=" + Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.setPackage("com.whatsapp").resolveActivity(packageManager) == null) {
            return JSONObject().put("status", "whatsapp_not_found")
        }
        startActivity(intent)
        waitUntilSettled("")
        return JSONObject().put("status", "ok").put("contact", shownName)
            .put("next", "Check the chat name matches this contact, then tap send.")
            .put("screen", readScreen())
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
        val useful = visibleNodes().filter { label(it).isNotEmpty() || it.isClickable || it.isEditable }
        // Pehle dabane wali cheezen, taaki AI ko chhoti list mile
        val list = (useful.filter { it.isClickable || it.isEditable } + useful.filter { !it.isClickable && !it.isEditable })
            .take(MAX_ELEMENTS)
        elements = list
        val items = JSONArray()
        val box = Rect()
        val metrics = resources.displayMetrics
        list.forEachIndexed { index, node ->
            node.getBoundsInScreen(box)
            val item = JSONObject().put("element", index)
            if (node.isClickable || node.isEditable) {
                item.put("x", box.centerX() * 1000 / metrics.widthPixels)
                    .put("y", box.centerY() * 1000 / metrics.heightPixels)
            }
            if (!node.isPassword) item.put("text", label(node).take(40))
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

        private const val MIN_SETTLE_MS = 150L
        private const val TYPE_CHECK_MS = 250L
        private const val MAX_SETTLE_MS = 4_000L
        private const val SETTLE_STEP_MS = 100L
        private const val LOAD_GRACE_MS = 500L
        private const val GESTURE_WAIT_MS = 3_000L
        private const val MAX_SCAN = 1_500
        private const val MAX_ELEMENTS = 40
        private const val MAX_READ = 6_000
        private const val LAST_APP_MS = 200L
        private const val REPLAY_TRIES = 10
        private const val REPLAY_RETRY_MS = 300L
        private const val REPLAY_WAIT_MS = 2_000L
        private val OPEN_STEP = Regex("^open app (.+)$")
        private val QUOTED_TAP = Regex("^tap \"(.+)\"$")
        private val POINT_TAP = Regex("^tap the icon at x=(\\d+) y=(\\d+)$")
        private val QUOTED_TYPE = Regex("^type \"(.*)\"$")
        private const val MAX_TEACH_STEPS = 120
        private const val SEND_TRIES = 8
        private const val SEND_CHECK_MS = 400L
        private val WHATSAPP_SEND_IDS = listOf(
            "com.whatsapp:id/send", "com.whatsapp:id/conversation_entry_action_button",
            "com.whatsapp:id/send_container", "com.whatsapp:id/send_btn"
        )
        private val SEND_WORDS = setOf("send", "भेजें", "भेजना", "पाठवा", "पाठवा.")
        private const val GMAIL = "com.google.android.gm"
        private val FINANCE_PACKAGES = setOf(
            "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user",
            "in.org.npci.upiapp", "in.amazon.mShop.android.shopping.pay", "com.sbi.lotusintouch",
            "com.csam.icici.bank.imobile", "com.snapwork.hdfc", "com.axis.mobile", "com.dreamplug.androidapp"
        )
        private val FINANCE_PARTS = listOf(".bank", "bank.", "banking", "upi", "wallet")
        private val FINANCE_NAMES = listOf("phonepe", "paytm", "google pay", "gpay", "bhim", "yono", "bank", "cred")
        private val STOP_WORDS = listOf(
            "pay now", "pay ₹", "pay rs", "proceed to pay", "continue to pay", "make payment",
            "payment method", "payment options", "select payment", "upi id", "upi pin",
            "net banking", "netbanking", "debit card", "credit card", "cvv", "enter otp",
            "one time password", "enter pin"
        )
    }
}
