package com.sahayak.app

import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pattern study ke seedhe niyam: kaunsi khabar kis pattern ki hai, aur lock ke baad share ne kya kiya. */
object PatternLogic {
    /**
     * Ek pattern. must = heading mein ye sab hona chahiye. not = ye ho to chhod do.
     * minPercent = heading mein kam se kam itne % ka badlav (ya big wala shabd) ho.
     */
    class Pattern(
        val id: String, val label: String, val about: String, val query: String,
        val must: List<Regex>, val not: Regex? = null, val minPercent: Int = 0, val big: Regex? = null
    )

    private fun r(text: String) = Regex(text, RegexOption.IGNORE_CASE)

    private val UP = "jumps?|surges?|soars?|zooms?|rises?|climbs?|grows?|doubles?|triples?|multi-?fold|up"
    private val DOWN = "falls?|plunges?|slumps?|declines?|drops?|tanks?|dips?|slips?|halves|down|shrinks?"

    val ALL = listOf(
        Pattern(
            "dividend", "Dividend", "Company announces a dividend or fixes its record date", "dividend per share declares OR announces OR \"record date\"",
            listOf(r("""\bdividends?\b"""), r("""\b(declares?|declared|announces?|announced|recommends?|recommended|approves?|approved|board|record date|fixes|sets|per share|rs|₹)\b""")),
            r("""\b(stocks? to watch|dividend stocks|yield stocks|should you|mutual fund|these stocks|top \d+|\d+ stocks)\b""")
        ),
        Pattern(
            "split", "Stock split", "Company announces a split of its shares", "\"stock split\" OR \"share split\" OR sub-division board approves",
            listOf(r("""\b(stock split|share split|sub-?division|split of (equity )?shares|splits? (its )?(stock|shares))\b""")),
            r("""\b(ex-?split|ex-?date|record date today|stocks? to watch|trade ex|turn ex)\b""")
        ),
        Pattern(
            "bonus", "Bonus shares", "Company announces free bonus shares", "\"bonus issue\" OR \"bonus shares\" board approves",
            listOf(r("""\bbonus (issue|shares?)\b""")),
            r("""\b(ex-?bonus|ex-?date|record date today|stocks? to watch|trade ex|turn ex)\b""")
        ),
        Pattern(
            "buyback", "Buyback", "Company will buy back its own shares", "buyback OR \"buy-back\" board approves shares",
            listOf(r("""\bbuy-?backs?\b"""), r("""\b(approves?|approved|announces?|announced|board|to buy|crore|opens?)\b""")),
            r("""\b(stocks? to watch|should you tender|tax on)\b""")
        ),
        Pattern(
            "fundraise", "Fund raise (QIP, rights)", "Company sells new shares to raise money", "QIP OR \"rights issue\" OR \"preferential issue\" board approves raise",
            listOf(r("""\b(qip|qualified institutional|rights issue|preferential (issue|allotment)|fund ?rais\w*)\b""")),
            r("""\b(stocks? to watch|ipo|startup|start-up)\b""")
        ),
        Pattern(
            "merger", "Merger, acquisition, demerger", "Company buys, joins or splits a business", "merger OR acquisition OR demerger OR \"to acquire\" announces stake",
            listOf(r("""\b(merger|merge with|acquisition|acquires?|to acquire|demerger|amalgamation|takeover)\b""")),
            r("""\b(stocks? to watch|startup|start-up|land parcel)\b""")
        ),
        Pattern(
            "promoterBuy", "Promoter buying", "The owner buys more shares", "promoter buys OR acquires OR increases OR hikes stake shares",
            listOf(r("""\bpromoters?\b"""), r("""\b(buys?|bought|acquires?|acquired|increases?|raises?|hikes?|ups)\b""")),
            r("""\b(sells?|sold|offloads?|pledges?|trims?|cuts?|stocks? to watch)\b""")
        ),
        Pattern(
            "promoterSell", "Promoter selling or pledge", "The owner sells or pledges shares", "promoter sells OR offloads OR pledges OR trims stake shares",
            listOf(r("""\bpromoters?\b"""), r("""\b(sells?|sold|offloads?|pledges?|pledged|trims?|cuts?)\b""")),
            r("""\b(stocks? to watch|releases? pledge|pledge released)\b""")
        ),
        Pattern(
            "bulkDeal", "Bulk or block deal", "A big fund buys or sells a large lot", "\"bulk deal\" OR \"block deal\" shares stake",
            listOf(r("""\b(bulk|block) deals?\b""")),
            r("""\b(stocks? to watch|what is)\b""")
        ),
        Pattern(
            "resultsUp", "Results: big profit jump", "Net profit up 25% or more", "Q1 OR Q2 OR Q3 OR Q4 results net profit jumps OR surges OR soars",
            listOf(r("""\b(net profit|profit|pat)\b"""), r("""\b($UP)\b""")),
            r("""\b($DOWN|loss|misses|stocks? to watch|preview|expected|may|likely)\b"""), 25, r("""\b(doubles?|triples?|multi-?fold)\b""")
        ),
        Pattern(
            "resultsDown", "Results: big profit fall", "Net profit down 25% or more, or a loss", "Q1 OR Q2 OR Q3 OR Q4 results net profit falls OR plunges OR slumps",
            listOf(r("""\b(net profit|profit|pat)\b"""), r("""\b($DOWN)\b""")),
            r("""\b(jumps?|surges?|soars?|zooms?|rises?|climbs?|grows?|stocks? to watch|preview|expected|may|likely)\b"""), 25, r("""\b(halves|swings? to (a )?loss|slips? into (a )?loss)\b""")
        ),
        Pattern(
            "order", "Big order win", "Company wins a large order", "bags OR wins OR secures order worth crore",
            listOf(r("""\b(bags?|wins?|secures?|receives?|gets?|bagged|won)\b"""), r("""\b(orders?|contracts?)\b"""), r("""\b(crore|cr|lakh crore)\b""")),
            r("""\b(stocks? to watch|court|tribunal|nclt|tax)\b""")
        ),
        Pattern(
            "rating", "Credit rating change", "A rating agency upgrades or downgrades the company", "credit rating upgrades OR downgrades CRISIL OR ICRA OR CARE",
            listOf(r("""\b(ratings?|rated)\b"""), r("""\b(upgrad\w*|downgrad\w*|revis\w*|reaffirm\w*)\b"""), r("""\b(crisil|icra|care|india ratings|moody'?s|fitch|s&p)\b""")),
            r("""\b(stocks? to watch|sovereign|india'?s rating|brokerages?)\b""")
        ),
        Pattern(
            "index", "Index entry or exit", "Share is added to or dropped from Nifty, Sensex or MSCI", "Nifty OR Sensex OR MSCI index inclusion OR \"to be included\" OR exclusion rejig",
            listOf(r("""\b(nifty|sensex|msci)\b"""), r("""\b(inclu\w*|added to|to enter|enters?|joins?|exclu\w*|dropped|rejig|reshuffle|replac\w*)\b""")),
            r("""\b(stocks? to watch|ends|closes|opens|points|record high)\b""")
        ),
        Pattern(
            "ceo", "CEO or MD change", "The top boss resigns or a new one is named", "CEO OR \"managing director\" resigns OR appointed OR \"steps down\" company",
            listOf(r("""\b(ceo|md|managing director|chief executive)\b"""), r("""\b(resigns?|resigned|resignation|appoints?|appointed|steps? down|stepped down|quits?|names?|named|elevates?)\b""")),
            r("""\b(stocks? to watch|startup|start-up|says|interview)\b""")
        ),
        Pattern(
            "usfda", "USFDA (pharma)", "US drug regulator approval, warning or inspection result", "USFDA approval OR \"warning letter\" OR \"Form 483\" OR \"import alert\" OR EIR pharma",
            listOf(r("""\b(usfda|us fda|fda)\b"""), r("""\b(approv\w*|nod|warning letter|483|import alert|eir|observations?|clears?|cleared|inspection)\b""")),
            r("""\b(stocks? to watch)\b""")
        )
    )

    fun byId(id: String): Pattern? = ALL.firstOrNull { it.id == id }

    private val PERCENT = Regex("""([0-9]+(?:\.[0-9]+)?)\s?(%|per ?cent|percent)""", RegexOption.IGNORE_CASE)

    fun maxPercent(title: String): Double = PERCENT.findAll(title).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: 0.0

    /** Abhi sirf soch, afwah ya umeed hai, hua nahi: aisi khabar par lock nahi. */
    private val NOT_YET = Regex(
        """\b(to consider|will consider|may (consider|announce|declare|approve|raise|sell|buy|acquire|split)|mulls?|likely|plans? to|planning to|considering|expected to|could|rumou?rs?|reportedly|in talks|eyes|explained|what is|how to)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Kya ye heading is pattern ki hai. Shak ho to nahi: galat lock se behtar hai chhod dena. */
    fun matches(p: Pattern, title: String): Boolean {
        if (NOT_YET.containsMatchIn(title)) return false
        if (!p.must.all { it.containsMatchIn(title) }) return false
        if (p.not?.containsMatchIn(title) == true) return false
        if (p.minPercent > 0 && maxPercent(title) < p.minPercent && p.big?.containsMatchIn(title) != true) return false
        return true
    }

    /** AI ka faisla ek heading par. */
    data class Check(val event: Boolean, val companies: List<String>, val detail: String)

    /** AI ka jawab: {"items":[{"n":1,"event":true,"companies":["X"],"detail":"..."}]} */
    fun parseChecks(text: String): Map<Int, Check> {
        val out = mutableMapOf<Int, Check>()
        try {
            val items = JSONObject(MarketLogic.jsonBlock(text)).optJSONArray("items") ?: return out
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val n = o.optInt("n", -1)
                if (n < 0) continue
                val names = mutableListOf<String>()
                val arr = o.optJSONArray("companies")
                if (arr != null) for (j in 0 until arr.length()) {
                    val name = if (arr.isNull(j)) "" else arr.optString(j).trim()
                    if (name.isNotEmpty() && names.none { it.equals(name, true) }) names.add(name)
                }
                out[n] = Check(o.optBoolean("event", false), names.take(MAX_COMPANIES), if (o.isNull("detail")) "" else o.optString("detail").trim().take(60))
            }
        } catch (e: JSONException) {
            return out
        }
        return out
    }

    private const val MAX_COMPANIES = 4

    /** Kya dhoondha hua share wahi company hai jo khabar mein thi: naam ke saare shabd milein, ya NSE code hi wahi ho. */
    fun sameCompany(asked: String, foundName: String, foundSymbol: String): Boolean {
        val code = foundSymbol.substringBefore('.')
        if (asked.replace(Regex("[^A-Za-z0-9&]"), "").equals(code, true)) return true
        val want = BrainLogic.tokens(MarketLogic.companyQuery(asked)).filter { it.length > 1 }
        if (want.isEmpty()) return false
        val have = BrainLogic.tokens(MarketLogic.companyQuery(foundName)).toSet()
        return want.all { it in have }
    }

    // ---------- Lock ke baad ka hisaab ----------

    private val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { timeZone = IST }.format(Date(ms))

    /**
     * Roz ke band bhaav. adj = split, bonus aur dividend ke hisaab se sudhara hua (tulna ke liye),
     * raw = us din ka asli band bhaav (dikhane ke liye).
     */
    class Daily(val days: List<String>, val adj: DoubleArray, val raw: DoubleArray)

    fun parseDaily(json: String): Daily? {
        try {
            val r = JSONObject(json).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val stamps = r.optJSONArray("timestamp") ?: return null
            val ind = r.optJSONObject("indicators") ?: return null
            val close = ind.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close") ?: return null
            val adjClose = ind.optJSONArray("adjclose")?.optJSONObject(0)?.optJSONArray("adjclose")
            val days = mutableListOf<String>()
            val adj = mutableListOf<Double>()
            val raw = mutableListOf<Double>()
            for (i in 0 until minOf(stamps.length(), close.length())) {
                val c = close.optDouble(i, Double.NaN)
                if (c.isNaN() || c <= 0.0) continue
                val a = adjClose?.optDouble(i, Double.NaN) ?: Double.NaN
                val d = day(stamps.optLong(i) * 1000L)
                if (days.isNotEmpty() && days.last() == d) {
                    // Ek hi din do baar aaye (aaj ka chalta bhaav): aakhri wala rakho
                    adj[adj.lastIndex] = if (a.isNaN() || a <= 0.0) c else a
                    raw[raw.lastIndex] = c
                    continue
                }
                days.add(d)
                adj.add(if (a.isNaN() || a <= 0.0) c else a)
                raw.add(c)
            }
            if (days.isEmpty()) return null
            return Daily(days, adj.toDoubleArray(), raw.toDoubleArray())
        } catch (e: JSONException) {
            return null
        }
    }

    /** Khabar purani ho to us din ka band bhaav aadhaar: us din ya usse pehle ka aakhri bazaar ka din. */
    fun baseOn(daily: Daily, eventMs: Long): Base? {
        val want = day(eventMs)
        val i = daily.days.indexOfLast { it <= want }
        if (i < 0) return null
        return Base(daily.days[i], daily.raw[i], false)
    }

    /** Chipkayi hui list ki ek line: company, uska detail (jaise ₹7.00), aur tarikh. */
    data class Row(val company: String, val detail: String)

    private val MONEY_LINE = Regex("""^\s*(₹|rs\.?|inr)\s*[0-9]""", RegexOption.IGNORE_CASE)
    private val DATE_LINE = Regex("""^\s*[0-9]{1,2}[\s\-/](jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?(\s*[0-9]{2,4})?\s*$""", RegexOption.IGNORE_CASE)
    private val NUMBER_LINE = Regex("""^[\s0-9.,:%₹/-]*$""")
    private val HEADER = setOf("company", "companies", "dividend/share", "dividend", "record date", "ex-date", "ex date", "amount", "date", "name", "stock", "ratio", "details")

    /**
     * Chipkayi hui list padho. Har line par ek cheez ho (jaise aapki list) ya ek hi line mein tab ya | se alag.
     * Company wali line ke baad aane wale ₹ aur tarikh us company ka detail bante hain.
     */
    fun parseList(text: String): List<Row> {
        val out = mutableListOf<Row>()
        var name = ""
        val detail = mutableListOf<String>()
        fun flush() {
            if (name.isNotEmpty() && out.none { it.company.equals(name, true) }) out.add(Row(name, detail.joinToString(" · ")))
            name = ""
            detail.clear()
        }
        text.lines().flatMap { line -> line.split('\t', '|').map { it.trim() } }.filter { it.isNotEmpty() }.forEach { part ->
            val lower = part.lowercase(Locale.ENGLISH)
            when {
                lower in HEADER -> Unit
                MONEY_LINE.containsMatchIn(part) -> if (name.isNotEmpty()) detail.add(part)
                DATE_LINE.matches(part) -> if (name.isNotEmpty()) detail.add("record " + part)
                NUMBER_LINE.matches(part) -> Unit
                part.length > 60 -> Unit
                else -> {
                    flush()
                    name = part.trimEnd('.', ',', ':')
                }
            }
        }
        flush()
        return out.take(MAX_PASTE)
    }

    private const val MAX_PASTE = 60

    // ---------- Aane wale dividend ki list (website se) ----------

    /** Ek aane wala dividend: company, ₹ per share (0 = pata nahi), record date "16 Nov 2026". */
    data class Div(val company: String, val amount: Double, val record: String)

    private val SCRIPT = Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>")
    private val ANY_TAG = Regex("(?s)<[^>]+>")
    private val FULL_DATE = Regex("""^\s*([0-9]{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]* ([0-9]{4})\s*$""", RegexOption.IGNORE_CASE)
    private val PER_SHARE = Regex("""dividend\s*\(\s*(?:rs\.?|₹)?\s*([0-9]+(?:\.[0-9]+)?)\s*/-\s*per share\s*\)""", RegexOption.IGNORE_CASE)
    private val NOT_NAME = Regex("""(?i)^(dividend|type|company name|ex-?date|record date|interim|final|special|page \d|next|prev|←|→)""")

    /** Web page ko lines mein todo (har tag ek nayi line). Dohrayi lines bhi rakho, table ke liye zaroori. */
    fun pageLines(html: String): List<String> =
        ANY_TAG.replace(SCRIPT.replace(html, " "), "\n")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }

    /**
     * Upcoming dividend table padho: "Indag Rubber Ltd | 16 Nov 2026 | 16 Nov 2026 | Dividend (0.9/- per share)".
     * Har "Dividend (x/- per share)" se peeche jaakar do tarikh aur company ka naam dhoondho.
     */
    fun parseDividendPage(html: String): List<Div> {
        val lines = pageLines(html)
        val out = mutableListOf<Div>()
        lines.forEachIndexed { i, line ->
            val amount = PER_SHARE.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@forEachIndexed
            val dates = mutableListOf<String>()
            var name = ""
            var j = i - 1
            while (j >= 0 && j >= i - BACK) {
                val l = lines[j]
                when {
                    FULL_DATE.matches(l) -> dates.add(0, l.trim())
                    PER_SHARE.containsMatchIn(l) -> j = -1
                    dates.isNotEmpty() && !NOT_NAME.containsMatchIn(l) && l.any { it.isLetter() } && l.length in 3..90 -> {
                        name = l
                        j = -1
                    }
                }
                j--
            }
            if (name.isEmpty() || dates.isEmpty()) return@forEachIndexed
            // Do tarikh ho to doosri record date hai (pehli ex-date)
            val record = dates.last()
            if (out.none { it.company.equals(name, true) && it.record == record }) out.add(Div(name, amount, record))
        }
        return out
    }

    private const val BACK = 6

    /** "16 Nov 2026" ko ms mein (IST din ki shuruaat). */
    fun dateMs(text: String): Long = try {
        SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).apply { timeZone = IST }.parse(text.trim())?.time ?: 0L
    } catch (e: java.text.ParseException) {
        0L
    }

    /** Trust, InvIT, REIT company ke share nahi: chhod do. */
    fun isTrust(name: String): Boolean = Regex("(?i)\\b(trust|invit|reit|fund)\\b").containsMatchIn(name)

    /** AI ka jawab: {"items":[{"company":"X","amount":1.5,"record_date":"16 Nov 2026"}]} */
    fun parseAiDividends(text: String): List<Div> {
        val out = mutableListOf<Div>()
        try {
            val items = JSONObject(MarketLogic.jsonBlock(text)).optJSONArray("items") ?: return out
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val name = if (o.isNull("company")) "" else o.optString("company").trim()
                val date = if (o.isNull("record_date")) "" else o.optString("record_date").trim()
                if (name.isEmpty() || dateMs(date) == 0L) continue
                out.add(Div(name, o.optDouble("amount", 0.0).takeIf { !it.isNaN() } ?: 0.0, date))
            }
        } catch (e: JSONException) {
            return out
        }
        return out
    }

    /** Lock ke samay ka aadhaar: kaunsa poora hua din, uska band bhaav, aur kya bazaar us waqt khula tha. */
    data class Base(val date: String, val close: Double, val intraday: Boolean)

    /**
     * Bazaar khula ho aur aaj ka din list mein ho to aadhaar kal ka din (aaj abhi poora nahi hua).
     * Warna list ka aakhri din hi aadhaar hai.
     */
    fun baseAt(daily: Daily, now: Long, open: Boolean): Base? {
        val last = daily.days.lastIndex
        val live = open && daily.days[last] == day(now)
        val i = if (live) last - 1 else last
        if (i < 0) return null
        return Base(daily.days[i], daily.raw[i], live)
    }

    /** Lock se ab tak aur 1 din, 1 hafta, 1 mahina, 3 mahine baad ka badlav (%). adjusted = beech mein split/bonus/dividend hua. */
    data class Track(val now: Double, val steps: List<Double?>, val adjusted: Boolean, val price: Double)

    val STEPS = listOf(1, 5, 21, 63)
    val STEP_NAMES = listOf("1 day", "1 week", "1 month", "3 months")

    /**
     * Lock ka bhaav aaj ke sudhare hue bhaav ke paimaane par lao (split ke baad 1000 ka lock 500 gina jaaye),
     * phir usse aage ke dinon ka badlav nikalo.
     */
    fun track(daily: Daily, base: Base, lockPrice: Double): Track? {
        var i = daily.days.indexOf(base.date)
        if (i < 0) i = daily.days.indexOfLast { it <= base.date }
        if (i < 0 || base.close <= 0.0 || lockPrice <= 0.0) return null
        val factor = daily.adj[i] / base.close
        val lock = lockPrice * factor
        val start = if (base.intraday) i + 1 else i
        val steps = STEPS.map { k -> daily.adj.getOrNull(start + k)?.let { MarketLogic.pct(it, lock) } }
        return Track(MarketLogic.pct(daily.adj.last(), lock), steps, Math.abs(factor - 1.0) > 0.02, daily.raw.last())
    }

    /** Ek pattern ka nateeja ek samay par: kitne shares, ausat badlav, Nifty ka ausat, kitne % upar gaye, kitne % ne Nifty ko haraya. */
    data class Score(val count: Int, val average: Double, val nifty: Double, val upPercent: Int, val beatPercent: Int)

    /** pairs = (share ka badlav %, Nifty ka badlav %). */
    fun score(pairs: List<Pair<Double, Double>>): Score? {
        if (pairs.isEmpty()) return null
        val n = pairs.size
        return Score(
            n, pairs.sumOf { it.first } / n, pairs.sumOf { it.second } / n,
            Math.round(pairs.count { it.first > 0.0 } * 100.0 / n).toInt(), Math.round(pairs.count { it.first > it.second } * 100.0 / n).toInt()
        )
    }

    fun signed(pct: Double): String = (if (pct > 0.004) "▲ " else if (pct < -0.004) "▼ " else "") + String.format(Locale.ENGLISH, "%.2f", Math.abs(pct)) + "%"
}
