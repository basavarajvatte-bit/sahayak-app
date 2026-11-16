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

    // ---------- Bhaav ki teen jagah se jaanch ----------

    const val TAPE = "Tickertape"
    const val YAHOO = "Yahoo"
    const val GOOGLE = "Google"
    private val TRUST = listOf(TAPE, YAHOO, GOOGLE)

    /** Jaanch ka nateeja: kaunsa bhaav maana, pakka hai ya nahi, aur kyun. */
    data class Verdict(val price: Double, val ok: Boolean, val status: String, val used: String)

    /**
     * Alag-alag jagah ke bhaav milao. Jo sabse zyada jagah aapas mein mile (tolerance % ke andar) wahi sahi.
     * Kam se kam do jagah mile tabhi "pakka". Bhaav Tickertape ka lo agar wo sehmat walon mein hai.
     */
    fun verify(prices: Map<String, Double>, tolerancePct: Double): Verdict? {
        val all = TRUST.mapNotNull { name -> prices[name]?.takeIf { it > 0.0 && !it.isNaN() }?.let { name to it } }
        if (all.isEmpty()) return null
        if (all.size == 1) return Verdict(all[0].second, false, "Only " + all[0].first + " gave a price, so it could not be cross-checked", all[0].first)
        fun near(a: Double, b: Double) = Math.abs(a - b) / minOf(a, b) * 100.0 <= tolerancePct
        val best = all.map { anchor -> all.filter { near(anchor.second, it.second) } }.maxByOrNull { it.size } ?: return null
        if (best.size < 2) {
            return Verdict(all[0].second, false, "Sources do not agree: " + all.joinToString(", ") { it.first + " " + money(it.second, "INR") }, all[0].first)
        }
        val pick = best.first()
        val odd = all.filter { it !in best }
        val status = if (odd.isEmpty()) "Verified by " + best.size + " sources"
        else best.size.toString() + " of " + all.size + " sources agree. " + odd.joinToString(", ") { it.first + " shows " + money(it.second, "INR") }
        return Verdict(pick.second, true, status, pick.first)
    }

    private val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private const val OPEN_MIN = 9 * 60 + 15
    private const val CLOSE_MIN = 15 * 60 + 30

    /** Jaanch ke samay (IST): har ghante, aur band hone ke baad ek aakhri. */
    /** Jaanch ke samay (IST): bazaar khula ho to har 15 minute (9:20 se 3:20), aur band hone ke baad 3:45 par aakhri. */
    private val CHECK_MINUTES = (9 * 60 + 20..15 * 60 + 20 step 15).toList() + (15 * 60 + 45)

    private fun weekday(cal: Calendar) = cal.get(Calendar.DAY_OF_WEEK).let { it != Calendar.SATURDAY && it != Calendar.SUNDAY }

    /** Somvar se shukravar, 9:15 se 3:30 (chhutti ka pata nahi hota). */
    fun marketOpen(ms: Long): Boolean {
        val cal = Calendar.getInstance(IST).apply { timeInMillis = ms }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return weekday(cal) && minute in OPEN_MIN..CLOSE_MIN
    }

    /** Agli jaanch kab. */
    fun nextVerifyAt(now: Long): Long {
        val cal = Calendar.getInstance(IST).apply { timeInMillis = now }
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        for (day in 0..7) {
            if (weekday(cal)) {
                for (m in CHECK_MINUTES) {
                    cal.set(Calendar.HOUR_OF_DAY, m / 60)
                    cal.set(Calendar.MINUTE, m % 60)
                    if (cal.timeInMillis > now) return cal.timeInMillis
                }
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
        }
        return now + 3_600_000L
    }

    /** Kya from aur to ke beech bazaar khula tha (ya abhi khula hai)? Nahi to bhaav wahi band wala hai. */
    fun sessionSince(from: Long, to: Long): Boolean {
        if (to <= from) return marketOpen(to)
        val cal = Calendar.getInstance(IST).apply { timeInMillis = from }
        for (day in 0..14) {
            if (weekday(cal)) {
                val open = (cal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 15); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val close = open + (CLOSE_MIN - OPEN_MIN) * 60_000L
                if (open < to && close > from) return true
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            if (cal.timeInMillis > to) return false
        }
        return true
    }

    /** Tickertape ka seedha bhaav: sid se (bhaav, aaj ka badlav rupaye mein). */
    fun parseTapeQuotes(json: String): Map<String, Pair<Double, Double?>> {
        val out = mutableMapOf<String, Pair<Double, Double?>>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    val sid = str(v, "sid")
                    val price = v.optDouble("price", Double.NaN).let { if (it.isNaN()) v.optDouble("lp", Double.NaN) else it }
                    if (sid.isNotEmpty() && !price.isNaN() && price > 0.0) {
                        out[sid] = price to v.optDouble("change", Double.NaN).takeIf { !it.isNaN() }
                    }
                    val keys = v.keys()
                    while (keys.hasNext()) walk(v.opt(keys.next()))
                }
                is org.json.JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        try {
            walk(JSONObject(json))
        } catch (e: JSONException) {
            return emptyMap()
        }
        return out
    }

    /** AI ke jawab mein se bhaav. */
    fun parseAiPrice(text: String): Double? = try {
        JSONObject(jsonBlock(text)).optDouble("price", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
    } catch (e: JSONException) {
        null
    }

    // ---------- Locked shares ke group ----------

    private val LOCK_FROM = Regex("(?i)^(smallcap|midcap|largecap) green scorecard: ([0-9]) of ([0-9])")

    /** "Smallcap green scorecard: 5 of 6 green" se (Smallcap, 5). Green list se na aaya ho to null. */
    fun lockGroup(fromTitle: String): Pair<String, Int>? {
        val m = LOCK_FROM.find(fromTitle.trim()) ?: return null
        val cap = m.groupValues[1].lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }
        return cap to m.groupValues[2].toInt()
    }

    // ---------- Chart ----------

    /** Chart ka ek button: naam, Yahoo ka range aur har bindu ka antar. */
    data class Span(val label: String, val range: String, val interval: String)

    val SPANS = listOf(
        Span("1D", "1d", "5m"), Span("1W", "5d", "15m"), Span("1M", "1mo", "1d"),
        Span("1Y", "1y", "1d"), Span("5Y", "5y", "1wk"), Span("Max", "max", "1mo")
    )

    /** Chart ke bindu. prev = pichhle din ka band bhaav (sirf 1D mein kaam ka). */
    class Series(
        val times: LongArray, val values: DoubleArray, val high: Double, val low: Double, val prev: Double?, val price: Double, val currency: String,
        val source: String = "Yahoo Finance"
    )

    /** Tickertape ke chart ka samay: 1d, 1w, 1mo, 1y, 5y, max. */
    fun tapeDuration(span: Span): String = when (span.label) {
        "1D" -> "1d"
        "1W" -> "1w"
        "1M" -> "1mo"
        "1Y" -> "1y"
        "5Y" -> "5y"
        else -> "max"
    }

    private fun tapeTime(v: Any?): Long = when (v) {
        is Number -> v.toLong().let { if (it < 100_000_000_000L) it * 1000L else it }
        is String -> try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(v)?.time ?: 0L
        } catch (e: java.text.ParseException) {
            0L
        }
        else -> 0L
    }

    /**
     * Tickertape ke apne chart ke bindu, taaki graph aur Returns unki app jaise hi aayein.
     * "points" naam ki list jisme har bindu ka samay (ts) aur bhaav (lp) ho.
     */
    fun parseTapeSeries(json: String, prev: Double?): Series? {
        var points: org.json.JSONArray? = null
        fun walk(v: Any?, key: String) {
            if (points != null) return
            when (v) {
                is JSONObject -> {
                    val keys = v.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        walk(v.opt(k), k)
                    }
                }
                is org.json.JSONArray -> {
                    val first = v.optJSONObject(0)
                    if (key == "points" && first != null && first.has("lp")) points = v else for (i in 0 until v.length()) walk(v.opt(i), key)
                }
            }
        }
        try {
            walk(JSONObject(json), "")
        } catch (e: JSONException) {
            return null
        }
        val list = points ?: return null
        val times = mutableListOf<Long>()
        val values = mutableListOf<Double>()
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val v = p.optDouble("lp", Double.NaN)
            val t = tapeTime(p.opt("ts"))
            if (v.isNaN() || v <= 0.0 || t <= 0L) continue
            times.add(t)
            values.add(v)
        }
        if (values.size < 2) return null
        return Series(times.toLongArray(), values.toDoubleArray(), values.maxOrNull() ?: 0.0, values.minOrNull() ?: 0.0, prev, values.last(), "INR", "Tickertape")
    }

    /** Yahoo chart ka jawab: samay ke saath bhaav. Khaali bindu chhod do. */
    fun parseSeries(json: String): Series? {
        try {
            val r = JSONObject(json).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = r.getJSONObject("meta")
            val stamps = r.optJSONArray("timestamp") ?: return null
            val quote = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return null
            val closes = quote.optJSONArray("close") ?: return null
            val highs = quote.optJSONArray("high")
            val lows = quote.optJSONArray("low")
            val times = mutableListOf<Long>()
            val values = mutableListOf<Double>()
            var high = Double.NEGATIVE_INFINITY
            var low = Double.POSITIVE_INFINITY
            for (i in 0 until minOf(stamps.length(), closes.length())) {
                val v = closes.optDouble(i, Double.NaN)
                if (v.isNaN() || v <= 0.0) continue
                times.add(stamps.optLong(i) * 1000L)
                values.add(v)
                val h = highs?.optDouble(i, Double.NaN) ?: Double.NaN
                val l = lows?.optDouble(i, Double.NaN) ?: Double.NaN
                high = maxOf(high, if (h.isNaN() || h <= 0.0) v else h)
                low = minOf(low, if (l.isNaN() || l <= 0.0) v else l)
            }
            if (values.size < 2) return null
            val prev = meta.optDouble("chartPreviousClose", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                ?: meta.optDouble("previousClose", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
            val price = meta.optDouble("regularMarketPrice", Double.NaN).takeIf { !it.isNaN() && it > 0.0 } ?: values.last()
            return Series(times.toLongArray(), values.toDoubleArray(), high, low, prev, price, meta.optString("currency").ifEmpty { "INR" })
        } catch (e: JSONException) {
            return null
        }
    }

    /** Is range ka badlav kisse ginna hai: 1D mein pichhle din ke band bhaav se, baaki mein range ke pehle bhaav se. */
    fun spanBase(series: Series, span: Span): Double = if (span.range == "1d" && series.prev != null) series.prev else series.values.first()

    /** "▲ 3.45% (+74.50)" */
    fun moveText(now: Double, base: Double): String {
        val diff = now - base
        val sign = if (diff > 0.004) "+" else if (diff < -0.004) "-" else ""
        return pctText(now, base) + " (" + sign + String.format(Locale.ENGLISH, "%,.2f", Math.abs(diff)) + ")"
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
    data class Trend(val what: String, val unit: String, val points: List<Pair<String, Double>>)
    data class Depth(
        val company: String, val level: Int, val what: String, val numbers: List<Num>, val past: List<String>, val why: String, val unsure: String,
        val trends: List<Trend> = emptyList(), val reasons: List<String> = emptyList(), val stocks: List<String> = emptyList()
    )

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
            val trends = mutableListOf<Trend>()
            val rows = o.optJSONArray("trend")
            if (rows != null) {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val points = mutableListOf<Pair<String, Double>>()
                    val list = row.optJSONArray("points") ?: continue
                    for (j in 0 until list.length()) {
                        val p = list.optJSONObject(j) ?: continue
                        val v = p.optDouble("value", Double.NaN)
                        if (v.isNaN() || v.isInfinite() || p.optString("period").isBlank()) continue
                        points.add(p.optString("period").trim() to v)
                    }
                    if (points.size >= 2) trends.add(Trend(row.optString("what").trim(), row.optString("unit"), points.take(8)))
                }
            }
            val reasons = mutableListOf<String>()
            val why = o.optJSONArray("reasons")
            if (why != null) for (i in 0 until why.length()) why.optString(i).trim().takeIf { it.isNotEmpty() }?.let { reasons.add(it) }
            val stocks = mutableListOf<String>()
            val named = o.optJSONArray("stocks")
            if (named != null) for (i in 0 until named.length()) {
                val n = if (named.isNull(i)) "" else named.optString(i).trim()
                if (n.isNotEmpty() && stocks.none { it.equals(n, true) }) stocks.add(n)
            }
            val what = str(o, "what")
            if (what.isEmpty() && numbers.isEmpty() && trends.isEmpty()) return null
            return Depth(str(o, "company"), o.optInt("level", 3).coerceIn(1, 5), what, numbers.take(6), past.take(8),
                str(o, "why"), str(o, "unsure"), trends.take(4), reasons.take(6), stocks.take(6))
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

    private val PERIOD = Regex("(q[1-4].*|fy ?[0-9].*|h[12].*|[0-9].*)")

    /** Heading ki shuruaat ke bade akshar wale shabd: aksar company ka naam. */
    fun companyGuess(title: String): String {
        val out = mutableListOf<String>()
        for (word in cleanTitle(title).split(' ')) {
            val w = word.trim(',', ':', ';', '\'', '"', '(', ')')
            if (w.isEmpty() || out.size >= 4) break
            val plain = w.lowercase(Locale.ENGLISH).removeSuffix("'s").removeSuffix("’s")
            if (plain in NOT_NAME || PERIOD.matches(plain) || !(w[0].isUpperCase() || w == "&")) break
            // "Infra, power & defence stocks": comma ke baad chhota shabd matlab sector ki list, company nahi
            if (word.trimEnd().endsWith(",")) return ""
            out.add(w.removeSuffix("'s").removeSuffix("’s"))
            if (w.endsWith("'s") || w.endsWith("’s")) break
        }
        return out.joinToString(" ").trimEnd('&', ' ')
    }

    /** Purani khabrein dhoondhne ke liye sawaal: company ho to sirf usi ka naam. */
    fun relatedQuery(title: String): String {
        val company = companyGuess(title)
        if (company.isEmpty()) return BrainLogic.tokens(cleanTitle(title)).filter { it.length > 2 }.take(6).joinToString(" ")
        return "\"" + companyQuery(company) + "\""
    }

    /** Kya ye heading isi company ki hai: naam ke saare shabd hone chahiye. */
    fun aboutCompany(title: String, company: String): Boolean {
        val need = BrainLogic.tokens(companyQuery(company))
        if (need.isEmpty()) return true
        val words = BrainLogic.tokens(cleanTitle(title)).toSet()
        return need.all { it in words }
    }

    private val SLUG = Regex("\"slug\"\\s*:\\s*\"((?:\\\\)?/stocks(?:\\\\)?/[^\"]+)\"")

    /** Tickertape search ke jawab mein se share ka page. sid diya ho to wahi wala. */
    fun tickertapeSlug(json: String, sid: String = ""): String? {
        val all = SLUG.findAll(json).map { it.groupValues[1].replace("\\/", "/") }.filter { it.startsWith("/stocks/") && it.length > 9 }.toList()
        return all.firstOrNull { sid.isNotEmpty() && it.endsWith("-$sid") } ?: all.firstOrNull()
    }

    /**
     * Tickertape search mein se SAHI share: NSE code diya ho to wahi code, warna naam ke saare shabd milne chahiye.
     * Galat company (jaise Spectrum Brands ki jagah Spectrum Electrical) se behtar hai kuch na kholna.
     */
    fun tickertapeMatch(json: String, ticker: String, name: String): String? {
        val items = mutableListOf<JSONObject>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    if (str(v, "slug").startsWith("/stocks/")) items.add(v)
                    v.keys().forEach { walk(v.opt(it)) }
                }
                is org.json.JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        try {
            walk(JSONObject(json))
        } catch (e: JSONException) {
            return null
        }
        val code = ticker.trim().uppercase(Locale.ENGLISH).substringBefore('.')
        if (code.isNotEmpty()) {
            items.firstOrNull { str(it, "ticker").equals(code, true) }?.let { return str(it, "slug") }
        }
        val need = BrainLogic.tokens(companyQuery(name))
        if (need.isEmpty()) return null
        return items.firstOrNull { item ->
            val have = BrainLogic.tokens(str(item, "name") + " " + str(item, "slug").replace('-', ' ')).toSet()
            need.all { it in have }
        }?.let { str(it, "slug") }
    }

    /** Videshi (America wali) share khabar ki pehchaan: aise akhbaar aur NYSE/Nasdaq ka naam. */
    private val FOREIGN_SOURCES = setOf(
        "zacks", "zacks investment research", "zacks.com", "marketbeat", "the motley fool", "motley fool", "seeking alpha", "nasdaq", "yahoo finance", "investorplace",
        "barron's", "barrons", "tipranks", "gurufocus", "simply wall st", "stocktitan", "stock titan", "insidermonkey", "insider monkey",
        "globenewswire", "business wire", "pr newswire", "accesswire", "benzinga", "marketwatch", "investing.com", "fxstreet", "moomoo",
        "ad hoc news", "defense world", "the globe and mail", "morningstar"
    )
    private val FOREIGN_WORDS = Regex("""(?i)\((nyse|nasdaq|otc|tsx|lse|asx)\s*:|\b(nyse|nasdaq)\b|\$[0-9]""")

    /** India ka seedha zikr: aisi khabar hamesha rakho. */
    private val INDIA_WORDS = Regex("""(?i)\b(india|indian|indias|sensex|nifty|rupee|rbi|sebi|nse|bse|dalal street|d-street|crore|lakh|gift nifty)\b|₹|\brs\.?\s?[0-9]""")

    /** Duniya ki aisi baatein jo Indian bazaar ko bhi hilati hain. */
    private val GLOBAL_MOVERS = Regex("""(?i)\b(fed|federal reserve|powell|us rate|interest rates?|crude|oil prices?|brent|opec|tariffs?|trade war|trade deal|sanctions?|dollar index|recession|us inflation|treasury yields?|bond yields?|war|ceasefire)\b""")

    /** Sirf America ke bazaar ki baat. */
    private val US_ONLY = Regex("""(?i)\b(wall street|s&p 500|dow jones|the dow|apple|microsoft|nvidia|tesla|amazon|alphabet|google|meta|netflix|broadcom|intel|amd|berkshire|jpmorgan|walmart|boeing)\b""")

    /**
     * Khabar chhodni hai ya nahi. India ka zikr ho to rakho. Fed, crude, tariff jaisi duniya ki badi baat rakho.
     * Baaki America ki company ya bazaar wali khabar chhodo.
     */
    fun foreignNews(fullTitle: String): Boolean {
        val title = cleanTitle(fullTitle)
        if (INDIA_WORDS.containsMatchIn(title)) return false
        if (GLOBAL_MOVERS.containsMatchIn(title)) return false
        return publisher(fullTitle).lowercase(Locale.ENGLISH) in FOREIGN_SOURCES || FOREIGN_WORDS.containsMatchIn(title) || US_ONLY.containsMatchIn(title)
    }

    fun indianSymbol(symbol: String): Boolean = symbol.endsWith(".NS") || symbol.endsWith(".BO")

    // ---------- Tickertape ka scorecard ----------

    data class Stock(val sid: String, val name: String, val ticker: String, val slug: String, val sector: String = "")

    /** Sector ka naam dikhane ke liye. Pata na ho to Other. */
    fun sectorName(raw: String): String = raw.trim().ifEmpty { "Other" }

    /**
     * Tickertape ke kisi bhi jawab mein se sector. code diya ho to usi share (ticker) ke andar wala,
     * warna jo pehla mile.
     */
    fun findSector(json: String, code: String): String {
        val want = code.trim().substringBefore('.')
        fun walk(v: Any?, inside: Boolean): String {
            when (v) {
                is JSONObject -> {
                    val here = inside || want.isEmpty() || str(v, "ticker").equals(want, true)
                    if (here) (v.opt("sector") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                    val keys = v.keys()
                    while (keys.hasNext()) walk(v.opt(keys.next()), here).takeIf { it.isNotEmpty() }?.let { return it }
                }
                is org.json.JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), inside).takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        return try {
            walk(JSONObject(json), false)
        } catch (e: JSONException) {
            ""
        }
    }

    // ---------- Saal dar saal revenue aur profit ----------

    /** Ek saal: revenue aur profit, dono ₹ crore mein. */
    data class Year(val label: String, val revenue: Double, val profit: Double)

    private val YEAR = Regex("(19|20)[0-9]{2}")
    private const val MAX_YEARS = 6

    /** Tickertape ke page ke andar chhupa data. */
    fun nextData(html: String): String {
        val at = html.indexOf("__NEXT_DATA__")
        if (at < 0) return ""
        val start = html.indexOf('>', at)
        val end = html.indexOf("</script>", at)
        return if (start in 0 until end) html.substring(start + 1, end).trim() else ""
    }

    /**
     * Tickertape ka income statement: jis list ke items mein incTrev (revenue) ho.
     * Saalana wali list ko pehle chuno, timahi wali ko nahi.
     */
    fun parseFinancials(json: String): List<Year> {
        val lists = mutableListOf<Pair<String, org.json.JSONArray>>()
        fun walk(v: Any?, key: String) {
            when (v) {
                is JSONObject -> {
                    val keys = v.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        walk(v.opt(k), k)
                    }
                }
                is org.json.JSONArray -> {
                    val first = v.optJSONObject(0)
                    if (first != null && first.has("incTrev")) lists.add(key to v) else for (i in 0 until v.length()) walk(v.opt(i), key)
                }
            }
        }
        try {
            walk(JSONObject(json), "")
        } catch (e: JSONException) {
            return emptyList()
        }
        val pick = lists.firstOrNull { it.first.contains("annual", true) }
            ?: lists.firstOrNull { !it.first.contains("interim", true) && !it.first.contains("quarter", true) }
            ?: return emptyList()
        val byYear = sortedMapOf<String, Year>()
        for (i in 0 until pick.second.length()) {
            val o = pick.second.optJSONObject(i) ?: continue
            val revenue = o.optDouble("incTrev", Double.NaN)
            if (revenue.isNaN()) continue
            val year = YEAR.find(str(o, "displayPeriod"))?.value ?: YEAR.find(str(o, "endDate"))?.value ?: continue
            val profit = o.optDouble("incNinc", Double.NaN)
            byYear[year] = Year(year, revenue, if (profit.isNaN()) 0.0 else profit)
        }
        return byYear.values.toList().takeLast(MAX_YEARS)
    }

    /** Yahoo ka saalana revenue aur profit (rupaye mein aata hai, crore mein badlo). */
    fun parseYahooFinancials(json: String): List<Year> {
        try {
            val results = JSONObject(json).getJSONObject("timeseries").getJSONArray("result")
            val revenue = sortedMapOf<String, Double>()
            val profit = mutableMapOf<String, Double>()
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val type = r.optJSONObject("meta")?.optJSONArray("type")?.optString(0).orEmpty()
                val rows = r.optJSONArray(type) ?: continue
                for (j in 0 until rows.length()) {
                    val row = rows.optJSONObject(j) ?: continue
                    val year = YEAR.find(str(row, "asOfDate"))?.value ?: continue
                    val raw = row.optJSONObject("reportedValue")?.optDouble("raw", Double.NaN) ?: Double.NaN
                    if (raw.isNaN()) continue
                    if (type == "annualTotalRevenue") revenue[year] = raw / 1e7 else if (type == "annualNetIncome") profit[year] = raw / 1e7
                }
            }
            return revenue.map { Year(it.key, it.value, profit[it.key] ?: 0.0) }.takeLast(MAX_YEARS)
        } catch (e: JSONException) {
            return emptyList()
        }
    }

    /** Graph ke upar chhota aankda: 879468 -> 8.8L, 12340 -> 12.3K. */
    fun shortNumber(v: Double): String {
        val a = Math.abs(v)
        val sign = if (v < 0) "-" else ""
        return sign + when {
            a >= 100_000 -> String.format(Locale.ENGLISH, "%.1fL", a / 100_000)
            a >= 1_000 -> String.format(Locale.ENGLISH, "%.1fK", a / 1_000)
            a >= 10 -> String.format(Locale.ENGLISH, "%.0f", a)
            else -> String.format(Locale.ENGLISH, "%.1f", a)
        }
    }

    fun crore(v: Double): String = (if (v < 0) "-₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", Math.abs(v)) + " cr"

    /** JSON null ko khaali maano (Android "null" likh deta hai). */
    private fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key).trim()

    /** Screener ka jawab: shares ki list, sabse badi company pehle. */
    fun parseScreener(json: String): List<Stock> {
        try {
            val rows = JSONObject(json).optJSONObject("data")?.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<Stock>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val stock = row.optJSONObject("stock")
                val info = stock?.optJSONObject("info")
                val sid = str(row, "sid").ifEmpty { stock?.let { str(it, "sid") }.orEmpty() }
                if (sid.isEmpty()) continue
                val ticker = info?.let { str(it, "ticker") }.orEmpty()
                val name = info?.let { str(it, "name") }.orEmpty().ifEmpty { ticker }.ifEmpty { sid }
                val slug = stock?.let { str(it, "slug") }.orEmpty().ifEmpty { str(row, "slug") }
                val sector = info?.let { str(it, "sector") }.orEmpty().ifEmpty { stock?.let { str(it, "sector") }.orEmpty() }
                out.add(Stock(sid, name, ticker, if (slug.startsWith("/stocks/")) slug else "", sector))
            }
            return out
        } catch (e: JSONException) {
            return emptyList()
        }
    }

    /** Scorecard ki ek line hari hai ya nahi. Rang likha ho to wahi, warna tag se. */
    fun isGreen(name: String, tag: String, colour: String): Boolean {
        when (colour.lowercase(Locale.ENGLISH)) {
            "green" -> return true
            "red", "yellow", "orange", "amber" -> return false
        }
        val n = name.lowercase(Locale.ENGLISH)
        val t = tag.lowercase(Locale.ENGLISH)
        return when {
            n.contains("valuation") || n.contains("red flag") -> t == "low"
            n.contains("entry") -> t == "good"
            else -> t == "high"
        }
    }

    /** Scorecard ki ek line ka tag (jaise Valuation -> "Low"). Na mile to khaali. */
    fun scoreTag(json: String, name: String): String {
        try {
            val items = JSONObject(json).optJSONArray("data") ?: return ""
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (str(item, "name").equals(name, true)) return str(item, "tag")
            }
        } catch (e: JSONException) {
            return ""
        }
        return ""
    }

    /** Tickertape ka "Low" valuation matlab bazaar ke ausat se sasta. */
    fun lowValuation(tag: String) = tag.equals("Low", true)

    /** Valuation ka seedha naam: Low, Avg, High (ya khaali). */
    fun valuationKind(tag: String): String = when {
        tag.equals("Low", true) -> "Low"
        tag.equals("High", true) -> "High"
        tag.equals("Avg", true) || tag.equals("Average", true) || tag.equals("Medium", true) -> "Avg"
        else -> ""
    }

    /** Valuation ka nishan: (likhne ke liye, akshar ka rang, peeche ka rang). */
    fun valuationLook(tag: String): Triple<String, Int, Int>? = when (valuationKind(tag)) {
        "Low" -> Triple("🟢 Low valuation", 0xFF166534.toInt(), 0xFFD1FADF.toInt())
        "Avg" -> Triple("🟡 Avg valuation", 0xFF8A5A00.toInt(), 0xFFFFE8A3.toInt())
        "High" -> Triple("🔴 High valuation", 0xFF991B1B.toInt(), 0xFFFEE2E2.toInt())
        else -> null
    }

    /** Matlab Hinglish mein. */
    fun valuationMeaning(kind: String): String = when (kind) {
        "Low" -> "bazaar ke ausat se sasta"
        "Avg" -> "bazaar ke ausat jitna"
        "High" -> "bazaar ke ausat se mehenga"
        else -> ""
    }

    /** Scorecard ka jawab: (kitni hari, kul kitni). Samajh na aaye to null. */
    fun parseScorecard(json: String): Pair<Int, Int>? {
        try {
            val items = JSONObject(json).optJSONArray("data") ?: return null
            var green = 0
            var total = 0
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val tag = str(item, "tag")
                if (tag.isEmpty()) continue
                total++
                if (isGreen(str(item, "name"), tag, str(item, "colour").ifEmpty { str(item, "color") })) green++
            }
            return green to total
        } catch (e: JSONException) {
            return null
        }
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
