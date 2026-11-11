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

    @Test fun readsGoogleNewsAndPicksRealHits() {
        val xml = """<rss><channel><title>x</title>
            <item><title>UP NEET PG Counselling 2026: Round 2 registration reopens today - Jagran</title><link>https://a.in/1</link><pubDate>Wed, 11 Nov 2026</pubDate></item>
            <item><title><![CDATA[NEET PG result &amp; cutoff out]]></title><link>https://b.in/2</link><pubDate>Tue</pubDate></item>
            </channel></rss>"""
        val items = WatchLogic.news(xml)
        org.junit.Assert.assertEquals(2, items.size)
        org.junit.Assert.assertEquals("NEET PG result & cutoff out", items[1].title)
        val keys = WatchLogic.keywords("UP NEET PG counselling registration open")
        org.junit.Assert.assertTrue(WatchLogic.newsHit(items[0].title, keys))
        org.junit.Assert.assertFalse(WatchLogic.newsHit(items[1].title, keys))
    }
}
