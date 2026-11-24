package com.yaprak.reader.data.persistence

import androidx.room.withTransaction
import com.yaprak.reader.domain.pdfannotations.PdfHighlightColor
import com.yaprak.reader.domain.pdfink.MAX_PDF_INK_STROKES_PER_BOOK
import com.yaprak.reader.domain.pdfink.PdfInkGeometryCodec
import com.yaprak.reader.domain.pdfink.PdfInkLimitException
import com.yaprak.reader.domain.pdfink.PdfInkMode
import com.yaprak.reader.domain.pdfink.PdfInkRepository
import com.yaprak.reader.domain.pdfink.PdfInkStroke
import com.yaprak.reader.domain.pdfink.validatePdfInkStroke
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomPdfInkRepository(
    private val database: LibraryDatabase,
    private val validateAccess: () -> Unit = {},
) : PdfInkRepository {
    private val dao get() = database.pdfInkStrokes()

    override fun observe(bookId: String, contentSha256: String): Flow<List<PdfInkStroke>> {
        validateAccess()
        // A row that no longer decodes (never written by this app) is not painted rather than guessed at.
        return dao.observe(bookId, contentSha256).map { rows -> rows.mapNotNull { it.toStrokeOrNull() } }
    }

    override suspend fun add(bookId: String, contentSha256: String, stroke: PdfInkStroke) {
        validateAccess()
        validatePdfInkStroke(stroke)
        val geometry = PdfInkGeometryCodec.encode(stroke.segments)
        database.withTransaction {
            val book = database.books().findById(bookId)
            require(book != null && book.sha256 == contentSha256 && book.format == "PDF")
            val pages = book.pageCount
            require(pages == null || stroke.pageIndex < pages)
            if (dao.count(bookId, contentSha256) >= MAX_PDF_INK_STROKES_PER_BOOK) throw PdfInkLimitException()
            dao.insert(PdfInkStrokeEntity(stroke.id, bookId, contentSha256, stroke.pageIndex, stroke.color.name,
                stroke.mode.name, geometry, stroke.createdAt))
        }
    }

    override suspend fun remove(bookId: String, contentSha256: String, strokeId: String) {
        validateAccess()
        dao.delete(strokeId, bookId, contentSha256)
    }
}

internal fun PdfInkStrokeEntity.toStrokeOrNull(): PdfInkStroke? = try {
    PdfInkStroke(id, pageIndex, PdfHighlightColor.valueOf(color), PdfInkMode.valueOf(mode),
        PdfInkGeometryCodec.decode(geometry), createdAt).also(::validatePdfInkStroke)
} catch (_: IllegalArgumentException) { null }
