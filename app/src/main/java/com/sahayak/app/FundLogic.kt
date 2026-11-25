package com.sahayak.app

import org.json.JSONObject
import kotlin.math.floor

/** Dummy fund ka hisaab: kitne share aaye, kitna laga, abhi kitne ka hai, Nifty mein lagate to kitna hota. */
object FundLogic {
    /** Itne rupaye mein poore kitne share aayenge (aadha share nahi milta). */
    fun shares(amount: Double, price: Double): Int = if (price <= 0.0 || amount <= 0.0) 0 else floor(amount / price + 1e-9).toInt()

    /** Fund ka saar. */
    data class Summary(
        val invested: Double, val value: Double, val realized: Double, val niftyValue: Double, val dayChange: Double, val holdings: Int
    ) {
        /** Kul munafa: abhi ke share ka munafa + beche hue share ka munafa. */
        val profit get() = value - invested + realized
        val profitPct get() = if (invested + soldCost > 0) profit / (invested + soldCost) * 100.0 else 0.0
        var soldCost = 0.0

        /** Nifty mein wahi paise lagate to munafa %. */
        val niftyPct get() = if (invested + soldCost > 0) (niftyValue - invested - soldCost) / (invested + soldCost) * 100.0 else 0.0
    }

    /** Ek holding: kharida (qty × buyPrice), abhi (qty × lastPrice), Nifty mein wahi paisa. */
    fun cost(h: JSONObject) = h.optInt("qty") * h.optDouble("buyPrice", 0.0)

    fun value(h: JSONObject) = h.optInt("qty") * h.optDouble("lastPrice", h.optDouble("buyPrice", 0.0))

    fun niftyValue(h: JSONObject, niftyNow: Double?): Double {
        val atBuy = h.optDouble("niftyAtBuy", 0.0)
        return if (niftyNow != null && niftyNow > 0 && atBuy > 0) cost(h) * niftyNow / atBuy else cost(h)
    }

    /** Aaj ka badlav rupaye mein: (abhi - kal band) × qty. */
    fun dayChange(h: JSONObject): Double {
        val prev = h.optDouble("prevClose", 0.0)
        return if (prev > 0) (h.optDouble("lastPrice", prev) - prev) * h.optInt("qty") else 0.0
    }

    fun summary(fund: JSONObject, niftyNow: Double?): Summary {
        val holds = fund.optJSONArray("holdings")
        val sold = fund.optJSONArray("sold")
        var invested = 0.0
        var value = 0.0
        var nifty = 0.0
        var day = 0.0
        val list = (0 until (holds?.length() ?: 0)).mapNotNull { holds?.optJSONObject(it) }
        list.forEach {
            invested += cost(it)
            value += value(it)
            nifty += niftyValue(it, niftyNow)
            day += dayChange(it)
        }
        var realized = 0.0
        var soldCost = 0.0
        for (i in 0 until (sold?.length() ?: 0)) {
            val s = sold?.optJSONObject(i) ?: continue
            val c = s.optInt("qty") * s.optDouble("buyPrice", 0.0)
            soldCost += c
            realized += s.optInt("qty") * s.optDouble("sellPrice", 0.0) - c
            // Becha hua paisa Nifty mein bhi utne din ke liye: becha tab tak ka Nifty
            val atBuy = s.optDouble("niftyAtBuy", 0.0)
            val atSell = s.optDouble("niftyAtSell", 0.0)
            nifty += if (atBuy > 0 && atSell > 0) c * atSell / atBuy else c
        }
        return Summary(invested, value, realized, nifty, day, list.size).also { it.soldCost = soldCost }
    }

    /** Faisla diary: kitne din baad apna kaaran dobara jaanchna hai. */
    val REVIEW_DAYS = listOf(90, 180, 365)

    fun daysHeld(h: JSONObject, now: Long): Int = ((now - h.optLong("buyAt")) / 86_400_000L).toInt().coerceAtLeast(0)

    /** Kaunsa review baaki hai (90/180/365 din), sabse purana pehle. Koi nahi to null. */
    fun reviewDue(h: JSONObject, now: Long): Int? {
        val done = mutableSetOf<Int>()
        val reviews = h.optJSONArray("reviews")
        if (reviews != null) for (i in 0 until reviews.length()) reviews.optJSONObject(i)?.let { done.add(it.optInt("days")) }
        val held = daysHeld(h, now)
        return REVIEW_DAYS.firstOrNull { held >= it && it !in done }
    }

    /** Fund mein paisa kahan laga: (sabse bade share ka naam, uska %), aur sector ke % (bade se chhota). */
    fun concentration(holdings: List<JSONObject>): Pair<Pair<String, Double>?, List<Pair<String, Double>>> {
        val total = holdings.sumOf { value(it) }
        if (total <= 0) return null to emptyList()
        val top = holdings.maxByOrNull { value(it) }?.let { it.optString("symbol").substringBefore('.') to value(it) / total * 100 }
        val sectors = holdings.groupBy { it.optString("sector").ifEmpty { "Other" } }
            .map { (s, list) -> s to list.sumOf { value(it) } / total * 100 }.sortedByDescending { it.second }
        return top to sectors
    }

    /** ₹1,23,456 jaisa Indian tareeke se. */
    fun rupees(v: Double, sign: Boolean = false): String {
        val neg = v < 0
        val whole = Math.round(Math.abs(v) * 100) / 100.0
        val rupee = whole.toLong()
        val paise = Math.round((whole - rupee) * 100).toInt()
        val digits = rupee.toString()
        val grouped = if (digits.length <= 3) digits else {
            val head = digits.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + digits.takeLast(3)
        }
        val body = "₹" + grouped + (if (paise > 0) "." + paise.toString().padStart(2, '0') else "")
        return (if (neg) "-" else if (sign && v > 0.004) "+" else "") + body
    }
}
