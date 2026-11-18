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
    fun optionWords(side: String): String = if (side == "CE") "Call (upar ka daav)" else "Put (neeche ka daav)"

    /** India ke time mein abhi bazaar khula hai? (Sirf din aur samay, chhutti nahi pata.) */
    fun marketHours(day: Int, minute: Int): Boolean = day in 2..6 && minute in (9 * 60 + 15)..(15 * 60 + 30)
}
