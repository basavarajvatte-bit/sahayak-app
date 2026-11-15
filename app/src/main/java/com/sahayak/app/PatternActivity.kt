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
        page.addView(button("Scan news now (last 7 days)") { work("Reading news") { Patterns.run(app, false) } })

        // ----- Apni list se jodna -----
        val paste = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFFFFF.toInt(), 10)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        paste.addView(text("Add shares by hand", 15f, true))
        paste.addView(text("Paste a list (like a dividend list) or type one company per line. They are locked at today's verified price.", 13f, false, GREY))
        val names = PatternLogic.ALL.map { it.label }
        val choose = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@PatternActivity, android.R.layout.simple_spinner_dropdown_item, names)
        }
        paste.addView(choose)
        val listBox = android.widget.EditText(this).apply {
            hint = "Ashok Leyland\n₹1.00\n20 Nov\nOil India\n₹7.00\n20 Nov"
            minLines = 4
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        paste.addView(listBox)
        paste.addView(button("Lock these shares") {
            val pattern = PatternLogic.ALL[choose.selectedItemPosition.coerceIn(0, PatternLogic.ALL.lastIndex)]
            val words = listBox.text.toString()
            open.add(pattern.id)
            work("Locking shares") {
                val answer = Patterns.addList(app, pattern.id, words)
                if (answer.startsWith("Locked") && !answer.startsWith("Locked 0")) main.post { listBox.setText("") }
                answer
            }
        })
        page.addView(paste, wide(8))
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
        Patterns.rearm(this)
        val app = applicationContext
        Thread {
            val changed = try {
                Patterns.fillValuations(app)
            } catch (e: org.json.JSONException) {
                false
            }
            if (changed) main.post { if (!isDestroyed) render() }
        }.start()
        render()
        if (Patterns.running()) main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    private fun render() {
        val app = applicationContext
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
            val head = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = rounded(if (list.isEmpty()) 0xFF667085.toInt() else INK, 8)
                setPadding(0, 0, dp(6), 0)
            }
            head.addView(TextView(this).apply {
                text = (if (shown) "▼ " else "▶ ") + pattern.label + "  (" + list.size + ")"
                textSize = 17f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(9), dp(8), dp(9))
                setOnClickListener {
                    if (shown) open.remove(pattern.id) else open.add(pattern.id)
                    render()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            // Isi pattern ka scan: khabar padho aur share lock karo (Dividend mein list bhi)
            head.addView(TextView(this).apply {
                text = "🔍 Scan"
                textSize = 14f
                setTextColor(INK)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rounded(0xFFFFFFFF.toInt(), 6)
                setOnClickListener {
                    open.add(pattern.id)
                    work("Scanning " + pattern.label) { Patterns.scanOne(app, pattern.id) }
                }
            })
            box.addView(head, wide(10))
            if (!shown) return@forEach
            box.addView(text(pattern.about, 13f, false, GREY))
            if (list.isEmpty()) {
                if (pattern.id == "dividend") dividendList()
                box.addView(text("No share locked for this pattern yet.", 14f))
                return@forEach
            }
            if (pattern.id == "dividend") dividendList()
            scoreboard(list)
            // Ek line mein 3 dabbe: lock ke baad badha to hara, gira to laal
            val rows = if (pattern.id in full) list else list.take(TILE_ROWS * TILES)
            rows.chunked(TILES).forEach { line ->
                val strip = LinearLayout(this)
                line.forEach { lock ->
                    strip.addView(tile(lock), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                repeat(TILES - line.size) { strip.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
                box.addView(strip)
            }
            if (list.size > TILE_ROWS * TILES) {
                box.addView(button(if (pattern.id in full) "▲ Less" else "▼ More (" + (list.size - TILE_ROWS * TILES) + " shares)") {
                    if (pattern.id in full) full.remove(pattern.id) else full.add(pattern.id)
                    render()
                })
            }
        }
    }

    /** Aane wale dividend ki list: kaun lock hua, kaun nahi aur kyun. */
    private fun dividendList() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFF6E5.toInt(), 10)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        card.addView(text("Upcoming dividends (next 14 days)", 15f, true, 0xFFB54700.toInt()))
        val rows = Patterns.dividendList(this)
        val note = Patterns.dividendListNote(this)
        card.addView(text(if (note.isEmpty()) "Not fetched yet. Tap 🔍 Scan." else note, 12f, false, GREY))
        val shown = if ("divlist" in full) rows else rows.take(DIV_ROWS)
        shown.forEach { r ->
            val amount = r.optDouble("amount", 0.0)
            card.addView(text(
                (if (r.optBoolean("locked")) "✓ " else "✗ ") + r.optString("company") + "  ·  " + (if (amount > 0) "₹" + String.format(Locale.ENGLISH, "%.2f", amount) + "  ·  " else "") +
                    "record " + r.optString("record") + (if (r.optBoolean("locked")) "" else "\n    " + r.optString("why")),
                13f, false, if (r.optBoolean("locked")) INK else GREY
            ))
        }
        if (rows.size > DIV_ROWS) {
            card.addView(button(if ("divlist" in full) "▲ Less" else "▼ All " + rows.size) {
                if ("divlist" in full) full.remove("divlist") else full.add("divlist")
                render()
            })
        }
        val app = applicationContext
        card.addView(button("🔍 Scan dividends now (list + news)") { work("Scanning dividends") { Patterns.syncDividends(app) } })
        box.addView(card, wide(6))
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
    /** Ek lock hua share chhote dabbe mein. Tap karo to poora hisaab. */
    private fun tile(lock: JSONObject): LinearLayout {
        val change = if (lock.has("now")) lock.optDouble("now") else MarketLogic.pct(lock.optDouble("price", 0.0), lock.optDouble("lockPrice", 0.0))
        val plain = change in -0.004..0.004
        val ink = if (plain) INK else 0xFFFFFFFF.toInt()
        val soft = if (plain) GREY else 0xE6FFFFFF.toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(if (change > 0.004) GREEN else if (change < -0.004) RED else 0xFFE4E7EC.toInt(), 10)
            setOnClickListener { startActivity(PatternLockActivity.forLock(this@PatternActivity, lock.optString("id"))) }
        }
        fun line(words: String, size: Float, bold: Boolean, color: Int) = TextView(this).apply {
            text = words
            textSize = size
            setTextColor(color)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(line(lock.optString("symbol").substringBefore('.'), 13f, true, ink))
        card.addView(line(MarketLogic.money(lock.optDouble("price", 0.0), "INR"), 14f, true, ink))
        card.addView(line(PatternLogic.signed(change), 18f, true, ink))
        card.addView(line("Lock " + MarketLogic.money(lock.optDouble("lockPrice", 0.0), "INR"), 11f, false, soft))
        card.addView(line(day.format(Date(lock.optLong("lockAt"))), 11f, false, soft))
        MarketLogic.valuationLook(lock.optString("valuation"))?.let { look ->
            card.addView(TextView(this).apply {
                text = look.first
                textSize = 11f
                setTextColor(look.second)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                maxLines = 1
                background = rounded(look.third, 5)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        }
        return card
    }

    companion object {
        private const val TILES = 3
        private const val TILE_ROWS = 3
        private const val DIV_ROWS = 8
        private const val TRUST = 20
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF16A34A.toInt()
        private const val RED = 0xFFDC2626.toInt()
        private const val TEAL = 0xFF1F5C4D.toInt()
        private const val BLUE = 0xFF1565C0.toInt()
    }
}
