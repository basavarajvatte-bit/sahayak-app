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

    @Test fun offlineStoryFitsLength() {
        val story = StoryLogic.offline("baby dances in the jungle", "baby", 15)
        assertEquals("jungle", story.scenes[0].place)
        assertTrue(story.scenes.sumOf { it.seconds } <= 15.0)
        assertEquals("Bye bye! See you soon!", story.scenes.last().line)
        assertTrue(StoryLogic.offline("x", "chhotu", 60).scenes.size >= 8)
    }

    @Test fun readsMyScript() {
        val story = StoryLogic.fromScript("drive: Vroom vroom!\n\nYummy food here\ndance:\nfly: up up", "baby", "park", "Fun day")!!
        assertEquals(listOf("drive", "feast", "dance", "happy"), story.scenes.map { it.action })
        assertEquals("Vroom vroom!", story.scenes[0].line)
        assertEquals("", story.scenes[2].line)
        assertEquals("fly: up up", story.scenes[3].line)
        assertEquals("park", story.scenes[1].place)
        assertNull(StoryLogic.fromScript("  \n ", "baby", "park", ""))
    }

    @Test fun readsTagsInScript() {
        val story = StoryLogic.fromScript("feast: Mmm mango! {close}\nfeast: {Yum!} {hearts}", "baby", "sweet", "Mango")!!
        assertEquals(true, story.scenes[0].close)
        assertEquals("Mmm mango!", story.scenes[0].line)
        assertEquals("Yum!", story.scenes[1].pop)
        assertEquals("hearts", story.scenes[1].effect)
        assertEquals("", story.scenes[1].line)
    }

    @Test fun musicHasRightLengthAndFades() {
        val music = Music.make(2.0, 8_000, 1)
        assertEquals(16_000, music.size)
        assertEquals(0, music[0].toInt())
        assertTrue(music.any { kotlin.math.abs(it.toInt()) > 1_000 })
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
