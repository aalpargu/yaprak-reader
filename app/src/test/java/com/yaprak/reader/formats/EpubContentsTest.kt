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
class EpubContentsTest {
    private fun link(href: String, title: String? = null, children: List<Link> = emptyList()) = Link(Url(href)!!, title = title, children = children)

    @Test fun `nested titles keep order depth duplicate anchors and original destinations`() {
        val first = link("EPUB/one.xhtml#start", "Aynı başlık")
        val child = link("EPUB/one.xhtml#part%202", "Aynı başlık")
        val parent = first.copy(children = listOf(child))
        val final = link("EPUB/three.xhtml", " Son bölüm ")
        val toc = EpubContents(listOf(parent, final), listOf(link("EPUB/one.xhtml"), final))
        assertEquals(listOf("Aynı başlık", "Aynı başlık", "Son bölüm"), toc.contents.entries.map { it.title })
        assertEquals(listOf(0, 1, 0), toc.contents.entries.map { it.depth })
        assertEquals(3, toc.contents.entries.map { it.id }.toSet().size)
        assertEquals(child, toc.destination(toc.contents.entries[1].id))
        assertTrue(toc.contents.entries.all { it.navigable })
        assertNull(toc.destination("EPUB/one.xhtml"))
        assertFalse(toc.contents.truncated)
    }

    @Test fun `external missing and query destinations cannot navigate but children remain available`() {
        val local = link("EPUB/one.xhtml", "Geçerli")
        val external = link("https://example.org/book.xhtml", "Dış bağlantı", listOf(local))
        val missing = link("EPUB/missing.xhtml", "Grup", listOf(local.copy(title = "Alt başlık")))
        val toc = EpubContents(listOf(external, missing, link("//example.org/book.xhtml"), link("EPUB/one.xhtml?other=1")), listOf(local, external))
        assertEquals(listOf(false, true, false, true, false, false), toc.contents.entries.map { it.navigable })
        toc.contents.entries.filterNot { it.navigable }.forEach { assertNull(toc.destination(it.id)) }
        assertEquals("Alt başlık", toc.contents.entries[3].title)
    }

    @Test fun `absent TOC remains empty instead of inventing chapters`() {
        val toc = EpubContents(emptyList(), listOf(link("EPUB/one.xhtml")))
        assertTrue(toc.contents.entries.isEmpty())
        assertFalse(toc.contents.truncated)
        assertNull(toc.destination("0"))
    }

    @Test fun `very large TOC is bounded and truncation is explicit`() {
        val resource = link("chapter.xhtml")
        val toc = EpubContents(List(5001) { resource.copy(title = "Bölüm $it") }, listOf(resource))
        assertEquals(5000, toc.contents.entries.size)
        assertTrue(toc.contents.truncated)
        assertNull(toc.destination("5000"))
        assertFalse(EpubContents(List(5000) { resource }, listOf(resource)).contents.truncated)
    }

    @Test fun `deep trees do not recurse and blank titles have honest labels`() {
        val resource = link("chapter.xhtml", " ")
        var nested = resource
        repeat(2000) { nested = resource.copy(children = listOf(nested)) }
        val toc = EpubContents(listOf(nested), listOf(resource))
        assertEquals(2001, toc.contents.entries.size)
        assertEquals(2000, toc.contents.entries.last().depth)
        assertEquals("Adsız başlık", toc.contents.entries.last().title)
        assertFalse(toc.contents.truncated)
    }
}
