package com.sahayak.app

import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Market Watch ke seedhe niyam: bhaav padhna, khabar pehchanna, badlav ginna. Test ho sakte hain. */
object MarketLogic {
    data class Quote(val symbol: String, val name: String, val currency: String, val price: Double, val prevClose: Double?, val timeMs: Long)
    data class Found(val symbol: String, val name: String)
    data class Tag(val kind: String, val mood: String)

    private val TYPES = setOf("EQUITY", "INDEX", "ETF")

    /** Yahoo chart ka jawab: abhi ka bhaav aur pichhle din ka band bhaav. */
    fun parseQuote(json: String): Quote? {
        try {
            val r = JSONObject(json).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = r.getJSONObject("meta")
            val price = meta.optDouble("regularMarketPrice", Double.NaN)
            if (price.isNaN() || price <= 0.0) return null
            val closes = mutableListOf<Double>()
            val arr = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val v = arr.optDouble(i, Double.NaN)
                    if (!v.isNaN() && v > 0.0) closes.add(v)
                }
            }
            val prev = if (closes.size >= 2) closes[closes.size - 2]
            else meta.optDouble("chartPreviousClose", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
            val symbol = meta.optString("symbol")
            val name = meta.optString("longName").ifEmpty { meta.optString("shortName") }.ifEmpty { symbol }
            return Quote(symbol, name, meta.optString("currency").ifEmpty { "INR" }, price, prev, meta.optLong("regularMarketTime", 0L) * 1000L)
        } catch (e: JSONException) {
            return null
        }
    }

    /** Yahoo search ka jawab: naam se share ka code. */
    fun parseSearch(json: String): List<Found> {
        try {
            val a = JSONObject(json).optJSONArray("quotes") ?: return emptyList()
            val out = mutableListOf<Found>()
            for (i in 0 until a.length()) {
                val q = a.optJSONObject(i) ?: continue
                val symbol = q.optString("symbol")
                if (symbol.isEmpty() || q.optString("quoteType") !in TYPES) continue
                out.add(Found(symbol, q.optString("longname").ifEmpty { q.optString("shortname") }.ifEmpty { symbol }))
            }
            return out
        } catch (e: JSONException) {
            return emptyList()
        }
    }

    /** Pehle NSE, phir BSE, phir jo mile. */
    fun best(found: List<Found>): Found? =
        found.firstOrNull { it.symbol.endsWith(".NS") } ?: found.firstOrNull { it.symbol.endsWith(".BO") } ?: found.firstOrNull()

    /** Search na chale to likhe hue naam ko hi code maan kar dekho. */
    fun guessSymbols(typed: String): List<String> {
        val t = typed.trim().uppercase(Locale.ENGLISH)
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("^") || t.contains('.')) return listOf(t.replace(" ", ""))
        val joined = t.replace(Regex("[^A-Z0-9&-]"), "")
        if (joined.isEmpty()) return emptyList()
        return listOf("$joined.NS", "$joined.BO")
    }

    fun googleCode(symbol: String): String? = when {
        symbol.endsWith(".NS") -> symbol.removeSuffix(".NS") + ":NSE"
        symbol.endsWith(".BO") -> symbol.removeSuffix(".BO") + ":BOM"
        else -> null
    }

    private val GOOGLE_PRICE = Regex("data-last-price=\"([0-9]+(?:\\.[0-9]+)?)\"")

    fun parseGooglePrice(html: String): Double? = GOOGLE_PRICE.find(html)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0.0 }

    fun money(price: Double, currency: String): String {
        val sign = when (currency.uppercase(Locale.ENGLISH)) {
            "INR", "" -> "₹"
            "USD" -> "$"
            else -> "$currency "
        }
        return sign + String.format(Locale.ENGLISH, "%,.2f", price)
    }

    fun pct(now: Double, base: Double): Double = if (base > 0.0) (now - base) / base * 100.0 else 0.0

    /** ▲ 1.25% ya ▼ 0.80%. */
    fun pctText(now: Double, base: Double): String {
        val p = pct(now, base)
        val arrow = if (p > 0.004) "▲ " else if (p < -0.004) "▼ " else ""
        return arrow + String.format(Locale.ENGLISH, "%.2f", Math.abs(p)) + "%"
    }

    private val KINDS = listOf(
        "Trade war" to Regex("""\b(tariffs?|trade war|trade deal|sanctions?|embargo|export ban|import duty|anti-dumping)\b"""),
        "Results" to Regex("""\b(q[1-4]|results?|earnings|net profit|profit|revenue|ebitda|pat|dividend|guidance|net loss)\b"""),
        "Management" to Regex("""\b(ceo|cfo|md|chairman|chairperson|resigns?|resignation|steps? down|appoints?|appointed|quits|sacked|ousted)\b"""),
        "Deal" to Regex("""\b(orders?|contract|acquisition|acquires?|merger|stake|buyback|bags|ipo|demerger|joint venture|partnership|qip|fund ?raise|takeover|deal)\b"""),
        "Policy" to Regex("""\b(rbi|sebi|gst|repo rate|budget|fed|rate cut|rate hike|interest rates?|pli|ban|penalty|probe|raid|usfda|fda|fraud|default)\b"""),
        "Market" to Regex("""\b(sensex|nifty|crash|crashes|rally|rallies|sell-?off|markets)\b""")
    )

    private val GOOD = setOf(
        "surges", "surge", "jumps", "jump", "rises", "rise", "soars", "gains", "gain", "rallies", "rally", "climbs", "beats", "wins", "bags",
        "record", "upgrade", "upgrades", "approval", "approves", "growth", "grows", "up", "high", "strong", "doubles", "zooms", "boost"
    )
    private val BAD = setOf(
        "falls", "fall", "plunges", "plunge", "slumps", "drops", "drop", "tanks", "crashes", "crash", "loss", "losses", "misses", "resigns",
        "fraud", "probe", "raid", "downgrade", "downgrades", "penalty", "ban", "war", "default", "warning", "declines", "decline", "slips",
        "sinks", "down", "low", "weak", "cuts", "tariff", "tariffs", "sanctions", "quits", "selloff", "threat", "threatens", "fine", "dips"
    )
    private val LETTERS = Regex("[a-z]+")

    /** Khabar kis tarah ki hai aur shabdon se achhi lagti hai ya buri. Kaam ki na ho to null. */
    fun classify(title: String): Tag? {
        val lower = title.lowercase(Locale.ENGLISH)
        val kind = KINDS.firstOrNull { it.second.containsMatchIn(lower) }?.first ?: return null
        val words = LETTERS.findAll(lower).map { it.value }.toList()
        val score = words.count { it in GOOD } - words.count { it in BAD }
        return Tag(kind, if (score > 0) "Good" else if (score < 0) "Bad" else "")
    }

    /** Google News heading ke aakhir mein " - Akhbaar" hota hai. */
    fun cleanTitle(title: String): String = title.substringBeforeLast(" - ", title).trim()

    fun publisher(title: String): String = if (title.contains(" - ")) title.substringAfterLast(" - ").trim() else ""

    fun key(title: String): Set<String> = BrainLogic.tokens(cleanTitle(title)).filter { it.length > 2 }.toSet()

    /** Ek hi khabar alag akhbaar se: zyada tar shabd same. */
    fun similar(a: Set<String>, b: Set<String>): Boolean {
        val small = minOf(a.size, b.size)
        if (small < 3) return a == b
        return a.count { it in b } >= small * 0.7
    }

    private val TAIL = Regex("(?i)\\b(limited|ltd\\.?|corporation|corp\\.?|inc\\.?|plc)\\b")

    /** Khabar dhoondhne ke liye company ka chhota naam. */
    fun companyQuery(name: String): String = TAIL.replace(name, " ").replace(Regex("[^A-Za-z0-9& ]"), " ").replace(Regex(" +"), " ").trim()

    fun pubTime(text: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH).parse(text)?.time ?: 0L
    } catch (e: java.text.ParseException) {
        0L
    }

    fun dayKey(ms: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { timeZone = zone }.format(Date(ms))

    /** Agli baar report kab: aaj ka samay nikal gaya ho to kal. */
    fun nextReportAt(now: Long, hour: Int, minute: Int, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    fun clock(hour: Int, minute: Int): String {
        val h = if (hour % 12 == 0) 12 else hour % 12
        return h.toString() + ":" + String.format(Locale.ENGLISH, "%02d", minute) + (if (hour < 12) " AM" else " PM")
    }

    // ---------- Khabar ki gehrai ----------

    data class Num(val what: String, val before: Double, val after: Double, val unit: String, val period: String)
    data class Depth(val company: String, val level: Int, val what: String, val numbers: List<Num>, val past: List<String>, val why: String, val unsure: String)

    /** AI ke jawab mein se JSON wala hissa. */
    fun jsonBlock(text: String): String {
        val a = text.indexOf('{')
        val b = text.lastIndexOf('}')
        return if (a >= 0 && b > a) text.substring(a, b + 1) else ""
    }

    fun parseDepth(text: String): Depth? {
        try {
            val o = JSONObject(jsonBlock(text))
            val numbers = mutableListOf<Num>()
            val arr = o.optJSONArray("numbers")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val n = arr.optJSONObject(i) ?: continue
                    val before = n.optDouble("before", Double.NaN)
                    val after = n.optDouble("after", Double.NaN)
                    if (before.isNaN() || after.isNaN() || before.isInfinite() || after.isInfinite()) continue
                    numbers.add(Num(n.optString("what"), before, after, n.optString("unit"), n.optString("period")))
                }
            }
            val past = mutableListOf<String>()
            val old = o.optJSONArray("past")
            if (old != null) for (i in 0 until old.length()) old.optString(i).trim().takeIf { it.isNotEmpty() }?.let { past.add(it) }
            val what = o.optString("what").trim()
            if (what.isEmpty() && numbers.isEmpty()) return null
            return Depth(o.optString("company").trim(), o.optInt("level", 3).coerceIn(1, 5), what, numbers.take(6), past.take(5),
                o.optString("why").trim(), o.optString("unsure").trim())
        } catch (e: JSONException) {
            return null
        }
    }

    fun levelWord(level: Int): String = when (level) {
        1 -> "Routine"
        2 -> "Small"
        3 -> "Worth noting"
        4 -> "Big"
        else -> "Very big"
    }

    /** 25 aur "₹ crore" se "₹25 crore". */
    fun numText(value: Double, unit: String): String {
        val n = String.format(Locale.ENGLISH, "%,.2f", value).removeSuffix("0").removeSuffix("0").removeSuffix(".")
        val u = unit.trim()
        return when {
            u.isEmpty() -> n
            u == "%" -> "$n%"
            u.startsWith("₹") -> ("₹" + n + " " + u.removePrefix("₹").trim()).trim()
            u.startsWith("$") -> ("$" + n + " " + u.removePrefix("$").trim()).trim()
            else -> "$n $u"
        }
    }

    /** Kitna bada badlav. Jodi: (likhne ke liye, +1 badha / -1 ghata / 0 barabar). */
    fun jumpText(before: Double, after: Double): Pair<String, Int> {
        fun p(v: Double) = String.format(Locale.ENGLISH, "%,.0f", v) + "%"
        return when {
            before > 0 && after > 0 -> {
                val ratio = after / before
                when {
                    ratio >= 2.0 -> (String.format(Locale.ENGLISH, "%.1f", ratio) + " times bigger  (▲ " + p((ratio - 1) * 100) + ")") to 1
                    ratio > 1.0005 -> ("▲ " + String.format(Locale.ENGLISH, "%.1f", (ratio - 1) * 100) + "% more") to 1
                    ratio < 0.9995 -> ("▼ " + String.format(Locale.ENGLISH, "%.1f", (1 - ratio) * 100) + "% less") to -1
                    else -> "No change" to 0
                }
            }
            before < 0 && after > 0 -> "Loss turned into profit" to 1
            before > 0 && after < 0 -> "Profit turned into loss" to -1
            before < 0 && after < 0 -> if (after > before) ("Loss became smaller") to 1 else if (after < before) ("Loss became bigger") to -1 else "No change" to 0
            before == 0.0 && after > 0 -> "New, was zero before" to 1
            else -> "" to 0
        }
    }

    private val NOT_NAME = setOf(
        "q1", "q2", "q3", "q4", "ceo", "cfo", "md", "net", "share", "shares", "stock", "stocks", "profit", "results", "result", "bags", "wins",
        "to", "gets", "reports", "posts", "board", "india", "indian", "sensex", "nifty", "rbi", "sebi", "gst", "why", "how", "what", "these", "top", "breaking"
    )

    /** Heading ki shuruaat ke bade akshar wale shabd: aksar company ka naam. */
    fun companyGuess(title: String): String {
        val out = mutableListOf<String>()
        for (word in cleanTitle(title).split(' ')) {
            val w = word.trim(',', ':', ';', '\'', '"', '(', ')')
            if (w.isEmpty() || out.size >= 4) break
            val plain = w.lowercase(Locale.ENGLISH).removeSuffix("'s").removeSuffix("’s")
            if (plain in NOT_NAME || !(w[0].isUpperCase() || w == "&")) break
            out.add(w.removeSuffix("'s").removeSuffix("’s"))
            if (w.endsWith("'s") || w.endsWith("’s")) break
        }
        return out.joinToString(" ").trimEnd('&', ' ')
    }

    /** Purani khabrein dhoondhne ke liye sawaal. */
    fun relatedQuery(title: String, kind: String): String {
        val company = companyGuess(title)
        if (company.isEmpty()) return BrainLogic.tokens(cleanTitle(title)).filter { it.length > 2 }.take(6).joinToString(" ")
        val about = when (kind) {
            "Results" -> "results profit"
            "Management" -> "CEO"
            "Deal" -> "order deal"
            "Trade war" -> "tariff"
            else -> ""
        }
        return "$company $about".trim()
    }

    private val AMOUNT = Regex("""(?i)([0-9][0-9,]*(?:\.[0-9]+)?)\s*(lakh crore|crore|cr|lakh|billion|bn|million|mn)\b""")

    private fun inCrore(value: Double, unit: String): Double? = when (unit.lowercase(Locale.ENGLISH)) {
        "lakh crore" -> value * 100_000
        "crore", "cr" -> value
        "lakh" -> value / 100
        else -> null
    }

    /** Heading mein "from Rs 25 crore to Rs 300 crore" jaisa ho to khud nikal lo. */
    fun headlineJump(title: String): Num? {
        val found = AMOUNT.findAll(title).toList()
        if (found.size < 2) return null
        val from = title.lowercase(Locale.ENGLISH).indexOf("from ")
        if (from < 0) return null
        val beforeMatch = found.firstOrNull { it.range.first > from } ?: return null
        val afterMatch = found.firstOrNull { it !== beforeMatch } ?: return null
        fun crore(m: MatchResult) = m.groupValues[1].replace(",", "").toDoubleOrNull()?.let { inCrore(it, m.groupValues[2]) }
        val before = crore(beforeMatch) ?: return null
        val after = crore(afterMatch) ?: return null
        return Num("", before, after, "₹ crore", "")
    }
}
