package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One saved record is one Front/Back import row; an attached note is the back of its passage. */
internal suspend fun renderAnkiStudyCards(records: StudyRecordsSnapshot): ByteArray {
    currentCoroutineContext().ensureActive()
    validateStudyRecordsSnapshot(records)
    val output = BoundedStudyText()
    output.append("#separator:Tab\n#html:false\n#columns:Front\tBack\n")
    val book = records.book
    val source = "Kitap: ${book.title}\nYazar: ${book.authors ?: "Bilinmiyor"}\nBiçim: ${book.format.name}"
    suspend fun card(front: String, answer: String?, location: String, color: String? = null) {
        currentCoroutineContext().ensureActive()
        val back = buildString {
            if (answer != null) append(answer).append("\n\n")
            append(source).append('\n').append(location)
            if (color != null) append("\nVurgu rengi: ").append(color)
        }
        validStudyRecord('\u0000' !in front && '\u0000' !in back)
        // UTF-8, all fields quoted, doubled quotes and HTML disabled: saved text stays literal.
        output.append("\"", front.replace("\"", "\"\""), "\"\t\"", back.replace("\"", "\"\""), "\"\n")
    }
    for (note in records.notes) card(note.text, null,
        note.pdfPageIndex?.let { "PDF sayfası: ${it.toLong() + 1}" } ?: "Kitap notu")
    for (word in records.words) card(word.english, word.turkish, "Kayıtlı kelime")
    for (passage in records.passages) card(passage.text, passage.attachedNote,
        "Kaynak EPUB dosyası: ${passage.anchor.resourceHref}", passage.highlightColor?.ankiLabel())
    for (annotation in records.pdfAnnotations) card(annotation.text, annotation.attachedNote,
        "PDF sayfası: ${annotation.anchor.pageIndex.toLong() + 1}", annotation.highlightColor?.ankiLabel())
    currentCoroutineContext().ensureActive()
    return output.bytes()
}

private fun EpubHighlightColor.ankiLabel(): String = when (this) {
    EpubHighlightColor.YELLOW -> "Sarı"
    EpubHighlightColor.GREEN -> "Yeşil"
    EpubHighlightColor.BLUE -> "Mavi"
    EpubHighlightColor.PINK -> "Pembe"
}

private fun PdfHighlightColor.ankiLabel(): String = when (this) {
    PdfHighlightColor.YELLOW -> "Sarı"
    PdfHighlightColor.GREEN -> "Yeşil"
    PdfHighlightColor.BLUE -> "Mavi"
    PdfHighlightColor.PINK -> "Pembe"
}
