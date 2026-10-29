package com.yaprak.reader.domain

import android.app.Application
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.reader.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfAnchorCodecTest {
    private val first = PdfTextBounds(20.25, 40.5, 180.75, 55.5)
    private fun anchor(bounds: List<PdfTextBounds> = listOf(first)) =
        PdfSourceAnchor(3, 300, 600, PdfPagePoint(40, 48), PdfPagePoint(150, 48), bounds)
    @Test fun canonicalGeometryRoundTripsWithoutInventingTextOffsetsOrPageNumbers() {
        val source = anchor(listOf(first, PdfTextBounds(20.0, 60.0, 75.0, 78.0)))
        val json = PdfAnchorCodec.encode(source)
        assertEquals(source, PdfAnchorCodec.decode(json)); assertEquals(source, PdfAnchorCodec.verify(json, PdfAnchorCodec.key(source)))
        assertTrue(json.contains("\"pageIndex\":3")); assertFalse(json.contains("offset")); assertFalse(json.contains("text"))
        assertEquals(PdfAnchorCodec.key(source), PdfAnchorCodec.key(PdfAnchorCodec.decode(json)))
    }
    @Test fun sourceGeometryDefensivelyCopiesAndCannotBeMutatedThroughReturnedList() {
        val input = mutableListOf(first); val source = anchor(input); val hash = PdfAnchorCodec.key(source)
        input.clear(); assertEquals(listOf(first), source.bounds)
        assertTrue(runCatching { (source.bounds as MutableList<*>).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(hash, PdfAnchorCodec.key(source))
    }
    @Test fun sameRealWordHasOneSelectionIdentityDespiteDifferentTouchPoints() {
        val source = anchor()
        val otherTouch = PdfSourceAnchor(3, 300, 600, PdfPagePoint(41, 48), PdfPagePoint(151, 48), source.bounds)
        assertNotEquals(PdfAnchorCodec.key(source), PdfAnchorCodec.key(otherTouch))
        assertEquals(PdfAnchorCodec.selectionKey(source, "alpha 🌿"), PdfAnchorCodec.selectionKey(otherTouch, "alpha 🌿"))
        assertNotEquals(PdfAnchorCodec.selectionKey(source, "alpha 🌿"), PdfAnchorCodec.selectionKey(otherTouch, "alpha"))
    }
    @Test fun nativeBoundaryPointsAreNotReorderedAndWordSelectionMayHaveEqualPoints() {
        val reverse = PdfSourceAnchor(0, 300, 600, PdfPagePoint(150, 48), PdfPagePoint(40, 48), listOf(first))
        assertEquals(reverse, PdfAnchorCodec.decode(PdfAnchorCodec.encode(reverse)))
        val word = PdfSourceAnchor(0, 300, 600, PdfPagePoint(40, 48), PdfPagePoint(40, 48), listOf(first))
        assertEquals(word, PdfAnchorCodec.decode(PdfAnchorCodec.encode(word)))
        assertNotEquals(PdfAnchorCodec.key(reverse), PdfAnchorCodec.key(word))
    }
    @Test fun emptyNonFiniteInvertedOrOutsideNativeRectanglesCannotBeAnnotationEvidence() {
        val invalid = listOf(emptyList(), listOf(first.copy(left = Double.NaN)), listOf(first.copy(bottom = Double.POSITIVE_INFINITY)),
            listOf(first.copy(right = first.left)), listOf(first.copy(left = -.1)), listOf(first.copy(top = -.1)),
            listOf(first.copy(right = 300.1)), listOf(first.copy(bottom = 600.1)), List(257) { first })
        for (bounds in invalid) assertTrue(runCatching { anchor(bounds) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun invalidPageDimensionsAndPointsAreRejectedWithoutClamping() {
        for ((page, width, height, point) in listOf(
            Case(-1, 300, 600, PdfPagePoint(1, 1)), Case(0, 0, 600, PdfPagePoint(1, 1)),
            Case(0, 300, 0, PdfPagePoint(1, 1)), Case(0, 300, 600, PdfPagePoint(-1, 1)),
            Case(0, 300, 600, PdfPagePoint(300, 1)), Case(0, 300, 600, PdfPagePoint(1, 600))))
            assertTrue(runCatching { PdfSourceAnchor(page, width, height, point, point, listOf(first)) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun unknownDuplicateMissingNonCanonicalAndWrongVersionPayloadsAreRejected() {
        val json = PdfAnchorCodec.encode(anchor())
        for (invalid in listOf(json.replace("\"version\":1", "\"version\":2"),
            json.replace("\"version\":1", "\"version\":1,\"version\":1"), json.replace("\"version\":1", "\"other\":1"),
            json.replace("\"pageIndex\":3", "\"pageIndex\":3.0"), json.replace("\"pageIndex\":3", "\"pageIndex\":\"3\""),
            json.replace("\"pageIndex\":3", "\"pageIndex\":2147483648"), " $json", "$json{}",
            json.replace("20.25", "20.250"), json.replace("20.25", "NaN"), json.replace("20.25", "1e9999")))
            assertTrue(invalid, runCatching { PdfAnchorCodec.decode(invalid) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun hashIsBoundToTheCompletePagePointsAndNativeGeometry() {
        val source = anchor(); val json = PdfAnchorCodec.encode(source); val hash = PdfAnchorCodec.key(source)
        assertTrue(runCatching { PdfAnchorCodec.verify(json.replace("\"pageIndex\":3", "\"pageIndex\":2"), hash) }.isFailure)
        assertTrue(runCatching { PdfAnchorCodec.verify(json.replace("20.25", "20.5"), hash) }.isFailure)
        assertTrue(runCatching { PdfAnchorCodec.verify(json, hash.uppercase()) }.isFailure)
    }
    @Test fun payloadAndNativeRectangleLimitsRejectOversizedOrMalformedInputBeforeDecoding() {
        for (json in listOf(" ".repeat(65537), "\uD800", "\u0000", "{}", "[]"))
            assertTrue(runCatching { PdfAnchorCodec.decode(json) }.exceptionOrNull() is IllegalArgumentException)
        val source = anchor(List(PdfSourceAnchor.MAX_BOUNDS) { first })
        assertEquals(source, PdfAnchorCodec.decode(PdfAnchorCodec.encode(source)))
    }
    @Test fun exactSelectedTextAndOptionalNoteKeepWhitespaceAndUnicodeWithinSeparateLimits() {
        validatePdfAnnotation("  alpha 🌿\nı  ", PdfHighlightColor.GREEN, " note\n ")
        validatePdfAnnotation("x".repeat(5000), PdfHighlightColor.PINK, "n".repeat(2000))
        validatePdfAnnotation("alpha", null, "note")
        for ((text, note) in listOf("x".repeat(5001) to null, "alpha" to "n".repeat(2001),
            "\uD800" to null, "alpha" to "\uDC00", "alpha\u0000" to null, "alpha" to "\n ", " " to null))
            assertTrue(runCatching { validatePdfAnnotation(text, PdfHighlightColor.YELLOW, note) }.isFailure)
        assertTrue(runCatching { validatePdfAnnotation("alpha", null, null) }.isFailure)
    }
    private data class Case(val page: Int, val width: Int, val height: Int, val point: PdfPagePoint)
}
