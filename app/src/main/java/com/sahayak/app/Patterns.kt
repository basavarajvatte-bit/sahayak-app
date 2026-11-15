package com.sahayak.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** Pattern study: ghatna ki khabar aate hi share lock karo aur dekho uske baad wo kaisa chalta hai. */
object Patterns {
    private const val PREFS = "sahayak_patterns"
    const val ACTION = "com.sahayak.app.PATTERN_SCAN"
    private const val GAP_MS = 2 * 3_600_000L
    private const val TRACK_GAP_MS = 5 * 3_600_000L
    private const val FRESH_MS = 36 * 3_600_000L
    private const val SAME_MS = 30 * 86_400_000L
    private const val ACTIVE_MS = 100 * 86_400_000L
    private const val MAX_LOCKS = 500
    private const val MAX_SEEN = 4_000
    private const val MAX_CANDIDATES = 30
    private const val AI_BATCH = 15
    private const val OPEN_TOLERANCE = 1.0
    private const val CLOSED_TOLERANCE = 0.3
    private const val NIFTY = "^NSEI"

    private val busy = AtomicBoolean(false)

    @Volatile
    var progress = ""
        private set

    fun running() = busy.get()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun on(c: Context) = prefs(c).getBoolean("on", false)

    fun lastScan(c: Context) = prefs(c).getLong("last", 0L)

    fun lastNote(c: Context) = prefs(c).getString("note", "").orEmpty()

    fun setOn(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("on", value).apply()
        if (value) Market.armPattern(c, System.currentTimeMillis() + 3_000L) else Market.cancelPattern(c)
    }

    fun rearm(c: Context) {
        if (!on(c)) return
        val at = Market.nextPattern(c)
        Market.armPattern(c, if (at > System.currentTimeMillis()) at else System.currentTimeMillis() + 10_000L)
    }

    fun locks(c: Context): List<JSONObject> {
        val a = JSONArray(prefs(c).getString("locks", "[]"))
        return List(a.length()) { a.getJSONObject(it) }
    }

    @Synchronized
    private fun change(c: Context, edit: (MutableList<JSONObject>) -> Unit) {
        val list = locks(c).toMutableList()
        edit(list)
        prefs(c).edit().putString("locks", JSONArray(list.sortedByDescending { it.optLong("lockAt") }.take(MAX_LOCKS)).toString()).apply()
    }

    fun remove(c: Context, id: String) = change(c) { list -> list.removeAll { it.optString("id") == id } }

    private fun rss(query: String) =
        "https://news.google.com/rss/search?q=" + URLEncoder.encode("$query when:1d", "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en"

    private fun daily(symbol: String, range: String): PatternLogic.Daily? {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        for (host in listOf("query1", "query2")) {
            val got = Market.web("https://$host.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=$range")?.let { PatternLogic.parseDaily(it) }
            if (got != null) return got
        }
        return null
    }

    private class Candidate(val pattern: PatternLogic.Pattern, val title: String, val link: String, val at: Long, val source: String)

    /** Khabrein dhoondho, naye shares lock karo, phir purane locks ka hisaab taaza karo. Jawab user ke liye. */
    fun run(c: Context, scheduled: Boolean): String {
        if (scheduled) {
            if (!on(c)) return ""
            Market.armPattern(c, System.currentTimeMillis() + GAP_MS)
        }
        if (!busy.compareAndSet(false, true)) return "Pattern study is already running"
        try {
            val added = scan(c)
            if (added < 0) return "No internet. Try again"
            val now = System.currentTimeMillis()
            if (!scheduled || now - prefs(c).getLong("track_at", 0L) > TRACK_GAP_MS) track(c)
            val note = if (added == 0) "No new event found" else "$added new shares locked"
            prefs(c).edit().putLong("last", now).putString("note", note).apply()
            return note
        } catch (e: org.json.JSONException) {
            return "Something went wrong. Try again"
        } finally {
            progress = ""
            busy.set(false)
        }
    }

    /** Sirf hisaab taaza karo (khabar nahi dhoondhni). */
    fun refresh(c: Context): String {
        if (!busy.compareAndSet(false, true)) return "Pattern study is already running"
        try {
            val n = track(c)
            return if (n < 0) "No internet. Try again" else "Updated $n shares"
        } catch (e: org.json.JSONException) {
            return "Something went wrong. Try again"
        } finally {
            progress = ""
            busy.set(false)
        }
    }

    private fun scan(c: Context): Int {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val seen = p.getStringSet("seen", null).orEmpty().toMutableSet()
        val found = mutableListOf<Candidate>()
        val keys = mutableListOf<Pair<String, Set<String>>>()
        var reached = false
        PatternLogic.ALL.forEachIndexed { i, pattern ->
            progress = "Reading news: " + pattern.label + " (" + (i + 1) + " of " + PatternLogic.ALL.size + ")"
            val xml = Market.web(rss(pattern.query)) ?: return@forEachIndexed
            reached = true
            WatchLogic.news(xml).forEach { n ->
                val mark = pattern.id + "|" + n.link
                if (mark in seen) return@forEach
                seen.add(mark)
                val title = MarketLogic.cleanTitle(n.title)
                if (MarketLogic.foreignNews(n.title) || !PatternLogic.matches(pattern, title)) return@forEach
                val at = MarketLogic.pubTime(n.date).takeIf { it in 1..now } ?: now
                if (now - at > FRESH_MS) return@forEach
                // Ek hi khabar kai akhbaaron mein: ek baar hi
                val key = MarketLogic.key(title)
                if (keys.any { it.first == pattern.id && MarketLogic.similar(key, it.second) }) return@forEach
                keys.add(pattern.id to key)
                found.add(Candidate(pattern, title, n.link, at, MarketLogic.publisher(n.title)))
            }
        }
        if (!reached) return -1
        p.edit().putStringSet("seen", seen.toList().takeLast(MAX_SEEN).toSet()).apply()
        val candidates = found.sortedByDescending { it.at }.take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return 0

        // AI se pakka karo ki ye sach mein wahi ghatna hai aur kis company ki hai
        val key = SecureStore(c.getSharedPreferences("sahayak", Context.MODE_PRIVATE)).load()
        val checks = mutableMapOf<Int, PatternLogic.Check>()
        var aiWorked = false
        if (key.isNotEmpty()) {
            val planner = Planner(GeminiLive.newClient(), key, 60L)
            candidates.indices.chunked(AI_BATCH).forEach { part ->
                progress = "AI is checking " + candidates.size + " headlines"
                val text = part.joinToString("\n") { i -> (i + 1).toString() + ". [" + candidates[i].pattern.label + "] " + candidates[i].title }
                planner.patternCheck(text)?.let { answer ->
                    val got = PatternLogic.parseChecks(answer)
                    if (got.isNotEmpty()) aiWorked = true
                    checks.putAll(got)
                }
            }
        }

        val niftyDaily = daily(NIFTY, "1mo")
        val niftyNow = Market.yahoo(NIFTY)?.price
        var added = 0
        val names = mutableListOf<String>()
        candidates.forEachIndexed { i, cand ->
            val check = checks[i + 1]
            // AI chala aur usne mana kiya: lock nahi. AI chala hi nahi: sirf heading se saaf company ho tabhi
            val companies = when {
                check != null -> if (check.event) check.companies else emptyList()
                aiWorked -> emptyList()
                else -> listOf(MarketLogic.companyGuess(cand.title)).filter { it.isNotEmpty() }
            }
            companies.forEach { company ->
                progress = "Locking " + company + " (" + cand.pattern.label + ")"
                if (lock(c, cand, company, check?.detail.orEmpty(), check != null, niftyDaily, niftyNow)) {
                    added++
                    names.add(company)
                }
            }
        }
        if (added > 0) {
            Watcher.alertNow(
                c, Market.PATTERN_NOTE, "Pattern study: $added new " + (if (added == 1) "share" else "shares") + " locked",
                names.take(6).joinToString(", ") + (if (names.size > 6) " and more" else ""), "", PatternActivity::class.java
            )
        }
        return added
    }

    /** Ek company ko ek pattern mein lock karo. Company, bhaav aur aadhaar teeno pakke hon tabhi. */
    private fun lock(
        c: Context, cand: Candidate, company: String, detail: String, aiChecked: Boolean, niftyDaily: PatternLogic.Daily?, niftyNow: Double?
    ): Boolean {
        val quote = Market.findIndian(company) ?: return false
        if (!PatternLogic.sameCompany(company, quote.name, quote.symbol)) return false
        val now = System.currentTimeMillis()
        if (locks(c).any { it.optString("p") == cand.pattern.id && it.optString("symbol") == quote.symbol && now - it.optLong("lockAt") < SAME_MS }) return false

        // Bhaav kam se kam do jagah se mile tabhi lock
        val slug = Market.tickertapeLink(quote.name, "", quote.symbol)?.removePrefix("https://www.tickertape.in").orEmpty()
        val sid = slug.substringAfterLast('-', "")
        val open = MarketLogic.marketOpen(now)
        val tolerance = if (open) OPEN_TOLERANCE else CLOSED_TOLERANCE
        val prices = mutableMapOf(MarketLogic.YAHOO to quote.price)
        Market.tapePrice(sid)?.let { prices[MarketLogic.TAPE] = it }
        if (MarketLogic.verify(prices, tolerance)?.ok != true) Market.googleOnly(quote.symbol)?.let { prices[MarketLogic.GOOGLE] = it }
        val verdict = MarketLogic.verify(prices, tolerance) ?: return false
        // Jagah aapas mein na milein to lock nahi. Sirf ek hi jagah bhaav mila ho to lock, par nishan ke saath
        if (!verdict.ok && prices.size >= 2) return false

        val stock = daily(quote.symbol, "1mo") ?: return false
        val base = PatternLogic.baseAt(stock, now, open) ?: return false
        val item = JSONObject().put("id", now.toString() + "-" + quote.symbol).put("p", cand.pattern.id).put("symbol", quote.symbol).put("name", quote.name)
            .put("slug", slug).put("title", cand.title).put("link", cand.link).put("source", cand.source).put("detail", detail)
            .put("eventAt", cand.at).put("lockAt", now).put("lockPrice", verdict.price).put("price", verdict.price).put("ai", aiChecked)
            .put("baseDate", base.date).put("baseClose", base.close).put("intraday", base.intraday).put("sources", prices.size).put("sure", verdict.ok)
        // Nifty ka wahi aadhaar, taaki tulna barabar ki ho
        if (niftyDaily != null && niftyNow != null) {
            val i = niftyDaily.days.indexOf(base.date)
            if (i >= 0) item.put("nBaseClose", niftyDaily.raw[i]).put("nLock", if (base.intraday) niftyNow else niftyDaily.raw[i])
        }
        change(c) { it.add(item) }
        return true
    }

    /** Har chalte lock ka hisaab: ab tak aur 1 din, 1 hafta, 1 mahina, 3 mahine baad, Nifty ke saath. */
    private fun track(c: Context): Int {
        val now = System.currentTimeMillis()
        val active = locks(c).filter { now - it.optLong("lockAt") < ACTIVE_MS }
        if (active.isEmpty()) {
            prefs(c).edit().putLong("track_at", now).apply()
            return 0
        }
        progress = "Getting Nifty"
        val nifty = daily(NIFTY, "6mo") ?: return -1
        var done = 0
        active.forEachIndexed { i, lock ->
            progress = "Updating " + (i + 1) + " of " + active.size + " shares"
            val stock = daily(lock.optString("symbol"), "6mo") ?: return@forEachIndexed
            val base = PatternLogic.Base(lock.optString("baseDate"), lock.optDouble("baseClose", 0.0), lock.optBoolean("intraday"))
            val mine = PatternLogic.track(stock, base, lock.optDouble("lockPrice", 0.0)) ?: return@forEachIndexed
            val index = if (lock.has("nBaseClose")) {
                PatternLogic.track(nifty, PatternLogic.Base(base.date, lock.optDouble("nBaseClose"), base.intraday), lock.optDouble("nLock", 0.0))
            } else null
            done++
            change(c) { list ->
                val item = list.firstOrNull { it.optString("id") == lock.optString("id") } ?: return@change
                item.put("price", mine.price).put("now", mine.now).put("adjusted", mine.adjusted).put("trackAt", System.currentTimeMillis())
                item.put("steps", steps(mine.steps))
                if (index != null) item.put("nNow", index.now).put("nSteps", steps(index.steps)) else item.remove("nNow")
            }
        }
        prefs(c).edit().putLong("track_at", now).apply()
        return done
    }

    private fun steps(values: List<Double?>): JSONArray {
        val a = JSONArray()
        values.forEach { if (it == null) a.put(JSONObject.NULL) else a.put(it) }
        return a
    }

    /** Ek pattern ka scoreboard: [ab tak, 1 din, 1 hafta, 1 mahina, 3 mahine]. Sirf wo shares jinka Nifty ka hisaab bhi hai. */
    fun scores(list: List<JSONObject>): List<PatternLogic.Score?> {
        val now = list.filter { it.has("now") && it.has("nNow") }.map { it.optDouble("now") to it.optDouble("nNow") }
        val out = mutableListOf(PatternLogic.score(now))
        PatternLogic.STEPS.indices.forEach { k ->
            out.add(PatternLogic.score(list.mapNotNull { lock ->
                val mine = lock.optJSONArray("steps")
                val index = lock.optJSONArray("nSteps")
                if (mine == null || index == null || mine.isNull(k) || index.isNull(k)) null else mine.optDouble(k) to index.optDouble(k)
            }))
        }
        return out
    }
}
