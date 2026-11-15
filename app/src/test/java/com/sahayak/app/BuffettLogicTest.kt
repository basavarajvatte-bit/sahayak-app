package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuffettLogicTest {
    private fun series(type: String, vararg v: Pair<String, Double>) =
        """{"meta":{"type":["$type"]},"$type":[""" + v.joinToString(",") { """{"asOfDate":"${it.first}-03-31","reportedValue":{"raw":${it.second}}}""" } + "]}"

    private val cr = 1e7

    private fun json(vararg parts: String) = """{"timeseries":{"result":[""" + parts.joinToString(",") + "]}}"

    @Test fun strongCompanyPassesMostChecks() {
        val j = json(
            series("annualStockholdersEquity", "2023" to 1000 * cr, "2024" to 1200 * cr, "2025" to 1400 * cr),
            series("annualTotalDebt", "2025" to 100 * cr),
            series("annualLongTermDebt", "2025" to 80 * cr),
            series("annualCashAndCashEquivalents", "2025" to 300 * cr),
            series("annualRetainedEarnings", "2023" to 500 * cr, "2024" to 650 * cr, "2025" to 820 * cr),
            series("annualNetIncome", "2023" to 200 * cr, "2024" to 240 * cr, "2025" to 280 * cr),
            series("annualTotalRevenue", "2023" to 1000 * cr, "2024" to 1150 * cr, "2025" to 1300 * cr),
            series("annualCapitalExpenditure", "2023" to -40 * cr, "2024" to -50 * cr, "2025" to -60 * cr),
            series("annualOperatingCashFlow", "2023" to 220 * cr, "2024" to 260 * cr, "2025" to 300 * cr),
            series("annualRepurchaseOfCapitalStock", "2024" to -50 * cr)
        )
        val d = BuffettLogic.parse(j)
        assertEquals(3, BuffettLogic.years(d))
        val c = BuffettLogic.evaluate(d, false).associateBy { it.id }
        assertEquals(BuffettLogic.PASS, c["debt"]!!.status)
        assertEquals(BuffettLogic.PASS, c["de"]!!.status)
        assertEquals(BuffettLogic.PASS, c["retained"]!!.status)
        assertEquals(BuffettLogic.PASS, c["roe"]!!.status)
        assertEquals(BuffettLogic.PASS, c["capex"]!!.status)
        assertEquals(BuffettLogic.PASS, c["preferred"]!!.status)
        assertEquals(BuffettLogic.PASS, c["buyback"]!!.status)
        assertEquals(BuffettLogic.PASS, c["moat"]!!.status)
        assertEquals(8 to 8, BuffettLogic.score(c.values.toList()))
        assertEquals("D/E 0.07", c["de"]!!.value)
    }

    @Test fun debtHeavyCompanyFails() {
        val j = json(
            series("annualStockholdersEquity", "2024" to 500 * cr, "2025" to 520 * cr),
            series("annualTotalDebt", "2025" to 900 * cr),
            series("annualNetIncome", "2024" to 60 * cr, "2025" to 40 * cr),
            series("annualCashAndCashEquivalents", "2025" to 20 * cr),
            series("annualRetainedEarnings", "2024" to 200 * cr, "2025" to 190 * cr)
        )
        val c = BuffettLogic.evaluate(BuffettLogic.parse(j), false).associateBy { it.id }
        assertEquals(BuffettLogic.FAIL, c["debt"]!!.status)
        assertTrue(c["debt"]!!.value.contains("22.5 saal"))
        assertEquals(BuffettLogic.FAIL, c["de"]!!.status)
        assertEquals(BuffettLogic.FAIL, c["retained"]!!.status)
        assertEquals(BuffettLogic.FAIL, c["roe"]!!.status)
        assertEquals(BuffettLogic.UNKNOWN, c["capex"]!!.status)
    }

    @Test fun banksSkipDebtChecks() {
        val j = json(series("annualStockholdersEquity", "2025" to 100 * cr), series("annualTotalDebt", "2025" to 900 * cr))
        val checks = BuffettLogic.evaluate(BuffettLogic.parse(j), true)
        val c = checks.associateBy { it.id }
        assertEquals(BuffettLogic.SKIP, c["debt"]!!.status)
        assertEquals(BuffettLogic.SKIP, c["de"]!!.status)
        assertEquals(BuffettLogic.SKIP, c["capex"]!!.status)
        assertEquals(5, BuffettLogic.score(checks).second)
        assertTrue(BuffettLogic.parse("blocked").isEmpty())
        assertEquals(8, BuffettLogic.INFO.size)
    }

    @Test fun checksProfitQualityPriceAndFlags() {
        val j = json(
            series("annualOperatingCashFlow", "2024" to 90 * cr, "2025" to 110 * cr),
            series("annualNetIncome", "2024" to 100 * cr, "2025" to 120 * cr),
            series("annualCapitalExpenditure", "2025" to -30 * cr),
            series("annualOrdinarySharesNumber", "2023" to 1.0e8, "2025" to 1.2e8),
            series("annualTotalDebt", "2023" to 100 * cr, "2025" to 400 * cr),
            series("annualStockholdersEquity", "2025" to 1000 * cr)
        )
        val d = BuffettLogic.parse(j)
        val q = BuffettLogic.quality(d)
        assertEquals(BuffettLogic.PASS, q.status)
        assertEquals("Cash ÷ profit = 0.91 (2 saal)", q.value)
        // Owner earnings 80 cr; keemat 1.2 cr share × ₹100 = 1,200 cr; yield 6.7% < 7%
        val y = BuffettLogic.ownerYield(d, 100.0)
        assertEquals(BuffettLogic.FAIL, y.status)
        assertTrue(y.value.startsWith("Owner earnings yield 6.7%"))
        assertEquals(BuffettLogic.PASS, BuffettLogic.ownerYield(d, 50.0).status)
        val flags = BuffettLogic.dataFlags(d).associateBy { it.id }
        assertEquals(BuffettLogic.FAIL, flags["dilution"]!!.status)
        assertEquals(BuffettLogic.FAIL, flags["debtRise"]!!.status)
        assertTrue(BuffettLogic.redFlagNews("Promoters of XYZ pledge 20% stake"))
        assertTrue(BuffettLogic.redFlagNews("ABC Ltd auditor resigns citing concerns"))
        assertTrue(!BuffettLogic.redFlagNews("ABC Ltd Q2 profit rises 20%"))
        assertEquals(5, BuffettLogic.MORE_INFO.size)
    }
}
