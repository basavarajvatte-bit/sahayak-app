package com.sahayak.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLogicTest {
    @Test fun rupeesFormat() {
        assertEquals("₹1,234", GameLogic.rupees(1234.4))
        assertEquals("−₹56", GameLogic.rupees(-56.2))
        assertEquals("₹0", GameLogic.rupees(-0.2))
    }

    @Test fun guessWords() {
        assertEquals("▲ up 61%", GameLogic.guess(0.61))
        assertEquals("▼ down 58%", GameLogic.guess(0.42))
    }

    @Test fun verdictComparesWithBestSimpleWay() {
        val falling = JSONObject("""{"guesses":137,"model_pct":64.2,"coin_pct":50,"repeat_last_pct":37.2,"always_up_pct":35.8}""")
        // Hamesha neeche bolna 64.2% hota, isliye model aage nahi
        assertEquals(64.2, GameLogic.bestSimple(falling), 0.01)
        assertTrue(GameLogic.verdict(falling).startsWith("Model is not better"))
        val good = JSONObject("""{"guesses":200,"model_pct":58,"coin_pct":50,"repeat_last_pct":51,"always_up_pct":49}""")
        assertTrue(GameLogic.verdict(good).startsWith("Model is ahead"))
        assertEquals("No guesses checked yet", GameLogic.verdict(JSONObject("""{"guesses":0}""")))
    }

    @Test fun lines() {
        val s = JSONObject("""{"guesses":238,"model_pct":53.8,"repeat_last_pct":47.9,"always_up_pct":45.4,"trades":3,"trades_won":1,"pnl":-146.0,"costs":120.0}""")
        assertEquals("54% right of 238 · coin 50 · repeat last 48% · always up 45%", GameLogic.scoreLine(s))
        assertEquals("3 trades · 1 won · −₹146 after costs (costs ₹120)", GameLogic.moneyLine(s))
        assertTrue(GameLogic.marketHours(4, 10 * 60))
        assertTrue(!GameLogic.marketHours(1, 10 * 60))
    }
}
