package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.formats.epub.bookmarkTarget
import com.yaprak.reader.formats.epub.decodeEpubLocation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubLocationsTest {
    private val href = Url("EPUB/chapter.xhtml")!!
    private val order = listOf(Link(href, MediaType.XHTML))

    @Test fun `bookmark strips surrounding text but preserves precise locator and label`() {
        val locator = Locator(href, MediaType.XHTML, " Bölüm 2 ",
            locations = Locator.Locations(fragments = listOf("part-two"), progression = 0.35),
            text = Locator.Text(before = "private before", after = "future text", highlight = "selected text"))
        val target = bookmarkTarget(locator)
        assertEquals("Bölüm 2", target.label)
        val json = JSONObject(target.location.locatorJson)
        assertFalse(json.has("text"))
        assertFalse(json.has("title"))
        val decoded = decodeEpubLocation(target.location, order)!!
        assertEquals(locator.locations, decoded.locations)
        assertEquals(href, decoded.href)
        assertEquals(target.location, bookmarkTarget(locator.copy(title = "Changed", text = Locator.Text())).location)
    }

    @Test fun `corrupt foreign resource mismatched href version and external locations are rejected`() {
        val valid = bookmarkTarget(Locator(href, MediaType.XHTML)).location
        assertNotNull(decodeEpubLocation(valid, order))
        listOf(null, valid.copy(locatorVersion = 2), valid.copy(locatorJson = "broken"),
            valid.copy(resourceHref = "EPUB/other.xhtml"), valid.copy(locatorJson = "x".repeat(64_001)),
            bookmarkTarget(Locator(Url("EPUB/missing.xhtml")!!, MediaType.XHTML)).location,
            bookmarkTarget(Locator(Url("https://example.org/book.xhtml")!!, MediaType.XHTML)).location
        ).forEach { assertNull(decodeEpubLocation(it, order)) }
    }
}
