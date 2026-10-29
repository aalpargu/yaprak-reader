package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.PdfCropController
import com.yaprak.reader.reader.PdfViewController
import com.yaprak.reader.reader.PdfSpreadController
import com.yaprak.reader.reader.PdfScrollController
import com.yaprak.reader.reader.ReaderSurface

/** Records accepted native choices; the final mode command releases a pending initial PDF resume. */
internal class ReaderPdfBookViewPreferencesBinding {
    private var surface: ReaderSurface? = null
    private var owner: PdfBookViewOwner? = null
    private var crop: PdfPageCrop? = null
    private var fit: PdfFitPreference? = null
    private var spread: Boolean? = null
    private var navigation: PdfNavigationMode? = null
    private var fitCommand = -1L
    private var cropCommand = -1L
    private var boundCrop: PdfCropController? = null
    private var boundFit: PdfViewController? = null
    private var boundSpread: PdfSpreadController? = null
    private var boundScroll: PdfScrollController? = null

    fun apply(target: ReaderSurface, book: Book, state: PdfBookViewPreferencesState,
        isCurrent: () -> Boolean = { true }): Boolean {
        val scope = state.owner ?: return false
        val value = state.preferences ?: return false
        if (book.format != BookFormat.PDF || scope.bookId != book.id || scope.contentSha256 != book.sha256 ||
            state.status != PdfBookViewStatus.APPLYING || !isCurrent()) return false
        val cropController = checkNotNull(target.pdfCrop) { "PDF crop controller unavailable" }
        val fitController = checkNotNull(target.pdfView) { "PDF fit controller unavailable" }
        val spreadController = checkNotNull(target.pdfSpread) { "PDF spread controller unavailable" }
        val scrollController = checkNotNull(target.pdfScroll) { "PDF scroll controller unavailable" }
        fun stillCurrent() = isCurrent() && target.pdfCrop === cropController &&
            target.pdfView === fitController && target.pdfSpread === spreadController && target.pdfScroll === scrollController
        if (surface !== target || owner?.bookId != scope.bookId || owner?.contentSha256 != scope.contentSha256 ||
            boundCrop !== cropController || boundFit !== fitController || boundSpread !== spreadController ||
            boundScroll !== scrollController) {
            surface = target; owner = scope
            boundCrop = cropController; boundFit = fitController; boundSpread = spreadController
            boundScroll = scrollController
            crop = null; fit = null; spread = null; navigation = null; fitCommand = -1; cropCommand = -1
        }
        owner = scope
        val desiredCrop = PdfPageCrop(value.horizontalPercent, value.verticalPercent)
        if (!stillCurrent()) return false
        if (crop != desiredCrop || cropCommand != state.cropCommand) {
            cropController.apply(desiredCrop)
            if (!stillCurrent()) return false
            crop = desiredCrop
            cropCommand = state.cropCommand
        }
        if (!stillCurrent()) return false
        if (fit != value.fitMode || fitCommand != state.fitCommand) {
            if (value.fitMode == PdfFitPreference.WIDTH) fitController.fitWidth() else fitController.fitPage()
            if (!stillCurrent()) return false
            fit = value.fitMode
            fitCommand = state.fitCommand
        }
        if (!stillCurrent()) return false
        if (spread != value.doublePagePreferred) {
            spreadController.apply(value.doublePagePreferred)
            if (!stillCurrent()) return false
            spread = value.doublePagePreferred
        }
        if (!stillCurrent()) return false
        val scrolling = value.navigationMode != PdfNavigationMode.PAGED
        fun modeMatches() = scrollController.enabled.value == scrolling &&
            (scrollController.navigationMode?.value?.let { it == value.navigationMode }
                ?: (value.navigationMode != PdfNavigationMode.HORIZONTAL_SCROLL))
        if (navigation != value.navigationMode || !modeMatches()) {
            if (!scrollController.apply(value.navigationMode) || !stillCurrent() || !modeMatches()) return false
            navigation = value.navigationMode
        }
        return stillCurrent()
    }
}
