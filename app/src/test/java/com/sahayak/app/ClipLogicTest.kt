package com.sahayak.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipLogicTest {
    @Test fun readsTimes() {
        assertEquals(65.0, ClipLogic.seconds("1:05")!!, 0.001)
        assertEquals(3723.0, ClipLogic.seconds("1:02:03")!!, 0.001)
        assertEquals(65.5, ClipLogic.seconds("65.5")!!, 0.001)
        assertEquals(42.0, ClipLogic.seconds(42)!!, 0.001)
        assertNull(ClipLogic.seconds("abc"))
        assertNull(ClipLogic.seconds(""))
        assertNull(ClipLogic.seconds(null))
    }

    @Test fun writesClock() {
        assertEquals("1:05", ClipLogic.clock(65.9))
        assertEquals("1:02:03", ClipLogic.clock(3723.0))
    }

    @Test fun parsesAndCleansClips() {
        val reply = """Here: {"clips":[
            {"start":"0:10","end":"0:50","hook":"Phone ki lat","caption":"Scroll stops here."},
            {"start":"0:40","end":"1:20","hook":"overlaps","caption":""},
            {"start":"2:00","end":"2:05","hook":"too short","caption":""},
            {"start":"3:00","end":"5:00","hook":"too long","caption":""},
            {"start":"9:30","end":"11:00","hook":"past end","caption":""}
        ]}"""
        val picks = ClipLogic.parse(reply, 600.0, 10)
        assertEquals(listOf("Phone ki lat", "too long", "past end"), picks.map { it.hook })
        assertEquals(ClipLogic.MAX_SECONDS, picks[1].seconds, 0.001)
        assertEquals(600.0, picks[2].endS, 0.001)
        assertEquals(1, ClipLogic.parse(reply, 600.0, 1).size)
    }

    @Test fun badReplyGivesNothing() {
        assertTrue(ClipLogic.parse("sorry", 100.0, 3).isEmpty())
        assertTrue(ClipLogic.parse("{broken", 100.0, 3).isEmpty())
    }

    @Test fun cleansHashtagsAndCaption() {
        assertEquals("#SocialMediaJustice #ai", ClipLogic.hashtags("SocialMediaJustice, #ai #AI"))
        val pick = ClipPick(0.0, 30.0, "Hook", "Watch this.")
        assertEquals("Watch this.\n\n#SocialMediaJustice", ClipLogic.caption(pick, "#SocialMediaJustice"))
        assertEquals("Hook", ClipLogic.caption(ClipPick(0.0, 30.0, "Hook", ""), ""))
    }

    @Test fun wrapsTitle() {
        assertEquals(listOf("Social media is", "the new cigarette"), ClipLogic.wrap("Social media is the new cigarette"))
        assertEquals(3, ClipLogic.wrap("one two three four five six seven eight nine ten eleven twelve", 5).size)
    }

    @Test fun cropsCenterForZoom() {
        assertArrayEquals(intArrayOf(0, 0, 1920, 1080), ClipLogic.cropRect(1920, 1080, false, 720, 1280))
        val r = ClipLogic.cropRect(1920, 1080, true, 720, 1280)
        assertEquals(1080, r[3])
        assertEquals(606, r[2])
        assertEquals(656, r[0])
    }
}
