package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogicTest {
    private val sdp = (1..120).joinToString("\r\n") { "a=candidate:$it 1 udp 2122260223 192.168.1.$it 5000$it typ host generation 0 ufrag abcd network-id 1" }

    @Test fun packsAndUnpacks() {
        assertEquals(sdp, CallLogic.unpack(CallLogic.pack(sdp)))
        assertTrue(CallLogic.pack(sdp).length < sdp.length)
        assertEquals("", CallLogic.pack(""))
        assertEquals("", CallLogic.unpack(""))
        assertNull(CallLogic.unpack("not gzip"))
    }

    @Test fun longSignalGoesInPartsAndComesBackWhole() {
        val parts = CallLogic.parts("call1", "offer", true, sdp, 1000L, 200)
        assertTrue(parts.size > 1)
        val joiner = CallLogic.Joiner()
        var got: CallLogic.Signal? = null
        // Ulte kram mein aayein tab bhi
        parts.reversed().forEachIndexed { i, p ->
            val s = joiner.add(p)
            if (i < parts.size - 1) assertNull(s) else got = s
        }
        assertEquals(sdp, got?.body)
        assertEquals("offer", got?.kind)
        assertEquals("call1", got?.call)
        assertTrue(got?.video == true)
        assertEquals(1000L, got?.at)
    }

    @Test fun everyPartFitsTheRelayAfterLocking() {
        CallLogic.parts("abcdef0123", "offer", true, sdp + sdp + sdp, 1L).forEach { part ->
            val locked = ShareLogic.lock("ABCDEFGHJKLMNPQR", ShareLogic.packet("device12", "call", listOf(part)))
            assertTrue("too big: " + locked.length, locked.toByteArray().size < 4096)
        }
    }

    @Test fun shortSignalsHaveNoBody() {
        val parts = CallLogic.parts("c", "bye", false, "", 5L)
        assertEquals(1, parts.size)
        val s = CallLogic.Joiner().add(parts[0])
        assertEquals("bye", s?.kind)
        assertEquals("", s?.body)
        assertFalse(s?.video == true)
    }

    @Test fun signalsSurviveThePacket() {
        val part = CallLogic.parts("c9", "answer", false, "v=0", 7L)[0]
        val back = ShareLogic.parsePacket(ShareLogic.packet("dev", "call", listOf(part)))
        assertEquals("call", back?.type)
        assertEquals("v=0", CallLogic.Joiner().add(back!!.items[0])?.body)
    }

    @Test fun brokenPartsAreIgnored() {
        val j = CallLogic.Joiner()
        assertNull(j.add(org.json.JSONObject().put("k", "offer")))
        assertNull(j.add(org.json.JSONObject().put("call", "c").put("k", "offer").put("i", 5).put("n", 2)))
        assertNull(j.add(org.json.JSONObject().put("call", "c").put("k", "offer").put("i", 0).put("n", 1).put("d", "###")))
    }

    @Test fun oldSignalsAreNotFresh() {
        assertTrue(CallLogic.fresh(100_000L, 130_000L))
        assertTrue(CallLogic.fresh(130_000L, 100_000L))
        assertFalse(CallLogic.fresh(100_000L, 100_000L + CallLogic.FRESH_MS + 1))
    }

    @Test fun clockAndTurnPassword() {
        assertEquals("0:05", CallLogic.clock(5))
        assertEquals("12:00", CallLogic.clock(720))
        assertEquals("86500", CallLogic.turnUser(100))
        // HMAC-SHA1("key", "The quick brown fox jumps over the lazy dog") ka jaana-maana jawab
        assertEquals("3nybhbi3iqa8ino29wqQcBydtNk=", CallLogic.turnPass("key", "The quick brown fox jumps over the lazy dog"))
    }
}
