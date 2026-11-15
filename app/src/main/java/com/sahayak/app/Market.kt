package com.sahayak.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Market Watch: bazaar hilane wali khabrein, apne shares ki list aur roz ki report. */
object Market {
    private const val PREFS = "sahayak_market"
    const val ACTION_NEWS = "com.sahayak.app.MARKET_NEWS"
    const val ACTION_REPORT = "com.sahayak.app.MARKET_REPORT"
    private const val NEWS_NOTE = 7_901
    private const val REPORT_NOTE = 7_902
    const val MAX_SHARES = 200
    private const val NOTE_LINES = 8
    private const val MAX_NEWS = 200
    private const val MAX_REPORTS = 10
    private const val MAX_LINKS = 3_000
    private const val MAX_BODY = 2_000_000
    private const val MAX_PAGE = 8_000_000
    private const val FIN_KEEP_MS = 7 * 86_400_000L
    private const val SECTOR_LOOKUPS = 12
    private const val FIRST_SHOW = 25
    private const val FRESH_MS = 6 * 3_600_000L
    private const val SHARE_NEWS = 3
    private const val UA = "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    val INTERVALS = listOf(5, 15, 30, 60)

    /** Aisi khabrein jo share ka bhaav hila sakti hain. */
    private val QUERIES = listOf(
        "quarterly results net profit shares",
        "CEO OR MD resigns OR appointed company shares",
        "bags order OR acquisition OR stake OR merger shares",
        "tariff OR \"trade war\" OR sanctions markets",
        "RBI OR SEBI OR GST decision stocks",
        "Sensex Nifty today"
    )

    private val newsBusy = AtomicBoolean(false)
    private val reportBusy = AtomicBoolean(false)

    @Volatile
    var lastError = ""
        private set

    private val client: OkHttpClient by lazy { GeminiLive.newClient().newBuilder().callTimeout(30, TimeUnit.SECONDS).build() }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun get(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept", "*/*").build()).execute().use { r ->
            if (!r.isSuccessful) {
                lastError = "Server answered " + r.code
                null
            } else {
                r.body?.string()?.take(MAX_BODY)
            }
        }
    } catch (e: IOException) {
        lastError = "No internet"
        null
    }

    /** Tickertape ke khule pages. Wo browser jaisa Referer maangte hain. */
    fun tape(url: String, body: String? = null, page: Boolean = false): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Referer", "https://www.tickertape.in/")
        if (page) request.header("Accept", "text/html,application/xhtml+xml")
        else request.header("Accept", "application/json, text/plain, */*").header("accept-version", "7.9.0")
        if (body != null) request.post(body.toRequestBody("application/json".toMediaType()))
        client.newCall(request.build()).execute().use { r ->
            if (!r.isSuccessful) {
                lastError = "Tickertape answered " + r.code
                null
            } else {
                r.body?.string()?.take(if (page) MAX_PAGE else MAX_BODY)
            }
        }
    } catch (e: IOException) {
        lastError = "No internet"
        null
    }

    // ---------- Pattern study ke liye khule darwaze ----------

    fun web(url: String): String? = get(url)

    /** Naam se Indian share aur uska Yahoo bhaav. */
    fun findIndian(name: String): MarketLogic.Quote? = find(name)

    fun yahoo(symbol: String): MarketLogic.Quote? = yahooQuote(symbol)

    /** Tickertape ka bhaav ek share ka (sid se). */
    fun tapePrice(sid: String): Double? = if (sid.isEmpty()) null else tapeQuotes(listOf(sid))[sid]?.first

    fun googleOnly(symbol: String): Double? = googlePrice(symbol)

    fun armPattern(c: Context, at: Long) = arm(c, Patterns.ACTION, PATTERN_NOTE, "pattern_next", at)

    fun cancelPattern(c: Context) = cancel(c, Patterns.ACTION, PATTERN_NOTE, "pattern_next")

    fun nextPattern(c: Context) = prefs(c).getLong("pattern_next", 0L)

    const val PATTERN_NOTE = 7_904

    private fun rss(query: String) =
        "https://news.google.com/rss/search?q=" + URLEncoder.encode("$query when:1d", "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en"

    // ---------- Bhaav ----------

    private fun yahooQuote(symbol: String): MarketLogic.Quote? {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        for (host in listOf("query1", "query2")) {
            val q = get("https://$host.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=5d")?.let { MarketLogic.parseQuote(it) }
            if (q != null) return q
        }
        return null
    }

    private fun googlePrice(symbol: String): Double? {
        val code = MarketLogic.googleCode(symbol) ?: return null
        return get("https://www.google.com/finance/quote/$code")?.let { MarketLogic.parseGooglePrice(it) }
    }

    fun quote(symbol: String): MarketLogic.Quote? {
        yahooQuote(symbol)?.let { return it }
        val price = googlePrice(symbol) ?: return null
        return MarketLogic.Quote(symbol, symbol.substringBefore('.'), "INR", price, null, System.currentTimeMillis())
    }

    // ---------- Teen jagah se jaanch ----------

    const val ACTION_VERIFY = "com.sahayak.app.MARKET_VERIFY"
    private const val VERIFY_NOTE = 7_903
    private const val OPEN_TOLERANCE = 1.0
    private const val CLOSED_TOLERANCE = 0.3
    private const val AI_CHECKS = 5
    private const val QUOTE_BATCH = 40
    private val verifyBusy = AtomicBoolean(false)

    /** Screen par dikhane ke liye: jaanch kahan tak pahunchi. */
    @Volatile
    var verifyProgress = ""
        private set

    fun verifying() = verifyBusy.get()

    fun verifyOn(c: Context) = prefs(c).getBoolean("verify_on", true)

    fun lastVerify(c: Context) = prefs(c).getLong("verify_last", 0L)

    fun nextVerify(c: Context) = prefs(c).getLong("verify_next", 0L)

    fun setVerify(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("verify_on", on).apply()
        armVerify(c)
    }

    private fun armVerify(c: Context) {
        if (verifyOn(c) && shares(c).isNotEmpty()) arm(c, ACTION_VERIFY, VERIFY_NOTE, "verify_next", MarketLogic.nextVerifyAt(System.currentTimeMillis()))
        else cancel(c, ACTION_VERIFY, VERIFY_NOTE, "verify_next")
    }

    /** Tickertape ka seedha bhaav kai shares ka ek saath. */
    private fun tapeQuotes(sids: List<String>): Map<String, Pair<Double, Double?>> {
        val out = mutableMapOf<String, Pair<Double, Double?>>()
        sids.distinct().filter { it.isNotEmpty() }.chunked(QUOTE_BATCH).forEach { part ->
            tape("https://quotes-api.tickertape.in/quotes?sids=" + URLEncoder.encode(part.joinToString(","), "UTF-8"))
                ?.let { out.putAll(MarketLogic.parseTapeQuotes(it)) }
        }
        return out
    }

    /**
     * Har locked share ka bhaav teen jagah se (Tickertape, Yahoo, Google) lao aur milao.
     * Do jagah mile to pakka. Koi na mile to AI se Google search karwa kar dekho kaunsi jagah sahi hai.
     * only = sirf is id ka share; fresh = abhi lock hua hai, lock ka bhaav bhi jaanche hue bhaav par rakho.
     */
    fun verifyAll(c: Context, scheduled: Boolean, only: String = "", fresh: Boolean = false): String {
        if (scheduled) {
            if (!verifyOn(c)) return ""
            armVerify(c)
        }
        val list = shares(c).filter { (only.isEmpty() || it.optString("id") == only) && MarketLogic.indianSymbol(it.optString("symbol")) }
        if (list.isEmpty()) return "Lock a share first"
        if (!verifyBusy.compareAndSet(false, true)) return "Prices are already being checked"
        try {
            val now = System.currentTimeMillis()
            val tolerance = if (MarketLogic.marketOpen(now)) OPEN_TOLERANCE else CLOSED_TOLERANCE
            verifyProgress = "Finding the shares on Tickertape"
            val sidOf = list.associate { it.optString("id") to slugOf(c, it).substringAfterLast('-', "") }
            val tapes = tapeQuotes(sidOf.values.toList())
            val key = SecureStore(c.getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
            var aiLeft = if (key.isEmpty()) 0 else AI_CHECKS
            var good = 0
            var bad = 0
            var fixed = 0
            list.forEachIndexed { i, s ->
                verifyProgress = "Checked $i of " + list.size + " shares"
                val id = s.optString("id")
                val symbol = s.optString("symbol")
                val sid = sidOf[id].orEmpty()
                var tapeQuote = tapes[sid]
                if (tapeQuote == null && sid.isNotEmpty()) {
                    tapeQuote = tape("https://api.tickertape.in/stocks/charts/inter/$sid?duration=1d")
                        ?.let { MarketLogic.parseTapeSeries(it, null) }?.let { it.price to null }
                }
                val yahoo = yahooQuote(symbol)
                val prices = mutableMapOf<String, Double>()
                tapeQuote?.let { prices[MarketLogic.TAPE] = it.first }
                yahoo?.let { prices[MarketLogic.YAHOO] = it.price }
                // Google ka page bhaari hota hai (mobile data). Poori list ki jaanch mein use tabhi kholo
                // jab pehli do jagah aapas mein na milein; ek share ki jaanch mein hamesha
                if (only.isNotEmpty() || MarketLogic.verify(prices, tolerance)?.ok != true) {
                    googlePrice(symbol)?.let { prices[MarketLogic.GOOGLE] = it }
                }
                var verdict = MarketLogic.verify(prices, tolerance) ?: return@forEachIndexed
                var ai: Double? = null
                if (!verdict.ok && prices.size >= 2 && aiLeft > 0) {
                    aiLeft--
                    verifyProgress = "Sources differ for " + s.optString("name") + ". Asking AI to check"
                    ai = Planner(GeminiLive.newClient(), key, 40L).priceCheck(s.optString("name"), symbol.substringBefore('.'))
                    val seen = ai
                    if (seen != null) {
                        val match = prices.entries.minByOrNull { Math.abs(it.value - seen) }
                        if (match != null && Math.abs(match.value - seen) / seen * 100.0 <= tolerance * 2) {
                            verdict = MarketLogic.Verdict(match.value, true, "Sources differed. AI checked with Google search and it matched " + match.key, match.key)
                        }
                    }
                }
                if (verdict.ok) good++ else bad++
                val check = JSONObject().put("at", System.currentTimeMillis()).put("ok", verdict.ok).put("status", verdict.status).put("used", verdict.used)
                prices.forEach { (name, p) -> check.put(name, p) }
                if (ai != null) check.put("AI", ai)
                change(c) { all ->
                    val item = all.firstOrNull { it.optString("id") == id } ?: return@change
                    val old = item.optDouble("lastPrice", 0.0)
                    if (Math.abs(old - verdict.price) > 0.005) check.put("was", old)
                    item.put("lastPrice", verdict.price).put("lastAt", System.currentTimeMillis())
                    if (yahoo != null) item.put("marketAt", yahoo.timeMs)
                    // Pichhla band bhaav: Tickertape ka badlav mile to usse, warna Yahoo ka
                    val delta = tapeQuote?.second
                    val yahooPrev = yahoo?.prevClose
                    if (verdict.used == MarketLogic.TAPE && delta != null) item.put("prevClose", verdict.price - delta)
                    else if (yahooPrev != null) item.put("prevClose", yahooPrev)
                    // Lock ke baad bazaar khula hi nahi (ya abhi lock hua): lock ka bhaav bhi wahi sahi bhaav hona chahiye
                    val saved = item.optDouble("savedPrice", 0.0)
                    if (verdict.ok && (fresh || !MarketLogic.sessionSince(item.optLong("savedAt"), System.currentTimeMillis())) &&
                        Math.abs(saved - verdict.price) > 0.005
                    ) {
                        item.put("savedPrice", verdict.price)
                        check.put("lockWas", saved)
                        fixed++
                    }
                    item.put("check", check)
                }
            }
            if (only.isEmpty()) prefs(c).edit().putLong("verify_last", System.currentTimeMillis()).putInt("verify_good", good).putInt("verify_bad", bad).apply()
            return "Checked " + (good + bad) + " shares: $good verified" + (if (bad > 0) ", $bad need a look" else "") +
                (if (fixed > 0) ". Corrected $fixed lock prices" else "")
        } finally {
            verifyProgress = ""
            verifyBusy.set(false)
        }
    }

    /** Chart ke liye bhaav ka itihaas. Pehle Tickertape ka apna chart (taaki unki app jaisa hi dikhe), na mile to Yahoo. */
    fun series(c: Context, share: JSONObject, span: MarketLogic.Span): MarketLogic.Series? {
        val symbol = share.optString("symbol")
        if (MarketLogic.indianSymbol(symbol)) {
            val sid = slugOf(c, share).substringAfterLast('-', "")
            if (sid.isNotEmpty()) {
                val prev = share.optDouble("prevClose", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                tape("https://api.tickertape.in/stocks/charts/inter/$sid?duration=" + MarketLogic.tapeDuration(span))
                    ?.let { MarketLogic.parseTapeSeries(it, prev) }?.let { return it }
            }
        }
        val enc = URLEncoder.encode(symbol, "UTF-8")
        for (host in listOf("query1", "query2")) {
            val got = get("https://$host.finance.yahoo.com/v8/finance/chart/$enc?interval=" + span.interval + "&range=" + span.range)
                ?.let { MarketLogic.parseSeries(it) }
            if (got != null) return got
        }
        return null
    }

    private fun find(typed: String): MarketLogic.Quote? {
        val q = URLEncoder.encode(typed, "UTF-8")
        val all = get("https://query2.finance.yahoo.com/v1/finance/search?q=$q&quotesCount=8&newsCount=0")
            ?.let { MarketLogic.parseSearch(it) }.orEmpty()
        // Pehle Indian share (NSE/BSE). Videshi share sirf tab, jab Indian bilkul na mile
        val indian = all.filter { it.symbol.endsWith(".NS") || it.symbol.endsWith(".BO") }
        MarketLogic.best(indian)?.let { found ->
            quote(found.symbol)?.let { return it.copy(name = found.name.ifEmpty { it.name }) }
        }
        for (symbol in MarketLogic.guessSymbols(typed)) {
            val got = quote(symbol)
            if (got != null) return got
        }
        // Sirf Indian share bazaar (NSE/BSE). Videshi share kabhi nahi
        return null
    }

    // ---------- Shares ki list ----------

    fun shares(c: Context): List<JSONObject> {
        val a = JSONArray(prefs(c).getString("shares", "[]"))
        return List(a.length()) { a.getJSONObject(it) }
    }

    @Synchronized
    private fun change(c: Context, edit: (MutableList<JSONObject>) -> Unit) {
        val list = shares(c).toMutableList()
        edit(list)
        prefs(c).edit().putString("shares", JSONArray(list).toString()).apply()
    }

    /** Naam se share dhoondho, abhi ka bhaav yaad rakho. Jawab user ko dikhane ke liye. */
    fun addShare(c: Context, typed: String, fromTitle: String = "", fromLink: String = ""): String {
        val name = typed.trim()
        if (name.isEmpty()) return "Type a share name first"
        if (shares(c).size >= MAX_SHARES) return "List is full. Remove one share first"
        lastError = ""
        val q = find(name)
            ?: return if (lastError == "No internet") "No internet. Try again"
            else "\"$name\" is not on NSE or BSE. It may be a foreign company. Try the exact NSE code, e.g. RELIANCE"
        return save(c, q, fromTitle, fromLink)
    }

    /** NSE code pata ho (jaise green list mein) to seedha usi ka bhaav, bina dhoondhe. */
    fun lockSymbol(c: Context, ticker: String, name: String, fromTitle: String, slug: String = "", sector: String = ""): String {
        if (shares(c).size >= MAX_SHARES) return "List is full. Remove one share first"
        lastError = ""
        val code = ticker.trim().uppercase(Locale.ENGLISH)
        val q = (if (code.isEmpty()) null else quote("$code.NS") ?: quote("$code.BO")) ?: find(name)
            ?: return if (lastError == "No internet") "No internet. Try again" else "Could not get the price of $name. Try again"
        return save(c, q.copy(name = name.ifEmpty { q.name }), fromTitle, "", slug, sector)
    }

    private fun save(c: Context, q: MarketLogic.Quote, fromTitle: String, fromLink: String, slug: String = "", sector: String = ""): String {
        if (shares(c).any { it.optString("symbol") == q.symbol }) return q.name + " is already in your list"
        val now = System.currentTimeMillis()
        val item = JSONObject().put("id", now.toString()).put("symbol", q.symbol).put("name", q.name).put("currency", q.currency)
            .put("savedPrice", q.price).put("savedAt", now).put("lastPrice", q.price).put("lastAt", now).put("marketAt", q.timeMs)
        q.prevClose?.let { item.put("prevClose", it) }
        if (fromTitle.isNotEmpty()) item.put("fromTitle", fromTitle).put("fromLink", fromLink)
        if (slug.startsWith("/stocks/")) item.put("slug", slug)
        if (sector.isNotBlank()) item.put("sector", sector.trim())
        change(c) { it.add(item) }
        rearm(c)
        // Lock ka bhaav turant teen jagah se jaancho
        verifyAll(c, false, item.optString("id"), true)
        val locked = shares(c).firstOrNull { it.optString("id") == item.optString("id") } ?: item
        val sure = locked.optJSONObject("check")?.optBoolean("ok") == true
        return "Locked " + q.name + " at " + MarketLogic.money(locked.optDouble("savedPrice", q.price), q.currency) +
            (if (sure) " (price verified)" else "") + ". See it in My shares"
    }

    private fun setField(c: Context, id: String, key: String, value: Any) = change(c) { list ->
        list.firstOrNull { it.optString("id") == id }?.put(key, value)
    }

    /** Share ka Tickertape page. Yaad na ho to NSE code se dhoondh kar yaad rakh lo. */
    private fun slugOf(c: Context, share: JSONObject): String {
        val known = share.optString("slug")
        if (known.startsWith("/stocks/")) return known
        val found = tickertapeLink(share.optString("name"), "", share.optString("symbol"))?.removePrefix("https://www.tickertape.in").orEmpty()
        if (found.startsWith("/stocks/")) setField(c, share.optString("id"), "slug", found)
        return found
    }

    /**
     * Jin shares ka sector nahi pata, unka dhoondho: pehle bani hui green list mein, phir Tickertape par.
     * Kuch badla ho to true.
     */
    fun fillSectors(c: Context): Boolean {
        val lists = GreenList.CAPS.mapNotNull { GreenList.saved(c, it)?.optJSONArray("rows") }
        var changed = false
        var lookups = 0
        shares(c).forEach { s ->
            if (s.optString("sector").isNotEmpty() || !MarketLogic.indianSymbol(s.optString("symbol"))) return@forEach
            val code = s.optString("symbol").substringBefore('.')
            var sector = ""
            for (rows in lists) {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    if (row.optString("ticker").equals(code, true) && row.optString("sector").isNotEmpty()) sector = row.optString("sector")
                }
            }
            if (sector.isEmpty() && !s.optBoolean("sectorTried") && lookups < SECTOR_LOOKUPS) {
                lookups++
                sector = tape("https://api.tickertape.in/search?text=" + URLEncoder.encode(code, "UTF-8") + "&types=stock&pageNumber=0")
                    ?.let { MarketLogic.findSector(it, code) }.orEmpty()
                if (sector.isEmpty()) {
                    val sid = slugOf(c, s).substringAfterLast('-', "")
                    if (sid.isNotEmpty()) sector = tape("https://api.tickertape.in/stocks/info/$sid")?.let { MarketLogic.findSector(it, "") }.orEmpty()
                }
                if (sector.isEmpty() && lastError != "No internet") setField(c, s.optString("id"), "sectorTried", true)
            }
            if (sector.isNotEmpty()) {
                setField(c, s.optString("id"), "sector", sector)
                changed = true
            }
        }
        return changed
    }

    /**
     * Saal dar saal revenue aur profit (₹ crore). Pehle Tickertape, na mile to Yahoo.
     * Jawab: (saal, kahan se aaya). Hafte bhar yaad rakha jaata hai.
     */
    fun financials(c: Context, share: JSONObject): Pair<List<MarketLogic.Year>, String> {
        val symbol = share.optString("symbol")
        val key = "fin_$symbol"
        prefs(c).getString(key, null)?.let { saved ->
            try {
                val o = JSONObject(saved)
                val a = o.getJSONArray("years")
                val years = List(a.length()) { i -> a.getJSONObject(i).let { MarketLogic.Year(it.getString("y"), it.getDouble("r"), it.getDouble("p")) } }
                if (years.isNotEmpty() && System.currentTimeMillis() - o.optLong("at") < FIN_KEEP_MS) return years to o.optString("from")
            } catch (e: org.json.JSONException) {
                // Purana kharab data, dobara laao
            }
        }
        lastError = ""
        var years = emptyList<MarketLogic.Year>()
        var from = "Tickertape"
        val slug = slugOf(c, share)
        val sid = slug.substringAfterLast('-', "")
        if (sid.isNotEmpty()) {
            years = tape("https://api.tickertape.in/stocks/financials/income/$sid/annual/normal?count=10")?.let { MarketLogic.parseFinancials(it) }.orEmpty()
            if (years.size < 2) {
                years = tape("https://www.tickertape.in$slug", null, true)?.let { MarketLogic.parseFinancials(MarketLogic.nextData(it)) }.orEmpty()
            }
        }
        if (years.size < 2) {
            from = "Yahoo Finance"
            val enc = URLEncoder.encode(symbol, "UTF-8")
            val now = System.currentTimeMillis() / 1000
            years = get("https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/$enc?symbol=$enc" +
                "&type=annualTotalRevenue,annualNetIncome&period1=946684800&period2=$now")?.let { MarketLogic.parseYahooFinancials(it) }.orEmpty()
        }
        if (years.isEmpty()) return years to ""
        val a = JSONArray()
        years.forEach { a.put(JSONObject().put("y", it.label).put("r", it.revenue).put("p", it.profit)) }
        prefs(c).edit().putString(key, JSONObject().put("at", System.currentTimeMillis()).put("from", from).put("years", a).toString()).apply()
        return years to from
    }

    fun removeShare(c: Context, id: String) {
        change(c) { list -> list.removeAll { it.optString("id") == id } }
        rearm(c)
    }

    /** Sab shares ka naya bhaav. Kitne mile, wo lautao. */
    fun refresh(c: Context): Int {
        var ok = 0
        shares(c).forEach { s ->
            val q = quote(s.optString("symbol")) ?: return@forEach
            ok++
            change(c) { list ->
                list.firstOrNull { it.optString("id") == s.optString("id") }?.let { item ->
                    item.put("lastPrice", q.price).put("lastAt", System.currentTimeMillis()).put("marketAt", q.timeMs)
                    if (q.prevClose != null) item.put("prevClose", q.prevClose) else item.remove("prevClose")
                }
            }
        }
        return ok
    }

    // ---------- Roz ki report ----------

    fun reportOn(c: Context) = prefs(c).getBoolean("report_on", true)

    fun reportHour(c: Context) = prefs(c).getInt("report_hour", 16)

    fun reportMinute(c: Context) = prefs(c).getInt("report_minute", 0)

    fun nextReport(c: Context) = prefs(c).getLong("report_next", 0L)

    fun setReport(c: Context, on: Boolean, hour: Int, minute: Int) {
        prefs(c).edit().putBoolean("report_on", on).putInt("report_hour", hour).putInt("report_minute", minute).apply()
        if (on && shares(c).isNotEmpty()) armReport(c) else cancel(c, ACTION_REPORT, REPORT_NOTE, "report_next")
    }

    fun reports(c: Context): List<JSONObject> {
        val a = JSONArray(prefs(c).getString("reports", "[]"))
        return List(a.length()) { a.getJSONObject(it) }
    }

    fun clearReports(c: Context) = prefs(c).edit().putString("reports", "[]").apply()

    /** Har share: aaj kitna badla, save karne ke baad se kitna badla, aur aaj ki khabrein. */
    fun report(c: Context, scheduled: Boolean): String {
        if (scheduled) {
            if (!reportOn(c)) return ""
            armReport(c)
        }
        if (shares(c).isEmpty()) return "Save a share first"
        if (!reportBusy.compareAndSet(false, true)) return "Report is already being made"
        try {
            val started = System.currentTimeMillis()
            lastError = ""
            // Report se pehle bhaav ki poori jaanch; jaanch na chal sake to seedha bhaav
            if (verifyAll(c, false).startsWith("Prices are already")) refresh(c)
            val zone = TimeZone.getDefault()
            val today = MarketLogic.dayKey(started, zone)
            val day = SimpleDateFormat("d MMM", Locale.ENGLISH)
            val items = JSONArray()
            val note = mutableListOf<String>()
            var got = 0
            shares(c).forEach { s ->
                val name = s.optString("name")
                val currency = s.optString("currency")
                val price = s.optDouble("lastPrice", 0.0)
                val saved = s.optDouble("savedPrice", 0.0)
                val fresh = s.optLong("lastAt") >= started
                if (fresh) got++
                val parts = mutableListOf(MarketLogic.money(price, currency))
                var short = MarketLogic.money(price, currency)
                if (fresh && s.has("prevClose")) {
                    val t = MarketLogic.pctText(price, s.optDouble("prevClose"))
                    parts.add("Today $t")
                    short += "  aaj $t"
                }
                val since = MarketLogic.pctText(price, saved)
                parts.add("Since saved (" + day.format(Date(s.optLong("savedAt"))) + ", " + MarketLogic.money(saved, currency) + ") " + since)
                short += ",  lock se $since"
                val market = s.optLong("marketAt")
                val extra = when {
                    !fresh -> "Could not get today's price. Showing the last one."
                    market > 0 && MarketLogic.dayKey(market, zone) != today -> "Market was closed today. Last trading day shown."
                    else -> ""
                }
                val news = JSONArray()
                val query = MarketLogic.companyQuery(name)
                // Sirf isi company ki khabar: naam ke saare shabd heading mein hon
                get(rss("\"$query\" share"))?.let { xml ->
                    WatchLogic.news(xml).filter { MarketLogic.aboutCompany(it.title, query) && !MarketLogic.foreignNews(it.title) }
                        .sortedByDescending { MarketLogic.pubTime(it.date) }.take(SHARE_NEWS)
                        .forEach { news.put(JSONObject().put("t", MarketLogic.cleanTitle(it.title)).put("s", MarketLogic.publisher(it.title)).put("l", it.link)) }
                }
                val item = JSONObject().put("name", name).put("code", s.optString("symbol").substringBefore('.')).put("text", parts.joinToString("  ·  "))
                    .put("extra", extra).put("up", MarketLogic.pct(price, saved)).put("news", news)
                    .put("price", price).put("currency", currency).put("saved", saved).put("savedAt", s.optLong("savedAt"))
                    .put("fresh", fresh).put("closed", market > 0 && MarketLogic.dayKey(market, zone) != today)
                if (fresh && s.has("prevClose")) item.put("today", MarketLogic.pct(price, s.optDouble("prevClose")))
                items.put(item)
                note.add("$name $short")
            }
            val list = reports(c).toMutableList()
            list.add(0, JSONObject().put("at", started).put("items", items))
            prefs(c).edit().putString("reports", JSONArray(list.take(MAX_REPORTS)).toString()).apply()
            if (scheduled || got > 0) {
                Watcher.alertNow(c, REPORT_NOTE, "Share report: aaj ka hisaab", note.take(NOTE_LINES).joinToString("\n") + (if (note.size > NOTE_LINES) "\n…aur " + (note.size - NOTE_LINES) + " shares Sahayak mein" else ""), "", MarketActivity::class.java)
            }
            return if (got == 0) "Could not get prices. " + lastError else "Report ready"
        } finally {
            reportBusy.set(false)
        }
    }

    // ---------- Bazaar ki khabrein ----------

    fun newsOn(c: Context) = prefs(c).getBoolean("news_on", false)

    fun newsMinutes(c: Context) = prefs(c).getInt("news_minutes", 15)

    fun alertOn(c: Context) = prefs(c).getBoolean("news_alert", true)

    fun nextNews(c: Context) = prefs(c).getLong("news_next", 0L)

    fun lastNews(c: Context) = prefs(c).getLong("news_last", 0L)

    fun setNews(c: Context, on: Boolean, minutes: Int, alert: Boolean) {
        val was = newsOn(c)
        prefs(c).edit().putBoolean("news_on", on).putInt("news_minutes", minutes).putBoolean("news_alert", alert).apply()
        if (!on) cancel(c, ACTION_NEWS, NEWS_NOTE, "news_next")
        else if (!was) arm(c, ACTION_NEWS, NEWS_NOTE, "news_next", System.currentTimeMillis() + 2_000L)
    }

    fun news(c: Context): List<JSONObject> {
        val a = JSONArray(prefs(c).getString("news", "[]"))
        return List(a.length()) { a.getJSONObject(it) }
    }

    fun clearNews(c: Context) = prefs(c).edit().putString("news", "[]").apply()

    /** Nayi khabrein dhoondho. Kitni nayi mili, wo lautao. -1 matlab internet nahi. */
    fun runNews(c: Context, scheduled: Boolean): Int {
        if (scheduled) {
            if (!newsOn(c)) return 0
            arm(c, ACTION_NEWS, NEWS_NOTE, "news_next", System.currentTimeMillis() + newsMinutes(c) * 60_000L)
        }
        if (!newsBusy.compareAndSet(false, true)) return 0
        try {
            val p = prefs(c)
            val now = System.currentTimeMillis()
            val first = !p.getBoolean("news_started", false)
            val old = news(c)
            val links = p.getStringSet("news_links", null).orEmpty().toMutableSet()
            val keys = old.take(150).map { MarketLogic.key(it.optString("title")) }.toMutableList()
            val fresh = mutableListOf<JSONObject>()
            var reached = false
            QUERIES.forEach { query ->
                val xml = get(rss(query)) ?: return@forEach
                reached = true
                WatchLogic.news(xml).forEach { n ->
                    if (n.link in links) return@forEach
                    links.add(n.link)
                    val title = MarketLogic.cleanTitle(n.title)
                    // Sirf Indian bazaar ki khabar: America ki company wali khabar chhod do
                    if (MarketLogic.foreignNews(n.title)) return@forEach
                    val tag = MarketLogic.classify(title) ?: return@forEach
                    val at = MarketLogic.pubTime(n.date).takeIf { it in 1..now } ?: now
                    if (!first && now - at > FRESH_MS) return@forEach
                    val key = MarketLogic.key(title)
                    if (keys.any { MarketLogic.similar(key, it) }) return@forEach
                    keys.add(key)
                    fresh.add(JSONObject().put("at", at).put("title", title).put("link", n.link).put("kind", tag.kind)
                        .put("mood", tag.mood).put("source", MarketLogic.publisher(n.title)))
                }
            }
            if (!reached) return -1
            fresh.sortByDescending { it.optLong("at") }
            val add = if (first) fresh.take(FIRST_SHOW) else fresh
            val all = (add + old).sortedByDescending { it.optLong("at") }.take(MAX_NEWS)
            p.edit().putString("news", JSONArray(all).toString()).putStringSet("news_links", links.toList().takeLast(MAX_LINKS).toSet())
                .putBoolean("news_started", true).putLong("news_last", now).apply()
            if (!first && add.isNotEmpty() && alertOn(c)) {
                val title = if (add.size == 1) "Market news · " + add[0].optString("kind") else add.size.toString() + " new market news"
                val text = add.take(5).joinToString("\n") { mark(it.optString("mood")) + it.optString("title") }
                Watcher.alertNow(c, NEWS_NOTE, title, text, add[0].optString("link"), MarketActivity::class.java)
            }
            return add.size
        } finally {
            newsBusy.set(false)
        }
    }

    // ---------- Khabar ki gehrai ----------

    private const val MAX_DEPTH = 40
    private const val RELATED = 20

    private fun cachedDepth(c: Context, link: String): JSONObject? {
        val a = JSONArray(prefs(c).getString("depth", "[]"))
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("link") == link) return o
        }
        return null
    }

    /**
     * Khabar par research: purani khabrein dhoondho, phir AI se poochho ki ye kitni badi baat hai.
     * Jawab mein: ai (JSON text), searched, related [{t,l,d}], error.
     */
    fun depth(c: Context, title: String, link: String, kind: String, force: Boolean): JSONObject {
        if (!force) cachedDepth(c, link)?.let { return it }
        val out = JSONObject().put("link", link).put("at", System.currentTimeMillis())
        lastError = ""
        val mine = MarketLogic.key(title)
        val company = MarketLogic.companyGuess(title)
        // Company ki khabar ho to sirf usi company ki purani khabrein
        val older = get(rssAll(MarketLogic.relatedQuery(title)))?.let { xml ->
            WatchLogic.news(xml).filter { MarketLogic.cleanTitle(it.title) != title && MarketLogic.key(it.title) != mine }
                .filter { company.isEmpty() || MarketLogic.aboutCompany(it.title, company) }
                .sortedByDescending { MarketLogic.pubTime(it.date) }.take(RELATED)
        }.orEmpty()
        val day = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
        val related = JSONArray()
        older.forEach { n ->
            val at = MarketLogic.pubTime(n.date)
            related.put(JSONObject().put("t", n.title).put("l", n.link).put("d", if (at > 0) day.format(Date(at)) else ""))
        }
        out.put("related", related)
        val key = SecureStore(c.getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
        if (key.isEmpty()) return out.put("error", "Add your Gemini key in Sahayak to get the full research")
        val text = "Today: " + day.format(Date()) + "\nType: " + kind + "\nHeadline: " + title +
            (if (company.isNotEmpty()) "\nCompany: $company (write only about this company)" else "") + "\nOlder headlines about it:\n" +
            (if (older.isEmpty()) "(none found)" else older.joinToString("\n") { n ->
                val at = MarketLogic.pubTime(n.date)
                "- " + (if (at > 0) day.format(Date(at)) + ": " else "") + n.title
            })
        val planner = Planner(GeminiLive.newClient(), key, 60L)
        val answer = planner.depth(text)
        if (answer == null || MarketLogic.parseDepth(answer.first) == null) {
            return out.put("error", planner.lastError.ifEmpty { "AI did not give a clear answer. Tap Depth again" })
        }
        out.put("ai", MarketLogic.jsonBlock(answer.first)).put("searched", answer.second)
        synchronized(this) {
            val a = JSONArray(prefs(c).getString("depth", "[]"))
            val list = mutableListOf(out)
            for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("link") != link }?.let { list.add(it) }
            prefs(c).edit().putString("depth", JSONArray(list.take(MAX_DEPTH)).toString()).apply()
        }
        return out
    }

    /** Tickertape par is share ka page. Na mile to Google se dhoondhne ka link. */
    /** Tickertape par wahi share: pehle NSE code se, phir naam se. Pakka na mile to Tickertape ki search. */
    fun tickertapeLink(name: String, sid: String = "", ticker: String = ""): String? {
        val q = MarketLogic.companyQuery(name)
        val code = ticker.substringBefore('.')
        var slug: String? = null
        if (code.isNotEmpty()) {
            slug = tape("https://api.tickertape.in/search?text=" + URLEncoder.encode(code, "UTF-8") + "&types=stock&pageNumber=0")
                ?.let { MarketLogic.tickertapeMatch(it, code, "") }
        }
        if (slug == null && sid.isNotEmpty()) {
            slug = tape("https://api.tickertape.in/search?text=" + URLEncoder.encode(q, "UTF-8") + "&types=stock&pageNumber=0")
                ?.let { MarketLogic.tickertapeSlug(it, sid)?.takeIf { s -> s.endsWith("-$sid") } }
        }
        if (slug == null) {
            slug = tape("https://api.tickertape.in/search?text=" + URLEncoder.encode(q, "UTF-8") + "&types=stock&pageNumber=0")
                ?.let { MarketLogic.tickertapeMatch(it, "", q) }
        }
        return slug?.let { "https://www.tickertape.in$it" }
    }

    private const val TICKERTAPE = "in.tickertape"

    /**
     * Tickertape APP mein kholo, Chrome mein nahi. Jawab: khaali matlab khul gaya, warna user ke liye sandesh.
     * 1) Share ka link seedha app mein. 2) Na chale to app kholo aur NSE code copy kar do, search mein paste karne ke liye.
     */
    fun openTickertape(a: android.app.Activity, link: String?, code: String): String {
        if (link != null && link.startsWith("https://www.tickertape.in/")) {
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).setPackage(TICKERTAPE)
                    .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return ""
            } catch (e: android.content.ActivityNotFoundException) {
                // App ye link seedha nahi kholta, to app ka home kholenge
            }
        }
        val launch = a.packageManager.getLaunchIntentForPackage(TICKERTAPE)
        if (launch != null) {
            val name = code.substringBefore('.')
            if (name.isNotEmpty()) {
                val clip = a.getSystemService(android.content.ClipboardManager::class.java)
                clip?.setPrimaryClip(android.content.ClipData.newPlainText("share", name))
            }
            a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return if (name.isEmpty()) "Tickertape opened. Search the share there"
            else "Tickertape opened. Tap its search, long-press and Paste: $name"
        }
        return "Tickertape app is not installed on this phone. Install it from Play Store"
    }

    private fun rssAll(query: String) =
        "https://news.google.com/rss/search?q=" + URLEncoder.encode(query, "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en"

    private fun mark(mood: String) = when (mood) {
        "Good" -> "🟢 "
        "Bad" -> "🔴 "
        else -> "• "
    }

    // ---------- Alarm ----------

    private fun pending(c: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        c, code, Intent(c, WorkReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun arm(c: Context, action: String, code: Int, key: String, at: Long) {
        prefs(c).edit().putLong(key, at).apply()
        val manager = c.getSystemService(AlarmManager::class.java)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            val show = PendingIntent.getActivity(c, code, Intent(c, MarketActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(c, action, code))
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, action, code))
        }
    }

    private fun cancel(c: Context, action: String, code: Int, key: String) {
        prefs(c).edit().putLong(key, 0L).apply()
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, action, code))
    }

    private fun armReport(c: Context) = arm(
        c, ACTION_REPORT, REPORT_NOTE, "report_next",
        MarketLogic.nextReportAt(System.currentTimeMillis(), reportHour(c), reportMinute(c), TimeZone.getDefault())
    )

    /** Share jodne ya hatane ke baad, aur phone chalu hone par. */
    fun rearm(c: Context) {
        armVerify(c)
        Patterns.rearm(c)
        if (reportOn(c) && shares(c).isNotEmpty()) armReport(c) else cancel(c, ACTION_REPORT, REPORT_NOTE, "report_next")
        if (newsOn(c)) {
            val at = nextNews(c)
            arm(c, ACTION_NEWS, NEWS_NOTE, "news_next", if (at > System.currentTimeMillis()) at else System.currentTimeMillis() + 5_000L)
        }
    }
}
