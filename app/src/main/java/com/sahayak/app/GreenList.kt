package com.sahayak.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Tickertape par jin shares ka poora scorecard hara hai, unki list. */
object GreenList {
    private const val PREFS = "sahayak_green"
    private const val PAGE = 100
    private const val GAP_MS = 250L
    private const val MIN_ITEMS = 5
    const val MIN_GREEN = 4
    const val MAX_GREEN = 6
    private const val GIVE_UP = 12

    /** Market cap ke hisaab se teen group: rank 1-100, 101-250, 251-750. */
    data class Cap(val id: String, val label: String, val from: Int, val count: Int)

    val CAPS = listOf(Cap("small", "Smallcap", 250, 500), Cap("mid", "Midcap", 100, 150), Cap("large", "Largecap", 0, 100))

    /** Abhi kis group ki list ban rahi hai. Khaali matlab koi nahi. */
    @Volatile
    var runningCap = ""
        private set

    private val busy = AtomicBoolean(false)

    @Volatile
    private var stop = false

    /** Screen par dikhane ke liye: abhi kya chal raha hai. */
    @Volatile
    var progress = ""
        private set

    fun running() = busy.get()

    fun stop() {
        stop = true
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pichhli bani list: at, checked, rows [{sid,name,ticker,slug,green,total}]. */
    fun saved(c: Context, cap: Cap): JSONObject? = prefs(c).getString("list_" + cap.id, null)?.let {
        try {
            JSONObject(it)
        } catch (e: org.json.JSONException) {
            null
        }
    }

    /** Sabse badi companies ka scorecard ek-ek karke dekho. Jawab user ko dikhane ke liye. */
    fun run(c: Context, cap: Cap): String {
        if (!busy.compareAndSet(false, true)) return "A list is already being made. Wait for it or tap Stop"
        stop = false
        runningCap = cap.id
        try {
            val want = cap.count
            progress = "Getting the list of " + cap.label + " companies"
            val stocks = mutableListOf<MarketLogic.Stock>()
            var offset = cap.from
            while (stocks.size < want && !stop) {
                val body = JSONObject().put("match", JSONObject()).put("sortBy", "mrktCapf").put("sortOrder", -1)
                    .put("project", JSONArray().put("subindustry").put("mrktCapf").put("lastPrice"))
                    .put("offset", offset).put("count", PAGE).put("sids", JSONArray()).toString()
                val page = Market.tape("https://api.tickertape.in/screener/query", body)?.let { MarketLogic.parseScreener(it) }.orEmpty()
                if (page.isEmpty()) break
                page.forEach { s -> if (stocks.none { it.sid == s.sid }) stocks.add(s) }
                offset += PAGE
            }
            if (stocks.isEmpty()) return "Could not get the company list from Tickertape. " + Market.lastError
            val list = stocks.take(want)
            val rows = JSONArray()
            var checked = 0
            var failed = 0
            var full = 0
            var thin = 0
            for (s in list) {
                if (stop) break
                progress = cap.label + ": checked $checked of " + list.size + ". All green so far: $full"
                val card = Market.tape("https://analyze.api.tickertape.in/stocks/scorecard/" + s.sid)?.let { MarketLogic.parseScorecard(it) }
                if (card == null) {
                    failed++
                    if (checked == 0 && failed >= GIVE_UP) return "Tickertape did not give the scorecard. " + Market.lastError
                } else {
                    checked++
                    val (green, total) = card
                    if (total < MIN_ITEMS) thin++
                    if (total >= MIN_ITEMS && green >= MIN_GREEN) {
                        if (green == total) full++
                        rows.put(JSONObject().put("sid", s.sid).put("name", s.name).put("ticker", s.ticker).put("slug", s.slug)
                            .put("green", green).put("total", total))
                    }
                }
                try {
                    Thread.sleep(GAP_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
            if (checked == 0) return "Tickertape did not give the scorecard. " + Market.lastError
            prefs(c).edit().putString("list_" + cap.id, JSONObject().put("at", System.currentTimeMillis()).put("checked", checked)
                .put("rows", rows).toString()).apply()
            if (thin == checked) return "Tickertape gave only a part of the scorecard, so the list cannot be made. Send a screenshot of this"
            return (if (stop) "Stopped. " else "") + cap.label + ": checked $checked companies. All green: $full"
        } catch (e: org.json.JSONException) {
            return "Something went wrong. Try again"
        } finally {
            progress = ""
            runningCap = ""
            busy.set(false)
        }
    }
}
