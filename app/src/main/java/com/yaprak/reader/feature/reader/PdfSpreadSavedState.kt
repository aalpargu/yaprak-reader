package com.yaprak.reader.feature.reader

import android.os.Bundle
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat

internal data class PdfSpreadChoice(val bookId: String, val contentSha256: String, val enabled: Boolean) {
    fun matches(book: Book) = book.format == BookFormat.PDF && book.id == bookId && book.sha256 == contentSha256
}

/** Applied session choice only; no new persistent book, settings or backup field. */
internal object PdfSpreadSavedState {
    private const val BOOK = "readerPdfSpreadBook"
    private const val HASH = "readerPdfSpreadHash"
    private const val ENABLED = "readerPdfSpreadEnabled"

    @Suppress("DEPRECATION") // Raw types reject String/Int/Long values without coercing them.
    fun read(bundle: Bundle?): PdfSpreadChoice? {
        if (bundle == null) return null
        val book = (bundle.get(BOOK) as? String)?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
        val hash = (bundle.get(HASH) as? String)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        val enabled = bundle.get(ENABLED) as? Boolean ?: return null
        return PdfSpreadChoice(book, hash, enabled)
    }

    fun write(bundle: Bundle, choice: PdfSpreadChoice?) {
        listOf(BOOK, HASH, ENABLED).forEach(bundle::remove)
        if (choice == null) return
        bundle.putString(BOOK, choice.bookId)
        bundle.putString(HASH, choice.contentSha256)
        bundle.putBoolean(ENABLED, choice.enabled)
    }
}
