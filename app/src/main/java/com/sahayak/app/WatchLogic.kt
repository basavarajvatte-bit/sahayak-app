package com.sahayak.app

/** Website ka text nikalna aur nayi lines pehchanna. Seedhe niyam, test ho sakte hain. */
object WatchLogic {
    private val DROP = Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>")
    private val BREAK = Regex("(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6]|/a|/td|/marquee|/span)[^>]*>")
    private val TAG = Regex("<[^>]+>")
    private val SPACE = Regex("[ \\t\\u00A0]+")
    private const val MIN_LINE = 4
    private const val MAX_LINE = 300
    val ALERT_WORDS = listOf("registration", "register", "apply", "open", "start", "schedule", "notice", "round", "counselling", "counseling", "date")

    fun lines(html: String): List<String> {
        val text = TAG.replace(BREAK.replace(DROP.replace(html, " "), "\n"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
        return text.lines().map { SPACE.replace(it, " ").trim() }
            .filter { it.length in MIN_LINE..MAX_LINE }.distinct()
    }

    fun keywords(job: String): List<String> = BrainLogic.tokens(job).filter { it.length > 2 || it == "pg" || it == "up" }

    /** Line kitni kaam ki hai: job ke shabd aur registration jaise shabd. */
    fun score(line: String, keys: List<String>): Int {
        val lower = line.lowercase()
        return keys.count { it in lower } + ALERT_WORDS.count { it in lower }
    }

    fun newLines(now: List<String>, before: Set<String>): List<String> = now.filter { it !in before }

    fun important(lines: List<String>, keys: List<String>): List<String> =
        lines.map { it to score(it, keys) }.filter { it.second >= 2 }.sortedByDescending { it.second }.map { it.first }

    data class News(val title: String, val link: String, val date: String)

    private val ITEM = Regex("(?is)<item>(.*?)</item>")
    private fun tag(body: String, name: String): String =
        Regex("(?is)<$name[^>]*>(.*?)</$name>").find(body)?.groupValues?.get(1).orEmpty()
            .replace("<![CDATA[", "").replace("]]>", "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .trim()

    /** Google News RSS se khabrein. */
    fun news(xml: String): List<News> = ITEM.findAll(xml).map { m ->
        val b = m.groupValues[1]
        News(tag(b, "title"), tag(b, "link"), tag(b, "pubDate"))
    }.filter { it.title.isNotEmpty() && it.link.isNotEmpty() }.toList()

    private val START_WORDS = setOf("registration", "register", "apply", "open", "opens", "opened", "reopen", "reopens", "reopened", "start", "starts", "started", "begin", "begins", "began", "resume", "resumes", "resumed", "extended")

    /** Kya ye khabar kaam ki hai: job ke kam se kam 3 shabd (ya sab) aur shuru/registration wala shabd. */
    fun newsHit(title: String, keys: List<String>): Boolean {
        val words = BrainLogic.tokens(title).toSet()
        val need = minOf(3, keys.size)
        return keys.count { it in words } >= need && words.any { it in START_WORDS }
    }
}
