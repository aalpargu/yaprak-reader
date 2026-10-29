package com.yaprak.reader.formats

import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test

class PdfPointNavigationTest {
    @Test fun explicitOriginalPointOwnsItsPageAndRepeatedTargetsReceiveANewRevision() {
        val status = PdfRenderStatus(10, 2)
        status.setScrolling(true)
        assertTrue(status.scrollNavigation.value.pointNavigationRevisions.isEmpty())
        assertTrue(status.goTo(2, 600_000))
        val first = status.scrollNavigation.value
        assertEquals(first.revision, first.pointRevision(2)); assertEquals(0L, first.pointRevision(3))
        assertTrue(status.goTo(2, 600_000))
        val repeated = status.scrollNavigation.value
        assertTrue(repeated.pointRevision(2) > first.pointRevision(2))
        assertTrue(status.goTo(3, 400_000))
        val neighbor = status.scrollNavigation.value
        assertEquals(repeated.pointRevision(2), neighbor.pointRevision(2))
        assertEquals(neighbor.revision, neighbor.pointRevision(3))
        assertTrue(status.goTo(2))
        assertNull(status.scrollNavigation.value.anchorY)
        assertEquals(neighbor.pointNavigationRevisions, status.scrollNavigation.value.pointNavigationRevisions)
        status.registerScrollItem(2); status.registerScrollItem(3)
        status.updateScrollViewport(listOf(2, 3), 2)
        assertTrue(status.adoptScrollPrimary(3))
        assertEquals(neighbor.pointNavigationRevisions, status.scrollNavigation.value.pointNavigationRevisions)
        assertEquals(first.revision, first.pointRevision(2)) // Detached commands are immutable.
    }

    @Test fun geometryAndInvalidCommandsPreservePointTransformOwnershipAndPendingAdmission() {
        val status = PdfRenderStatus(10, 2)
        status.setScrolling(true)
        status.updateScrollGeometry(PdfScrollViewport(400, 800, 96, 48, PdfPageCrop(), PdfFitMode.WIDTH))
        status.registerScrollItem(2); assertTrue(status.goTo(2, 600_000))
        val original = status.scrollNavigation.value
        status.updateScrollViewport(listOf(2), 2, original.revision)
        val ticket = requireNotNull(status.startRender(2)); status.finishRender(2, ticket)
        status.finishDraw(2, ticket, 600_000)
        status.updateScrollGeometry(PdfScrollViewport(240, 480, 96, 48, PdfPageCrop(10, 15), PdfFitMode.WIDTH))
        val reflow = status.scrollNavigation.value
        assertTrue(reflow.revision > original.revision); assertEquals(600_000, reflow.anchorY)
        assertEquals(original.pointNavigationRevisions, reflow.pointNavigationRevisions)
        for ((page, anchor) in listOf(-1 to 0, 10 to 0, 2 to -1, 2 to 1_000_001)) {
            assertFalse(status.goTo(page, anchor)); assertEquals(reflow, status.scrollNavigation.value)
        }
        status.updateScrollViewport(listOf(2), 2, original.revision)
        status.finishDraw(2, ticket, 50_000)
        assertNull(status.drawnPosition.value)
        assertEquals(reflow, status.scrollNavigation.value)
    }

    @Test fun initialPointResetsOnceAndPagedTargetsCannotReplayAfterChangingMode() {
        val status = PdfRenderStatus(10, 2, 600_000)
        status.setScrolling(true)
        assertTrue(status.scrollNavigation.value.pointNavigationRevisions.isEmpty())
        status.initialPreferencesApplied()
        val initial = status.scrollNavigation.value
        assertEquals(initial.revision, initial.pointRevision(2))
        status.initialPreferencesApplied(); assertEquals(initial, status.scrollNavigation.value)
        status.setScrolling(false)
        assertTrue(status.scrollNavigation.value.pointNavigationRevisions.isEmpty())
        assertTrue(status.goTo(3, 700_000))
        status.setScrolling(true)
        assertEquals(3, status.scrollNavigation.value.pageIndex)
        assertNull(status.scrollNavigation.value.anchorY)
        assertTrue(status.scrollNavigation.value.pointNavigationRevisions.isEmpty())
        status.initialPreferencesApplied()
        assertNull(status.scrollNavigation.value.anchorY)
        assertTrue(status.goTo(3, 700_000))
        assertEquals(setOf(3), status.scrollNavigation.value.pointNavigationRevisions.keys)
    }
}
