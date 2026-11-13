package com.sahayak.app

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Market Watch ki screen: khabrein, apne shares, roz ki report. */
class MarketActivity : Activity() {
    private lateinit var name: EditText
    private lateinit var shareStatus: TextView
    private lateinit var sharesBox: LinearLayout
    private lateinit var reportOn: CheckBox
    private lateinit var reportTime: Button
    private lateinit var reportStatus: TextView
    private lateinit var reportBox: LinearLayout
    private lateinit var newsOn: CheckBox
    private lateinit var alertOn: CheckBox
    private lateinit var every: RadioGroup
    private lateinit var newsStatus: TextView
    private lateinit var newsBox: LinearLayout
    private var newsOpen = false
    private var reportsOpen = false
    private var loading = true
    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
    private val day = SimpleDateFormat("d MMM", Locale.ENGLISH)
    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = INK) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(3))
    }

    private fun button(words: String, action: () -> Unit) = Button(this).apply {
        text = words
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun card(alert: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(if (alert) 0xFFFFF1C2.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun LinearLayout.addCard(view: LinearLayout) = addView(
        view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    )

    private fun open(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: android.content.ActivityNotFoundException) {
            newsStatus.text = "No browser found"
        }
    }

    /** Internet ka kaam peeche, jawab screen par. */
    private fun work(status: TextView, busy: String, job: () -> String) {
        status.text = busy
        Thread {
            val answer = try {
                job()
            } catch (e: org.json.JSONException) {
                "Something went wrong. Try again"
            }
            main.post {
                if (isDestroyed) return@post
                status.text = answer
                render()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        val app = applicationContext
        page.addView(text("Market Watch", 24f, true))
        page.addView(text("See news that can move the market. Save a share to follow its price every day.", 14f))

        // ----- Mere shares -----
        page.addView(text("My shares", 18f, true))
        name = EditText(this).apply {
            hint = "Share name, e.g. Tata Motors"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        page.addView(name)
        page.addView(button("Save share at today's price") {
            val typed = name.text.toString()
            work(shareStatus, "Finding the share and its price") {
                val answer = Market.addShare(app, typed)
                if (answer.startsWith("Saved")) main.post { name.setText("") }
                answer
            }
        })
        shareStatus = text("", 14f, true)
        page.addView(shareStatus)
        sharesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(sharesBox)
        page.addView(button("Update prices now") {
            work(shareStatus, "Getting prices") {
                val total = Market.shares(app).size
                if (total == 0) "Save a share first" else {
                    val ok = Market.refresh(app)
                    if (ok == total) "Prices updated" else "Got $ok of $total prices. " + Market.lastError
                }
            }
        })

        // ----- Roz ki report -----
        page.addView(text("Daily report", 18f, true))
        page.addView(text("Every day: how much each saved share moved, and its news.", 13f))
        reportOn = CheckBox(this).apply {
            text = "Send me the report every day"
            isChecked = Market.reportOn(this@MarketActivity)
            setOnCheckedChangeListener { _, _ -> saveReport(Market.reportHour(app), Market.reportMinute(app)) }
        }
        page.addView(reportOn)
        reportTime = button("") {
            TimePickerDialog(this, { _, h, m -> saveReport(h, m) }, Market.reportHour(this), Market.reportMinute(this), false).show()
        }
        page.addView(reportTime)
        page.addView(button("Make report now") {
            work(reportStatus, "Making the report") { Market.report(app, false) }
        })
        reportStatus = text("", 14f, true)
        page.addView(reportStatus)
        reportBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(reportBox)

        // ----- Bazaar ki khabrein -----
        page.addView(text("Market news", 18f, true))
        page.addView(text("Results, CEO changes, big orders and deals, tariffs and trade war, RBI and government decisions.", 13f))
        newsOn = CheckBox(this).apply {
            text = "Keep watching market news"
            isChecked = Market.newsOn(this@MarketActivity)
            setOnCheckedChangeListener { _, _ -> saveNews() }
        }
        page.addView(newsOn)
        alertOn = CheckBox(this).apply {
            text = "Pop-up alert when new news comes"
            isChecked = Market.alertOn(this@MarketActivity)
            setOnCheckedChangeListener { _, _ -> saveNews() }
        }
        page.addView(alertOn)
        page.addView(text("Check every", 15f, true))
        every = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            Market.INTERVALS.forEachIndexed { i, m ->
                addView(RadioButton(context).apply { id = IDS + i; text = if (m < 60) "$m min" else "1 hour"; textSize = 15f })
            }
            check(IDS + Market.INTERVALS.indexOf(Market.newsMinutes(this@MarketActivity)).coerceAtLeast(0))
            setOnCheckedChangeListener { _, _ -> saveNews() }
        }
        page.addView(HorizontalScrollView(this).apply { addView(every) })
        page.addView(button("Check news now") {
            work(newsStatus, "Checking news") {
                when (val n = Market.runNews(app, false)) {
                    -1 -> "No internet. Try again"
                    0 -> "No new news right now"
                    else -> "$n new news"
                }
            }
        })
        newsStatus = text("", 14f, true)
        page.addView(newsStatus)
        page.addView(text("Good and Bad tags are a guess from the words in the headline. They are not advice.", 12f, false, GREY))
        page.addView(text("Tap any news to see how big it is, with before and now figures and what happened earlier.", 13f, true, TEAL))
        newsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(newsBox)
        page.addView(button("Clear news list") {
            Market.clearNews(this)
            render()
        })
        loading = false
    }

    private fun saveReport(hour: Int, minute: Int) {
        if (loading) return
        Market.setReport(this, reportOn.isChecked, hour, minute)
        render()
    }

    private fun saveNews() {
        if (loading) return
        val minutes = Market.INTERVALS[(every.checkedRadioButtonId - IDS).coerceIn(0, Market.INTERVALS.lastIndex)]
        Market.setNews(this, newsOn.isChecked, minutes, alertOn.isChecked)
        render()
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresh)
    }

    private fun render() {
        renderShares()
        renderReports()
        renderNews()
    }

    private fun renderShares() {
        sharesBox.removeAllViews()
        val shares = Market.shares(this)
        if (shares.isEmpty()) sharesBox.addView(text("No share saved yet.", 14f, false, GREY))
        shares.forEach { s ->
            val currency = s.optString("currency")
            val price = s.optDouble("lastPrice", 0.0)
            val saved = s.optDouble("savedPrice", 0.0)
            val change = MarketLogic.pct(price, saved)
            val box = card()
            box.addView(text(s.optString("name") + "  (" + s.optString("symbol") + ")", 16f, true))
            val today = if (s.has("prevClose")) "  ·  Today " + MarketLogic.pctText(price, s.optDouble("prevClose")) else ""
            box.addView(text("Now " + MarketLogic.money(price, currency) + today, 15f))
            box.addView(text(
                "Saved " + day.format(Date(s.optLong("savedAt"))) + " at " + MarketLogic.money(saved, currency) +
                    "  ·  Since then " + MarketLogic.pctText(price, saved),
                14f, true, if (change > 0.004) GREEN else if (change < -0.004) RED else INK
            ))
            box.addView(text("Price checked " + time.format(Date(s.optLong("lastAt"))), 12f, false, GREY))
            val row = LinearLayout(this).apply { gravity = Gravity.END }
            row.addView(button("News") {
                open("https://news.google.com/search?q=" + URLEncoder.encode(MarketLogic.companyQuery(s.optString("name")) + " share", "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en")
            })
            row.addView(button("Remove") {
                Market.removeShare(this, s.optString("id"))
                shareStatus.text = "Removed " + s.optString("name")
                render()
            })
            box.addView(row)
            sharesBox.addCard(box)
        }
    }

    private fun renderReports() {
        val hour = Market.reportHour(this)
        val minute = Market.reportMinute(this)
        reportTime.text = "Report time: " + MarketLogic.clock(hour, minute) + "  (tap to change)"
        reportBox.removeAllViews()
        val next = Market.nextReport(this)
        if (Market.reportOn(this) && next > 0) reportBox.addView(text("Next report: " + time.format(Date(next)), 13f, false, GREY))
        val reports = Market.reports(this)
        val shown = if (reportsOpen) reports else reports.take(1)
        shown.forEach { r ->
            val box = card()
            box.addView(text("Report  ·  " + time.format(Date(r.optLong("at"))), 13f, true, TEAL))
            val items = r.optJSONArray("items") ?: return@forEach
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val up = item.optDouble("up", 0.0)
                box.addView(text(item.optString("name"), 15f, true))
                box.addView(text(item.optString("text"), 14f, false, if (up > 0.004) GREEN else if (up < -0.004) RED else INK))
                item.optString("extra").takeIf { it.isNotEmpty() }?.let { box.addView(text(it, 12f, false, GREY)) }
                val news = item.optJSONArray("news")
                if (news == null || news.length() == 0) {
                    box.addView(text("No news today.", 13f, false, GREY))
                } else {
                    for (j in 0 until news.length()) {
                        val n = news.optJSONObject(j) ?: continue
                        box.addView(text("• " + n.optString("t"), 13f, false, BLUE).apply { setOnClickListener { open(n.optString("l")) } })
                    }
                }
            }
            reportBox.addCard(box)
        }
        if (reports.size > 1) {
            reportBox.addView(button(if (reportsOpen) "Hide old reports" else "Old reports (" + (reports.size - 1) + ")") {
                reportsOpen = !reportsOpen
                render()
            })
        }
    }

    private fun renderNews() {
        val last = Market.lastNews(this)
        val next = Market.nextNews(this)
        val on = Market.newsOn(this)
        val line = (if (on) "Watching. " else "Not watching. ") +
            (if (last > 0) "Last check: " + time.format(Date(last)) + ". " else "") +
            (if (on && next > 0) "Next: " + time.format(Date(next)) else "")
        newsBox.removeAllViews()
        newsBox.addView(text(line, 13f, false, GREY))
        val news = Market.news(this)
        if (news.isEmpty()) newsBox.addView(text("No news yet. Tap Check news now.", 14f, false, GREY))
        val shown = if (newsOpen) news else news.take(NEWS_ROWS)
        shown.forEach { n ->
            val mood = n.optString("mood")
            val color = when (mood) {
                "Good" -> GREEN
                "Bad" -> RED
                else -> TEAL
            }
            val head = time.format(Date(n.optLong("at"))) + "  ·  " + n.optString("kind") + (if (mood.isNotEmpty()) "  ·  $mood" else "")
            val box = card()
            box.addView(text(head, 13f, true, color))
            box.addView(text(n.optString("title"), 15f))
            n.optString("source").takeIf { it.isNotEmpty() }?.let { box.addView(text(it, 12f, false, GREY)) }
            box.setOnClickListener { startActivity(MarketNewsActivity.forNews(this, n)) }
            newsBox.addCard(box)
        }
        if (news.size > NEWS_ROWS) {
            newsBox.addView(button(if (newsOpen) "Less" else "More (" + (news.size - NEWS_ROWS) + ")") {
                newsOpen = !newsOpen
                render()
            })
        }
    }

    companion object {
        private const val IDS = 6_000
        private const val REFRESH_MS = 15_000L
        private const val NEWS_ROWS = 10
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF1B7F3B.toInt()
        private const val RED = 0xFFC62828.toInt()
        private const val TEAL = 0xFF1F5C4D.toInt()
        private const val BLUE = 0xFF1565C0.toInt()
    }
}
