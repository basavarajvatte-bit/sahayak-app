package com.sahayak.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ek dummy fund: saar, shares kharidna, har share ka dabba, beche hue shares. */
class FundActivity : Activity() {
    private lateinit var fundId: String
    private lateinit var title: TextView
    private lateinit var summary: LinearLayout
    private lateinit var box: LinearLayout
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private val day = SimpleDateFormat("d MMM", Locale.ENGLISH)

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

    private fun wide(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    /** Internet ka kaam peeche, jawab screen par. */
    private fun work(busy: String, job: () -> String) {
        status.text = busy
        val tick = object : Runnable {
            override fun run() {
                if (Funds.progress.isNotEmpty()) status.text = Funds.progress
                main.postDelayed(this, 800L)
            }
        }
        main.postDelayed(tick, 600L)
        Thread {
            val answer = try {
                job()
            } catch (e: org.json.JSONException) {
                "Kuch gadbad hui. Dobara try karo"
            }
            main.post {
                main.removeCallbacks(tick)
                if (isDestroyed) return@post
                status.text = answer
                render()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fundId = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (Funds.get(this, fundId) == null) {
            finish()
            return
        }
        val app = applicationContext
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        title = text("", 24f, true)
        page.addView(title)
        summary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(summary)

        // ----- Share kharido -----
        val buy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFFFFF.toInt(), 12)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        buy.addView(text("🛒 Share kharido", 15f, true))
        buy.addView(text("Aaj ke jaanche hue bhaav par kharida jayega. Sirf poore share aate hain.", 12f, false, GREY))
        val name = EditText(this).apply {
            hint = "Share ka naam, jaise Ashok Leyland"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        buy.addView(name)
        val amount = EditText(this).apply {
            hint = "Kitne rupaye ka, jaise 10000"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        buy.addView(amount)
        buy.addView(button("Kharido") {
            val n = name.text.toString()
            val rs = amount.text.toString().toDoubleOrNull() ?: 0.0
            work("Kharid raha hu") {
                val answer = Funds.buy(app, fundId, n, rs)
                if (answer.startsWith("Kharida")) main.post {
                    name.setText("")
                    amount.setText("")
                }
                answer
            }
        })
        page.addView(buy, wide(12))
        status = text("", 14f, true, TEAL)
        page.addView(status)
        page.addView(button("Bhaav update karo") { work("Bhaav update ho rahe hain") { Funds.refresh(app) } })
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        page.addView(button("Ye fund delete karo") {
            AlertDialog.Builder(this).setMessage("Ye fund aur iske saare shares hata du?")
                .setPositiveButton("Haan, hatao") { _, _ ->
                    Funds.delete(this, fundId)
                    finish()
                }.setNegativeButton("Nahi", null).show()
        }, wide(20))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val fund = Funds.get(this, fundId) ?: return
        val nifty = Funds.niftyNow(this)
        title.text = "💼 " + fund.optString("name")
        val s = FundLogic.summary(fund, nifty)
        summary.removeAllViews()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(INK, 12)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        head.addView(text("Abhi ki keemat", 13f, true, SOFT))
        head.addView(text(FundLogic.rupees(s.value), 28f, true, 0xFFFFFFFF.toInt()))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(chip("Kul " + FundLogic.rupees(s.profit, true) + " (" + PatternLogic.signed(s.profitPct) + ")", s.profit))
        if (Math.abs(s.dayChange) > 0.004) row.addView(chip("Aaj " + FundLogic.rupees(s.dayChange, true), s.dayChange),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        head.addView(row)
        head.addView(text("Laga: " + FundLogic.rupees(s.invested) + (if (s.soldCost > 0) "  ·  Beche hue ka munafa: " + FundLogic.rupees(s.realized, true) else ""), 13f, false, SOFT))
        if (nifty != null && s.invested + s.soldCost > 0) {
            val beat = s.profitPct - s.niftyPct
            head.addView(text("Wahi paisa Nifty mein: " + PatternLogic.signed(s.niftyPct) + "  ·  " + (if (beat >= 0) "Aap Nifty se aage " else "Aap Nifty se peeche ") +
                String.format(Locale.ENGLISH, "%.2f", Math.abs(beat)) + "%", 13f, true, if (beat >= 0) 0xFF4ADE80.toInt() else 0xFFF87171.toInt()))
        }
        summary.addView(head, wide(8))

        box.removeAllViews()
        val holds = fund.optJSONArray("holdings")
        val list = (0 until (holds?.length() ?: 0)).mapNotNull { holds?.optJSONObject(it) }
        box.addView(text("Shares (" + list.size + ")", 17f, true).apply { setPadding(0, dp(14), 0, dp(4)) })
        if (list.isEmpty()) box.addView(text("Abhi koi share nahi. Upar se kharido.", 14f, false, GREY))
        // Ek line mein 3 dabbe: kharid ke bhaav se upar hara, neeche laal
        list.sortedByDescending { FundLogic.value(it) - FundLogic.cost(it) }.chunked(3).forEach { line ->
            val strip = LinearLayout(this)
            line.forEach { h -> strip.addView(tile(h), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            repeat(3 - line.size) { strip.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
            box.addView(strip)
        }
        val sold = fund.optJSONArray("sold")
        if (sold != null && sold.length() > 0) {
            box.addView(text("Beche hue (" + sold.length() + ")", 15f, true, GREY).apply { setPadding(0, dp(12), 0, dp(2)) })
            for (i in sold.length() - 1 downTo 0) {
                val h = sold.optJSONObject(i) ?: continue
                val profit = h.optInt("qty") * (h.optDouble("sellPrice") - h.optDouble("buyPrice"))
                box.addView(text(h.optString("name") + ":  " + h.optInt("qty") + " × " + FundLogic.rupees(h.optDouble("buyPrice")) + " → " +
                    FundLogic.rupees(h.optDouble("sellPrice")) + "  ·  " + FundLogic.rupees(profit, true), 13f, false, color(profit)))
            }
        }
    }

    /** Ek share ka dabba. */
    private fun tile(h: JSONObject): LinearLayout {
        val cost = FundLogic.cost(h)
        val profit = FundLogic.value(h) - cost
        val pct = if (cost > 0) profit / cost * 100 else 0.0
        val plain = Math.abs(profit) < 0.005
        val ink = if (plain) INK else 0xFFFFFFFF.toInt()
        val soft = if (plain) GREY else 0xE6FFFFFF.toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(if (profit > 0.004) GREEN else if (profit < -0.004) RED else 0xFFE4E7EC.toInt(), 10)
            setOnClickListener { details(h) }
        }
        fun line(words: String, size: Float, bold: Boolean, c: Int) = TextView(this).apply {
            text = words
            textSize = size
            setTextColor(c)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(line(h.optString("symbol").substringBefore('.'), 13f, true, ink))
        card.addView(line(FundLogic.rupees(FundLogic.value(h)), 14f, true, ink))
        card.addView(line(PatternLogic.signed(pct), 18f, true, ink))
        card.addView(line(FundLogic.rupees(profit, true), 12f, true, soft))
        card.addView(line(h.optInt("qty").toString() + " × " + FundLogic.rupees(h.optDouble("buyPrice")), 11f, false, soft))
        return card
    }

    /** Share ka poora hisaab aur Becho / Tickertape. */
    private fun details(h: JSONObject) {
        val cost = FundLogic.cost(h)
        val value = FundLogic.value(h)
        val nifty = Funds.niftyNow(this)
        val niftyPct = if (nifty != null && h.optDouble("niftyAtBuy", 0.0) > 0) (nifty / h.optDouble("niftyAtBuy") - 1) * 100 else Double.NaN
        val words = StringBuilder()
            .append("Kharida: ").append(day.format(Date(h.optLong("buyAt")))).append(", ").append(h.optInt("qty")).append(" share × ").append(FundLogic.rupees(h.optDouble("buyPrice"))).append("\n")
            .append("Laga: ").append(FundLogic.rupees(cost)).append("\n")
            .append("Abhi: ").append(FundLogic.rupees(h.optDouble("lastPrice"))).append(" × ").append(h.optInt("qty")).append(" = ").append(FundLogic.rupees(value)).append("\n")
            .append("Munafa: ").append(FundLogic.rupees(value - cost, true)).append(" (").append(PatternLogic.signed(if (cost > 0) (value - cost) / cost * 100 else 0.0)).append(")")
        if (!niftyPct.isNaN()) words.append("\nUsi din se Nifty: ").append(PatternLogic.signed(niftyPct))
        val app = applicationContext
        AlertDialog.Builder(this).setTitle(h.optString("name")).setMessage(words.toString())
            .setPositiveButton("Becho") { _, _ -> work("Bech raha hu") { Funds.sell(app, fundId, h.optString("id")) } }
            .setNeutralButton("Tickertape") { _, _ ->
                val slug = h.optString("slug")
                status.text = Market.openTickertape(this, if (slug.startsWith("/stocks/")) "https://www.tickertape.in$slug" else null, h.optString("symbol"))
            }
            .setNegativeButton("Band karo", null).show()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val INK = 0xFF1D2433.toInt()
        private const val SOFT = 0xFFB8C0CC.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF16A34A.toInt()
        private const val RED = 0xFFDC2626.toInt()
        private const val TEAL = 0xFF1F5C4D.toInt()

        fun forFund(context: Context, id: String): Intent = Intent(context, FundActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
