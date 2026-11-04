package com.sahayak.app

import android.content.Context
import org.json.JSONArray

/** Sahayak ki lambi yaadasht: sirf phone par rehti hai. Secret yahan kabhi nahi. */
object Memory {
    private const val PREFS = "sahayak_memory"
    private const val FACTS = "facts"
    private const val MAX_FACTS = 40
    private const val MAX_LENGTH = 160
    private val BLOCKED = listOf("password", "passcode", "otp", "pin ", "cvv", "card number", "upi pin", "पासवर्ड", "ओटीपी")

    fun all(context: Context): List<String> {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FACTS, "[]"))
        return List(array.length()) { array.getString(it) }
    }

    private fun save(context: Context, facts: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(FACTS, JSONArray(facts).toString()).apply()
    }

    @Synchronized
    fun add(context: Context, fact: String): Boolean {
        val clean = fact.trim().take(MAX_LENGTH)
        val lower = clean.lowercase() + " "
        if (clean.isEmpty() || BLOCKED.any { it in lower }) return false
        save(context, (all(context).filter { it != clean } + clean).takeLast(MAX_FACTS))
        return true
    }

    @Synchronized
    fun remove(context: Context, fact: String) {
        save(context, all(context).filter { it != fact })
    }
}
