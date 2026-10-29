package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.library.MAX_STUDY_EXPORT_BYTES
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import java.io.ByteArrayOutputStream

/** Receives validated records directly; exported TXT is never parsed into another format. */
internal interface StudyExportTextWriter {
    fun note(index: Int, text: String, pdfPageIndex: Long?)
    fun endNotes(count: Int)
    fun word(index: Int, english: String, turkish: String)
    fun endWords(count: Int)
    fun passage(index: Int, text: String, resourceHref: String, color: EpubHighlightColor? = null, attachedNote: String? = null)
    fun endPassages(count: Int)
    fun pdfAnnotation(index: Int, text: String, pageIndex: Int, color: PdfHighlightColor?, attachedNote: String?)
    fun bytes(): ByteArray
}

internal fun studyExportTextWriter(
    format: StudyExportFormat,
    title: String,
    authors: String?,
    bookFormat: String,
): StudyExportTextWriter = when (format) {
    StudyExportFormat.TEXT -> PlainStudyExportWriter(title, authors, bookFormat)
    StudyExportFormat.MARKDOWN -> MarkdownStudyExportWriter(title, authors, bookFormat)
    StudyExportFormat.ANKI_TSV -> AnkiStudyExportWriter()
    StudyExportFormat.PDF, StudyExportFormat.ANKI_STUDY_TSV, StudyExportFormat.NOTION_CSV, StudyExportFormat.OBSIDIAN_ZIP ->
        throw StudyExportException(StudyExportFailure.UNSUPPORTED_FORMAT)
}

private class PlainStudyExportWriter(title: String, authors: String?, format: String) : StudyExportTextWriter {
    private val output = BoundedStudyText()
    private var pdfSection = false

    init {
        output.append("Yaprak — Kitap çalışma kayıtları\nBaşlık: ", title,
            "\nYazar: ", authors ?: "Bilinmiyor", "\nBiçim: ", format, "\n\nNotlar\n------\n")
    }

    override fun note(index: Int, text: String, pdfPageIndex: Long?) {
        output.append("[", index.toString(), "]", pdfPageIndex?.let { " — PDF sayfası: ${it + 1}" } ?: "",
            "\n", text, "\n\n")
    }

    override fun endNotes(count: Int) {
        if (count == 0) output.append("Kayıt yok.\n\n")
        output.append("Kelimeler\n---------\n")
    }

    override fun word(index: Int, english: String, turkish: String) {
        output.append("[", index.toString(), "]\nİngilizce: ", english, "\nTürkçe: ", turkish, "\n\n")
    }

    override fun endWords(count: Int) {
        if (count == 0) output.append("Kayıt yok.\n\n")
        output.append("Kayıtlı EPUB pasajları\n---------------------\n")
    }

    override fun passage(index: Int, text: String, resourceHref: String, color: EpubHighlightColor?, attachedNote: String?) {
        output.append("[", index.toString(), "]\n", text, "\n")
        if (color != null || attachedNote != null) output.append("Kaynak: ", resourceHref, "\n")
        color?.let { output.append("Vurgu rengi: ", it.displayName(), "\n") }
        attachedNote?.let { output.append("Bağlı not:\n", it, "\n") }
        output.append("\n")
    }

    override fun endPassages(count: Int) {
        if (count == 0) output.append("Kayıt yok.\n")
    }

    override fun pdfAnnotation(index: Int, text: String, pageIndex: Int, color: PdfHighlightColor?, attachedNote: String?) {
        if (!pdfSection) { output.append("\nPDF vurguları ve bağlı notlar\n----------------------------\n"); pdfSection = true }
        output.append("[", index.toString(), "] — PDF sayfası: ", (pageIndex.toLong() + 1).toString(), "\n", text, "\n")
        color?.let { output.append("Vurgu rengi: ", it.displayName(), "\n") }
        attachedNote?.let { output.append("Bağlı not:\n", it, "\n") }
        output.append("\n")
    }

    override fun bytes() = output.bytes()
}

private class MarkdownStudyExportWriter(title: String, authors: String?, format: String) : StudyExportTextWriter {
    private val output = BoundedStudyText()
    private var pdfSection = false

    init {
        output.append("# Yaprak — Kitap çalışma kayıtları\n\nBaşlık:\n\n")
        output.literal(title)
        output.append("Yazar:\n\n")
        output.literal(authors ?: "Bilinmiyor")
        output.append("Biçim: ", format, "\n\n## Notlar\n\n")
    }

    override fun note(index: Int, text: String, pdfPageIndex: Long?) {
        output.append("### Not ", index.toString(), "\n\n")
        if (pdfPageIndex != null) output.append("PDF sayfası: ", (pdfPageIndex + 1).toString(), "\n\n")
        output.literal(text)
    }

    override fun endNotes(count: Int) {
        if (count == 0) output.append("Kayıt yok.\n\n")
        output.append("## Kelimeler\n\n")
    }

    override fun word(index: Int, english: String, turkish: String) {
        output.append("### Kelime ", index.toString(), "\n\nİngilizce:\n\n")
        output.literal(english)
        output.append("Türkçe:\n\n")
        output.literal(turkish)
    }

    override fun endWords(count: Int) {
        if (count == 0) output.append("Kayıt yok.\n\n")
        output.append("## Kayıtlı EPUB pasajları\n\n")
    }

    override fun passage(index: Int, text: String, resourceHref: String, color: EpubHighlightColor?, attachedNote: String?) {
        output.append("### Pasaj ", index.toString(), "\n\nKaynak EPUB dosyası:\n\n")
        output.literal(resourceHref)
        output.append("Pasaj:\n\n")
        output.literal(text)
        color?.let { output.append("Vurgu rengi: ", it.displayName(), "\n\n") }
        attachedNote?.let { output.append("Bağlı not:\n\n"); output.literal(it) }
    }

    override fun endPassages(count: Int) {
        if (count == 0) output.append("Kayıt yok.\n")
    }

    override fun pdfAnnotation(index: Int, text: String, pageIndex: Int, color: PdfHighlightColor?, attachedNote: String?) {
        if (!pdfSection) { output.append("\n## PDF vurguları ve bağlı notlar\n\n"); pdfSection = true }
        output.append("### PDF kaydı ", index.toString(), "\n\nPDF sayfası: ", (pageIndex.toLong() + 1).toString(), "\n\nPasaj:\n\n")
        output.literal(text)
        color?.let { output.append("Vurgu rengi: ", it.displayName(), "\n\n") }
        attachedNote?.let { output.append("Bağlı not:\n\n"); output.literal(it) }
    }

    override fun bytes() = output.bytes()
}

/** All user text stays inside literal fences, including metadata and validated local EPUB hrefs. */
private fun BoundedStudyText.literal(value: String) {
    var longest = 0
    var run = 0
    for (character in value) {
        run = if (character == '`') run + 1 else 0
        longest = maxOf(longest, run)
    }
    val fence = "`".repeat(maxOf(3, longest + 1))
    append(fence, "\n", value, "\n", fence, "\n\n")
}

private class AnkiStudyExportWriter : StudyExportTextWriter {
    private val output = BoundedStudyText().apply {
        append("#separator:Tab\n#html:false\n#columns:Front\tBack\n")
    }

    override fun note(index: Int, text: String, pdfPageIndex: Long?) = error("Anki accepts saved word pairs only")
    override fun endNotes(count: Int) = Unit

    override fun word(index: Int, english: String, turkish: String) {
        // Quote every field: embedded TAB, quote or a leading # remains ordinary card text.
        output.append("\"", english.replace("\"", "\"\""), "\"\t\"", turkish.replace("\"", "\"\""), "\"\n")
    }

    override fun endWords(count: Int) = Unit
    override fun passage(index: Int, text: String, resourceHref: String, color: EpubHighlightColor?, attachedNote: String?) =
        error("Anki accepts saved word pairs only")
    override fun endPassages(count: Int) = Unit
    override fun pdfAnnotation(index: Int, text: String, pageIndex: Int, color: PdfHighlightColor?, attachedNote: String?) =
        error("Anki accepts saved word pairs only")
    override fun bytes() = output.bytes()
}

private fun PdfHighlightColor.displayName() = when (this) {
    PdfHighlightColor.YELLOW -> "Sarı"
    PdfHighlightColor.GREEN -> "Yeşil"
    PdfHighlightColor.BLUE -> "Mavi"
    PdfHighlightColor.PINK -> "Pembe"
}

private fun EpubHighlightColor.displayName() = when (this) {
    EpubHighlightColor.YELLOW -> "Sarı"
    EpubHighlightColor.GREEN -> "Yeşil"
    EpubHighlightColor.BLUE -> "Mavi"
    EpubHighlightColor.PINK -> "Pembe"
}

/** Count every UTF-8 byte, including headings, fence expansion, quotes and separators. */
internal class BoundedStudyText(private val maximumBytes: Int = MAX_STUDY_EXPORT_BYTES) {
    private val output = ByteArrayOutputStream()

    init { require(maximumBytes in 1..MAX_STUDY_EXPORT_BYTES) }

    fun append(vararg pieces: String) {
        for (piece in pieces) {
            val bytes = piece.toByteArray(Charsets.UTF_8)
            if (bytes.size > maximumBytes - output.size()) {
                throw StudyExportException(StudyExportFailure.TOO_LARGE)
            }
            output.write(bytes)
        }
    }

    fun bytes(): ByteArray = output.toByteArray()
}
