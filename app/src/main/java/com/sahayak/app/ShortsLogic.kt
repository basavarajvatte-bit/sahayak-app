package com.sahayak.app

import java.util.Calendar

/** YouTube screen pehchanne aur upload ka samay nikalne ke seedhe niyam. */
object ShortsLogic {
    const val MAX_CAPTION = 100
    private val FINAL_BUTTONS = listOf("upload short", "upload video", "upload")
    private const val SHORT_LABEL = 40
    private const val DAY_MINUTES = 1_440

    fun norm(text: String): String = text.lowercase()
        .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"').trim()

    private fun madeForKids(label: String) = "made for kids" in label

    fun yesOption(label: String) = label.startsWith("yes") && madeForKids(label)

    fun noOption(label: String) = label.startsWith("no") && madeForKids(label)

    fun isAudiencePage(labels: List<String>) = labels.any { yesOption(it) } && labels.any { noOption(it) }

    fun isVisibilityPage(labels: List<String>) =
        listOf("public", "unlisted", "private").all { word -> labels.any { it.startsWith(word) } }

    private val MEDIA_NAME = Regex("\\.(mp4|mov|mkv|3gp|webm|avi)(\\s|$)")

    fun isGallery(labels: List<String>) = "create video" in labels || labels.any { MEDIA_NAME.containsMatchIn(it) }

    fun isHome(labels: List<String>) = "home" in labels && "create" in labels

    fun isCamera(labels: List<String>) = "add" in labels && "short" in labels

    /** Poora file naam milna zaroori: robot.mp4 ko 10_robot.mp4 par nahi dabana. */
    fun sameVideo(label: String, fileName: String): Boolean {
        val wanted = norm(fileName)
        return wanted.isNotEmpty() && Regex("(^|\\s)" + Regex.escape(wanted) + "(\\s|$)").containsMatchIn(label)
    }

    fun finalButton(labels: List<String>): String? = FINAL_BUTTONS.firstOrNull { it in labels }

    fun isDetailsPage(labels: List<String>) = "add details" in labels || finalButton(labels) != null

    fun audienceUnset(labels: List<String>) = labels.any { "select audience" in it && it.length <= SHORT_LABEL }

    /** Audience row par ulta jawab pehle se chuna ho. Niche ki COPPA line ginti mein nahi. */
    fun audienceWrong(labels: List<String>, kids: Boolean): Boolean = labels.any { label ->
        label.length <= SHORT_LABEL && !label.startsWith("what") && !label.endsWith("?") && madeForKids(label) &&
            (if (kids) "not made for kids" in label else "not made for kids" !in label)
    }

    fun visibilityShown(labels: List<String>, wanted: String) =
        labels.any { it == wanted || ("visibility" in it && wanted in it) }

    fun visibilityRow(labels: List<String>) = labels.any { "visibility" in it }

    fun limitHit(labels: List<String>) = labels.any { label ->
        "upload limit" in label || "daily limit" in label || ("too many" in label && "upload" in label)
    }

    /** Link, @handle ya naam: tulna ke liye sirf pehchan wala hissa. */
    fun channelKey(channel: String): String {
        val text = channel.trim().trimEnd('/')
        val handle = text.substringAfterLast('@', "").substringBefore('/').substringBefore('?').trim()
        return when {
            handle.isNotEmpty() -> handle
            "://" in text || "youtube.com" in text.lowercase() -> text.substringAfterLast('/').substringBefore('?')
            else -> text
        }
    }

    /** Box mein link aaye to @handle ki tarah rakho. */
    fun channelValue(channel: String): String {
        val text = channel.trim()
        return if ('@' in text || '/' in text) "@" + channelKey(text) else text
    }

    /** Poora shabd milna zaroori: channel "baby" ko caption #baby se match nahi hona chahiye. */
    fun channelShown(labels: List<String>, channel: String): Boolean {
        val wanted = norm(channelKey(channel))
        if (wanted.isEmpty()) return false
        val pattern = Regex("(^|[\\s@])" + Regex.escape(wanted) + "($|\\s)")
        return labels.any { '#' !in it && pattern.containsMatchIn(it) }
    }

    /** 10_Robot_Dance se Robot Dance. */
    fun cleanName(name: String): String = name.substringBeforeLast('.')
        .replace(Regex("^[0-9]+[ _.-]*"), "").replace(Regex("[_-]+"), " ").trim()

    fun caption(name: String, tags: String, useName: Boolean): String {
        val end = tags.trim()
        val start = if (useName) cleanName(name) else ""
        val room = MAX_CAPTION - end.length - 1
        return when {
            start.isEmpty() || room <= 0 -> end.take(MAX_CAPTION)
            end.isEmpty() -> start.take(MAX_CAPTION)
            else -> start.take(room).trim() + " " + end
        }
    }

    /** Din ke upload samay, minute mein, barabar doori par. */
    fun slots(perDay: Int, from: Int, to: Int): List<Int> {
        val start = from.coerceIn(0, DAY_MINUTES - 1)
        val end = to.coerceIn(start, DAY_MINUTES - 1)
        val count = perDay.coerceAtLeast(1)
        if (count == 1 || end == start) return listOf(start)
        return List(count) { start + it * (end - start) / (count - 1) }.distinct()
    }

    /** Is pal ke baad ka agla samay. Chhoote samay ek saath nahi chalte. */
    fun nextSlot(now: Long, perDay: Int, from: Int, to: Int): Long {
        val minutes = slots(perDay, from, to)
        for (dayOffset in 0..1) {
            for (minute in minutes) {
                val at = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, minute / 60)
                    set(Calendar.MINUTE, minute % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (at > now) return at
            }
        }
        return now + DAY_MINUTES * 60_000L
    }
}
