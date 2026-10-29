package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.notes.LibraryNotesExportSnapshot

/** Detached multi-book note records only; no database, original book or provider access. */
fun interface LibraryNotesPdfRenderer {
    suspend fun render(snapshot: LibraryNotesExportSnapshot): ByteArray
}

class AndroidLibraryNotesPdfRenderer internal constructor(private val renderer: AndroidStudyPdfRenderer) : LibraryNotesPdfRenderer {
    constructor() : this(AndroidStudyPdfRenderer())
    override suspend fun render(snapshot: LibraryNotesExportSnapshot): ByteArray = renderer.renderLibraryNotes(snapshot)
}

internal fun libraryNotesPdfBlocks(snapshot: LibraryNotesExportSnapshot): List<StudyPdfBlock> {
    validateLibraryNotesExportSnapshot(snapshot)
    return buildList {
        fun label(text: String) { add(StudyPdfBlock(text, StudyPdfTextStyle.SOURCE, keepWithNext = true)) }
        fun field(title: String, value: String) { label(title); add(StudyPdfBlock(value)) }
        add(StudyPdfBlock("Yaprak — Kütüphane notları", StudyPdfTextStyle.TITLE, keepWithNext = true))
        field("Arama:", snapshot.query.ifEmpty { "Tüm notlar" })
        add(StudyPdfBlock("${snapshot.entries.size} not", StudyPdfTextStyle.SOURCE))
        snapshot.entries.forEachIndexed { index, entry ->
            val note = entry.note
            add(StudyPdfBlock("Not ${index + 1}", StudyPdfTextStyle.HEADING, keepWithNext = true))
            field("Kitap:", note.bookTitle); field("Yazar:", note.bookAuthors ?: "Bilinmiyor")
            add(StudyPdfBlock("Biçim: ${note.bookFormat.name}", StudyPdfTextStyle.SOURCE))
            note.sourceAnchor?.let { field("Kaynak EPUB dosyası:", it.resourceHref) }
            note.pdfPageIndex?.let { add(StudyPdfBlock("PDF sayfası: ${it.toLong() + 1}", StudyPdfTextStyle.SOURCE)) }
            (note.highlightColor?.name ?: note.pdfHighlightColor?.name)?.let { color ->
                val name = when (color) { "YELLOW" -> "Sarı"; "GREEN" -> "Yeşil"; "BLUE" -> "Mavi"; "PINK" -> "Pembe"; else -> error("Validated color") }
                add(StudyPdfBlock("Vurgu rengi: $name", StudyPdfTextStyle.SOURCE))
            }
            note.sourceText?.let { quote ->
                label("Kaynak alıntı:")
                add(StudyPdfBlock(quote, highlightColor = note.highlightColor, pdfHighlightColor = note.pdfHighlightColor))
            }
            field("Not:", note.text)
        }
    }
}
