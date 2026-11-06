package com.sahayak.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Khud seekhe hue tareeke (playbooks) aur aapka chhota profile. */
object Brain {
    private const val PREFS = "sahayak_brain"
    private const val BOOKS = "playbooks"
    private const val PROFILE = "profile"
    private const val MAX_BOOKS = 60
    private const val MIN_STEPS = 5
    private const val MAX_MATCHES = 3
    private const val MAX_PAST = 3
    private const val MAX_BAD_RUNS = 3
    private const val LEARN_TIMEOUT_S = 40L
    private const val MAX_STEP_TEXT = 3_000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun profile(context: Context): String = prefs(context).getString(PROFILE, "").orEmpty()

    fun setProfile(context: Context, text: String) {
        val clean = BrainLogic.scrub(text, 700) ?: return
        prefs(context).edit().putString(PROFILE, clean).apply()
    }

    @Synchronized
    fun books(context: Context): List<JSONObject> {
        val array = JSONArray(prefs(context).getString(BOOKS, "[]"))
        return List(array.length()) { array.getJSONObject(it) }
    }

    private fun save(context: Context, items: List<JSONObject>) {
        prefs(context).edit().putString(BOOKS, JSONArray(items).toString()).apply()
    }

    private fun topic(book: JSONObject) = book.optString("name") + " " + book.optString("when")

    private fun bad(book: JSONObject) =
        book.optInt("fails") >= MAX_BAD_RUNS && book.optInt("fails") > book.optInt("wins")

    fun best(context: Context, order: String): List<JSONObject> = books(context).filter { !bad(it) }
        .map { it to BrainLogic.overlap(order, topic(it)) }.filter { it.second > 0 }
        .sortedByDescending { it.second }.take(MAX_MATCHES).map { it.first }

    /** Plan banane wale ko purana anubhav. Yeh sirf record hai, hukm nahi. */
    fun notes(context: Context, order: String): List<String> {
        val guides = best(context, order).map { book ->
            val steps = book.optJSONArray("steps") ?: JSONArray()
            "Playbook that worked before, " + book.optString("name") + ": " +
                List(steps.length()) { steps.optString(it) }.joinToString(" > ")
        }
        val past = History.search(context, order, MAX_PAST + 1).filter { !it.endsWith(": $order") }.take(MAX_PAST)
            .map { "Past record: $it" }
        val who = profile(context)
        return (if (who.isEmpty()) emptyList() else listOf("Profile: $who")) + guides + past
    }

    @Synchronized
    fun remove(context: Context, name: String) {
        save(context, books(context).filter { it.optString("name") != name })
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    @Synchronized
    private fun record(context: Context, name: String, ok: Boolean) {
        save(context, books(context).onEach {
            if (it.optString("name") == name) {
                val key = if (ok) "wins" else "fails"
                it.put(key, it.optInt(key) + 1)
            }
        })
    }

    @Synchronized
    private fun store(context: Context, json: JSONObject, oldName: String?) {
        val name = json.optString("name").trim().take(60)
        val steps = json.optJSONArray("steps")
        if (name.isEmpty() || steps == null || steps.length() == 0) return
        val all = books(context).toMutableList()
        val old = all.firstOrNull { it.optString("name") == oldName || it.optString("name").equals(name, ignoreCase = true) }
        all.remove(old)
        all.add(
            JSONObject().put("name", name).put("when", json.optString("when").take(160)).put("steps", steps)
                .put("wins", (old?.optInt("wins") ?: 0)).put("fails", old?.optInt("fails") ?: 0)
        )
        save(context, all.takeLast(MAX_BOOKS))
    }

    /** Order khatam: result likho, aur lamba safal kaam ko tareeka banao ya sudharo. */
    fun finished(context: Context, order: String, ok: Boolean, steps: List<String>) {
        History.add(context, "result", (if (ok) "finished: " else "not finished: ") + order)
        DailyLearning.nudge(context)
        if (!DailyLearning.isEnabled(context)) return
        val app = context.applicationContext
        val known = best(context, order)
        known.forEach { record(context, it.optString("name"), ok) }
        if (!ok || steps.size < MIN_STEPS) return
        val text = BrainLogic.scrub(order + "\n" + steps.joinToString("\n"), MAX_STEP_TEXT) ?: return
        Thread {
            val key = SecureStore(app.getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
            if (key.isNotEmpty()) {
                val old = known.firstOrNull()
                val json = Planner(GeminiLive.newClient(), key, LEARN_TIMEOUT_S).playbook(text, old?.toString().orEmpty())
                if (json != null && !json.optBoolean("skip")) {
                    store(app, json.put("wins", 1), old?.optString("name"))
                    if (old == null) record(app, json.optString("name").trim().take(60), true)
                    RunLog.add(app, "playbook saved " + json.optString("name"))
                }
            }
        }.start()
    }

    fun exportJson(context: Context): JSONObject =
        JSONObject().put("profile", profile(context)).put("playbooks", JSONArray(books(context)))

    @Synchronized
    fun importJson(context: Context, data: JSONObject?) {
        if (data == null) return
        if (profile(context).isEmpty()) setProfile(context, data.optString("profile"))
        val incoming = data.optJSONArray("playbooks") ?: return
        val all = books(context).toMutableList()
        for (i in 0 until incoming.length()) {
            val book = incoming.getJSONObject(i)
            if (all.none { it.optString("name") == book.optString("name") }) all.add(book)
        }
        save(context, all.takeLast(MAX_BOOKS))
    }
}
