package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.formats.epub.EpubContents
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubWholeResourceChapterTest {
    private fun link(href: String, children: List<Link> = emptyList()) = Link(Url(href)!!, title = href, children = children)

    @Test fun `actual adjacent whole leaf TOC entries have exact resource and EOF ends`() {
        val order = listOf(link("a.xhtml"), link("b.xhtml"), link("c.xhtml"))
        val contents = EpubContents(order, order)
        assertEquals(listOf("0", "1", "2"), order.map { contents.wholeResourceEntryId(it.url().toString()) })
    }

    @Test fun `an unlisted continuation means the first file end is not the chapter end`() {
        val a = link("a.xhtml"); val b = link("b.xhtml"); val c = link("c.xhtml")
        val contents = EpubContents(listOf(a, c), listOf(a, b, c))
        assertNull(contents.wholeResourceEntryId("a.xhtml")); assertNull(contents.wholeResourceEntryId("b.xhtml"))
        assertEquals("1", contents.wholeResourceEntryId("c.xhtml"))
    }

    @Test fun `a parent chapter with child resources is not labeled a whole file chapter`() {
        val a = link("a.xhtml"); val b = link("b.xhtml"); val c = link("c.xhtml")
        val contents = EpubContents(listOf(a.copy(children = listOf(b)), c), listOf(a, b, c))
        assertNull(contents.wholeResourceEntryId("a.xhtml"))
        assertEquals("1", contents.wholeResourceEntryId("b.xhtml"))
        assertEquals("2", contents.wholeResourceEntryId("c.xhtml"))
    }

    @Test fun `a fragment start cannot claim the whole current resource`() {
        val contents = EpubContents(listOf(link("a.xhtml#start"), link("b.xhtml")), listOf(link("a.xhtml"), link("b.xhtml")))
        assertNull(contents.wholeResourceEntryId("a.xhtml"))
        assertEquals("1", contents.wholeResourceEntryId("b.xhtml"))
    }

    @Test fun `a fragment at the next TOC start cannot prove the preceding resource end`() {
        val contents = EpubContents(listOf(link("a.xhtml"), link("b.xhtml#start")), listOf(link("a.xhtml"), link("b.xhtml")))
        assertNull(contents.wholeResourceEntryId("a.xhtml")); assertNull(contents.wholeResourceEntryId("b.xhtml"))
    }

    @Test fun `duplicate TOC destinations keep partial chapter scopes unavailable`() {
        val contents = EpubContents(listOf(link("a.xhtml"), link("a.xhtml#part"), link("b.xhtml")),
            listOf(link("a.xhtml"), link("b.xhtml")))
        assertNull(contents.wholeResourceEntryId("a.xhtml"))
        assertEquals("2", contents.wholeResourceEntryId("b.xhtml"))
    }

    @Test fun `missing or truncated TOC never invents a chapter extent`() {
        val a = link("a.xhtml")
        assertNull(EpubContents(emptyList(), listOf(a)).wholeResourceEntryId("a.xhtml"))
        val order = List(5001) { link("$it.xhtml") }
        val contents = EpubContents(order, order)
        assertTrue(contents.contents.truncated); assertNull(contents.wholeResourceEntryId("0.xhtml"))
    }

    @Test fun `duplicate reading order resources cannot supply an unambiguous chapter end`() {
        val a = link("a.xhtml"); val b = link("b.xhtml")
        val contents = EpubContents(listOf(a, b), listOf(a, a, b))
        assertNull(contents.wholeResourceEntryId("a.xhtml")); assertNull(contents.wholeResourceEntryId("b.xhtml"))
    }

    @Test fun `out of order TOC disables chapter extents instead of guessing`() {
        val a = link("a.xhtml"); val b = link("b.xhtml")
        val contents = EpubContents(listOf(b, a), listOf(a, b))
        assertNull(contents.wholeResourceEntryId("a.xhtml")); assertNull(contents.wholeResourceEntryId("b.xhtml"))
    }

    @Test fun `unknown external next entry cannot serve as a local chapter boundary`() {
        val a = link("a.xhtml"); val b = link("b.xhtml")
        val contents = EpubContents(listOf(a, link("https://example.org/elsewhere"), b), listOf(a, b))
        assertNull(contents.wholeResourceEntryId("a.xhtml")); assertEquals("2", contents.wholeResourceEntryId("b.xhtml"))
        assertNull(contents.wholeResourceEntryId("unknown.xhtml"))
    }
}
