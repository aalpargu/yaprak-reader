package com.yaprak.reader.formats

import com.yaprak.reader.formats.pdf.PdfRenderStatus
import org.junit.Assert.*
import org.junit.Test

/** Ticket admission tests; only the separate native host tests establish actual pixel visibility. */
class PdfVisibleRenderStatusTest {
    @Test fun bitmapPreparationAndAdjacentAcknowledgementNeverProveTheSelectedPrimaryDraw() {
        val status = PdfRenderStatus(5, 1)
        status.setDoublePage(true)
        val primary = status.startRender(1)!!; val adjacent = status.startRender(2)!!
        status.finishRender(1, primary)
        status.finishDraw(1, primary); assertNull(status.drawnPage.value)
        status.finishRender(2, adjacent)
        assertEquals(1, status.renderedPage.value); assertNull(status.drawnPage.value)
        status.finishDraw(2, adjacent); assertNull(status.drawnPage.value)
        status.finishDraw(1, primary); assertEquals(1, status.drawnPage.value)
    }

    @Test fun requestsAndSamePageAbaRejectBothOldPreparationAndOldNativeDrawTickets() {
        val status = PdfRenderStatus(4, 0)
        val old = status.startRender(0)!!; status.finishRender(0, old); status.finishDraw(0, old)
        assertEquals(0, status.drawnPage.value)
        assertTrue(status.goTo(1)); assertNull(status.drawnPage.value)
        assertTrue(status.goTo(0)); status.finishRender(0, old); status.finishDraw(0, old)
        assertNull(status.drawnPage.value)
        val latest = status.startRender(0)!!; status.finishRender(0, latest)
        status.finishDraw(0, old); assertNull(status.drawnPage.value)
        status.finishDraw(0, latest); assertEquals(0, status.drawnPage.value)
        status.finishDraw(1, latest); status.finishDraw(0, old)
        assertEquals(0, status.drawnPage.value)
    }

    @Test fun neighborLoadingAndFailureRevokeProofAndRequireAnotherPrimaryDrawAfterRetryPreparation() {
        val status = PdfRenderStatus(2, 0); status.setDoublePage(true)
        val primary = status.startRender(0)!!; val adjacent = status.startRender(1)!!
        status.finishRender(0, primary); status.finishRender(1, adjacent); status.finishDraw(0, primary)
        val failed = status.startRender(1)!!; assertNull(status.drawnPage.value)
        status.failRender(1, failed); status.finishDraw(0, primary)
        assertTrue(status.renderFailed.value); assertNull(status.drawnPage.value)
        val retry = status.startRender(1)!!; status.finishRender(1, retry)
        assertEquals(0, status.renderedPage.value); assertNull(status.drawnPage.value)
        status.finishDraw(1, retry); assertNull(status.drawnPage.value)
        status.finishDraw(0, primary); assertEquals(0, status.drawnPage.value)
        status.failRender(1, failed); assertEquals(0, status.drawnPage.value)
    }

    @Test fun visibilityRevocationRetainsPreparedPageButNeverReplaysDrawWithoutAnExplicitWitness() {
        val status = PdfRenderStatus(7, 3)
        val ticket = status.startRender(3)!!; status.finishRender(3, ticket); status.finishDraw(3, ticket)
        status.clearDraw(); assertEquals(3, status.renderedPage.value); assertNull(status.drawnPage.value)
        assertTrue(status.goTo(3)); assertFalse(status.move(0)); assertFalse(status.goTo(7))
        assertNull(status.drawnPage.value)
        status.finishDraw(3, ticket); assertEquals(3, status.drawnPage.value)
        status.invalidate(); status.finishRender(3, ticket); status.finishDraw(3, ticket)
        assertNull(status.renderedPage.value); assertNull(status.drawnPage.value)
    }

    @Test fun viewportSpreadTransitionsAndPrimaryRerenderRequireTheirOwnCurrentDraw() {
        val status = PdfRenderStatus(5, 3)
        val single = status.startRender(3)!!; status.finishRender(3, single); status.finishDraw(3, single)
        status.setDoublePage(true); assertNull(status.drawnPage.value)
        val primary = status.startRender(3)!!; val adjacent = status.startRender(4)!!
        status.finishRender(3, primary); status.finishRender(4, adjacent)
        status.finishDraw(3, single); assertNull(status.drawnPage.value)
        status.finishDraw(3, primary); assertEquals(3, status.drawnPage.value)
        val rerender = status.startRender(3)!!; status.finishRender(3, rerender)
        status.finishDraw(3, primary); assertNull(status.drawnPage.value)
        status.finishDraw(3, rerender); assertEquals(3, status.drawnPage.value)
        status.setDoublePage(false); assertEquals(listOf(3), status.requestedPages()); assertNull(status.drawnPage.value)
    }
}
