package com.sahayak.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "100 mein se 89 sahi" dabane par: aaj ka har jaancha hua andaza, ek-ek karke.
 * Kab socha, kya socha (upar/neeche), bhaav kitne se kitna hua, aur sahi ya galat.
 */
class GuessListActivity : Activity() {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private lateinit var summary: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var all: List<GameLogic.Checked> = emptyList()
    private var filter = 0 // 0 sab, 1 sahi, 2 galat

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = INK) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(2))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun LinearLayout.addCard(view: LinearLayout) = addView(
        view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    )

    private fun row(left: String, right: String, rightColor: Int = INK, size: Float = 15f) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(left, size), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(right, size, true, rightColor).apply { gravity = Gravity.END })
    }

    private val kind get() = intent.getStringExtra(KIND) ?: "index"
    private val index get() = intent.getStringExtra(INDEX) ?: "NIFTY"
    private val minutes get() = intent.getIntExtra(MINUTES, 10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        val title = if (kind == "shares") "Nifty 50 shares" else if (index == "BANKNIFTY") "Bank Nifty" else "Nifty"
        page.addView(text("📋 $title: aaj ke saare andaze", 22f, true))
        page.addView(text("Agle $minutes minute ke andaze. Har andaze ke $minutes minute baad computer ne bhaav dekh kar jaancha.", 14f, false, GREY))
        status = text("Laa raha hoon…", 14f, false, GREY)
        page.addView(status)

        summary = card()
        page.addCard(summary)

        val buttons = LinearLayout(this)
        listOf("Sab", "✅ Sahi", "❌ Galat").forEachIndexed { i, label ->
            buttons.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { filter = i; showList() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        page.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list)
        load()
    }

    private fun load() {
        val url = if (kind == "shares") GameLogic.SHARES_TODAY_URL else GameLogic.INDEX_TODAY_URL
        Thread {
            val body = try {
                client.newCall(Request.Builder().url(url + "?t=" + System.currentTimeMillis() / 30_000).build()).execute()
                    .use { if (it.isSuccessful) it.body?.string() else null }
            } catch (e: IOException) {
                null
            }
            val rows = body?.let { if (kind == "shares") GameLogic.sharesChecked(it, minutes) else GameLogic.indexChecked(it, index, minutes) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (rows == null) {
                    status.text = "Internet check karo, phir wapas aakar dobara dabao"
                    return@runOnUiThread
                }
                all = rows
                status.text = if (rows.isEmpty()) "Aaj abhi koi andaza jaancha nahi gaya" else ""
                showSummary()
                showList()
            }
        }.start()
    }

    private fun showSummary() {
        summary.removeAllViews()
        val right = all.count { it.right }
        summary.addView(text("🧮 Hisaab", 18f, true))
        summary.addView(row("Kul jaanche gaye", all.size.toString()))
        summary.addView(row("✅ Sahi", right.toString(), GREEN))
        summary.addView(row("❌ Galat", (all.size - right).toString(), RED))
        val sure = all.filter { GameLogic.isSure(it.p) }
        val soft = all.filter { !GameLogic.isSure(it.p) }
        summary.addView(text("", 4f))
        summary.addView(row("💪 Pakke andaze", GameLogic.countWords(sure)))
        summary.addView(row("❔ 'Pata nahi' wale", GameLogic.countWords(soft), GREY))
        summary.addView(text("Dhyan do: '100 mein se ${if (all.isEmpty()) 0 else Math.round(right * 100.0 / all.size)} sahi' mein 'Pata nahi' wale halke andaze bhi gine jaate hain. " +
            "Nakli trade sirf pakke andaze par hota hai, isliye asli kaam ka number 'Pakke andaze' wala hai.", 13f, false, AMBER))
    }

    private fun showList() {
        list.removeAllViews()
        val rows = when (filter) {
            1 -> all.filter { it.right }
            2 -> all.filter { !it.right }
            else -> all
        }
        val shown = rows.take(MAX_ROWS)
        shown.forEach { c ->
            val box = card()
            val head = (if (kind == "shares") c.name + "  ·  " else "") + GameLogic.hhmm(c.at) + " → " + GameLogic.hhmm(c.at + minutes)
            box.addView(row(head, if (c.right) "✅ Sahi" else "❌ Galat", if (c.right) GREEN else RED, 16f))
            box.addView(text(GameLogic.thought(c.p), 14f, false, if (GameLogic.isSure(c.p)) INK else GREY))
            val move = String.format(Locale.ENGLISH, "%+.2f%%", c.movePct)
            val went = if (c.after > c.price) "⬆️ upar gaya" else "⬇️ neeche gaya"
            box.addView(text("Bhaav: " + money(c.price) + " → " + money(c.after) + "  ($move, $went)", 14f, false, if (c.after > c.price) GREEN else RED))
            list.addCard(box)
        }
        if (rows.size > shown.size) list.addView(text("Sirf naye ${shown.size} dikhaye. Kul ${rows.size} hain.", 13f, false, GREY))
        if (rows.isEmpty() && all.isNotEmpty()) list.addView(text("Is list mein kuch nahi", 14f, false, GREY))
    }

    private fun money(v: Double) = String.format(Locale.ENGLISH, if (kind == "shares") "₹%,.2f" else "%,.2f", v)

    companion object {
        const val KIND = "kind"
        const val INDEX = "index"
        const val MINUTES = "minutes"
        private const val MAX_ROWS = 300
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF15803D.toInt()
        private const val RED = 0xFFB91C1C.toInt()
        private const val AMBER = 0xFFB45309.toInt()
    }
}
