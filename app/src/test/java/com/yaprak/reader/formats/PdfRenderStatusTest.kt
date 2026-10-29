package com.yaprak.reader.formats

import com.yaprak.reader.formats.pdf.PdfRenderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRenderStatusTest {
    @Test fun `both real pages must finish before a spread becomes visible and tickets are independent`() {
        val status = PdfRenderStatus(7, 1)
        status.setDoublePage(true)
        assertEquals(listOf(1, 2), status.requestedPages())
        val left = status.startRender(1)!!
        val right = status.startRender(2)!!
        assertNull(status.startRender(0)); assertNull(status.startRender(3))
        status.finishRender(2, right)
        assertNull(status.renderedPage.value)
        status.finishRender(1, left)
        assertEquals(1, status.renderedPage.value)
        status.failRender(2, right)
        assertFalse(status.renderFailed.value)
    }

    @Test fun `failure in one pane survives the other pane start or success until its own explicit retry`() {
        val status = PdfRenderStatus(3, 0)
        status.setDoublePage(true)
        val left = status.startRender(0)!!
        status.failRender(0, left)
        val right = status.startRender(1)!!
        assertTrue(status.renderFailed.value)
        status.finishRender(1, right)
        assertTrue(status.renderFailed.value); assertNull(status.renderedPage.value)
        val retry = status.startRender(0)!!
        assertFalse(status.renderFailed.value); assertNull(status.renderedPage.value)
        status.finishRender(0, left)
        assertNull(status.renderedPage.value)
        status.finishRender(0, retry)
        assertEquals(0, status.renderedPage.value)
    }

    @Test fun `wide to narrow to wide rejects old same-page tickets and preserves the exact selected anchor`() {
        val status = PdfRenderStatus(5, 3)
        status.setDoublePage(true)
        val left = status.startRender(3)!!; val right = status.startRender(4)!!
        status.finishRender(3, left); status.finishRender(4, right)
        assertEquals(3, status.renderedPage.value)
        status.setDoublePage(false)
        assertEquals(listOf(3), status.requestedPages()); assertNull(status.renderedPage.value)
        status.finishRender(4, right)
        status.setDoublePage(true)
        status.finishRender(3, left); status.finishRender(4, right)
        assertNull(status.renderedPage.value)
        status.finishRender(3, status.startRender(3)!!)
        status.finishRender(4, status.startRender(4)!!)
        assertEquals(3, status.renderedPage.value)
    }

    @Test fun `spread turns visit every pair and the last odd page without repeating the completed last pair`() {
        val status = PdfRenderStatus(5, 0)
        status.setDoublePage(true)
        assertFalse(status.turn(-1)); assertFalse(status.turn(Int.MAX_VALUE))
        assertTrue(status.turn(1)); assertEquals(listOf(2, 3), status.requestedPages())
        assertTrue(status.turn(1)); assertEquals(listOf(4), status.requestedPages())
        status.finishRender(4, status.startRender(4)!!)
        assertEquals(4, status.renderedPage.value)
        assertFalse(status.turn(1)); assertTrue(status.turn(-1))
        assertEquals(listOf(2, 3), status.requestedPages())
        assertTrue(status.goTo(3)); assertEquals(listOf(3, 4), status.requestedPages())
        assertFalse(status.turn(1))
        status.setDoublePage(false)
        assertTrue(status.turn(1)); assertEquals(listOf(4), status.requestedPages())
    }

    @Test fun `single-page books never enable a spread and all indices remain inside real page count`() {
        val status = PdfRenderStatus(1, 0)
        status.setDoublePage(true)
        assertFalse(status.doublePage.value); assertEquals(listOf(0), status.requestedPages())
        assertFalse(status.turn(1)); assertFalse(status.turn(-1)); assertFalse(status.goTo(1))
        status.finishRender(0, status.startRender(0)!!)
        assertEquals(0, status.renderedPage.value)
        status.invalidate()
        assertNull(status.renderedPage.value)
    }

    @Test fun `rerender of one pane suspends the spread until it finishes without redrawing its valid neighbor`() {
        val status = PdfRenderStatus(2, 0)
        status.setDoublePage(true)
        val left = status.startRender(0)!!; val right = status.startRender(1)!!
        status.finishRender(0, left); status.finishRender(1, right)
        val newRight = status.startRender(1)!!
        assertNull(status.renderedPage.value)
        status.finishRender(1, right); assertNull(status.renderedPage.value)
        status.finishRender(1, newRight); assertEquals(0, status.renderedPage.value)
        status.invalidate()
        status.finishRender(0, left); status.finishRender(1, newRight)
        assertNull(status.renderedPage.value)
    }

    @Test fun `page label clears while a new page loads and stays clear after failure`() {
        val status = PdfRenderStatus(8, 2)
        assertNull(status.renderedPage.value)
        val first = status.startRender(2)!!
        status.finishRender(2, first)
        assertEquals(2, status.renderedPage.value)

        assertTrue(status.goTo(5))
        assertNull(status.renderedPage.value)
        status.finishRender(2, first)
        assertNull(status.renderedPage.value)
        status.startRender(5) // Simulate a render that fails without calling finishRender.
        assertNull(status.renderedPage.value)
    }

    @Test fun `same page navigation keeps a completed page and an invalid request changes nothing`() {
        val status = PdfRenderStatus(3, 1)
        status.finishRender(1, status.startRender(1)!!)
        assertTrue(status.goTo(1))
        assertEquals(1, status.renderedPage.value)
        assertFalse(status.goTo(3))
        assertEquals(1, status.renderedPage.value)
        assertFalse(status.move(0))
        assertEquals(1, status.renderedPage.value)
    }

    @Test fun `rapid requests reject an old render even when they return to the same page`() {
        val status = PdfRenderStatus(4, 0)
        val obsolete = status.startRender(0)!!
        assertTrue(status.move(1))
        assertTrue(status.move(-1))
        status.finishRender(0, obsolete)
        assertNull(status.renderedPage.value)
        val latest = status.startRender(0)!!
        status.finishRender(0, latest)
        assertEquals(0, status.renderedPage.value)
        assertFalse(status.move(-1))
        assertEquals(0, status.renderedPage.value)
    }

    @Test fun `rerender clears page until a matching render completes`() {
        val status = PdfRenderStatus(2, 1)
        val first = status.startRender(1)!!
        status.finishRender(1, first)
        val second = status.startRender(1)!!
        assertNull(status.renderedPage.value)
        status.finishRender(1, first)
        assertNull(status.renderedPage.value)
        status.finishRender(1, second)
        assertEquals(1, status.renderedPage.value)
    }

    @Test fun `latest failure is observable and an explicit retry clears it before succeeding`() {
        val status = PdfRenderStatus(3, 1)
        assertFalse(status.renderFailed.value)
        val failed = status.startRender(1)!!
        status.failRender(1, failed)
        assertTrue(status.renderFailed.value)
        assertNull(status.renderedPage.value)
        assertFalse(status.goTo(3))
        assertTrue(status.goTo(1))
        assertTrue(status.renderFailed.value)
        val retried = status.startRender(1)!!
        assertFalse(status.renderFailed.value)
        assertNull(status.renderedPage.value)
        status.finishRender(1, retried)
        assertFalse(status.renderFailed.value)
        assertEquals(1, status.renderedPage.value)
    }

    @Test fun `moving to another page clears its predecessor failure and rejects late callbacks`() {
        val status = PdfRenderStatus(3, 0)
        val previous = status.startRender(0)!!
        status.failRender(0, previous)
        assertTrue(status.renderFailed.value)
        assertTrue(status.goTo(1))
        assertFalse(status.renderFailed.value)
        status.failRender(0, previous)
        status.finishRender(0, previous)
        assertFalse(status.renderFailed.value)
        assertNull(status.renderedPage.value)
        status.finishRender(1, status.startRender(1)!!)
        assertEquals(1, status.renderedPage.value)
    }

    @Test fun `an obsolete rerender cannot erase or replace the latest failure`() {
        val status = PdfRenderStatus(2, 1)
        val obsolete = status.startRender(1)!!
        val current = status.startRender(1)!!
        status.failRender(1, current)
        status.finishRender(1, obsolete)
        status.failRender(1, obsolete)
        assertTrue(status.renderFailed.value)
        assertNull(status.renderedPage.value)
        val retry = status.startRender(1)!!
        status.finishRender(1, retry)
        status.failRender(1, current)
        assertFalse(status.renderFailed.value)
        assertEquals(1, status.renderedPage.value)
    }

    @Test fun `a completed render rejects a late failure even from its own ticket`() {
        val status = PdfRenderStatus(2, 0)
        val complete = status.startRender(0)!!
        status.finishRender(0, complete)
        status.failRender(0, complete)
        assertFalse(status.renderFailed.value)
        assertEquals(0, status.renderedPage.value)
    }
}
