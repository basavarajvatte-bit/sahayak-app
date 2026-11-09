package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryLogicTest {
    @Test fun parsesAndRepairsStory() {
        val json = """```json
{"title":"Jungle Fun!","caption":"Dance time #baby","scenes":[
 {"action":"dance","place":"jungle","line":"Hello friends!","seconds":4},
 {"action":"fly","place":"moon","line":"","seconds":99},
 {"action":"swag","place":"beach","line":"Bye","seconds":1}]}
```"""
        val story = StoryLogic.parse(json, "baby")
        assertNotNull(story)
        story!!
        assertEquals(3, story.scenes.size)
        assertEquals("happy", story.scenes[1].action)
        assertEquals("jungle", story.scenes[1].place)
        assertEquals(8.0, story.scenes[1].seconds, 0.001)
        assertEquals("happy", story.scenes[2].action)
        assertEquals(2.0, story.scenes[2].seconds, 0.001)
    }

    @Test fun chhotuKeepsSwag() {
        val story = StoryLogic.parse("""{"scenes":[{"action":"swag","place":"stage","line":"","seconds":3}]}""", "chhotu")
        assertEquals("swag", story!!.scenes[0].action)
        assertEquals("My Short", story.title)
    }

    @Test fun rejectsBadJson() {
        assertNull(StoryLogic.parse("not json", "baby"))
        assertNull(StoryLogic.parse("""{"scenes":[]}""", "baby"))
    }

    @Test fun makesSafeFileName() {
        assertEquals("Jungle_Fun_1109_1400.mp4", StoryLogic.fileName("Jungle  Fun!", "1109_1400"))
    }

    @Test fun wrapsSubtitles() {
        val lines = StoryLogic.wrap("Hello my little friends, let us dance together", 22)
        assertTrue(lines.all { it.length <= 22 })
        assertEquals("Hello my little", lines[0])
    }
}
