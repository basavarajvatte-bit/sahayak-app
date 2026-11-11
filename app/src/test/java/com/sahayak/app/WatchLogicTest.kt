package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLogicTest {
    private val page = """<html><head><title>x</title><script>var a=1;</script></head><body>
        <marquee>Notice: Registration for NEET PG Round 2 will open on 15 Nov</marquee>
        <div>Home</div><div>Contact&nbsp;us</div><p>Old result list</p></body></html>"""

    @Test fun extractsVisibleLines() {
        val lines = WatchLogic.lines(page)
        assertTrue(lines.any { it.startsWith("Notice: Registration") })
        assertTrue("Contact us" in lines)
        assertFalse(lines.any { "var a" in it })
    }

    @Test fun findsNewImportantLines() {
        val before = setOf("Home", "Contact us", "Old result list")
        val fresh = WatchLogic.newLines(WatchLogic.lines(page), before)
        assertEquals(1, fresh.size)
        val keys = WatchLogic.keywords("UP NEET PG counselling registration open")
        assertEquals(fresh, WatchLogic.important(fresh, keys))
        assertTrue(WatchLogic.important(listOf("Contact us"), keys).isEmpty())
    }
}
