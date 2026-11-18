package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDoctorLogicTest {
    private val feed = """<?xml version="1.0"?><feed>
<entry><yt:videoId>AAA</yt:videoId><title>addictive Meta ads</title><published>2026-11-18T13:30:00+00:00</published>
<media:group><media:description>clip</media:description><media:community><media:starRating count="1" average="5"/><media:statistics views="2"/></media:community></media:group></entry>
<entry><yt:videoId>BBB</yt:videoId><title>Why you can&#39;t stop scrolling? #shorts</title><published>2026-11-10T01:00:00+00:00</published>
<media:group><media:description></media:description><media:community><media:starRating count="40" average="5"/><media:statistics views="900"/></media:community></media:group></entry>
</feed>"""

    @Test fun findsChannel() {
        assertEquals("UCabcdefghijklmnopqrstuv", YtDoctorLogic.channelIdIn("https://youtube.com/channel/UCabcdefghijklmnopqrstuv"))
        assertEquals("@doc_raj", YtDoctorLogic.handleIn("https://youtube.com/@doc_raj/shorts"))
        assertEquals("@docraj", YtDoctorLogic.handleIn("docraj"))
        assertNull(YtDoctorLogic.handleIn("a b"))
        assertEquals("UCabcdefghijklmnopqrstuv", YtDoctorLogic.channelIdFromPage("x\"externalId\":\"UCabcdefghijklmnopqrstuv\"y"))
    }

    @Test fun readsFeed() {
        val v = YtDoctorLogic.parseFeed(feed)
        assertEquals(listOf("AAA", "BBB"), v.map { it.id })
        assertEquals("Why you can't stop scrolling? #shorts", v[1].title)
        assertEquals(900L, v[1].views)
        assertEquals(40L, v[1].likes)
        assertEquals("clip", v[0].description)
        assertEquals(19, YtDoctorLogic.hourIst(v[0].publishedMs))
    }

    @Test fun findsVideoProblems() {
        val v = YtDoctorLogic.parseFeed(feed)
        val now = YtDoctorLogic.parseTime("2026-11-21T00:00:00+00:00")
        val weak = YtDoctorLogic.videoIssues(v[0], now, true)
        assertTrue(weak.any { it.startsWith("Title bahut chhota") })
        assertTrue(weak.any { it.contains("chhote akshar") })
        assertTrue(weak.any { it.contains("2 din baad") })
        assertTrue(weak.any { it.contains("America") })
        assertTrue(weak.any { it.contains("hashtag") })
        val good = YtDoctorLogic.videoIssues(v[1], now, true)
        assertTrue(good.none { it.contains("chhote akshar") || it.contains("2 din baad") || it.contains("America") || it.contains("hashtag") })
    }

    @Test fun findsChannelProblems() {
        val v = YtDoctorLogic.parseFeed(feed)
        val now = YtDoctorLogic.parseTime("2026-11-21T00:00:00+00:00")
        val issues = YtDoctorLogic.channelIssues(v, now)
        assertTrue(issues.any { it.contains("14 din") })
        assertTrue(issues.any { it.contains("4 din") })
        assertEquals(451L, YtDoctorLogic.median(listOf(2, 900)))
    }

    @Test fun readsReport() {
        val r = YtDoctorLogic.parseReport("""ok {"score":12,"first_seconds":"Dark room","problems":["Hook weak"],"fixes":["Start with question"],"better_title":"T","better_hook":"H"}""")!!
        assertEquals(10, r.score)
        assertEquals(listOf("Hook weak"), r.problems)
        assertNull(YtDoctorLogic.parseReport("nothing"))
        val text = YtDoctorLogic.reportText(YtVideo("A", "Title", 0, 5, 1), r, listOf("x"))
        assertTrue(text.contains("❌ Hook weak") && text.contains("✅ Start with question") && text.contains("⚠ x"))
    }
}
