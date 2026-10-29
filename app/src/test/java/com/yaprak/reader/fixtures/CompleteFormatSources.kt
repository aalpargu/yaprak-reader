package com.yaprak.reader.fixtures

import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import java.util.UUID

/** Detached source evidence only; actual original-byte/Room proof has separate adapter tests. */
internal object CompleteFormatSources {
    fun book(format: BookFormat) = AssistantFixtures.book.copy(format = format, originalName = "source.${format.extension}",
        storageName = "source.${format.extension}", pageCount = if (format == BookFormat.PDF) 3 else null)
    fun section(format: BookFormat, count: Int = 12) = BookSourceSection(AssistantFixtures.scope, UUID.randomUUID().toString(),
        (if (format == BookFormat.DOCX) 0 else 2), when (format) { BookFormat.PDF -> ""; BookFormat.FB2 -> "Text/b1-p3.xhtml"; BookFormat.DOCX -> "Text/document.xhtml"; else -> "Text/part0003.xhtml" }, "b".repeat(64),
        when (format) { BookFormat.DOCX -> "Belge metni"; BookFormat.TXT, BookFormat.FB2 -> "Metin 3"; else -> null }, 0, count, format, if (format == BookFormat.PDF) 2 else null)
    fun sources(section: BookSourceSection, book: Book = book(section.sourceFormat), suffix: String = "") =
        PreparedBookSources(UUID.randomUUID().toString(), section.scope, "", section.chunkCount,
            (0 until section.chunkCount).map { index ->
                val text = "Güneş doğdu. Kaynak $index. 🌿 $suffix"
                BookSourceReference("S${index + 1}", LibraryTextHit(index + 1L, section.generation, book,
                    LibraryTextTarget(book.id, book.sha256, section.resourceHref, section.resourceSha256,
                        sourceFormat = section.sourceFormat, pdfPageIndex = section.pdfPageIndex),
                    section.chapterTitle, text, text, section.readingOrderIndex, section.firstChunkIndex + index))
            }, section)
}
