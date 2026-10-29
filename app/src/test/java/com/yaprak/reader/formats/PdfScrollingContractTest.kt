package com.yaprak.reader.formats

import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test

class PdfScrollingContractTest {
    @Test fun originalAspectCropAndFitDecideTheWholeItemWithoutBitmapRoundingOrViewportClipping() {
        val page = PdfPageDimensions(100, 400)
        assertEquals(1200, pdfScrollItemHeight(page, 300, 600, 96, PdfPageCrop(), PdfFitMode.WIDTH))
        assertEquals(600, pdfScrollItemHeight(page, 300, 600, 96, PdfPageCrop(), PdfFitMode.PAGE))
        assertEquals(720, pdfScrollItemHeight(page, 300, 600, 96, PdfPageCrop(0, 20), PdfFitMode.WIDTH))
        assertEquals(2000, pdfScrollItemHeight(page, 300, 600, 96, PdfPageCrop(20, 0), PdfFitMode.WIDTH))
        assertEquals(96, pdfScrollItemHeight(PdfPageDimensions(1000, 1), 300, 600, 96, PdfPageCrop(), PdfFitMode.WIDTH))
        assertNull(pdfScrollItemHeight(page, 0, 600, 96, PdfPageCrop(), PdfFitMode.WIDTH))
        assertNull(pdfScrollItemHeight(page, 300, 0, 96, PdfPageCrop(), PdfFitMode.WIDTH))
    }
    @Test fun overlongWidthFitFailsWithoutPartialHeightAndPageFitRemainsARecovery() {
        assertEquals(32000, pdfScrollItemHeight(PdfPageDimensions(1, 100), 320, 600, 96, PdfPageCrop(), PdfFitMode.WIDTH))
        assertNull(pdfScrollItemHeight(PdfPageDimensions(1, 101), 320, 600, 96, PdfPageCrop(), PdfFitMode.WIDTH))
        assertNull(pdfScrollItemHeight(PdfPageDimensions(1, Int.MAX_VALUE), Int.MAX_VALUE, 600, 96, PdfPageCrop(), PdfFitMode.WIDTH))
        assertEquals(600, pdfScrollItemHeight(PdfPageDimensions(1, Int.MAX_VALUE), 320, 600, 96, PdfPageCrop(), PdfFitMode.PAGE))
        assertThrows(IllegalArgumentException::class.java) { PdfPageDimensions(0, 1) }
    }
    @Test fun upperReadingLineKeepsAShortExplicitTargetAndRejectsPrefetchAndOverflowArtifacts() {
        assertEquals(4, pdfScrollPrimary(listOf(Triple(4, 0, 96), Triple(5, 104, 800)), 0, 600, 48))
        assertEquals(5, pdfScrollPrimary(listOf(Triple(4, -95, 96), Triple(5, 9, 800)), 0, 600, 48))
        assertEquals(0, pdfScrollPrimary(listOf(Triple(99, 700, 100), Triple(0, -100, 800)), 0, 600, 48))
        assertEquals(2, pdfScrollPrimary(listOf(Triple(2, Int.MAX_VALUE - 20, 200)), Int.MAX_VALUE - 30, Int.MAX_VALUE, 48))
        assertNull(pdfScrollPrimary(emptyList(), 0, 600, 48))
        assertNull(pdfScrollPrimary(listOf(Triple(0, 0, 0)), 0, 600, 48))
    }
    private fun status() = PdfRenderStatus(100, 0).apply {
        setScrolling(true); registerScrollItem(0); registerScrollItem(1); updateScrollViewport(listOf(0, 1), 0)
    }
    @Test fun neighborPreparationNeverProvesOrRevokesTheVisiblePrimaryAndAdoptionRequiresItsOwnDraw() {
        val s = status(); val first = s.startRender(0)!!; s.finishRender(0, first)
        assertNull(s.drawnPage.value); s.finishDraw(0, first); assertEquals(0, s.drawnPage.value)
        val neighbor = s.startRender(1)!!; s.finishRender(1, neighbor); s.finishDraw(1, neighbor)
        assertEquals(0, s.drawnPage.value)
        assertTrue(s.adoptScrollPrimary(1)); assertEquals(1, s.renderedPage.value); assertNull(s.drawnPage.value)
        s.finishDraw(0, first); assertNull(s.drawnPage.value)
        s.finishDraw(1, neighbor); assertEquals(1, s.drawnPage.value)
        s.updateScrollViewport(emptyList(), null); s.finishDraw(1, neighbor); assertNull(s.drawnPage.value)
    }
    @Test fun failedNeighborDoesNotBlockTheCurrentPageAndItsRetryAndDisposalCannotReplayOldTickets() {
        val s = status(); val first = s.startRender(0)!!; s.finishRender(0, first); s.finishDraw(0, first)
        val old = s.startRender(1)!!; s.failRender(1, old)
        assertFalse(s.renderFailed.value); assertEquals(0, s.drawnPage.value)
        s.adoptScrollPrimary(1); assertTrue(s.renderFailed.value); assertNull(s.drawnPage.value)
        val retry = s.startRender(1)!!; s.finishRender(1, retry); s.finishDraw(1, old); assertNull(s.drawnPage.value)
        s.finishDraw(1, retry); assertEquals(1, s.drawnPage.value)
        s.unregisterScrollItem(1); s.finishRender(1, retry); s.finishDraw(1, retry)
        assertNull(s.drawnPage.value); assertNull(s.startRender(1)); assertFalse(s.preparedTicket(1, retry))
    }
    @Test fun explicitNavigationHasItsOwnRevisionAndVisibleAdoptionNeverIssuesAnotherJump() {
        val s = status(); val initial = s.scrollNavigation.value
        assertTrue(s.adoptScrollPrimary(1)); assertEquals(initial, s.scrollNavigation.value)
        assertTrue(s.goTo(1)); val first = s.scrollNavigation.value
        assertEquals(1, first.pageIndex); assertTrue(first.revision > initial.revision)
        assertTrue(s.goTo(1)); assertTrue(s.scrollNavigation.value.revision > first.revision)
        val latest = s.scrollNavigation.value
        assertFalse(s.goTo(-1)); assertFalse(s.goTo(100)); assertEquals(latest, s.scrollNavigation.value)
        assertFalse(s.adoptScrollPrimary(99)); assertEquals(1, s.requestedPage.value)
    }
    @Test fun changingModesKeepsTheExactPageButRequiresNewPreparationAndNeverTreatsAScrollAsASpread() {
        val s = status(); s.adoptScrollPrimary(1)
        val old = s.startRender(1)!!; s.finishRender(1, old); s.finishDraw(1, old)
        s.setDoublePage(true); assertFalse(s.doublePage.value)
        s.setScrolling(false); assertEquals(1, s.requestedPage.value); assertNull(s.drawnPage.value)
        s.finishRender(1, old); s.finishDraw(1, old); assertNull(s.drawnPage.value)
        s.setDoublePage(true); assertEquals(listOf(1, 2), s.requestedPages())
        s.setScrolling(true); assertFalse(s.doublePage.value); assertEquals(1, s.requestedPage.value)
        assertNull(s.startRender(1)); s.registerScrollItem(1); assertNotNull(s.startRender(1))
    }
}
