package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.domain.pdfannotations.PdfAnnotationSourceException
import com.yaprak.reader.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native boundary ownership/proof only. No actual PDF decoder is replaced or claimed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PdfOriginalAnnotationsTest {
    private val anchor = PdfSourceAnchor(0, 300, 600, PdfPagePoint(60, 180), PdfPagePoint(210, 300),
        listOf(PdfTextBounds(60.0, 180.0, 210.0, 200.0), PdfTextBounds(10.0, 280.0, 210.0, 300.0)))
    private class Native : PdfNativeRenderer {
        override val pageCount = 2
        override var textAvailable = true
        var width = 300; var height = 600
        val events = mutableListOf<String>()
        var selection: (Int, PdfTextPoint, PdfTextPoint) -> PdfSelectedText? = { _, _, _ -> null }
        override fun openPage(pageIndex: Int): PdfNativePage {
            events += "open:$pageIndex"
            return object : PdfNativePage {
                override val width get() = this@Native.width
                override val height get() = this@Native.height
                override fun render(bitmap: Bitmap) = Unit
                override fun selectText(start: PdfTextPoint, stop: PdfTextPoint): PdfSelectedText? {
                    events += "select:$pageIndex"; return selection(pageIndex, start, stop)
                }
                override fun close() { events += "page-close:$pageIndex" }
            }
        }
        override fun close() { events += "close" }
    }
    @Test fun proofUsesTheStoredNativePointPairAndExactActualReturnedTextAndBounds() = runTest {
        val native = Native().apply { selection = { page, start, stop ->
            assertEquals(0, page); assertEquals(anchor.start, nativePdfPoint(start, width, height))
            assertEquals(anchor.stop, nativePdfPoint(stop, width, height))
            PdfSelectedText(page, "alpha\n🌿", anchor)
        } }
        val doc = PdfDocument(native, StandardTestDispatcher(testScheduler))
        try {
            verifyPdfAnnotationSelection(doc, "alpha\n🌿", anchor)
            assertEquals(listOf("open:0", "select:0", "page-close:0"), native.events)
        } finally { doc.close(); advanceUntilIdle() }
    }
    @Test fun similarTextWhitespaceChangeMissingGeometryAndMovedNativeRectangleAreNotProof() = runTest {
        for (selected in listOf(null, PdfSelectedText(0, "alpha 🌿", anchor), PdfSelectedText(0, "alpha\n🌿"),
            PdfSelectedText(0, "alpha\n🌿", PdfSourceAnchor(0, 300, 600, anchor.start, anchor.stop,
                listOf(PdfTextBounds(61.0, 180.0, 211.0, 200.0)))))) {
            val native = Native().apply { selection = { _, _, _ -> selected } }
            val doc = PdfDocument(native, StandardTestDispatcher(testScheduler))
            try {
                assertTrue(runCatching { verifyPdfAnnotationSelection(doc, "alpha\n🌿", anchor) }.exceptionOrNull() is PdfAnnotationSourceException)
                assertEquals("page-close:0", native.events.last())
            } finally { doc.close(); advanceUntilIdle() }
        }
    }
    @Test fun nativeEvidenceCannotForgeItsPageDimensionsOrTheRequestedIntegerPoints() = runTest {
        for (forged in listOf(
            PdfSourceAnchor(1, 300, 600, anchor.start, anchor.stop, anchor.bounds),
            PdfSourceAnchor(0, 301, 600, anchor.start, anchor.stop, anchor.bounds),
            PdfSourceAnchor(0, 300, 601, anchor.start, anchor.stop, anchor.bounds),
            PdfSourceAnchor(0, 300, 600, PdfPagePoint(61, 180), anchor.stop, anchor.bounds),
        )) {
            val native = Native().apply { selection = { _, _, _ -> PdfSelectedText(0, "alpha", forged) } }
            val doc = PdfDocument(native, StandardTestDispatcher(testScheduler))
            try {
                val failed = runCatching { doc.selectText(0, PdfTextPoint(.2, .3), PdfTextPoint(.7, .5)) }.exceptionOrNull()
                assertEquals(TextSelectionFailure.INVALID_RESOURCE, (failed as TextSelectionException).reason)
                assertEquals("page-close:0", native.events.last())
            } finally { doc.close(); advanceUntilIdle() }
        }
    }
    @Test fun textWithoutNativeGeometryStaysCopyableAndSourceCoordinatorRetainsRealEvidenceWhenPresent() = runTest {
        val native = Native().apply { selection = { page, _, _ -> PdfSelectedText(page, "alpha", anchor) } }
        val doc = PdfDocument(native, StandardTestDispatcher(testScheduler)); val source = PdfTextSelectionSource(doc) { true }
        try {
            source.accept(0, PdfTextPoint(.2, .3), PdfTextPoint(.7, .5))
            assertEquals(anchor, source.capture()!!.pdfSourceAnchor)
            native.selection = { page, _, _ -> PdfSelectedText(page, "alpha") }
            val copyable = source.capture()!!
            assertEquals("alpha", copyable.text); assertNull(copyable.pdfSourceAnchor)
        } finally { doc.close(); advanceUntilIdle() }
    }
    @Test fun callerCancellationDuringNativeProofClosesPageAndCannotReturnVerifiedEvidence() = runTest {
        val native = Native(); val doc = PdfDocument(native, StandardTestDispatcher(testScheduler))
        lateinit var proof: Deferred<Unit>
        native.selection = { page, _, _ -> proof.cancel(); PdfSelectedText(page, "alpha", anchor) }
        proof = async { verifyPdfAnnotationSelection(doc, "alpha", anchor) }
        try {
            assertTrue(runCatching { proof.await() }.exceptionOrNull() is CancellationException)
            assertEquals(listOf("open:0", "select:0", "page-close:0"), native.events)
        } finally { doc.close(); advanceUntilIdle() }
    }
    @Test fun closedUnsupportedAndChangedPageCannotBeVerified() = runTest {
        val native = Native(); val doc = PdfDocument(native, StandardTestDispatcher(testScheduler))
        try {
            native.textAvailable = false
            assertTrue(runCatching { verifyPdfAnnotationSelection(doc, "alpha", anchor) }.isFailure)
            assertTrue(native.events.isEmpty())
            doc.close(); advanceUntilIdle()
            assertTrue(runCatching { verifyPdfAnnotationSelection(doc, "alpha", anchor) }.isFailure)
            assertEquals(listOf("close"), native.events)
        } finally { doc.close(); advanceUntilIdle() }
    }
}
