package com.yaprak.reader.feature.reader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Window
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.R
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.EpubImageFilterMode
import com.yaprak.reader.reader.ContentsNavigator
import com.yaprak.reader.reader.BookmarkNavigator
import com.yaprak.reader.reader.PassageNavigator
import com.yaprak.reader.reader.IndexedChapterNavigator
import com.yaprak.reader.reader.IndexedPdfPageNavigator
import com.yaprak.reader.reader.PageNavigator
import com.yaprak.reader.reader.BookPositionNavigator
import com.yaprak.reader.reader.BookTextSearch
import com.yaprak.reader.reader.BookSearchHit
import com.yaprak.reader.reader.EpubHighlightStatus
import com.yaprak.reader.reader.EpubHighlightFailure
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.search.LibraryTextTarget
import com.yaprak.reader.domain.passages.EpubPassage
import com.yaprak.reader.feature.bookmarks.BookmarksRoute
import com.yaprak.reader.feature.notes.BookNotesRoute
import com.yaprak.reader.feature.passages.EpubPassagesRoute
import com.yaprak.reader.feature.passages.PassageDraft
import com.yaprak.reader.feature.passages.EpubAnnotationRequest
import com.yaprak.reader.feature.passages.EpubAnnotationRoute
import com.yaprak.reader.feature.vocabulary.SavedWordsRoute
import com.yaprak.reader.feature.dictionary.DictionaryLookupViewModel
import com.yaprak.reader.feature.dictionary.DictionaryLookupState
import com.yaprak.reader.ui.theme.YaprakTheme
import com.yaprak.reader.feature.settings.EpubSettingsDialog
import com.yaprak.reader.feature.settings.EpubSettingsState
import com.yaprak.reader.feature.settings.EpubSettingsViewModel
import com.yaprak.reader.feature.settings.TapZoneDialog
import com.yaprak.reader.feature.settings.TapZoneState
import com.yaprak.reader.feature.settings.TapZoneViewModel
import com.yaprak.reader.reader.ReaderTap
import com.yaprak.reader.domain.settings.TapZoneAction
import com.yaprak.reader.feature.settings.VolumeKeyDialog
import com.yaprak.reader.feature.settings.VolumeKeyState
import com.yaprak.reader.feature.settings.VolumeKeyViewModel
import com.yaprak.reader.feature.settings.ScreenAwakeDialog
import com.yaprak.reader.feature.settings.ScreenAwakeState
import com.yaprak.reader.feature.settings.ScreenAwakeViewModel
import com.yaprak.reader.feature.settings.ReaderOrientationDialog
import com.yaprak.reader.feature.settings.ReaderOrientationState
import com.yaprak.reader.feature.settings.ReaderOrientationViewModel
import com.yaprak.reader.feature.settings.BookThemeDialog
import com.yaprak.reader.feature.settings.BookThemeState
import com.yaprak.reader.feature.settings.BookThemeViewModel
import com.yaprak.reader.feature.settings.BookFontSizeDialog
import com.yaprak.reader.feature.settings.BookFontSizeState
import com.yaprak.reader.feature.settings.BookFontSizeViewModel
import com.yaprak.reader.feature.settings.BookFontDialog
import com.yaprak.reader.feature.settings.BookFontState
import com.yaprak.reader.feature.settings.BookFontViewModel
import com.yaprak.reader.feature.settings.BookAppearanceDialog
import com.yaprak.reader.feature.settings.BookMarginsDialog
import com.yaprak.reader.feature.settings.BookMarginsState
import com.yaprak.reader.feature.settings.BookMarginsViewModel
import com.yaprak.reader.feature.settings.BookLineHeightDialog
import com.yaprak.reader.feature.settings.BookLineHeightState
import com.yaprak.reader.feature.settings.BookLineHeightViewModel
import com.yaprak.reader.feature.settings.BookSpacingDialog
import com.yaprak.reader.feature.settings.BookSpacingState
import com.yaprak.reader.feature.settings.BookSpacingViewModel
import com.yaprak.reader.feature.settings.BookTextAlignmentDialog
import com.yaprak.reader.feature.settings.BookTextAlignmentState
import com.yaprak.reader.feature.settings.BookTextAlignmentViewModel
import com.yaprak.reader.feature.settings.BookReadingModeDialog
import com.yaprak.reader.feature.settings.BookReadingModeState
import com.yaprak.reader.feature.settings.BookReadingModeViewModel
import com.yaprak.reader.feature.settings.BookAppearanceResetDialog
import com.yaprak.reader.feature.settings.BookAppearanceResetState
import com.yaprak.reader.feature.settings.BookAppearanceResetViewModel
import com.yaprak.reader.feature.settings.BookAppearanceResetRefreshDialog
import com.yaprak.reader.domain.settings.PdfFitPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** PDF crop, page links and thumbnails panels. */

internal fun ReaderActivity.cropFor(book: Book): PdfPageCrop {
    val current = pdfBookView.state.value
    val preference = current.preferences?.takeIf {
        current.owner?.bookId == book.id && current.owner?.contentSha256 == book.sha256
    } ?: return PdfPageCrop()
    return PdfPageCrop(preference.horizontalPercent, preference.verticalPercent)
}

internal fun ReaderActivity.openPdfCrop() {
    openPdfCrop(surface, pdfBookView.state.value)
}

internal fun ReaderActivity.openPdfCrop(expectedOwner: ReaderSurface?, expected: PdfBookViewPreferencesState) {
    val owner = surface ?: return
    if (owner !== expectedOwner || pdfBookView.state.value != expected || !canUsePdfSurface(owner)) return
    val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
    val preferences = pdfBookView.state.value
    if (!preferences.canEdit) return
    val controller = owner.pdfCrop ?: return
    val source = owner.pdfThumbnails
    val navigator = pageNavigator?.takeIf { owner.pageNavigator === it }
    val hasPreview = source != null && navigator != null && source.pageCount > 0 &&
        source.pageCount == navigator.pageCount
    val page = if (hasPreview) navigator?.currentPage?.takeIf { it in 0 until source!!.pageCount } else null
    pdfCropRequest = PdfCropRequest(book, owner, controller, cropFor(book),
        if (hasPreview) source else null, if (hasPreview) navigator else null, page,
        if (hasPreview) source!!.pageCount else 0, preferences.owner, preferences.revision)
    updateDuration()
    updatePdfThumbnailsEffects()
}

internal fun ReaderActivity.isPdfCropCurrent(request: PdfCropRequest): Boolean =
    pdfCropRequest === request && pdfCropRequest?.id == request.id &&
        (uiState as? ReaderState.Ready)?.session?.book == request.book &&
        request.book.format == BookFormat.PDF && surface === request.surface &&
        surface?.pdfCrop === request.controller && pdfBookView.state.value.owner == request.preferencesOwner &&
        (pdfBookView.state.value.revision == request.preferencesRevision || pdfCropSavingRequest === request) &&
        pdfBookView.state.value.status != PdfBookViewStatus.LOAD_FAILED &&
        (request.previewSource == null || (surface?.pdfThumbnails === request.previewSource &&
            request.previewSource.pageCount == request.previewPageCount && request.previewPageCount > 0)) &&
        (request.previewNavigator == null || (pageNavigator === request.previewNavigator &&
            surface?.pageNavigator === request.previewNavigator &&
            request.previewNavigator.pageCount == request.previewPageCount)) &&
        (request.previewPage == null || request.previewPage in 0 until request.previewPageCount) &&
        !exiting && !loadFailed && !saveFailed && !model.saveFailed.value &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !annotationActive &&
        selectionPreview == null && !hasCompetingSelectionModal(includeCrop = false)

internal suspend fun ReaderActivity.renderPdfCropPreview(request: PdfCropRequest): Bitmap {
    if (!isPdfCropCurrent(request)) throw CancellationException("Obsolete PDF crop preview")
    val source = checkNotNull(request.previewSource)
    val page = checkNotNull(request.previewPage)
    val image = source.render(page)
    currentCoroutineContext().ensureActive()
    if (!isPdfCropCurrent(request)) throw CancellationException("Obsolete PDF crop image")
    return image
}

internal suspend fun ReaderActivity.applyPdfCrop(request: PdfCropRequest, crop: PdfPageCrop): Boolean {
    if (!isPdfCropCurrent(request)) return false
    if (pdfBookView.state.value.status == PdfBookViewStatus.APPLY_FAILED && crop == cropFor(request.book)) {
        if (pdfCropSavingRequest != null) return false
        pdfCropSavingRequest = request
        try {
            pdfBookView.retryApply()
            request.preferencesRevision = pdfBookView.state.value.revision
            val accepted = applyPdfBookView()
            if (accepted && isPdfCropCurrent(request)) clearPdfCrop(request)
            return accepted
        } finally { if (pdfCropSavingRequest === request) pdfCropSavingRequest = null }
    }
    if (!pdfBookView.state.value.canEdit || pdfCropSavingRequest != null) return false
    pdfCropSavingRequest = request
    try {
        val committed = pdfBookView.saveCrop(pdfBookView.state.value, crop.horizontalPercent, crop.verticalPercent)
            ?: return false
        currentCoroutineContext().ensureActive()
        if (pdfCropRequest !== request || surface !== request.surface || !pdfBookView.isCurrent(committed)) return false
        // This editor keeps its draft after its own commit, but no external revision.
        request.preferencesRevision = committed.revision
        val applied = applyPdfBookView()
        if (applied && isPdfCropCurrent(request)) clearPdfCrop(request)
        return applied
    } finally {
        val current = pdfBookView.state.value
        if (pdfCropRequest === request && current.owner == request.preferencesOwner &&
            current.status == PdfBookViewStatus.SAVE_FAILED)
            request.preferencesRevision = current.revision
        if (pdfCropSavingRequest === request) pdfCropSavingRequest = null
    }
}

internal fun ReaderActivity.dismissPdfCrop(request: PdfCropRequest) {
    if (isPdfCropCurrent(request)) clearPdfCrop(request)
}

internal fun ReaderActivity.clearPdfCrop(expected: PdfCropRequest? = null) {
    if (expected == null || pdfCropRequest === expected) {
        pdfCropRequest = null
        updatePdfThumbnailsEffects()
    }
}

internal fun ReaderActivity.openPdfPageLinks(owner: ReaderSurface, page: Int) {
    if (!canUsePageSurface(owner)) return
    val book = (uiState as? ReaderState.Ready)?.session?.book?.takeIf { it.format == BookFormat.PDF } ?: return
    val source = owner.pdfPageLinks ?: return
    val navigator = owner.pageNavigator?.takeIf { pageNavigator === it } ?: return
    fun current() = surface === owner && owner.pdfPageLinks === source && pageNavigator === navigator &&
        (uiState as? ReaderState.Ready)?.session?.book == book && navigator.captureStablePage() == page &&
        !exiting && !loadFailed && !saveFailed && !model.saveFailed.value &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !annotationActive &&
        selectionPreview == null && !hasCompetingSelectionModal(includePdfPageLinks = false)
    pdfPageLinks.open(source, page, ::current) { request, target ->
        if (!current()) false else navigateJump({ source.goTo(target) }, null, request)
    }
}

internal fun ReaderActivity.openPdfThumbnails() {
    val owner = surface ?: return
    if (!canUsePageSurface(owner, allowPdfRenderRecovery = true)) return
    val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
    if (book.format.comic && (!durationVisible || durationSurface !== owner ||
        renderingFailed || !window.decorView.hasWindowFocus())) return
    val source = owner.pageThumbnails ?: return
    val navigator = pageNavigator?.takeIf { owner.pageNavigator === it } ?: return
    val count = source.pageCount
    if (count <= 0 || navigator.pageCount != count) return
    val current = navigator.currentPage?.takeIf { it in 0 until count }
    pdfThumbnailsRequest = PdfThumbnailsRequest(book, owner, source, navigator, count, current)
    updateDuration()
    updatePdfThumbnailsEffects()
}

internal fun ReaderActivity.isPdfThumbnailsCurrent(request: PdfThumbnailsRequest): Boolean =
    pdfThumbnailsRequest === request && pdfThumbnailsRequest?.id == request.id &&
        (uiState as? ReaderState.Ready)?.session?.book == request.book &&
        request.book.format in setOf(BookFormat.PDF, BookFormat.CBZ, BookFormat.CBR) && surface === request.surface &&
        surface?.pageThumbnails === request.source && pageNavigator === request.navigator &&
        surface?.pageNavigator === request.navigator && request.pageCount > 0 &&
        request.source.pageCount == request.pageCount && request.navigator.pageCount == request.pageCount &&
        !exiting && !loadFailed && !saveFailed && !model.saveFailed.value &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !annotationActive && selectionPreview == null && !hasCompetingSelectionModal(includeThumbnails = false)

internal suspend fun ReaderActivity.renderPdfThumbnail(request: PdfThumbnailsRequest, pageIndex: Int): Bitmap {
    if (!isPdfThumbnailsCurrent(request)) throw CancellationException("Obsolete PDF thumbnail request")
    require(pageIndex in 0 until request.pageCount)
    val image = request.source.render(pageIndex)
    currentCoroutineContext().ensureActive()
    if (!isPdfThumbnailsCurrent(request)) throw CancellationException("Obsolete PDF thumbnail result")
    // The source owns shared images. Rejecting delivery must never recycle a cached bitmap.
    return image
}

internal fun ReaderActivity.selectPdfThumbnail(request: PdfThumbnailsRequest, pageIndex: Int): Boolean {
    if (!isPdfThumbnailsCurrent(request) || pageIndex !in 0 until request.pageCount) return false
    val accepted = navigateJump({ request.navigator.goTo(pageIndex) }, request)
    if (accepted && pdfThumbnailsRequest === request) {
        clearPdfThumbnails(request)
    }
    return accepted
}

internal fun ReaderActivity.dismissPdfThumbnails(request: PdfThumbnailsRequest) {
    if (!isPdfThumbnailsCurrent(request)) return
    clearPdfThumbnails(request)
}

internal fun ReaderActivity.clearPdfThumbnails(expected: PdfThumbnailsRequest? = null) {
    if (expected == null || pdfThumbnailsRequest === expected) {
        pdfThumbnailsRequest = null
        updatePdfThumbnailsEffects()
    }
}

internal fun ReaderActivity.updatePdfThumbnailsEffects() {
    // Coverage/swipe predicates read the live modal state. Resubscribing to a PDF's
    // rendered-page StateFlow would replay its old page and write reading state again.
    if (immersiveControllerReady) updateImmersive()
    if (brightnessControllerReady) updateBrightness()
}

internal fun ReaderActivity.capturePdfNotePage(): Int? {
    if (orientation.state.value.open || annotationActive) return null
    if (exiting || loadFailed || !bookNotesOpen ||
        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
    return pageNavigator?.captureStablePage()
}
