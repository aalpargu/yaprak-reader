package com.yaprak.reader.formats.pdf.ink

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfink.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfInkControllerTest {
    private val book = Book("b", "Kitap", null, BookFormat.PDF, "a.pdf", "b.pdf", 10, "a".repeat(64), 1, pageCount = 5)

    private class Store : PdfInkRepository {
        val rows = MutableStateFlow<List<PdfInkStroke>>(emptyList())
        var failAdds = false
        override fun observe(bookId: String, contentSha256: String): Flow<List<PdfInkStroke>> = rows
        override suspend fun add(bookId: String, contentSha256: String, stroke: PdfInkStroke) {
            if (failAdds) error("disk full")
            rows.update { it + stroke }
        }
        override suspend fun remove(bookId: String, contentSha256: String, strokeId: String) { rows.update { list -> list.filterNot { it.id == strokeId } } }
    }

    /** A 20 pt heading line at 10..14% of the page and a 10 pt body line at 30..32%. */
    private val text = PdfInkTextLookup { _, start, stop ->
        val heading = PdfInkTextRect(0.10, 0.10, 0.70, 0.14)
        val body = PdfInkTextRect(0.10, 0.30, 0.80, 0.32)
        when {
            start == stop && start.y in 10_000..14_000 -> listOf(heading.copy(left = 0.2, right = 0.3))
            start == stop && start.y in 30_000..32_000 -> listOf(body.copy(left = 0.2, right = 0.3))
            start == stop -> null
            // A drag from the heading into the body selects the heading's tail and the body's head.
            else -> listOf(heading.copy(left = start.x / 100_000.0), body.copy(right = stop.x / 100_000.0))
        }
    }

    private var ids = 0
    private fun controller(store: Store, lookup: PdfInkTextLookup? = text) =
        PdfInkController(book, store, lookup, clock = { 42L }, newId = { "id-${++ids}" })

    @Test fun alignedStrokeSnapsToEachSelectedLineWithItsHeight() = runTest {
        val store = Store(); val pen = controller(store)
        pen.attach(backgroundScope); assertTrue(pen.enter())
        assertEquals(PdfInkMode.ALIGNED, pen.tool.value.mode)
        pen.begin(2, PdfInkPoint(30_000, 12_000))
        pen.extend(2, PdfInkPoint(50_000, 25_000))
        pen.extend(2, PdfInkPoint(60_000, 31_000))
        runCurrent()
        assertEquals(2, pen.draft.value!!.segments.size)
        pen.finish(); runCurrent()
        val saved = store.rows.value.single()
        assertEquals(2, saved.pageIndex); assertEquals(PdfInkMode.ALIGNED, saved.mode); assertEquals(42L, saved.createdAt)
        assertEquals(listOf(4000, 2000), saved.segments.map { it.width })
        assertEquals(listOf(PdfInkPoint(30_000, 12_000), PdfInkPoint(70_000, 12_000)), saved.segments[0].points)
        assertEquals(listOf(PdfInkPoint(10_000, 31_000), PdfInkPoint(60_000, 31_000)), saved.segments[1].points)
        assertNull(pen.draft.value)
        assertEquals(listOf(saved), pen.strokes.value)
    }

    @Test fun freehandThicknessComesFromTheWordUnderTheFirstTouch() = runTest {
        val store = Store(); val pen = controller(store)
        pen.attach(backgroundScope); pen.enter(); pen.select(PdfInkMode.FREEHAND)
        pen.begin(0, PdfInkPoint(25_000, 12_000)); runCurrent()
        assertEquals(4000, pen.draft.value!!.segments.single().width)
        pen.extend(0, PdfInkPoint(40_000, 13_000)); pen.extend(0, PdfInkPoint(55_000, 11_000))
        pen.finish(); runCurrent()
        assertEquals(4000, store.rows.value.single().segments.single().width)
        assertEquals(3, store.rows.value.single().segments.single().points.size)
        // Small body text gives a thinner band.
        pen.begin(0, PdfInkPoint(25_000, 31_000)); runCurrent(); pen.extend(0, PdfInkPoint(40_000, 31_000)); pen.finish(); runCurrent()
        assertEquals(2000, store.rows.value.last().segments.single().width)
    }

    @Test fun withoutTextThePenStillWorksWithTheDefaultThicknessAndAStraightBand() = runTest {
        val store = Store(); val pen = controller(store, lookup = null)
        pen.attach(backgroundScope); pen.enter()
        assertEquals(PdfInkMode.FREEHAND, pen.tool.value.mode)
        assertFalse(pen.textAvailable)
        pen.begin(1, PdfInkPoint(10_000, 50_000)); pen.extend(1, PdfInkPoint(30_000, 52_000)); pen.finish(); runCurrent()
        assertEquals(DEFAULT_PDF_INK_WIDTH, store.rows.value.single().segments.single().width)
        // Aligned with no text under the drag: one straight horizontal band at the start height.
        val aligned = controller(store, lookup = PdfInkTextLookup { _, _, _ -> null })
        aligned.attach(backgroundScope); aligned.enter()
        aligned.begin(1, PdfInkPoint(10_000, 70_000)); aligned.extend(1, PdfInkPoint(40_000, 73_000)); aligned.finish(); runCurrent()
        assertEquals(listOf(PdfInkPoint(10_000, 70_000), PdfInkPoint(40_000, 70_000)), store.rows.value.last().segments.single().points)
    }

    @Test fun undoRemovesTheLastStrokeAndRestoresAnErasedOne() = runTest {
        val store = Store(); val pen = controller(store)
        pen.attach(backgroundScope); pen.enter(); pen.select(PdfInkMode.FREEHAND)
        repeat(2) { pen.begin(0, PdfInkPoint(1000 + it, 90_000)); pen.extend(0, PdfInkPoint(9000, 90_000)); pen.finish(); runCurrent() }
        assertEquals(2, store.rows.value.size); assertTrue(pen.canUndo.value)
        pen.undo(); runCurrent()
        assertEquals(listOf("id-1"), store.rows.value.map { it.id })
        val first = store.rows.value.single()
        pen.erase(first); runCurrent()
        assertTrue(store.rows.value.isEmpty()); assertTrue(pen.strokes.value.isEmpty())
        pen.undo(); runCurrent()
        assertEquals(listOf(first), store.rows.value)
        assertEquals(listOf(first), pen.strokes.value)
    }

    @Test fun aSecondFingerOrExitCancelsTheDraftAndAFailedSaveSaysSo() = runTest {
        val store = Store(); val pen = controller(store)
        pen.attach(backgroundScope); pen.enter()
        pen.begin(0, PdfInkPoint(1000, 1000)); pen.cancel(); runCurrent()
        assertNull(pen.draft.value); assertTrue(store.rows.value.isEmpty())
        pen.begin(0, PdfInkPoint(1000, 1000)); pen.exit(); pen.finish(); runCurrent()
        assertTrue(store.rows.value.isEmpty()); assertFalse(pen.active.value)
        // While off, touches do nothing.
        pen.begin(0, PdfInkPoint(1000, 1000)); assertNull(pen.draft.value)
        pen.enter(); store.failAdds = true; pen.select(PdfInkMode.FREEHAND)
        pen.begin(0, PdfInkPoint(1000, 1000)); pen.extend(0, PdfInkPoint(5000, 1000)); pen.finish(); runCurrent()
        assertTrue(pen.strokes.value.isEmpty()); assertNotNull(pen.notice.value); assertFalse(pen.canUndo.value)
    }

    @Test fun eraserModeDoesNotPaint() = runTest {
        val store = Store(); val pen = controller(store)
        pen.attach(backgroundScope); pen.enter(); pen.toggleEraser()
        pen.begin(0, PdfInkPoint(1000, 1000)); assertNull(pen.draft.value)
        pen.select(PdfHighlightColor.GREEN); assertFalse(pen.tool.value.eraser)
    }

    @Test fun cannotEnterBeforeAttachOrWhenThePageIsNotReady() = runTest {
        val store = Store()
        assertFalse(controller(store).enter())
        val blocked = PdfInkController(book, store, text, canEnter = { false })
        blocked.attach(backgroundScope); assertFalse(blocked.enter()); assertFalse(blocked.active.value)
    }
}
