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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lock kiye share ka poora page: bhaav, graph, 1D se Max tak, lock ke baad kitna badla. */
class ShareActivity : Activity() {
    private lateinit var share: JSONObject
    private lateinit var price: TextView
    private lateinit var move: TextView
    private lateinit var pick: TextView
    private lateinit var high: TextView
    private lateinit var low: TextView
    private lateinit var returns: TextView
    private lateinit var chart: ChartView
    private lateinit var startLabel: TextView
    private lateinit var endLabel: TextView
    private lateinit var lockLine: TextView
    private lateinit var lockBadge: TextView
    private lateinit var finance: FinanceView
    private lateinit var yearLine: TextView
    private lateinit var growthLine: TextView
    private var years: List<MarketLogic.Year> = emptyList()
    private lateinit var status: TextView
    private val tabs = mutableListOf<TextView>()
    private val cache = mutableMapOf<String, MarketLogic.Series>()
    private val main = Handler(Looper.getMainLooper())
    private var span = MarketLogic.SPANS[0]
    private var shown: MarketLogic.Series? = null

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

    /** Hara ya laal dabba, safed akshar. */
    private fun badge(view: TextView, change: Double) {
        view.setTextColor(0xFFFFFFFF.toInt())
        view.setPadding(dp(8), dp(3), dp(8), dp(3))
        view.background = rounded(if (change > 0.004) 0xFF16A34A.toInt() else if (change < -0.004) 0xFFDC2626.toInt() else 0xFF98A2B3.toInt(), 6)
    }

    private fun wide() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun part() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val found = Market.shares(this).firstOrNull { it.optString("id") == id }
        if (found == null) {
            finish()
            return
        }
        share = found
        val currency = share.optString("currency")
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(18), dp(22), dp(18), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(page)
        })

        page.addView(text(share.optString("name"), 23f, true))
        val code = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        code.addView(text(share.optString("symbol").substringBefore('.'), 15f, true, GREY).apply { letterSpacing = 0.18f })
        val market = if (share.optString("symbol").endsWith(".BO")) "BSE" else "NSE"
        code.addView(text(market, 12f, true, GREY).apply {
            background = rounded(0xFFEEF1F6.toInt(), 5)
            setPadding(dp(7), dp(2), dp(7), dp(2))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        share.optString("sector").takeIf { it.isNotEmpty() }?.let { sector ->
            code.addView(text(sector, 12f, true, 0xFF175CD3.toInt()).apply {
                background = rounded(0xFFE8F1FF.toInt(), 5)
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        }
        page.addView(code)

        val top = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            setPadding(0, dp(14), 0, 0)
        }
        price = text(MarketLogic.money(share.optDouble("lastPrice", 0.0), currency), 34f, true)
        top.addView(price)
        move = text("", 17f, true)
        top.addView(move, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(10)
            bottomMargin = dp(5)
        })
        page.addView(top)
        pick = text(" ", 13f, false, GREY)
        page.addView(pick)

        val stats = LinearLayout(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        fun stat(name: String, end: Boolean): TextView {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                if (end) gravity = Gravity.END
            }
            box.addView(text(name, 14f, false, GREY))
            val value = text("—", 17f, true)
            box.addView(value)
            stats.addView(box, part())
            return value
        }
        high = stat("High", false)
        low = stat("Low", false)
        returns = stat("Returns", true)
        page.addView(stats)

        chart = ChartView(this)
        chart.onPick = { i -> picked(i) }
        page.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250)))
        val ends = LinearLayout(this)
        startLabel = text("", 12f, false, GREY)
        endLabel = text("", 12f, false, GREY).apply { gravity = Gravity.END }
        ends.addView(startLabel, part())
        ends.addView(endLabel, part())
        page.addView(ends)

        val bar = LinearLayout(this).apply {
            background = rounded(0xFFF1F3F8.toInt(), 12)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        MarketLogic.SPANS.forEach { s ->
            val tab = text(s.label, 15f, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { choose(s) }
            }
            tabs.add(tab)
            bar.addView(tab, part())
        }
        page.addView(bar, wide().apply { topMargin = dp(12) })

        status = text("", 14f, true, GREY)
        page.addView(status)

        val locked = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFF6E5.toInt(), 12)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        locked.addView(text("🔒 Your lock", 13f, true, 0xFFB54700.toInt()))
        lockLine = text("", 16f, true)
        locked.addView(lockLine)
        lockBadge = text("", 15f, true)
        locked.addView(lockBadge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        share.optString("fromTitle").takeIf { it.isNotEmpty() }?.let { why ->
            val link = share.optString("fromLink")
            locked.addView(text((if (link.isNotEmpty()) "From news: " else "From ") + why, 13f, false, if (link.isNotEmpty()) BLUE else GREY).apply {
                if (link.isNotEmpty()) setOnClickListener { open(link) }
            })
        }
        page.addView(locked, wide().apply { topMargin = dp(14) })

        // ----- Saal dar saal revenue aur profit -----
        page.addView(text("Revenue and profit", 18f, true), wide().apply { topMargin = dp(18) })
        val legend = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun key(color: Int, words: String) {
            legend.addView(android.view.View(this).apply { background = rounded(color, 3) },
                LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(5) })
            legend.addView(text(words, 13f, false, GREY), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(14) })
        }
        key(FinanceView.REVENUE, "Revenue")
        key(FinanceView.PROFIT, "Profit")
        key(FinanceView.LOSS, "Loss")
        page.addView(legend)
        finance = FinanceView(this)
        finance.onPick = { i -> yearPicked(i) }
        page.addView(finance, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)).apply { topMargin = dp(6) })
        yearLine = text("Getting revenue and profit", 14f, true)
        page.addView(yearLine)
        growthLine = text("", 13f, false, GREY)
        page.addView(growthLine)
        loadFinance()

        val row = LinearLayout(this)
        row.addView(button("Tickertape") { tickertape() }, part())
        row.addView(button("News") {
            open("https://news.google.com/search?q=" + URLEncoder.encode(MarketLogic.companyQuery(share.optString("name")) + " share", "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en")
        }, part())
        page.addView(row, wide().apply { topMargin = dp(10) })
        page.addView(button("Remove from My shares") {
            Market.removeShare(this, share.optString("id"))
            finish()
        })
        page.addView(text("Prices come from a free source and can be 10 to 15 minutes late while the market is open.", 12f, false, GREY))

        showLock(share.optDouble("lastPrice", 0.0))
        choose(span)
    }

    private fun loadFinance() {
        val app = applicationContext
        val item = share
        Thread {
            val got = try {
                Market.financials(app, item)
            } catch (e: org.json.JSONException) {
                emptyList<MarketLogic.Year>() to ""
            }
            main.post {
                if (isDestroyed) return@post
                years = got.first
                if (years.isEmpty()) {
                    yearLine.text = "Could not get revenue and profit for this share"
                    growthLine.text = if (Market.lastError == "No internet") "Check internet and open this page again." else ""
                    return@post
                }
                finance.show(years)
                yearPicked(years.lastIndex)
                growthLine.text = "Figures in ₹ crore, year ending March. Tap a year. Source: " + got.second
            }
        }.start()
    }

    /** Chune hue saal ke poore aankde aur pichhle saal se tulna. */
    private fun yearPicked(i: Int) {
        val y = years.getOrNull(i) ?: return
        val words = StringBuilder(y.label + ":  Revenue " + MarketLogic.crore(y.revenue) + "  ·  " +
            (if (y.profit >= 0) "Profit " else "Loss ") + MarketLogic.crore(Math.abs(y.profit)))
        years.getOrNull(i - 1)?.let { before ->
            words.append("\nFrom " + before.label + ":  Revenue " + MarketLogic.jumpText(before.revenue, y.revenue).first +
                "  ·  Profit " + MarketLogic.jumpText(before.profit, y.profit).first)
        }
        yearLine.text = words
        yearLine.setTextColor(if (y.profit >= 0) INK else RED)
    }

    private fun open(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: android.content.ActivityNotFoundException) {
            status.text = "No browser found"
        }
    }

    private fun tickertape() {
        val code = share.optString("symbol")
        val slug = share.optString("slug")
        if (slug.startsWith("/stocks/")) {
            status.text = Market.openTickertape(this, "https://www.tickertape.in$slug", code)
            return
        }
        status.text = "Opening in Tickertape"
        Thread {
            val link = Market.tickertapeLink(share.optString("name"), "", code)
            main.post { if (!isDestroyed) status.text = Market.openTickertape(this, link, code) }
        }.start()
    }

    private fun showLock(now: Double) {
        val currency = share.optString("currency")
        val saved = share.optDouble("savedPrice", 0.0)
        val change = MarketLogic.pct(now, saved)
        lockLine.text = "Locked " + SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(share.optLong("savedAt"))) + " at " +
            MarketLogic.money(saved, currency) + "\nSince then " + MarketLogic.moveText(now, saved)
        lockLine.setTextColor(if (change > 0.004) GREEN else if (change < -0.004) RED else INK)
        lockBadge.text = "Since lock " + MarketLogic.pctText(now, saved)
        badge(lockBadge, change)
    }

    /** 1D, 1W ... ka button. */
    private fun choose(next: MarketLogic.Span) {
        span = next
        tabs.forEachIndexed { i, tab ->
            val on = MarketLogic.SPANS[i] == next
            tab.background = if (on) rounded(0xFFFFFFFF.toInt(), 9) else null
            tab.setTextColor(if (on) INK else GREY)
        }
        cache[next.label]?.let {
            show(it)
            return
        }
        status.text = "Getting the " + next.label + " chart"
        val symbol = share.optString("symbol")
        Thread {
            val got = Market.series(symbol, next)
            main.post {
                if (isDestroyed) return@post
                if (got == null) {
                    if (span == next) status.text = "Could not get the chart. Check internet and tap " + next.label + " again"
                    return@post
                }
                cache[next.label] = got
                if (span == next) show(got)
            }
        }.start()
    }

    private fun show(series: MarketLogic.Series) {
        shown = series
        status.text = ""
        val currency = series.currency
        val base = MarketLogic.spanBase(series, span)
        val now = series.price
        val up = now >= base
        price.text = MarketLogic.money(now, currency)
        move.text = MarketLogic.moveText(now, base)
        badge(move, MarketLogic.pct(now, base))
        high.text = MarketLogic.money(maxOf(series.high, now), currency)
        low.text = MarketLogic.money(minOf(series.low, now), currency)
        returns.text = MarketLogic.pctText(now, base)
        returns.setTextColor(if (up) GREEN else RED)
        pick.text = " "
        val day = span.range == "1d"
        val saved = share.optDouble("savedPrice", Double.NaN)
        chart.show(
            series.values, base, if (day) "Prev. Close " + MarketLogic.money(base, currency) else "",
            saved, "🔒 Locked " + MarketLogic.money(saved, currency)
        )
        startLabel.text = stamp(series.times.first())
        endLabel.text = stamp(series.times.last())
        showLock(now)
    }

    private fun stamp(ms: Long): String = SimpleDateFormat(
        when (span.range) {
            "1d" -> "h:mm a"
            "5d" -> "d MMM, h:mm a"
            else -> "d MMM yyyy"
        }, Locale.ENGLISH
    ).format(Date(ms))

    /** Graph par ungli: us samay ka bhaav. */
    private fun picked(i: Int) {
        val series = shown ?: return
        if (i !in series.values.indices) {
            pick.text = " "
            return
        }
        pick.text = MarketLogic.money(series.values[i], series.currency) + "  ·  " + stamp(series.times[i])
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF1B9E50.toInt()
        private const val RED = 0xFFD93A4A.toInt()
        private const val BLUE = 0xFF1565C0.toInt()

        fun forShare(context: Context, id: String): Intent = Intent(context, ShareActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
