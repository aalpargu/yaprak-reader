package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.formats.epub.selectedTextOrNull
import com.yaprak.reader.reader.SelectedText
import com.yaprak.reader.reader.TextSelectionException
import com.yaprak.reader.reader.TextSelectionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
class EpubTextSelectionTest {
    private val href = Url("EPUB/chapter.xhtml")!!
    private val order = listOf(Link(href, MediaType.XHTML))
    private val current = Locator(href, MediaType.XHTML,
        locations = Locator.Locations(progression = 0.25))

    private fun selected(highlight: String) = current.copy(text = Locator.Text(
        before = "future or private context before", highlight = highlight,
        after = "future or private context after"))

    @Test fun `only exact selected text crosses the reader boundary`() {
        val preview = selectedTextOrNull(selected(" Seçilen metin "), current, current, order)
        assertEquals(SelectedText(" Seçilen metin "), preview)
        assertFalse(preview.toString().contains("future or private context"))
        assertNull(selectedTextOrNull(null, current, current, order))
        assertNull(selectedTextOrNull(selected(" \n\t "), current, current, order))
    }

    @Test fun `five thousand UTF-16 units are accepted and longer selection fails explicitly`() {
        assertEquals(5000, selectedTextOrNull(selected("a".repeat(5000)), current, current, order)?.text?.length)
        val error = assertThrows(TextSelectionException::class.java) {
            selectedTextOrNull(selected("a".repeat(5001)), current, current, order)
        }
        assertEquals(TextSelectionFailure.TOO_LONG, error.reason)
        // Supplementary characters occupy two UTF-16 code units.
        val surrogateError = assertThrows(TextSelectionException::class.java) {
            selectedTextOrNull(selected("😀".repeat(2501)), current, current, order)
        }
        assertEquals(TextSelectionFailure.TOO_LONG, surrogateError.reason)
    }

    @Test fun `foreign absolute and missing resources are rejected`() {
        listOf("EPUB/missing.xhtml", "https://example.org/chapter.xhtml", "//example.org/chapter.xhtml").forEach { value ->
            val error = assertThrows(TextSelectionException::class.java) {
                selectedTextOrNull(selected("metin").copy(href = Url(value)!!), current, current, order)
            }
            assertEquals(TextSelectionFailure.INVALID_RESOURCE, error.reason)
        }
    }

    @Test fun `navigation to another resource during capture is rejected`() {
        val next = Url("EPUB/next.xhtml")!!
        val after = current.copy(href = next)
        val error = assertThrows(TextSelectionException::class.java) {
            selectedTextOrNull(selected("metin"), current, after, order + Link(next, MediaType.XHTML))
        }
        assertEquals(TextSelectionFailure.INVALID_RESOURCE, error.reason)

        // Ordinary pagination in the same resource must not invalidate a real selection.
        val moved = current.copy(locations = Locator.Locations(progression = 0.40))
        assertEquals(SelectedText("metin"), selectedTextOrNull(selected("metin"), current, moved, order))
    }
}
