package com.yaprak.reader.fixtures

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import java.util.UUID

/** Detached domain evidence; original-file and actual Room proofs have separate adapter tests. */
internal object DocxAssistantSources {
    val book = Book(UUID.randomUUID().toString(), "Private title", "Private author", BookFormat.DOCX,
        "private-source.docx", "owned.docx", 123, "a".repeat(64), 1)
    val scope = BookTextScope(book.id, book.sha256)
    fun section(count: Int = 12) = BookSourceSection(scope, UUID.randomUUID().toString(), 0,
        "Text/document.xhtml", "b".repeat(64), "Belge metni", 0, count, BookFormat.DOCX)
    fun hit(section: BookSourceSection, index: Int) = LibraryTextHit(index + 1L, section.generation, book,
        LibraryTextTarget(book.id, book.sha256, section.resourceHref, section.resourceSha256,
            sourceFormat = BookFormat.DOCX), section.chapterTitle, "Literal source " + index + ". 🌿", "Literal source", 0, index)
    fun sources(section: BookSourceSection = section(), hits: List<LibraryTextHit> =
        (0 until section.chunkCount).map { hit(section, it) }) = PreparedBookSources(UUID.randomUUID().toString(),
        scope, "", section.chunkCount, hits.mapIndexed { index, hit -> BookSourceReference("S" + (index + 1), hit) }, section)
}
