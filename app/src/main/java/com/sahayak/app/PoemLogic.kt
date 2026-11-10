package com.sahayak.app

import kotlin.math.abs
import kotlin.math.sqrt

/** Poem ko video ki kahani banana: har line ek scene, shabdon se jagah, activity aur chamak. */
object PoemLogic {
    const val MAX_LINES = 16
    private const val MAX_LINE = 80
    private const val LINE_SECONDS = 2.0
    private const val INTRO_SECONDS = 1.2
    private const val END_SECONDS = 3.0
    private const val SECONDS_PER_LINE = 2.7
    private val ASKS = listOf("poem", "rhyme", "kavita", "recite", "nursery")
    private val WORD = Regex("[a-z']+")

    private val STAR = listOf(
        "Little star up in the sky,", "Shining bright and oh so high.",
        "When the night is dark and deep,", "You watch over while I sleep.",
        "Twinkle here and twinkle there,", "Sprinkle light through evening air.",
        "When the morning sun says hi,", "Little star, you wave goodbye."
    )
    private val RAIN = listOf(
        "Pitter patter, drops of rain,", "Tapping on my window pane.",
        "Puddles splash beneath my feet,", "Dancing down the shiny street.",
        "Clouds are grey but I don't mind,", "Rainbows are not far behind.",
        "Pitter patter, hear it sing,", "Rain makes flowers bloom in spring."
    )
    private val SUN = listOf(
        "Good morning, sun, so warm and bright,", "You chase away the sleepy night.",
        "The birds all sing, the flowers wake,", "There's so much fun for us to make.",
        "I stretch up tall, I touch my toes,", "I wash my face, I wipe my nose.",
        "Hello, new day, I'm glad you're here,", "Let's fill the world with happy cheer."
    )
    private val ANIMALS = listOf(
        "My little cat says meow, meow, meow,", "My little dog says bow, wow, wow.",
        "The duck says quack, the cow says moo,", "The owl at night says who, who, who.",
        "The lion roars, the birdie sings,", "The bee goes buzzing on its wings.",
        "So many friends, so many sounds,", "We sing along as we go round."
    )
    private val CAR = listOf(
        "Beep, beep, beep, my little car,", "We are going very far.",
        "Round the corner, up the hill,", "Wheels are spinning, never still.",
        "Red light says that we must stop,", "Green light says go, hop, hop, hop.",
        "Beep, beep, beep, we're home at last,", "Little car, you went so fast."
    )
    private val HAPPY = listOf(
        "Clap your hands and stomp your feet,", "Dancing to a happy beat.",
        "Jump up high and touch the sky,", "Wave your hands and say hi, hi.",
        "Turn around and wiggle too,", "I am happy, so are you.",
        "One, two, three, let's shout hooray,", "What a lovely, sunny day."
    )

    private fun words(text: String) = WORD.findAll(text.lowercase()).map { it.value }.toSet()

    /** Idea mein poem maangi hai? */
    fun wantsPoem(idea: String) = words(idea).any { it in ASKS || it.removeSuffix("s") in ASKS }

    /** Kitni lines: 10 sec mein 4, 30 mein 10, 60 mein 16. Hamesha jodi mein. */
    fun lineCount(seconds: Int) = ((seconds / SECONDS_PER_LINE).toInt() / 2 * 2).coerceIn(4, MAX_LINES)

    /** Likhi ya AI ki poem ko saaf lines mein todna. Numbering, bullets aur khaali lines hatao. */
    fun lines(text: String): List<String> = text.lines()
        .map { it.trim().replace(Regex("^(\\d+[.)]|[-*•])\\s*"), "").trim('"', '*', ' ') }
        .filter { row -> row.any { it.isLetter() } }
        .map { it.take(MAX_LINE) }
        .take(MAX_LINES)

    /** Internet ya key na ho to bhi poem mile: idea ke shabdon se Sahayak ki apni poem. */
    fun builtIn(idea: String, count: Int): List<String> {
        val w = words(idea)
        fun has(vararg keys: String) = keys.any { it in w }
        val first = when {
            has("star", "stars", "moon", "night", "twinkle", "sky") -> STAR
            has("rain", "rainy", "cloud", "clouds", "water", "umbrella") -> RAIN
            has("sun", "morning", "day", "wake") -> SUN
            has("cat", "dog", "animal", "animals", "zoo", "cow", "duck", "lion", "bird") -> ANIMALS
            has("car", "drive", "driving", "bus", "road") -> CAR
            else -> HAPPY
        }
        val second = if (first === HAPPY) SUN else HAPPY
        return (first + second).take(count.coerceIn(2, MAX_LINES))
    }

    private fun placeFor(w: Set<String>): String {
        fun has(vararg keys: String) = keys.any { it in w }
        return StoryLogic.PLACES.firstOrNull { it in w } ?: when {
            has("star", "stars", "moon", "twinkle", "sleep", "dream", "dreams") -> "night"
            has("sea", "sand", "waves", "ocean", "fish") -> "beach"
            has("rocket", "planet", "planets", "astronaut") -> "space"
            has("ice", "winter", "snowman", "snowy") -> "snow"
            has("tiger", "lion", "monkey", "elephant", "forest") -> "jungle"
            has("mango", "sweet", "candy", "cake", "yummy") -> "sweet"
            has("guitar", "song", "sing", "show") -> "stage"
            has("home", "house", "bed", "mummy", "mama", "papa") -> "room"
            else -> "park"
        }
    }

    private fun effectFor(w: Set<String>): String {
        fun has(vararg keys: String) = keys.any { it in w }
        return when {
            has("star", "stars", "twinkle", "moon", "shining", "shine") -> "stars"
            has("love", "heart", "hug", "kiss", "mummy", "mama", "papa", "friend", "friends") -> "hearts"
            has("rain", "water", "splash", "sea", "fish", "bubble", "bubbles", "puddles") -> "bubbles"
            has("magic", "rainbow", "rainbows", "hooray", "wow") -> "sparkles"
            else -> ""
        }
    }

    private fun actionFor(w: Set<String>, allowed: List<String>, index: Int): String {
        fun has(vararg keys: String) = keys.any { it in w }
        val wish = when {
            has("car", "drive", "driving", "beep", "wheels") -> "drive"
            has("guitar", "sing", "song", "songs", "music") -> "guitar"
            has("jump", "hop", "bunny", "rabbit") -> "hop"
            has("swing", "jhoola") -> "swing"
            has("mango", "yummy", "yum") -> "feast"
            has("apple") -> "eat"
            has("dance", "dancing", "wiggle", "clap") -> "dance"
            has("sleep", "sleepy", "dream", "dreams", "goodnight") -> "sleepy"
            has("wow", "surprise", "look") -> "surprised"
            has("why", "wonder", "think") -> "thinking"
            else -> ""
        }
        return if (wish in allowed) wish else listOf("happy", "excited")[index % 2]
    }

    /**
     * Poem se kahani. Har line ek scene hai jisme character line bolta hai.
     * seconds lamba ho to shuru mein title aur aakhir mein naach bhi judta hai.
     */
    fun fromPoem(rows: List<String>, character: String, place: String?, title: String, seconds: Int): Story? {
        val clean = rows.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_LINES)
        if (clean.isEmpty()) return null
        val spot = place ?: placeFor(words(clean.joinToString(" ")))
        val allowed = StoryLogic.actionsFor(character)
        val scenes = ArrayList<Scene>()
        val roomy = clean.size * SECONDS_PER_LINE + INTRO_SECONDS + END_SECONDS <= seconds
        if (roomy) scenes.add(Scene("happy", spot, "", INTRO_SECONDS))
        clean.forEachIndexed { i, row ->
            val w = words(row)
            val effect = effectFor(w).ifEmpty { if (i == clean.lastIndex) "sparkles" else "" }
            scenes.add(Scene(actionFor(w, allowed, i), spot, row, LINE_SECONDS, effect = effect))
        }
        if (roomy) scenes.add(Scene("dance", spot, "", END_SECONDS, pop = "Yay!", effect = "stars"))
        val name = title.trim().ifEmpty { clean[0].trim(',', '.', '!', '?', ';', ':', ' ') }.take(40)
        return Story(name, "#poem #kidspoem #nurseryrhymes #shorts", character, scenes, poem = true)
    }

    /**
     * Har shabd kab khatam hota hai, 0 se 1 ke beech. Lambe shabd aur comma ke baad zyada samay.
     * Isse bolte waqt wahi shabd chamakta hai.
     */
    fun wordEnds(line: String): List<Double> {
        val parts = line.split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val weights = parts.map { word ->
            val letters = word.count { it.isLetterOrDigit() }
            val pause = if (word.last() in ",.;:!?") 2.0 else 0.0
            1.5 + letters + pause
        }
        val total = weights.sum()
        var run = 0.0
        return weights.map { run += it; run / total }
    }

    /** fraction 0 se 1: abhi kaunsa shabd bola ja raha hai. */
    fun activeWord(ends: List<Double>, fraction: Double): Int {
        if (ends.isEmpty()) return -1
        val i = ends.indexOfFirst { fraction < it }
        return if (i < 0) ends.lastIndex else i
    }
}

/** Awaaz ke hisaab se muh: zor ki awaaz par khula, chup par band. */
object LipSync {
    /** Talk clip ke har frame mein muh kitna khula hai, 0 band se 1 poora khula. Teeno talk clip mein same chaal hai. */
    val OPEN = floatArrayOf(
        1.0f, 0.97f, 0.9f, 0.77f, 0.61f, 0.47f, 0.42f, 0.43f, 0.5f, 0.54f, 0.54f, 0.51f,
        0.48f, 0.51f, 0.61f, 0.72f, 0.84f, 0.92f, 0.93f, 0.85f, 0.66f, 0.43f, 0.21f, 0.06f,
        0.0f, 0.04f, 0.17f, 0.34f, 0.54f, 0.72f, 0.78f, 0.83f, 0.89f, 0.95f, 1.0f, 1.0f
    )
    private const val QUIET = 0.12f
    private const val BACK = 2
    private const val AHEAD = 3
    private const val MAX_DRIFT = 4
    private const val JUMP_COST = 0.02f

    /** Video ke har frame ke liye awaaz ka zor, 0 se 1. */
    fun levels(samples: ShortArray, rate: Int, fps: Int): FloatArray {
        val per = (rate / fps).coerceAtLeast(1)
        val count = (samples.size + per - 1) / per
        val raw = FloatArray(count) { f ->
            var sum = 0.0
            val from = f * per
            val to = minOf(samples.size, from + per)
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            sqrt(sum / (to - from).coerceAtLeast(1)).toFloat()
        }
        val peak = raw.maxOrNull() ?: 0f
        if (peak <= 0f) return FloatArray(count)
        return FloatArray(count) { sqrt(raw[it] / peak) }
    }

    private fun gap(a: Int, b: Int): Int {
        val d = abs(a - b) % OPEN.size
        return minOf(d, OPEN.size - d)
    }

    /**
     * Agla talk frame. Pichhle frame ke aas-paas hi chunta hai taaki haath na jhatke,
     * aur time wale frame (clock) se MAX_DRIFT se zyada door nahi jaata.
     * Clip mein muh poora band sirf palak jhapakte waqt hota hai, isliye chup par muh chhota hota hai, poora band nahi.
     */
    fun next(prev: Int, clock: Int, level: Float): Int {
        val n = OPEN.size
        val now = clock % n
        val want = if (level < QUIET) 0f else 0.35f + 0.65f * level.coerceAtMost(1f)
        var best = now
        var bestCost = Float.MAX_VALUE
        for (step in -BACK..AHEAD) {
            val at = ((prev + step) % n + n) % n
            if (gap(at, now) > MAX_DRIFT) continue
            val cost = abs(OPEN[at] - want) + JUMP_COST * abs(step - 1)
            if (cost < bestCost) {
                bestCost = cost
                best = at
            }
        }
        return best
    }
}
