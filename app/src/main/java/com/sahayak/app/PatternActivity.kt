package com.sahayak.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pattern study ki screen: har pattern ka scoreboard aur uske neeche apne aap lock hue shares. */
class PatternActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var box: LinearLayout
    private val open = mutableSetOf<String>()
    private val full = mutableSetOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
    private val day = SimpleDateFormat("d MMM", Locale.ENGLISH)
    private val tick = object : Runnable {
        override fun run() {
            if (!Patterns.running()) return
            status.text = Patterns.progress
            main.postDelayed(this, 1_000L)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = INK) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(2))
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

    private fun wide(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun wrap(left: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dp(left)
    }

    /** Badha to hara dabba, gira to laal. */
    private fun chip(words: String, change: Double) = TextView(this).apply {
        text = words
        textSize = 13f
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = rounded(if (change > 0.004) GREEN else if (change < -0.004) RED else GREY, 6)
    }

    private fun open(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: android.content.ActivityNotFoundException) {
            status.text = "No browser found"
        }
    }

    private fun work(busy: String, job: () -> String) {
        status.text = busy
        main.postDelayed(tick, 500L)
        Thread {
            val answer = job()
            main.post {
                if (isDestroyed) return@post
                status.text = answer
                render()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        page.addView(text("Pattern study", 24f, true))
        page.addView(text("When a company announces an event (dividend, split, buyback ...), Sahayak locks that share on that day and keeps watching how it moves, next to Nifty.", 14f))
        page.addView(CheckBox(this).apply {
            text = "Auto-lock shares when these events are announced"
            isChecked = Patterns.on(this@PatternActivity)
            setOnCheckedChangeListener { _, value ->
                Patterns.setOn(this@PatternActivity, value)
                render()
            }
        })
        page.addView(button("Scan news now") { work("Reading news") { Patterns.run(app, false) } })
        page.addView(button("Update results now") { work("Updating") { Patterns.refresh(app) } })
        status = text("", 14f, true, TEAL)
        page.addView(status)
        info = text("", 13f, false, GREY)
        page.addView(info)
        page.addView(text(
            "How to read it: a share is counted from its lock price. Splits, bonus and dividends are adjusted, so a split does not look like a fall. " +
                "A pattern means something only after about 20 shares. This is for learning, not advice.", 12f, false, GREY
        ))
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (Patterns.running()) main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    private fun render() {
        val locks = Patterns.locks(this)
        val last = Patterns.lastScan(this)
        val next = Market.nextPattern(this)
        info.text = locks.size.toString() + " shares locked in all" +
            (if (last > 0) "  ·  Last scan " + time.format(Date(last)) + " (" + Patterns.lastNote(this) + ")" else "") +
            (if (Patterns.on(this) && next > 0) "  ·  Next " + time.format(Date(next)) else "")
        box.removeAllViews()
        val groups = locks.groupBy { it.optString("p") }
        PatternLogic.ALL.forEach { pattern ->
            val list = groups[pattern.id].orEmpty().sortedByDescending { it.optLong("lockAt") }
            val shown = pattern.id in open
            box.addView(TextView(this).apply {
                text = (if (shown) "▼ " else "▶ ") + pattern.label + "  (" + list.size + ")"
                textSize = 17f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = rounded(if (list.isEmpty()) 0xFF667085.toInt() else INK, 8)
                setOnClickListener {
                    if (shown) open.remove(pattern.id) else open.add(pattern.id)
                    render()
                }
            }, wide(10))
            if (!shown) return@forEach
            box.addView(text(pattern.about, 13f, false, GREY))
            if (list.isEmpty()) {
                box.addView(text("No share locked for this pattern yet.", 14f))
                return@forEach
            }
            scoreboard(list)
            val rows = if (pattern.id in full) list else list.take(ROWS)
            rows.forEach { row(it) }
            if (list.size > ROWS) {
                box.addView(button(if (pattern.id in full) "▲ Less" else "▼ More (" + (list.size - ROWS) + " shares)") {
                    if (pattern.id in full) full.remove(pattern.id) else full.add(pattern.id)
                    render()
                })
            }
        }
    }

    /** Pattern ka nateeja: ab tak aur 1 din, 1 hafta, 1 mahina, 3 mahine baad. */
    private fun scoreboard(list: List<JSONObject>) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFFFFF.toInt(), 10)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        card.addView(text("Scoreboard", 13f, true, GREY))
        val names = listOf("So far") + PatternLogic.STEP_NAMES.map { "After $it" }
        var any = false
        Patterns.scores(list).forEachIndexed { i, score ->
            if (score == null) return@forEachIndexed
            any = true
            val line = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            line.addView(text(names[i], 14f, true), LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT))
            line.addView(chip(PatternLogic.signed(score.average), score.average))
            line.addView(text("Nifty " + PatternLogic.signed(score.nifty), 13f, false, GREY), wrap(8))
            card.addView(line)
            card.addView(text(
                score.count.toString() + (if (score.count == 1) " share" else " shares") + "  ·  " + score.upPercent + "% went up  ·  " +
                    score.beatPercent + "% beat Nifty" + (if (score.count < TRUST) "  ·  too few to trust yet" else ""), 12f, false, GREY
            ))
        }
        if (!any) card.addView(text("Results come after the first update. Tap Update results now.", 13f))
        box.addView(card, wide(6))
    }

    /** Ek lock hua share. */
    private fun row(lock: JSONObject) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFFFFF.toInt(), 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val code = lock.optString("symbol").substringBefore('.')
        val sure = lock.optBoolean("sure", true)
        card.addView(text(lock.optString("name") + "  ($code)" + (if (sure) "  ✓" else "  ⚠"), 15f, true))
        val detail = lock.optString("detail")
        card.addView(text(
            "Locked " + day.format(Date(lock.optLong("lockAt"))) + " at " + MarketLogic.money(lock.optDouble("lockPrice", 0.0), "INR") +
                "  ·  Now " + MarketLogic.money(lock.optDouble("price", 0.0), "INR") + (if (detail.isNotEmpty()) "\n" + detail else ""), 14f
        ))
        if (lock.has("now")) {
            val now = lock.optDouble("now")
            val line = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            line.addView(chip("Since lock " + PatternLogic.signed(now), now))
            if (lock.has("nNow")) {
                val extra = now - lock.optDouble("nNow")
                line.addView(text("Nifty " + PatternLogic.signed(lock.optDouble("nNow")) + "  ·  vs Nifty " + PatternLogic.signed(extra), 13f, true,
                    if (extra > 0.004) GREEN else if (extra < -0.004) RED else GREY), wrap(8))
            }
            card.addView(line)
            val steps = lock.optJSONArray("steps")
            if (steps != null) {
                val parts = PatternLogic.STEP_NAMES.indices.filter { !steps.isNull(it) }.map { PatternLogic.STEP_NAMES[it] + " " + PatternLogic.signed(steps.optDouble(it)) }
                if (parts.isNotEmpty()) card.addView(text(parts.joinToString("  ·  "), 13f, false, GREY))
            }
            if (lock.optBoolean("adjusted")) card.addView(text("Adjusted for split, bonus or dividend", 12f, false, 0xFFB54700.toInt()))
        } else {
            card.addView(text("Result comes after the next update.", 13f, false, GREY))
        }
        card.addView(text(lock.optString("title") + (lock.optString("source").takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""), 13f, false, BLUE).apply {
            setOnClickListener { open(lock.optString("link")) }
        })
        card.addView(text(
            (if (lock.optBoolean("ai")) "AI-checked event" else "Not AI-checked (AI was not available)") + "  ·  Price from " + lock.optInt("sources", 1) +
                (if (lock.optInt("sources", 1) == 1) " source" else " sources"), 12f, false, GREY
        ))
        val actions = LinearLayout(this).apply { gravity = android.view.Gravity.END }
        actions.addView(button("Tickertape") {
            val slug = lock.optString("slug")
            status.text = Market.openTickertape(this, if (slug.startsWith("/stocks/")) "https://www.tickertape.in$slug" else null, lock.optString("symbol"))
        })
        actions.addView(button("Remove") {
            Patterns.remove(this, lock.optString("id"))
            render()
        })
        card.addView(actions)
        box.addView(card, wide(6))
    }

    companion object {
        private const val ROWS = 3
        private const val TRUST = 20
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF16A34A.toInt()
        private const val RED = 0xFFDC2626.toInt()
        private const val TEAL = 0xFF1F5C4D.toInt()
        private const val BLUE = 0xFF1565C0.toInt()
    }
}
