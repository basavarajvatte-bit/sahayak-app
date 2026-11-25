package com.sahayak.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLogicTest {
    @Test fun rupeesFormat() {
        assertEquals("₹1,234", GameLogic.rupees(1234.4))
        assertEquals("−₹56", GameLogic.rupees(-56.2))
        assertEquals("₹0", GameLogic.rupees(-0.2))
    }

    @Test fun guessWordsAreSimple() {
        assertEquals("❔ Pata nahi", GameLogic.guess(0.52))
        assertEquals("❔ Pata nahi", GameLogic.guess(0.48))
        assertEquals("❔ Pata nahi", GameLogic.guess(0.57))
        assertEquals("⬆️ Upar jayega (shayad)", GameLogic.guess(0.62))
        assertEquals("⬇️ Neeche jayega (kaafi pakka)", GameLogic.guess(0.34))
        assertEquals("⬆️ Upar jayega (bahut pakka)", GameLogic.guess(0.72))
        assertTrue(GameLogic.isSure(0.60))
        assertFalse(GameLogic.isSure(0.59))
        assertEquals("100 mein se 47 sahi", GameLogic.outOf100(46.8))
    }

    @Test fun verdictComparesWithBestSimpleWay() {
        val falling = JSONObject("""{"guesses":137,"model_pct":64.2,"coin_pct":50,"repeat_last_pct":37.2,"always_up_pct":35.8}""")
        // Hamesha neeche bolna 64.2% hota, isliye computer aage nahi
        assertEquals(64.2, GameLogic.bestSimple(falling), 0.01)
        assertEquals("😐", GameLogic.verdict(falling).first)
        val good = JSONObject("""{"guesses":200,"model_pct":58,"coin_pct":50,"repeat_last_pct":51,"always_up_pct":49}""")
        assertEquals("🙂", GameLogic.verdict(good).first)
        val bad = JSONObject("""{"guesses":746,"model_pct":47,"coin_pct":50,"repeat_last_pct":49,"always_up_pct":52}""")
        assertEquals("😟", GameLogic.verdict(bad).first)
        assertEquals("⏳", GameLogic.verdict(JSONObject("""{"guesses":0}""")).first)
        assertEquals("⏳", GameLogic.verdict(null).first)
    }

    @Test fun newRulesHelpers() {
        val today = JSONObject("""{"10":{"trades":1,"pnl":-23.2},"30":{"trades":41,"pnl":-599.4}}""")
        val (n, v) = GameLogic.sharesMoney(today)
        assertEquals(42, n)
        assertEquals(-622.6, v, 0.01)
        val ix = JSONObject("""{"NIFTY":{"30":{"trades":1,"pnl":-1218.98}},"BANKNIFTY":{"30":{"trades":1,"pnl":-3290.03}}}""")
        assertEquals(1, GameLogic.indexMoney(ix, setOf("NIFTY")).first)
        assertEquals(-1218.98, GameLogic.indexMoney(ix, setOf("NIFTY")).second, 0.01)
        assertEquals("stop-loss laga", GameLogic.exitWords("stop"))
        assertEquals(null, GameLogic.staleWords("2026-11-25 14:05", "2026-11-25", 14 * 60 + 10, true))
        assertTrue(GameLogic.staleWords("2026-11-25 12:25", "2026-11-25", 13 * 60 + 15, true)!!.contains("50 minute"))
        assertEquals(null, GameLogic.staleWords("2026-11-25 12:25", "2026-11-25", 13 * 60 + 15, false))
        assertTrue(GameLogic.realMoneyWords(3).endsWith("3 / 40"))
    }

    @Test fun moneyWords() {
        assertTrue(GameLogic.moneyWords(0, 0.0).startsWith("Aaj abhi tak koi nakli trade nahi"))
        assertEquals("Aaj 3 baar khela: ₹146 ganvaye 😟", GameLogic.moneyWords(3, -146.0))
        assertEquals("Aaj 2 baar khela: ₹80 kamaye 🙂", GameLogic.moneyWords(2, 80.0))
        assertEquals("+₹62 🙂", GameLogic.tradeResult(62.0))
        assertEquals("−₹71 😟", GameLogic.tradeResult(-71.0))
        assertEquals("Call (upar ka daav)", GameLogic.optionWords("CE"))
        assertTrue(GameLogic.marketHours(4, 10 * 60))
        assertFalse(GameLogic.marketHours(1, 10 * 60))
    }

    @Test fun tradeShowsContractLotsAndMoney() {
        val t = GameLogic.tradeLines("15:15", 10, false, "NIFTY", "Nifty", "NIFTY 25350 PE", "01-Dec-2026", 65, 245.4, 250.85, 77.53)
        assertEquals("Nifty 25350 PE (1 Dec, weekly)\nLiya 15:15 · Becha 15:25\n1 lot (65) · ₹245.40 → ₹250.85\nLagaye ₹15,951 · Kharcha ₹78", t)
    }

    @Test fun openTradeAndMonthlyExpiry() {
        val t = GameLogic.tradeLines("11:45", 30, true, "BANKNIFTY", "Bank Nifty", "BANKNIFTY 63300 CE", "29-Dec-2026", 60, 400.0, null, null)
        assertEquals("Bank Nifty 63300 CE (29 Dec, monthly)\nLiya 11:45 · Bechenge 12:15 ko\n2 lot (60) · ₹400.00\nLagaye ₹24,000", t)
        assertEquals("12:05", GameLogic.clock24("11:55", 10))
        assertEquals("09:15", GameLogic.clock24("9:15"))
        assertEquals("15:25", GameLogic.clock24("15:15", 10))
        assertEquals("bad", GameLogic.expiryWords("bad"))
    }

    @Test fun liveOpenTradeShowsNowPriceAndSignedPnl() {
        val t = GameLogic.tradeLines("14:25", 30, true, "NIFTY", "Nifty", "NIFTY 25450 PE", "01-Dec-2026", 65, 245.35, null, null, 262.1, "14:37")
        assertEquals("Nifty 25450 PE (1 Dec, weekly)\nLiya 14:25 · Bechenge 14:55 ko\n1 lot (65) · ₹245.35 → abhi ₹262.10 (14:37)\nLagaye ₹15,948", t)
        assertEquals("+₹1,010", GameLogic.livePnl(1010.2))
        assertEquals("−₹120", GameLogic.livePnl(-120.0))
        assertEquals("₹0", GameLogic.livePnl(0.2))
    }

    @Test fun checkedListMatchesGameCount() {
        val idx = listOf(
            """{"day":"2026-11-25","at":555,"index":"NIFTY","minutes":10,"price":100,"p":0.52,"after":101,"move_pct":1,"right":true,"done":true}""",
            """{"day":"2026-11-25","at":565,"index":"NIFTY","minutes":10,"price":100,"p":0.6,"after":99,"move_pct":-1,"right":false,"done":true}""",
            """{"day":"2026-11-25","at":575,"index":"NIFTY","minutes":10,"price":100,"p":0.6,"after":100,"move_pct":0,"right":null,"done":true}""",
            """{"day":"2026-11-25","at":575,"index":"NIFTY","minutes":30,"price":100,"p":0.6,"after":101,"move_pct":1,"right":true,"done":true}""",
            """{"day":"2026-11-25","at":585,"index":"BANKNIFTY","minutes":10,"price":100,"p":0.4,"after":99,"move_pct":-1,"right":true,"done":true}""",
            """{"day":"2026-11-25","at":595,"index":"NIFTY","minutes":10,"price":100,"p":0.6}"""
        ).joinToString("\n")
        val n = GameLogic.indexChecked(idx, "NIFTY", 10)
        assertEquals(2, n.size)
        assertEquals(565, n[0].at)
        assertEquals("2 mein se 1 sahi", GameLogic.countWords(n))
        assertEquals(1, GameLogic.indexChecked(idx, "BANKNIFTY", 10).size)

        val sh = listOf(
            """{"at":555,"symbol":"TCS","price":10,"p":{"10":0.7,"30":0.4},"res":{"10":{"after":11,"move_pct":10,"right":true}}}""",
            """{"at":555,"symbol":"INFY","price":10,"p":{"10":0.3},"res":{"10":{"after":10,"move_pct":0,"right":null}}}""",
            """{"at":565,"symbol":"ITC","price":10,"p":{"10":0.3},"res":{}}"""
        ).joinToString("\n")
        val s = GameLogic.sharesChecked(sh, 10)
        assertEquals(1, s.size)
        assertEquals("TCS", s[0].name)
        assertEquals("09:15", GameLogic.hhmm(555))
        assertEquals("⬆️ Upar socha (bahut pakka)", GameLogic.thought(0.7))
        assertTrue(GameLogic.thought(0.52).contains("Pata nahi"))
    }
}
