package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardTest {
    @Test fun phoneOrdersAreScreenWork() {
        assertEquals(TaskBoard.Kind.SCREEN, TaskBoard.classify("Play Pal Pal Dil Ke Paas on YouTube"))
        assertEquals(TaskBoard.Kind.SCREEN, TaskBoard.classify("Find the API key settings on Vercel"))
        assertEquals(TaskBoard.Kind.SCREEN, TaskBoard.classify("send hi to Atul on whatsapp"))
        assertEquals(TaskBoard.Kind.SCREEN, TaskBoard.classify("set an alarm for 6"))
        assertEquals(TaskBoard.Kind.SCREEN, TaskBoard.classify("something unclear"))
    }

    @Test fun questionsAreResearch() {
        assertEquals(TaskBoard.Kind.RESEARCH,
            TaskBoard.classify("Find the complete list of General Medicine faculty at Solapur Government Medical College"))
        assertEquals(TaskBoard.Kind.RESEARCH, TaskBoard.classify("What is the NEET PG cutoff for Maharashtra"))
        assertEquals(TaskBoard.Kind.RESEARCH, TaskBoard.classify("research UP NEET PG registration date"))
        assertTrue(TaskBoard.isDeep("find faculty list"))
        assertFalse(TaskBoard.isDeep("what is the time in London"))
    }

    @Test fun screenWorkWaitsInLine() {
        val board = TaskBoard()
        val first = board.add("open youtube", TaskBoard.Kind.SCREEN, false, 1)
        board.start(first, 1)
        board.add("send hi to atul", TaskBoard.Kind.SCREEN, false, 2)
        board.add("open settings", TaskBoard.Kind.SCREEN, true, 3)
        assertEquals(2, board.waitingScreen())
        board.end(first, TaskBoard.Status.DONE, 4)
        val next = board.takeNextScreen(5)
        assertEquals("send hi to atul", next?.text)
        assertEquals(next, board.runningScreen())
        assertEquals(1, board.waitingScreen())
    }

    @Test fun researchRunsBesideScreenWork() {
        val board = TaskBoard()
        board.start(board.add("open youtube", TaskBoard.Kind.SCREEN, false, 1), 1)
        board.start(board.add("research faculty", TaskBoard.Kind.RESEARCH, false, 2), 2)
        board.add("open settings", TaskBoard.Kind.SCREEN, false, 3)
        assertEquals("1 task running, 1 waiting, 1 research", board.summary())
    }

    @Test fun shortTapStopsEverything() {
        val board = TaskBoard()
        val running = board.add("open youtube", TaskBoard.Kind.SCREEN, false, 1)
        board.start(running, 1)
        val research = board.add("research faculty", TaskBoard.Kind.RESEARCH, false, 2)
        board.start(research, 2)
        board.add("open settings", TaskBoard.Kind.SCREEN, false, 3)
        board.stopAll(4)
        assertTrue(board.isStopped(running))
        assertTrue(board.isStopped(research))
        assertNull(board.takeNextScreen(5))
        assertEquals("", board.summary())
        // Band kaam ka result baad mein aaye to bhi status nahi badalta
        board.end(research, TaskBoard.Status.DONE, 6, "late")
        assertTrue(board.isStopped(research))
    }
}
