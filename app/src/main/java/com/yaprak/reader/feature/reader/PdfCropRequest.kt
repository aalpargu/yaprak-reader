package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.reader.PageNavigator
import com.yaprak.reader.reader.PdfCropController
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.PdfThumbnailsSource
import com.yaprak.reader.reader.ReaderSurface
import java.util.UUID

/** A non-restored editor lease tied to one exact reader surface and opening preview page. */
internal data class PdfCropRequest(
    val book: Book,
    val surface: ReaderSurface,
    val controller: PdfCropController,
    val saved: PdfPageCrop,
    val previewSource: PdfThumbnailsSource?,
    val previewNavigator: PageNavigator?,
    val previewPage: Int?,
    val previewPageCount: Int,
    val preferencesOwner: PdfBookViewOwner? = null,
    var preferencesRevision: Long = 0,
    val id: String = UUID.randomUUID().toString(),
)
