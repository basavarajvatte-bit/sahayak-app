package com.sahayak.app

import org.json.JSONException
import org.json.JSONObject

data class Scene(val action: String, val place: String, val line: String, val seconds: Double)

data class Story(val title: String, val caption: String, val character: String, val scenes: List<Scene>)

/** Idea se bani kahani ko jaanchna. AI galat naam de to bhi video bane. */
object StoryLogic {
    val ACTIONS = listOf("idle", "happy", "excited", "sad", "surprised", "sleepy", "grumpy", "thinking", "dance")
    val SWAG = listOf("swag", "swagidle")
    val PLACES = listOf("jungle", "beach", "night", "stage", "room", "park", "space", "snow")
    private const val MIN_SECONDS = 2.0
    private const val MAX_SECONDS = 8.0
    private const val MAX_SCENES = 12
    private const val MAX_LINE = 120
    private const val MAX_TOTAL = 60.0

    fun actionsFor(character: String) = if (character == "chhotu") ACTIONS + SWAG else ACTIONS

    fun parse(json: String, character: String): Story? {
        val root = try {
            JSONObject(BrainLogic.cleanJson(json))
        } catch (e: JSONException) {
            return null
        }
        val list = root.optJSONArray("scenes") ?: return null
        val allowed = actionsFor(character)
        var total = 0.0
        val scenes = ArrayList<Scene>()
        for (i in 0 until minOf(list.length(), MAX_SCENES)) {
            val o = list.optJSONObject(i) ?: continue
            val action = o.optString("action").lowercase().trim().let { if (it in allowed) it else "happy" }
            val place = o.optString("place").lowercase().trim().let { if (it in PLACES) it else scenes.lastOrNull()?.place ?: "park" }
            val line = o.optString("line").trim().take(MAX_LINE)
            val seconds = o.optDouble("seconds", 3.0).let { if (it.isNaN()) 3.0 else it }.coerceIn(MIN_SECONDS, MAX_SECONDS)
            if (total + seconds > MAX_TOTAL) break
            total += seconds
            scenes.add(Scene(action, place, line, seconds))
        }
        if (scenes.isEmpty()) return null
        val title = root.optString("title").trim().ifEmpty { "My Short" }.take(60)
        val caption = root.optString("caption").trim().take(ShortsLogic.MAX_CAPTION)
        return Story(title, caption, character, scenes)
    }

    /** File naam: sirf akshar aur ank. */
    fun fileName(title: String, stamp: String): String {
        val clean = title.replace(Regex("[^A-Za-z0-9 ]"), "").trim().replace(Regex("\\s+"), "_").take(40).ifEmpty { "Short" }
        return clean + "_" + stamp + ".mp4"
    }

    /** Lambi line ko subtitle ki kai lines mein todna. */
    fun wrap(text: String, perLine: Int): List<String> {
        val lines = ArrayList<String>()
        var current = ""
        text.split(" ").filter { it.isNotEmpty() }.forEach { word ->
            current = if (current.isEmpty()) word else if (current.length + 1 + word.length <= perLine) "$current $word" else {
                lines.add(current)
                word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }
}
