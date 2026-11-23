package com.sahayak.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Dummy funds: aapke banaye nakli mutual fund, asli bhaav par. Sirf phone mein. */
object Funds {
    private const val PREFS = "sahayak_funds"
    private const val NIFTY = "^NSEI"
    private const val OPEN_TOLERANCE = 1.0
    private const val CLOSED_TOLERANCE = 0.3
    const val MAX_FUNDS = 20
    const val MAX_HOLDINGS = 50
    private val busy = AtomicBoolean(false)

    @Volatile
    var progress = ""
        private set

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<JSONObject> {
        val a = JSONArray(prefs(c).getString("funds", "[]"))
        return List(a.length()) { a.getJSONObject(it) }
    }

    fun get(c: Context, id: String) = all(c).firstOrNull { it.optString("id") == id }

    fun niftyNow(c: Context): Double? = prefs(c).getFloat("nifty", 0f).toDouble().takeIf { it > 0 }

    fun lastUpdate(c: Context) = prefs(c).getLong("updated", 0L)

    @Synchronized
    private fun change(c: Context, edit: (MutableList<JSONObject>) -> Unit) {
        val list = all(c).toMutableList()
        edit(list)
        prefs(c).edit().putString("funds", JSONArray(list).toString()).apply()
    }

    private fun holdings(f: JSONObject): JSONArray = f.optJSONArray("holdings") ?: JSONArray().also { f.put("holdings", it) }

    private fun sold(f: JSONObject): JSONArray = f.optJSONArray("sold") ?: JSONArray().also { f.put("sold", it) }

    fun create(c: Context, name: String): String {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return "Fund ka naam likho"
        if (all(c).size >= MAX_FUNDS) return "Zyada se zyada $MAX_FUNDS funds ban sakte hain"
        if (all(c).any { it.optString("name").equals(clean, true) }) return "Is naam ka fund pehle se hai"
        change(c) { it.add(JSONObject().put("id", System.currentTimeMillis().toString()).put("name", clean).put("createdAt", System.currentTimeMillis())) }
        return "Fund ban gaya: $clean"
    }

    fun delete(c: Context, id: String) = change(c) { list -> list.removeAll { it.optString("id") == id } }

    /** Bhaav do jagah se milao. Na milein to null. */
    private fun checked(symbol: String, slug: String, yahooPrice: Double?): Pair<Double, Int>? {
        val open = MarketLogic.marketOpen(System.currentTimeMillis())
        val tolerance = if (open) OPEN_TOLERANCE else CLOSED_TOLERANCE
        val prices = mutableMapOf<String, Double>()
        (yahooPrice ?: Market.yahoo(symbol)?.price)?.let { prices[MarketLogic.YAHOO] = it }
        Market.tapePrice(slug.substringAfterLast('-', ""))?.let { prices[MarketLogic.TAPE] = it }
        if (MarketLogic.verify(prices, tolerance)?.ok != true) Market.googleOnly(symbol)?.let { prices[MarketLogic.GOOGLE] = it }
        val v = MarketLogic.verify(prices, tolerance) ?: return null
        if (!v.ok && prices.size >= 2) return null
        return v.price to prices.size
    }

    /** Fund mein share kharido: itne rupaye ka, aaj ke jaanche hue bhaav par. Jawab user ke liye. */
    /** Khareedne se pehle ki soch: kyun le raha hu, kya galat ho sakta hai, dhandha samajhta hu ya nahi. */
    data class Thought(val why: String = "", val risks: String = "", val understand: Boolean = false)

    fun buy(c: Context, fundId: String, typed: String, amount: Double, thought: Thought = Thought()): String {
        val fund = get(c, fundId) ?: return "Fund nahi mila"
        if (typed.isBlank()) return "Share ka naam likho"
        if (amount <= 0) return "Kitne rupaye ka kharidna hai, wo likho"
        if (holdings(fund).length() >= MAX_HOLDINGS) return "Ek fund mein zyada se zyada $MAX_HOLDINGS shares"
        progress = "$typed dhoondh raha hu"
        val quote = Market.findIndian(typed.trim()) ?: return "\"$typed\" NSE/BSE par nahi mila. NSE code try karo, jaise RELIANCE"
        return buyQuote(c, fundId, quote, amount, "", thought)
    }

    /** Share ka NSE code pata ho (green list, My shares, pattern): seedha wahi share, naam se dhoondhe bina. */
    fun buySymbol(c: Context, fundId: String, symbol: String, name: String, slug: String, amount: Double, thought: Thought = Thought()): String {
        val fund = get(c, fundId) ?: return "Fund nahi mila"
        if (amount <= 0) return "Kitne rupaye ka kharidna hai, wo likho"
        if (holdings(fund).length() >= MAX_HOLDINGS) return "Ek fund mein zyada se zyada $MAX_HOLDINGS shares"
        progress = "$name ka bhaav jaanch raha hu"
        val code = symbol.substringBefore('.')
        val quote = (if (symbol.contains('.')) Market.yahoo(symbol) else null) ?: Market.yahoo("$code.NS") ?: Market.yahoo("$code.BO") ?: Market.findIndian(name)
            ?: return "$name ka bhaav nahi mila. Internet check karo"
        return buyQuote(c, fundId, quote.copy(name = name.ifEmpty { quote.name }), amount, slug, thought)
    }

    private fun buyQuote(c: Context, fundId: String, quote: MarketLogic.Quote, amount: Double, knownSlug: String, thought: Thought): String {
        progress = quote.name + " ka bhaav jaanch raha hu"
        val slug = knownSlug.takeIf { it.startsWith("/stocks/") }
            ?: Market.tickertapeLink(quote.name, "", quote.symbol)?.removePrefix("https://www.tickertape.in").orEmpty()
        val (price, sources) = checked(quote.symbol, slug, quote.price) ?: return quote.name + " ka bhaav alag jagah alag aa raha hai. Thodi der baad try karo"
        val qty = FundLogic.shares(amount, price)
        if (qty == 0) return FundLogic.rupees(amount) + " mein " + quote.name + " ka ek bhi share nahi aata (1 share = " + FundLogic.rupees(price) + ")"
        val nifty = Market.yahoo(NIFTY)?.price
        val now = System.currentTimeMillis()
        val h = JSONObject().put("id", now.toString()).put("symbol", quote.symbol).put("name", quote.name).put("slug", slug)
            .put("qty", qty).put("buyPrice", price).put("buyAt", now).put("lastPrice", price).put("lastAt", now).put("sources", sources)
        quote.prevClose?.let { h.put("prevClose", it) }
        nifty?.let { h.put("niftyAtBuy", it) }
        h.put("why", thought.why.trim().take(300)).put("risks", thought.risks.trim().take(400)).put("understand", thought.understand)
        // Sector, taaki fund ka risk dikh sake
        val code = quote.symbol.substringBefore('.')
        Market.tape("https://api.tickertape.in/search?text=" + java.net.URLEncoder.encode(code, "UTF-8") + "&types=stock&pageNumber=0")
            ?.let { MarketLogic.findSector(it, code) }?.takeIf { it.isNotEmpty() }?.let { h.put("sector", it) }
        change(c) { list -> list.firstOrNull { it.optString("id") == fundId }?.let { holdings(it).put(h) } }
        nifty?.let { prefs(c).edit().putFloat("nifty", it.toFloat()).apply() }
        progress = ""
        val spent = qty * price
        val fundName = get(c, fundId)?.optString("name").orEmpty()
        return "Kharida ($fundName): " + quote.name + "  " + qty + " share × " + FundLogic.rupees(price) + " = " + FundLogic.rupees(spent) +
            (if (amount - spent >= 1) "  (" + FundLogic.rupees(amount - spent) + " bacha)" else "")
    }

    /** Faisla diary: apne kaaran ko 90/180/365 din baad jaancho. verdict = right / wrong / unsure. */
    fun review(c: Context, fundId: String, holdingId: String, days: Int, verdict: String) {
        change(c) { all ->
            val f = all.firstOrNull { it.optString("id") == fundId } ?: return@change
            val hs = holdings(f)
            for (i in 0 until hs.length()) {
                val h = hs.optJSONObject(i) ?: continue
                if (h.optString("id") != holdingId) continue
                val cost = FundLogic.cost(h)
                val pct = if (cost > 0) (FundLogic.value(h) - cost) / cost * 100 else 0.0
                val list = h.optJSONArray("reviews") ?: JSONArray().also { h.put("reviews", it) }
                list.put(JSONObject().put("days", days).put("verdict", verdict).put("at", System.currentTimeMillis()).put("pct", pct))
            }
        }
    }

    /** Share becho: abhi ke jaanche hue bhaav par. Munafa ya nuksaan fund mein jud jaata hai. */
    fun sell(c: Context, fundId: String, holdingId: String): String {
        val fund = get(c, fundId) ?: return "Fund nahi mila"
        val list = holdings(fund)
        val h = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optString("id") == holdingId } ?: return "Share nahi mila"
        val (price, _) = checked(h.optString("symbol"), h.optString("slug"), null) ?: return "Bhaav pakka nahi hua. Thodi der baad becho"
        val nifty = Market.yahoo(NIFTY)?.price
        val done = JSONObject(h.toString()).put("sellPrice", price).put("sellAt", System.currentTimeMillis())
        nifty?.let { done.put("niftyAtSell", it) }
        change(c) { all ->
            val f = all.firstOrNull { it.optString("id") == fundId } ?: return@change
            val keep = JSONArray()
            val hs = holdings(f)
            for (i in 0 until hs.length()) hs.optJSONObject(i)?.takeIf { it.optString("id") != holdingId }?.let { keep.put(it) }
            f.put("holdings", keep)
            sold(f).put(done)
        }
        val profit = h.optInt("qty") * (price - h.optDouble("buyPrice", 0.0))
        return "Becha: " + h.optString("name") + " " + FundLogic.rupees(price) + " par. " + (if (profit >= 0) "Munafa " else "Nuksaan ") + FundLogic.rupees(Math.abs(profit))
    }

    /** Saare funds ke shares ka naya bhaav (do jagah se jaancha hua). */
    fun refresh(c: Context): String {
        if (!busy.compareAndSet(false, true)) return "Bhaav pehle se update ho rahe hain"
        try {
            val symbols = all(c).flatMap { f -> (0 until holdings(f).length()).mapNotNull { holdings(f).optJSONObject(it) } }
                .associateBy { it.optString("symbol") }
            if (symbols.isEmpty()) return "Abhi kisi fund mein share nahi hai"
            Market.yahoo(NIFTY)?.price?.let { prefs(c).edit().putFloat("nifty", it.toFloat()).apply() }
            var ok = 0
            val prices = mutableMapOf<String, Pair<Double, Double?>>()
            symbols.values.forEachIndexed { i, h ->
                progress = "Bhaav: " + (i + 1) + " / " + symbols.size
                val q = Market.yahoo(h.optString("symbol"))
                val p = checked(h.optString("symbol"), h.optString("slug"), q?.price) ?: return@forEachIndexed
                prices[h.optString("symbol")] = p.first to q?.prevClose
                ok++
            }
            change(c) { all ->
                all.forEach { f ->
                    val hs = holdings(f)
                    for (i in 0 until hs.length()) {
                        val h = hs.optJSONObject(i) ?: continue
                        val p = prices[h.optString("symbol")] ?: continue
                        h.put("lastPrice", p.first).put("lastAt", System.currentTimeMillis())
                        p.second?.let { h.put("prevClose", it) }
                    }
                }
            }
            prefs(c).edit().putLong("updated", System.currentTimeMillis()).apply()
            return if (ok == symbols.size) "Saare bhaav update ho gaye" else "$ok / " + symbols.size + " bhaav update hue"
        } catch (e: org.json.JSONException) {
            return "Kuch gadbad hui. Dobara try karo"
        } finally {
            progress = ""
            busy.set(false)
        }
    }
}
