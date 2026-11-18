package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLogicTest {
    @Test fun zoomSteps() {
        assertEquals(1.5f, PdfLogic.zoomIn(1.0f))
        assertEquals(3.0f, PdfLogic.zoomIn(3.0f))
        assertEquals(1.0f, PdfLogic.zoomOut(1.5f))
        assertEquals(1.0f, PdfLogic.zoomOut(1.0f))
        assertEquals(2.0f, PdfLogic.doubleTapZoom(1.0f))
        assertEquals(1.0f, PdfLogic.doubleTapZoom(2.0f))
        assertEquals(1.0f, PdfLogic.doubleTapZoom(1.5f))
    }

    @Test fun labelsAndTitles() {
        assertEquals("Page 3 / 12", PdfLogic.pageLabel(3, 12))
        assertEquals("Page 12 / 12", PdfLogic.pageLabel(20, 12))
        assertEquals("", PdfLogic.pageLabel(1, 0))
        assertEquals("Bill march", PdfLogic.title("Bill march.pdf"))
        assertEquals("report", PdfLogic.title("/x/y/report.PDF"))
        assertEquals("PDF", PdfLogic.title(null))
    }

    @Test fun bitmapKeepsShapeAndLimit() {
        val (w, h) = PdfLogic.bitmapSize(1000, 1f, 595, 842)
        assertEquals(1000, w)
        assertEquals(1415, h)
        val (bw, bh) = PdfLogic.bitmapSize(1080, 3f, 595, 842)
        assertTrue(bw.toLong() * bh <= 12_000_000)
        assertEquals(842.0 / 595, bh.toDouble() / bw, 0.01)
    }

    @Test fun findIgnoresCaseAndSpaces() {
        val pages = listOf("Total amount Rs 500", "nothing here", "TOTAL  bill and total")
        val m = PdfLogic.find(pages, "  total ")
        assertEquals(listOf(0, 2, 2), m.map { it.page })
        assertEquals(0, m[0].start)
        assertEquals(5, m[0].end)
        assertEquals(2, PdfLogic.find(pages, "Rs 500").size + PdfLogic.find(pages, "bill").size)
        assertTrue(PdfLogic.find(pages, "").isEmpty())
        assertEquals("Nahi mila", PdfLogic.resultLabel(0, 0))
        assertEquals("3 / 17", PdfLogic.resultLabel(2, 17))
    }

    @Test fun boxesJoinOneLine() {
        val b = PdfLogic.Box(0.1f, 0.1f, 0.12f, 0.12f)
        val c = PdfLogic.Box(0.12f, 0.1f, 0.14f, 0.12f)
        val next = PdfLogic.Box(0.1f, 0.2f, 0.12f, 0.22f)
        val out = PdfLogic.boxes(listOf(b, c, null, next), 0, 4)
        assertEquals(2, out.size)
        assertEquals(PdfLogic.Box(0.1f, 0.1f, 0.14f, 0.12f), out[0])
        assertEquals(next, out[1])
    }
}
