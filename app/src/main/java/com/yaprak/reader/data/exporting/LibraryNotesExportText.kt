package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.domain.passages.EpubRangeAnchorCodec
import com.yaprak.reader.domain.passages.isWellFormedEpubAnnotationText
import com.yaprak.reader.domain.pdfannotations.PdfAnchorCodec
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceTextAnchor

/** Applied before retaining matching source rows and repeated on detached renderer input. */
internal class LibraryNotesExportBudget(query: String) {
    private var size = 0
    private var count = 0
    private val identities = HashSet<LibraryNoteIdentity>()
    init {
        valid(query.length <= MAX_LIBRARY_NOTE_QUERY_LENGTH && '\u0000' !in query &&
            (query.isEmpty() || isWellFormedEpubAnnotationText(query)))
        bytes(query)
    }

    fun add(entry: LibraryNoteExportEntry) {
        if (count == MAX_LIBRARY_NOTES_EXPORT_RECORDS) throw LibraryNotesExportException(StudyExportFailure.TOO_LARGE)
        val note = entry.note
        valid(identities.add(note.identity))
        valid(note.id.length in 1..1000 && note.id.isNotBlank() && isWellFormedEpubAnnotationText(note.id))
        valid(note.bookId.length in 1..1000 && note.bookId.isNotBlank() && isWellFormedEpubAnnotationText(note.bookId))
        valid(STUDY_SOURCE_HASH.matches(note.contentSha256) && note.sourceRevision >= 0)
        valid(listOf(note.bookTitle, note.bookAuthors ?: "", note.text, note.sourceText ?: "")
            .none { '\u0000' in it })
        val book = StudyExportBook(note.bookTitle, note.bookAuthors, note.bookFormat, entry.bookPageCount)
        val records = when (note.source) {
            LibraryNoteSource.BOOK_NOTE -> {
                valid(note.sourceText == null && note.sourceAnchor == null && note.highlightColor == null &&
                    note.pdfSourceAnchor == null && note.pdfHighlightColor == null && note.sourceRevision == 0L)
                StudyRecordsSnapshot(book, notes = listOf(StudyNoteRecord(note.text, note.pdfPageIndex)))
            }
            LibraryNoteSource.EPUB_PASSAGE -> {
                valid(note.pdfPageIndex == null && note.pdfSourceAnchor == null && note.pdfHighlightColor == null)
                val quote = note.sourceText ?: invalid()
                val anchor = note.sourceAnchor ?: invalid()
                StudyRecordsSnapshot(book, passages = listOf(StudyPassageRecord(quote, anchor,
                    note.highlightColor, note.text, note.sourceRevision)))
            }
            LibraryNoteSource.PDF_ANNOTATION -> {
                valid(note.sourceAnchor == null && note.highlightColor == null)
                val quote = note.sourceText ?: invalid()
                val anchor = note.pdfSourceAnchor ?: invalid()
                valid(note.pdfPageIndex == anchor.pageIndex)
                StudyRecordsSnapshot(book, pdfAnnotations = listOf(StudyPdfAnnotationRecord(quote, anchor,
                    note.pdfHighlightColor, note.text, note.sourceRevision)))
            }
        }
        validateStudyRecordsSnapshot(records)
        val sourceIdentity = when (val anchor = note.sourceAnchor) {
            is SourceTextAnchor -> anchor.nodeTextSha256
            is SourceRangeAnchor -> EpubRangeAnchorCodec.encode(anchor)
            null -> ""
        }
        bytes(note.id, note.bookId, note.contentSha256, note.bookTitle, note.bookAuthors ?: "", note.bookFormat.name,
            note.text, note.sourceText ?: "", note.sourceAnchor?.resourceHref ?: "", sourceIdentity,
            note.pdfSourceAnchor?.let(PdfAnchorCodec::encode) ?: "", note.highlightColor?.name ?: "",
            note.pdfHighlightColor?.name ?: "")
        count++
    }

    private fun bytes(vararg fields: String) {
        for (field in fields) {
            val length = field.toByteArray(Charsets.UTF_8).size
            if (length > MAX_LIBRARY_NOTES_EXPORT_SOURCE_BYTES - size)
                throw LibraryNotesExportException(StudyExportFailure.TOO_LARGE)
            size += length
        }
    }
    private fun valid(value: Boolean) { if (!value) invalid() }
    private fun invalid(): Nothing = throw LibraryNotesExportException(StudyExportFailure.IO)
}

internal fun validateLibraryNotesExportSnapshot(snapshot: LibraryNotesExportSnapshot) {
    val budget = LibraryNotesExportBudget(snapshot.query)
    snapshot.entries.forEach(budget::add)
    if (snapshot.entries.isEmpty()) throw LibraryNotesExportException(StudyExportFailure.NO_RECORDS)
}

internal fun renderLibraryNotesExport(snapshot: LibraryNotesExportSnapshot, format: LibraryNotesExportFormat): ByteArray {
    if (format !in setOf(LibraryNotesExportFormat.TEXT, LibraryNotesExportFormat.MARKDOWN))
        throw LibraryNotesExportException(StudyExportFailure.UNSUPPORTED_FORMAT)
    validateLibraryNotesExportSnapshot(snapshot)
    val output = BoundedStudyText()
    fun literal(text: String) {
        if (format == LibraryNotesExportFormat.TEXT) output.append(text, "\n\n")
        else {
            var longest = 0; var run = 0
            for (char in text) { run = if (char == '`') run + 1 else 0; longest = maxOf(longest, run) }
            val fence = "`".repeat(maxOf(3, longest + 1))
            output.append(fence, "\n", text, "\n", fence, "\n\n")
        }
    }
    fun field(label: String, text: String) {
        output.append(label, ":\n"); literal(text)
    }
    output.append(if (format == LibraryNotesExportFormat.MARKDOWN) "# Yaprak — Kütüphane notları\n\n" else "Yaprak — Kütüphane notları\n\n")
    field("Arama", snapshot.query.ifEmpty { "Tüm notlar" })
    snapshot.entries.forEachIndexed { index, entry ->
        val note = entry.note
        output.append(if (format == LibraryNotesExportFormat.MARKDOWN) "## Not " else "Not ", (index + 1).toString(), "\n\n")
        field("Kitap", note.bookTitle); field("Yazar", note.bookAuthors ?: "Bilinmiyor")
        field("Biçim", note.bookFormat.name)
        note.sourceAnchor?.let { field("Kaynak EPUB dosyası", it.resourceHref) }
        note.pdfPageIndex?.let { field("PDF sayfası", (it.toLong() + 1).toString()) }
        val color = note.highlightColor?.name ?: note.pdfHighlightColor?.name
        color?.let { field("Vurgu rengi", when (it) { "YELLOW" -> "Sarı"; "GREEN" -> "Yeşil"; "BLUE" -> "Mavi"; "PINK" -> "Pembe"; else -> error("Validated color") }) }
        note.sourceText?.let { field("Kaynak alıntı", it) }
        field("Not", note.text)
    }
    return output.bytes()
}
