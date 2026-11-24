package com.yaprak.reader.domain.pdfink

import org.junit.Assert.*
import org.junit.Test

class PdfInkTest {
    private fun rect(left: Double, top: Double, right: Double, bottom: Double) = PdfInkTextRect(left, top, right, bottom)

    @Test fun geometryCodecRoundTripsAndIsCanonical() {
        val segments = listOf(PdfInkSegment(1200, listOf(PdfInkPoint(0, 0), PdfInkPoint(100_000, 55_555))),
            PdfInkSegment(50, listOf(PdfInkPoint(7, 8))))
        val text = PdfInkGeometryCodec.encode(segments)
        assertEquals("1;1200,0,0,100000,55555;50,7,8", text)
        assertEquals(segments, PdfInkGeometryCodec.decode(text))
    }

    @Test fun geometryCodecRejectsAnythingItDidNotWrite() {
        listOf("", "1", "2;1200,0,0", "1;1200,0", "1;1200,0,0,5", "1;01200,0,0", "1;1200,-1,0", "1;1200,0,100001",
            "1;49,0,0", "1;15001,0,0", "1;1200,0,0;", "1;1200, 0,0", "1;1200,0,0x", "1;+1200,0,0").forEach { bad ->
            assertTrue(bad, runCatching { PdfInkGeometryCodec.decode(bad) }.isFailure)
        }
        val tooMany = "1;" + List(MAX_PDF_INK_SEGMENTS + 1) { "1200,0,0" }.joinToString(";")
        assertTrue(runCatching { PdfInkGeometryCodec.decode(tooMany) }.isFailure)
    }

    @Test fun lineThicknessFollowsTheTextHeight() {
        // A 24 pt heading and 9 pt footnote on a 792 pt page.
        val heading = pdfInkWidthForLine(24.0 / 792)
        val footnote = pdfInkWidthForLine(9.0 / 792)
        assertEquals(3030, heading)
        assertEquals(1136, footnote)
        assertTrue(heading > footnote * 2)
        assertEquals(DEFAULT_PDF_INK_WIDTH, pdfInkWidthForLine(0.0))
        assertEquals(DEFAULT_PDF_INK_WIDTH, pdfInkWidthForLine(Double.NaN))
        assertEquals(MIN_PDF_INK_WIDTH, pdfInkWidthForLine(0.00001))
        assertEquals(MAX_PDF_INK_WIDTH, pdfInkWidthForLine(0.9))
    }

    @Test fun alignedBandsSnapToEachLineWithItsOwnHeight() {
        val rects = listOf(
            // A heading split into two runs on the same line.
            rect(0.10, 0.10, 0.30, 0.14), rect(0.31, 0.10, 0.60, 0.14),
            // Body line below it.
            rect(0.10, 0.20, 0.90, 0.22),
            // Last body line, only the words up to the drag end.
            rect(0.10, 0.25, 0.45, 0.27),
        )
        val segments = pdfInkAlignedSegments(rects.shuffled(java.util.Random(4)))
        assertEquals(3, segments.size)
        val (heading, body, last) = segments
        assertEquals(listOf(PdfInkPoint(10_000, 12_000), PdfInkPoint(60_000, 12_000)), heading.points)
        assertEquals(4000, heading.width)
        assertEquals(listOf(PdfInkPoint(10_000, 21_000), PdfInkPoint(90_000, 21_000)), body.points)
        assertEquals(2000, body.width)
        assertEquals(45_000, last.points[1].x)
        // Every band is horizontal.
        assertTrue(segments.all { it.points.size == 2 && it.points[0].y == it.points[1].y })
    }

    @Test fun aSuperscriptDoesNotStartANewLineOrInflateTheBand() {
        val segments = pdfInkAlignedSegments(listOf(rect(0.1, 0.30, 0.4, 0.32), rect(0.41, 0.296, 0.43, 0.308),
            rect(0.44, 0.30, 0.7, 0.32)))
        assertEquals(1, segments.size)
        assertEquals(2000, segments.single().width)
        assertEquals(70_000, segments.single().points.last().x)
    }

    @Test fun invalidRectsAreIgnored() {
        assertTrue(pdfInkAlignedSegments(listOf(rect(0.5, 0.5, 0.4, 0.6), rect(0.1, Double.NaN, 0.2, 0.3))).isEmpty())
    }

    @Test fun simplificationKeepsEndsDropsJitterAndCapsPoints() {
        val jitter = listOf(PdfInkPoint(0, 0), PdfInkPoint(10, 10), PdfInkPoint(20, 0), PdfInkPoint(1000, 0), PdfInkPoint(1005, 0))
        assertEquals(listOf(PdfInkPoint(0, 0), PdfInkPoint(1000, 0), PdfInkPoint(1005, 0)), simplifyPdfInkPoints(jitter))
        val long = (0 until 10_000).map { PdfInkPoint(it * 10, (it * 300) % 100_000) }
        val simplified = simplifyPdfInkPoints(long)
        assertTrue(simplified.size <= MAX_PDF_INK_POINTS)
        assertEquals(long.first(), simplified.first()); assertEquals(long.last(), simplified.last())
        validatePdfInkSegments(listOf(PdfInkSegment(1000, simplified)))
    }

    @Test fun strokeValidationBoundsEverything() {
        val ok = PdfInkStroke("id", 0, com.yaprak.reader.domain.pdfannotations.PdfHighlightColor.YELLOW, PdfInkMode.FREEHAND,
            listOf(PdfInkSegment(1000, listOf(PdfInkPoint(1, 1)))), 0)
        validatePdfInkStroke(ok)
        listOf(ok.copy(id = ""), ok.copy(pageIndex = -1), ok.copy(createdAt = -1), ok.copy(segments = emptyList()),
            ok.copy(segments = listOf(PdfInkSegment(1000, emptyList())))).forEach {
            assertTrue(runCatching { validatePdfInkStroke(it) }.isFailure)
        }
    }
}
