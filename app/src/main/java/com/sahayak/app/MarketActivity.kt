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
    private val closedCaps = mutableSetOf<String>()
    private var loading = true
    private lateinit var greenStatus: TextView
    private lateinit var greenFresh: Button
    private lateinit var greenBox: LinearLayout
    private var greenOpen = false
    private var greenCap = GreenList.CAPS[0]
    private val capButtons = mutableMapOf<String, Button>()
    private val greenGroups = mutableSetOf(GreenList.MAX_GREEN, GreenList.MAX_GREEN - 1)
    private lateinit var verifyLine: TextView
    private lateinit var topStatus: TextView
    private val verifyTick = object : Runnable {
        override fun run() {
            if (!Market.verifying()) return
            shareStatus.text = Market.verifyProgress
            topStatus.text = Market.verifyProgress
            main.postDelayed(this, 1_000L)
        }
    }
    private val greenTick = object : Runnable {
        override fun run() {
            if (!GreenList.running()) return
            greenStatus.text = GreenList.progress
            main.postDelayed(this, 1_000L)
        }
    }
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

    /** Chhota nishan: Tickertape valuation Low (hara), Avg (peela), High (laal). */
    private fun valueTag(tag: String): TextView? {
        val look = MarketLogic.valuationLook(tag) ?: return null
        return TextView(this).apply {
            text = look.first
            textSize = 11f
            setTextColor(look.second)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(5), dp(1), dp(5), dp(1))
            maxLines = 1
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(look.third)
                cornerRadius = dp(5).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) }
        }
    }

    /** Badha to hara dabba, gira to laal, barabar to grey. Safed akshar. */
    private fun chip(words: String, change: Double) = TextView(this).apply {
        text = words
        textSize = 13f
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (change > 0.004) CHIP_GREEN else if (change < -0.004) CHIP_RED else CHIP_GREY)
            cornerRadius = dp(6).toFloat()
        }
    }

    private fun chipSpace() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dp(10)
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
        // Sabse upar: bhaav abhi update karo
        val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(Button(this).apply {
            text = "🔄 Update prices"
            isAllCaps = false
            textSize = 16f
            setOnClickListener {
                main.postDelayed(verifyTick, 500L)
                work(topStatus, "Updating all prices") { Market.verifyAll(applicationContext, false) }
            }
        })
        topStatus = text("", 13f, false, GREY)
        topRow.addView(topStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        page.addView(topRow)
        page.addView(text("See news that can move the market. Save a share to follow its price every day.", 14f))

        // ----- Mere shares -----
        page.addView(button("💼 My portfolio: dummy funds") {
            startActivity(Intent(this, FundsActivity::class.java))
        })
        page.addView(button("📊 Pattern study: dividend, split, buyback and more") {
            startActivity(Intent(this, PatternActivity::class.java))
        })
        page.addView(text("My shares", 18f, true))
        name = EditText(this).apply {
            hint = "Share name, e.g. Tata Motors"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        page.addView(name)
        page.addView(button("Lock at today's price") {
            val typed = name.text.toString()
            work(shareStatus, "Finding the share and its price") {
                val answer = Market.addShare(app, typed)
                if (answer.startsWith("Locked")) main.post { name.setText("") }
                answer
            }
        })
        shareStatus = text("", 14f, true)
        page.addView(shareStatus)
        sharesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(sharesBox)
        page.addView(button("Verify all prices now") {
            main.postDelayed(verifyTick, 500L)
            work(shareStatus, "Checking every price from 3 sources") { Market.verifyAll(app, false) }
        })
        page.addView(CheckBox(this).apply {
            text = "Update prices every 15 minutes while the market is open"
            isChecked = Market.verifyOn(this@MarketActivity)
            setOnCheckedChangeListener { _, on ->
                Market.setVerify(this@MarketActivity, on)
                render()
            }
        })
        verifyLine = text("", 13f, false, GREY)
        page.addView(verifyLine)

        // ----- Hara scorecard -----
        page.addView(text("Green scorecard", 18f, true))
        page.addView(text("Shares by how much of their Tickertape scorecard is green: 6 of 6, 5 of 6, 4 of 6. Tap a share to open it in Tickertape.", 13f))
        val capRow = LinearLayout(this)
        GreenList.CAPS.forEach { cap ->
            val b = button(cap.label) {
                // Wahi button dobara: list band. Naya button: uski list, na ho to banao
                if (greenCap == cap && greenOpen) greenOpen = false else {
                    greenCap = cap
                    greenOpen = true
                    if (GreenList.saved(this, cap) == null && !GreenList.running()) makeGreen()
                }
                render()
            }
            capButtons[cap.id] = b
            capRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        page.addView(capRow)
        page.addView(text("Largecap: biggest 100 companies. Midcap: next 150. Smallcap: next 500.", 12f, false, GREY))
        greenFresh = button("") { if (GreenList.running()) GreenList.stop() else makeGreen() }
        page.addView(greenFresh)
        greenStatus = text("", 14f, true)
        page.addView(greenStatus)
        greenBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(greenBox)

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
        if (GreenList.running()) main.post(greenTick)
        if (Market.verifying()) main.post(verifyTick)
        Patterns.rearm(this)
        // Jin locked shares ka sector nahi pata, peeche se dhoondh lo
        val app = applicationContext
        Thread {
            val changed = try {
                listOf(Market.fillSectors(app), Market.fillValuations(app), Market.fillBuffett(app)).any { it }
            } catch (e: org.json.JSONException) {
                false
            }
            if (changed) main.post { if (!isDestroyed) render() }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresh)
        main.removeCallbacks(greenTick)
        main.removeCallbacks(verifyTick)
    }

    /** Tickertape se sab companies ka scorecard dekhna: kuch minute lagte hain. */
    private fun makeGreen() {
        val app = applicationContext
        val cap = greenCap
        greenOpen = true
        greenStatus.text = "Starting"
        Thread {
            val answer = GreenList.run(app, cap)
            main.post {
                if (isDestroyed) return@post
                greenStatus.text = answer
                render()
            }
        }.start()
        main.postDelayed(greenTick, 500L)
        main.postDelayed({ if (!isDestroyed) render() }, 600L)
    }

    /** Green list ka share aaj ke bhaav par My shares mein. */
    private fun lockGreen(row: org.json.JSONObject, count: Int) {
        val name = row.optString("name")
        greenStatus.text = "Locking $name at today's price"
        val app = applicationContext
        val from = greenCap.label + " green scorecard: " + count + " of " + row.optInt("total") + " green"
        Thread {
            val answer = try {
                Market.lockSymbol(app, row.optString("ticker"), name, from, row.optString("slug"), row.optString("sector"))
            } catch (e: org.json.JSONException) {
                "Something went wrong. Try again"
            }
            main.post {
                if (isDestroyed) return@post
                greenStatus.text = answer
                render()
            }
        }.start()
    }

    private fun openGreen(row: org.json.JSONObject) {
        val slug = row.optString("slug")
        val code = row.optString("ticker")
        if (slug.startsWith("/stocks/")) {
            greenStatus.text = Market.openTickertape(this, "https://www.tickertape.in$slug", code)
            return
        }
        greenStatus.text = "Opening " + row.optString("name") + " in Tickertape"
        Thread {
            val link = Market.tickertapeLink(row.optString("name"), row.optString("sid"), code)
            main.post {
                if (isDestroyed) return@post
                greenStatus.text = Market.openTickertape(this, link, code)
            }
        }.start()
    }


    private fun renderGreen() {
        val running = GreenList.running()
        greenFresh.text = if (running) "Stop" else "Make fresh " + greenCap.label + " list (takes a few minutes)"
        greenFresh.visibility = if (greenOpen || running) android.view.View.VISIBLE else android.view.View.GONE
        capButtons.forEach { (id, b) ->
            val cap = GreenList.CAPS.first { it.id == id }
            b.text = (if (greenOpen && cap == greenCap) "● " else "") + cap.label
        }
        greenBox.removeAllViews()
        if (!greenOpen) return
        val saved = GreenList.saved(this, greenCap)
        if (saved == null) {
            greenBox.addView(text(
                if (GreenList.runningCap == greenCap.id) "Making the " + greenCap.label + " list" else if (running) "Another list is being made. Wait or tap Stop" else "No " + greenCap.label + " list yet.",
                14f, false, GREY
            ))
            return
        }
        greenBox.addView(text(greenCap.label, 16f, true))
        val rows = saved.optJSONArray("rows") ?: org.json.JSONArray()
        val all = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        val full = all.count { it.optInt("green") >= GreenList.MAX_GREEN }
        greenBox.addView(text("List made " + time.format(Date(saved.optLong("at"))) + ". Checked " + saved.optInt("checked") + " companies. All green: " + full, 13f, false, GREY))
        // 6 of 6, phir 5 of 6, phir 4 of 6: har list apne button se khulti hai
        for (count in GreenList.MAX_GREEN downTo GreenList.MIN_GREEN) {
            val group = all.filter { minOf(it.optInt("green"), GreenList.MAX_GREEN) == count }
            val open = count in greenGroups
            greenBox.addView(button((if (open) "▲ " else "▼ ") + count + " of " + GreenList.MAX_GREEN + " green  (" + group.size + ")") {
                if (open) greenGroups.remove(count) else greenGroups.add(count)
                render()
            })
            if (!open) continue
            if (group.isEmpty()) greenBox.addView(text("No share here right now.", 14f, false, GREY))
            // Sector ke hisaab se: Energy (4), Financials (12) ...
            val bySector = group.groupBy { MarketLogic.sectorName(it.optString("sector")) }.toSortedMap()
            val named = bySector.size > 1 || bySector.keys.firstOrNull() != "Other"
            if (!named && group.isNotEmpty()) greenBox.addView(text("To see sectors, tap Make fresh " + greenCap.label + " list.", 12f, false, GREY))
            bySector.forEach { (sector, list) ->
              if (named) greenBox.addView(sectorHead(sector, list.size))
              list.forEach { row ->
                val box = card()
                val ticker = row.optString("ticker")
                box.addView(text(row.optString("name") + (if (ticker.isNotEmpty()) "  ($ticker)" else ""), 16f, true))
                box.addView(text(row.optInt("green").toString() + " of " + row.optInt("total") + " green  ·  Open in Tickertape", 13f, true, if (count == GreenList.MAX_GREEN) GREEN else TEAL))
                valueTag(row.optString("valuation"))?.let { box.addView(it) }
                box.setOnClickListener { openGreen(row) }
                val locked = Market.shares(this).any { it.optString("symbol").substringBefore('.') == ticker && ticker.isNotEmpty() }
                val actions = LinearLayout(this).apply { gravity = Gravity.END }
                actions.addView(button("🛒 Buy") {
                    BuyDialog.show(this, row.optString("ticker"), row.optString("name"), row.optString("slug")) { greenStatus.text = it }
                })
                actions.addView(button(if (locked) "✓ Locked" else "🔒 Lock at today's price") {
                    if (!locked) lockGreen(row, count)
                }.apply { isEnabled = !locked })
                box.addView(actions)
                greenBox.addCard(box)
              }
            }
        }
    }

    /** Sector ki chhoti patti: naam aur ginti. */
    private fun sectorHead(sector: String, count: Int) = TextView(this).apply {
        text = "$sector  ($count)"
        textSize = 14f
        setTextColor(0xFF175CD3.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFE8F1FF.toInt())
            cornerRadius = dp(6).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    /** Dabbe sector ke hisaab se. Kisi ka sector pata na ho to seedhe dabbe. */
    private fun sectorGrid(list: List<org.json.JSONObject>) {
        val bySector = list.groupBy { MarketLogic.sectorName(it.optString("sector")) }.toSortedMap()
        if (bySector.size == 1 && bySector.keys.first() == "Other") {
            grid(list)
            return
        }
        bySector.forEach { (sector, shares) ->
            sharesBox.addView(sectorHead(sector, shares.size))
            grid(shares)
        }
    }

    private fun render() {
        renderGreen()
        renderShares()
        renderReports()
        renderNews()
    }

    private fun renderShares() {
        sharesBox.removeAllViews()
        val shares = Market.shares(this)
        if (shares.isEmpty()) {
            sharesBox.addView(text("No share locked yet.", 14f, false, GREY))
            return
        }
        // Pehle Smallcap (6, 5, 4 of 6 green), phir Midcap, phir Largecap, aakhir mein khabar ya naam se lock kiye
        val ordered = shares.sortedByDescending { it.optLong("savedAt") }
        var up = 0
        var down = 0
        ordered.forEach {
            val c = MarketLogic.pct(it.optDouble("lastPrice", 0.0), it.optDouble("savedPrice", 0.0))
            if (c > 0.004) up++ else if (c < -0.004) down++
        }
        sharesBox.addView(text(ordered.size.toString() + " locked  ·  ▲ $up up  ·  ▼ $down down since lock. Tap a box for its chart.", 13f, true, GREY))
        // Tickertape ka valuation: Low (sasta), Avg, High (mehenga) alag-alag
        val valueBox = card()
        valueBox.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dp(10).toFloat()
        }
        valueBox.addView(text("Valuation (Tickertape)", 15f, true))
        listOf("Low", "Avg", "High").forEach { kind ->
            val group = ordered.filter { MarketLogic.valuationKind(it.optString("valuation")) == kind }
            val look = MarketLogic.valuationLook(kind) ?: return@forEach
            valueBox.addView(text(look.first + ": " + group.size + "  (" + MarketLogic.valuationMeaning(kind) + ")", 14f, true, look.second).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(look.third)
                    cornerRadius = dp(6).toFloat()
                }
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            if (group.isNotEmpty()) valueBox.addView(text(group.joinToString(",  ") { it.optString("symbol").substringBefore('.') }, 13f))
        }
        val unknown = ordered.count { MarketLogic.valuationKind(it.optString("valuation")).isEmpty() }
        if (unknown > 0) valueBox.addView(text("$unknown shares ka valuation abhi nahi mila (check ho raha hai ya Tickertape par nahi).", 12f, false, GREY))
        valueBox.addView(text("Din mein ek baar Tickertape scorecard se check hota hai.", 12f, false, GREY))
        sharesBox.addCard(valueBox)
        val sure = ordered.count { it.optJSONObject("check")?.optBoolean("ok") == true }
        val doubt = ordered.count { it.optJSONObject("check")?.optBoolean("ok") == false }
        val last = Market.lastVerify(this)
        val next = Market.nextVerify(this)
        if (!Market.verifying()) topStatus.text = (if (last > 0) "Updated " + SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(last)) else "Not updated yet") +
            (if (Market.verifyOn(this) && next > 0) "\nNext " + SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(next)) else "")
        verifyLine.text = "✓ $sure verified by 2 or more sources" + (if (doubt > 0) "  ·  ⚠ $doubt need a look" else "") +
            (if (last > 0) "\nLast check " + time.format(Date(last)) else "") +
            (if (Market.verifyOn(this) && next > 0) "  ·  Next " + time.format(Date(next)) else "")
        val groups = ordered.groupBy { MarketLogic.lockGroup(it.optString("fromTitle")) }
        val sections = listOf("Smallcap", "Midcap", "Largecap").map { cap ->
            cap to (GreenList.MAX_GREEN downTo GreenList.MIN_GREEN).map { n -> n to groups[cap to n].orEmpty() }
        }
        sections.forEach { (cap, parts) ->
            val total = parts.sumOf { it.second.size }
            if (total == 0) return@forEach
            val open = cap !in closedCaps
            sharesBox.addView(section((if (open) "▼ " else "▶ ") + cap + "  ($total)") {
                if (open) closedCaps.add(cap) else closedCaps.remove(cap)
                render()
            })
            if (!open) return@forEach
            parts.forEach { (n, list) ->
                if (list.isEmpty()) return@forEach
                sharesBox.addView(text("$n of " + GreenList.MAX_GREEN + " green  (" + list.size + ")", 15f, true, if (n == GreenList.MAX_GREEN) GREEN else TEAL))
                sectorGrid(list)
            }
        }
        val other = groups[null].orEmpty()
        if (other.isNotEmpty()) {
            val open = OTHER !in closedCaps
            sharesBox.addView(section((if (open) "▼ " else "▶ ") + "From news or typed  (" + other.size + ")") {
                if (open) closedCaps.add(OTHER) else closedCaps.remove(OTHER)
                render()
            })
            if (open) sectorGrid(other)
        }
    }

    /** Group ka naam: bada, tap karke band ya khulta hai. */
    private fun section(words: String, action: () -> Unit) = TextView(this).apply {
        text = words
        textSize = 17f
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(INK)
            cornerRadius = dp(8).toFloat()
        }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
    }

    /** Ek line mein 3 dabbe. */
    private fun grid(list: List<org.json.JSONObject>) {
        list.chunked(TILES).forEach { line ->
            val row = LinearLayout(this)
            line.forEach { s -> row.addView(tile(s), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            // Khaali jagah taaki aakhri line ke dabbe bhi same size ke rahein
            repeat(TILES - line.size) { row.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
            sharesBox.addView(row)
        }
    }

    /** Ek share ka chhota dabba: lock se badha to poora hara, ghata to poora laal. */
    private fun tile(s: org.json.JSONObject): LinearLayout {
        val currency = s.optString("currency")
        val price = s.optDouble("lastPrice", 0.0)
        val saved = s.optDouble("savedPrice", 0.0)
        val change = MarketLogic.pct(price, saved)
        val plain = change in -0.004..0.004
        val ink = if (plain) INK else 0xFFFFFFFF.toInt()
        val soft = if (plain) GREY else 0xE6FFFFFF.toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (change > 0.004) CHIP_GREEN else if (change < -0.004) CHIP_RED else 0xFFE4E7EC.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { startActivity(ShareActivity.forShare(this@MarketActivity, s.optString("id"))) }
        }
        fun line(words: String, size: Float, bold: Boolean, color: Int) = TextView(this).apply {
            text = words
            textSize = size
            setTextColor(color)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val check = s.optJSONObject("check")
        val mark = if (check == null) "" else if (check.optBoolean("ok")) " ✓" else " ⚠"
        box.addView(line(s.optString("symbol").substringBefore('.') + mark, 13f, true, ink))
        box.addView(line(MarketLogic.money(price, currency), 14f, true, ink))
        box.addView(line(MarketLogic.pctText(price, saved), 18f, true, ink))
        box.addView(line("Lock " + MarketLogic.money(saved, currency), 11f, false, soft))
        box.addView(line(day.format(Date(s.optLong("savedAt"))) + if (MarketLogic.indianSymbol(s.optString("symbol"))) "" else "  ·  Foreign", 11f, false, soft))
        valueTag(s.optString("valuation"))?.let { box.addView(it) }
        s.optString("buffett").takeIf { it.contains('/') }?.let { b ->
            box.addView(TextView(this).apply {
                text = "🧓 Buffett $b"
                textSize = 11f
                setTextColor(INK)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFE8F1FF.toInt())
                    cornerRadius = dp(5).toFloat()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        }
        // Chhota Buy button: dummy fund mein kharido
        box.addView(TextView(this).apply {
            text = "🛒 Buy"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(INK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF2FFFFFF.toInt())
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { BuyDialog.show(this@MarketActivity, s.optString("symbol"), s.optString("name"), s.optString("slug")) { shareStatus.text = it } }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        return box
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
        shown.forEach { r -> reportView(r) }
        if (reports.size > 1) {
            reportBox.addView(button(if (reportsOpen) "Purani reports chhupao" else "Purani reports (" + (reports.size - 1) + ")") {
                reportsOpen = !reportsOpen
                render()
            })
        }
    }

    /** Ek report: upar saar, phir har share ka saaf dabba (Hinglish). */
    private fun reportView(r: org.json.JSONObject) {
        val items = r.optJSONArray("items") ?: return
        val list = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
        val head = card()
        head.addView(text("📋 Report  ·  " + time.format(Date(r.optLong("at"))), 16f, true, TEAL))
        val up = list.count { it.optDouble("up", 0.0) > 0.004 }
        val down = list.count { it.optDouble("up", 0.0) < -0.004 }
        head.addView(text(list.size.toString() + " shares   ·   🟢 $up lock se upar   ·   🔴 $down lock se neeche", 14f, true))
        if (list.any { it.optBoolean("closed") }) head.addView(text("Aaj bazaar band tha, isliye pichhle bazaar din ka bhaav dikh raha hai.", 13f, false, GREY))
        if (list.any { it.has("fresh") && !it.optBoolean("fresh") }) head.addView(text("Kuch shares ka aaj ka bhaav nahi mila, unka purana bhaav dikh raha hai.", 13f, false, GREY))
        reportBox.addCard(head)
        // Sabse zyada badhe upar, sabse zyada gire neeche
        list.sortedByDescending { it.optDouble("up", 0.0) }.forEach { item -> reportShare(item) }
    }

    private fun reportShare(item: org.json.JSONObject) {
        val box = card()
        val change = item.optDouble("up", 0.0)
        // Baayi taraf patli hari/laal patti, ek nazar mein pata chale
        box.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(if (change > 0.004) CHIP_GREEN else if (change < -0.004) CHIP_RED else CHIP_GREY)
                cornerRadius = dp(10).toFloat()
            },
            android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(10).toFloat()
            }
        )).apply { setLayerInset(1, dp(5), 0, 0, 0) }
        box.setPadding(dp(16), dp(10), dp(12), dp(10))
        val code = item.optString("code")
        box.addView(text(item.optString("name") + if (code.isNotEmpty()) "  ($code)" else "", 16f, true))
        if (!item.has("price")) {
            // Purani report: jaisi thi waisi
            box.addView(text(item.optString("text"), 14f, false, if (change > 0.004) GREEN else if (change < -0.004) RED else INK))
        } else {
            val currency = item.optString("currency")
            val nowRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            nowRow.addView(text(MarketLogic.money(item.optDouble("price"), currency), 22f, true))
            if (item.has("today")) {
                val today = item.optDouble("today")
                nowRow.addView(chip("Aaj " + MarketLogic.pctText(1.0 + today / 100.0, 1.0), today), chipSpace())
            }
            box.addView(nowRow)
            val lockRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            lockRow.addView(text("🔒 " + day.format(Date(item.optLong("savedAt"))) + " ko " + MarketLogic.money(item.optDouble("saved"), currency), 14f, false, GREY))
            lockRow.addView(chip("Lock se " + MarketLogic.pctText(1.0 + change / 100.0, 1.0), change), chipSpace())
            box.addView(lockRow)
        }
        val news = item.optJSONArray("news")
        if (news != null && news.length() > 0) {
            box.addView(text("Khabar:", 13f, true, GREY).apply { setPadding(0, dp(8), 0, 0) })
            for (j in 0 until minOf(news.length(), 2)) {
                val n = news.optJSONObject(j) ?: continue
                val source = n.optString("s")
                box.addView(text("📰 " + n.optString("t") + if (source.isNotEmpty()) "  ($source)" else "", 13f, false, BLUE).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener { open(n.optString("l")) }
                })
            }
        }
        box.setOnClickListener {
            Market.shares(this).firstOrNull { it.optString("symbol").substringBefore('.') == code && code.isNotEmpty() }
                ?.let { startActivity(ShareActivity.forShare(this, it.optString("id"))) }
        }
        reportBox.addCard(box)
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
        private const val TILES = 3
        private const val OTHER = "other"
        private const val CHIP_GREEN = 0xFF16A34A.toInt()
        private const val CHIP_RED = 0xFFDC2626.toInt()
        private const val CHIP_GREY = 0xFF98A2B3.toInt()
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF1B7F3B.toInt()
        private const val RED = 0xFFC62828.toInt()
        private const val TEAL = 0xFF1F5C4D.toInt()
        private const val BLUE = 0xFF1565C0.toInt()
    }
}
