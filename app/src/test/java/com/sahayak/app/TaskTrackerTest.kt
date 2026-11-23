package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTrackerTest {
    private val results = listOf("Search YouTube", "Pal Pal Dil Ke Paas - 4 minutes - 10M views - play video")
    private val player = listOf("Pause video", "Minimize", "Pal Pal Dil Ke Paas")
    private val yt = "com.google.android.youtube"

    private fun sig(labels: List<String>, app: String = yt) = TaskTracker.signature(app, labels)

    // TEST 1: normal kaam, koi warning nahi
    @Test fun normalRunHasNoWarnings() {
        val t = TaskTracker("play song", 0)
        val a = t.record("quick_action", "youtube_search song", true, true, sig(results), results, yt, 1)
        val b = t.record("tap_text", "Pal Pal Dil Ke Paas", true, true, sig(player), player, yt, 2)
        assertEquals(TaskTracker.Verdict.OK, a.verdict)
        assertEquals(TaskTracker.Verdict.OK, b.verdict)
        assertNull(b.advice)
        assertFalse(b.askPerson)
        assertTrue(t.doneSoFar().contains("tap_text"))
    }

    // TEST 2: tap hua par screen nahi badli = fail, agla tarika batao, shuru se nahi
    @Test fun failedClickGetsNextWayNotRestart() {
        val t = TaskTracker("play song", 0)
        t.record("quick_action", "youtube_search song", true, true, sig(results), results, yt, 1)
        val f = t.record("tap_text", "Pal Pal", true, false, sig(results), results, yt, 2)
        assertEquals(TaskTracker.Verdict.NO_CHANGE, f.verdict)
        assertTrue(f.advice!!.contains("tap_position"))
        assertTrue(t.doneSoFar().contains("quick_action"))
    }

    @Test fun missingTextSuggestsElementOrPosition() {
        val t = TaskTracker("x", 0)
        val f = t.record("tap_text", "Play", false, false, sig(results), results, yt, 1)
        assertEquals(TaskTracker.Verdict.FAILED, f.verdict)
        assertTrue(f.advice!!.contains("element number"))
    }

    // TEST 3: popup aur permission pehchano
    @Test fun popupsAndPermissionsAreDetected() {
        assertEquals(TaskTracker.Situation.PERMISSION,
            TaskTracker.detect(listOf("Allow YouTube to send notifications?", "Allow", "Don't allow"), yt))
        assertEquals(TaskTracker.Situation.POPUP, TaskTracker.detect(listOf("Try Premium", "No thanks"), yt))
        val t = TaskTracker("x", 0)
        val f = t.record("open_app", "YouTube", true, true, sig(listOf("Allow", "Don't allow")), listOf("Allow", "Don't allow"), yt, 1)
        assertTrue(f.advice!!.contains("Allow"))
    }

    // TEST 4: loading par wait, baar baar tap nahi
    @Test fun loadingScreenAdvisesWait() {
        val t = TaskTracker("x", 0)
        val loading = listOf("Loading")
        val f = t.record("tap_text", "Video", true, false, sig(loading), loading, yt, 1)
        assertTrue(f.advice!!.contains("wait"))
        val w1 = t.record("wait", "", true, null, sig(loading), loading, yt, 2)
        val w2 = t.record("wait", "", true, null, sig(loading), loading, yt, 3)
        val w3 = t.record("wait", "", true, null, sig(loading), loading, yt, 4)
        assertEquals(TaskTracker.Verdict.OK, w3.verdict) // thodi baar wait theek hai
        assertEquals(TaskTracker.Verdict.OK, w1.verdict)
        assertEquals(TaskTracker.Verdict.OK, w2.verdict)
    }

    // TEST 5: search me kuch nahi mila = query badlo
    @Test fun noResultsAdvisesNewQuery() {
        val t = TaskTracker("x", 0)
        val empty = listOf("Search", "No results found", "Try different keywords")
        val f = t.record("press_enter", "", true, true, sig(empty), empty, yt, 1)
        assertEquals(TaskTracker.Situation.NOT_FOUND, f.situation)
        assertTrue(f.advice!!.contains("search words"))
    }

    // TEST 6: ek hi bekaar kaam baar baar = chakkar pakdo
    @Test fun repeatedUselessTapIsCaught() {
        val t = TaskTracker("x", 0)
        t.record("get_screen", "", true, null, sig(results), results, yt, 0)
        t.record("tap_text", "Play", true, false, sig(results), results, yt, 1)
        t.record("tap_text", "Play", true, false, sig(results), results, yt, 2)
        val third = t.record("tap_text", "Play", true, false, sig(results), results, yt, 3)
        assertEquals(TaskTracker.Verdict.REPEAT, third.verdict)
        assertTrue(third.advice!!.contains("Stop doing"))
    }

    @Test fun bouncingBetweenTwoScreensIsCaught() {
        val t = TaskTracker("x", 0)
        val a = listOf("Screen A"); val b = listOf("Screen B")
        t.record("tap_text", "B", true, true, sig(a), a, yt, 1)
        t.record("tap_text", "B", true, true, sig(b), b, yt, 2)
        t.record("go_back", "", true, true, sig(a), a, yt, 3)
        val f = t.record("tap_text", "B", true, true, sig(b), b, yt, 4)
        assertEquals(TaskTracker.Verdict.BOUNCE, f.verdict)
    }

    @Test fun recoveryLimitAsksPersonWithClearMessage() {
        val t = TaskTracker("x", 0, TaskTracker.Limits(maxRecoveries = 2))
        t.record("open_app", "YouTube", true, true, sig(results), results, yt, 0)
        var last: TaskTracker.Feedback? = null
        repeat(4) { last = t.record("tap_position", "500,500", true, false, sig(results), results, yt, it + 1L) }
        assertEquals(TaskTracker.Verdict.GIVE_UP, last!!.verdict)
        assertTrue(last!!.askPerson)
        val msg = t.helpMessage(null)
        assertTrue(msg.contains("Done so far"))
        assertTrue(msg.contains("open_app"))
        assertTrue(msg.contains("I tried"))
        assertFalse(msg.contains("loop"))
    }

    // TEST 7: app band ho gaya = home screen pehchano
    @Test fun homeScreenAfterFreezeIsDetected() {
        assertEquals(TaskTracker.Situation.HOME_SCREEN,
            TaskTracker.detect(listOf("Phone", "Messages", "Chrome", "Camera"), "com.android.launcher3"))
        assertEquals(TaskTracker.Situation.NOT_RESPONDING,
            TaskTracker.detect(listOf("WhatsApp isn't responding", "Close app", "Wait"), "android"))
    }

    // TEST 8: network error
    @Test fun networkErrorIsDetected() {
        assertEquals(TaskTracker.Situation.NETWORK,
            TaskTracker.detect(listOf("You're offline", "Check your connection", "Retry"), yt))
    }

    // TEST 10: login/captcha par insaan ko bulao, password kabhi nahi
    @Test fun loginAndCaptchaNeedPerson() {
        val t = TaskTracker("x", 0)
        val login = listOf("Sign in to continue", "Email", "Next")
        val f = t.record("open_url", "site", true, true, sig(login), login, "com.android.chrome", 1)
        assertTrue(f.askPerson)
        assertEquals(TaskTracker.Situation.LOGIN, f.situation)
        assertTrue(t.helpMessage(f.situation).contains("sign in yourself"))
        assertEquals(TaskTracker.Situation.CAPTCHA, TaskTracker.detect(listOf("I'm not a robot"), "x"))
    }

    // WhatsApp: message do baar kabhi nahi
    @Test fun uncertainSendWarnsBeforeResending() {
        val wa = "com.whatsapp"
        val chat = listOf("Rahul", "Type a message", "Send")
        val t = TaskTracker("msg rahul", 0)
        t.record("open_whatsapp_chat", "Rahul", true, true, sig(chat, wa), chat, wa, 1)
        val send = t.record("tap_text", "Send", true, false, sig(chat, wa), chat, wa, 2)
        assertTrue(send.advice!!.contains("Never send it twice"))
        assertEquals(1, t.sendTaps)
        assertTrue(TaskTracker.isSend("press_enter", "", wa))
        assertFalse(TaskTracker.isSend("tap_text", "Sender name", wa))
    }

    @Test fun signatureIgnoresClockDigits() {
        assertEquals(sig(listOf("10:41", "Chats")), sig(listOf("10:42", "Chats")))
        assertNotNull(sig(emptyList()))
    }

    @Test fun timeoutGivesUp() {
        val t = TaskTracker("x", 0, TaskTracker.Limits(timeoutMs = 1000))
        val f = t.record("tap_text", "A", true, true, sig(results), results, yt, 5000)
        assertEquals(TaskTracker.Verdict.GIVE_UP, f.verdict)
    }
}
