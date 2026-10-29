package com.yaprak.reader.data.exporting

import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.StudyExportFailure
import com.yaprak.reader.domain.notes.MAX_BOOK_NOTE_LENGTH
import com.yaprak.reader.domain.passages.EpubHighlightColor
import com.yaprak.reader.domain.passages.EpubRangeAnchorCodec
import com.yaprak.reader.domain.passages.MAX_EPUB_ATTACHED_NOTE_LENGTH
import com.yaprak.reader.domain.passages.MAX_EPUB_PASSAGE_LENGTH
import com.yaprak.reader.domain.passages.isWellFormedEpubAnnotationText
import com.yaprak.reader.domain.vocabulary.MAX_ENGLISH_WORD_LENGTH
import com.yaprak.reader.domain.vocabulary.MAX_TURKISH_MEANING_LENGTH
import com.yaprak.reader.reader.SourceTextAnchor
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.EpubSourceAnchor
import com.yaprak.reader.reader.PdfSourceAnchor
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfannotations.PdfAnchorCodec
import com.yaprak.reader.domain.pdfannotations.validatePdfAnnotation
import java.net.URI
import java.util.Collections

const val MAX_STUDY_PDF_RECORDS = 1000
const val MAX_STUDY_PDF_SOURCE_BYTES = 1024 * 1024

data class StudyExportBook(val title: String, val authors: String?, val format: BookFormat,
    val pageCount: Int? = null)
data class StudyNoteRecord(val text: String, val pdfPageIndex: Int? = null)
data class StudyWordRecord(val english: String, val turkish: String)
data class StudyPassageRecord(val text: String, val anchor: EpubSourceAnchor,
    val highlightColor: EpubHighlightColor? = null, val attachedNote: String? = null,
    val revision: Long = 0)
data class StudyPdfAnnotationRecord(val text: String, val anchor: PdfSourceAnchor,
    val highlightColor: PdfHighlightColor?, val attachedNote: String?, val revision: Long = 0)

/** Detached input for drawing after the database transaction and file mutex have ended. */
class StudyRecordsSnapshot(
    val book: StudyExportBook,
    notes: List<StudyNoteRecord> = emptyList(),
    words: List<StudyWordRecord> = emptyList(),
    passages: List<StudyPassageRecord> = emptyList(),
    pdfAnnotations: List<StudyPdfAnnotationRecord> = emptyList(),
) {
    init {
        // Check before defensive copying, including deliberately oversized source implementations.
        if (notes.size.toLong() + words.size + passages.size + pdfAnnotations.size > MAX_STUDY_PDF_RECORDS)
            throw StudyExportException(StudyExportFailure.TOO_LARGE)
    }
    val notes: List<StudyNoteRecord> = immutableCopy(notes)
    val words: List<StudyWordRecord> = immutableCopy(words)
    val passages: List<StudyPassageRecord> = immutableCopy(passages)
    val pdfAnnotations: List<StudyPdfAnnotationRecord> = immutableCopy(pdfAnnotations)
    val noteCount: Int get() = notes.size + passages.count { it.attachedNote != null } + pdfAnnotations.count { it.attachedNote != null }
    val wordCount: Int get() = words.size
    val passageCount: Int get() = passages.size + pdfAnnotations.size
    val recordCount: Int get() = notes.size + words.size + passages.size + pdfAnnotations.size
}

private fun <T> immutableCopy(records: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(records))

/** Recheck detached or injected sources at the renderer boundary; never silently omit records. */
fun validateStudyRecordsSnapshot(snapshot: StudyRecordsSnapshot) {
    val budget = StudyRecordsBudget(snapshot.book)
    snapshot.notes.forEach(budget::note)
    snapshot.words.forEach(budget::word)
    snapshot.passages.forEach(budget::passage)
    snapshot.pdfAnnotations.forEach(budget::pdfAnnotation)
    budget.finish()
}

/** The capture path applies the same budget before retaining each new record. */
internal class StudyRecordsBudget(private val book: StudyExportBook) {
    private var records = 0
    private var bytes = 0

    init {
        string(book.title, 1000)
        book.authors?.let { string(it, 2000) }
        when (book.format) {
            BookFormat.PDF -> validStudyRecord(book.pageCount != null && book.pageCount > 0)
            BookFormat.CBZ, BookFormat.CBR -> validStudyRecord(book.pageCount != null && book.pageCount in 1..2000)
            BookFormat.EPUB, BookFormat.TXT, BookFormat.FB2, BookFormat.DOCX -> validStudyRecord(book.pageCount == null)
        }
        bytes(book.title, book.authors ?: "", book.format.name)
    }

    fun note(note: StudyNoteRecord) {
        admit()
        string(note.text, MAX_BOOK_NOTE_LENGTH)
        note.pdfPageIndex?.let { page ->
            validStudyRecord(book.format == BookFormat.PDF && book.pageCount != null && page >= 0 && page < book.pageCount)
        }
        bytes(note.text)
    }

    fun word(word: StudyWordRecord) {
        admit()
        string(word.english, MAX_ENGLISH_WORD_LENGTH, singleLine = true)
        string(word.turkish, MAX_TURKISH_MEANING_LENGTH, singleLine = true)
        bytes(word.english, word.turkish)
    }

    fun passage(passage: StudyPassageRecord) {
        admit()
        validStudyRecord(book.format == BookFormat.EPUB)
        string(passage.text, MAX_EPUB_PASSAGE_LENGTH)
        val anchor = passage.anchor
        validStudyRecord(isSafeStudyPassageHref(anchor.resourceHref) && passage.revision >= 0)
        val sourceIdentity = when (anchor) {
            is SourceTextAnchor -> {
                validStudyRecord(anchor.start >= 0 && anchor.end > anchor.start && anchor.end <= MAX_EPUB_PASSAGE_LENGTH &&
                    anchor.end - anchor.start == passage.text.length && STUDY_SOURCE_HASH.matches(anchor.nodeTextSha256))
                anchor.nodeTextSha256
            }
            is SourceRangeAnchor -> try { EpubRangeAnchorCodec.encode(anchor) }
                catch (_: Exception) { throw StudyExportException(StudyExportFailure.IO) }
        }
        string(anchor.resourceHref, 4096)
        passage.attachedNote?.let { string(it, MAX_EPUB_ATTACHED_NOTE_LENGTH) }
        bytes(passage.text, anchor.resourceHref, sourceIdentity,
            passage.highlightColor?.name ?: "", passage.attachedNote ?: "")
    }

    fun pdfAnnotation(annotation: StudyPdfAnnotationRecord) {
        admit()
        validStudyRecord(book.format == BookFormat.PDF && book.pageCount != null &&
            annotation.anchor.pageIndex in 0 until book.pageCount && annotation.revision >= 0)
        val geometry = try {
            validatePdfAnnotation(annotation.text, annotation.highlightColor, annotation.attachedNote)
            PdfAnchorCodec.encode(annotation.anchor)
        } catch (_: Exception) { throw StudyExportException(StudyExportFailure.IO) }
        bytes(annotation.text, geometry, annotation.highlightColor?.name ?: "", annotation.attachedNote ?: "")
    }

    fun finish() {
        if (records == 0) throw StudyExportException(StudyExportFailure.NO_RECORDS)
    }

    private fun admit() {
        if (records == MAX_STUDY_PDF_RECORDS) throw StudyExportException(StudyExportFailure.TOO_LARGE)
        records++
    }

    private fun string(value: String, limit: Int, singleLine: Boolean = false) {
        validStudyRecord(value.length in 1..limit && value.isNotBlank() && isWellFormedEpubAnnotationText(value))
        if (singleLine) validStudyRecord(value.none { it == '\r' || it == '\n' })
    }

    // Every string field counts, including source hashes and color names. UTF-16 length is not a byte budget.
    private fun bytes(vararg fields: String) {
        fields.forEach { field ->
            val size = field.toByteArray(Charsets.UTF_8).size
            if (size > MAX_STUDY_PDF_SOURCE_BYTES - bytes) throw StudyExportException(StudyExportFailure.TOO_LARGE)
            bytes += size
        }
    }
}

internal val STUDY_SOURCE_HASH = Regex("[0-9a-f]{64}")

internal fun isSafeStudyPassageHref(href: String): Boolean {
    if (href.isEmpty() || href.length > 4096 || href.contains('\\') || href.any(Char::isISOControl) ||
        Regex("%(?:2[fF]|5[cC])").containsMatchIn(href)) return false
    return try {
        val uri = URI(href)
        val path = uri.path ?: return false
        !uri.isAbsolute && !uri.isOpaque && uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null &&
            path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) } && uri.rawPath == href
    } catch (_: Exception) { false }
}

internal fun validStudyRecord(condition: Boolean) {
    if (!condition) throw StudyExportException(StudyExportFailure.IO)
}
