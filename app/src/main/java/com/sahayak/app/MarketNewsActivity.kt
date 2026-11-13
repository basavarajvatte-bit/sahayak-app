package com.sahayak.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ek khabar ki gehrai: kitni badi baat hai, pehle aur ab ke aankde, pehle kya hua tha. */
class MarketNewsActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var headline = ""
    private var link = ""
    private var kind = ""
    private var company = ""
    private lateinit var tape: Button

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

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(10))
        setBackgroundColor(0xFFFFFFFF.toInt())
    }

    private fun LinearLayout.addCard(view: LinearLayout) = addView(
        view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    )

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: android.content.ActivityNotFoundException) {
            status.text = "No browser found"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        headline = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        link = intent.getStringExtra(EXTRA_LINK).orEmpty()
        kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        val meta = listOf(
            if (at > 0) SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(at)) else "", kind, source
        ).filter { it.isNotEmpty() }.joinToString("  ·  ")
        page.addView(text(meta, 13f, true, TEAL))
        page.addView(text(headline, 19f, true))
        company = MarketLogic.companyGuess(headline)
        tape = button("") { tickertape() }
        page.addView(tape)
        showTape()
        status = text("", 14f, true)
        page.addView(status)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(body)
        page.addView(button("Open full news") { open(link) })
        page.addView(button("Depth") { research(true) })
        page.addView(text("AI reads the news and can make mistakes. Open the full news and check before you decide anything.", 12f, false, GREY))
        research(false)
    }

    private fun research(force: Boolean) {
        status.text = "Doing depth research. It can take up to a minute"
        body.removeAllViews()
        val app = applicationContext
        Thread {
            val result = try {
                Market.depth(app, headline, link, kind, force)
            } catch (e: org.json.JSONException) {
                JSONObject().put("error", "Something went wrong. Tap Depth again")
            }
            main.post {
                if (!isDestroyed) show(result)
            }
        }.start()
    }

    private fun show(result: JSONObject) {
        body.removeAllViews()
        val depth = result.optString("ai").takeIf { it.isNotEmpty() }?.let { MarketLogic.parseDepth(it) }
        status.text = when {
            depth == null -> result.optString("error").ifEmpty { "Could not research this news" }
            result.optBoolean("searched") -> "Researched with Google search"
            else -> "Researched from news headlines only (search limit reached), so some figures may be missing"
        }
        if (depth != null) {
            val level = card()
            level.addView(text("How big is this news", 13f, true, GREY))
            val color = when (depth.level) {
                5 -> RED
                4 -> ORANGE
                3 -> TEAL
                else -> GREY
            }
            level.addView(text("●".repeat(depth.level) + "○".repeat(5 - depth.level) + "   " + MarketLogic.levelWord(depth.level) + "  (" + depth.level + " of 5)", 19f, true, color))
            if (depth.what.isNotEmpty()) level.addView(text(depth.what, 15f))
            body.addCard(level)
            if (depth.company.isNotEmpty()) {
                company = depth.company
                showTape()
            }
            numbers(depth.numbers)
            trends(depth.trends)
            if (depth.reasons.isNotEmpty()) {
                val reasons = card()
                reasons.addView(text("Why it changed", 13f, true, GREY))
                depth.reasons.forEach { reasons.addView(text("• $it", 15f)) }
                body.addCard(reasons)
            }
            if (depth.past.isNotEmpty()) {
                val past = card()
                past.addView(text("What happened before", 13f, true, GREY))
                depth.past.forEach { past.addView(text("• $it", 15f)) }
                body.addCard(past)
            }
            if (depth.why.isNotEmpty()) {
                val why = card()
                why.addView(text("Why it matters", 13f, true, GREY))
                why.addView(text(depth.why, 15f))
                body.addCard(why)
            }
            if (depth.unsure.isNotEmpty()) {
                val unsure = card()
                unsure.addView(text("Not confirmed", 13f, true, GREY))
                unsure.addView(text(depth.unsure, 15f))
                body.addCard(unsure)
            }
            if (depth.company.isNotEmpty()) watchButton(depth.company)
        } else {
            // AI na chale to heading se jo aankde mil jayein
            MarketLogic.headlineJump(headline)?.let { numbers(listOf(it)) }
        }
        val related = result.optJSONArray("related")
        if (related != null && related.length() > 0) {
            val old = card()
            old.addView(text("Earlier news on this", 13f, true, GREY))
            for (i in 0 until related.length()) {
                val n = related.optJSONObject(i) ?: continue
                val date = n.optString("d")
                old.addView(text((if (date.isNotEmpty()) "$date  ·  " else "") + n.optString("t"), 14f, false, BLUE).apply {
                    setOnClickListener { open(n.optString("l")) }
                })
            }
            body.addCard(old)
        }
    }

    private fun numbers(list: List<MarketLogic.Num>) {
        if (list.isEmpty()) return
        val box = card()
        box.addView(text("Before and now", 13f, true, GREY))
        list.forEach { n ->
            val (jump, way) = MarketLogic.jumpText(n.before, n.after)
            val head = listOf(n.what, n.period).filter { it.isNotEmpty() }.joinToString("  ·  ")
            if (head.isNotEmpty()) box.addView(text(head, 14f, true))
            box.addView(text(MarketLogic.numText(n.before, n.unit) + "   →   " + MarketLogic.numText(n.after, n.unit), 18f, true))
            if (jump.isNotEmpty()) box.addView(text(jump, 16f, true, if (way > 0) GREEN else if (way < 0) RED else INK))
        }
        body.addCard(box)
    }

    /** Pichhle saal aur usse pehle ke aankde, har kadam par kitna badla. */
    private fun trends(list: List<MarketLogic.Trend>) {
        if (list.isEmpty()) return
        val box = card()
        box.addView(text("Earlier years and quarters", 13f, true, GREY))
        list.forEach { t ->
            if (t.what.isNotEmpty()) box.addView(text(t.what, 15f, true))
            t.points.forEachIndexed { i, point ->
                val row = LinearLayout(this)
                row.addView(text(point.first, 14f, false, GREY), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(text(MarketLogic.numText(point.second, t.unit), 15f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val (jump, way) = if (i > 0) MarketLogic.jumpText(t.points[i - 1].second, point.second) else "" to 0
                row.addView(text(jump.substringBefore("  ("), 13f, true, if (way > 0) GREEN else if (way < 0) RED else INK),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
                box.addView(row)
            }
        }
        body.addCard(box)
    }

    private fun showTape() {
        tape.text = "Tickertape:  $company"
        tape.visibility = if (company.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Is company ka page Tickertape app mein. */
    private fun tickertape() {
        val name = company
        status.text = "Opening $name in Tickertape"
        Thread {
            val link = Market.tickertapeLink(name)
            main.post {
                if (isDestroyed) return@post
                status.text = if (Market.openTickertape(this, link)) "" else "Could not open Tickertape"
            }
        }.start()
    }

    private fun watchButton(company: String) {
        val app = applicationContext
        body.addView(button("Save $company share to My shares") {
            status.text = "Finding the share and its price"
            Thread {
                val answer = try {
                    Market.addShare(app, company)
                } catch (e: org.json.JSONException) {
                    "Something went wrong. Try again"
                }
                main.post { if (!isDestroyed) status.text = answer }
            }.start()
        })
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LINK = "link"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_AT = "at"
        private const val EXTRA_SOURCE = "source"
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF1B7F3B.toInt()
        private const val RED = 0xFFC62828.toInt()
        private const val ORANGE = 0xFFB54700.toInt()
        private const val TEAL = 0xFF1F5C4D.toInt()
        private const val BLUE = 0xFF1565C0.toInt()

        fun forNews(context: Context, news: JSONObject): Intent = Intent(context, MarketNewsActivity::class.java)
            .putExtra(EXTRA_TITLE, news.optString("title")).putExtra(EXTRA_LINK, news.optString("link"))
            .putExtra(EXTRA_KIND, news.optString("kind")).putExtra(EXTRA_AT, news.optLong("at"))
            .putExtra(EXTRA_SOURCE, news.optString("source"))
    }
}
