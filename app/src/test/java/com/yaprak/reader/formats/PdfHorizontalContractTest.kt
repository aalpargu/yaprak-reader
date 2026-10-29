package com.yaprak.reader.formats

import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test

/** Geometry/ownership contracts only; native Compose input and PDF decoding need device acceptance. */
class PdfHorizontalContractTest {
    @Test fun changingAxisKeepsTheExactPageButRevokesOldRastersDrawsAndPendingPointCommands() {
        val s = PdfRenderStatus(4, 1)
        s.setScrolling(true); s.registerScrollItem(1); s.updateScrollViewport(listOf(1), 1)
        val old = s.startRender(1)!!; s.finishRender(1, old); s.finishDraw(1, old, 600_000)
        s.goTo(1, 700_000)
        val command = s.scrollNavigation.value
        s.setScrolling(true, horizontal = true)
        assertTrue(s.scrolling); assertTrue(s.horizontalScrolling); assertEquals(1, s.requestedPage.value)
        assertNull(s.drawnPosition.value); assertNull(s.renderedPage.value); assertNull(s.scrollNavigation.value.anchorY)
        assertTrue(s.scrollNavigation.value.revision > command.revision)
        s.finishRender(1, old); s.finishDraw(1, old, 600_000); assertNull(s.drawnPosition.value)
        assertNull(s.startRender(1)); s.registerScrollItem(1)
        val fresh = s.startRender(1)!!; s.finishRender(1, fresh)
        s.updateScrollViewport(listOf(1), 1, command.revision); s.finishDraw(1, fresh, 600_000)
        assertNull(s.drawnPosition.value)
        s.updateScrollViewport(listOf(1), 1); s.finishDraw(1, fresh, 50_000)
        assertEquals(PdfResumePosition(1, 50_000), s.drawnPosition.value)
    }
    @Test fun horizontalResumeWaitsForPreferencesAndReflowUsesItsLatestActualPaintedY() {
        val s = PdfRenderStatus(4, 1, 600_000)
        s.setScrolling(true, true)
        val viewport = PdfScrollViewport(400, 600, 96, 48, PdfPageCrop(), PdfFitMode.WIDTH)
        s.updateScrollGeometry(viewport); s.registerScrollItem(1)
        val ticket = s.startRender(1)!!; s.finishRender(1, ticket); s.updateScrollViewport(listOf(1), 1)
        s.finishDraw(1, ticket, 20_000); assertNull(s.drawnPosition.value)
        s.initialPreferencesApplied()
        val command = s.scrollNavigation.value
        assertEquals(600_000, command.anchorY)
        s.finishDraw(1, ticket, 600_000); assertNull(s.drawnPosition.value)
        s.updateScrollViewport(listOf(1), 1); s.finishDraw(1, ticket, 600_000)
        s.finishDraw(1, ticket, 710_000)
        s.updateScrollGeometry(viewport.copy(width = 240, crop = PdfPageCrop(10, 15)))
        assertEquals(710_000, s.scrollNavigation.value.anchorY); assertNull(s.drawnPosition.value)
        assertTrue(s.horizontalScrolling); assertFalse(s.scrollNavigationSettled)
    }
    @Test fun horizontalPrimaryUsesOnlyVisibleWidthWhilePrefetchAndPreparedNeighborsProveNothing() {
        assertEquals(1, pdfScrollPrimary(listOf(Triple(0, -390, 400), Triple(1, 18, 400), Triple(2, 426, 400)), 0, 400, 48))
        assertEquals(0, pdfScrollPrimary(listOf(Triple(0, -300, 400), Triple(1, 108, 400)), 0, 400, 48))
        val s = PdfRenderStatus(4, 0); s.setScrolling(true, true)
        s.registerScrollItem(0); s.registerScrollItem(1)
        val first = s.startRender(0)!!; s.finishRender(0, first)
        val next = s.startRender(1)!!; s.finishRender(1, next)
        s.updateScrollViewport(listOf(0), 0); s.finishDraw(1, next, 20_000)
        assertNull(s.drawnPage.value)
        s.finishDraw(0, first, 20_000); assertEquals(0, s.drawnPage.value)
        assertFalse(s.adoptScrollPrimary(1))
        s.updateScrollViewport(listOf(1), 1); assertNull(s.drawnPage.value)
        s.finishDraw(1, next, 30_000); assertEquals(PdfResumePosition(1, 30_000), s.drawnPosition.value)
    }
    @Test fun horizontalWidthFitHasReachableVerticalOverflowAndCropUsesOriginalCoordinates() {
        val page = PdfPageDimensions(200, 1200)
        assertEquals(2400, pdfScrollItemHeight(page, 400, 600, 96, PdfPageCrop(), PdfFitMode.WIDTH))
        val offset = pdfResumeScrollOffset(600_000, page, 400, 600, 96, 48, PdfPageCrop(), PdfFitMode.WIDTH)!!
        assertTrue(offset in 1..1800)
        val cropped = pdfResumeScrollOffset(600_000, page, 400, 600, 96, 48, PdfPageCrop(10, 15), PdfFitMode.WIDTH)!!
        assertTrue(cropped > 0); assertNotEquals(offset, cropped)
        assertEquals(600, pdfScrollItemHeight(page, 400, 600, 96, PdfPageCrop(), PdfFitMode.PAGE))
        assertNull(pdfScrollItemHeight(PdfPageDimensions(1, Int.MAX_VALUE), 400, 600, 96, PdfPageCrop(), PdfFitMode.WIDTH))
    }
    @Test fun repeatedSameAxisDoesNotResetAndPagedChoiceNeverRetainsHorizontalOrSpreadState() {
        val s = PdfRenderStatus(4, 2); s.setScrolling(true, true)
        val first = s.scrollNavigation.value
        s.setScrolling(true, true); assertEquals(first, s.scrollNavigation.value)
        s.setDoublePage(true); assertFalse(s.doublePage.value)
        s.setScrolling(false, true); assertFalse(s.scrolling); assertFalse(s.horizontalScrolling)
        s.setDoublePage(true); assertTrue(s.doublePage.value); assertEquals(listOf(2, 3), s.requestedPages())
        s.setScrolling(true, false); assertFalse(s.doublePage.value); assertFalse(s.horizontalScrolling)
        assertEquals(2, s.requestedPage.value)
    }
}
