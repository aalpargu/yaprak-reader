package com.yaprak.reader.fixtures

import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.reader.*
import java.util.UUID

internal object PdfSelectedAssistantFixtures {
    val book = SelectedAssistantFixtures.book.copy(format = BookFormat.PDF, storageName = "source.pdf", originalName = "source.pdf", pageCount = 3)
    val anchor = PdfSourceAnchor(2, 300, 600, PdfPagePoint(60, 180), PdfPagePoint(210, 300),
        listOf(PdfTextBounds(60.0, 180.0, 210.0, 200.0)))
    fun request(text: String = SelectedAssistantFixtures.text) = SelectedTextSourceRequest(book.id, book.sha256,
        UUID.randomUUID().toString(), text, sourceFormat = BookFormat.PDF, pdfSourceAnchor = anchor,
        sourceSelectionId = UUID.randomUUID().toString())
    fun sources(request: SelectedTextSourceRequest = request()) = PreparedSelectedTextSources(request, book, splitSelectedTextReferences(request.preview))
}
