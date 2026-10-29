package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.formats.pdf.PdfPosition
import com.yaprak.reader.formats.pdf.pdfBitmapSize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfPositionTest {
    @Test fun `PDF position round trips and does not accept foreign or out of range anchors`() {
        assertEquals(9, PdfPosition.decode(PdfPosition.encode(9), 10))
        val valid = PdfPosition.encode(9)
        listOf(null, valid.copy(resourceHref = "chapter.xhtml"), valid.copy(locatorVersion = 2),
            valid.copy(locatorJson = "broken"), PdfPosition.encode(10),
            ReadingLocation("""{"format":"epub","version":1,"pageIndex":9}""", "pdf"),
            ReadingLocation("""{"format":"pdf","version":2,"pageIndex":9}""", "pdf"),
            ReadingLocation("""{"format":"pdf","version":1,"pageIndex":-1}""", "pdf"),
            ReadingLocation("""{"format":"pdf","version":1,"pageIndex":1.5}""", "pdf")
        ).forEach { assertEquals(0, PdfPosition.decode(it, 10)) }
    }

    @Test fun `render dimensions remain bounded for pathological page shapes`() {
        listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE,
            612 to 792, 792 to 612).forEach { (width, height) ->
            val (w, h) = pdfBitmapSize(width, height, Int.MAX_VALUE)
            assertTrue(w in 1..2048 && h in 1..2048)
            assertTrue(w.toLong() * h * 4 <= 16L * 1024 * 1024)
        }
        assertEquals(800 to 1600, pdfBitmapSize(400, 800, 400))
    }

    @Test fun `explicit bookmark navigation distinguishes page one from malformed anchors`() {
        assertEquals(0, PdfPosition.decodeOrNull(PdfPosition.encode(0), 2))
        assertEquals(1, PdfPosition.decodeOrNull(PdfPosition.encode(1), 2))
        val valid = PdfPosition.encode(0)
        listOf(null, valid.copy(locatorVersion = 2), valid.copy(resourceHref = "one.xhtml"),
            valid.copy(locatorJson = "x".repeat(4097)), valid.copy(locatorJson = "broken"),
            PdfPosition.encode(2),
            valid.copy(locatorJson = """{"format":"pdf","version":1.5,"pageIndex":0}"""),
            valid.copy(locatorJson = """{"format":"pdf","version":"1","pageIndex":0}"""),
            valid.copy(locatorJson = """{"format":"pdf","version":1,"pageIndex":"0"}"""),
            valid.copy(locatorJson = """{"format":"epub","version":1,"pageIndex":0}""")
        ).forEach { assertNull(PdfPosition.decodeOrNull(it, 2)) }
        assertNull(PdfPosition.decodeOrNull(valid, 0))
    }

    @Test fun `invalid render dimensions are rejected before native allocation`() {
        listOf(Triple(0, 100, 100), Triple(100, -1, 100), Triple(100, 100, 0)).forEach { (w, h, v) ->
            assertTrue(runCatching { pdfBitmapSize(w, h, v) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
