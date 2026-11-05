package com.sahayak.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Aapki di hui 1 se 10 rating. Sirf phone par, aur agle kaam ki planning mein use hoti hai. */
object Ratings {
    private const val PREFS = "sahayak_ratings"
    private const val KEY = "ratings"
    private const val MAX_ITEMS = 300

    @Synchronized
    fun all(context: Context): List<JSONObject> {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        return List(array.length()) { array.getJSONObject(it) }
    }

    @Synchronized
    private fun save(context: Context, items: List<JSONObject>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(items.takeLast(MAX_ITEMS)).toString()).apply()
    }

    @Synchronized
    fun add(context: Context, order: String, score: Int, steps: String): String {
        val id = System.currentTimeMillis().toString()
        val item = JSONObject().put("id", id).put("order", order.take(200)).put("score", score)
            .put("steps", steps.take(800)).put("at", System.currentTimeMillis())
        save(context, all(context) + item)
        return id
    }

    @Synchronized
    fun addNote(context: Context, id: String, note: String) {
        save(context, all(context).map { if (it.optString("id") == id) it.put("note", note.take(200)) else it })
    }

    fun average(context: Context): Double? {
        val items = all(context)
        return if (items.isEmpty()) null else items.map { it.optInt("score") }.average()
    }

    /** Milte-julte purane kaam: kya achha chala aur kya galat hua. */
    fun lessonsFor(context: Context, order: String): List<String> {
        val words = order.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return emptyList()
        return all(context).map { item ->
            item to words.count { it in item.optString("order").lowercase() }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(3).map { (item, _) ->
            val score = item.optInt("score")
            val note = item.optString("note")
            if (score >= 8) {
                "Past similar order \"" + item.optString("order") + "\" rated $score/10. This way worked, prefer it: " + item.optString("steps")
            } else {
                "Past similar order \"" + item.optString("order") + "\" rated $score/10. Avoid repeating this way: " +
                    item.optString("steps") + (if (note.isEmpty()) "" else ". The person said: $note")
            }
        }
    }
}
