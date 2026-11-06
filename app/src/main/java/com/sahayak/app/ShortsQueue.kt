package com.sahayak.app

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class ShortOutcome { DONE, RETRY, NO_RETRY, LIMIT, STOPPED }

data class ShortResult(val outcome: ShortOutcome, val reason: String = "")

/** YouTube Auto Upload ki video list, settings aur roz ka samay. */
object ShortsQueue {
    private const val PREFS = "sahayak_shorts"
    private const val ITEMS = "items"
    private const val SEEN = "seen"
    private const val MAX_TRIES = 3
    private const val LOCKED_RETRY_MS = 180_000L
    private const val FAIL_RETRY_MS = 300_000L
    private const val LIMIT_WAIT_MS = 86_400_000L
    private const val WAKE_MS = 300_000L
    private const val UNLOCK_WAIT_MS = 1_000L
    private const val NOTE_ID = 7_701
    const val ACTION_RUN = "com.sahayak.app.RUN_SHORT"
    const val WAITING = "waiting"
    const val UPLOADED = "uploaded"
    const val FAILED = "failed"
    private val busy = AtomicBoolean(false)

    data class Settings(
        val channel: String, val tags: String, val useName: Boolean, val kids: Boolean,
        val visibility: String, val perDay: Int, val from: Int, val to: Int, val auto: Boolean
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): Settings {
        val saved = prefs(context)
        val oldChannel = context.getSharedPreferences("sahayak", Context.MODE_PRIVATE).getString("youtube_channel", "").orEmpty()
        return Settings(
            saved.getString("channel", oldChannel).orEmpty(),
            saved.getString("tags", "#baby #babydance").orEmpty(),
            saved.getBoolean("use_name", false),
            saved.getBoolean("kids", true),
            saved.getString("visibility", "public").orEmpty(),
            saved.getInt("per_day", 6),
            saved.getInt("from", 9 * 60),
            saved.getInt("to", 21 * 60),
            saved.getBoolean("auto", false)
        )
    }

    fun saveSettings(context: Context, value: Settings) {
        prefs(context).edit().putString("channel", value.channel).putString("tags", value.tags)
            .putBoolean("use_name", value.useName).putBoolean("kids", value.kids)
            .putString("visibility", value.visibility).putInt("per_day", value.perDay)
            .putInt("from", value.from).putInt("to", value.to).putBoolean("auto", value.auto).apply()
    }

    private fun load(context: Context): MutableList<JSONObject> {
        val array = JSONArray(prefs(context).getString(ITEMS, "[]"))
        return MutableList(array.length()) { array.getJSONObject(it) }
    }

    private fun save(context: Context, items: List<JSONObject>) {
        prefs(context).edit().putString(ITEMS, JSONArray(items).toString()).apply()
    }

    @Synchronized
    fun items(context: Context): List<JSONObject> = load(context)

    fun count(context: Context, status: String): Int = items(context).count { it.optString("status") == status }

    /** Ek video list mein ek hi baar aati hai, chahe dobara chuni jaye. */
    @Synchronized
    fun add(context: Context, uri: Uri, name: String, size: Long, inFolder: Boolean): Boolean {
        val key = "$name|$size"
        val seen = prefs(context).getStringSet(SEEN, emptySet()).orEmpty()
        if (key in seen) return false
        val item = JSONObject().put("id", UUID.randomUUID().toString().take(8)).put("uri", uri.toString())
            .put("name", name).put("key", key).put("status", WAITING).put("tries", 0).put("reason", "")
            .put("folder", inFolder)
        save(context, load(context) + item)
        prefs(context).edit().putStringSet(SEEN, seen + key).apply()
        return true
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val items = load(context)
        val gone = items.firstOrNull { it.optString("id") == id } ?: return
        items.remove(gone)
        save(context, items)
        if (gone.optString("status") != UPLOADED) forget(context, gone)
        release(context, gone)
    }

    private fun forget(context: Context, item: JSONObject) {
        val seen = prefs(context).getStringSet(SEEN, emptySet()).orEmpty()
        prefs(context).edit().putStringSet(SEEN, seen - item.optString("key")).apply()
    }

    private fun release(context: Context, item: JSONObject) {
        if (item.optBoolean("folder")) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(item.optString("uri")), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Permission pehle hi nahi thi
        }
    }

    @Synchronized
    fun retryFailed(context: Context) {
        val items = load(context)
        items.filter { it.optString("status") == FAILED }.forEach {
            it.put("status", WAITING).put("tries", 0).put("reason", "")
        }
        save(context, items)
    }

    @Synchronized
    fun clearUploaded(context: Context) {
        val (gone, kept) = load(context).partition { it.optString("status") == UPLOADED }
        save(context, kept)
        gone.forEach { release(context, it) }
    }

    @Synchronized
    private fun update(context: Context, item: JSONObject) {
        save(context, load(context).map { if (it.optString("id") == item.optString("id")) item else it })
    }

    fun nextAt(context: Context): Long = prefs(context).getLong("next_at", 0L)

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, NOTE_ID,
        Intent(context, WorkReceiver::class.java).setAction(ACTION_RUN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun arm(context: Context, at: Long) {
        prefs(context).edit().putLong("next_at", at).apply()
        val manager = context.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (exact) {
            val show = PendingIntent.getActivity(
                context, NOTE_ID, Intent(context, YouTubeActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), alarmIntent(context))
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        }
    }

    fun start(context: Context) {
        val set = settings(context)
        saveSettings(context, set.copy(auto = true))
        arm(context, ShortsLogic.nextSlot(System.currentTimeMillis(), set.perDay, set.from, set.to))
    }

    fun stop(context: Context) {
        saveSettings(context, settings(context).copy(auto = false))
        prefs(context).edit().putLong("next_at", 0L).apply()
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        PhoneControl.instance?.abort = true
    }

    /** Phone restart ya app update ke baad samay wapas lagao. */
    fun rearm(context: Context) {
        val set = settings(context)
        if (!set.auto || count(context, WAITING) == 0) return
        val now = System.currentTimeMillis()
        val at = nextAt(context)
        arm(context, if (at > now) at else ShortsLogic.nextSlot(now, set.perDay, set.from, set.to))
    }

    private fun armNextSlot(context: Context) {
        val set = settings(context)
        if (set.auto) arm(context, ShortsLogic.nextSlot(System.currentTimeMillis(), set.perDay, set.from, set.to))
    }

    private fun canRead(context: Context, item: JSONObject): Boolean = try {
        context.contentResolver.openFileDescriptor(Uri.parse(item.optString("uri")), "r")?.close()
        true
    } catch (e: Exception) {
        false
    }

    /** Sabse pehli baaki video upload karo. */
    fun run(context: Context, scheduled: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        val power = context.getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        val wake = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "sahayak:shorts"
        )
        try {
            wake.acquire(WAKE_MS)
            uploadNext(context, scheduled)
        } catch (e: RuntimeException) {
            RunLog.add(context, "short crashed " + e.message)
            if (scheduled) arm(context, System.currentTimeMillis() + FAIL_RETRY_MS)
        } finally {
            if (wake.isHeld) wake.release()
            busy.set(false)
        }
    }

    private fun uploadNext(context: Context, scheduled: Boolean) {
        val set = settings(context)
        if (scheduled && !set.auto) return
        val item = items(context).firstOrNull { it.optString("status") == WAITING }
        if (item == null) {
            if (scheduled) WorkList.notifyText(context, NOTE_ID, "All videos are uploaded. Add more videos.")
            prefs(context).edit().putLong("next_at", 0L).apply()
            return
        }
        val control = PhoneControl.instance
        control?.swipeUnlock()
        Thread.sleep(UNLOCK_WAIT_MS)
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (control == null || locked) {
            WorkList.notifyText(context, NOTE_ID, (if (locked) "Unlock the phone" else "Turn on phone control") + " to upload: " + item.optString("name"))
            if (scheduled) arm(context, System.currentTimeMillis() + LOCKED_RETRY_MS)
            return
        }
        if (!canRead(context, item)) {
            item.put("status", FAILED).put("reason", "Video file not found")
            update(context, item)
            if (scheduled) armNextSlot(context)
            return
        }
        val caption = ShortsLogic.caption(item.optString("name"), set.tags, set.useName)
        val result = control.uploadShort(item.optString("uri"), item.optString("name"), caption, set)
        RunLog.add(context, "short " + result.outcome + " " + item.optString("name") + " " + result.reason)
        finish(context, item, result, scheduled)
    }

    private fun finish(context: Context, item: JSONObject, result: ShortResult, scheduled: Boolean) {
        val name = item.optString("name")
        when (result.outcome) {
            ShortOutcome.DONE -> {
                item.put("status", UPLOADED).put("reason", "")
                update(context, item)
                WorkList.notifyText(context, NOTE_ID, "Upload started: $name")
                if (scheduled) armNextSlot(context)
            }
            ShortOutcome.STOPPED -> if (scheduled) armNextSlot(context)
            ShortOutcome.LIMIT -> {
                item.put("reason", "YouTube daily limit reached")
                update(context, item)
                WorkList.notifyText(context, NOTE_ID, "YouTube daily limit reached. Uploads will continue tomorrow.")
                if (scheduled) arm(context, System.currentTimeMillis() + LIMIT_WAIT_MS)
            }
            ShortOutcome.NO_RETRY -> fail(context, item, result.reason, scheduled)
            ShortOutcome.RETRY -> {
                val tries = item.optInt("tries") + 1
                item.put("tries", tries).put("reason", result.reason)
                if (tries >= MAX_TRIES) {
                    fail(context, item, result.reason, scheduled)
                } else {
                    update(context, item)
                    if (scheduled) arm(context, System.currentTimeMillis() + FAIL_RETRY_MS)
                }
            }
        }
    }

    private fun fail(context: Context, item: JSONObject, reason: String, scheduled: Boolean) {
        item.put("status", FAILED).put("reason", reason)
        update(context, item)
        WorkList.notifyText(context, NOTE_ID, "Upload failed: " + item.optString("name") + " - " + reason)
        if (scheduled) armNextSlot(context)
    }
}
