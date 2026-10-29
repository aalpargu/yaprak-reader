package com.yaprak.reader.domain.pdfannotations

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.reader.PdfSourceAnchor
import kotlinx.coroutines.flow.Flow

enum class PdfHighlightColor { YELLOW, GREEN, BLUE, PINK }
const val MAX_PDF_ANNOTATION_TEXT = 5000
const val MAX_PDF_ATTACHED_NOTE = 2000
const val MAX_PDF_ANNOTATIONS_PER_BOOK = 1000

data class PdfAnnotation(val id: String, val text: String, val anchor: PdfSourceAnchor,
    val createdAt: Long, val highlightColor: PdfHighlightColor? = null,
    val attachedNote: String? = null, val revision: Long = 0)

class PdfAnnotationSourceException(cause: Throwable? = null) : Exception("PDF annotation source is unavailable or invalid", cause)
class PdfAnnotationChangedException : Exception("PDF annotation has changed or is missing")
class PdfAnnotationLimitException : Exception("PDF annotation limit reached")

/** Formats owns the fresh original/native proof; data owns file/recovery/transaction guards. */
fun interface PdfAnnotationOriginalSource {
    suspend fun verify(book: Book, text: String, anchor: PdfSourceAnchor)
    suspend fun verifyAll(book: Book, annotations: List<PdfAnnotation>) {
        annotations.forEach { verify(book, it.text, it.anchor) }
    }
}

interface PdfAnnotationRepository {
    fun observe(bookId: String, contentSha256: String): Flow<List<PdfAnnotation>>
    suspend fun create(bookId: String, contentSha256: String, text: String, anchor: PdfSourceAnchor,
        highlightColor: PdfHighlightColor?, attachedNote: String?): PdfAnnotation
    suspend fun update(bookId: String, contentSha256: String, expected: PdfAnnotation,
        highlightColor: PdfHighlightColor?, attachedNote: String?): PdfAnnotation
    suspend fun remove(bookId: String, contentSha256: String, expected: PdfAnnotation)
    /** Current stored rows plus fresh file/native proof, before painting any saved geometry. */
    suspend fun verifyExisting(bookId: String, contentSha256: String, expected: List<PdfAnnotation>)
}

fun validatePdfAnnotationText(text: String, limit: Int) {
    require(text.length in 1..limit && text.isNotBlank() && '\u0000' !in text)
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (char.isHighSurrogate()) require(index < text.length && text[index++].isLowSurrogate())
        else require(!char.isLowSurrogate())
    }
}

fun validatePdfAnnotation(text: String, color: PdfHighlightColor?, note: String?) {
    validatePdfAnnotationText(text, MAX_PDF_ANNOTATION_TEXT)
    note?.let { validatePdfAnnotationText(it, MAX_PDF_ATTACHED_NOTE) }
    require(color != null || note != null) { "A PDF annotation needs a highlight or attached note" }
}
