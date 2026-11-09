package com.sahayak.app

import org.json.JSONException
import org.json.JSONObject

data class Scene(
    val action: String, val place: String, val line: String, val seconds: Double,
    val pop: String = "", val effect: String = "", val close: Boolean = false
)

data class Story(val title: String, val caption: String, val character: String, val scenes: List<Scene>, val music: Boolean = true)

/** Idea se bani kahani ko jaanchna. AI galat naam de to bhi video bane. */
object StoryLogic {
    val ACTIONS = listOf("idle", "happy", "excited", "sad", "surprised", "sleepy", "grumpy", "thinking", "dance")
    val SWAG = listOf("swag", "swagidle")
    val ACTIVITIES = listOf("hop", "eat", "drive", "swing", "feast", "guitar")
    val EFFECTS = listOf("hearts", "sparkles", "stars", "bubbles")
    private const val MAX_POP = 16
    private val TAG = Regex("\\{([^}]*)\\}")
    val PLACES = listOf("jungle", "beach", "night", "stage", "room", "park", "space", "snow", "sweet", "kitchen")
    private const val MIN_SECONDS = 2.0
    private const val MAX_SECONDS = 8.0
    private const val MAX_SCENES = 12
    private const val MAX_LINE = 120
    private const val MAX_TOTAL = 60.0

    fun actionsFor(character: String) = if (character == "chhotu") ACTIONS + SWAG else ACTIONS + ACTIVITIES

    /** Idea ke shabdon se activity: car se drive, khana se eat. */
    private fun activityFor(lower: String): String? = when {
        listOf("guitar", "sing", "song", "music").any { it in lower } -> "guitar"
        listOf("car", "drive", "driving").any { it in lower } -> "drive"
        listOf("mango", "high chair", "feed", "eat", "food", "lunch", "khana", "yum").any { it in lower } -> "feast"
        "apple" in lower -> "eat"
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
            val tags = TAG.findAll(row).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
            val plain = TAG.replace(row, "").trim()
            val head = plain.substringBefore(':', "").trim().lowercase()
            val known = head.isNotEmpty() && head in allowed
            val line = (if (known) plain.substringAfter(':') else plain).trim().take(MAX_LINE)
            val action = if (known) head else activityFor(line.lowercase()) ?: "happy"
            val effect = tags.firstOrNull { it.lowercase() in EFFECTS }?.lowercase().orEmpty()
            val close = tags.any { it.equals("close", ignoreCase = true) }
            val pop = tags.firstOrNull { it.lowercase() !in EFFECTS && !it.equals("close", ignoreCase = true) }.orEmpty().take(MAX_POP)
            Scene(action, place, line, if (line.isEmpty()) 4.0 else 3.0, pop, effect, close)
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
            val effect = o.optString("effect").lowercase().trim().let { if (it in EFFECTS) it else "" }
            scenes.add(Scene(action, place, line, seconds, o.optString("pop").trim().take(MAX_POP), effect, o.optBoolean("close")))
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
        if (doing == "feast") {
            // Khane wali reel: bina bole, close-up, chamak, Yum! aur dil
            val spot = PLACES.firstOrNull { it in lower } ?: "sweet"
            val reel = listOf(
                Scene("feast", spot, "", 3.0, effect = "sparkles"),
                Scene("feast", spot, "", 3.0, close = true),
                Scene("feast", spot, "", 3.0, pop = "Yum!", effect = "hearts", close = true),
                Scene("happy", spot, "", 3.0, pop = "Yummy!", effect = "hearts"),
                Scene("feast", spot, "", 3.0, effect = "stars", close = true)
            )
            val fit = ArrayList<Scene>()
            var used = 0.0
            while (used + 3.0 <= seconds.coerceAtLeast(6)) {
                fit.add(reel[fit.size % reel.size])
                used += 3.0
            }
            return Story(idea.trim().replaceFirstChar { it.uppercase() }.take(40).ifEmpty { "Yummy" }, "#baby #yummy #shorts", character, fit)
        }
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
