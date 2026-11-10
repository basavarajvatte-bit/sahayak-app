package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoemLogicTest {
    @Test fun knowsWhenPoemIsAsked() {
        assertTrue(PoemLogic.wantsPoem("Say a poem about rain"))
        assertTrue(PoemLogic.wantsPoem("nursery rhymes for baby"))
        assertFalse(PoemLogic.wantsPoem("Baby dances in the jungle"))
    }

    @Test fun cleansPastedPoem() {
        val rows = PoemLogic.lines("1. Little star up high,\n\n- Shining in the sky.\n***\n\"Good night\"")
        assertEquals(listOf("Little star up high,", "Shining in the sky.", "Good night"), rows)
        assertEquals(PoemLogic.MAX_LINES, PoemLogic.lines((1..40).joinToString("\n") { "line $it" }).size)
    }

    @Test fun lineCountFitsLength() {
        assertEquals(4, PoemLogic.lineCount(10))
        assertEquals(4, PoemLogic.lineCount(15))
        assertEquals(10, PoemLogic.lineCount(30))
        assertEquals(16, PoemLogic.lineCount(60))
    }

    @Test fun ownPoemMatchesTopic() {
        assertTrue(PoemLogic.builtIn("poem about rain", 4)[0].contains("rain"))
        assertTrue(PoemLogic.builtIn("poem on my car", 4)[0].contains("car"))
        assertEquals(4, PoemLogic.builtIn("a poem", 4).size)
        assertEquals(16, PoemLogic.builtIn("poem about stars", 16).size)
    }

    @Test fun wholeWordsOnly() {
        // "great beat" mein eat nahi, "start" mein star nahi
        val story = PoemLogic.fromPoem(listOf("What a great beat to start"), "baby", null, "", 2)!!
        assertEquals(1, story.scenes.size)
        assertEquals("park", story.scenes[0].place)
        assertEquals("happy", story.scenes[0].action)
    }

    @Test fun buildsPoemStory() {
        val rows = PoemLogic.builtIn("poem about stars", 4)
        val short = PoemLogic.fromPoem(rows, "baby", null, "", 10)!!
        assertTrue(short.poem)
        assertEquals(4, short.scenes.size)
        assertEquals(rows, short.scenes.map { it.line })
        assertEquals("night", short.scenes[0].place)
        assertEquals("stars", short.scenes[0].effect)
        assertEquals("Little star up in the sky", short.title)
        val long = PoemLogic.fromPoem(rows, "baby", "beach", "Star poem", 30)!!
        assertEquals(6, long.scenes.size)
        assertEquals("", long.scenes.first().line)
        assertEquals("dance", long.scenes.last().action)
        assertEquals("beach", long.scenes[1].place)
        assertEquals("Star poem", long.title)
        assertNull(PoemLogic.fromPoem(listOf(" "), "baby", null, "", 10))
    }

    @Test fun carPoemDrivesOnlyForBaby() {
        val row = listOf("Beep, beep, beep, my little car,")
        assertEquals("drive", PoemLogic.fromPoem(row, "baby", null, "", 2)!!.scenes[0].action)
        assertEquals("happy", PoemLogic.fromPoem(row, "chhotu", null, "", 2)!!.scenes[0].action)
    }

    @Test fun wordsLightUpInOrder() {
        val ends = PoemLogic.wordEnds("Little star, so bright")
        assertEquals(4, ends.size)
        assertEquals(1.0, ends.last(), 0.0001)
        assertTrue(ends.zipWithNext().all { it.first < it.second })
        assertEquals(0, PoemLogic.activeWord(ends, 0.0))
        assertEquals(3, PoemLogic.activeWord(ends, 0.99))
        assertEquals(3, PoemLogic.activeWord(ends, 1.0))
        assertEquals(-1, PoemLogic.activeWord(emptyList(), 0.5))
    }

    @Test fun mouthFollowsVoice() {
        val rate = 18_000
        val loud = ShortArray(rate) { (if (it % 40 < 20) 12_000 else -12_000).toShort() }
        val voice = loud + ShortArray(rate)
        val levels = LipSync.levels(voice, rate, 18)
        assertEquals(36, levels.size)
        assertTrue(levels[5] > 0.9f)
        assertEquals(0f, levels[30], 0.0001f)
        assertEquals(0, LipSync.levels(ShortArray(0), rate, 18).size)
        var mouth = 0
        var open = 0f
        var shut = 0f
        for (f in 0 until 36) {
            mouth = LipSync.next(mouth, f, levels[f])
            assertTrue(mouth in LipSync.OPEN.indices)
            if (f in 6..17) open += LipSync.OPEN[mouth]
            if (f in 24..35) shut += LipSync.OPEN[mouth]
        }
        assertTrue("open $open shut $shut", open / 12 > 0.55f)
        assertTrue("open $open shut $shut", shut < open - 2f)
    }

    @Test fun mouthDoesNotJump() {
        var mouth = 10
        val rnd = java.util.Random(3)
        for (f in 10 until 400) {
            val before = mouth
            mouth = LipSync.next(mouth, f, rnd.nextFloat())
            val step = Math.floorMod(mouth - before, 36)
            assertTrue(step <= 3 || step >= 34)
            val away = Math.floorMod(mouth - f, 36)
            assertTrue("drift $away at $f", away <= 4 || away >= 32)
        }
    }

    @Test fun softMusicIsGentle() {
        val tune = Music.soft(4.0, 8_000, 7)
        assertEquals(32_000, tune.size)
        assertEquals(0, tune[0].toInt())
        assertTrue(tune.any { kotlin.math.abs(it.toInt()) > 1_000 })
        assertTrue(tune.all { kotlin.math.abs(it.toInt()) < 30_000 })
    }

    @Test fun musicDipsUnderVoiceSmoothly() {
        val rate = 8_000
        val music = ShortArray(rate * 3) { 10_000 }
        val voice = ShortArray(rate * 3) { if (it in rate until rate * 2) 5_000 else 0 }
        val out = Music.mix(voice, music, rate)
        assertEquals(10_000, out[rate / 2].toInt())
        assertEquals(8_000, out[rate + rate / 2].toInt())
        assertEquals(10_000, out[rate * 3 - 1].toInt())
        // ek sample mein awaaz kabhi zor se nahi badalti
        assertTrue((1 until out.size).all { it == rate || it == rate * 2 || kotlin.math.abs(out[it] - out[it - 1]) < 60 })
    }
}
