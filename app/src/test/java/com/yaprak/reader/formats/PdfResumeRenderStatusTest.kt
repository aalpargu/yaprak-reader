package com.yaprak.reader.formats

import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test

class PdfResumeRenderStatusTest {
    private fun prepare(status: PdfRenderStatus, page: Int): Long = status.startRender(page)!!.also { status.finishRender(page, it) }
    @Test fun currentNativeDrawUpdatesTheAnchorWithinOnePageWhilePreparationAndMissingGeometryDoNot() {
        val status = PdfRenderStatus(3, 1)
        status.setScrolling(true); status.registerScrollItem(1); status.updateScrollViewport(listOf(1), 1)
        val ticket = prepare(status, 1)
        assertNull(status.drawnPosition.value)
        status.finishDraw(1, ticket, 300_000, positionAvailable = false)
        assertNull(status.drawnPosition.value)
        status.finishDraw(1, ticket, 300_000)
        assertEquals(PdfResumePosition(1, 300_000), status.drawnPosition.value)
        status.finishDraw(1, ticket, 450_000)
        assertEquals(1, status.drawnPage.value)
        assertEquals(PdfResumePosition(1, 450_000), status.drawnPosition.value)
        status.clearDraw(); assertNull(status.drawnPage.value); assertNull(status.drawnPosition.value)
    }
    @Test fun initialAnchorWaitsForAuthoritativePreferencesAndMatchingCompletedScrollThenAnotherDraw() {
        val status = PdfRenderStatus(3, 1, 650_000)
        var ticket = prepare(status, 1); status.finishDraw(1, ticket)
        assertEquals(1, status.drawnPage.value); assertNull(status.drawnPosition.value)
        status.setScrolling(true); status.registerScrollItem(1)
        status.updateScrollViewport(listOf(1), 1); ticket = prepare(status, 1)
        status.finishDraw(1, ticket, 10_000); assertNull(status.drawnPosition.value)
        val before = status.scrollNavigation.value
        status.initialPreferencesApplied()
        val restore = status.scrollNavigation.value
        assertTrue(restore.revision > before.revision); assertEquals(650_000, restore.anchorY)
        status.updateScrollViewport(listOf(1), 1, before.revision)
        status.finishDraw(1, ticket, 10_000); assertNull(status.drawnPosition.value)
        status.updateScrollViewport(listOf(1), 1, restore.revision)
        assertNull(status.drawnPosition.value)
        status.finishDraw(1, ticket, 650_000)
        assertEquals(PdfResumePosition(1, 650_000), status.drawnPosition.value)
        status.initialPreferencesApplied(); assertEquals(restore, status.scrollNavigation.value)
    }
    @Test fun settledResumeRetainsItsUndrawnTargetAcrossChromeResizeUntilAnActualNativePointTakesOver() {
        val viewport = PdfScrollViewport(400, 800, 96, 48, PdfPageCrop(), PdfFitMode.WIDTH)
        val status = PdfRenderStatus(3, 1, 650_000)
        status.setScrolling(true); status.updateScrollGeometry(viewport); status.registerScrollItem(1)
        val ticket = prepare(status, 1)
        status.initialPreferencesApplied()
        val original = status.scrollNavigation.value
        status.updateScrollViewport(listOf(1), 1, original.revision)
        assertTrue(status.scrollNavigationSettled)
        assertNull(status.drawnPosition.value)

        // T1 can restore visible controls after placement but before PdfDrawProofView paints.
        status.updateScrollGeometry(viewport.copy(height = 740))
        val resized = status.scrollNavigation.value
        assertTrue(resized.revision > original.revision)
        assertEquals(650_000, resized.anchorY)
        assertEquals(original.pointNavigationRevisions, resized.pointNavigationRevisions)
        assertFalse(status.scrollNavigationSettled)
        status.updateScrollViewport(listOf(1), 1, original.revision)
        status.finishDraw(1, ticket, 20_000)
        assertNull(status.drawnPosition.value)

        status.updateScrollViewport(listOf(1), 1, resized.revision)
        status.finishDraw(1, ticket, 645_000)
        assertEquals(PdfResumePosition(1, 645_000), status.drawnPosition.value)
        status.clearDraw()
        status.updateScrollGeometry(viewport.copy(height = 700))
        val painted = status.scrollNavigation.value
        assertEquals(645_000, painted.anchorY)

        // An admitted page-only paint retires the command; an old original point cannot return.
        status.updateScrollViewport(listOf(1), 1, painted.revision)
        status.finishDraw(1, ticket, null)
        assertEquals(PdfResumePosition(1), status.drawnPosition.value)
        status.updateScrollGeometry(viewport.copy(height = 660))
        assertNull(status.scrollNavigation.value.anchorY)
    }
    @Test fun pagedPreferenceConsumesThePendingAnchorOnlyAfterItsActualPagedDraw() {
        val status = PdfRenderStatus(3, 1, 650_000)
        val ticket = prepare(status, 1)
        status.finishDraw(1, ticket); assertNull(status.drawnPosition.value)
        status.initialPreferencesApplied(); assertNull(status.drawnPosition.value)
        status.finishDraw(1, ticket, 650_000)
        assertEquals(PdfResumePosition(1), status.drawnPosition.value)
    }
    @Test fun anExplicitNavigationReplacesAPendingResumeWithoutALatePreferenceJump() {
        val status = PdfRenderStatus(3, 1, 650_000)
        status.setScrolling(true); assertTrue(status.goTo(2))
        val command = status.scrollNavigation.value
        status.initialPreferencesApplied(); assertEquals(command, status.scrollNavigation.value); assertNull(command.anchorY)
        status.registerScrollItem(2); status.updateScrollViewport(listOf(2), 2)
        val ticket = prepare(status, 2); status.finishDraw(2, ticket, 20_000)
        assertEquals(PdfResumePosition(2, 20_000), status.drawnPosition.value)
    }
    @Test fun neighborAndStaleTicketsOrNavigationCannotProveThePrimaryAnchor() {
        val status = PdfRenderStatus(3, 0)
        status.setScrolling(true); status.registerScrollItem(0); status.registerScrollItem(1)
        status.updateScrollViewport(listOf(0, 1), 0)
        val primary = prepare(status, 0); val neighbor = prepare(status, 1)
        status.finishDraw(1, neighbor, 700_000); assertNull(status.drawnPosition.value)
        status.finishDraw(0, primary, 200_000); assertNotNull(status.drawnPosition.value)
        val oldCommand = status.scrollNavigation.value
        assertTrue(status.goTo(0)); status.updateScrollViewport(listOf(0, 1), 0, oldCommand.revision)
        status.finishDraw(0, primary, 800_000); assertNull(status.drawnPosition.value)
        status.updateScrollViewport(listOf(0, 1), 0)
        val next = prepare(status, 0); status.finishDraw(0, primary, 800_000); assertNull(status.drawnPosition.value)
        status.finishDraw(0, next, 300_000); assertEquals(PdfResumePosition(0, 300_000), status.drawnPosition.value)
        status.unregisterScrollItem(0); status.finishDraw(0, next, 300_000); assertNull(status.drawnPosition.value)
    }
}
