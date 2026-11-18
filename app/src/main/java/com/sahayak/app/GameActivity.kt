package com.sahayak.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 10 minute game: GitHub par chalne wale dummy game ka live haal. Nifty / Bank Nifty ke andaze aur dummy option trade,
 * Nifty 50 shares ke andaze aur dummy trade, aaj ka score aur pichhle dinon ka record. Sirf seekhne ke liye.
 */
class GameActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private lateinit var box: LinearLayout
    private lateinit var status: TextView
    private var loading = false
    private val tick = object : Runnable {
        override fun run() {
            load()
            main.postDelayed(this, if (marketOpen()) 60_000L else 10 * 60_000L)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = INK) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun card(tint: Int = 0xFFFFFFFF.toInt()) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(tint)
            cornerRadius = dp(10).toFloat()
        }
    }

    private fun LinearLayout.addCard(view: LinearLayout) = addView(
        view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    )

    /** Do kinare wali line: baayein naam, daayein number. */
    private fun row(left: String, right: String, rightColor: Int = INK, bold: Boolean = false) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(3), 0, dp(3))
        addView(text(left, 14f, bold), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(right, 14f, bold, rightColor).apply { gravity = Gravity.END })
    }

    private fun moneyColor(v: Double) = if (v > 0.5) GREEN else if (v < -0.5) RED else GREY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        page.addView(text("🎯 10 min game", 24f, true))
        page.addView(text("Every 10 minutes in market hours a computer on GitHub guesses where Nifty, Bank Nifty and Nifty 50 shares go in the next 10 and 30 minutes, and makes dummy trades. Dummy money only, for learning.", 13f, false, GREY))
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply {
            text = "🔄 Refresh"
            isAllCaps = false
            setOnClickListener { load() }
        })
        status = text("", 13f, false, GREY)
        top.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        page.addView(top)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        page.addView(text("Learning only, not investment advice. 9 of 10 people who trade F&O lose money (SEBI study). Do not trade real money on these guesses.", 12f, false, GREY).apply { setPadding(0, dp(16), 0, 0) })
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    private fun marketOpen(): Boolean {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        return GameLogic.marketHours(c.get(Calendar.DAY_OF_WEEK), c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
    }

    private fun fetch(url: String): String? {
        val request = Request.Builder().url(url + "?t=" + System.currentTimeMillis() / 30_000).build()
        return try {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (e: IOException) {
            null
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        status.text = "Loading…"
        Thread {
            val live = fetch(GameLogic.LIVE_URL)
            val history = fetch(GameLogic.HISTORY_URL)
            main.post {
                loading = false
                if (isDestroyed) return@post
                try {
                    if (live == null) {
                        status.text = "Could not load. Check the internet and tap Refresh"
                    } else {
                        show(JSONObject(live), history?.let { JSONArray(it) })
                    }
                } catch (e: JSONException) {
                    status.text = "The game data looks broken. Try again in a few minutes"
                }
            }
        }.start()
    }

    private fun show(live: JSONObject, history: JSONArray?) {
        box.removeAllViews()
        val open = live.optBoolean("market_open")
        status.text = "Updated " + live.optString("updated").substringAfter(" ") + " · " + live.optString("updated").substringBefore(" ") +
            if (open) " · game is on" else " · game is resting"
        live.optJSONObject("index")?.let { showIndex(it) }
        showShares(live)
        if (history != null && history.length() > 0) showHistory(history)
    }

    // ---------- Nifty / Bank Nifty ----------

    private fun showIndex(ix: JSONObject) {
        val c = card()
        c.addView(text("Nifty and Bank Nifty", 18f, true))
        val guesses = ix.optJSONArray("guesses") ?: JSONArray()
        for (i in 0 until guesses.length()) {
            val g = guesses.getJSONObject(i)
            val p = g.optJSONObject("p") ?: continue
            val words = listOf("10", "30").filter { p.has(it) }.joinToString("   ") { "$it min ${GameLogic.guess(p.getDouble(it))}" }
            c.addView(row(name(g.optString("index")) + "  " + g.optDouble("price").let { if (it.isNaN()) "" else String.format("%.0f", it) }, words, INK, true))
        }
        if (guesses.length() == 0) c.addView(text("No guess right now", 14f, false, GREY))
        val today = ix.optJSONObject("today")
        today?.keys()?.forEach { index ->
            val byH = today.optJSONObject(index) ?: return@forEach
            c.addView(text(name(index) + " today", 15f, true).apply { setPadding(0, dp(10), 0, 0) })
            for (h in listOf("10", "30")) {
                val s = byH.optJSONObject(h) ?: continue
                c.addView(text("$h min: " + GameLogic.scoreLine(s), 13f))
                c.addView(text("   " + GameLogic.moneyLine(s), 13f, false, moneyColor(s.optDouble("pnl", 0.0))))
                val rightLost = s.optInt("right_but_lost", 0)
                if (rightLost > 0) c.addView(text("   $rightLost trades guessed the direction right but still lost (time decay and costs)", 12f, false, AMBER))
            }
            byH.optJSONObject("10")?.let { c.addView(text(GameLogic.verdict(it), 12f, false, AMBER)) }
        }
        val trades = ix.optJSONArray("trades") ?: JSONArray()
        if (trades.length() > 0) {
            c.addView(text("Dummy option trades (1 lot, live NSE prices)", 15f, true).apply { setPadding(0, dp(10), 0, 0) })
            for (i in trades.length() - 1 downTo 0) {
                val t = trades.getJSONObject(i)
                val left = t.optString("at") + "  " + t.optString("option") + " · " + t.optInt("minutes") + " min\n" +
                    "bought ₹" + t.optDouble("buy") + " × " + t.optInt("qty") +
                    (if (!t.isNull("sell")) "  sold ₹" + t.optDouble("sell") else "") +
                    (if (!t.isNull("index_move_pct")) "  index " + String.format("%+.2f%%", t.optDouble("index_move_pct")) else "")
                val right = when {
                    t.optBoolean("open") -> "open"
                    t.isNull("pnl") -> "not counted"
                    else -> GameLogic.rupees(t.optDouble("pnl"))
                }
                c.addView(row(left, right, if (t.isNull("pnl")) GREY else moneyColor(t.optDouble("pnl"))))
            }
        }
        box.addCard(c)
    }

    private fun name(index: String) = when (index) {
        "NIFTY" -> "Nifty"
        "BANKNIFTY" -> "Bank Nifty"
        else -> index
    }

    // ---------- Shares ----------

    private fun showShares(live: JSONObject) {
        val c = card()
        c.addView(text("Nifty 50 shares", 18f, true))
        val today = live.optJSONObject("today")
        for (h in listOf("10", "30")) {
            val s = today?.optJSONObject(h) ?: continue
            c.addView(text("$h min: " + GameLogic.scoreLine(s), 13f))
            c.addView(text("   " + GameLogic.moneyLine(s), 13f, false, moneyColor(s.optDouble("pnl", 0.0))))
            if (!s.isNull("nifty_buy_pnl_per_trade")) {
                c.addView(text("   Buy NIFTYBEES every time: " + GameLogic.rupees(s.optDouble("nifty_buy_pnl_per_trade")) + " per trade" +
                    (if (!s.isNull("pnl_per_trade")) " · model " + GameLogic.rupees(s.optDouble("pnl_per_trade")) + " per trade" else ""), 12f, false, GREY))
            }
        }
        today?.optJSONObject("10")?.let { c.addView(text(GameLogic.verdict(it), 12f, false, AMBER)) }

        val guesses = live.optJSONArray("guesses") ?: live.optJSONArray("top_guesses") ?: JSONArray()
        if (guesses.length() > 0) {
            c.addView(text("Latest guesses", 15f, true).apply { setPadding(0, dp(10), 0, 0) })
            for (i in 0 until minOf(10, guesses.length())) {
                val g = guesses.getJSONObject(i)
                val p = g.optJSONObject("p")
                val words = if (p != null) listOf("10", "30").filter { p.has(it) }.joinToString("  ") { "$it m ${GameLogic.guess(p.getDouble(it))}" }
                else GameLogic.guess(g.optDouble("p", 0.5))
                c.addView(row(g.optString("symbol") + "  ₹" + g.optDouble("price"), words))
                val news = g.optString("news")
                if (news.isNotEmpty()) {
                    val beat = if (g.isNull("news_beat_pct")) "" else " · 10 years: beat Nifty next day ${g.optInt("news_beat_pct")}%"
                    c.addView(text("   📰 $news$beat", 12f, false, BLUE))
                }
            }
        }
        val openTrades = live.optJSONArray("open_trades") ?: JSONArray()
        if (openTrades.length() > 0) {
            c.addView(text("Open dummy trades (₹" + live.optInt("stake", 10000) + " each)", 15f, true).apply { setPadding(0, dp(10), 0, 0) })
            for (i in 0 until openTrades.length()) {
                val t = openTrades.getJSONObject(i)
                c.addView(row(t.optString("symbol") + " · " + t.optString("minutes", "10") + " min", "bought " + t.optString("at") + " at ₹" + t.optDouble("price"), GREY))
            }
        }
        val s10 = today?.optJSONObject("10")
        val best = s10?.optJSONArray("best")
        val worst = s10?.optJSONArray("worst")
        if ((best?.length() ?: 0) > 0) {
            c.addView(text("Best and worst today (10 min)", 15f, true).apply { setPadding(0, dp(10), 0, 0) })
            for (list in listOf(best, worst)) {
                for (i in 0 until (list?.length() ?: 0)) {
                    val t = list!!.getJSONObject(i)
                    val pnl = t.optDouble("pnl", 0.0)
                    c.addView(row(t.optString("symbol") + " at " + t.optString("at") + "  ₹" + t.optDouble("price") + " → ₹" + t.optDouble("after"), GameLogic.rupees(pnl), moneyColor(pnl)))
                }
            }
        }
        box.addCard(c)
    }

    // ---------- Pichhle din ----------

    private fun showHistory(h: JSONArray) {
        val c = card()
        c.addView(text("Day by day (look at 20 days before trusting anything)", 16f, true))
        c.addView(row("Day", "Shares 10 min · option P&L", GREY))
        for (i in h.length() - 1 downTo maxOf(0, h.length() - 20)) {
            val d = h.getJSONObject(i)
            val s = d.optJSONObject("10")
            var options = 0.0
            var hasOptions = false
            d.optJSONObject("index")?.let { ix ->
                ix.keys().forEach { k ->
                    ix.optJSONObject(k)?.let { byH ->
                        byH.keys().forEach { hh ->
                            byH.optJSONObject(hh)?.let { sc ->
                                if (sc.optInt("trades", 0) > 0) hasOptions = true
                                options += sc.optDouble("pnl", 0.0)
                            }
                        }
                    }
                }
            }
            val right = (s?.let { GameLogic.pct(it.optDouble("model_pct")) } ?: "–") + " right"
            val money = if (hasOptions) GameLogic.rupees(options) else "no option trades"
            c.addView(row(d.optString("day"), "$right · $money", if (hasOptions) moneyColor(options) else INK))
        }
        val total = (0 until h.length()).sumOf { i ->
            var sum = 0.0
            h.getJSONObject(i).optJSONObject("index")?.let { ix ->
                ix.keys().forEach { k -> ix.optJSONObject(k)?.let { b -> b.keys().forEach { hh -> sum += b.optJSONObject(hh)?.optDouble("pnl", 0.0) ?: 0.0 } } }
            }
            sum
        }
        c.addView(row("Option P&L, all days", GameLogic.rupees(total), moneyColor(total), true))
        box.addCard(c)
    }

    companion object {
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF15803D.toInt()
        private const val RED = 0xFFB91C1C.toInt()
        private const val AMBER = 0xFFB45309.toInt()
        private const val BLUE = 0xFF1D4ED8.toInt()
    }
}
