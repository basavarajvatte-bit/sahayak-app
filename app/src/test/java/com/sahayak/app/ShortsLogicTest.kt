package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ShortsLogicTest {
    private val details = listOf(
        "add details", "caption your short", "filmy doctor", "@trailerpecharcha-b6i", "visibility", "public",
        "select audience", "show more",
        "regardless of your location, you're legally required to comply with the children's online privacy protection act (coppa) and other laws. you're required to tell us whether your videos are made for kids.",
        "what's considered \"made for kids\"?", "save draft", "upload short"
    )

    @Test fun readsAddDetailsScreen() {
        assertTrue(ShortsLogic.isDetailsPage(details))
        assertEquals("upload short", ShortsLogic.finalButton(details))
        assertTrue(ShortsLogic.audienceUnset(details))
        assertFalse(ShortsLogic.audienceWrong(details, true))
        assertFalse(ShortsLogic.audienceWrong(details, false))
        assertFalse(ShortsLogic.isAudiencePage(details))
        assertFalse(ShortsLogic.isVisibilityPage(details))
        assertTrue(ShortsLogic.visibilityShown(details, "public"))
        assertFalse(ShortsLogic.visibilityShown(details, "private"))
        assertTrue(ShortsLogic.channelShown(details, "Filmy Doctor"))
        assertTrue(ShortsLogic.channelShown(details, "@TrailerpeCharcha-b6i"))
        assertFalse(ShortsLogic.channelShown(details, "Other channel"))
    }

    @Test fun understandsChannelLinks() {
        assertEquals("VarshaVatte", ShortsLogic.channelKey("https://www.youtube.com/@VarshaVatte"))
        assertEquals("VarshaVatte", ShortsLogic.channelKey("https://youtube.com/@VarshaVatte/shorts?si=abc"))
        assertEquals("VarshaVatte", ShortsLogic.channelKey(" @VarshaVatte "))
        assertEquals("filmy doctor", ShortsLogic.channelKey("filmy doctor"))
        assertEquals("@VarshaVatte", ShortsLogic.channelValue("https://www.youtube.com/@VarshaVatte/"))
        assertEquals("baby", ShortsLogic.channelValue(" baby "))
        val screen = listOf("baby 🤗 @varshavatte")
        assertTrue(ShortsLogic.channelShown(screen, "https://www.youtube.com/@VarshaVatte"))
        assertTrue(ShortsLogic.channelShown(screen, "baby"))
        assertTrue(ShortsLogic.channelShown(listOf("@varshavatte"), "@VarshaVatte"))
    }

    @Test fun captionIsNotMistakenForChannel() {
        assertFalse(ShortsLogic.channelShown(listOf("#baby #babydance", "filmy doctor"), "baby"))
        assertFalse(ShortsLogic.channelShown(listOf("babydance"), "baby"))
        assertFalse(ShortsLogic.channelShown(listOf("@varshavatte2"), "@VarshaVatte"))
    }

    @Test fun picksExactVideoInGallery() {
        val cell = "10_robot_dance.mp4 1 0:10"
        assertTrue(ShortsLogic.sameVideo(cell, "10_Robot_Dance.mp4"))
        assertFalse(ShortsLogic.sameVideo(cell, "Robot_Dance.mp4"))
        assertFalse(ShortsLogic.sameVideo("110_robot_dance.mp4 0:10", "10_Robot_Dance.mp4"))
        assertFalse(ShortsLogic.sameVideo(cell, ""))
    }

    @Test fun readsTaughtScreens() {
        assertTrue(ShortsLogic.isHome(listOf("home", "shorts", "create", "subscriptions")))
        assertTrue(ShortsLogic.isCamera(listOf("video", "short", "live", "post", "add")))
        assertTrue(ShortsLogic.isGallery(listOf("close", "create video")))
        assertTrue(ShortsLogic.isGallery(listOf("10_robot_dance.mp4 0:10")))
        assertFalse(ShortsLogic.isGallery(details))
    }

    @Test fun handleWorksWithoutAtSign() {
        assertTrue(ShortsLogic.channelShown(listOf("baby 🤗", "@varshavatte"), "VarshaVatte"))
        assertTrue(ShortsLogic.channelShown(listOf("baby 🤗 @varshavatte"), "VarshaVatte"))
        assertFalse(ShortsLogic.channelShown(listOf("filmy doctor", "@trailerpecharcha-b6i"), "VarshaVatte"))
    }

    @Test fun readsChannelsFromAccountList() {
        val list = listOf("accounts", "varsha@gmail.com", "baby 🤗", "@varshavatte", "filmy doctor", "@trailerpecharcha-b6i", "add account")
        assertEquals(listOf("@varshavatte", "@trailerpecharcha-b6i"), ShortsLogic.channelsFrom(list))
        assertEquals(listOf("baby 🤗", "filmy doctor"), ShortsLogic.channelsFrom(listOf("accounts", "varsha@gmail.com", "baby 🤗", "filmy doctor", "add account")))
    }

    @Test fun channelMatchIsStrict() {
        assertFalse(ShortsLogic.channelShown(listOf("baby songs"), "@baby"))
        assertFalse(ShortsLogic.channelShown(listOf("filmy doctor"), "@filmy"))
        assertFalse(ShortsLogic.channelShown(listOf("baby dance fun"), "baby"))
        assertFalse(ShortsLogic.channelShown(listOf("caption your short"), "short"))
        assertTrue(ShortsLogic.channelShown(listOf("@varshavatte•view channel"), "@VarshaVatte"))
        assertTrue(ShortsLogic.channelShown(listOf("varsha, @varshavatte, view channel"), "@VarshaVatte"))
    }

    @Test fun audienceMustBeReadBack() {
        assertFalse(ShortsLogic.audienceRight(details, true))
        assertFalse(ShortsLogic.audienceRight(listOf("add details", "upload short"), true))
        assertTrue(ShortsLogic.audienceRight(listOf("made for kids"), true))
        assertFalse(ShortsLogic.audienceRight(listOf("not made for kids"), true))
        assertTrue(ShortsLogic.audienceRight(listOf("not made for kids"), false))
    }

    @Test fun confirmsUploadLeftTheScreen() {
        assertFalse(ShortsLogic.leftUpload(details))
        assertFalse(ShortsLogic.leftUpload(emptyList()))
        assertTrue(ShortsLogic.leftUpload(listOf("home", "shorts", "create", "subscriptions")))
    }

    @Test fun feedTitleIsNotLimit() {
        assertFalse(ShortsLogic.limitHit(listOf("youtube shorts daily limit explained")))
        assertFalse(ShortsLogic.limitHit(listOf("home", "create", "you've reached your daily upload limit explained")))
    }

    @Test fun readsAudiencePage() {
        val page = listOf(ShortsLogic.norm("Yes, it’s made for kids"), ShortsLogic.norm("No, it’s not made for kids"))
        assertTrue(ShortsLogic.isAudiencePage(page))
        assertTrue(ShortsLogic.yesOption(page[0]))
        assertFalse(ShortsLogic.yesOption(page[1]))
        assertTrue(ShortsLogic.noOption(page[1]))
    }

    @Test fun seesWrongAudience() {
        assertTrue(ShortsLogic.audienceWrong(listOf("not made for kids"), true))
        assertFalse(ShortsLogic.audienceWrong(listOf("made for kids"), true))
        assertTrue(ShortsLogic.audienceWrong(listOf("made for kids"), false))
    }

    @Test fun buildsCaption() {
        assertEquals("#baby #babydance", ShortsLogic.caption("10_Robot_Dance.mp4", " #baby #babydance ", false))
        assertEquals("Robot Dance #baby #babydance", ShortsLogic.caption("10_Robot_Dance.mp4", "#baby #babydance", true))
        assertTrue(ShortsLogic.caption("x".repeat(300), "#baby", true).length <= ShortsLogic.MAX_CAPTION)
    }

    @Test fun spacesSlotsEvenly() {
        assertEquals(listOf(540, 900, 1260), ShortsLogic.slots(3, 540, 1260))
        assertEquals(listOf(540), ShortsLogic.slots(1, 540, 1260))
        assertEquals(listOf(540), ShortsLogic.slots(5, 540, 400))
    }

    private fun at(hour: Int, minute: Int, plusDays: Int = 0) = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 6, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, plusDays)
    }.timeInMillis

    @Test fun picksNextSlotWithoutBurst() {
        assertEquals(at(15, 0), ShortsLogic.nextSlot(at(12, 0), 3, 540, 1260))
        assertEquals(at(9, 0, 1), ShortsLogic.nextSlot(at(21, 0), 3, 540, 1260))
    }

    @Test fun seesDailyLimit() {
        assertTrue(ShortsLogic.limitHit(listOf("you've reached your daily upload limit")))
        assertFalse(ShortsLogic.limitHit(details))
    }
}
