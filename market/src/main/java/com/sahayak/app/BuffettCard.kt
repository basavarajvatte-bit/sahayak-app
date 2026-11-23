package com.sahayak.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Share page par "Buffett check" ka dabba: 8 jaanch, har ek ke saath ⓘ (samjhaane wala) button. */
class BuffettCard(
    private val a: Activity, private val symbol: String, private val financial: Boolean, private val name: String = "", private val price: Double = 0.0
) {
    val view = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val main = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = INK) = TextView(a).apply {
        text = words
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD) else typeface = Typeface.DEFAULT
        setPadding(0, dp(2), 0, dp(2))
    }

    init {
        view.background = rounded(0xFFF1F3F8.toInt(), 12)
        view.setPadding(dp(14), dp(10), dp(14), dp(12))
        load(false)
    }

    private fun header() {
        view.removeAllViews()
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("🧓 Buffett check", 17f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(infoButton {
            show("Buffett check kya hai",
                "Warren Buffett duniya ke sabse safal investors mein se hain. Wo share khareedne se pehle company ki balance sheet mein ye dekhte hain ki company " +
                    "strong hai, karz kam hai, aur lagaataar paisa jod rahi hai.\n\nYahan 8 jaanch hain. Har jaanch ke aage ⓘ dabao, uska matlab, kab ✓ milta hai aur " +
                    "kyun zaroori hai, sab likha hai.\n\n✓ = pass   ✗ = fail   ? = data nahi mila   — = is company par laagu nahi\n\n" +
                    "Data Yahoo Finance se aata hai, aksar 4 saal ka. 10 saal ka poora hisaab Screener.in button se dekho.\n\nYe seekhne ke liye hai. Pass hone ka matlab ye nahi ki share zaroor badhega.")
        })
        view.addView(head)
    }

    private fun load(fresh: Boolean) {
        header()
        view.addView(text("Balance sheet laa raha hu…", 13f, false, GREY))
        val app = a.applicationContext
        Thread {
            val json = Market.buffettData(app, symbol, fresh)
            main.post { if (!a.isDestroyed) show(json) }
        }.start()
    }

    private fun show(json: String?) {
        header()
        if (json == null) {
            view.addView(text("Balance sheet ka data nahi mila. Internet check karo, ya Screener.in par dekho.", 14f, false, GREY))
            buttons()
            return
        }
        val data = BuffettLogic.parse(json)
        val checks = BuffettLogic.evaluate(data, financial)
        val (pass, total) = BuffettLogic.score(checks)
        val good = pass * 2 >= total
        view.addView(text("Buffett score: $pass / $total", 22f, true, if (pass * 4 >= total * 3) GREEN else if (good) AMBER else RED))
        view.addView(text(BuffettLogic.years(data).toString() + " saal ke data se (Yahoo Finance)" + if (financial) " · Bank/NBFC: karz wali jaanch nahi" else "", 12f, false, GREY))
        checks.forEach { c ->
            val row = LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(2))
            }
            val (mark, color) = when (c.status) {
                BuffettLogic.PASS -> "✓" to GREEN
                BuffettLogic.FAIL -> "✗" to RED
                BuffettLogic.SKIP -> "—" to GREY
                else -> "?" to GREY
            }
            row.addView(text(mark, 20f, true, color), LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT))
            val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            words.addView(text(c.title, 15f, true))
            words.addView(text(c.value, 13f, false, GREY))
            row.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(infoButton { explain(c.id) })
            view.addView(row)
        }

        // ----- Munger ki soch: profit asli? bhaav sahi? laal jhande -----
        section("💵 Profit asli hai?")
        checkRow(BuffettLogic.quality(data))
        section("🏷️ Sasta hai ya mehenga?")
        pegRow(BuffettLogic.pegData(data))
        checkRow(BuffettLogic.ownerYield(data, price))
        section("🚩 Laal jhande")
        BuffettLogic.dataFlags(data).forEach { checkRow(it) }
        newsBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        view.addView(newsBox)
        loadNews()
        buttons()
    }

    private var newsBox: LinearLayout? = null

    private fun section(words: String) {
        view.addView(text(words, 16f, true).apply { setPadding(0, dp(14), 0, dp(2)) })
    }

    private fun checkRow(c: BuffettLogic.Check) {
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val (mark, color) = when (c.status) {
            BuffettLogic.PASS -> "✓" to GREEN
            BuffettLogic.FAIL -> "✗" to RED
            else -> "?" to GREY
        }
        row.addView(text(mark, 20f, true, color), LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT))
        val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(c.title, 15f, true))
        words.addView(text(c.value, 13f, false, GREY))
        row.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(infoButton { explainMore(c.id) })
        view.addView(row)
    }

    /** PEG: bada number, saath mein sasta/mehenga, aur ⓘ. */
    private fun pegRow(p: BuffettLogic.Peg) {
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val peg = BuffettLogic.pegOf(p.eps, p.growth, price)
        val (look, color) = BuffettLogic.pegLook(peg)
        val top = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(if (peg.isNaN()) "PEG: —" else "PEG " + BuffettLogic.num(peg), 20f, true, color))
        if (!peg.isNaN()) {
            top.addView(TextView(a).apply {
                text = look
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = rounded(color, 8)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        }
        words.addView(top)
        val pe = BuffettLogic.pe(p.eps, price)
        when {
            p.problem.isNotEmpty() -> words.addView(text(p.problem + (if (!pe.isNaN()) "  ·  P/E " + BuffettLogic.num(pe) else ""), 13f, false, GREY))
            price <= 0 -> words.addView(text("Share ka bhaav abhi nahi mila", 13f, false, GREY))
            else -> {
                words.addView(text("P/E " + BuffettLogic.num(pe) + "  ÷  Growth " + BuffettLogic.num(p.growth) + "% har saal", 13f, false, GREY))
                words.addView(text("Growth: EPS " + p.from + " se " + p.to + " (" + p.years + " saal)", 12f, false, GREY))
                if (p.growth > BuffettLogic.PEG_HOT_GROWTH) {
                    words.addView(text("⚠ Growth bahut zyada hai, aage itni nahi rahi to PEG jhootha kam dikh raha hai", 12f, false, AMBER))
                }
                if (financial) words.addView(text("⚠ Bank/NBFC par PEG kam kaam aata hai", 12f, false, AMBER))
            }
        }
        row.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(infoButton {
            val info = BuffettLogic.PEG_INFO
            show(info.title, "📌 Kya hai\n" + info.what + "\n\n✓ Kitna hona chahiye\n" + info.pass + "\n\n💡 Kyun zaroori hai\n" + info.why)
        })
        view.addView(row)
    }

    /** Khabron mein laal jhande (peeche se). */
    private fun loadNews() {
        val box = newsBox ?: return
        if (name.isEmpty()) return
        val head = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val mark = text("…", 20f, true, GREY)
        head.addView(mark, LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT))
        val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text("Khabron mein laal jhande", 15f, true))
        val line = text("6 mahine ki khabrein dekh raha hu…", 13f, false, GREY)
        words.addView(line)
        head.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(infoButton { explainMore("news") })
        box.addView(head)
        Thread {
            val found = Market.redNews(name)
            main.post {
                if (a.isDestroyed) return@post
                if (found.isEmpty()) {
                    mark.text = "✓"
                    mark.setTextColor(GREEN)
                    line.text = "Pledge, auditor, SEBI, default jaisi koi khabar nahi mili"
                    return@post
                }
                mark.text = "⚠"
                mark.setTextColor(RED)
                line.text = found.size.toString() + " khabar mili, khud padh kar dekho:"
                found.forEach { n ->
                    box.addView(text("📰 " + MarketLogic.cleanTitle(n.title) + "  (" + MarketLogic.publisher(n.title) + ")", 13f, false, 0xFF1565C0.toInt()).apply {
                        setPadding(dp(28), dp(2), 0, dp(2))
                        setOnClickListener {
                            try {
                                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.link)))
                            } catch (e: android.content.ActivityNotFoundException) {
                                // Browser nahi hai
                            }
                        }
                    })
                }
            }
        }.start()
    }

    private fun explainMore(id: String) {
        val info = BuffettLogic.MORE_INFO[id] ?: return
        show(info.title, "📌 Kya dekhte hain\n" + info.what + "\n\n✓ Kab milta hai\n" + info.pass + "\n\n💡 Kyun zaroori hai\n" + info.why)
    }

    private fun buttons() {
        val row = LinearLayout(a).apply { setPadding(0, dp(8), 0, 0) }
        row.addView(Button(a).apply {
            text = "Screener.in (10 saal)"
            isAllCaps = false
            setOnClickListener {
                val code = symbol.substringBefore('.')
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.screener.in/company/$code/")))
                } catch (e: android.content.ActivityNotFoundException) {
                    // Browser nahi hai
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(a).apply {
            text = "Dobara jaancho"
            isAllCaps = false
            setOnClickListener { load(true) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(row)
    }

    /** Gol ⓘ button. */
    private fun infoButton(action: () -> Unit) = TextView(a).apply {
        text = "i"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(Typeface.SERIF, Typeface.BOLD_ITALIC)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF175CD3.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(8) }
        setOnClickListener { action() }
    }

    private fun explain(id: String) {
        val info = BuffettLogic.INFO[id] ?: return
        show(info.title, "📌 Kya dekhte hain\n" + info.what + "\n\n✓ Kab milta hai\n" + info.pass + "\n\n💡 Kyun zaroori hai\n" + info.why)
    }

    private fun show(title: String, body: String) {
        AlertDialog.Builder(a).setTitle(title).setMessage(body).setPositiveButton("Samajh gaya", null).show().also { Lang.watch(it) }
    }

    companion object {
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF16A34A.toInt()
        private const val AMBER = 0xFFB54700.toInt()
        private const val RED = 0xFFDC2626.toInt()
    }
}
