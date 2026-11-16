package com.sahayak.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Aapki to-do list. Sirf phone par rehti hai. */
object Todos {
    private const val PREFS = "sahayak_todos"
    private const val KEY = "items"

    /** Doosre ki list ki copy is dibbe mein rehti hai (bhai ki list). Apni list ka dibba khaali naam "". */
    const val PEER = "peer"

    private fun prefs(context: Context, box: String) =
        context.getSharedPreferences(if (box.isEmpty()) PREFS else PREFS + "_" + box, Context.MODE_PRIVATE)

    /** Sab kuch, hataye hue kaam ke nishaan samet. Sirf do phone milane ke liye. */
    @Synchronized
    fun raw(context: Context, box: String = ""): List<JSONObject> {
        val array = JSONArray(prefs(context, box).getString(KEY, "[]"))
        return List(array.length()) { array.getJSONObject(it) }
    }

    fun all(context: Context, box: String = ""): List<JSONObject> = raw(context, box).filter { !it.optBoolean("del") }

    /** Baaki kaam, samay ke kram mein (bina tarikh wale aaj ke maane jaate hain). Koi ginti ki seema nahi. */
    fun pending(context: Context, box: String = "") = all(context, box).filter { !it.optBoolean("done") }.sortedWith(compareBy({ dayStart(dueOf(it)) }, { timed(it).not() }, { dueOf(it) }))

    /** Sirf aaj ke (aur pichhle chhoote hue) kaam. Chhotu sirf yahi batata hai. */
    fun today(context: Context, now: Long = System.currentTimeMillis(), box: String = ""): List<JSONObject> {
        val end = dayStart(now) + DAY_MS
        return pending(context, box).filter { dueOf(it, now) < end }
    }

    /** Kaam ki tarikh. Purane kaam (bina tarikh) aaj ke maane jaate hain. */
    fun dueOf(item: JSONObject, now: Long = System.currentTimeMillis()): Long = item.optLong("at", 0L).let { if (it > 0L) it else now }

    fun timed(item: JSONObject) = item.optBoolean("timed", false) && item.optLong("at", 0L) > 0L

    fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Din ka naam: Today, Tomorrow, Yesterday, ya "Mon 17 Nov". */
    fun dayName(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = Math.round((dayStart(ms) - dayStart(now)).toDouble() / DAY_MS).toInt()
        return when (diff) {
            0 -> "Today"
            1 -> "Tomorrow"
            -1 -> "Yesterday"
            else -> SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(Date(ms))
        }
    }

    fun clock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(ms))

    /** List mein dikhane ke liye: "Today 5:30 PM", "Tomorrow", "Overdue · Fri 14 Nov 9:00 AM". */
    fun whenText(item: JSONObject, now: Long = System.currentTimeMillis()): String {
        val at = item.optLong("at", 0L)
        if (at <= 0L) return "Today"
        val late = if (timed(item)) at < now else dayStart(at) < dayStart(now)
        return (if (late) "Overdue · " else "") + dayName(at, now) + (if (timed(item)) " " + clock(at) else "")
    }

    /** Group ki heading: Overdue, Today, Tomorrow, ya tarikh. */
    fun groupOf(item: JSONObject, now: Long = System.currentTimeMillis()): String {
        val at = dueOf(item, now)
        return if (dayStart(at) < dayStart(now)) "Overdue" else dayName(at, now)
    }

    private const val DAY_MS = 86_400_000L

    @Synchronized
    private fun save(context: Context, items: List<JSONObject>, box: String) {
        prefs(context, box).edit()
            .putString(KEY, JSONArray(ShareLogic.prune(items, System.currentTimeMillis())).toString()).commit()
        if (box.isEmpty()) refreshWidgets(context)
    }

    /** Home screen ka to-do widget taaza karo (Sahayak mein ho to; Chhotu To-Do app mein ye kuch nahi karta). */
    fun refreshWidgets(context: Context) {
        try {
            val manager = android.appwidget.AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context.packageName, "com.sahayak.app.TodoWidget"))
            if (ids.isEmpty()) return
            // Widget khud apni list taaza karta hai (onUpdate mein)
            context.sendBroadcast(android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setClassName(context.packageName, "com.sahayak.app.TodoWidget")
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        } catch (e: RuntimeException) {
            // Widget na ho to kuch nahi
        }
    }

    @Synchronized
    fun add(context: Context, text: String, at: Long = 0L, timed: Boolean = false, box: String = "") {
        val clean = text.trim().take(TEXT_MAX)
        if (clean.isEmpty()) return
        val item = JSONObject().put("id", UUID.randomUUID().toString().take(8)).put("text", clean).put("done", false)
            .put("mod", System.currentTimeMillis())
        if (at > 0L) item.put("at", at).put("timed", timed)
        save(context, raw(context, box) + item, box)
        TodoSync.localChange(context, box, listOf(item))
    }

    @Synchronized
    fun complete(context: Context, id: String, box: String = "") = mark(context, id, box, "done")

    /** Hataya hua kaam nishaan ke saath rehta hai, taaki doosre phone se wapas na aa jaye. 30 din baad saaf. */
    @Synchronized
    fun remove(context: Context, id: String, box: String = "") = mark(context, id, box, "del")

    private fun mark(context: Context, id: String, box: String, flag: String) {
        val changed = mutableListOf<JSONObject>()
        val items = raw(context, box).map {
            if (it.optString("id") == id) it.put(flag, true).put("mod", System.currentTimeMillis()).also { item -> changed.add(item) } else it
        }
        save(context, items, box)
        TodoSync.localChange(context, box, changed)
    }

    /** Doosre phone se aaye kaam milao. Jo naya aaya ya abhi "ho gaya" hua wo lautao. */
    @Synchronized
    fun merge(context: Context, box: String, incoming: List<JSONObject>): ShareLogic.Merged {
        val merged = ShareLogic.merge(raw(context, box), incoming, System.currentTimeMillis())
        if (merged.changed) save(context, merged.items, box)
        return merged
    }

    /** Ek kaam mein itne akshar tak. */
    private const val TEXT_MAX = 300
}
