package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternLogicTest {
    private fun p(id: String) = PatternLogic.byId(id)!!

    @Test fun matchesRealAnnouncementsOnly() {
        assertTrue(PatternLogic.matches(p("dividend"), "Coal India declares interim dividend of Rs 5.50 per share"))
        assertFalse(PatternLogic.matches(p("dividend"), "5 stocks to trade ex-dividend this week"))
        assertFalse(PatternLogic.matches(p("dividend"), "Board of ITC to consider dividend on Nov 20"))
        assertTrue(PatternLogic.matches(p("split"), "Timex Group board approves 1:5 stock split"))
        assertFalse(PatternLogic.matches(p("split"), "What is a stock split? Explained"))
        assertTrue(PatternLogic.matches(p("bonus"), "Wipro board approves 1:1 bonus issue"))
        assertTrue(PatternLogic.matches(p("buyback"), "Infosys board approves Rs 18,000 crore buyback"))
        assertTrue(PatternLogic.matches(p("promoterBuy"), "Promoter buys 2 lakh shares of Jindal Saw"))
        assertFalse(PatternLogic.matches(p("promoterBuy"), "Promoter sells 3% stake in Adani Wilmar"))
        assertTrue(PatternLogic.matches(p("promoterSell"), "Promoter sells 3% stake in Adani Wilmar"))
        assertTrue(PatternLogic.matches(p("order"), "L&T bags Rs 5,000 crore order from Railways"))
        assertFalse(PatternLogic.matches(p("order"), "L&T wins court order in tax case worth Rs 500 crore"))
        assertTrue(PatternLogic.matches(p("usfda"), "Lupin gets USFDA nod for generic diabetes drug"))
        assertTrue(PatternLogic.matches(p("ceo"), "Infosys CEO resigns, shares slip"))
        assertTrue(PatternLogic.matches(p("rating"), "CRISIL upgrades Tata Power rating to AA+"))
        assertEquals(16, PatternLogic.ALL.size)
    }

    @Test fun resultsNeedABigMove() {
        assertTrue(PatternLogic.matches(p("resultsUp"), "Confidence Petroleum Q2 net profit rises 237% YoY to Rs 686 crore"))
        assertFalse(PatternLogic.matches(p("resultsUp"), "Sammaan Capital Q2 net profit rises 1.25% to Rs 312 crore"))
        assertTrue(PatternLogic.matches(p("resultsUp"), "ABC Ltd Q3 profit doubles on strong demand"))
        assertTrue(PatternLogic.matches(p("resultsDown"), "Krishna Hotels Q2 net profit falls 40% on margin pressure"))
        assertFalse(PatternLogic.matches(p("resultsDown"), "Patanjali Foods Q2 net profit falls 18.8% YoY"))
        // Ek hi heading mein badha aur ghata dono: saaf nahi, chhod do
        assertFalse(PatternLogic.matches(p("resultsUp"), "XYZ Q2 net profit rises 30% but revenue falls 5%"))
        assertEquals(237.0, PatternLogic.maxPercent("profit up 12%, revenue up 237 per cent"), 0.001)
    }

    @Test fun readsAiChecksAndComparesCompanies() {
        val checks = PatternLogic.parseChecks("""{"items":[{"n":1,"event":true,"companies":["Coal India","coal india",null],"detail":"Rs 5.50 per share"},
            {"n":2,"event":false,"companies":[],"detail":null},{"event":true}]}""")
        assertEquals(2, checks.size)
        assertEquals(PatternLogic.Check(true, listOf("Coal India"), "Rs 5.50 per share"), checks[1])
        assertFalse(checks[2]!!.event)
        assertTrue(PatternLogic.parseChecks("sorry").isEmpty())
        assertTrue(PatternLogic.sameCompany("Sun Pharmaceutical", "Sun Pharmaceutical Industries Limited", "SUNPHARMA.NS"))
        assertTrue(PatternLogic.sameCompany("BEL", "Bharat Electronics Limited", "BEL.NS"))
        assertFalse(PatternLogic.sameCompany("Spectrum Brands", "Spectrum Electrical Industries Limited", "SPECTRUM.NS"))
        assertFalse(PatternLogic.sameCompany("", "Anything Ltd", "ANY.NS"))
    }

    private fun chart(stamps: String, close: String, adj: String?) = """{"chart":{"result":[{"meta":{},"timestamp":[$stamps],
        "indicators":{"quote":[{"close":[$close]}]""" + (if (adj == null) "" else ""","adjclose":[{"adjclose":[$adj]}]""") + """}}]}}"""

    // 10, 11, 12, 13, 16 Nov 2026 (IST din ke 9:15 baje)
    private val days = "1794282300,1794368700,1794455100,1794541500,1794800700"

    @Test fun tracksFromTheLockPrice() {
        val d = PatternLogic.parseDaily(chart(days, "100,null,104,110,121", null))!!
        assertEquals(listOf("2026-11-10", "2026-11-12", "2026-11-13", "2026-11-16"), d.days)
        // 13 Nov ko bazaar band hone ke baad lock, 110 par
        val base = PatternLogic.baseAt(d.let { PatternLogic.Daily(it.days.take(3), it.adj.copyOf(3), it.raw.copyOf(3)) }, 0L, false)!!
        assertEquals(PatternLogic.Base("2026-11-13", 110.0, false), base)
        val t = PatternLogic.track(d, base, 110.0)!!
        assertEquals(10.0, t.now, 0.001)
        assertEquals(10.0, t.steps[0]!!, 0.001)
        assertNull(t.steps[1])
        assertFalse(t.adjusted)
        assertEquals(121.0, t.price, 0.001)
    }

    @Test fun splitDoesNotLookLikeAFall() {
        // Lock 13 Nov ko 1000 par. Baad mein 1:2 split: purane bhaav aadhe gine jaate hain, naya bhaav 520
        val after = PatternLogic.parseDaily(chart(days, "480,490,495,500,520", "480,490,495,500,520"))!!
        val t = PatternLogic.track(after, PatternLogic.Base("2026-11-13", 1000.0, false), 1000.0)!!
        assertEquals(4.0, t.now, 0.001)
        assertTrue(t.adjusted)
        assertEquals(520.0, t.price, 0.001)
    }

    @Test fun lockDuringMarketHoursUsesYesterdayAsBase() {
        val d = PatternLogic.parseDaily(chart(days, "100,102,104,110,112", null))!!
        // 16 Nov ko bazaar khula hai aur aaj ka din list mein hai
        val now = 1794800700000L + 3_600_000L
        val base = PatternLogic.baseAt(d, now, true)!!
        assertEquals(PatternLogic.Base("2026-11-13", 110.0, true), base)
        // Din mein 111 par lock, din 112 par band: ab tak ▲ 0.9%, aur 1 din baad ka abhi nahi
        val t = PatternLogic.track(d, base, 111.0)!!
        assertEquals((112.0 - 111.0) / 111.0 * 100, t.now, 0.001)
        assertNull(t.steps[0])
        // Chhutti ke din (aaj ka din list mein nahi): aakhri poora din hi aadhaar
        assertFalse(PatternLogic.baseAt(d, now + 86_400_000L, true)!!.intraday)
    }

    @Test fun scoresAPattern() {
        val s = PatternLogic.score(listOf(4.0 to 1.0, -2.0 to 1.0, 1.0 to 2.0, 5.0 to 0.0))!!
        assertEquals(4, s.count)
        assertEquals(2.0, s.average, 0.001)
        assertEquals(1.0, s.nifty, 0.001)
        assertEquals(75, s.upPercent)
        assertEquals(50, s.beatPercent)
        assertNull(PatternLogic.score(emptyList()))
        assertEquals("▲ 2.00%", PatternLogic.signed(2.0))
        assertEquals("▼ 0.50%", PatternLogic.signed(-0.5))
        assertNotNull(PatternLogic.byId("usfda"))
    }
}
