package com.sahayak.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LangTest {
    @Before fun marathiOn() { Lang.on = true }
    @After fun marathiOff() { Lang.on = false }

    private fun english(s: String) = Regex("[A-Za-z]{3,}").findAll(s).map { it.value }.toList()

    @Test fun offMeansNoChange() {
        Lang.on = false
        assertEquals("My shares", Lang.t("My shares"))
        assertEquals("rules", Lang.ai("rules"))
    }

    @Test fun plainLinesAndMarks() {
        assertEquals("माझे shares", Lang.t("My shares"))
        assertEquals("🔒 आजच्या price वर lock करा", Lang.t("🔒 Lock at today's price"))
        assertEquals("✓ Gemini key save झाली", Lang.t("✓ Gemini key saved"))
        assertEquals("काहीतरी चुकले. पुन्हा try करा", Lang.t("Something went wrong. Try again"))
        assertEquals("Internet नाही. पुन्हा try करा", Lang.t("No internet. Try again"))
    }

    @Test fun numbersAndDatesAreKept() {
        assertEquals("लक्ष ठेवले आहे. शेवटचे check: 16 Nov, 12:39 PM. पुढचे: 16 Nov, 1:09 PM",
            Lang.t("Watching. Last check: 16 Nov, 12:39 PM. Next: 16 Nov, 1:09 PM"))
        assertEquals("▼ 6 पैकी 5 green  (12)", Lang.t("▼ 5 of 6 green  (12)"))
        assertEquals("15 मिनिटे", Lang.t("15 min"))
        assertEquals("आज +1.2%", Lang.t("Aaj +1.2%"))
        assertEquals("500 पैकी 12 shares तपासले", Lang.t("Checked 12 of 500 shares"))
        assertEquals("12 lock केलेले  ·  ▲ 5 वर  ·  ▼ 7 खाली (lock पासून). Chart साठी box वर tap करा.",
            Lang.t("12 locked  ·  ▲ 5 up  ·  ▼ 7 down since lock. Tap a box for its chart."))
    }

    @Test fun namesInsideLines() {
        assertEquals("Tata Motors आजच्या price वर lock होत आहे", Lang.t("Locking Tata Motors at today's price"))
        assertEquals("Tata Motors ला ₹712.50 वर lock केले (price पक्की). माझे shares मध्ये पहा",
            Lang.t("Locked Tata Motors at ₹712.50 (price verified). See it in My shares"))
        assertEquals("\"Abc\" NSE किंवा BSE वर नाही. ती परदेशी company असू शकते. नेमका NSE code टाकून पहा, उदा. RELIANCE",
            Lang.t("\"Abc\" is not on NSE or BSE. It may be a foreign company. Try the exact NSE code, e.g. RELIANCE"))
        assertEquals("Lock ₹120.50", Lang.t("Lock ₹120.50"))
        assertEquals("●●○○○   छोटी  (5 पैकी 2)", Lang.t("●●○○○   Chhoti  (2 of 5)"))
        assertEquals("नवीन Smallcap list बनवा (काही मिनिटे लागतात)", Lang.t("Make fresh Smallcap list (takes a few minutes)"))
    }

    @Test fun longExplanationsLineByLine() {
        val body = "📌 Kya dekhte hain\nKarz ÷ shareholders ka paisa (equity). Jaise D/E 0.3 matlab har ₹100 apne paise par sirf ₹30 karz.\n\n" +
            "✓ Kab milta hai\nD/E 0.5 se kam. Bank aur NBFC ke liye ye jaanch nahi hoti, kyunki logon ka paisa (deposit) lena hi unka dhandha hai."
        val out = Lang.t(body)
        assertTrue(out, out.startsWith("📌 काय पाहतो\nकर्ज ÷ shareholders चे पैसे (equity). उदा. D/E 0.3"))
        assertTrue(out, out.contains("✓ कधी मिळते\nD/E 0.5 पेक्षा कमी. Bank आणि NBFC"))
        assertFalse(out, out.contains("jaanch"))
    }

    @Test fun headlinesStayAsTheyAre() {
        val news = "Jubilant Pharmova shares swing 8% as Q2 profit falls 74%"
        assertEquals(news, Lang.t(news))
        assertEquals("Tata Motors", Lang.t("Tata Motors"))
        assertEquals("16 Nov, 11:45 AM  ·  Results  ·  वाईट news", Lang.t("16 Nov, 11:45 AM  ·  Results  ·  Buri khabar"))
    }

    @Test fun everyDictionaryLineIsWellFormed() {
        var count = 0
        LangMr.PARTS.forEach { part ->
            part.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }.forEach { line ->
                assertTrue("No arrow: $line", line.contains(" => "))
                val key = line.substringBefore(" => ")
                val out = Lang.t(key)
                // Har line ka Marathi banna chahiye: kam se kam ek Devanagari akshar
                assertTrue("Not translated: $key -> $out", out.any { it in 'ऀ'..'ॿ' } || english(out) == english(key))
                count++
            }
        }
        assertTrue(count > 600)
    }

    @Test fun aiIsToldToWriteMarathi() {
        assertTrue(Lang.ai("rules").contains("Marathi"))
    }
}
