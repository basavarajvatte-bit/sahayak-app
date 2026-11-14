package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MarketLogicTest {
    private val chart = """{"chart":{"result":[{"meta":{"currency":"INR","symbol":"RELIANCE.NS","regularMarketPrice":1260.5,
        "regularMarketTime":1794571200,"longName":"Reliance Industries Limited","chartPreviousClose":1200.0},
        "timestamp":[1,2,3],"indicators":{"quote":[{"close":[1240.0,null,1250.0,1260.5]}]}}],"error":null}}"""

    @Test fun readsPriceAndPreviousClose() {
        val q = MarketLogic.parseQuote(chart)!!
        assertEquals("RELIANCE.NS", q.symbol)
        assertEquals("Reliance Industries Limited", q.name)
        assertEquals(1260.5, q.price, 0.001)
        assertEquals(1250.0, q.prevClose!!, 0.001)
        assertEquals(1794571200000L, q.timeMs)
    }

    @Test fun badChartGivesNothing() {
        assertNull(MarketLogic.parseQuote("""{"chart":{"result":null,"error":{"code":"Not Found"}}}"""))
        assertNull(MarketLogic.parseQuote("<html>blocked</html>"))
    }

    @Test fun searchPrefersNse() {
        val json = """{"quotes":[{"symbol":"TTM","shortname":"Tata Motors ADR","quoteType":"EQUITY"},
            {"symbol":"TATAMOTORS.BO","longname":"Tata Motors Limited","quoteType":"EQUITY"},
            {"symbol":"TATAMOTORS.NS","longname":"Tata Motors Limited","quoteType":"EQUITY"},
            {"symbol":"X","quoteType":"OPTION"}]}"""
        val found = MarketLogic.parseSearch(json)
        assertEquals(3, found.size)
        assertEquals("TATAMOTORS.NS", MarketLogic.best(found)!!.symbol)
        assertTrue(MarketLogic.parseSearch("oops").isEmpty())
    }

    @Test fun guessesCodesFromTypedName() {
        assertEquals(listOf("TATAMOTORS.NS", "TATAMOTORS.BO"), MarketLogic.guessSymbols("tata motors"))
        assertEquals(listOf("^NSEI"), MarketLogic.guessSymbols("^nsei"))
        assertEquals("RELIANCE:NSE", MarketLogic.googleCode("RELIANCE.NS"))
        assertEquals(1234.5, MarketLogic.parseGooglePrice("""<div data-last-price="1234.5" data-x="1">""")!!, 0.001)
    }

    @Test fun showsMoneyAndChange() {
        assertEquals("₹1,260.50", MarketLogic.money(1260.5, "INR"))
        assertEquals("▲ 5.00%", MarketLogic.pctText(105.0, 100.0))
        assertEquals("▼ 2.50%", MarketLogic.pctText(97.5, 100.0))
        assertEquals("0.00%", MarketLogic.pctText(100.0, 100.0))
    }

    @Test fun tagsNewsThatMovesShares() {
        assertEquals(MarketLogic.Tag("Results", "Bad"), MarketLogic.classify("Tata Motors Q2 net profit falls 20%"))
        assertEquals(MarketLogic.Tag("Trade war", "Bad"), MarketLogic.classify("Trump threatens 50% tariff on India"))
        assertEquals(MarketLogic.Tag("Deal", "Good"), MarketLogic.classify("L&T bags Rs 5,000 crore order from Railways"))
        assertEquals("Management", MarketLogic.classify("Infosys CEO resigns, shares slip")!!.kind)
        assertNotNull(MarketLogic.classify("RBI keeps repo rate unchanged"))
        assertNull(MarketLogic.classify("Heavy rain expected in Mumbai this weekend"))
    }

    @Test fun sameStoryFromTwoPapersIsOne() {
        val a = MarketLogic.key("Tata Motors Q2 net profit falls 20% on weak JLR sales - Mint")
        val b = MarketLogic.key("Tata Motors Q2 profit falls 20% as JLR sales weak - Moneycontrol")
        val c = MarketLogic.key("Infosys wins large deal from European bank - Mint")
        assertTrue(MarketLogic.similar(a, b))
        assertFalse(MarketLogic.similar(a, c))
        assertEquals("Mint", MarketLogic.publisher("Some news - Mint"))
        assertEquals("Some news", MarketLogic.cleanTitle("Some news - Mint"))
    }

    @Test fun cleansCompanyNameAndTimes() {
        assertEquals("Tata Motors", MarketLogic.companyQuery("Tata Motors Limited"))
        val zone = TimeZone.getTimeZone("Asia/Kolkata")
        val now = MarketLogic.pubTime("Fri, 13 Nov 2026 12:00:00 GMT")
        assertTrue(now > 0)
        // 5:30 PM India time: 4 PM nikal gaya, to kal 4 PM
        val next = MarketLogic.nextReportAt(now, 16, 0, zone)
        assertEquals("2026-11-14", MarketLogic.dayKey(next, zone))
        assertEquals(now + 22 * 3_600_000L + 30 * 60_000L, next)
        assertEquals("4:00 PM", MarketLogic.clock(16, 0))
        assertEquals("12:05 AM", MarketLogic.clock(0, 5))
    }

    @Test fun readsDepthAnswerAndShowsTheJump() {
        val answer = """Here is the analysis:
```json
{"company":"ABC Ltd","level":9,"what":"Profit bahut badha.","numbers":[{"what":"Net profit","before":25,"after":300,"unit":"₹ crore","period":"Q2 vs last year"},
{"what":"bad","before":"n/a","after":3}],"past":["12 Aug 2026: order mila", ""],"why":"Kamai badhi.","unsure":""}
```"""
        val d = MarketLogic.parseDepth(answer)!!
        assertEquals("ABC Ltd", d.company)
        assertEquals(5, d.level)
        assertEquals(1, d.numbers.size)
        assertEquals(1, d.past.size)
        val n = d.numbers[0]
        assertEquals("₹25 crore", MarketLogic.numText(n.before, n.unit))
        assertEquals("₹300 crore", MarketLogic.numText(n.after, n.unit))
        assertEquals("12.0 times bigger  (▲ 1,100%)" to 1, MarketLogic.jumpText(n.before, n.after))
        assertNull(MarketLogic.parseDepth("Sorry, no idea"))
        val deep = MarketLogic.parseDepth("""{"company":"Axel Polymers","level":3,"what":"x","trend":[{"what":"Revenue","unit":"₹ crore",
            "points":[{"period":"Q2 FY25","value":40},{"period":"Q2 FY26","value":50},{"period":"","value":1},{"period":"Q2 FY27","value":75}]},
            {"what":"One point","points":[{"period":"FY26","value":3}]}],"reasons":["Naya order mila", " "]}""")!!
        assertEquals(1, deep.trends.size)
        assertEquals(listOf("Q2 FY25" to 40.0, "Q2 FY26" to 50.0, "Q2 FY27" to 75.0), deep.trends[0].points)
        assertEquals(listOf("Naya order mila"), deep.reasons)
    }

    @Test fun describesOtherChanges() {
        assertEquals("▲ 4.0% more" to 1, MarketLogic.jumpText(100.0, 104.0))
        assertEquals("▼ 20.0% less" to -1, MarketLogic.jumpText(100.0, 80.0))
        assertEquals("Loss turned into profit" to 1, MarketLogic.jumpText(-50.0, 20.0))
        assertEquals("Profit turned into loss" to -1, MarketLogic.jumpText(50.0, -20.0))
        assertEquals("Loss became bigger" to -1, MarketLogic.jumpText(-10.0, -40.0))
        assertEquals("1,234.5 MW", MarketLogic.numText(1234.5, "MW"))
        assertEquals("12%", MarketLogic.numText(12.0, "%"))
    }

    @Test fun findsCompanyAndHeadlineFigures() {
        assertEquals("Tata Motors", MarketLogic.companyGuess("Tata Motors Q2 net profit falls 20% - Mint"))
        assertEquals("L&T", MarketLogic.companyGuess("L&T bags Rs 5,000 crore order"))
        assertEquals("", MarketLogic.companyGuess("Sensex falls 500 points"))
        assertEquals("\"Tata Motors\"", MarketLogic.relatedQuery("Tata Motors Q2 net profit falls 20%"))
        val axel = "Axel Polymers Q2FY27 Results: Revenue up 50% YoY, faces ₹86 cr GST demand - scanx.trade"
        assertEquals("Axel Polymers", MarketLogic.companyGuess(axel))
        assertTrue(MarketLogic.aboutCompany("Axel Polymers Ltd Q2 FY24 net profit at Rs. 49.95 lakhs - EquityBulls", "Axel Polymers"))
        assertFalse(MarketLogic.aboutCompany("Krishna GVK Hotels Q2 net profit falls to ₹26.6 crore - scanx.trade", "Axel Polymers"))
        assertEquals("/stocks/axel-polymers-AXEL", MarketLogic.tickertapeSlug("""{"data":{"stocks":[{"sid":"AXEL","slug":"\/stocks\/axel-polymers-AXEL"}]}}"""))
        assertEquals("/stocks/reliance-industries-RELI", MarketLogic.tickertapeSlug("""{"slug": "/stocks/reliance-industries-RELI"}"""))
        assertNull(MarketLogic.tickertapeSlug("""{"data":{"stocks":[]}}"""))
        val a = MarketLogic.headlineJump("ABC net profit jumps to Rs 300 crore from Rs 25 crore")!!
        assertEquals(25.0, a.before, 0.001)
        assertEquals(300.0, a.after, 0.001)
        val b = MarketLogic.headlineJump("XYZ order book grows from Rs 1.5 lakh crore to Rs 2 lakh crore")!!
        assertEquals(150000.0, b.before, 0.001)
        assertEquals(200000.0, b.after, 0.001)
        assertNull(MarketLogic.headlineJump("ABC wins Rs 300 crore order"))
    }

    @Test fun readsScreenerAndScorecard() {
        val screen = """{"success":true,"data":{"results":[
            {"sid":"RELI","stock":{"info":{"name":"Reliance Industries Ltd","ticker":"RELIANCE"},"slug":"/stocks/reliance-industries-RELI"}},
            {"sid":"TCS","stock":{"info":{"name":null,"ticker":"TCS"}}},{"stock":{}}]}}"""
        val stocks = MarketLogic.parseScreener(screen)
        assertEquals(2, stocks.size)
        assertEquals(MarketLogic.Stock("RELI", "Reliance Industries Ltd", "RELIANCE", "/stocks/reliance-industries-RELI"), stocks[0])
        assertEquals(MarketLogic.Stock("TCS", "TCS", "TCS", ""), stocks[1])
        assertTrue(MarketLogic.parseScreener("blocked").isEmpty())

        // Screenshot wala Torrent Power: 3 hari, 6 mein se
        val torrent = """{"success":true,"data":[
            {"name":"Performance","tag":"Avg","colour":"yellow","locked":true},{"name":"Valuation","tag":"High","colour":"red","locked":true},
            {"name":"Growth","tag":"Low","colour":"red","locked":true},{"name":"Profitability","tag":"High","colour":"green","locked":true},
            {"name":"Entry point","tag":"Good","colour":"green"},{"name":"Red flags","tag":"Low","colour":"green"}]}"""
        assertEquals(3 to 6, MarketLogic.parseScorecard(torrent))
        // Rang na likha ho to tag se: sab hara
        val green = """{"data":[{"name":"Performance","tag":"High"},{"name":"Valuation","tag":"Low"},{"name":"Growth","tag":"High"},
            {"name":"Profitability","tag":"High"},{"name":"Entry point","tag":"Good"},{"name":"Red flags","tag":"Low"},{"name":"Empty","tag":null}]}"""
        assertEquals(6 to 6, MarketLogic.parseScorecard(green))
        assertFalse(MarketLogic.isGreen("Valuation", "High", ""))
        assertFalse(MarketLogic.isGreen("Red flags", "High", "#ff0000"))
        assertNull(MarketLogic.parseScorecard("<html>"))
        assertEquals("/stocks/b-TCS", MarketLogic.tickertapeSlug("""{"a":[{"slug":"/stocks/a-AAA"},{"slug":"/stocks/b-TCS"}]}""", "TCS"))
    }

    @Test fun sectorNewsHasNoSingleCompanyButListsStocks() {
        assertEquals("", MarketLogic.companyGuess("Infra, power & defence stocks bag Rs 4,000 crore orders: What it means for investors - The Financial Express"))
        assertEquals("Axel Polymers", MarketLogic.companyGuess("Axel Polymers Q2FY27 Results: Revenue up 50% YoY"))
        val d = MarketLogic.parseDepth("""{"company":"","level":3,"what":"Orders mile.","stocks":["Larsen & Toubro","BEL","bel",null,""]}""")!!
        assertEquals(listOf("Larsen & Toubro", "BEL"), d.stocks)
    }

    @Test fun opensOnlyTheRightShareAndSkipsForeignNews() {
        val search = """{"data":{"stocks":[{"sid":"SPEC","ticker":"SPECTRUM","name":"Spectrum Electrical Industries Ltd","slug":"/stocks/spectrum-electrical-industries-SPEC"},
            {"sid":"CHPE","ticker":"CHENNPETRO","name":"Chennai Petroleum Corporation Ltd","slug":"/stocks/chennai-petroleum-corporation-CHPE"}]}}"""
        // Spectrum Brands (America) ko Spectrum Electrical se nahi milana
        assertNull(MarketLogic.tickertapeMatch(search, "", "Spectrum Brands Holdings, Inc."))
        assertEquals("/stocks/chennai-petroleum-corporation-CHPE", MarketLogic.tickertapeMatch(search, "CHENNPETRO.NS", ""))
        assertEquals("/stocks/chennai-petroleum-corporation-CHPE", MarketLogic.tickertapeMatch(search, "", "Chennai Petroleum Corporation Ltd"))
        assertEquals("/stocks/spectrum-electrical-industries-SPEC", MarketLogic.tickertapeMatch(search, "", "Spectrum Electrical"))
        assertTrue(MarketLogic.foreignNews("Spectrum Brands' Q4 Earnings Beat Estimates on Margin Gains - Zacks Investment Research"))
        assertTrue(MarketLogic.foreignNews("Spectrum Brands Holdings Inc. (NYSE:SPB) Stock Now Rated Hold - MarketBeat"))
        assertFalse(MarketLogic.foreignNews("Sammaan Capital Q2 FY27 results: Net profit rises 1.25% to ₹312 crore - scanx.trade"))
        assertFalse(MarketLogic.foreignNews("Trump threatens 50% tariff on India - The Economic Times"))
        // America ki khabar sirf tab, jab India par asar ho
        assertFalse(MarketLogic.foreignNews("Fed cuts rates by 25 bps, signals more easing - Reuters"))
        assertFalse(MarketLogic.foreignNews("Nvidia results lift Indian IT stocks - Mint"))
        assertTrue(MarketLogic.foreignNews("Nvidia shares surge on record revenue - Mint"))
        assertTrue(MarketLogic.foreignNews("Wall Street ends higher as tech rallies - CNBC"))
        assertFalse(MarketLogic.foreignNews("Crude oil prices jump 4% after OPEC cut - Business Standard"))
        assertTrue(MarketLogic.indianSymbol("CHENNPETRO.NS"))
        assertFalse(MarketLogic.indianSymbol("SPB"))
    }
}
