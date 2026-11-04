package com.sahayak.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Aapke sikhaye hue kaam. Sirf phone par rehte hain. */
object Skills {
    private const val PREFS = "sahayak_skills"
    private const val KEY = "skills"
    private const val MAX_SKILLS = 20

    fun all(context: Context): List<JSONObject> {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        return List(array.length()) { array.getJSONObject(it) }
    }

    private fun save(context: Context, items: List<JSONObject>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(items).toString()).apply()
    }

    @Synchronized
    fun add(context: Context, name: String, steps: List<String>) {
        val others = all(context).filter { !it.optString("name").equals(name, ignoreCase = true) }
        val item = JSONObject().put("name", name).put("steps", JSONArray(steps))
        save(context, (others + item).takeLast(MAX_SKILLS))
    }

    fun steps(context: Context, name: String): List<String> {
        val array = all(context).firstOrNull { it.optString("name") == name }?.optJSONArray("steps") ?: return emptyList()
        return List(array.length()) { array.optString(it) }
    }

    /** Practice ka hisaab, aur behtar steps mile to unhe rakho. */
    @Synchronized
    fun recordRun(context: Context, name: String, ok: Boolean, better: List<String>?) {
        save(context, all(context).map { skill ->
            if (skill.optString("name") == name) {
                skill.put("runs", skill.optInt("runs") + 1)
                if (ok) skill.put("good", skill.optInt("good") + 1)
                if (!better.isNullOrEmpty()) skill.put("steps", JSONArray(better))
            }
            skill
        })
    }

    fun label(skill: JSONObject): String {
        val runs = skill.optInt("runs")
        val name = skill.optString("name")
        return if (runs == 0) name else name + " (" + runs + " baar, " + skill.optInt("good") + " sahi)"
    }

    @Synchronized
    fun remove(context: Context, name: String) {
        save(context, all(context).filter { it.optString("name") != name })
    }

    /** AI ko dene ke liye sikhaye hue kaam ki list. */
    fun promptLines(context: Context): List<String> = all(context).map { skill ->
        val steps = skill.optJSONArray("steps") ?: JSONArray()
        "Taught task \"" + skill.optString("name") + "\": " +
            List(steps.length()) { (it + 1).toString() + ". " + steps.optString(it) }.joinToString(" ")
    }
}
