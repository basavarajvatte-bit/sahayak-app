package com.sahayak.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLogicTest {
    private fun item(id: String, mod: Long, done: Boolean = false, del: Boolean = false, text: String = "Kaam $id") =
        JSONObject().put("id", id).put("text", text).put("done", done).put("del", del).put("mod", mod)

    @Test fun codesAreCleanedAndShown() {
        val code = ShareLogic.newCode()
        assertEquals(16, code.length)
        assertEquals(code, ShareLogic.cleanCode(ShareLogic.showCode(code).lowercase() + " "))
        assertEquals("", ShareLogic.cleanCode("ABCD"))
        assertEquals("", ShareLogic.cleanCode("ABCD-EFGH-JKLM-NPQ0"))
        assertEquals("ABCD-EFGH-JKLM-NPQR", ShareLogic.showCode("ABCDEFGHJKLMNPQR"))
    }

    @Test fun topicHidesTheCode() {
        val topic = ShareLogic.topic("ABCDEFGHJKLMNPQR")
        assertEquals(43, topic.length)
        assertTrue(topic.all { it.isLetterOrDigit() })
        assertFalse(topic.contains("ABCD"))
        assertNotEquals(topic, ShareLogic.topic("ABCDEFGHJKLMNPQS"))
    }

    @Test fun onlyTheRightCodeOpensAMessage() {
        val locked = ShareLogic.lock("ABCDEFGHJKLMNPQR", "दूध लाना hai")
        assertFalse(locked.contains("hai"))
        assertEquals("दूध लाना hai", ShareLogic.unlock("ABCDEFGHJKLMNPQR", locked))
        assertNull(ShareLogic.unlock("ABCDEFGHJKLMNPQS", locked))
        assertNull(ShareLogic.unlock("ABCDEFGHJKLMNPQR", "not a message"))
        assertNull(ShareLogic.unlock("ABCDEFGHJKLMNPQR", ""))
    }

    @Test fun readsRelayLines() {
        assertNull(ShareLogic.parseLine("{\"id\":\"a\",\"event\":\"open\",\"topic\":\"t\"}"))
        assertNull(ShareLogic.parseLine("{\"id\":\"a\",\"event\":\"keepalive\"}"))
        assertNull(ShareLogic.parseLine("garbage"))
        val line = ShareLogic.parseLine("{\"id\":\"x1\",\"time\":1,\"event\":\"message\",\"topic\":\"t\",\"message\":\"hello\"}")
        assertEquals("x1", line?.id)
        assertEquals("hello", line?.message)
    }

    @Test fun packetsGoRoundTrip() {
        val plain = ShareLogic.packet("dev1", "items", listOf(item("a", 5)))
        val back = ShareLogic.parsePacket(plain)
        assertEquals("dev1", back?.from)
        assertEquals("items", back?.type)
        assertEquals("a", back?.items?.first()?.optString("id"))
        assertNull(ShareLogic.parsePacket("{}"))
        assertNull(ShareLogic.parsePacket("oops"))
        assertEquals(0, ShareLogic.parsePacket(ShareLogic.packet("dev1", "hello"))?.items?.size)
    }

    @Test fun bigListsAreSplitAndStayUnderTheRelayLimit() {
        val items = (1..200).map { item("id$it", it.toLong(), text = "क".repeat(120)) }
        val parts = ShareLogic.chunks(items)
        assertEquals(200, parts.sumOf { it.size })
        assertTrue(parts.size > 1)
        parts.forEach { part ->
            val locked = ShareLogic.lock("ABCDEFGHJKLMNPQR", ShareLogic.packet("device12", "items", part))
            assertTrue("message too big: " + locked.length, locked.toByteArray().size < 4096)
        }
        assertEquals(0, ShareLogic.chunks(emptyList()).size)
    }

    @Test fun newTasksFromTheOtherPhoneAreAdded() {
        val merged = ShareLogic.merge(listOf(item("a", 10)), listOf(item("b", 20)), 100)
        assertTrue(merged.changed)
        assertEquals(listOf("a", "b"), merged.items.map { it.optString("id") })
        assertEquals(listOf("b"), merged.added.map { it.optString("id") })
    }

    @Test fun laterChangeWins() {
        val done = ShareLogic.merge(listOf(item("a", 10)), listOf(item("a", 20, done = true)), 100)
        assertTrue(done.items.first().optBoolean("done"))
        assertEquals(1, done.finished.size)
        assertEquals(0, done.added.size)
        val stale = ShareLogic.merge(listOf(item("a", 30, done = true)), listOf(item("a", 20)), 100)
        assertFalse(stale.changed)
        assertTrue(stale.items.first().optBoolean("done"))
        val same = ShareLogic.merge(listOf(item("a", 10)), listOf(item("a", 10)), 100)
        assertFalse(same.changed)
        val tie = ShareLogic.merge(listOf(item("a", 10)), listOf(item("a", 10, del = true)), 100)
        assertTrue(tie.items.first().optBoolean("del"))
    }

    @Test fun removedTasksDoNotComeBack() {
        val merged = ShareLogic.merge(listOf(item("a", 50, del = true)), listOf(item("a", 10)), 100)
        assertFalse(merged.changed)
        assertTrue(merged.items.first().optBoolean("del"))
        assertEquals(0, merged.added.size)
    }

    @Test fun oldFinishedTasksAreCleaned() {
        val now = ShareLogic.KEEP_MS * 3
        val items = listOf(item("old", 1, done = true), item("gone", 1, del = true), item("live", 1), item("fresh", now - 5, done = true))
        assertEquals(listOf("live", "fresh"), ShareLogic.prune(items, now).map { it.optString("id") })
        // Bahut purana ho chuka kaam doosre phone se wapas nahi aata
        assertFalse(ShareLogic.merge(emptyList(), listOf(item("old", 1, done = true)), now).changed)
    }
}
