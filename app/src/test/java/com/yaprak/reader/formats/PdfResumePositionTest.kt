package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.formats.pdf.PdfPosition
import com.yaprak.reader.formats.pdf.PdfResumePosition
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfResumePositionTest {
    @Test fun originalPageAnchorsRoundTripWithoutChangingTheLocationEnvelope() {
        for (page in listOf(0, 1, 19)) for (anchor in listOf(0, 1, 48_321, 500_000, 999_999, 1_000_000)) {
            val position = PdfResumePosition(page, anchor)
            val location = PdfPosition.encode(position)
            assertEquals(1, location.locatorVersion)
            assertEquals("pdf", location.resourceHref)
            val json = JSONObject(location.locatorJson)
            assertEquals(setOf("format", "version", "pageIndex", "anchorY"), json.keys().asSequence().toSet())
            assertEquals("pdf", json.get("format"))
            assertEquals(2, json.get("version"))
            assertEquals(page, json.get("pageIndex"))
            assertEquals(anchor, json.get("anchorY"))
            assertEquals(position, PdfPosition.decodeResumeOrNull(location, 20))
            assertEquals(position, PdfPosition.decodeResume(location, 20))
            assertEquals(page, PdfPosition.decodeOrNull(location, 20))
            assertEquals(page, PdfPosition.decode(location, 20))
            assertEquals(location, PdfPosition.encode(PdfPosition.decodeResume(location, 20)))
        }
    }

    @Test fun pageOnlyEncodingAndLegacyAdditionalKeysRetainTheirOriginalMeaning() {
        val legacy = PdfPosition.encode(7)
        assertEquals(legacy, PdfPosition.encode(PdfResumePosition(7)))
        assertEquals(setOf("format", "version", "pageIndex"), JSONObject(legacy.locatorJson).keys().asSequence().toSet())
        assertEquals(PdfResumePosition(7), PdfPosition.decodeResumeOrNull(legacy, 8))
        val additional = legacy.copy(locatorJson = """{"format":"pdf","version":1,"pageIndex":7,"anchorY":600000,"future":{"v":true}}""")
        assertEquals(PdfResumePosition(7), PdfPosition.decodeResumeOrNull(additional, 8))
        assertEquals(7, PdfPosition.decodeOrNull(additional, 8))
        assertEquals(legacy, PdfPosition.encode(PdfPosition.decodeResume(additional, 8)))
    }

    @Test fun versionTwoRequiresAllAndOnlyTypedBoundedFields() {
        val invalid = mutableListOf(
            """{"format":"pdf","version":2,"pageIndex":0}""",
            """{"format":"pdf","version":2,"pageIndex":0,"anchorY":1,"zoom":1}""",
            """{"format":"pdf","version":2,"pageIndex":0,"anchorY":1,"extra":null}""",
            """{"format":"pdf","version":2,"anchorY":1}""",
            """{"format":"pdf","pageIndex":0,"anchorY":1}""",
            """{"version":2,"pageIndex":0,"anchorY":1}""",
        )
        for (anchor in listOf("null", "true", "\"1\"", "1.0", "1.5", "-1", "1000001", "2147483648", "1e309", "NaN", "Infinity", "[]", "{}"))
            invalid += """{"format":"pdf","version":2,"pageIndex":0,"anchorY":$anchor}"""
        for (page in listOf("null", "true", "\"0\"", "0.0", "0.5", "-1", "20", "2147483648", "1e309", "NaN", "Infinity", "[]", "{}"))
            invalid += """{"format":"pdf","version":2,"pageIndex":$page,"anchorY":1}"""
        for (version in listOf("null", "true", "\"2\"", "2.0", "2.5", "0", "3", "2147483648", "1e309", "NaN", "Infinity"))
            invalid += """{"format":"pdf","version":$version,"pageIndex":0,"anchorY":1}"""
        for (format in listOf("null", "true", "2", "\"PDF\"", "\"epub\"", "[]", "{}"))
            invalid += """{"format":$format,"version":2,"pageIndex":0,"anchorY":1}"""
        for (json in invalid) {
            val location = ReadingLocation(json, "pdf")
            assertNull("Accepted malformed resume: $json", PdfPosition.decodeResumeOrNull(location, 20))
            assertNull("Accepted malformed navigation: $json", PdfPosition.decodeOrNull(location, 20))
        }
    }

    @Test fun foreignOversizedOrOutOfRangeLocationsCannotBecomeExplicitNavigation() {
        val valid = PdfPosition.encode(PdfResumePosition(1, 750_000))
        val invalid = listOf(null, valid.copy(locatorVersion = 2), valid.copy(resourceHref = "chapter.xhtml"),
            valid.copy(resourceHref = "PDF"), valid.copy(locatorJson = "broken"),
            valid.copy(locatorJson = ""), valid.copy(locatorJson = "x".repeat(4097)),
            PdfPosition.encode(PdfResumePosition(20, 750_000)))
        for (location in invalid) {
            assertNull(PdfPosition.decodeResumeOrNull(location, 20))
            assertNull(PdfPosition.decodeOrNull(location, 20))
            assertEquals(PdfResumePosition(0), PdfPosition.decodeResume(location, 20))
            assertEquals(0, PdfPosition.decode(location, 20))
        }
        for (count in listOf(Int.MIN_VALUE, -1, 0, 1)) {
            assertNull(PdfPosition.decodeResumeOrNull(valid, count))
            assertNull(PdfPosition.decodeOrNull(valid, count))
        }
        assertEquals(PdfResumePosition(1, 750_000), PdfPosition.decodeResumeOrNull(valid, 2))
        assertEquals(PdfResumePosition(Int.MAX_VALUE - 1, 1_000_000), PdfPosition.decodeResumeOrNull(
            PdfPosition.encode(PdfResumePosition(Int.MAX_VALUE - 1, 1_000_000)), Int.MAX_VALUE))
    }

    @Test fun invalidTypedPositionsCannotBeEncodedOrCreatedThroughCopy() {
        for (page in listOf(-1, Int.MIN_VALUE))
            assertTrue(runCatching { PdfResumePosition(page) }.exceptionOrNull() is IllegalArgumentException)
        for (anchor in listOf(Int.MIN_VALUE, -1, 1_000_001, Int.MAX_VALUE)) {
            assertTrue(runCatching { PdfResumePosition(0, anchor) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { PdfResumePosition(0, 1).copy(anchorY = anchor) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { PdfResumePosition(0).copy(pageIndex = -1) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(PdfPosition.encode(Int.MAX_VALUE), PdfPosition.encode(PdfResumePosition(Int.MAX_VALUE)))
    }
}
