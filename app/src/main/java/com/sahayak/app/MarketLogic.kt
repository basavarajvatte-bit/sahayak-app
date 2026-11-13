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
}
