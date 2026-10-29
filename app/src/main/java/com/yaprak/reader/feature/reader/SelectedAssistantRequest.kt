package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.assistant.SelectedTextSourceRequest
import com.yaprak.reader.domain.assistant.validAnswerText
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.reader.PdfSourceAnchor
import java.util.UUID

internal fun eligiblePdfAssistantAnchor(preview: SelectedTextPreview.Ready): PdfSourceAnchor? =
    eligiblePdfAnnotationAnchor(preview)?.takeIf {
        preview.rangeCandidate == null && validAnswerText(preview.text, 4096) && preview.sourceSelectionId?.let { id ->
            runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        } == true
    }

internal fun eligibleEpubAssistantPreview(preview: SelectedTextPreview.Ready): Boolean =
    !preview.pdfOcr && preview.pdfPageIndex == null && preview.pdfSourceAnchor == null &&
        eligiblePassageAnchor(preview.text, preview.sourceAnchor) != null

/** Pure current-selection admission. Fresh original-byte/native proof remains the data boundary's job. */
internal fun selectedAssistantRequest(book: Book, preview: SelectedTextPreview.Ready): SelectedTextSourceRequest? {
    val request = when (book.format) {
        BookFormat.EPUB -> if (eligibleEpubAssistantPreview(preview)) SelectedTextSourceRequest(book.id, book.sha256,
            preview.sessionId, preview.text, preview.sourceAnchor) else return null
        BookFormat.PDF -> {
            val anchor = eligiblePdfAssistantAnchor(preview) ?: return null
            if (book.pageCount == null || anchor.pageIndex !in 0 until book.pageCount) return null
            SelectedTextSourceRequest(book.id, book.sha256, preview.sessionId, preview.text, sourceFormat = BookFormat.PDF,
                pdfSourceAnchor = anchor, sourceSelectionId = preview.sourceSelectionId)
        }
        BookFormat.TXT, BookFormat.CBZ, BookFormat.CBR, BookFormat.FB2, BookFormat.DOCX -> return null
    }
    return request.takeIf { it.isValid }
}
