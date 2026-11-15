package com.sahayak.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pattern mein lock hue ek share ka poora hisaab: khabar ke din se kya badla, Nifty ke saath, aur graph. */
class PatternLockActivity : Activity() {
    private lateinit var lock: JSONObject
    private lateinit var status: TextView
    private lateinit var chart: ChartView
    private lateinit var pick: TextView
    private val main = Handler(Looper.getMainLooper())
    private val dayFmt = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
    private var days: List<String> = emptyList()
    private var values = DoubleArray(0)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = INK) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD) else typeface = Typeface.DEFAULT
        setPadding(0, dp(3), 0, dp(2))
    }

    private fun button(words: String, action: () -> Unit) = Button(this).apply {
        text = words
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun color(change: Double) = if (change > 0.004) GREEN else if (change < -0.004) RED else GREY

    private fun badge(words: String, change: Double) = text(words, 16f, true, 0xFFFFFFFF.toInt()).apply {
        setPadding(dp(9), dp(3), dp(9), dp(3))
        background = rounded(color(change), 6)
    }

    private fun card(tint: Int = 0xFFF4F6FA.toInt()) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(tint, 12)
        setPadding(dp(14), dp(10), dp(14), dp(12))
    }

    private fun wide(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun part(weight: Float) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val found = Patterns.locks(this).firstOrNull { it.optString("id") == id }
        if (found == null) {
            finish()
            return
        }
        lock = found
        val pattern = PatternLogic.byId(lock.optString("p"))
        val price = lock.optDouble("price", 0.0)
        val lockPrice = lock.optDouble("lockPrice", 0.0)
        val since = if (lock.has("now")) lock.optDouble("now") else MarketLogic.pct(price, lockPrice)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(18), dp(22), dp(18), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(page)
        })

        page.addView(text(lock.optString("name"), 22f, true))
        val tags = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tags.addView(text(lock.optString("symbol").substringBefore('.'), 14f, true, GREY).apply { letterSpacing = 0.15f })
        pattern?.let {
            tags.addView(text(it.label, 12f, true, 0xFF175CD3.toInt()).apply {
                background = rounded(0xFFE8F1FF.toInt(), 5)
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        }
        page.addView(tags)
        lock.optString("detail").takeIf { it.isNotEmpty() }?.let { page.addView(text(it, 15f, true, 0xFFB54700.toInt())) }

        // ----- Abhi ka bhaav aur lock se badlav -----
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        top.addView(text(MarketLogic.money(price, "INR"), 32f, true))
        top.addView(badge("Since lock " + PatternLogic.signed(since), since),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        page.addView(top)
        if (lock.has("nNow")) {
            val nifty = lock.optDouble("nNow")
            val extra = since - nifty
            page.addView(text("Nifty in the same time " + PatternLogic.signed(nifty) + "   ·   " + (if (extra >= 0) "Beat Nifty by " else "Behind Nifty by ") +
                String.format(Locale.ENGLISH, "%.2f", Math.abs(extra)) + "%", 14f, true, color(extra)))
        }

        // ----- Kab kya hua -----
        val dates = card(0xFFFFF6E5.toInt())
        dates.addView(text("Dates", 13f, true, 0xFFB54700.toInt()))
        val event = lock.optLong("eventAt")
        if (event > 0) dates.addView(text("News came:  " + dayFmt.format(Date(event)), 15f))
        dates.addView(text("Locked:  " + dayFmt.format(Date(lock.optLong("lockAt"))) + " at " + MarketLogic.money(lockPrice, "INR"), 15f, true))
        dates.addView(text(
            if (lock.optBoolean("eventDay")) "Lock price is the closing price on the news day (" + lock.optString("baseDate") + "), so the change counts from the news."
            else "Lock price is the verified price when Sahayak locked it.", 12f, false, GREY
        ))
        if (lock.optBoolean("adjusted")) dates.addView(text("Adjusted for split, bonus or dividend since the lock.", 12f, false, 0xFFB54700.toInt()))
        page.addView(dates, wide(12))

        // ----- 1 din, 1 hafta, 1 mahina, 3 mahine -----
        val steps = card()
        steps.addView(text("How it moved after the lock", 13f, true, GREY))
        val head = LinearLayout(this)
        head.addView(text("", 13f), part(1.3f))
        head.addView(text("Share", 13f, true, GREY), part(1f))
        head.addView(text("Nifty", 13f, true, GREY), part(1f))
        head.addView(text("vs Nifty", 13f, true, GREY), part(1f))
        steps.addView(head)
        val mine = lock.optJSONArray("steps")
        val index = lock.optJSONArray("nSteps")
        val names = PatternLogic.STEP_NAMES + "So far"
        names.forEachIndexed { k, name ->
            val share = if (k < PatternLogic.STEPS.size) mine?.takeIf { !it.isNull(k) }?.optDouble(k) else since
            val nifty = if (k < PatternLogic.STEPS.size) index?.takeIf { !it.isNull(k) }?.optDouble(k) else lock.optDouble("nNow", Double.NaN).takeIf { !it.isNaN() }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(text("After " + name, 14f, true), part(1.3f))
            if (share == null) {
                row.addView(text("comes later", 13f, false, GREY), part(3f))
            } else {
                row.addView(text(PatternLogic.signed(share), 14f, true, color(share)), part(1f))
                row.addView(text(nifty?.let { PatternLogic.signed(it) } ?: "—", 14f, false, GREY), part(1f))
                val diff = nifty?.let { share - it }
                row.addView(text(diff?.let { PatternLogic.signed(it) } ?: "—", 14f, true, diff?.let { color(it) } ?: GREY), part(1f))
            }
            steps.addView(row)
        }
        val checked = lock.optLong("trackAt")
        steps.addView(text(if (checked > 0) "Updated " + SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(checked)) else "Not updated yet. Tap Update results now on the list.", 12f, false, GREY))
        page.addView(steps, wide(12))

        // ----- Graph -----
        page.addView(text("Price since a little before the lock", 16f, true), wide(16))
        pick = text(" ", 13f, false, GREY)
        page.addView(pick)
        chart = ChartView(this)
        chart.onPick = { i -> pick.text = if (i in values.indices) MarketLogic.money(values[i], "INR") + "  ·  " + days[i] else " " }
        page.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
        status = text("Getting the chart", 13f, false, GREY)
        page.addView(status)
        loadChart()

        // ----- Khabar aur buttons -----
        val news = card()
        news.addView(text("The news", 13f, true, GREY))
        news.addView(text(lock.optString("title"), 15f, false, BLUE).apply {
            setOnClickListener { lock.optString("link").takeIf { it.startsWith("http") }?.let { open(it) } }
        })
        news.addView(text(
            when {
                lock.optString("via").isNotEmpty() -> "From " + lock.optString("via")
                lock.optBoolean("manual") -> "Added by you"
                lock.optBoolean("ai") -> "AI-checked event"
                else -> "Not AI-checked"
            }, 12f, false, GREY
        ))
        page.addView(news, wide(14))
        val row = LinearLayout(this)
        row.addView(button("Tickertape") {
            val slug = lock.optString("slug")
            status.text = Market.openTickertape(this, if (slug.startsWith("/stocks/")) "https://www.tickertape.in$slug" else null, lock.optString("symbol"))
        }, part(1f))
        row.addView(button("Remove") {
            Patterns.remove(this, lock.optString("id"))
            finish()
        }, part(1f))
        page.addView(row, wide(10))
        page.addView(text("For learning, not advice.", 12f, false, GREY))
    }

    private fun open(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: android.content.ActivityNotFoundException) {
            status.text = "No browser found"
        }
    }

    /** Lock se 10 bazaar din pehle se aaj tak ka graph, lock ki line ke saath. */
    private fun loadChart() {
        val symbol = lock.optString("symbol")
        val base = lock.optString("baseDate")
        val lockPrice = lock.optDouble("lockPrice", 0.0)
        Thread {
            val daily = Patterns.history(symbol, "6mo")
            main.post {
                if (isDestroyed) return@post
                if (daily == null) {
                    status.text = "Could not get the chart. Check internet and open again"
                    return@post
                }
                var i = daily.days.indexOfLast { it <= base }
                if (i < 0) i = 0
                val from = (i - BEFORE).coerceAtLeast(0)
                days = daily.days.subList(from, daily.days.size)
                // Asli bhaav dikhao; split hua ho to lock ki line bhi usi paimaane par
                values = daily.raw.copyOfRange(from, daily.raw.size)
                val adjusted = lock.optBoolean("adjusted") && daily.raw[i] > 0
                val line = if (adjusted) lockPrice * daily.adj[i] / daily.raw[i] else lockPrice
                if (adjusted) values = daily.adj.copyOfRange(from, daily.adj.size)
                chart.show(values, Double.NaN, "", line, "🔒 Locked " + MarketLogic.money(lockPrice, "INR"))
                status.text = (days.firstOrNull() ?: "") + "  →  " + (days.lastOrNull() ?: "") + (if (adjusted) "  ·  adjusted for split or bonus" else "")
            }
        }.start()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val BEFORE = 10
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF16A34A.toInt()
        private const val RED = 0xFFDC2626.toInt()
        private const val BLUE = 0xFF1565C0.toInt()

        fun forLock(context: Context, id: String): Intent = Intent(context, PatternLockActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
