package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.reader.PageNavigator
import com.yaprak.reader.reader.PageThumbnailsSource
import com.yaprak.reader.reader.ReaderSurface
import java.util.UUID

/** One transient window bound to the exact publication and rendering surface that opened it. */
internal class PdfThumbnailsRequest(
    val book: Book,
    val surface: ReaderSurface,
    val source: PageThumbnailsSource,
    val navigator: PageNavigator,
    val pageCount: Int,
    val currentPage: Int?,
    val id: String = UUID.randomUUID().toString(),
)
