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
        "annualOperatingCashFlow", "annualPreferredStock", "annualRepurchaseOfCapitalStock", "annualTotalRevenue", "annualOrdinarySharesNumber"
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
