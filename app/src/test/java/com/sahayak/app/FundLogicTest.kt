package com.sahayak.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FundLogicTest {
    @Test fun buysOnlyWholeShares() {
        assertEquals(59, FundLogic.shares(10000.0, 169.20))
        assertEquals(0, FundLogic.shares(100.0, 169.20))
        assertEquals(10, FundLogic.shares(1000.0, 100.0))
        assertEquals(0, FundLogic.shares(1000.0, 0.0))
    }

    @Test fun addsUpAFund() {
        val fund = JSONObject().put("holdings", JSONArray()
            .put(JSONObject().put("qty", 10).put("buyPrice", 100.0).put("lastPrice", 110.0).put("prevClose", 108.0).put("niftyAtBuy", 20000.0))
            .put(JSONObject().put("qty", 5).put("buyPrice", 200.0).put("lastPrice", 180.0).put("prevClose", 182.0).put("niftyAtBuy", 20000.0)))
            .put("sold", JSONArray().put(JSONObject().put("qty", 2).put("buyPrice", 500.0).put("sellPrice", 550.0).put("niftyAtBuy", 20000.0).put("niftyAtSell", 21000.0)))
        val s = FundLogic.summary(fund, 20400.0)
        assertEquals(2000.0, s.invested, 0.001)
        assertEquals(2000.0, s.value, 0.001)
        assertEquals(100.0, s.realized, 0.001)
        assertEquals(1000.0, s.soldCost, 0.001)
        // Abhi ke shares 0 munafa + beche ka 100 = 100, 3000 par
        assertEquals(100.0, s.profit, 0.001)
        assertEquals(100.0 / 3000 * 100, s.profitPct, 0.001)
        // Nifty: 2000 × 1.02 + 1000 × 1.05 = 3090
        assertEquals(3090.0, s.niftyValue, 0.001)
        assertEquals(3.0, s.niftyPct, 0.001)
        // Aaj: (110-108)×10 + (180-182)×5 = 10
        assertEquals(10.0, s.dayChange, 0.001)
    }

    @Test fun writesRupeesTheIndianWay() {
        assertEquals("₹1,23,456", FundLogic.rupees(123456.0))
        assertEquals("₹10,000.50", FundLogic.rupees(10000.5))
        assertEquals("+₹500", FundLogic.rupees(500.0, true))
        assertEquals("-₹1,200.25", FundLogic.rupees(-1200.25, true))
        assertEquals("₹999", FundLogic.rupees(999.0))
    }
}
