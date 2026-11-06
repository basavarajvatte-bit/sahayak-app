package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainLogicTest {
    @Test fun keepsSecretsOut() {
        assertNull(BrainLogic.scrub("my OTP is 123456", 100))
        assertNull(BrainLogic.scrub("password rahul", 100))
        assertEquals("call [number] now", BrainLogic.scrub("call 9673643799 now", 100))
        assertNull(BrainLogic.scrub("   ", 100))
    }

    @Test fun findsSimilarWork() {
        assertTrue(BrainLogic.overlap("upload baby video on youtube", "YouTube upload video") > 0)
        assertEquals(0, BrainLogic.overlap("send message on whatsapp", "YouTube upload video"))
        assertEquals(0, BrainLogic.overlap("open youtube", "YouTube upload video"))
    }

    @Test fun tokensDropCommonWords() {
        assertEquals(listOf("send", "mail", "atul"), BrainLogic.tokens("Please send the mail to Atul"))
        assertFalse("the" in BrainLogic.tokens("the"))
    }

    @Test fun cleansJsonFence() {
        assertEquals("{\"a\":1}", BrainLogic.cleanJson("```json\n{\"a\":1}\n```"))
    }
}
