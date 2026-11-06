package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutBookTest {
    private fun action(text: String) = ShortcutBook.match(text)?.action

    @Test fun system() {
        assertEquals("torch_on", action("turn on flashlight"))
        assertEquals("volume_up", action("Volume up"))
        assertEquals("lock", action("lock the phone"))
        assertEquals("scroll_down", action("scroll down"))
        assertEquals("camera", action("take a photo"))
        assertEquals("battery", action("battery level"))
    }

    @Test fun search() {
        val hit = ShortcutBook.match("Search red shoes on Amazon")!!
        assertEquals("url", hit.action)
        assertTrue(hit.value.contains("amazon.in/s?k=red%20shoes"))
        assertEquals("maps_search", action("find hotels on Google Maps"))
        assertTrue(ShortcutBook.match("search cats on youtube")!!.value.contains("youtube"))
        assertTrue(ShortcutBook.match("Google best phones")!!.value.contains("google.com/search"))
    }

    @Test fun open() {
        assertEquals("open_named", action("open YouTube"))
        assertEquals("open_named", action("open the WhatsApp app"))
        assertEquals("app_category", action("open gallery"))
        assertEquals("url", action("open wikipedia.org"))
        assertEquals("open_settings", action("open wifi settings"))
        assertEquals("open_settings", action("open settings"))
    }

    @Test fun clock() {
        assertEquals("6:30", ShortcutBook.match("set alarm for 6:30 am")!!.value)
        assertEquals("18:0", ShortcutBook.match("wake me up at 6 pm")!!.value)
        assertEquals("0:0", ShortcutBook.match("set alarm for 12 am")!!.value)
        assertEquals("300", ShortcutBook.match("set timer for 5 minutes")!!.value)
        assertEquals("reminder", action("remind me in 10 minutes to call the shop"))
        assertEquals("reminder", action("remind me at 6 pm to close the shop"))
    }

    @Test fun answers() {
        assertEquals("100", ShortcutBook.match("what is 25 times 4")!!.value)
        assertEquals("14", ShortcutBook.match("calculate 2 + 3 * 4")!!.value)
        assertEquals("20", ShortcutBook.match("(2+3)*4")!!.value)
        assertEquals("50", ShortcutBook.match("10 percent of 500")!!.value)
        assertEquals("1 km", ShortcutBook.match("convert 1000 m to km")!!.value)
        assertEquals("212", ShortcutBook.match("100 celsius to fahrenheit")!!.value)
        assertNull(ShortcutBook.evaluate("5/0"))
        assertNotNull(ShortcutBook.match("flip a coin"))
    }

    @Test fun messages() {
        assertEquals("dial", action("call Mom"))
        assertEquals("Mom|hello", ShortcutBook.match("text Mom saying hello")!!.value)
        assertEquals("compose_email", action("email a@b.com about meeting"))
        assertEquals("calendar", action("add event Doctor visit"))
    }

    @Test fun todo() {
        assertEquals("Call supplier", ShortcutBook.match("add to do Call supplier")!!.value)
        assertEquals("Buy milk", ShortcutBook.match("add Buy milk to my to-do list")!!.value)
    }

    @Test fun repeatStaysSeparate() {
        assertEquals("", LocalCommands.repeatTarget("repeat"))
        assertEquals("Check weather", LocalCommands.repeatTarget("Check weather repeat"))
    }

    @Test fun whatsappFirst() {
        val command = LocalCommands.parse("Mom ko WhatsApp par hello bhejo")
        assertTrue(command is LocalCommands.Command.WhatsApp)
    }
}
