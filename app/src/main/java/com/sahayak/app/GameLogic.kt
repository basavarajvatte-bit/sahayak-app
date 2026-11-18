package com.sahayak.app

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/** 10 minute game ki screen ke liye hisaab (GitHub par chalne wala game live.json aur history.json banata hai). */
object GameLogic {
    private const val BASE = "https://github.com/basavarajvatte-bit/sahayak-app/releases/download/game/"
    const val LIVE_URL = BASE + "live.json"
    const val HISTORY_URL = BASE + "history.json"

    /** Model seedhe tarikon se kam se kam itna aage ho tabhi "aage" maano. */
    private const val EDGE = 2.0

    /** ₹1,234 ya −₹56 */
    fun rupees(v: Double): String {
        val n = abs(v).roundToLong()
        val s = String.format(java.util.Locale.ENGLISH, "%,d", n)
        return if (v < -0.5) "−₹$s" else "₹$s"
    }

    fun pct(v: Double?): String = if (v == null || v.isNaN()) "–" else "${v.roundToLong()}%"

    /** "Upar 61%" ya "Neeche 58%" (andaze ka matlab). */
    fun guess(p: Double): String = if (p >= 0.5) "▲ up ${(p * 100).roundToLong()}%" else "▼ down ${((1 - p) * 100).roundToLong()}%"

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

    /** Model seedhe tarikon se behtar hai ya nahi, saaf shabdon mein. */
    fun verdict(score: JSONObject?): String {
        if (score == null) return "No guesses checked yet"
        val n = score.optInt("guesses", 0)
        if (n == 0) return "No guesses checked yet"
        val model = score.optDouble("model_pct", Double.NaN)
        if (model.isNaN()) return "No guesses checked yet"
        val best = bestSimple(score)
        return when {
            model >= best + EDGE -> "Model is ahead of simple ways today (${pct(model)} vs ${pct(best)}). One day means little; look at 20 days."
            model <= best - EDGE -> "Model is behind simple ways today (${pct(model)} vs ${pct(best)})."
            else -> "Model is not better than simple ways yet (${pct(model)} vs ${pct(best)})."
        }
    }

    /** "52% right of 238 · coin 50 · repeat 48 · always up 45" */
    fun scoreLine(score: JSONObject?): String {
        if (score == null || score.optInt("guesses", 0) == 0) return "No guesses checked yet"
        val parts = mutableListOf("${pct(score.optDouble("model_pct"))} right of ${score.optInt("guesses")}")
        parts += "coin 50"
        if (!score.isNull("repeat_last_pct")) parts += "repeat last ${pct(score.optDouble("repeat_last_pct"))}"
        if (!score.isNull("always_up_pct")) parts += "always up ${pct(score.optDouble("always_up_pct"))}"
        return parts.joinToString(" · ")
    }

    /** "3 trades · 1 won · −₹146 after costs" */
    fun moneyLine(score: JSONObject?): String {
        if (score == null) return ""
        val trades = score.optInt("trades", 0)
        if (trades == 0) return "No dummy trades yet"
        val won = score.optInt("trades_won", 0)
        var line = "$trades trades · $won won · ${rupees(score.optDouble("pnl", 0.0))} after costs"
        if (score.has("costs")) line += " (costs ${rupees(score.optDouble("costs", 0.0))})"
        return line
    }

    /** India ke time mein abhi bazaar khula hai? (Sirf din aur samay, chhutti nahi pata.) */
    fun marketHours(day: Int, minute: Int): Boolean = day in 2..6 && minute in (9 * 60 + 15)..(15 * 60 + 30)
}
