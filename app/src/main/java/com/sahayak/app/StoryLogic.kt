package com.sahayak.app

import org.json.JSONException
import org.json.JSONObject

data class Scene(val action: String, val place: String, val line: String, val seconds: Double)

data class Story(val title: String, val caption: String, val character: String, val scenes: List<Scene>)

/** Idea se bani kahani ko jaanchna. AI galat naam de to bhi video bane. */
object StoryLogic {
    val ACTIONS = listOf("idle", "happy", "excited", "sad", "surprised", "sleepy", "grumpy", "thinking", "dance")
    val SWAG = listOf("swag", "swagidle")
    val ACTIVITIES = listOf("hop", "eat", "drive", "swing")
    val PLACES = listOf("jungle", "beach", "night", "stage", "room", "park", "space", "snow")
    private const val MIN_SECONDS = 2.0
    private const val MAX_SECONDS = 8.0
    private const val MAX_SCENES = 12
    private const val MAX_LINE = 120
    private const val MAX_TOTAL = 60.0

    fun actionsFor(character: String) = if (character == "chhotu") ACTIONS + SWAG else ACTIONS + ACTIVITIES

    /** Idea ke shabdon se activity: car se drive, khana se eat. */
    private fun activityFor(lower: String): String? = when {
        listOf("car", "drive", "driving").any { it in lower } -> "drive"
        listOf("eat", "food", "apple", "lunch", "khana").any { it in lower } -> "eat"
        listOf("hop", "jump", "bunny").any { it in lower } -> "hop"
        listOf("swing", "jhoola").any { it in lower } -> "swing"
        "dance" in lower -> "dance"
        else -> null
    }

    /**
     * Aapki apni script. Har line ek scene: "drive: Vroom vroom!" ya sirf "Hello!".
     * Sirf "dance:" likha ho to bina bole naach.
     */
    fun fromScript(script: String, character: String, place: String, title: String): Story? {
        val allowed = actionsFor(character)
        val scenes = script.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_SCENES).map { row ->
            val head = row.substringBefore(':', "").trim().lowercase()
            val known = head.isNotEmpty() && head in allowed
            val line = (if (known) row.substringAfter(':') else row).trim().take(MAX_LINE)
            val action = if (known) head else activityFor(line.lowercase()) ?: "happy"
            Scene(action, place, line, if (line.isEmpty()) 4.0 else 3.0)
        }
        if (scenes.isEmpty()) return null
        return Story(title.ifBlank { "My Short" }.take(60), "#kids #cartoon #shorts", character, scenes)
    }

    fun parse(json: String, character: String): Story? {
        // Jawab ke aage-peeche kuch likha ho to bhi sirf { } wala hissa lo
        val clean = BrainLogic.cleanJson(json)
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = try {
            JSONObject(clean.substring(start, end + 1))
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

    /** Gemini na chale to bhi video bane: idea se saadi kahani. */
    fun offline(idea: String, character: String, seconds: Int): Story {
        val lower = idea.lowercase()
        val place = PLACES.firstOrNull { it in lower } ?: when {
            "sea" in lower || "sand" in lower -> "beach"
            "moon" in lower || "star" in lower || "sleep" in lower -> "night"
            "song" in lower || "sing" in lower || "show" in lower -> "stage"
            "home" in lower || "house" in lower -> "room"
            "rocket" in lower || "planet" in lower -> "space"
            "ice" in lower || "winter" in lower -> "snow"
            else -> "park"
        }
        val who = if (character == "baby") "Baby" else "Chhotu"
        val doing = activityFor(lower)?.takeIf { it in actionsFor(character) } ?: "dance"
        val beats = listOf(
            Scene("happy", place, "Hello friends! I am $who!", 3.0),
            Scene(doing, place, idea.trim().replaceFirstChar { it.uppercase() }.take(MAX_LINE), 4.0),
            Scene("excited", place, "Today is a fun day in the $place!", 3.0),
            Scene(doing, place, "", 4.0),
            Scene("surprised", place, "Wow! Did you see that?", 3.0),
            Scene("dance", place, "Dance, dance, dance!", 4.0),
            Scene("thinking", place, "What shall we do next?", 3.0),
            Scene("happy", place, "Clap your hands with me!", 3.0),
            Scene("dance", place, "", 4.0),
            Scene("excited", place, "That was so much fun!", 3.0),
            Scene("happy", place, "Bye bye! See you soon!", 3.0)
        )
        val picked = ArrayList<Scene>()
        var total = 0.0
        for (scene in beats.dropLast(1)) {
            if (total + scene.seconds + beats.last().seconds > seconds) break
            picked.add(scene)
            total += scene.seconds
        }
        picked.add(beats.last())
        val title = idea.trim().replaceFirstChar { it.uppercase() }.take(40).ifEmpty { "Fun Time" }
        return Story(title, "#kids #cartoon #shorts", character, picked)
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
