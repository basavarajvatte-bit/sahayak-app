package com.sahayak.app

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 10 minute game ki screen ke liye hisaab, bilkul aasaan shabdon mein (10 saal ka bachcha bhi samjhe).
 * GitHub par chalne wala game live.json aur history.json banata hai.
 */
object GameLogic {
    private const val BASE = "https://github.com/basavarajvatte-bit/sahayak-app/releases/download/game/"
    const val LIVE_URL = BASE + "live.json"
    const val HISTORY_URL = BASE + "history.json"

    /** Itna pakka ho tabhi game nakli trade karta hai. */
    const val SURE = 0.56

    /** Model seedhe tarikon se kam se kam itna aage ho tabhi "aage" maano. */
    private const val EDGE = 2.0

    /** ₹1,234 ya −₹56 */
    fun rupees(v: Double): String {
        val n = abs(v).roundToLong()
        val s = String.format(java.util.Locale.ENGLISH, "%,d", n)
        return if (v < -0.5) "−₹$s" else "₹$s"
    }

    fun pct(v: Double?): String = if (v == null || v.isNaN()) "–" else "${v.roundToLong()}%"

    /** Kitna pakka, shabdon mein. p = upar jaane ka chance (0 se 1). */
    fun sureWords(p: Double): String {
        val s = maxOf(p, 1 - p)
        return when {
            s < SURE -> "pata nahi"
            s < 0.60 -> "shayad"
            s < 0.65 -> "kaafi pakka"
            else -> "bahut pakka"
        }
    }

    /** Ek andaza ek line mein: "⬆️ Upar jayega (shayad)" ya "❔ Pata nahi". */
    fun guess(p: Double): String = when {
        maxOf(p, 1 - p) < SURE -> "❔ Pata nahi"
        p >= 0.5 -> "⬆️ Upar jayega (${sureWords(p)})"
        else -> "⬇️ Neeche jayega (${sureWords(p)})"
    }

    /** Kya ye andaza "pakka" wala hai (jis par nakli trade hota hai)? */
    fun isSure(p: Double) = maxOf(p, 1 - p) >= SURE

    /** 100 mein se kitne sahi: "100 mein se 47 sahi" */
    fun outOf100(v: Double): String = if (v.isNaN()) "–" else "100 mein se ${v.roundToInt()} sahi"

    /** Sabse achha seedha tarika: sikka, pichhla move dohrao, hamesha upar, hamesha neeche. */
    fun bestSimple(score: JSONObject): Double {
        val up = score.optDouble("always_up_pct", Double.NaN)
        val list = listOf(
            score.optDouble("coin_pct", 50.0),
            score.optDouble("repeat_last_pct", Double.NaN),
            up,
            if (up.isNaN()) Double.NaN else 100 - up,
        ).filter { !it.isNaN() }
        return list.maxOrNull() ?: 50.0
    }

    /** Chehra aur ek line: computer seedhe tarikon (sikka wagaira) se behtar hai ya nahi. */
    fun verdict(score: JSONObject?): Pair<String, String> {
        val n = score?.optInt("guesses", 0) ?: 0
        val model = score?.optDouble("model_pct", Double.NaN) ?: Double.NaN
        if (n == 0 || model.isNaN()) return "⏳" to "Abhi koi andaza jaancha nahi gaya"
        val best = bestSimple(score!!)
        return when {
            model >= best + EDGE -> "🙂" to "Aaj computer sikke se aage hai. Par ek din se kuch pata nahi chalta, 20 din dekhenge."
            model <= best - EDGE -> "😟" to "Aaj computer sikka uchhalne se bhi peeche hai. Abhi wo seekh raha hai."
            else -> "😐" to "Aaj computer sikka uchhalne jitna hi hai. Abhi wo seekh raha hai."
        }
    }

    /** Nakli paise ka hisaab: "Aaj 3 baar khela: ₹146 ganvaye 😟" */
    fun moneyWords(trades: Int, pnl: Double): String = when {
        trades == 0 -> "Aaj abhi tak koi nakli trade nahi hua (computer ko pakka nahi laga)"
        pnl > 0.5 -> "Aaj $trades baar khela: ${rupees(pnl)} kamaye 🙂"
        pnl < -0.5 -> "Aaj $trades baar khela: ${rupees(-pnl)} ganvaye 😟"
        else -> "Aaj $trades baar khela: na faayda, na nuksaan"
    }

    /** Ek nakli trade ka nateeja: "+₹62 🙂" */
    fun tradeResult(pnl: Double): String = when {
        pnl > 0.5 -> "+${rupees(pnl)} 🙂"
        pnl < -0.5 -> "${rupees(pnl)} 😟"
        else -> "₹0 😐"
    }

    /** "CE" -> Call (upar wala), "PE" -> Put (neeche wala) */
    /** Ek lot mein kitne unit (game ki setting, research/index_game.py jaisi). */
    fun lotSize(index: String): Int = when (index) {
        "NIFTY" -> 65
        "BANKNIFTY" -> 30
        else -> 0
    }

    /** "01-Dec-2026" -> "1 Dec, weekly". Mahine ka aakhri expiry = monthly (agle hafte mahina badal jaye). */
    fun expiryWords(expiry: String): String = try {
        val d = java.time.LocalDate.parse(expiry, java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH))
        val kind = if (d.plusDays(7).monthValue != d.monthValue) "monthly" else "weekly"
        d.dayOfMonth.toString() + " " + d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + ", " + kind
    } catch (e: Exception) {
        expiry
    }

    private fun price(v: Double) = "₹" + String.format(java.util.Locale.ENGLISH, "%.2f", v)

    /** "14:25" (+ minute) -> "14:55". 24 ghante ki ghadi (broker app se milane ke liye). Galat ho to jaisa aaya waisa. */
    fun clock24(hhmm: String, plus: Int = 0): String {
        val p = hhmm.trim().split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return hhmm
        val m = p.getOrNull(1)?.toIntOrNull() ?: return hhmm
        val total = ((h * 60 + m + plus) % (24 * 60) + 24 * 60) % (24 * 60)
        return String.format(java.util.Locale.ENGLISH, "%02d:%02d", total / 60, total % 60)
    }

    /**
     * Ek nakli option trade, poori jaankari ke saath:
     * "Nifty 25350 PE (1 Dec, weekly)"
     * "Liya 15:15 · Becha 15:25"
     * "1 lot (65) · ₹245.40 → ₹250.85"
     * "Lagaye ₹15,951 · Kharcha ₹78"
     */
    fun tradeLines(at: String, minutes: Int, open: Boolean, index: String, indexName: String, option: String,
                   expiry: String, qty: Int, buy: Double, sell: Double?, costs: Double?,
                   nowPrice: Double? = null, nowAt: String = ""): String {
        val parts = option.trim().split(" ")
        val contract = if (parts.size >= 3) parts[parts.size - 2].toDoubleOrNull()?.roundToLong()?.toString().orEmpty() + " " + parts.last()
        else option
        val head = indexName + " " + contract.trim() + if (expiry.isBlank()) "" else " (" + expiryWords(expiry) + ")"
        val sellAt = if (minutes > 0) clock24(at, minutes) else ""
        val times = "Liya " + clock24(at) + when {
            sellAt.isEmpty() -> ""
            open -> " · Bechenge " + sellAt + " ko"
            else -> " · Becha " + sellAt
        }
        val size = lotSize(index)
        val lots = if (size > 0 && qty > 0 && qty % size == 0) qty / size else 0
        val amount = when {
            lots == 1 -> "1 lot ($qty)"
            lots > 1 -> "$lots lot ($qty)"
            else -> "$qty unit"
        }
        val move = amount + " · " + price(buy) + when {
            sell != null -> " → " + price(sell)
            open && nowPrice != null -> " → abhi " + price(nowPrice) + if (nowAt.isBlank()) "" else " (" + clock24(nowAt) + ")"
            else -> ""
        }
        val money = "Lagaye " + rupees(buy * qty) + if (costs != null) " · Kharcha " + rupees(costs) else ""
        return head + "\n" + times + "\n" + move + "\n" + money
    }

    /** Zerodha jaisa: "+₹277" ya "−₹120". Live trade ke liye, bina emoji. */
    fun livePnl(pnl: Double): String = when {
        pnl > 0.5 -> "+" + rupees(pnl)
        pnl < -0.5 -> rupees(pnl)
        else -> "₹0"
    }

    fun optionWords(side: String): String = if (side == "CE") "Call (upar ka daav)" else "Put (neeche ka daav)"

    const val INDEX_TODAY_URL = BASE + "index_today.jsonl"
    const val SHARES_TODAY_URL = BASE + "today.jsonl"

    /** Ek jaancha hua andaza: kab, kis par, kya socha, kya hua. */
    data class Checked(val at: Int, val name: String, val price: Double, val after: Double, val movePct: Double, val p: Double, val right: Boolean)

    private fun lines(jsonl: String) = jsonl.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull {
        try { JSONObject(it) } catch (e: org.json.JSONException) { null }
    }

    /** Nifty / Bank Nifty ke jaanche hue andaze (game wahi ginta hai jo yahan hain). Naya sabse upar. */
    fun indexChecked(jsonl: String, index: String, minutes: Int): List<Checked> = lines(jsonl).filter {
        it.optString("index") == index && it.optInt("minutes") == minutes && it.optBoolean("done") && !it.isNull("right") && it.has("right")
    }.map {
        Checked(it.optInt("at"), index, it.optDouble("price"), it.optDouble("after"), it.optDouble("move_pct"), it.optDouble("p"), it.optBoolean("right"))
    }.sortedByDescending { it.at }.toList()

    /** Nifty 50 shares ke jaanche hue andaze. Naya sabse upar. */
    fun sharesChecked(jsonl: String, minutes: Int): List<Checked> {
        val key = minutes.toString()
        return lines(jsonl).mapNotNull { e ->
            val r = e.optJSONObject("res")?.optJSONObject(key) ?: return@mapNotNull null
            val p = e.optJSONObject("p")?.optDouble(key, Double.NaN) ?: Double.NaN
            if (r.isNull("right") || !r.has("right") || p.isNaN()) return@mapNotNull null
            Checked(e.optInt("at"), e.optString("symbol"), e.optDouble("price"), r.optDouble("after"), r.optDouble("move_pct"), p, r.optBoolean("right"))
        }.sortedWith(compareByDescending<Checked> { it.at }.thenBy { it.name }).toList()
    }

    /** 555 -> "09:15" */
    fun hhmm(minute: Int): String = String.format(java.util.Locale.ENGLISH, "%02d:%02d", minute / 60, minute % 60)

    /** "Upar socha" / "Neeche socha", aur kitna pakka tha. */
    fun thought(p: Double): String = (if (p >= 0.5) "⬆️ Upar socha" else "⬇️ Neeche socha") +
        if (isSure(p)) " (${sureWords(p)})" else " (halka sa · screen par 'Pata nahi' dikha)"

    /** "12 mein se 9 sahi" */
    fun countWords(list: List<Checked>): String = if (list.isEmpty()) "koi nahi" else "${list.size} mein se ${list.count { it.right }} sahi"

    /** India ke time mein abhi bazaar khula hai? (Sirf din aur samay, chhutti nahi pata.) */
    fun marketHours(day: Int, minute: Int): Boolean = day in 2..6 && minute in (9 * 60 + 15)..(15 * 60 + 30)
}
