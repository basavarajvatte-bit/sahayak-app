package com.sahayak.app

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Aage ke kaam ki list: tay samay par alarm se kaam chalta hai. */
object WorkList {
    private const val PREFS = "sahayak_work"
    private const val ITEMS = "items"
    private const val CHANNEL = "sahayak_work"
    private const val RETRY_MS = 120_000L
    private const val MAX_TRIES = 60
    const val ACTION_RUN = "com.sahayak.app.RUN_WORK"
    const val EXTRA_ID = "id"
    private val display = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)

    private fun load(context: Context): MutableList<JSONObject> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ITEMS, "[]")
        val array = JSONArray(raw)
        return MutableList(array.length()) { array.getJSONObject(it) }
    }

    private fun save(context: Context, items: List<JSONObject>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ITEMS, JSONArray(items).toString()).apply()
    }

    @Synchronized
    fun all(context: Context): List<JSONObject> = load(context).sortedBy { it.optLong("at") }

    fun label(item: JSONObject): String =
        display.format(Date(item.optLong("at"))) + " - " + item.optString("title") + statusText(item)

    private fun statusText(item: JSONObject): String = when (item.optString("status")) {
        "done" -> " (done)"
        "failed" -> " (failed)"
        else -> ""
    }

    @Synchronized
    fun add(context: Context, kind: String, at: Long, title: String, to: String, subject: String, message: String): JSONObject {
        val item = JSONObject().put("id", UUID.randomUUID().toString().take(8)).put("kind", kind)
            .put("at", at).put("title", title).put("to", to).put("subject", subject)
            .put("message", message).put("status", "pending").put("tries", 0)
        save(context, load(context) + item)
        arm(context, item)
        return item
    }

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val items = load(context)
        val found = items.removeAll { it.optString("id") == id }
        save(context, items)
        alarms(context).cancel(pending(context, id))
        return found
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, WorkReceiver::class.java).setAction(ACTION_RUN).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun arm(context: Context, item: JSONObject) {
        val at = item.optLong("at")
        val trigger = pending(context, item.optString("id"))
        val manager = alarms(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (exact) {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), trigger)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, trigger)
        }
    }

    /** Phone restart hone par alarm wapas lagao. Chhoote kaam turant chalenge. */
    @Synchronized
    fun rearm(context: Context) {
        val now = System.currentTimeMillis()
        load(context).filter { it.optString("status") == "pending" }.forEach {
            if (it.optLong("at") <= now) {
                it.put("at", now + RETRY_MS)
                update(context, it)
            }
            arm(context, it)
        }
    }

    @Synchronized
    private fun update(context: Context, item: JSONObject) {
        val items = load(context).map { if (it.optString("id") == item.optString("id")) item else it }
        save(context, items)
    }

    fun find(context: Context, id: String): JSONObject? = load(context).firstOrNull { it.optString("id") == id }

    fun run(context: Context, id: String) {
        val item = find(context, id) ?: return
        if (item.optString("status") != "pending") return
        if (item.optString("kind") == "reminder") {
            finish(context, item, "done", "Reminder: " + item.optString("message"))
            return
        }
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val control = PhoneControl.instance
        if (locked || control == null) {
            val tries = item.optInt("tries") + 1
            if (tries > MAX_TRIES) {
                finish(context, item, "failed", "Task failed: " + item.optString("title"))
                return
            }
            item.put("tries", tries).put("at", System.currentTimeMillis() + RETRY_MS)
            update(context, item)
            arm(context, item)
            val why = if (locked) "Unlock the phone" else "Turn on phone control"
            notify(context, id.hashCode(), why + ", task pending: " + item.optString("title"))
            return
        }
        wake(context)
        val ok = try {
            control.runWork(item)
        } catch (e: RuntimeException) {
            false
        }
        val why = control.lastWorkError
        RunLog.add(context, "scheduled " + item.optString("kind") + " " + (if (ok) "ok" else "failed " + why))
        finish(context, item, if (ok) "done" else "failed",
            (if (ok) "Done: " else "Task failed: ") + item.optString("title") + (if (why.isEmpty()) "" else " - $why"))
    }

    private fun wake(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive) return
        @Suppress("DEPRECATION")
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "sahayak:work"
        ).acquire(10_000L)
    }

    private fun finish(context: Context, item: JSONObject, status: String, text: String) {
        item.put("status", status)
        update(context, item)
        notify(context, item.optString("id").hashCode(), text)
    }

    fun notifyText(context: Context, id: Int, text: String) = notify(context, id, text)

    private fun notify(context: Context, id: Int, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sahayak tasks", NotificationManager.IMPORTANCE_HIGH))
        manager.notify(id, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Sahayak")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build())
    }
}

class WorkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Watcher.ACTION_RUN) {
            val app = context.applicationContext
            Thread { Watcher.run(app, true) }.start()
            return
        }
        if (intent.action == Market.ACTION_NEWS || intent.action == Market.ACTION_REPORT) {
            val app = context.applicationContext
            val report = intent.action == Market.ACTION_REPORT
            Thread { if (report) Market.report(app, true) else Market.runNews(app, true) }.start()
            return
        }
        val queue = listOf(ShortsQueue, ReelsQueue).firstOrNull { it.actionRun == intent.action }
        if (queue != null) {
            // Upload do minute tak chalta hai, phone control ka service app ko zinda rakhta hai
            val app = context.applicationContext
            Thread { queue.run(app, true) }.start()
            return
        }
        val result = goAsync()
        Thread {
            try {
                if (intent.action == WorkList.ACTION_RUN) {
                    WorkList.run(context, intent.getStringExtra(WorkList.EXTRA_ID).orEmpty())
                } else {
                    WorkList.rearm(context)
                    ShortsQueue.rearm(context)
                    ReelsQueue.rearm(context)
                    Watcher.rearm(context)
                    Market.rearm(context)
                }
            } finally {
                result.finish()
            }
        }.start()
    }
}
