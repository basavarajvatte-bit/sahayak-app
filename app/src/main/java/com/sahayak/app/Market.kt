package com.sahayak.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import okhttp3.OkHttpClient
import okhttp3.Request
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
    const val MAX_SHARES = 25
    private const val MAX_NEWS = 200
    private const val MAX_REPORTS = 30
    private const val MAX_LINKS = 3_000
    private const val MAX_BODY = 2_000_000
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

    private fun rss(query: String) =
        "https://news.google.com/rss/search?q=" + URLEncoder.encode("$query when:1d", "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en"

    // ---------- Bhaav ----------

    fun quote(symbol: String): MarketLogic.Quote? {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        for (host in listOf("query1", "query2")) {
            val q = get("https://$host.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=5d")?.let { MarketLogic.parseQuote(it) }
            if (q != null) return q
        }
        val code = MarketLogic.googleCode(symbol) ?: return null
        val price = get("https://www.google.com/finance/quote/$code")?.let { MarketLogic.parseGooglePrice(it) } ?: return null
        return MarketLogic.Quote(symbol, symbol.substringBefore('.'), "INR", price, null, System.currentTimeMillis())
    }

    private fun find(typed: String): MarketLogic.Quote? {
        val q = URLEncoder.encode(typed, "UTF-8")
        val found = get("https://query2.finance.yahoo.com/v1/finance/search?q=$q&quotesCount=8&newsCount=0")
            ?.let { MarketLogic.best(MarketLogic.parseSearch(it)) }
        if (found != null) {
            val got = quote(found.symbol)
            if (got != null) return got.copy(name = found.name.ifEmpty { got.name })
        }
        for (symbol in MarketLogic.guessSymbols(typed)) {
            val got = quote(symbol)
            if (got != null) return got
        }
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
    fun addShare(c: Context, typed: String): String {
        val name = typed.trim()
        if (name.isEmpty()) return "Type a share name first"
        if (shares(c).size >= MAX_SHARES) return "List is full. Remove one share first"
        lastError = ""
        val q = find(name)
            ?: return if (lastError == "No internet") "No internet. Try again" else "Could not find \"$name\". Try the exact NSE code, e.g. RELIANCE"
        if (shares(c).any { it.optString("symbol") == q.symbol }) return q.name + " is already in your list"
        val now = System.currentTimeMillis()
        val item = JSONObject().put("id", now.toString()).put("symbol", q.symbol).put("name", q.name).put("currency", q.currency)
            .put("savedPrice", q.price).put("savedAt", now).put("lastPrice", q.price).put("lastAt", now).put("marketAt", q.timeMs)
        q.prevClose?.let { item.put("prevClose", it) }
        change(c) { it.add(item) }
        rearm(c)
        return "Saved " + q.name + " at " + MarketLogic.money(q.price, q.currency)
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
            refresh(c)
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
                    short += " $t today"
                }
                val since = MarketLogic.pctText(price, saved)
                parts.add("Since saved (" + day.format(Date(s.optLong("savedAt"))) + ", " + MarketLogic.money(saved, currency) + ") " + since)
                short += ", $since since saved"
                val market = s.optLong("marketAt")
                val extra = when {
                    !fresh -> "Could not get today's price. Showing the last one."
                    market > 0 && MarketLogic.dayKey(market, zone) != today -> "Market was closed today. Last trading day shown."
                    else -> ""
                }
                val news = JSONArray()
                val query = MarketLogic.companyQuery(name)
                val first = BrainLogic.tokens(query).firstOrNull().orEmpty()
                get(rss("$query share"))?.let { xml ->
                    WatchLogic.news(xml).filter { first.isEmpty() || first in it.title.lowercase(Locale.ENGLISH) }
                        .sortedByDescending { MarketLogic.pubTime(it.date) }.take(SHARE_NEWS)
                        .forEach { news.put(JSONObject().put("t", it.title).put("l", it.link)) }
                }
                items.put(JSONObject().put("name", name).put("text", parts.joinToString("  ·  ")).put("extra", extra)
                    .put("up", MarketLogic.pct(price, saved)).put("news", news))
                note.add("$name $short")
            }
            val list = reports(c).toMutableList()
            list.add(0, JSONObject().put("at", started).put("items", items))
            prefs(c).edit().putString("reports", JSONArray(list.take(MAX_REPORTS)).toString()).apply()
            if (scheduled || got > 0) {
                Watcher.alertNow(c, REPORT_NOTE, "Share report", note.joinToString("\n"), "", MarketActivity::class.java)
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
    fun tickertapeLink(name: String): String {
        val q = MarketLogic.companyQuery(name)
        val slug = get("https://api.tickertape.in/search?text=" + URLEncoder.encode(q, "UTF-8") + "&types=stock&pageNumber=0")
            ?.let { MarketLogic.tickertapeSlug(it) }
        return if (slug != null) "https://www.tickertape.in$slug"
        else "https://www.google.com/search?q=" + URLEncoder.encode("tickertape $q share", "UTF-8")
    }

    /** Tickertape app mein kholo. App na ho ya link uska na ho to browser mein. */
    fun openTickertape(a: android.app.Activity, link: String): Boolean {
        val uri = android.net.Uri.parse(link)
        if (link.startsWith("https://www.tickertape.in")) {
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("in.tickertape"))
                return true
            } catch (e: android.content.ActivityNotFoundException) {
                // App nahi hai ya ye link nahi kholta
            }
        }
        return try {
            a.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        }
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
        if (reportOn(c) && shares(c).isNotEmpty()) armReport(c) else cancel(c, ACTION_REPORT, REPORT_NOTE, "report_next")
        if (newsOn(c)) {
            val at = nextNews(c)
            arm(c, ACTION_NEWS, NEWS_NOTE, "news_next", if (at > System.currentTimeMillis()) at else System.currentTimeMillis() + 5_000L)
        }
    }
}
