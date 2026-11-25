package com.sahayak.app

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** My portfolio: aapke dummy funds ki list. */
class FundsActivity : Activity() {
    private lateinit var box: LinearLayout
    private lateinit var status: TextView
    private lateinit var total: LinearLayout
    private val main = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = INK) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
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

    private fun color(v: Double) = if (v > 0.004) GREEN else if (v < -0.004) RED else GREY

    private fun chip(words: String, v: Double) = text(words, 14f, true, 0xFFFFFFFF.toInt()).apply {
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = rounded(color(v), 6)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        page.addView(text("💼 My portfolio", 24f, true))
        page.addView(text("Nakli paise, asli bhaav. Apne funds banao, shares chuno, aur dekho aapka andaaza kitna sahi nikla.", 14f))
        total = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(total)

        val make = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFFFFF.toInt(), 12)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        make.addView(text("Naya fund banao", 15f, true))
        val name = EditText(this).apply {
            hint = "Fund ka naam, jaise Dividend Fund"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        make.addView(name)
        make.addView(button("+ Fund banao") {
            status.text = Funds.create(this, name.text.toString())
            if (status.text.startsWith("Fund ban gaya")) name.setText("")
            render()
        })
        page.addView(make, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        page.addView(button("Saare bhaav update karo") { refresh() })
        status = text("", 14f, true, TEAL)
        page.addView(status)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        page.addView(text("Ye seekhne ke liye nakli paisa hai, asli nivesh nahi. Faisla aapka.", 12f, false, GREY))
    }

    private fun refresh() {
        status.text = "Bhaav update ho rahe hain"
        val app = applicationContext
        val tick = object : Runnable {
            override fun run() {
                if (Funds.progress.isNotEmpty()) status.text = Funds.progress
                main.postDelayed(this, 1_000L)
            }
        }
        main.postDelayed(tick, 800L)
        Thread {
            val answer = Funds.refresh(app)
            main.post {
                main.removeCallbacks(tick)
                if (isDestroyed) return@post
                status.text = answer
                render()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        render()
        // 10 minute se purane bhaav ho to khud update
        if (System.currentTimeMillis() - Funds.lastUpdate(this) > 10 * 60_000L && Funds.all(this).any { (it.optJSONArray("holdings")?.length() ?: 0) > 0 }) refresh()
    }

    private fun render() {
        val funds = Funds.all(this)
        val nifty = Funds.niftyNow(this)
        total.removeAllViews()
        box.removeAllViews()
        if (funds.isEmpty()) {
            box.addView(text("Abhi koi fund nahi. Upar naam likho aur + Fund banao dabao.", 14f, false, GREY))
            return
        }
        val sums = funds.map { FundLogic.summary(it, nifty) }
        val invested = sums.sumOf { it.invested + it.soldCost }
        val profit = sums.sumOf { it.profit }
        if (invested > 0) {
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(INK, 12)
                setPadding(dp(14), dp(10), dp(14), dp(12))
            }
            head.addView(text("Kul portfolio", 13f, true, 0xFFB8C0CC.toInt()))
            head.addView(text(FundLogic.rupees(sums.sumOf { it.value }), 26f, true, 0xFFFFFFFF.toInt()))
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(chip(FundLogic.rupees(profit, true) + "  (" + PatternLogic.signed(profit / invested * 100) + ")", profit))
            row.addView(text("   Laga: " + FundLogic.rupees(invested), 13f, false, 0xFFB8C0CC.toInt()))
            head.addView(row)
            val updated = Funds.lastUpdate(this)
            if (updated > 0) head.addView(text("Bhaav: " + SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(updated)), 12f, false, 0xFFB8C0CC.toInt()))
            total.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        funds.forEachIndexed { i, f ->
            val s = sums[i]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xFFFFFFFF.toInt(), 12)
                setPadding(dp(14), dp(10), dp(14), dp(12))
                setOnClickListener { startActivity(FundActivity.forFund(this@FundsActivity, f.optString("id"))) }
            }
            card.addView(text(f.optString("name"), 18f, true))
            if (s.invested + s.soldCost <= 0) {
                card.addView(text("Khaali fund. Tap karke shares kharido.", 14f, false, GREY))
            } else {
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(text(FundLogic.rupees(s.value), 20f, true))
                row.addView(chip(PatternLogic.signed(s.profitPct), s.profit), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
                card.addView(row)
                card.addView(text("Laga " + FundLogic.rupees(s.invested + s.soldCost) + "  ·  Munafa " + FundLogic.rupees(s.profit, true) + "  ·  " + s.holdings + " shares", 13f, false, GREY))
                if (nifty != null) {
                    val beat = s.profitPct - s.niftyPct
                    card.addView(text("Nifty mein lagate to " + PatternLogic.signed(s.niftyPct) + "  ·  " + (if (beat >= 0) "Nifty se aage " else "Nifty se peeche ") +
                        String.format(Locale.ENGLISH, "%.2f", Math.abs(beat)) + "%", 13f, true, color(beat)))
                }
            }
            box.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }

    companion object {
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF16A34A.toInt()
        private const val RED = 0xFFDC2626.toInt()
        private const val TEAL = 0xFF1F5C4D.toInt()
    }
}
