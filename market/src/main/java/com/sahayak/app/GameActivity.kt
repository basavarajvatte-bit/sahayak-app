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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 10 minute ka khel: GitHub par chalne wale nakli game ka live haal, bilkul aasaan shabdon mein.
 * Nifty / Bank Nifty ke andaze aur nakli option trade, Nifty 50 shares ke andaze, aur roz ka report card.
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
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(tint)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun LinearLayout.addCard(view: LinearLayout) = addView(
        view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    )

    /** Do kinare wali line: baayein naam, daayein jawab. */
    private fun row(left: String, right: String, rightColor: Int = INK, size: Float = 16f) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        addView(text(left, size), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(right, size, true, rightColor).apply { gravity = Gravity.END })
    }

    private fun gap() = text("", 4f)

    private fun moneyColor(v: Double) = if (v > 0.5) GREEN else if (v < -0.5) RED else GREY

    private fun guessColor(p: Double) = when {
        !GameLogic.isSure(p) -> GREY
        p >= 0.5 -> GREEN
        else -> RED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        page.addView(text("🎯 10 minute ka khel", 26f, true))

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply {
            text = "🔄 Refresh"
            isAllCaps = false
            setOnClickListener { load() }
        })
        status = text("", 14f, false, GREY)
        top.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        page.addView(top)

        val how = card(0xFFEFF6FF.toInt())
        how.addView(text("🤔 Ye khel kya hai?", 18f, true))
        how.addView(text("Har 10 minute computer andaza lagata hai: agle 10 minute mein bhaav upar jayega ya neeche?", 15f))
        how.addView(text("10 minute baad wo khud check karta hai: andaza sahi tha ya galat. Bilkul mausam ke andaze jaisa. 🌦️", 15f))
        how.addView(text("Jab computer ko pakka lagta hai, tab wo nakli paise se khelta hai. Paise nakli hain, sirf seekhne ke liye. 🎮", 15f))
        how.addView(text("🪙 Sikka uchhalo to 100 mein se 50 baar sahi hota hai. Computer ko isse zyada sahi hona chahiye, tabhi wo kaam ka hai.", 15f))
        page.addCard(how)

        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)

        val warn = card(0xFFFFF4E5.toInt())
        warn.addView(text("⚠️ Yaad rakho", 17f, true, AMBER))
        warn.addView(text("Ye sirf khel hai. Is par asli paisa mat lagana. Option trading (F&O) karne wale 10 mein se 9 log paisa ganvaate hain (SEBI ki study).", 15f))
        page.addCard(warn)
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
        status.text = "Laa raha hoon…"
        Thread {
            val live = fetch(GameLogic.LIVE_URL)
            val history = fetch(GameLogic.HISTORY_URL)
            main.post {
                loading = false
                if (isDestroyed) return@post
                try {
                    if (live == null) status.text = "Internet check karo, phir 🔄 Refresh dabao"
                    else show(JSONObject(live), history?.let { JSONArray(it) })
                } catch (e: JSONException) {
                    status.text = "Data thoda gadbad hai. Kuch minute baad Refresh dabao"
                }
            }
        }.start()
    }

    /** "2026-11-18 14:15" -> "2:15 baje" */
    private fun clock(stamp: String): String = try {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(stamp)!!
        SimpleDateFormat("HH:mm", Locale.ENGLISH).format(t)
    } catch (e: java.text.ParseException) {
        stamp
    }

    private fun show(live: JSONObject, history: JSONArray?) {
        box.removeAllViews()
        val on = live.optBoolean("market_open")
        status.text = (if (on) "✅ Khel chal raha hai" else "😴 Khel aaram kar raha hai (bazaar band)") + " · " + clock(live.optString("updated"))
        live.optJSONObject("index")?.let { showIndex(it) }
        showShares(live)
        if (history != null && history.length() > 0) showHistory(history)
    }

    // ---------- Nifty / Bank Nifty ----------

    private fun name(index: String) = when (index) {
        "NIFTY" -> "Nifty"
        "BANKNIFTY" -> "Bank Nifty"
        else -> index
    }

    private fun showIndex(ix: JSONObject) {
        val c = card()
        c.addView(text("📈 Nifty aur Bank Nifty", 20f, true))
        val guesses = ix.optJSONArray("guesses") ?: JSONArray()
        val today = ix.optJSONObject("today")
        if (guesses.length() == 0 && today == null) c.addView(text("Abhi koi andaza nahi", 15f, false, GREY))
        for (i in 0 until guesses.length()) {
            val g = guesses.getJSONObject(i)
            val index = g.optString("index")
            val price = g.optDouble("price")
            c.addView(gap())
            c.addView(text(name(index) + if (price.isNaN()) "" else "  " + String.format(Locale.ENGLISH, "%,.0f", price), 18f, true))
            val p = g.optJSONObject("p")
            for (h in listOf("10", "30")) {
                if (p == null || !p.has(h)) continue
                val v = p.getDouble(h)
                c.addView(row("Agle $h minute", GameLogic.guess(v), guessColor(v)))
            }
            today?.optJSONObject(index)?.optJSONObject("10")?.let { s ->
                val (face, _) = GameLogic.verdict(s)
                if (s.optInt("guesses", 0) > 0) c.addView(row("Aaj ke andaze", "$face " + GameLogic.outOf100(s.optDouble("model_pct")), INK, 15f))
            }
        }

        // Nakli option trade
        var trades = 0
        var pnl = 0.0
        var rightLost = 0
        today?.keys()?.forEach { k ->
            today.optJSONObject(k)?.let { byH ->
                byH.keys().forEach { h ->
                    byH.optJSONObject(h)?.let { s ->
                        trades += s.optInt("trades", 0)
                        pnl += s.optDouble("pnl", 0.0)
                        rightLost += s.optInt("right_but_lost", 0)
                    }
                }
            }
        }
        c.addView(gap())
        c.addView(text("🎮 Nakli paise ka khel (options)", 17f, true))
        c.addView(text(GameLogic.moneyWords(trades, pnl), 15f, false, if (trades == 0) GREY else moneyColor(pnl)))
        // Zerodha jaisa: abhi khule trade ka live munafa/nuksaan, bada aur rang ke saath
        if (!ix.isNull("open_pnl") && ix.optInt("open_count") > 0) {
            val open = ix.optDouble("open_pnl")
            c.addView(row("🔴 LIVE · khule trade (" + ix.optInt("open_count") + ")", GameLogic.livePnl(open), moneyColor(open), 18f))
        }
        val list = ix.optJSONArray("trades") ?: JSONArray()
        for (i in list.length() - 1 downTo 0) {
            val t = list.getJSONObject(i)
            val index = t.optString("index")
            val what = GameLogic.tradeLines(t.optString("at"), t.optInt("minutes"), t.optBoolean("open"), index, name(index), t.optString("option"),
                t.optString("expiry"), t.optInt("qty"), t.optDouble("buy"),
                if (t.isNull("sell")) null else t.optDouble("sell"),
                if (t.isNull("costs")) null else t.optDouble("costs"),
                if (t.isNull("now")) null else t.optDouble("now"), t.optString("now_at"))
            val live = t.optBoolean("open") && !t.isNull("now_pnl")
            val result = when {
                live -> "LIVE " + GameLogic.livePnl(t.optDouble("now_pnl"))
                t.optBoolean("open") -> "chal raha hai ⏳"
                t.isNull("pnl") -> "gina nahi"
                else -> GameLogic.tradeResult(t.optDouble("pnl"))
            }
            val color = when {
                live -> moneyColor(t.optDouble("now_pnl"))
                t.isNull("pnl") -> GREY
                else -> moneyColor(t.optDouble("pnl"))
            }
            c.addView(row(what, result, color, 14f))
        }
        if (rightLost > 0) {
            c.addView(text("🧊 $rightLost baar andaza sahi tha, phir bhi paisa gaya. Kyun? Option ka bhaav baraf ki tarah har minute thoda pighalta hai, aur har trade par kharcha bhi lagta hai.", 14f, false, AMBER))
        }
        box.addCard(c)
    }

    // ---------- Shares ----------

    private fun showShares(live: JSONObject) {
        val c = card()
        c.addView(text("🏢 Nifty 50 ke shares", 20f, true))
        val s10 = live.optJSONObject("today")?.optJSONObject("10")
        val (face, words) = GameLogic.verdict(s10)
        if (s10 != null && s10.optInt("guesses", 0) > 0) {
            c.addView(row("Computer aaj", "$face " + GameLogic.outOf100(s10.optDouble("model_pct")), INK, 17f))
            c.addView(row("Sikka uchhalne se", "🪙 100 mein se 50 sahi", GREY, 15f))
        }
        c.addView(text(words, 14f, false, AMBER))
        val trades = s10?.optInt("trades", 0) ?: 0
        val pnl = s10?.optDouble("pnl", 0.0) ?: 0.0
        c.addView(text("🎮 " + GameLogic.moneyWords(trades, pnl), 15f, false, if (trades == 0) GREY else moneyColor(pnl)))

        val guesses = live.optJSONArray("guesses") ?: live.optJSONArray("top_guesses") ?: JSONArray()
        val sure = mutableListOf<Pair<JSONObject, Double>>()
        var unsure = 0
        for (i in 0 until guesses.length()) {
            val g = guesses.getJSONObject(i)
            val p = g.optJSONObject("p")?.optDouble("10", Double.NaN) ?: g.optDouble("p", Double.NaN)
            if (p.isNaN()) continue
            if (GameLogic.isSure(p)) sure += g to p else unsure++
        }
        c.addView(gap())
        c.addView(text("👀 Computer ki pasand (agle 10 minute)", 17f, true))
        if (sure.isEmpty()) c.addView(text("Abhi kisi share par computer ko pakka nahi laga ❔", 15f, false, GREY))
        sure.forEach { (g, p) ->
            c.addView(row(g.optString("symbol") + "  ₹" + g.optDouble("price"), GameLogic.guess(p), guessColor(p), 15f))
            val news = g.optString("news")
            if (news.isNotEmpty()) c.addView(text("   📰 Taaza khabar: $news", 13f, false, BLUE))
        }
        if (unsure > 0) c.addView(text("Baaki shares ke liye: ❔ pata nahi", 14f, false, GREY))

        val best = s10?.optJSONArray("best")
        val worst = s10?.optJSONArray("worst")
        if ((best?.length() ?: 0) > 0) {
            c.addView(gap())
            c.addView(text("🏆 Aaj ka sabse achha aur sabse bura nakli trade", 16f, true))
            listOfNotNull(best?.optJSONObject(0), worst?.optJSONObject(0)).forEach { t ->
                val v = t.optDouble("pnl", 0.0)
                val at = t.optString("at")
                c.addView(row(t.optString("symbol") + "\nLiya " + GameLogic.clock24(at) + " · Becha " + GameLogic.clock24(at, 10),
                    GameLogic.tradeResult(v), moneyColor(v), 15f))
            }
        }
        box.addCard(c)
    }

    // ---------- Report card ----------

    private fun showHistory(h: JSONArray) {
        val c = card()
        c.addView(text("📒 Roz ka report card", 20f, true))
        c.addView(text("20 din ka report dekh kar hi pata chalega ki computer kaam ka hai ya nahi.", 14f, false, GREY))
        var total = 0.0
        var anyTrades = false
        for (i in h.length() - 1 downTo 0) {
            val d = h.getJSONObject(i)
            var money = 0.0
            var trades = 0
            d.optJSONObject("10")?.let { trades += it.optInt("trades", 0); money += it.optDouble("pnl", 0.0) }
            d.optJSONObject("index")?.let { ix ->
                ix.keys().forEach { k ->
                    ix.optJSONObject(k)?.let { b -> b.keys().forEach { hh -> b.optJSONObject(hh)?.let { s -> trades += s.optInt("trades", 0); money += s.optDouble("pnl", 0.0) } } }
                }
            }
            total += money
            if (trades > 0) anyTrades = true
            if (i < h.length() - 20) continue
            val s = d.optJSONObject("10")
            val (face, _) = GameLogic.verdict(s)
            val right = s?.let { GameLogic.outOf100(it.optDouble("model_pct")) } ?: "–"
            val day = try {
                SimpleDateFormat("d MMM", Locale.ENGLISH).format(SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(d.optString("day"))!!)
            } catch (e: java.text.ParseException) {
                d.optString("day")
            }
            c.addView(row("$day  $face $right", if (trades > 0) GameLogic.rupees(money) else "koi trade nahi", if (trades > 0) moneyColor(money) else GREY, 15f))
        }
        if (anyTrades) c.addView(row("Ab tak kul nakli paisa", GameLogic.tradeResult(total), moneyColor(total), 16f))
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
