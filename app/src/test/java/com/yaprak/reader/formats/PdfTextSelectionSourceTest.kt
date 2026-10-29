package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native seam verifies ownership/cleanup, not real PDF decoder acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PdfTextSelectionSourceTest {
    private class Native(override val textAvailable: Boolean = true) : PdfNativeRenderer {
        override val pageCount = 3
        val events = mutableListOf<String>()
        var onSelect: (Int, PdfTextPoint, PdfTextPoint) -> PdfSelectedText? = { page, _, _ -> PdfSelectedText(page, "alpha 🌿") }
        override fun openPage(pageIndex: Int): PdfNativePage {
            events += "open:$pageIndex"
            return object : PdfNativePage {
                override val width = 300; override val height = 600
                override fun render(bitmap: Bitmap) { events += "render:$pageIndex" }
                override fun selectText(start: PdfTextPoint, stop: PdfTextPoint): PdfSelectedText? {
                    events += "select:$pageIndex"; return onSelect(pageIndex, start, stop)
                }
                override fun close() { events += "page-close:$pageIndex" }
            }
        }
        override fun close() { events += "close" }
    }
    private val start = PdfTextPoint(.2, .3)
    private val stop = PdfTextPoint(.7, .5)
    @Test fun acceptingGestureOnlyNotifiesAndCaptureRetainsRealRightPageIdentity() = runTest {
        val native = Native(); val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        val source = PdfTextSelectionSource(document) { it in listOf(1, 2) }; var notices = 0
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.requests.collect { notices++ } }
        try {
            assertFalse(source.active); assertTrue(source.accept(2, start, stop)); assertTrue(source.active); assertEquals(1, notices); assertTrue(native.events.isEmpty())
            val selected = source.capture()!!
            assertEquals("alpha 🌿", selected.text); assertEquals(2, selected.pdfPageIndex)
            assertNull(selected.sourceAnchor); assertNull(selected.rangeCandidate)
            assertEquals(selected.sourceSelectionId, UUID.fromString(selected.sourceSelectionId!!).toString())
            assertTrue(source.isCurrent(selected.sourceSelectionId)); assertFalse(source.isCurrent(UUID.randomUUID().toString()))
            assertEquals(listOf("open:2", "select:2", "page-close:2"), native.events)
            source.clear(); assertFalse(source.active); assertFalse(source.isCurrent(selected.sourceSelectionId)); assertNull(source.capture())
        } finally { observer.cancel(); document.close(); advanceUntilIdle() }
    }
    @Test fun unsupportedInvisibleInvalidPageAndNonFinitePointsNeverOpenNativePage() = runTest {
        for (available in listOf(true, false)) {
            val native = Native(available); val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
            val source = PdfTextSelectionSource(document) { it == 1 }
            try {
                for (page in listOf(-1, 0, 3, Int.MAX_VALUE)) assertFalse(source.accept(page, start, stop))
                for (point in listOf(PdfTextPoint(Double.NaN, .2), PdfTextPoint(-.1, .2), PdfTextPoint(.2, 1.1)))
                    assertFalse(source.accept(1, point, stop))
                if (!available) assertFalse(source.accept(1, start, stop))
                assertNull(source.capture()); assertTrue(native.events.isEmpty())
            } finally { document.close(); advanceUntilIdle() }
        }
    }
    @Test fun wrongNativePageMalformedTextAndOversizeAreRejectedAfterPageCleanup() = runTest {
        for ((selected, reason) in listOf(PdfSelectedText(2, "word") to TextSelectionFailure.INVALID_RESOURCE,
            PdfSelectedText(1, "\uD83C") to TextSelectionFailure.INVALID_RESOURCE,
            PdfSelectedText(1, "a".repeat(5001)) to TextSelectionFailure.TOO_LONG)) {
            val native = Native().apply { onSelect = { _, _, _ -> selected } }
            val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
            try {
                val source = PdfTextSelectionSource(document) { true }; source.accept(1, start, stop)
                assertEquals(reason, (runCatching { source.capture() }.exceptionOrNull() as? TextSelectionException)?.reason)
                assertEquals("page-close:1", native.events.last())
            } finally { document.close(); advanceUntilIdle() }
        }
    }
    @Test fun blankAndNoNativeSelectionReturnNoFakeContent() = runTest {
        for (selected in listOf(null, PdfSelectedText(1, " \n\t"))) {
            val native = Native().apply { onSelect = { _, _, _ -> selected } }
            val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
            try {
                val source = PdfTextSelectionSource(document) { true }; source.accept(1, start, stop)
                assertNull(source.capture()); assertEquals("page-close:1", native.events.last())
            } finally { document.close(); advanceUntilIdle() }
        }
    }
    @Test fun replacementDuringNativeCallCannotReturnOldSelectionAsNewRequest() = runTest {
        val native = Native(); val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        val source = PdfTextSelectionSource(document) { true }
        try {
            source.accept(1, start, stop)
            native.onSelect = { page, _, _ -> source.accept(2, stop, start); PdfSelectedText(page, "old") }
            assertTrue(runCatching { source.capture() }.exceptionOrNull() is CancellationException)
            native.onSelect = { page, _, _ -> PdfSelectedText(page, "new") }
            assertEquals(2, source.capture()!!.pdfPageIndex)
        } finally { document.close(); advanceUntilIdle() }
    }
    @Test fun invisibleOrClearedDuringNativeCallDropsResultAndClosesActualPage() = runTest {
        for (clear in listOf(false, true)) {
            var visible = true
            val native = Native(); val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
            val source = PdfTextSelectionSource(document) { visible }
            try {
                source.accept(1, start, stop)
                native.onSelect = { page, _, _ -> if (clear) source.clear() else visible = false; PdfSelectedText(page, "old") }
                assertTrue(runCatching { source.capture() }.exceptionOrNull() is CancellationException)
                assertEquals("page-close:1", native.events.last()); assertFalse(source.isCurrent(null))
            } finally { document.close(); advanceUntilIdle() }
        }
    }
    @Test fun realCallerCancellationClosesPageAndDoesNotBecomeErrorText() = runTest {
        val native = Native(); val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        val source = PdfTextSelectionSource(document) { true }; source.accept(1, start, stop)
        val request = async { source.capture() }
        native.onSelect = { page, _, _ -> request.cancel(); PdfSelectedText(page, "late") }; runCurrent()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        assertEquals("page-close:1", native.events.last())
        source.clear(); document.close(); advanceUntilIdle(); assertFalse(source.isCurrent(null))
    }
    @Test fun closedDocumentAndUnsupportedNativeCapabilityAreNotSelections() = runTest {
        val native = Native(false); val document = PdfDocument(native, StandardTestDispatcher(testScheduler))
        assertEquals(TextSelectionFailure.UNSUPPORTED, (runCatching { document.selectText(1, start, stop) }.exceptionOrNull() as? TextSelectionException)?.reason)
        assertTrue(native.events.isEmpty()); document.close(); advanceUntilIdle()
        val source = PdfTextSelectionSource(document) { true }
        assertFalse(source.accept(1, start, stop)); assertNull(source.capture())
    }
}
