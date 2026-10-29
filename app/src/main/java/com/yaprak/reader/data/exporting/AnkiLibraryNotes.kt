package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.notes.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Full current-query notes, using the same literal Front/Back contract as per-book Anki exports. */
internal suspend fun renderAnkiLibraryNotes(snapshot: LibraryNotesExportSnapshot): ByteArray {
    currentCoroutineContext().ensureActive()
    validateLibraryNotesExportSnapshot(snapshot)
    val output = BoundedStudyText(5_000_000)
    output.append("#separator:Tab\n#html:false\n#columns:Front\tBack\n")
    for (entry in snapshot.entries) {
        currentCoroutineContext().ensureActive()
        val note = entry.note
        val attached = note.source != LibraryNoteSource.BOOK_NOTE
        val front = if (attached) requireNotNull(note.sourceText) else note.text
        val back = buildString {
            if (attached) append(note.text).append("\n\n")
            append("Kitap: ").append(note.bookTitle)
            append("\nYazar: ").append(note.bookAuthors ?: "Bilinmiyor")
            append("\nBiçim: ").append(note.bookFormat.name)
            when (note.source) {
                LibraryNoteSource.BOOK_NOTE -> append("\nKitap notu")
                LibraryNoteSource.EPUB_PASSAGE -> append("\nKaynak EPUB dosyası: ").append(requireNotNull(note.sourceAnchor).resourceHref)
                LibraryNoteSource.PDF_ANNOTATION -> Unit
            }
            note.pdfPageIndex?.let { append("\nPDF sayfası: ").append(it.toLong() + 1) }
            val color = note.highlightColor?.name ?: note.pdfHighlightColor?.name
            color?.let {
                append("\nVurgu rengi: ").append(when (it) {
                    "YELLOW" -> "Sarı"; "GREEN" -> "Yeşil"; "BLUE" -> "Mavi"; "PINK" -> "Pembe"
                    else -> error("Validated color")
                })
            }
        }
        // Quoting every field also prevents saved leading # content from becoming an import header.
        output.append("\"", front.replace("\"", "\"\""), "\"\t\"", back.replace("\"", "\"\""), "\"\n")
    }
    currentCoroutineContext().ensureActive()
    return output.bytes()
}
