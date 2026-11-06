package com.sahayak.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Aapki to-do list. Sirf phone par rehti hai. */
object Todos {
    private const val PREFS = "sahayak_todos"
    private const val KEY = "items"

    @Synchronized
    fun all(context: Context): List<JSONObject> {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        return List(array.length()) { array.getJSONObject(it) }
    }

    fun pending(context: Context) = all(context).filter { !it.optBoolean("done") }

    @Synchronized
    private fun save(context: Context, items: List<JSONObject>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(items).toString()).apply()
    }

    @Synchronized
    fun add(context: Context, text: String) {
        val clean = text.trim().take(120)
        if (clean.isEmpty()) return
        save(context, all(context) + JSONObject().put("id", UUID.randomUUID().toString().take(8)).put("text", clean).put("done", false))
    }

    @Synchronized
    fun complete(context: Context, id: String) {
        save(context, all(context).map { if (it.optString("id") == id) it.put("done", true) else it })
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        save(context, all(context).filter { it.optString("id") != id })
    }
}
