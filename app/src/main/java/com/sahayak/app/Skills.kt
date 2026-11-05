package com.sahayak.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Aapke sikhaye hue kaam. App khud kabhi delete nahi karta.
 * Har kaam ka asli demo, pichhla version, galtiyan aur hisaab rakha jata hai.
 */
object Skills {
    private const val PREFS = "sahayak_skills"
    private const val KEY = "skills"
    private const val MAX_DEMOS = 8
    private const val LOW_SCORE = 5
    private const val MAX_LESSONS = 12
    private const val PROMPT_LIMIT = 6_000
    private val work = Executors.newSingleThreadExecutor()

    @Synchronized
    fun all(context: Context): List<JSONObject> {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        return List(array.length()) { array.getJSONObject(it) }
    }

    @Synchronized
    private fun save(context: Context, items: List<JSONObject>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(items).toString()).commit()
    }

    private fun list(item: JSONObject?, key: String): List<String> {
        val array = item?.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { array.optString(it) }
    }

    private fun find(context: Context, name: String): JSONObject? =
        all(context).firstOrNull { it.optString("name").equals(name, ignoreCase = true) }

    private fun change(context: Context, name: String, edit: (JSONObject) -> Unit) {
        synchronized(this) {
            save(context, all(context).map { skill ->
                if (skill.optString("name").equals(name, ignoreCase = true)) edit(skill)
                skill
            })
        }
    }

    /** Naya kaam, ya purane kaam ka ek aur example. */
    fun addDemo(context: Context, name: String, steps: List<String>) {
        synchronized(this) {
            if (find(context, name) == null) {
                save(context, all(context) + JSONObject().put("name", name).put("steps", JSONArray(steps))
                    .put("original", JSONArray(steps)).put("demos", JSONArray()))
            }
            change(context, name) { skill ->
                val demos = skill.optJSONArray("demos") ?: JSONArray()
                demos.put(JSONArray(steps))
                while (demos.length() > MAX_DEMOS) demos.remove(0)
                skill.put("demos", demos)
            }
        }
        digest(context, name)
    }

    /** Demos se general kaam banata hai, taaki milte-julte kaam bhi ho saken. */
    private fun digest(context: Context, name: String) {
        val app = context.applicationContext
        work.execute {
            val key = SecureStore(app.getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
            val skill = find(app, name) ?: return@execute
            val demos = skill.optJSONArray("demos")?.toString().orEmpty()
            val digest = if (key.isEmpty()) null else
                Planner(GeminiLive.newClient(), key).digest(name, demos, list(skill, "lessons"))
            if (digest != null) {
                change(app, name) { item ->
                    if (item.optJSONArray("steps") != null) item.put("previous", item.optJSONArray("steps"))
                    item.put("steps", digest.optJSONArray("steps"))
                    item.put("goal", digest.optString("goal"))
                }
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, if (digest != null) "Learned: $name" else "Steps saved: $name", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Aakhri demo ke asli steps: dohrane ke liye sabse pakka rasta. */
    fun lastDemo(context: Context, name: String): List<String> {
        val demos = find(context, name)?.optJSONArray("demos")
        val demo = demos?.optJSONArray((demos.length()) - 1)
        return if (demo != null) List(demo.length()) { demo.optString(it) } else steps(context, name)
    }

    /** Bola gaya naam kisi sikhaye kaam se poora mile to uska naam. */
    fun exactName(context: Context, text: String): String? {
        val wanted = text.trim().trimEnd('.', '!', '?', '।').lowercase()
        return all(context).firstOrNull { it.optString("name").trim().lowercase() == wanted }?.optString("name")
    }

    fun steps(context: Context, name: String): List<String> = list(find(context, name), "steps")

    fun detail(context: Context, name: String): JSONObject? {
        val skill = find(context, name) ?: return null
        return JSONObject().put("name", skill.optString("name")).put("goal", skill.optString("goal"))
            .put("steps", skill.optJSONArray("steps")).put("lessons", skill.optJSONArray("lessons") ?: JSONArray())
    }

    /** Steps dikhane ke liye, aur galat step hatane ke liye. */
    fun removeStep(context: Context, name: String, step: String) {
        change(context, name) { skill ->
            skill.put("steps", JSONArray(list(skill, "steps").filter { it != step }))
            val demos = skill.optJSONArray("demos") ?: JSONArray()
            if (demos.length() > 0) {
                val last = demos.getJSONArray(demos.length() - 1)
                val kept = List(last.length()) { last.optString(it) }.filter { it != step }
                demos.put(demos.length() - 1, JSONArray(kept))
            }
        }
    }

    fun lesson(context: Context, name: String, text: String) {
        change(context, name) { skill ->
            val lessons = list(skill, "lessons") + text.take(200)
            skill.put("lessons", JSONArray(lessons.takeLast(MAX_LESSONS)))
        }
    }

    /** Aapne bola galat hua: galti yaad rakho, aur naya version kharab ho to pichhla wapas. */
    fun markWrong(context: Context, name: String, lessonText: String) {
        if (lessonText.isNotBlank()) {
            lesson(context, name, lessonText)
            DailyLearning.note(context, "correction for $name: $lessonText")
        }
        change(context, name) { skill ->
            skill.put("good", maxOf(0, skill.optInt("good") - 1))
            restoreIfImproved(skill)
        }
    }

    private fun restoreIfImproved(skill: JSONObject) {
        val previous = skill.optJSONArray("previous") ?: return
        skill.put("steps", previous)
        skill.remove("previous")
        skill.put("failStreak", 0)
    }

    /** Practice ka hisaab. Do baar sahi hone ke baad hi steps badlenge. */
    fun recordRun(context: Context, name: String, ok: Boolean, better: List<String>?, failedAt: String) {
        change(context, name) { skill ->
            skill.put("runs", skill.optInt("runs") + 1)
            if (ok) {
                skill.put("good", skill.optInt("good") + 1).put("failStreak", 0)
                if (!better.isNullOrEmpty() && skill.optInt("good") >= 2) {
                    skill.put("previous", skill.optJSONArray("steps"))
                    skill.put("steps", JSONArray(better))
                }
            } else {
                val streak = skill.optInt("failStreak") + 1
                skill.put("failStreak", streak)
                if (failedAt.isNotBlank()) {
                    val lessons = list(skill, "lessons") + ("A past run failed near: " + failedAt.take(120))
                    skill.put("lessons", JSONArray(lessons.takeLast(MAX_LESSONS)))
                }
                if (streak >= 2) restoreIfImproved(skill)
            }
        }
    }

    /** Aapki rating: achhi ho to kaam pakka, kam ho to pichhla version wapas. */
    fun recordRating(context: Context, name: String, score: Int) {
        change(context, name) { skill ->
            skill.put("ratingSum", skill.optInt("ratingSum") + score)
            skill.put("ratingCount", skill.optInt("ratingCount") + 1)
            if (score <= LOW_SCORE) restoreIfImproved(skill)
        }
    }

    fun label(skill: JSONObject): String {
        val runs = skill.optInt("runs")
        val demos = maxOf(1, skill.optJSONArray("demos")?.length() ?: 1)
        val name = skill.optString("name")
        val rated = skill.optInt("ratingCount")
        val rating = if (rated == 0) "" else ", rating " + String.format(java.util.Locale.ENGLISH, "%.1f", skill.optInt("ratingSum").toDouble() / rated)
        return name + " (" + demos + " example" + (if (runs == 0) "" else ", " + runs + " runs, " + skill.optInt("good") + " correct") + rating + ")"
    }

    fun remove(context: Context, name: String) {
        synchronized(this) { save(context, all(context).filter { it.optString("name") != name }) }
    }

    /** Sirf naam aur goal, taaki AI ko chhoti aur saaf list mile. */
    fun catalog(context: Context): String = all(context).joinToString("\n") {
        "- " + it.optString("name") + ": " + it.optString("goal")
    }.take(PROMPT_LIMIT)

    /** Order se milte kaam ke poore steps planner ke liye. */
    fun matches(context: Context, order: String): List<String> {
        val words = order.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        return all(context).map { skill ->
            val text = (skill.optString("name") + " " + skill.optString("goal")).lowercase()
            skill to words.count { it in text }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(3).map { (skill, _) ->
            "Taught task \"" + skill.optString("name") + "\" (goal: " + skill.optString("goal") + "). Steps: " +
                list(skill, "steps").joinToString(" | ") + ". Lessons: " + list(skill, "lessons").joinToString(" | ")
        }
    }

    fun exportJson(context: Context): String = JSONObject()
        .put("format", "sahayak-backup-1")
        .put("skills", JSONArray(all(context)))
        .put("memory", JSONArray(Memory.all(context)))
        .toString(2)

    /** Backup se wapas: purana kuch mitata nahi. */
    fun importJson(context: Context, text: String): Int {
        val backup = JSONObject(text)
        require(backup.optString("format") == "sahayak-backup-1")
        val incoming = backup.getJSONArray("skills")
        var added = 0
        synchronized(this) {
            val current = all(context).toMutableList()
            for (i in 0 until incoming.length()) {
                val skill = incoming.getJSONObject(i)
                if (current.none { it.optString("name").equals(skill.optString("name"), ignoreCase = true) }) {
                    current.add(skill)
                    added++
                }
            }
            save(context, current)
        }
        val facts = backup.optJSONArray("memory") ?: JSONArray()
        for (i in 0 until facts.length()) Memory.add(context, facts.optString(i))
        return added
    }
}
