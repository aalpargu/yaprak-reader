package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor

/** Conservative free-tier CSV size. Includes UTF-8 quoting, repeated metadata and delimiters. */
internal const val MAX_NOTION_STUDY_CSV_BYTES = 5_000_000

/** A saved-record table for explicit desktop/web import; never uploads or invents source fields. */
internal fun renderNotionStudyCsv(records: StudyRecordsSnapshot): ByteArray {
    validateStudyRecordsSnapshot(records)
    val table = NotionCsvTable()
    table.studyHeader()
    fun record(name: String, kind: String, text: String, meaning: String = "", attached: String = "",
        source: String = "", page: Int? = null, color: String = "") {
        table.row(name, records.book.title, records.book.authors ?: "", records.book.format.name, kind, text,
            meaning, attached, source, page?.let { (it.toLong() + 1).toString() } ?: "", color)
    }
    records.notes.forEachIndexed { index, note ->
        record("Not ${index + 1}", "Kitap notu", note.text,
            source = note.pdfPageIndex?.let { "PDF sayfası ${it.toLong() + 1}" } ?: "", page = note.pdfPageIndex)
    }
    records.words.forEachIndexed { index, word -> record("Kelime ${index + 1}", "Kelime", word.english, meaning = word.turkish) }
    records.passages.forEachIndexed { index, passage ->
        record("EPUB pasajı ${index + 1}", "EPUB pasajı", passage.text, attached = passage.attachedNote ?: "",
            source = passage.anchor.resourceHref, color = when (passage.highlightColor) {
                EpubHighlightColor.YELLOW -> "Sarı"; EpubHighlightColor.GREEN -> "Yeşil"
                EpubHighlightColor.BLUE -> "Mavi"; EpubHighlightColor.PINK -> "Pembe"; null -> ""
            })
    }
    records.pdfAnnotations.forEachIndexed { index, annotation ->
        record("PDF kaydı ${index + 1}", "PDF pasajı", annotation.text, attached = annotation.attachedNote ?: "",
            source = "PDF sayfası ${annotation.anchor.pageIndex.toLong() + 1}", page = annotation.anchor.pageIndex,
            color = when (annotation.highlightColor) {
                PdfHighlightColor.YELLOW -> "Sarı"; PdfHighlightColor.GREEN -> "Yeşil"
                PdfHighlightColor.BLUE -> "Mavi"; PdfHighlightColor.PINK -> "Pembe"; null -> ""
            })
    }
    return table.bytes()
}
