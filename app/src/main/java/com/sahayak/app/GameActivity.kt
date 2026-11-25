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

    /** "100 mein se 89 sahi" dabaya: aaj ke saare andaze ek-ek karke dikhao. */
    private fun openList(kind: String, index: String) = startActivity(
        android.content.Intent(this, GuessListActivity::class.java)
            .putExtra(GuessListActivity.KIND, kind).putExtra(GuessListActivity.INDEX, index).putExtra(GuessListActivity.MINUTES, 10)
    )

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
        showHealth(live)
        live.optJSONObject("index")?.let { showIndex(it) }
        showShares(live)
        showLessons(live)
        if (history != null && history.length() > 0) showHistory(history)
        showRules(live)
    }

    // ---------- Khel theek chal raha hai? ----------

    private fun showHealth(live: JSONObject) {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val nowDay = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(c.time)
        val warn = GameLogic.staleWords(live.optString("updated"), nowDay, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), marketOpen())
        val missed = live.optJSONArray("missed")
        val lag = if (live.isNull("data_lag_min")) -1 else live.optInt("data_lag_min", -1)
        if (warn == null && (missed == null || missed.length() == 0) && lag < 3) return
        val k = card(0xFFFEE2E2.toInt())
        warn?.let { k.addView(text(it, 15f, true, RED)) }
        if (missed != null && missed.length() > 0) {
            val list = (0 until missed.length()).map { missed.optString(it) }
            k.addView(text("⏸️ In samay koi andaza nahi bana: " + list.take(12).joinToString(", ") + if (list.size > 12) " …" else "", 14f, false, RED))
        }
        if (lag >= 3) k.addView(text("🐢 Bhaav $lag minute purana aa raha hai (Yahoo der se de raha hai)", 14f, false, AMBER))
        box.addCard(k)
    }

    // ---------- Aaj ki seekh, hafte/mahine ka hisaab ----------

    private fun showLessons(live: JSONObject) {
        val c = card(0xFFF0FDF4.toInt())
        c.addView(text("📚 Aaj ki seekh", 20f, true))
        val ls = live.optJSONArray("lessons")
        if (ls == null || ls.length() == 0) c.addView(text("Abhi kuch seekhne layak nahi hua", 15f, false, GREY))
        else for (i in 0 until ls.length()) c.addView(text("• " + ls.optString(i), 15f))

        val today = live.optJSONObject("today")
        val s10 = today?.optJSONObject("10")
        val s30 = today?.optJSONObject("30")
        // Kharcha alag-alag (#78-79)
        val gross = (s10?.optDouble("pnl_gross", 0.0) ?: 0.0) + (s30?.optDouble("pnl_gross", 0.0) ?: 0.0)
        val fees = (s10?.optDouble("fees", 0.0) ?: 0.0) + (s30?.optDouble("fees", 0.0) ?: 0.0)
        val slip = (s10?.optDouble("slip", 0.0) ?: 0.0) + (s30?.optDouble("slip", 0.0) ?: 0.0)
        if (fees > 0.5) {
            c.addView(gap())
            c.addView(text("🧾 Shares ka hisaab", 17f, true))
            c.addView(row("Bina kharche", GameLogic.livePnl(gross), moneyColor(gross), 15f))
            c.addView(row("Brokerage, tax, charges", GameLogic.rupees(-fees), RED, 15f))
            c.addView(row("Bid-ask farq (andaaza)", GameLogic.rupees(-slip), RED, 15f))
            c.addView(row("Kharche ke baad", GameLogic.livePnl(gross - fees - slip), moneyColor(gross - fees - slip), 15f))
        }
        // Kis samay model achha (#81)
        s10?.optJSONObject("by_time")?.let { bt ->
            if (bt.length() > 0) {
                c.addView(gap())
                c.addView(text("🕘 Kis samay kitne sahi (10 minute wale)", 17f, true))
                for (k in listOf("subah", "dopahar", "shaam")) bt.optJSONObject(k)?.let { b ->
                    c.addView(row(k.replaceFirstChar { it.uppercase() }, GameLogic.outOf100(b.optDouble("model_pct")), GREY, 15f))
                }
            }
        }
        // Khabar wale andaze (#82)
        if ((s10?.optInt("news_guesses", 0) ?: 0) > 0) {
            c.addView(row("📰 Khabar wale shares", GameLogic.outOf100(s10!!.optDouble("news_pct")), GREY, 15f))
        }
        // Hafta / mahina / lagatar (#74-77, #83-84, #96)
        live.optJSONObject("summary")?.let { sm ->
            c.addView(gap())
            c.addView(text("📅 Hafta aur mahina (Bank Nifty ke bina)", 17f, true))
            for ((label, key) in listOf("Pichhle 5 din" to "week", "Pichhle 22 din" to "month", "Shuru se" to "all")) sm.optJSONObject(key)?.let { w ->
                val v = w.optDouble("pnl", 0.0)
                c.addView(row(label + " · " + w.optInt("trades") + " trade", GameLogic.livePnl(v), moneyColor(v), 15f))
            }
            val lose = sm.optInt("lose_streak", 0)
            if (lose > 0) c.addView(text("😟 Lagatar $lose din se nuksaan", 15f, false, RED))
            sm.optJSONObject("best_day")?.let { c.addView(row("Sabse achha din " + it.optString("day").takeLast(5), GameLogic.livePnl(it.optDouble("pnl")), moneyColor(it.optDouble("pnl")), 14f)) }
            sm.optJSONObject("worst_day")?.let { c.addView(row("Sabse bura din " + it.optString("day").takeLast(5), GameLogic.livePnl(it.optDouble("pnl")), moneyColor(it.optDouble("pnl")), 14f)) }
            c.addView(text(GameLogic.realMoneyWords(sm.optInt("win_streak", 0)), 15f, true, AMBER))
        }
        box.addCard(c)
    }

    private fun showRules(live: JSONObject) {
        val rules = live.optJSONArray("rules") ?: return
        if (rules.length() == 0) return
        val c = card()
        c.addView(text("🛠️ Niyam kab badle", 18f, true))
        live.optJSONObject("settings")?.let { st ->
            c.addView(text("Abhi: pakkapan " + GameLogic.pct(st.optDouble("sure") * 100) + "+ · stop-loss " + st.optDouble("stop_pct") +
                "% · target " + st.optDouble("target_pct") + "% · din ki had " + GameLogic.rupees(st.optDouble("day_loss_limit")) +
                " aur " + st.optInt("max_trades") + " trade", 14f, false, GREY))
        }
        for (i in 0 until rules.length()) rules.optJSONObject(i)?.let { r ->
            c.addView(text("• " + r.optString("day").takeLast(5) + ": " + r.optString("text"), 14f))
        }
        c.addView(text("Ek baar mein ek niyam badlo aur 1 hafta dekho, tabhi pata chalega kya kaam aaya.", 13f, false, GREY))
        box.addCard(c)
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
            for (h in listOf("10", "30", "60")) {
                if (p == null || !p.has(h)) continue
                val v = p.getDouble(h)
                c.addView(row("Agle $h minute", GameLogic.guess(v), guessColor(v)))
            }
            today?.optJSONObject(index)?.optJSONObject("10")?.let { s ->
                val (face, _) = GameLogic.verdict(s)
                if (s.optInt("guesses", 0) > 0) c.addView(row("Aaj ke andaze 👆", "$face " + GameLogic.outOf100(s.optDouble("model_pct")), BLUE, 15f).apply {
                    setOnClickListener { openList("index", index) }
                })
            }
        }

        // Nakli option trade
        val on = ix.optJSONArray("trade_on")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: setOf("NIFTY")
        val (trades, pnl) = GameLogic.indexMoney(today, on)
        var rightLost = 0
        var itm = 0.0
        var itmN = 0
        today?.keys()?.forEach { k ->
            if (k !in on) return@forEach
            today.optJSONObject(k)?.let { byH ->
                byH.keys().forEach { h ->
                    byH.optJSONObject(h)?.let { s ->
                        rightLost += s.optInt("right_but_lost", 0)
                        if (!s.isNull("itm_pnl") && s.has("itm_pnl")) { itm += s.optDouble("itm_pnl"); itmN++ }
                    }
                }
            }
        }
        c.addView(gap())
        c.addView(text("🎮 Nakli paise ka khel (sirf Nifty option)", 17f, true))
        c.addView(text(GameLogic.moneyWords(trades, pnl), 15f, false, if (trades == 0) GREY else moneyColor(pnl)))
        if (itmN > 0) c.addView(row("Agar 1 strike ITM lete (tulna)", GameLogic.livePnl(itm), moneyColor(itm), 14f))
        ix.optString("big_day").takeIf { it.isNotBlank() && it != "null" }?.let { c.addView(text("🔔 Aaj bada din: $it", 14f, false, BLUE)) }
        ix.optString("gate_why").takeIf { it.isNotBlank() && it != "null" && trades == 0 }?.let { c.addView(text("🚫 Trade kyun nahi: $it", 14f, false, AMBER)) }
        // Bank Nifty ab nahi khelte: purana paisa alag dikhao, Nifty mein mat jodo
        val bank = GameLogic.indexMoney(today, setOf("BANKNIFTY"))
        if ("BANKNIFTY" !in on && bank.first > 0) {
            c.addView(row("Bank Nifty (ab band, alag gina)", GameLogic.livePnl(bank.second), GREY, 14f))
        }
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
            val extra = listOfNotNull(
                t.optString("exit").takeIf { it.isNotBlank() && it != "null" }?.let { "Band: " + GameLogic.exitWords(it) + (t.optString("exit_at").takeIf { a -> a.isNotBlank() && a != "null" }?.let { a -> " ($a)" } ?: "") },
                t.optString("why_lost").takeIf { it.isNotBlank() && it != "null" }?.let { "Nuksaan kyun: $it" },
            ).joinToString("\n")
            c.addView(row(if (extra.isEmpty()) what else what + "\n" + extra, result, color, 14f))
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
            c.addView(row("Computer aaj 👆", "$face " + GameLogic.outOf100(s10.optDouble("model_pct")), BLUE, 17f).apply {
                setOnClickListener { openList("shares", "") }
            })
            c.addView(row("Sikka uchhalne se", "🪙 100 mein se 50 sahi", GREY, 15f))
        }
        c.addView(text(words, 14f, false, AMBER))
        val (trades, pnl) = GameLogic.sharesMoney(live.optJSONObject("today"))
        c.addView(text("🎮 " + GameLogic.moneyWords(trades, pnl) + " (10 + 30 minute dono)", 15f, false, if (trades == 0) GREY else moneyColor(pnl)))
        live.optJSONObject("gate")?.let { g ->
            for (h in listOf("10", "30")) g.optJSONObject(h)?.let { x ->
                if (!x.optBoolean("ok")) c.addView(text("🚫 $h minute wale trade band: " + x.optString("why"), 14f, false, AMBER))
            }
        }
        val skipped = live.optJSONArray("skipped")
        if (skipped != null && skipped.length() > 0) {
            c.addView(text("Pakke andaze par bhi trade kyun nahi hua:", 14f, true, GREY))
            for (i in 0 until minOf(3, skipped.length())) skipped.optJSONObject(i)?.let {
                c.addView(text("   • " + it.optString("why") + " (" + it.optInt("count") + " baar)", 13f, false, GREY))
            }
        }

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

        val all = mutableListOf<JSONObject>()
        for (h in listOf("10", "30")) live.optJSONObject("today")?.optJSONObject(h)?.let { s ->
            for (key in listOf("best", "worst")) s.optJSONArray(key)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { all += it } }
        }
        if (all.isNotEmpty()) {
            c.addView(gap())
            c.addView(text("🏆 Aaj ka sabse achha aur sabse bura nakli trade (tap = Tickertape)", 16f, true))
            listOfNotNull(all.maxByOrNull { it.optDouble("pnl", 0.0) }, all.minByOrNull { it.optDouble("pnl", 0.0) }).distinct().forEach { t ->
                val v = t.optDouble("pnl", 0.0)
                val at = t.optString("at")
                val sold = t.optString("exit_at").takeIf { it.isNotBlank() && it != "null" } ?: GameLogic.clock24(at, t.optInt("minutes", 10))
                val how = t.optString("exit").takeIf { it.isNotBlank() && it != "null" }?.let { " (" + GameLogic.exitWords(it) + ")" } ?: ""
                val sym = t.optString("symbol")
                c.addView(row(sym + "  " + GameLogic.sideWords(t.optString("side", "long")) + "\nLiya " + GameLogic.clock24(at) + " · Becha " + GameLogic.clock24(sold) + how,
                    GameLogic.tradeResult(v), moneyColor(v), 15f).apply {
                    setOnClickListener { status.text = Market.openTickertape(this@GameActivity, null, sym).ifEmpty { status.text.toString() } }
                })
            }
        }
        // Kis share par sabse zyada nuksaan (#80)
        val bySym = mutableMapOf<String, Double>()
        for (h in listOf("10", "30")) live.optJSONObject("today")?.optJSONObject(h)?.optJSONArray("by_symbol")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { bySym[it.optString("symbol")] = (bySym[it.optString("symbol")] ?: 0.0) + it.optDouble("pnl", 0.0) }
        }
        if (bySym.size > 1) {
            c.addView(gap())
            c.addView(text("📊 Share ke hisaab se", 16f, true))
            bySym.entries.sortedBy { it.value }.take(5).forEach { (k, v) -> c.addView(row(k, GameLogic.livePnl(v), moneyColor(v), 14f)) }
        }
        box.addCard(c)
    }

    // ---------- Report card ----------

    private fun showHistory(h: JSONArray) {
        val c = card()
        c.addView(text("📒 Roz ka report card", 20f, true))
        c.addView(text("20 din ka report dekh kar hi pata chalega ki computer kaam ka hai ya nahi. (Bank Nifty ka paisa isme nahi)", 14f, false, GREY))
        var total = 0.0
        var anyTrades = false
        for (i in h.length() - 1 downTo 0) {
            val d = h.getJSONObject(i)
            var money = 0.0
            var trades = 0
            // Shares: 10 aur 30 minute dono (pehle sirf 10 gina jaata tha). Index: sirf Nifty (Bank Nifty ab nahi khelte).
            GameLogic.sharesMoney(d).let { (n, v) -> trades += n; money += v }
            GameLogic.indexMoney(d.optJSONObject("index"), setOf("NIFTY")).let { (n, v) -> trades += n; money += v }
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
