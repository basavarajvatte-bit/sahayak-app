package com.sahayak.app

import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/** Warren Buffett jaisi balance sheet jaanch: 8 baatein, har ek ka nateeja, number aur samjhaane wali jaankari. */
object BuffettLogic {
    const val PASS = "pass"
    const val FAIL = "fail"
    const val UNKNOWN = "unknown"
    const val SKIP = "skip"

    /** Yahoo se ye cheezein maangni hain (saalana). */
    val TYPES = listOf(
        "annualTotalDebt", "annualLongTermDebt", "annualStockholdersEquity", "annualCashAndCashEquivalents",
        "annualCashCashEquivalentsAndShortTermInvestments", "annualRetainedEarnings", "annualNetIncome", "annualCapitalExpenditure",
        "annualOperatingCashFlow", "annualPreferredStock", "annualRepurchaseOfCapitalStock", "annualTotalRevenue", "annualOrdinarySharesNumber",
        "annualDilutedEPS", "trailingDilutedEPS"
    )

    // ---------- PEG ----------

    /**
     * PEG ka hisaab. eps = ek share ka saal ka profit (sabse naya), growth = EPS ki saalana ausat badhat % (CAGR).
     * problem khaali na ho to PEG nahi nikla, aur problem batata hai kyun.
     */
    data class Peg(val eps: Double, val growth: Double, val years: Int, val from: String, val to: String, val problem: String)

    /** PEG ke liye EPS aur growth. Bhaav baad mein lagta hai (pegOf), taaki naye bhaav par PEG apne aap badle. */
    fun pegData(d: Map<String, List<Pair<String, Double>>>): Peg {
        val annual = d["annualDilutedEPS"].orEmpty().filter { !it.second.isNaN() }
        // EPS na mile to net profit ÷ kul share
        val series = if (annual.size >= 2) annual else {
            val shares = d["annualOrdinarySharesNumber"]?.toMap().orEmpty()
            d["annualNetIncome"].orEmpty().mapNotNull { (y, p) -> shares[y]?.takeIf { it > 0 }?.let { y to p / it } }
        }
        val trailing = d["trailingDilutedEPS"]?.lastOrNull()?.second?.takeIf { !it.isNaN() && it != 0.0 }
        val eps = trailing ?: series.lastOrNull()?.second ?: Double.NaN
        if (series.size < 2 || eps.isNaN()) return Peg(eps, Double.NaN, 0, "", "", "Data nahi mila")
        if (eps <= 0) return Peg(eps, Double.NaN, 0, "", "", "Company ghaate mein hai, PEG nahi nikalta")
        val use = series.takeLast(PEG_YEARS + 1)
        val first = use.first()
        val last = use.last()
        val gaps = use.size - 1
        if (first.second <= 0) return Peg(eps, Double.NaN, gaps, first.first, last.first, "Shuru ke saal ghaata tha, growth nahi nikalti")
        if (last.second <= 0) return Peg(eps, Double.NaN, gaps, first.first, last.first, "Pichhle saal ghaata tha, PEG nahi nikalta")
        val growth = (Math.pow(last.second / first.second, 1.0 / gaps) - 1) * 100
        if (growth <= 0) return Peg(eps, growth, gaps, first.first, last.first, "Profit badha nahi (" + String.format(Locale.ENGLISH, "%.1f", growth) + "% har saal), PEG kaam nahi karta")
        return Peg(eps, growth, gaps, first.first, last.first, "")
    }

    /** P/E aur PEG. Nahi nikla to NaN. */
    fun pe(eps: Double, price: Double): Double = if (eps > 0 && price > 0) price / eps else Double.NaN

    fun pegOf(eps: Double, growth: Double, price: Double): Double {
        val pe = pe(eps, price)
        return if (pe.isNaN() || growth.isNaN() || growth <= 0) Double.NaN else pe / growth
    }

    /** PEG ka matlab: (shabd, rang). */
    fun pegLook(peg: Double): Pair<String, Int> = when {
        peg.isNaN() -> "PEG nahi nikla" to 0xFF667085.toInt()
        peg < 1.0 -> "Sasta (growth ke hisaab se)" to 0xFF16A34A.toInt()
        peg < 1.5 -> "Theek daam" to 0xFF1F5C4D.toInt()
        peg < 2.0 -> "Thoda mehenga" to 0xFFB54700.toInt()
        else -> "Mehenga" to 0xFFDC2626.toInt()
    }

    /** Tile par chhota shabd. */
    fun pegShort(peg: Double): String = when {
        peg.isNaN() -> ""
        peg < 1.0 -> "Sasta"
        peg < 1.5 -> "Theek"
        peg < 2.0 -> "Thoda mehenga"
        else -> "Mehenga"
    }

    fun num(v: Double, digits: Int = 1): String = String.format(Locale.ENGLISH, "%." + digits + "f", v)

    private const val PEG_YEARS = 4
    const val PEG_HOT_GROWTH = 50.0

    val PEG_INFO = Info(
        "PEG ratio",
        "PEG = P/E ÷ profit ki saalana badhat (%).\n\nP/E = share ka bhaav ÷ ek share ka saal ka profit (EPS). Jaise P/E 30 matlab ₹1 ke saal ke profit ke liye ₹30 de rahe ho.\n\n" +
            "PEG batata hai ki growth dekhte hue ye daam sahi hai ya nahi. Jaise P/E 30 aur profit har saal 30% badhe to PEG 1. P/E 15 par growth sirf 5% ho to PEG 3, yaani sasta dikhne wala share asal mein mehenga.\n\n" +
            "Yahan growth = pichhle saalon mein EPS (ek share ka profit) har saal ausatan kitna badha (CAGR). Ye Yahoo Finance ke saalana data se nikla hai.",
        "PEG 1 se kam: growth ke hisaab se sasta.\nPEG 1 se 1.5: theek daam.\nPEG 1.5 se 2: thoda mehenga. India mein achhi companies aksar yahin milti hain.\nPEG 2 se zyada: mehenga.",
        "Sirf P/E dekhne se tezi se badhne wali achhi company mehengi aur ruki hui company sasti dikhti hai. PEG dono ko ek tarazu par laata hai. Ye idea Peter Lynch ne mashhoor kiya.\n\n" +
            "⚠ Dhyan rakho:\n• Growth pichhle saalon ki hai. Aage bhi utni hogi, ye pakka nahi.\n• Growth bahut zyada (50% se upar) ho to PEG jhootha kam dikh sakta hai, itni tezi zyada din nahi chalti.\n" +
            "• Company ghaate mein ho ya profit na badha ho to PEG nahi nikalta.\n• Bank, NBFC aur cyclical companies (steel, cement, metal) par PEG kam kaam aata hai.\n• PEG kam ho par company kharab ho to wo sasta jaal hai. Saath mein Buffett check bhi dekho.\n\n" +
            "Ye seekhne ke liye hai, salah nahi."
    )

    /** Ek jaanch ka nateeja. */
    data class Check(val id: String, val title: String, val status: String, val value: String)

    /** Har jaanch ki samajh: kya dekhte hain, kab ✓ milta hai, kyun zaroori hai. */
    data class Info(val title: String, val what: String, val pass: String, val why: String)

    val INFO = mapOf(
        "debt" to Info(
            "Cash zyada, karz kam",
            "Company par kitna lamba karz (long-term debt) hai, uske paas kitna cash hai, aur saal mein kitna munafa kamati hai.",
            "Long-term karz itna kam ho ki 4 saal ke net profit se poora chuk jaye, ya company ke paas karz se zyada cash ho.",
            "Bura samay (mandi, corona jaisa) aaye to karz wali company ko byaaj bharna padta hai, warna doob sakti hai. Kam karz wali company tufaan jhel leti hai aur mauka milne par sasti cheezein khareed sakti hai. Buffett kehte hain: karz se hi achhe-achhe log doobte hain."
        ),
        "de" to Info(
            "Debt-to-Equity kam",
            "Karz ÷ shareholders ka paisa (equity). Jaise D/E 0.3 matlab har ₹100 apne paise par sirf ₹30 karz.",
            "D/E 0.5 se kam. Bank aur NBFC ke liye ye jaanch nahi hoti, kyunki logon ka paisa (deposit) lena hi unka dhandha hai.",
            "D/E kam ho to company apne paise se chal rahi hai, udhaar se nahi. Zyada D/E wali company ka munafa byaaj mein chala jaata hai, aur byaaj dar badhne par sabse pehle wahi girti hai."
        ),
        "retained" to Info(
            "Retained earnings lagaataar badhe",
            "Retained earnings = company ne aaj tak jo munafa kamaya aur baanta nahi, wo company mein jama hai.",
            "Har saal pichhle saal se zyada ho (jitne saal ka data mila, sab mein).",
            "Iska badhna dikhata hai ki company har saal sach mein paisa jod rahi hai aur apne hi munafe se badh rahi hai, baar-baar naye share bechkar ya karz lekar nahi. Buffett ise company ki 'bachat' maante hain."
        ),
        "roe" to Info(
            "ROE 15% se zyada, karz se nahi",
            "ROE (Return on Equity) = net profit ÷ shareholders ka paisa. Matlab company aapke har ₹100 par saal mein kitna kamati hai.",
            "Har saal ROE 15% ya zyada ho, aur saath mein D/E 1 se kam ho (taaki ROE karz ke dum par na ho).",
            "Oonchi aur lagaataar ROE matlab dhandha andar se taakatwar hai. Lekin karz lekar bhi ROE badhaya ja sakta hai, jo khatarnaak hai. Isliye Buffett ROE ke saath karz bhi dekhte hain."
        ),
        "capex" to Info(
            "Kam capex",
            "Capex = machinery, factory, plant par kharcha. Ise dhandhe ki kamai (operating cash flow) se milaate hain.",
            "Saalon ka capex, kamai ke aadhe (50%) se kam ho.",
            "Jis company ko chalte rehne ke liye baar-baar bhaari machine aur plant lagane padte hain, uska bahut sa munafa wahi kha jaata hai. Achhi company kam kharche mein zyada kamati hai, aur bacha paisa shareholders ke kaam aata hai."
        ),
        "preferred" to Info(
            "Preferred stock na ho",
            "Preferred stock ek mehngi funding hai: unhe pakka dividend pehle dena padta hai.",
            "Preferred stock na ho, ya equity ke 1% se bhi kam ho.",
            "Strong company ko aisi mehngi funding ki zaroorat nahi padti. Iska hona dikhata hai ki company ko kabhi paise ki tangi thi. India mein ye bahut kam companies mein hota hai."
        ),
        "buyback" to Info(
            "Share buyback",
            "Company apne hi share bazaar se wapas khareedti hai, ya uske kul share ghat jaate hain.",
            "Pichhle saalon mein buyback hua ho, ya kul share ki ginti ghati ho.",
            "Buyback tab hota hai jab company ke paas faltu paisa ho aur management maanta ho ki share sasta hai. Isse bache shareholders ka hissa badhta hai. India mein buyback kam hota hai, isliye ✗ aana bura sign nahi, bas ✓ ek achha sign hai."
        ),
        "moat" to Info(
            "Moat (andaaza)",
            "Moat = aisi taakat jo doosron ko is dhandhe mein aane se roke: brand (Coca-Cola), network, sasta banana, patent. Ise seedha naapa nahi ja sakta, isliye numbers se andaaza lagaya hai.",
            "Har saal net profit margin 10% ya zyada, ROE 15% ya zyada, aur margin har saal lagbhag same (8% se zyada upar-neeche nahi).",
            "Jis company ka munafa saal dar saal oonchi aur sthir rehta hai, uske paas aksar koi moat hota hai, warna competition use kam kar deta. Ye sirf andaaza hai: company ka brand aur dhandha khud bhi samjhna zaroori hai."
        )
    )

    // ---------- Munger ki soch: profit asli hai? bhaav sahi hai? laal jhande ----------

    const val FD_RATE = 7.0

    val MORE_INFO = mapOf(
        "quality" to Info(
            "Profit asli hai ya kaagzi?",
            "Dhandhe se bank mein aaya asli cash (operating cash flow) ko kaagaz par dikhaye net profit se milaate hain. Saalon ka jod ÷ saalon ka jod.",
            "Cash, profit ka kam se kam 80% ho (0.8 ya zyada).",
            "Profit hisaab-kitaab se badhaya ja sakta hai, cash nahi. Agar company saalon tak profit dikhaye par cash na aaye, to ya to grahak paisa nahi de rahe, ya maal godown mein pada hai, ya hisaab mein gadbad hai. Munger ise sabse pehle dekhte the."
        ),
        "yield" to Info(
            "Sasta hai ya mehenga? (owner earnings yield)",
            "Owner earnings = dhandhe ki cash kamai − machinery ka kharcha (capex). Ise poori company ki bazaar keemat (share ka bhaav × kul share) se bhaag dete hain. Matlab: ₹100 ki company khareedo to saal mein kitna asli cash kamati hai.",
            "Ye yield FD (lagbhag 7%) se zyada ho.",
            "Buffett achhi company bhi mehenge bhaav par nahi lete; isse 'margin of safety' kehte hain. Agar company ki kamai FD se bhi kam hai, to aap khatra le rahe ho bina extra fayde ke, jab tak company bahut tezi se na badhe. Tezi se badhne wali company ka yield kam ho sakta hai, isliye ise growth ke saath dekho."
        ),
        "dilution" to Info(
            "Naye share bechna (dilution)",
            "Company ke kul share ki ginti saalon mein kitni badhi.",
            "Kul share 5% se zyada na badhe hon.",
            "Jab company baar-baar naye share bechti hai, to aapka hissa chhota hota jaata hai, bhale aapne kuch na kiya ho. Achhi company apne munafe se badhti hai, shareholders ka hissa kaat kar nahi."
        ),
        "debtRise" to Info(
            "Karz tezi se badhna",
            "Kul karz pehle saal se aakhri saal tak kitna badha.",
            "Karz 50% se zyada na badha ho (ya karz ho hi na).",
            "Karz ka tezi se badhna dikhata hai ki dhandha apne paise se nahi chal pa raha. Bura samay aaye to sabse pehle yahi companies phansti hain."
        ),
        "news" to Info(
            "Khabron mein laal jhande",
            "Pichhle 6 mahine ki khabron mein promoter ka share girvi (pledge), auditor ka istifa, SEBI/ED ki jaanch, default jaise shabd.",
            "Aisi koi khabar na mile.",
            "Munger kehte the: buri khabar ek baar aaye to aksar aur bhi chhupi hoti hai. Promoter share girvi rakhe to wo paise ki tangi mein hai; auditor chhod de to hisaab mein gadbad ka shak. Ye khabron se dhoondha jaata hai, isliye kabhi chhoot bhi sakta hai — khabar khud padh kar dekho."
        )
    )

    /** Profit asli hai: saalon ka operating cash ÷ saalon ka net profit. */
    fun quality(d: Map<String, List<Pair<String, Double>>>): Check {
        val pairs = paired(d, "annualOperatingCashFlow", "annualNetIncome")
        if (pairs.isEmpty()) return Check("quality", "Profit asli hai ya kaagzi?", UNKNOWN, "Data nahi mila")
        val cash = pairs.sumOf { it.second }
        val profit = pairs.sumOf { it.third }
        if (profit <= 0) return Check("quality", "Profit asli hai ya kaagzi?", FAIL, "Saalon ka jod ghaate mein")
        val ratio = cash / profit
        return Check("quality", "Profit asli hai ya kaagzi?", if (ratio >= 0.8) PASS else FAIL,
            "Cash ÷ profit = " + String.format(Locale.ENGLISH, "%.2f", ratio) + " (" + pairs.size + " saal)")
    }

    /** Sasta ya mehenga: owner earnings ÷ bazaar keemat, FD se tulna. */
    fun ownerYield(d: Map<String, List<Pair<String, Double>>>, price: Double): Check {
        val ocf = latest(d, "annualOperatingCashFlow")
        val capex = latest(d, "annualCapitalExpenditure") ?: 0.0
        val shares = latest(d, "annualOrdinarySharesNumber")
        if (ocf == null || shares == null || shares <= 0 || price <= 0) return Check("yield", "Sasta hai ya mehenga?", UNKNOWN, "Data nahi mila")
        val owner = ocf - Math.abs(capex)
        val cap = price * shares
        val y = owner / cap * 100
        return Check("yield", "Sasta hai ya mehenga?", if (y >= FD_RATE) PASS else FAIL,
            "Owner earnings yield " + String.format(Locale.ENGLISH, "%.1f", y) + "% · FD ~" + FD_RATE.toInt() + "% · company ki keemat " + cr(cap))
    }

    /** Data se laal jhande: naye share bechna, karz tezi se badhna. */
    fun dataFlags(d: Map<String, List<Pair<String, Double>>>): List<Check> {
        val out = mutableListOf<Check>()
        val shares = d["annualOrdinarySharesNumber"].orEmpty()
        out.add(if (shares.size < 2) Check("dilution", "Naye share bechna", UNKNOWN, "Data nahi mila")
        else {
            val rise = (shares.last().second / shares.first().second - 1) * 100
            Check("dilution", "Naye share bechna", if (rise <= 5) PASS else FAIL,
                "Kul share " + (if (rise >= 0) "+" else "") + String.format(Locale.ENGLISH, "%.1f", rise) + "% (" + shares.first().first + " se " + shares.last().first + ")")
        })
        val debt = d["annualTotalDebt"].orEmpty()
        out.add(if (debt.size < 2) Check("debtRise", "Karz tezi se badhna", if (debt.isEmpty() && d.containsKey("annualStockholdersEquity")) PASS else UNKNOWN,
            if (debt.isEmpty() && d.containsKey("annualStockholdersEquity")) "Karz nahi mila" else "Data nahi mila")
        else {
            val first = debt.first().second
            val last = debt.last().second
            val ok = last <= 0 || (first > 0 && last <= first * 1.5)
            Check("debtRise", "Karz tezi se badhna", if (ok) PASS else FAIL, "Karz " + cr(first) + " → " + cr(last))
        })
        return out
    }

    private val RED_WORDS = Regex(
        """(?i)\b(pledg\w*|auditor (resign\w*|quits?|exit\w*)|resignation of (the )?auditor|sebi (order|probe|notice|bans?|penalty)|ed (raid|probe|searches)|income tax (raid|search)|default\w*|fraud|forensic audit|insolvency|nclt|siphon\w*|whistleblower)\b"""
    )

    /** Khabar ki heading mein laal jhande wala shabd hai? */
    fun redFlagNews(title: String): Boolean = RED_WORDS.containsMatchIn(title)

    /** Yahoo ka jawab: har type ke liye (saal, value), purane se naye. */
    fun parse(json: String): Map<String, List<Pair<String, Double>>> {
        val out = mutableMapOf<String, List<Pair<String, Double>>>()
        try {
            val results = JSONObject(json).getJSONObject("timeseries").getJSONArray("result")
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val type = r.optJSONObject("meta")?.optJSONArray("type")?.optString(0).orEmpty()
                val rows = r.optJSONArray(type) ?: continue
                val list = sortedMapOf<String, Double>()
                for (j in 0 until rows.length()) {
                    val row = rows.optJSONObject(j) ?: continue
                    val date = if (row.isNull("asOfDate")) "" else row.optString("asOfDate")
                    val raw = row.optJSONObject("reportedValue")?.optDouble("raw", Double.NaN) ?: Double.NaN
                    if (date.length < 4 || raw.isNaN()) continue
                    list[date.take(4)] = raw
                }
                if (list.isNotEmpty()) out[type] = list.map { it.key to it.value }
            }
        } catch (e: JSONException) {
            return emptyMap()
        }
        return out
    }

    private fun cr(v: Double) = MarketLogic.crore(v / 1e7)

    private fun pct(v: Double) = String.format(Locale.ENGLISH, "%.0f%%", v)

    private fun latest(d: Map<String, List<Pair<String, Double>>>, type: String) = d[type]?.lastOrNull()?.second

    /** Saalon ka ek-ek jodi: dono type usi saal mein hon. */
    private fun paired(d: Map<String, List<Pair<String, Double>>>, a: String, b: String): List<Triple<String, Double, Double>> {
        val other = d[b]?.toMap() ?: return emptyList()
        return d[a].orEmpty().mapNotNull { (y, v) -> other[y]?.let { Triple(y, v, it) } }
    }

    /** Saari 8 jaanch. financial = bank/NBFC (karz wali jaanch chhod do). */
    fun evaluate(d: Map<String, List<Pair<String, Double>>>, financial: Boolean): List<Check> {
        val equity = latest(d, "annualStockholdersEquity")
        val totalDebt = latest(d, "annualTotalDebt")
        val longDebt = latest(d, "annualLongTermDebt") ?: totalDebt
        val cash = latest(d, "annualCashCashEquivalentsAndShortTermInvestments") ?: latest(d, "annualCashAndCashEquivalents")
        val profit = latest(d, "annualNetIncome")
        val haveBalance = equity != null
        val out = mutableListOf<Check>()

        // 1. Cash zyada, karz kam
        out.add(when {
            financial -> Check("debt", "Cash zyada, karz kam", SKIP, "Bank/NBFC: ye jaanch nahi hoti")
            longDebt == null && !haveBalance -> Check("debt", "Cash zyada, karz kam", UNKNOWN, "Data nahi mila")
            (longDebt ?: 0.0) <= 0.0 -> Check("debt", "Cash zyada, karz kam", PASS, "Koi lamba karz nahi" + (cash?.let { ", cash " + cr(it) } ?: ""))
            else -> {
                val debt = longDebt ?: 0.0
                val years = if (profit != null && profit > 0) debt / profit else Double.NaN
                val ok = (cash != null && cash >= debt) || (!years.isNaN() && years <= 4.0)
                Check("debt", "Cash zyada, karz kam", if (ok) PASS else FAIL,
                    "Karz " + cr(debt) + (cash?.let { " · cash " + cr(it) } ?: "") +
                        (if (years.isNaN()) " · munafa nahi" else " · " + String.format(Locale.ENGLISH, "%.1f", years) + " saal ke munafe mein chuke"))
            }
        })

        // 2. Debt-to-Equity
        val de = if (equity != null && equity > 0) (totalDebt ?: if (haveBalance) 0.0 else Double.NaN) / equity else Double.NaN
        out.add(when {
            financial -> Check("de", "Debt-to-Equity kam", SKIP, "Bank/NBFC: ye jaanch nahi hoti")
            de.isNaN() -> Check("de", "Debt-to-Equity kam", UNKNOWN, "Data nahi mila")
            else -> Check("de", "Debt-to-Equity kam", if (de < 0.5) PASS else FAIL, "D/E " + String.format(Locale.ENGLISH, "%.2f", de))
        })

        // 3. Retained earnings lagaataar badhe
        val re = d["annualRetainedEarnings"].orEmpty()
        out.add(if (re.size < 2) Check("retained", "Retained earnings lagaataar badhe", UNKNOWN, "Data nahi mila")
        else {
            val rising = re.zipWithNext().all { it.second.second > it.first.second }
            Check("retained", "Retained earnings lagaataar badhe", if (rising) PASS else FAIL,
                re.size.toString() + " saal: " + re.joinToString(" → ") { cr(it.second) })
        })

        // 4. ROE 15%+ har saal, karz se nahi
        val roe = paired(d, "annualNetIncome", "annualStockholdersEquity").filter { it.third > 0 }.map { it.first to it.second / it.third * 100 }
        out.add(if (roe.isEmpty()) Check("roe", "ROE 15% se zyada, karz se nahi", UNKNOWN, "Data nahi mila")
        else {
            val high = roe.all { it.second >= 15.0 }
            val fromDebt = !financial && !de.isNaN() && de >= 1.0
            Check("roe", "ROE 15% se zyada, karz se nahi", if (high && !fromDebt) PASS else FAIL,
                "ROE: " + roe.joinToString(", ") { pct(it.second) } + (if (fromDebt) " · par karz zyada (D/E " + String.format(Locale.ENGLISH, "%.1f", de) + ")" else ""))
        })

        // 5. Kam capex
        val capex = paired(d, "annualCapitalExpenditure", "annualOperatingCashFlow")
        out.add(when {
            financial -> Check("capex", "Kam capex", SKIP, "Bank/NBFC: ye jaanch nahi hoti")
            capex.isEmpty() -> Check("capex", "Kam capex", UNKNOWN, "Data nahi mila")
            else -> {
                val spent = capex.sumOf { Math.abs(it.second) }
                val earned = capex.sumOf { it.third }
                if (earned <= 0) Check("capex", "Kam capex", FAIL, "Dhandhe se cash nahi aaya")
                else {
                    val share = spent / earned * 100
                    Check("capex", "Kam capex", if (share < 50) PASS else FAIL, "Capex kamai ka " + pct(share))
                }
            }
        })

        // 6. Preferred stock
        val pref = latest(d, "annualPreferredStock")
        out.add(when {
            pref == null && haveBalance -> Check("preferred", "Preferred stock na ho", PASS, "Preferred stock nahi mila")
            pref == null -> Check("preferred", "Preferred stock na ho", UNKNOWN, "Data nahi mila")
            pref <= 0.0 || (equity != null && equity > 0 && pref / equity < 0.01) -> Check("preferred", "Preferred stock na ho", PASS, "Na ke barabar")
            else -> Check("preferred", "Preferred stock na ho", FAIL, "Preferred stock " + cr(pref))
        })

        // 7. Buyback
        val repurchase = d["annualRepurchaseOfCapitalStock"].orEmpty().filter { it.second < 0 }
        val sharesCount = d["annualOrdinarySharesNumber"].orEmpty()
        val fewer = sharesCount.size >= 2 && sharesCount.last().second < sharesCount.first().second * 0.99
        out.add(when {
            repurchase.isNotEmpty() -> Check("buyback", "Share buyback", PASS, "Buyback: " + repurchase.joinToString(", ") { it.first + " " + cr(Math.abs(it.second)) })
            fewer -> Check("buyback", "Share buyback", PASS, "Kul share ghate")
            sharesCount.isEmpty() && d["annualRepurchaseOfCapitalStock"] == null && !haveBalance -> Check("buyback", "Share buyback", UNKNOWN, "Data nahi mila")
            else -> Check("buyback", "Share buyback", FAIL, "Buyback nahi mila")
        })

        // 8. Moat (andaaza): oonchi aur sthir margin, oonchi ROE
        val margin = paired(d, "annualNetIncome", "annualTotalRevenue").filter { it.third > 0 }.map { it.second / it.third * 100 }
        out.add(if (margin.size < 2 || roe.size < 2) Check("moat", "Moat (andaaza)", UNKNOWN, "Data kam hai")
        else {
            val steady = (margin.maxOrNull() ?: 0.0) - (margin.minOrNull() ?: 0.0) <= 8.0
            val ok = margin.all { it >= 10.0 } && roe.all { it.second >= 15.0 } && steady
            Check("moat", "Moat (andaaza)", if (ok) PASS else FAIL, "Margin: " + margin.joinToString(", ") { pct(it) } + (if (!steady) " · upar-neeche zyada" else ""))
        })
        return out
    }

    /** Score: (✓ kitne, kul kitni jaanchi gayi). Chhodi hui nahi gini jaati; data na ho wali gini jaati hai. */
    fun score(checks: List<Check>): Pair<Int, Int> = checks.count { it.status == PASS } to checks.count { it.status != SKIP }

    fun years(d: Map<String, List<Pair<String, Double>>>): Int = TYPES.maxOfOrNull { d[it]?.size ?: 0 } ?: 0
}
