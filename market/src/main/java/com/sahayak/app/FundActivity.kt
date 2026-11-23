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
        val think = BuyDialog.thinking(this, buy)
        buy.addView(button("Kharido") {
            val n = name.text.toString()
            val rs = amount.text.toString().toDoubleOrNull() ?: 0.0
            think.problem()?.let {
                status.text = it
                return@button
            }
            val thought = think.thought()
            work("Kharid raha hu") {
                val answer = Funds.buy(app, fundId, n, rs, thought)
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
                }.setNegativeButton("Nahi", null).show().also { Lang.watch(it) }
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

        val holds0 = fund.optJSONArray("holdings")
        val list0 = (0 until (holds0?.length() ?: 0)).mapNotNull { holds0?.optJSONObject(it) }
        // ----- Paisa kahan laga (risk) -----
        val (top, sectors) = FundLogic.concentration(list0)
        if (top != null) {
            val risk = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xFFFFFFFF.toInt(), 12)
                setPadding(dp(14), dp(8), dp(14), dp(10))
            }
            val head2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head2.addView(text("⚖️ Paisa kahan laga", 15f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head2.addView(info("Paisa kahan laga (risk)",
                "📌 Kya dekhte hain\nFund ka kitna paisa ek hi share mein aur ek hi sector mein laga hai.\n\n✓ Theek kab\nEk share mein 40% se kam, ek sector mein 50% se kam.\n\n💡 Kyun zaroori hai\n" +
                    "Saara paisa ek jagah ho to us ek share ya sector ki buri khabar poora fund gira deti hai. Buffett kam shares mein bharosa rakhte hain, lekin sirf tab jab dhandha poori tarah samajh mein aaye. Bina samjhe ek jagah zyada paisa daalna sattebaazi hai."))
            risk.addView(head2)
            val bigShare = top.second > 40
            risk.addView(text("Sabse bada hissa: " + top.first + " " + String.format(Locale.ENGLISH, "%.0f%%", top.second) + (if (bigShare) "  ⚠ bahut zyada" else ""), 14f, true, if (bigShare) RED else INK))
            val bigSector = sectors.firstOrNull()?.let { it.second > 50 && it.first != "Other" } == true
            risk.addView(text("Sector: " + sectors.take(4).joinToString(",  ") { it.first + " " + String.format(Locale.ENGLISH, "%.0f%%", it.second) } +
                (if (bigSector) "  ⚠ ek sector mein zyada" else ""), 13f, false, if (bigSector) RED else GREY))
            summary.addView(risk, wide(8))
        }
        // ----- Faisla diary -----
        val now = System.currentTimeMillis()
        val due = list0.filter { FundLogic.reviewDue(it, now) != null }
        val verdicts = list0.flatMap { h -> (0 until (h.optJSONArray("reviews")?.length() ?: 0)).mapNotNull { h.optJSONArray("reviews")?.optJSONObject(it)?.optString("verdict") } }
        if (list0.isNotEmpty()) {
            val diary = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xFFFFF6E5.toInt(), 12)
                setPadding(dp(14), dp(8), dp(14), dp(10))
            }
            val head3 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head3.addView(text("📓 Faisla diary", 15f, true, 0xFFB54700.toInt()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head3.addView(info("Faisla diary",
                "📌 Kya hai\nKhareedte waqt aapne likha tha ki share kyun liya aur kya galat ho sakta hai. 3, 6 aur 12 mahine baad app wahi dikhata hai aur poochta hai: aapka kaaran sahi nikla ya nahi?\n\n" +
                    "💡 Kyun zaroori hai\nShare kabhi kismat se badhta hai, kabhi sahi soch se. Sirf munafa dekhne se ye pata nahi chalta. Buffett aur Munger apni galtiyan likh kar rakhte the, taaki dobara na hon. Kuch mahine mein aapko pata chalega ki aapki soch kitni baar sahi nikalti hai."))
            diary.addView(head3)
            diary.addView(text(
                if (due.isEmpty()) "Abhi koi review baaki nahi. Pehla review kharid ke 90 din baad aayega."
                else "🔔 " + due.size + " faisle review ke liye: " + due.joinToString(", ") { it.optString("symbol").substringBefore('.') } + ". Share ke dabbe par tap karo.",
                14f, due.isNotEmpty()
            ))
            if (verdicts.isNotEmpty()) {
                diary.addView(text("Ab tak: ✓ " + verdicts.count { it == "right" } + " sahi nikle  ·  ✗ " + verdicts.count { it == "wrong" } + " galat  ·  ? " +
                    verdicts.count { it == "unsure" } + " pata nahi", 13f, true, GREY))
            }
            summary.addView(diary, wide(8))
        }

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
        if (FundLogic.reviewDue(h, System.currentTimeMillis()) != null) card.addView(line("📓 Review karo", 11f, true, ink))
        return card
    }

    /** Gol ⓘ button jo samjhaata hai. */
    private fun info(title: String, body: String) = TextView(this).apply {
        text = "i"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(Typeface.SERIF, Typeface.BOLD_ITALIC)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF175CD3.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        setOnClickListener { AlertDialog.Builder(this@FundActivity).setTitle(title).setMessage(body).setPositiveButton("Samajh gaya", null).show().also { Lang.watch(it) } }
    }

    /** Share ka poora hisaab, aapka kaaran, review aur Becho / Tickertape. */
    private fun details(h: JSONObject) {
        val cost = FundLogic.cost(h)
        val value = FundLogic.value(h)
        val nifty = Funds.niftyNow(this)
        val now = System.currentTimeMillis()
        val held = FundLogic.daysHeld(h, now)
        val niftyPct = if (nifty != null && h.optDouble("niftyAtBuy", 0.0) > 0) (nifty / h.optDouble("niftyAtBuy") - 1) * 100 else Double.NaN
        val words = StringBuilder()
            .append("Kharida: ").append(day.format(Date(h.optLong("buyAt")))).append(" (").append(held).append(" din se rakha)\n")
            .append(h.optInt("qty")).append(" share × ").append(FundLogic.rupees(h.optDouble("buyPrice"))).append(" = ").append(FundLogic.rupees(cost)).append("\n")
            .append("Abhi: ").append(FundLogic.rupees(h.optDouble("lastPrice"))).append(" × ").append(h.optInt("qty")).append(" = ").append(FundLogic.rupees(value)).append("\n")
            .append("Munafa: ").append(FundLogic.rupees(value - cost, true)).append(" (").append(PatternLogic.signed(if (cost > 0) (value - cost) / cost * 100 else 0.0)).append(")")
        if (!niftyPct.isNaN()) words.append("\nUsi din se Nifty: ").append(PatternLogic.signed(niftyPct))
        h.optString("sector").takeIf { it.isNotEmpty() }?.let { words.append("\nSector: ").append(it) }
        if (h.optString("why").isNotEmpty()) words.append("\n\n🧠 Aapne kyun liya:\n").append(h.optString("why"))
        if (h.optString("risks").isNotEmpty()) words.append("\n\n⚠ Aapne socha kya galat ho sakta hai:\n").append(h.optString("risks"))
        if (h.has("understand")) words.append("\n\n" + if (h.optBoolean("understand")) "✓ Aapne kaha dhandha samajhte ho" else "✗ Aapne kaha dhandha poora nahi samajhte")
        val reviews = h.optJSONArray("reviews")
        if (reviews != null && reviews.length() > 0) {
            words.append("\n\n📓 Review:")
            for (i in 0 until reviews.length()) {
                val r = reviews.optJSONObject(i) ?: continue
                words.append("\n").append(r.optInt("days")).append(" din: ").append(
                    when (r.optString("verdict")) {
                        "right" -> "✓ kaaran sahi nikla"
                        "wrong" -> "✗ kaaran galat nikla"
                        else -> "? pata nahi"
                    }
                ).append(" (tab ").append(PatternLogic.signed(r.optDouble("pct"))).append(")")
            }
        }
        val due = FundLogic.reviewDue(h, now)
        val box = AlertDialog.Builder(this).setTitle(h.optString("name")).setMessage(words.toString())
            .setPositiveButton("Becho") { _, _ -> confirmSell(h, held) }
            .setNegativeButton("Band karo", null)
        if (due != null) box.setNeutralButton("📓 Review karo ($due din)") { _, _ -> review(h, due) }
        else box.setNeutralButton("Tickertape") { _, _ ->
            val slug = h.optString("slug")
            status.text = Market.openTickertape(this, if (slug.startsWith("/stocks/")) "https://www.tickertape.in$slug" else null, h.optString("symbol"))
        }
        Lang.watch(box.show())
    }

    /** Faisla diary ka review: aapka kaaran sahi nikla? */
    private fun review(h: JSONObject, days: Int) {
        val cost = FundLogic.cost(h)
        val pct = if (cost > 0) (FundLogic.value(h) - cost) / cost * 100 else 0.0
        val msg = "$days din pehle aapne ye share liya tha.\n\n🧠 Aapka kaaran:\n" + h.optString("why").ifEmpty { "(nahi likha tha)" } +
            "\n\n⚠ Aapke soche hue khatre:\n" + h.optString("risks").ifEmpty { "(nahi likhe the)" } +
            "\n\nAb tak share " + PatternLogic.signed(pct) + ".\n\nBhaav ko chhodo, sirf dhandha dekho: jo kaaran aapne socha tha, kya wo sach hua?"
        AlertDialog.Builder(this).setTitle("📓 Review: " + h.optString("name")).setMessage(msg)
            .setPositiveButton("✓ Sahi nikla") { _, _ -> saveReview(h, days, "right") }
            .setNegativeButton("✗ Galat nikla") { _, _ -> saveReview(h, days, "wrong") }
            .setNeutralButton("? Pata nahi") { _, _ -> saveReview(h, days, "unsure") }
            .show().also { Lang.watch(it) }
    }

    private fun saveReview(h: JSONObject, days: Int, verdict: String) {
        Funds.review(this, fundId, h.optString("id"), days, verdict)
        status.text = "Diary mein save ho gaya"
        render()
    }

    /** Buffett ka sabar: 1 saal se pehle bechne par ek baar rok kar poochho. */
    private fun confirmSell(h: JSONObject, held: Int) {
        val app = applicationContext
        val sell = { work("Bech raha hu") { Funds.sell(app, fundId, h.optString("id")) } }
        if (held >= PATIENCE_DAYS) {
            sell()
            return
        }
        AlertDialog.Builder(this).setTitle("Ruko, ek baar socho")
            .setMessage("Ye share sirf $held din pehle liya tha.\n\nBuffett: \"Jo share 10 saal nahi rakh sakte, use 10 minute bhi mat rakho.\"\n\n" +
                "Aapka kaaran tha:\n" + h.optString("why").ifEmpty { "(nahi likha tha)" } + "\n\nKya ye kaaran badal gaya hai, ya sirf bhaav upar-neeche hua hai?")
            .setPositiveButton("Kaaran badla, becho") { _, _ -> sell() }
            .setNegativeButton("Nahi, rakhta hu", null)
            .show().also { Lang.watch(it) }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val PATIENCE_DAYS = 365
        private const val INK = 0xFF1D2433.toInt()
        private const val SOFT = 0xFFB8C0CC.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val GREEN = 0xFF16A34A.toInt()
        private const val RED = 0xFFDC2626.toInt()
        private const val TEAL = 0xFF1F5C4D.toInt()

        fun forFund(context: Context, id: String): Intent = Intent(context, FundActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
