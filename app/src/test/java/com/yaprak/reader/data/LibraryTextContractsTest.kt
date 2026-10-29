package com.yaprak.reader.data

import com.yaprak.reader.data.search.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import org.junit.Assert.*
import org.junit.Test

class LibraryTextContractsTest {
    private val book = Book("book", "Title", null, BookFormat.EPUB, "b.epub", "b.epub", 5, "a".repeat(64), 1)
    private val chunk = ExtractedTextChunk(0, 0, "chapter.xhtml", "b".repeat(64), "Chapter", "Original 🌿 text")

    @Test fun `PDF addresses require real bounded pages and cannot mix EPUB href proof`() {
        val pdf = LibraryTextTarget(book.id, book.sha256, "", chunk.resourceSha256,
            sourceFormat = BookFormat.PDF, pdfPageIndex = 0)
        assertTrue(pdf.isValid)
        for (invalid in listOf(pdf.copy(pdfPageIndex = null), pdf.copy(pdfPageIndex = -1),
            pdf.copy(pdfPageIndex = MAX_INDEX_BOOK_RESOURCES), pdf.copy(resourceHref = "page/1"),
            pdf.copy(sourceFormat = BookFormat.EPUB), pdf.copy(resourceSha256 = "short"))) assertFalse(invalid.isValid)
        assertFalse(LibraryTextTarget(book.id, book.sha256, "chapter.xhtml", chunk.resourceSha256, pdfPageIndex = 0).isValid)
    }

    @Test fun `PDF chunks carry consistent page format total and page text proof`() {
        val pdf = book.copy(format = BookFormat.PDF, pageCount = 3)
        val page = chunk.copy(readingOrderIndex = 2, resourceHref = "", chapterTitle = null,
            sourceFormat = BookFormat.PDF, pdfPageIndex = 2)
        fun snapshot(chunks: List<ExtractedTextChunk>, count: Int = 3, format: BookFormat = BookFormat.PDF) =
            ExtractedBookText(pdf.id, pdf.sha256, chunks, count, sourceFormat = format)
        assertTrue(validateDocument(pdf, snapshot(listOf(page))) > 0)
        for (invalid in listOf(page.copy(pdfPageIndex = 1), page.copy(sourceFormat = BookFormat.EPUB),
            page.copy(chapterTitle = "Fake chapter"), page.copy(resourceHref = "page/3"))) {
            assertEquals(TextExtractionFailure.INVALID,
                (runCatching { validateDocument(pdf, snapshot(listOf(invalid))) }.exceptionOrNull() as TextExtractionException).reason)
        }
        assertTrue(runCatching { validateDocument(pdf, snapshot(listOf(page), count = 4)) }.isFailure)
        assertTrue(runCatching { validateDocument(pdf, snapshot(listOf(page), format = BookFormat.EPUB)) }.isFailure)
    }

    @Test fun `chapter proof accepts only bounded current local href and complete hashes`() {
        val target = LibraryTextTarget(book.id, book.sha256, "OPS/chapter%20one.xhtml", chunk.resourceSha256)
        assertTrue(target.isValid)
        for (href in listOf("https://example.com/a", "//remote/a", "/a", "../a", "a/./b", "a/%2e%2e/b",
            "a?query", "a#fragment", "a\\b", "a%00b", "a\nb", "x".repeat(2049))) {
            assertFalse(href, target.copy(resourceHref = href).isValid)
        }
        assertFalse(target.copy(extractionVersion = 2).isValid)
        assertFalse(target.copy(contentSha256 = "partial").isValid)
        assertFalse(target.copy(resourceSha256 = "B".repeat(64)).isValid)
    }

    @Test fun `query cutoff snippet and literal terms preserve whole Unicode code points`() {
        assertEquals(199, limitLibraryTextQuery("x".repeat(199) + "🌿tail").length)
        assertEquals(listOf("istanbul", "ışık".replace('ı','i')), libraryTextQueryTerms("İSTANBUL IŞIK"))
        assertEquals(listOf("or", "near", "literal"), libraryTextQueryTerms("\"OR\" NEAR literal*"))
        val text = "🌿".repeat(100) + " " + "prefix ".repeat(20) + "İSTANBUL " + "🌿".repeat(250)
        val snippet = libraryTextSnippet(text, listOf("istanbul"))
        assertTrue(snippet.contains("İSTANBUL")); assertTrue(wellFormedIndexText(snippet))
        assertTrue(snippet.length <= 402)
        assertFalse(wellFormedIndexText("\uD800"))
    }

    @Test fun `document snapshot detaches caller lists and rejects false source identities and invalid chunk order`() {
        val source = mutableListOf(chunk)
        val document = ExtractedBookText(book.id, book.sha256, source, 1)
        source.clear(); assertEquals(1, document.chunks.size)
        assertTrue(validateDocument(book, document) >= indexedChunkStringBytes(book.id, book.sha256, chunk))
        for (invalid in listOf(chunk.copy(chunkIndex = 1), chunk.copy(resourceHref = "../chapter.xhtml"),
            chunk.copy(resourceSha256 = "short"), chunk.copy(text = "\uD800"),
            chunk.copy(text = "x".repeat(MAX_INDEX_CHUNK_TEXT + 1)))) {
            try { validateDocument(book, ExtractedBookText(book.id, book.sha256, listOf(invalid), 1)); fail("Invalid source was accepted") }
            catch (failure: TextExtractionException) { assertEquals(TextExtractionFailure.INVALID, failure.reason) }
        }
        try { validateDocument(book, ExtractedBookText(book.id, book.sha256, List(MAX_INDEX_BOOK_CHUNKS + 1) { chunk }, 1)); fail("Limit ignored") }
        catch (failure: TextExtractionException) { assertEquals(TextExtractionFailure.LIMIT, failure.reason) }
    }
}
