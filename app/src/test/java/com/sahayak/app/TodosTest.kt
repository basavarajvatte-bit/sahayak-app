package com.sahayak.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TodosTest {
    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val now = at(15, 14, 30)

    @Test fun namesDaysAndTimes() {
        assertEquals("Today", Todos.dayName(at(15, 23), now))
        assertEquals("Tomorrow", Todos.dayName(at(16, 1), now))
        assertEquals("Yesterday", Todos.dayName(at(14, 9), now))
        assertEquals("Wed 18 Nov", Todos.dayName(at(18, 9), now))
        assertEquals("5:30 PM", Todos.clock(at(15, 17, 30)))
    }

    @Test fun describesEachTask() {
        val old = JSONObject().put("text", "Old task")
        assertEquals("Today", Todos.whenText(old, now))
        assertEquals("Today", Todos.groupOf(old, now))
        val evening = JSONObject().put("at", at(15, 18)).put("timed", true)
        assertEquals("Today 6:00 PM", Todos.whenText(evening, now))
        val morning = JSONObject().put("at", at(15, 9)).put("timed", true)
        assertEquals("Overdue · Today 9:00 AM", Todos.whenText(morning, now))
        assertEquals("Today", Todos.groupOf(morning, now))
        val tomorrow = JSONObject().put("at", at(16, 12)).put("timed", false)
        assertEquals("Tomorrow", Todos.whenText(tomorrow, now))
        assertFalse(Todos.timed(tomorrow))
        val missed = JSONObject().put("at", at(13, 12)).put("timed", false)
        assertEquals("Overdue", Todos.groupOf(missed, now))
        assertTrue(Todos.whenText(missed, now).startsWith("Overdue"))
    }
}
