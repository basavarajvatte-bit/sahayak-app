package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceLogicTest {
    @Test fun wavHeaderIsCorrect() {
        val h = VoiceLogic.wavHeader(48_000, 24_000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        assertEquals("data", String(h, 36, 4))
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 48_000, b.getInt(4))
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(24_000, b.getInt(24))
        assertEquals(48_000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(48_000, b.getInt(40))
    }

    @Test fun recordingLength() {
        assertEquals(30, VoiceLogic.seconds(44 + 30L * 24_000 * 2))
        assertEquals(0, VoiceLogic.seconds(10))
    }

    @Test fun readsVoiceIds() {
        assertEquals("abc123", VoiceLogic.voiceId(VoiceLogic.FISH, """{"_id":"abc123","state":"trained"}"""))
        assertEquals("v9", VoiceLogic.voiceId(VoiceLogic.ELEVEN, """{"voice_id":"v9","requires_verification":false}"""))
        assertEquals("", VoiceLogic.voiceId(VoiceLogic.ELEVEN, "oops"))
        assertEquals("", VoiceLogic.voiceId(VoiceLogic.FISH, """{"voice_id":"v9"}"""))
    }

    @Test fun explainsProblems() {
        val paid = VoiceLogic.problem(VoiceLogic.ELEVEN, 401, """{"detail":{"status":"can_not_use_instant_voice_cloning","message":"Your subscription has no access to instant voice cloning"}}""")
        assertTrue(paid.contains("key is not right"))
        assertTrue(paid.contains("no access to instant voice cloning"))
        assertTrue(VoiceLogic.problem(VoiceLogic.FISH, 402, """{"status":402,"message":"Insufficient balance"}""").contains("Insufficient balance"))
        assertTrue(VoiceLogic.problem(VoiceLogic.FISH, 503, "").contains("busy"))
        assertEquals("Fish Audio answered 418", VoiceLogic.problem(VoiceLogic.FISH, 418, "not json"))
        assertEquals("bad thing", VoiceLogic.serverMessage("""{"detail":"bad thing"}"""))
    }

    @Test fun scriptStartsWithConsentAndTextIsCleaned() {
        assertTrue(VoiceLogic.script("Basavaraj").startsWith("Mera naam Basavaraj hai. Ye meri apni awaaz hai"))
        assertTrue(VoiceLogic.script(" ").contains("______"))
        assertEquals("Namaste Atul", VoiceLogic.cleanText("  Namaste \n  Atul "))
        assertEquals(VoiceLogic.TEXT_MAX, VoiceLogic.cleanText("a".repeat(900)).length)
    }
}
