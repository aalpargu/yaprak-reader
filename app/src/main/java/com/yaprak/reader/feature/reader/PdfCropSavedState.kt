package com.yaprak.reader.feature.reader

import android.os.Bundle
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.reader.PdfPageCrop

/** Only an applied choice can survive Activity recreation; editor drafts never enter the Bundle. */
internal data class PdfCropChoice(val bookId: String, val contentSha256: String, val crop: PdfPageCrop) {
    fun matches(book: Book): Boolean = book.format == BookFormat.PDF &&
        book.id == bookId && book.sha256 == contentSha256
}

internal object PdfCropSavedState {
    private const val BOOK = "readerPdfCropBook"
    private const val HASH = "readerPdfCropHash"
    private const val HORIZONTAL = "readerPdfCropHorizontal"
    private const val VERTICAL = "readerPdfCropVertical"

    fun read(bundle: Bundle?): PdfCropChoice? {
        if (bundle == null || !listOf(BOOK, HASH, HORIZONTAL, VERTICAL).all(bundle::containsKey)) return null
        val id = bundle.getString(BOOK)?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
        val hash = bundle.getString(HASH)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        val horizontal = bundle.getInt(HORIZONTAL, -1)
        val vertical = bundle.getInt(VERTICAL, -1)
        if (horizontal !in 0..20 || vertical !in 0..20) return null
        return PdfCropChoice(id, hash, PdfPageCrop(horizontal, vertical))
    }

    fun write(bundle: Bundle, choice: PdfCropChoice?) {
        listOf(BOOK, HASH, HORIZONTAL, VERTICAL).forEach(bundle::remove)
        if (choice == null) return
        bundle.putString(BOOK, choice.bookId)
        bundle.putString(HASH, choice.contentSha256)
        bundle.putInt(HORIZONTAL, choice.crop.horizontalPercent)
        bundle.putInt(VERTICAL, choice.crop.verticalPercent)
    }
}
