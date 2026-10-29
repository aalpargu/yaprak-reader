package com.yaprak.reader.formats

import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test

class PdfBookmarkPointTest {
    private fun prepare(status: PdfRenderStatus, page: Int): Long =
        requireNotNull(status.startRender(page)).also { status.finishRender(page, it) }

    @Test fun explicitPointSupersedesStartupAndKeepsItsOwnTargetAcrossPendingGeometryAndStaleDraw() {
        val status = PdfRenderStatus(10, 1, 600_000)
        status.setScrolling(true)
        status.updateScrollGeometry(PdfScrollViewport(400, 600, 600, 48, PdfPageCrop(), PdfFitMode.WIDTH))
        status.registerScrollItem(1); status.registerScrollItem(2)
        val old = status.scrollNavigation.value
        assertTrue(status.goTo(2, 700_000))
        val point = status.scrollNavigation.value
        assertEquals(2, point.pageIndex); assertEquals(700_000, point.anchorY)
        assertTrue(point.revision > old.revision)
        status.initialPreferencesApplied()
        status.updateScrollGeometry(PdfScrollViewport(240, 480, 480, 48, PdfPageCrop(10, 15), PdfFitMode.WIDTH))
        assertEquals(point, status.scrollNavigation.value)
        val ticket = prepare(status, 2)
        status.updateScrollViewport(listOf(2), 2, old.revision)
        status.finishDraw(2, ticket, 10_000)
        assertNull(status.drawnPosition.value)
        status.updateScrollViewport(listOf(2), 2, point.revision)
        assertNull(status.drawnPosition.value)
        status.finishDraw(2, ticket, 700_000)
        assertEquals(PdfResumePosition(2, 700_000), status.drawnPosition.value)
        status.initialPreferencesApplied(); assertEquals(point, status.scrollNavigation.value)
    }

    @Test fun pagedPointOpensOnlyThePageAndCannotReplayAfterAModeChangeWhilePageOnlyCommandsStayPageOnly() {
        val status = PdfRenderStatus(10, 0, 600_000)
        assertTrue(status.goTo(2, 700_000)); assertFalse(status.scrolling)
        status.initialPreferencesApplied()
        val ticket = prepare(status, 2)
        status.finishDraw(2, ticket, 700_000)
        assertEquals(PdfResumePosition(2), status.drawnPosition.value)
        status.setScrolling(true)
        assertEquals(2, status.scrollNavigation.value.pageIndex); assertNull(status.scrollNavigation.value.anchorY)
        status.initialPreferencesApplied(); assertNull(status.scrollNavigation.value.anchorY)
        assertTrue(status.goTo(3, 650_000)); assertEquals(650_000, status.scrollNavigation.value.anchorY)
        assertTrue(status.goTo(4)); assertEquals(4, status.scrollNavigation.value.pageIndex)
        assertNull(status.scrollNavigation.value.anchorY)
    }

    @Test fun invalidPointCannotCancelPendingResumeOrCurrentProofAndFixedPointEndpointsAreValid() {
        val status = PdfRenderStatus(10, 1, 600_000)
        status.setScrolling(true)
        val pending = status.scrollNavigation.value
        for ((page, anchor) in listOf(-1 to 0, 10 to 0, 1 to -1, 1 to 1_000_001)) {
            assertFalse(status.goTo(page, anchor)); assertEquals(pending, status.scrollNavigation.value)
        }
        status.initialPreferencesApplied(); assertEquals(600_000, status.scrollNavigation.value.anchorY)
        status.registerScrollItem(1); status.updateScrollViewport(listOf(1), 1)
        val ticket = prepare(status, 1); status.finishDraw(1, ticket, 600_000)
        val proof = status.drawnPosition.value; val command = status.scrollNavigation.value
        assertNotNull(proof)
        for (anchor in listOf(-1, 1_000_001)) {
            assertFalse(status.goTo(1, anchor)); assertEquals(proof, status.drawnPosition.value)
            assertEquals(command, status.scrollNavigation.value)
        }
        for (anchor in listOf(0, 1_000_000)) {
            assertTrue(status.goTo(1, anchor)); assertNull(status.drawnPosition.value)
            assertEquals(anchor, status.scrollNavigation.value.anchorY)
            status.updateScrollViewport(listOf(1), 1)
            status.finishDraw(1, ticket, anchor)
            assertEquals(PdfResumePosition(1, anchor), status.drawnPosition.value)
        }
    }
}
