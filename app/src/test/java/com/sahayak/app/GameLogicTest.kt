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
        assertEquals("⬆️ Upar jayega (shayad)", GameLogic.guess(0.57))
        assertEquals("⬇️ Neeche jayega (kaafi pakka)", GameLogic.guess(0.38))
        assertEquals("⬆️ Upar jayega (bahut pakka)", GameLogic.guess(0.7))
        assertTrue(GameLogic.isSure(0.56))
        assertFalse(GameLogic.isSure(0.55))
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
        val t = GameLogic.tradeLines("15:15", "NIFTY", "Nifty", "NIFTY 25350 PE", "01-Dec-2026", 65, 245.4, 250.85, 77.53)
        assertEquals("15:15 · Nifty 25350 PE (1 Dec, weekly)\n1 lot (65) · ₹245.40 → ₹250.85\nLagaye ₹15,951 · Kharcha ₹78", t)
    }

    @Test fun openTradeAndMonthlyExpiry() {
        val t = GameLogic.tradeLines("12:15", "BANKNIFTY", "Bank Nifty", "BANKNIFTY 63300 CE", "29-Dec-2026", 60, 400.0, null, null)
        assertEquals("12:15 · Bank Nifty 63300 CE (29 Dec, monthly)\n2 lot (60) · ₹400.00\nLagaye ₹24,000", t)
        assertEquals("bad", GameLogic.expiryWords("bad"))
    }
}
