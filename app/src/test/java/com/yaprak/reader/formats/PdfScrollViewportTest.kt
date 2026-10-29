package com.yaprak.reader.formats

import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test

class PdfScrollViewportTest {
    private val wide = PdfScrollViewport(400, 800, 96, 48, PdfPageCrop(), PdfFitMode.WIDTH)
    private fun prepare(s: PdfRenderStatus, page: Int): Long = s.startRender(page)!!.also { s.finishRender(page, it) }
    private fun active(): Pair<PdfRenderStatus, Long> {
        val s = PdfRenderStatus(5, 1)
        s.setScrolling(true); s.updateScrollGeometry(wide); s.registerScrollItem(1)
        s.updateScrollViewport(listOf(1), 1)
        return s to prepare(s, 1)
    }

    @Test fun geometryChangeUsesTheMostRecentNativePointAndRequiresItsOwnCompletedRevisionAndNewDraw() {
        val (s, ticket) = active()
        s.finishDraw(1, ticket, 600_000); s.finishDraw(1, ticket, 710_000); s.clearDraw()
        val previous = s.scrollNavigation.value
        s.updateScrollGeometry(wide.copy(width = 240))
        val command = s.scrollNavigation.value
        assertEquals(1, command.pageIndex); assertEquals(710_000, command.anchorY); assertTrue(command.revision > previous.revision)
        assertNull(s.drawnPosition.value); assertFalse(s.scrollNavigationSettled)
        s.updateScrollViewport(listOf(1), 1, previous.revision); s.finishDraw(1, ticket, 900_000)
        assertNull(s.drawnPosition.value)
        s.updateScrollViewport(listOf(1), 1, command.revision)
        assertNull(s.drawnPosition.value)
        s.finishDraw(1, ticket, 710_000)
        assertEquals(PdfResumePosition(1, 710_000), s.drawnPosition.value)
    }

    @Test fun initialPreferenceBarrierAndPendingCommandsKeepTheirOriginalTargetAcrossRepeatedGeometryChanges() {
        val s = PdfRenderStatus(5, 1, 600_000)
        s.setScrolling(true); s.updateScrollGeometry(wide); s.registerScrollItem(1); s.updateScrollViewport(listOf(1), 1)
        val ticket = prepare(s, 1); s.finishDraw(1, ticket, 20_000)
        val before = s.scrollNavigation.value
        s.updateScrollGeometry(wide.copy(width = 240, crop = PdfPageCrop(10, 15)))
        assertEquals(before, s.scrollNavigation.value); assertNull(s.drawnPosition.value)
        s.initialPreferencesApplied()
        val pending = s.scrollNavigation.value
        assertEquals(600_000, pending.anchorY)
        s.updateScrollGeometry(wide.copy(width = 300)); s.updateScrollGeometry(wide.copy(height = 400))
        assertEquals(pending, s.scrollNavigation.value); assertNull(s.drawnPosition.value)
        s.updateScrollViewport(listOf(1), 1, pending.revision); s.finishDraw(1, ticket, 600_000)
        assertEquals(PdfResumePosition(1, 600_000), s.drawnPosition.value)
    }

    @Test fun explicitPageNavigationCannotReuseThePreviousPagesAnchorEvenWhenGeometryChangesBeforeOrAfterCompletion() {
        val (s, ticket) = active(); s.finishDraw(1, ticket, 710_000)
        assertTrue(s.goTo(2)); val explicit = s.scrollNavigation.value
        s.updateScrollGeometry(wide.copy(width = 240)); assertEquals(explicit, s.scrollNavigation.value)
        assertNull(explicit.anchorY)
        s.registerScrollItem(2); s.updateScrollViewport(listOf(2), 2, explicit.revision)
        s.updateScrollGeometry(wide.copy(width = 300))
        assertEquals(2, s.scrollNavigation.value.pageIndex); assertNull(s.scrollNavigation.value.anchorY)
        val next = prepare(s, 2); s.updateScrollViewport(listOf(2), 2); s.finishDraw(2, next, 50_000)
        s.updateScrollGeometry(wide.copy(width = 400))
        assertEquals(2, s.scrollNavigation.value.pageIndex); assertEquals(50_000, s.scrollNavigation.value.anchorY)
    }

    @Test fun pageOnlyDrawAndPrimaryPageChangesCannotResurrectAnOlderPaintedPoint() {
        val (s, ticket) = active(); s.finishDraw(1, ticket, 710_000)
        s.finishDraw(1, ticket, null)
        assertEquals(PdfResumePosition(1), s.drawnPosition.value)
        s.updateScrollGeometry(wide.copy(width = 240)); assertNull(s.scrollNavigation.value.anchorY)
        s.updateScrollViewport(listOf(1), 1); s.finishDraw(1, ticket, 600_000)
        s.registerScrollItem(2); s.updateScrollViewport(listOf(1, 2), 2)
        s.updateScrollGeometry(wide.copy(width = 300))
        assertEquals(2, s.scrollNavigation.value.pageIndex); assertNull(s.scrollNavigation.value.anchorY)
    }

    @Test fun unchangedAndPagedGeometryDoNotNavigateWhileAnInvalidViewportPreservesThePointForItsRecovery() {
        val (s, ticket) = active(); s.finishDraw(1, ticket, 600_000)
        val before = s.scrollNavigation.value
        s.updateScrollGeometry(wide)
        assertEquals(before, s.scrollNavigation.value); assertEquals(PdfResumePosition(1, 600_000), s.drawnPosition.value)
        s.updateScrollGeometry(wide.copy(width = 0)); s.updateScrollGeometry(wide.copy(height = -1))
        assertEquals(before, s.scrollNavigation.value); assertNull(s.drawnPosition.value)
        s.updateScrollGeometry(wide)
        assertTrue(s.scrollNavigation.value.revision > before.revision); assertEquals(600_000, s.scrollNavigation.value.anchorY)
        s.setScrolling(false); val paged = s.scrollNavigation.value
        s.updateScrollGeometry(wide.copy(width = 240)); assertEquals(paged, s.scrollNavigation.value)
        s.setScrolling(true); val reentered = s.scrollNavigation.value
        s.updateScrollGeometry(wide); assertEquals(reentered, s.scrollNavigation.value)
        s.updateScrollViewport(listOf(1), 1); s.updateScrollGeometry(wide.copy(width = 240))
        assertNull(s.scrollNavigation.value.anchorY)
    }
}
