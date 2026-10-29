package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.notes.*

/** Whole current-query notes only. Saved source quotes and their attached notes remain one row. */
internal fun renderNotionLibraryNotesCsv(snapshot: LibraryNotesExportSnapshot): ByteArray {
    validateLibraryNotesExportSnapshot(snapshot)
    val table = NotionCsvTable()
    table.studyHeader()
    snapshot.entries.forEachIndexed { index, entry ->
        val note = entry.note
        val attached = note.source != LibraryNoteSource.BOOK_NOTE
        val kind = when (note.source) {
            LibraryNoteSource.BOOK_NOTE -> "Kitap notu"
            LibraryNoteSource.EPUB_PASSAGE -> "EPUB pasajı"
            LibraryNoteSource.PDF_ANNOTATION -> "PDF pasajı"
        }
        val source = note.sourceAnchor?.resourceHref
            ?: note.pdfPageIndex?.let { "PDF sayfası ${it.toLong() + 1}" }.orEmpty()
        val color = when (note.highlightColor?.name ?: note.pdfHighlightColor?.name) {
            "YELLOW" -> "Sarı"; "GREEN" -> "Yeşil"; "BLUE" -> "Mavi"; "PINK" -> "Pembe"; null -> ""
            else -> error("Validated color")
        }
        table.row("Not ${index + 1}", note.bookTitle, note.bookAuthors ?: "", note.bookFormat.name, kind,
            if (attached) requireNotNull(note.sourceText) else note.text, "", if (attached) note.text else "",
            source, note.pdfPageIndex?.let { (it.toLong() + 1).toString() }.orEmpty(), color)
    }
    return table.bytes()
}
