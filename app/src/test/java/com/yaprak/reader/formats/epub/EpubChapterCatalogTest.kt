package com.yaprak.reader.formats.epub

import android.app.Application
import com.yaprak.reader.domain.search.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class EpubChapterCatalogTest {
    private val hrefs = listOf("EPUB/a.xhtml", "EPUB/b.xhtml", "EPUB/c.xhtml")
    private fun link(href: String, title: String = "Title", children: List<Link> = emptyList()) =
        Link(Url(href)!!, mediaType = MediaType.XHTML, title = title, children = children)
    private fun body(text: String, ids: Set<String>) = BoundedXhtmlTextParser.parseAnchored(
        ("<html xmlns='http://www.w3.org/1999/xhtml'><body>" + text + "</body></html>").toByteArray(), ids)
    private fun evidence(links: List<Link>, bodies: List<String> = listOf(
        "<h1 id='one'>ONE</h1><h2 id='two'>TWO</h2>", "<p>BRIDGE</p>", "<h1 id='end'>LAST</h1>")): ExtractedBookChapters {
        val catalog = EpubChapterCatalog(links, hrefs) {}
        val chunks = ArrayList<ExtractedTextChunk>(); val offsets = ArrayList<Int>()
        bodies.forEachIndexed { index, source ->
            val proof = body(source, catalog.wantedIds(index))
            catalog.accept(index, ('a' + index).toString().repeat(64), proof)
            if (proof.text.isNotBlank()) {
                chunks += ExtractedTextChunk(index, chunks.size, hrefs[index], ('a' + index).toString().repeat(64), null, proof.text)
                offsets += 0
            }
        }
        return catalog.chapters(ExtractedBookText("book", "f".repeat(64), chunks, hrefs.size), offsets)
    }
    private fun text(evidence: ExtractedBookChapters, chapter: ExtractedChapter): String =
        extractedChapterPieces(evidence, chapter.start, chapter.end).joinToString("") {
            evidence.document.chunks[it.chunkIndex].text.substring(it.excerpt.startUtf16, it.excerpt.endUtf16)
        }
    private fun refused(reason: TextExtractionFailure = TextExtractionFailure.UNSUPPORTED, block: () -> Unit) {
        try { block(); fail("Expected chapter refusal") }
        catch (failed: TextExtractionException) { assertEquals(reason, failed.reason) }
    }

    @Test fun sameFileHeadingsUseExactExclusiveBoundariesAndFinalChapterEndsAtBookEnd() {
        val proof = evidence(listOf(link("EPUB/a.xhtml#one", "One"), link("EPUB/a.xhtml#two", "Two"),
            link("EPUB/c.xhtml#end", "Last")))
        assertEquals(listOf("One", "Two", "Last"), proof.chapters.map { it.title })
        assertEquals("ONE\n", text(proof, proof.chapters[0]))
        assertEquals("TWO\nBRIDGE\n", text(proof, proof.chapters[1]))
        assertEquals("LAST\n", text(proof, proof.chapters[2]))
        assertEquals(listOf(1, 2, 1), proof.chapters.map { it.sourceCount })
    }

    @Test fun nestedParentIncludesChildrenAndEndsAtNextEqualDepthHeading() {
        val links = listOf(link("EPUB/a.xhtml#one", "Parent", listOf(link("EPUB/a.xhtml#one", "First child"),
            link("EPUB/a.xhtml#two", "Second child"))), link("EPUB/c.xhtml#end", "Last"))
        val proof = evidence(links)
        assertEquals(listOf(0, 1, 1, 0), proof.chapters.map { it.depth })
        assertEquals("ONE\nTWO\nBRIDGE\n", text(proof, proof.chapters[0]))
        assertEquals("ONE\n", text(proof, proof.chapters[1]))
        assertEquals("TWO\nBRIDGE\n", text(proof, proof.chapters[2]))
    }

    @Test fun encodedUnicodeAndLiteralPlusIdsResolveStrictly() {
        val proof = evidence(listOf(link("EPUB/a.xhtml#%CE%A9", "Omega"), link("EPUB/a.xhtml#a+b", "Plus")),
            listOf("<h1 id='Ω'>Ω TEXT</h1><h2 id='a+b'>PLUS</h2>", "<p>BRIDGE</p>", "<p>LAST</p>"))
        assertEquals("Ω TEXT\n", text(proof, proof.chapters.first()))
        assertEquals("PLUS\nBRIDGE\nLAST\n", text(proof, proof.chapters.last()))
        refused { evidence(listOf(link("EPUB/a.xhtml#%C0%AF"))) }
    }

    @Test fun missingHiddenDuplicateReversedRemoteAndZeroLengthSiblingBoundariesRefuse() {
        for (links in listOf(listOf(link("EPUB/a.xhtml#absent")), listOf(link("https://example.invalid/a#one")),
            listOf(link("EPUB/a.xhtml?q=1#one")), listOf(link("EPUB/a.xhtml#two"), link("EPUB/a.xhtml#one")),
            listOf(link("EPUB/a.xhtml#one"), link("EPUB/a.xhtml#one")))) refused { evidence(links) }
        refused { evidence(listOf(link("EPUB/a.xhtml#one")), listOf(
            "<h1 id='one'>One</h1><p id='one'>Duplicate</p>", "<p>B</p>", "<p>C</p>")) }
        refused { evidence(listOf(link("EPUB/a.xhtml#one")), listOf(
            "<div hidden='hidden' id='one'>Secret</div><p>Visible</p>", "<p>B</p>", "<p>C</p>")) }
    }

    @Test fun wholeFileTargetsEmptyCatalogAndBoundsRemainExplicit() {
        val proof = evidence(listOf(link("EPUB/a.xhtml", "All")))
        assertEquals("ONE\nTWO\nBRIDGE\nLAST\n", text(proof, proof.chapters.single()))
        assertTrue(evidence(emptyList()).chapters.isEmpty())
        refused(TextExtractionFailure.LIMIT) { evidence((0..MAX_BOOK_TOC_ENTRIES).map { link("EPUB/a.xhtml#one") }) }
        var deep = link("EPUB/a.xhtml#one")
        repeat(MAX_BOOK_TOC_DEPTH) { deep = link("EPUB/a.xhtml#one", children = listOf(deep)) }
        refused(TextExtractionFailure.LIMIT) { evidence(listOf(deep)) }
    }

    @Test fun tocFingerprintBindsTitleNestingAndTargetEvenWhenTheBodyIsUnchanged() {
        val one = evidence(listOf(link("EPUB/a.xhtml#one", "Original"))).chapters.single().tocSha256
        val renamed = evidence(listOf(link("EPUB/a.xhtml#one", "Renamed"))).chapters.single().tocSha256
        val moved = evidence(listOf(link("EPUB/a.xhtml#two", "Original"))).chapters.single().tocSha256
        assertNotEquals(one, renamed); assertNotEquals(one, moved)
        assertEquals(one, evidence(listOf(link("EPUB/a.xhtml#one", "Original"))).chapters.single().tocSha256)
    }
}
