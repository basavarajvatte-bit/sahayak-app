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
}
