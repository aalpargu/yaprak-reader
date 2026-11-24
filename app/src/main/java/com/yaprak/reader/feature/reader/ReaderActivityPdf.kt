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

/** PDF and comic view preferences: fit, scroll, spread, night mode. */

internal fun ReaderActivity.canUsePdfSurface(candidate: ReaderSurface): Boolean =
    (uiState as? ReaderState.Ready)?.session?.book?.format == BookFormat.PDF && canUsePageSurface(candidate)

internal fun ReaderActivity.bindComicSpread(owner: ReaderSurface) {
    comicSpreadPreferences?.cancel()
    val source = owner.comicSpread
    comicDoublePagePreferred = source?.preferred?.value ?: false
    comicSpreadPreferences = source?.let {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                owner.fragment.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    applyComicBookView()
                    it.preferred.collect { preferred ->
                        if (surface === owner && owner.comicSpread === it) comicDoublePagePreferred = preferred
                    }
                }
            }
        }
    }
}

internal fun ReaderActivity.toggleComicSpread(owner: ReaderSurface, expected: Boolean) {
    toggleComicSpread(owner, expected, comicBookView.state.value)
}

internal fun ReaderActivity.toggleComicSpread(owner: ReaderSurface, expected: Boolean, lease: ComicBookViewPreferencesState) {
    val source = owner.comicSpread ?: return
    val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
    if (!chrome || !canUsePageSurface(owner) || !window.decorView.hasWindowFocus() || renderingFailed ||
        model.saveFailed.value || (uiState as? ReaderState.Ready)?.session?.book?.format?.comic != true ||
        expected != source.preferred.value || owner.pageNavigator?.captureStablePage() == null ||
        comicBookView.state.value != lease || !lease.canEdit || lease.owner?.bookId != book.id ||
        lease.owner?.contentSha256 != book.sha256) return
    model.suspendDuration(durationOwner)
    observation?.cancel()
    lifecycleScope.launch {
        comicBookView.saveSpread(lease, !expected)
        if (surface === owner) applyComicBookView()
        updateDuration(); observeSurface()
    }
}

internal fun ReaderActivity.bindComicBookView() {
    if (comicBookViewSurface !== surface) { comicBookView.deactivate(); comicBookViewSurface = surface }
    val book = (uiState as? ReaderState.Ready)?.session?.book
    if (book?.format?.comic == true && surface != null && !exiting && !loadFailed &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) comicBookView.bind(book)
    else if (comicBookView.state.value.status != ComicBookViewStatus.INACTIVE) comicBookView.deactivate()
}

internal fun ReaderActivity.applyComicBookView(): Boolean {
    val expected = comicBookView.state.value
    if (expected.status != ComicBookViewStatus.APPLYING) return expected.status == ComicBookViewStatus.READY
    val book = (uiState as? ReaderState.Ready)?.session?.book ?: return false
    val owner = surface ?: return false
    if (exiting || loadFailed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !owner.fragment.isResumed) return false
    return try {
        if (comicBookViewBinding.apply(owner, book, expected) {
                comicBookView.state.value == expected && surface === owner && !exiting && !loadFailed &&
                    (uiState as? ReaderState.Ready)?.session?.book == book &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            } && comicBookView.state.value == expected) {
            comicBookView.applied(expected)
            comicDoublePagePreferred = owner.comicSpread?.preferred?.value ?: false
            true
        } else { comicBookView.applyFailed(expected); chrome = true; false }
    } catch (cancelled: CancellationException) { comicBookView.applyFailed(expected); throw cancelled }
    catch (_: Exception) { comicBookView.applyFailed(expected); chrome = true; false }
}

internal fun ReaderActivity.retryComicBookView(owner: ReaderSurface, expected: ComicBookViewPreferencesState) {
    if (!chrome || !canUsePageSurface(owner) || !window.decorView.hasWindowFocus() ||
        comicBookView.state.value != expected || !expected.failed) return
    lifecycleScope.launch { comicBookView.retry(); applyComicBookView() }
}

internal fun ReaderActivity.comicBookViewSettled(): Boolean =
    (uiState as? ReaderState.Ready)?.session?.book?.format?.comic != true ||
        comicBookView.state.value.status == ComicBookViewStatus.READY

internal fun ReaderActivity.canUsePageSurface(candidate: ReaderSurface): Boolean = canUsePageSurface(candidate, false)

internal fun ReaderActivity.isPdfRenderRecovery(): Boolean =
    (uiState as? ReaderState.Ready)?.session?.book?.format == BookFormat.PDF && renderingFailed &&
        presentation.state.value.phase == ReaderPhase.BlockingError(ReaderBlockingReason.RENDER)

internal fun ReaderActivity.canUsePageSurface(candidate: ReaderSurface, allowPdfRenderRecovery: Boolean): Boolean =
    (uiState as? ReaderState.Ready)?.session?.book?.format in setOf(BookFormat.PDF, BookFormat.CBZ, BookFormat.CBR) &&
        surface === candidate && !exiting && !loadFailed && !saveFailed &&
        (presentation.state.value.phase == ReaderPhase.Ready || allowPdfRenderRecovery && isPdfRenderRecovery()) &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !epubImagesOpen && !brightnessOpen && selectionPreview == null && !resetPending && !resetRefreshFailed &&
        !settings.state.value.open && !screenAwake.state.value.open && !tapZones.state.value.open && !volumeKeys.state.value.open && !orientation.state.value.open && !annotationActive && !bookAppearanceOpen &&
        !bookAppearanceReset.state.value.open && !bookTheme.state.value.open &&
        !bookFontSize.state.value.open && !bookFont.state.value.open &&
        !bookMargins.state.value.open && !bookLineHeight.state.value.open && !bookSpacing.state.value.open && !bookTextAlignment.state.value.open && !bookReadingMode.state.value.open &&
        !contentsOpen && !bookSearchOpen && !bookmarksOpen && !savedWordsOpen &&
        !bookNotesOpen && !passagesOpen && !pageJumpOpen && pdfThumbnailsRequest == null && !pdfPageLinks.state.value.open && pdfCropRequest == null && !positionJump.state.value.open

internal fun ReaderActivity.fitPdf(width: Boolean) {
    fitPdf(surface, pdfBookView.state.value, width)
}

internal fun ReaderActivity.fitPdf(expectedOwner: ReaderSurface?, expected: PdfBookViewPreferencesState, width: Boolean) {
    val active = surface ?: return
    if (active !== expectedOwner || !canUsePdfSurface(active) || active.pdfView == null ||
        pdfBookView.state.value != expected || !expected.canEdit) return
    lifecycleScope.launch {
        pdfBookView.saveFit(expected, if (width) PdfFitPreference.WIDTH else PdfFitPreference.PAGE)
        if (surface === active) applyPdfBookView()
    }
}

internal fun ReaderActivity.changePdfNavigation(expectedOwner: ReaderSurface?, source: com.yaprak.reader.reader.PdfScrollController,
    expected: com.yaprak.reader.domain.settings.PdfNavigationMode,
    choice: com.yaprak.reader.domain.settings.PdfNavigationMode, lease: PdfBookViewPreferencesState) {
    val owner = surface ?: return
    if (!chrome || owner !== expectedOwner || owner.pdfScroll !== source || !canUsePdfSurface(owner) ||
        source.navigationMode?.value != expected || source.enabled.value != (expected != com.yaprak.reader.domain.settings.PdfNavigationMode.PAGED) ||
        pdfBookView.state.value != lease || !lease.canEdit || choice == expected) return
    model.suspendDuration(durationOwner)
    lifecycleScope.launch {
        pdfBookView.saveNavigation(lease, choice)
        if (surface === owner) applyPdfBookView()
    }
}

internal fun ReaderActivity.togglePdfScroll(expectedOwner: ReaderSurface?, source: com.yaprak.reader.reader.PdfScrollController,
    expected: Boolean, lease: PdfBookViewPreferencesState) {
    val owner = surface ?: return
    if (!chrome || owner !== expectedOwner || owner.pdfScroll !== source || !canUsePdfSurface(owner) ||
        source.enabled.value != expected || pdfBookView.state.value != lease || !lease.canEdit) return
    model.suspendDuration(durationOwner)
    lifecycleScope.launch {
        pdfBookView.saveNavigation(lease, if (expected)
            com.yaprak.reader.domain.settings.PdfNavigationMode.PAGED else
            com.yaprak.reader.domain.settings.PdfNavigationMode.VERTICAL_SCROLL)
        if (surface === owner) applyPdfBookView()
    }
}

internal fun ReaderActivity.spreadFor(book: Book): Boolean {
    val current = pdfBookView.state.value
    return if (current.owner?.bookId == book.id && current.owner?.contentSha256 == book.sha256)
        current.applied?.doublePagePreferred ?: false else false
}

internal fun ReaderActivity.togglePdfSpread(book: Book, owner: ReaderSurface, expected: Boolean) {
    togglePdfSpread(book, owner, expected, pdfBookView.state.value)
}

internal fun ReaderActivity.togglePdfSpread(book: Book, owner: ReaderSurface, expected: Boolean,
    lease: PdfBookViewPreferencesState) {
    if (!chrome || !canUsePdfSurface(owner) || (uiState as? ReaderState.Ready)?.session?.book != book ||
        expected != spreadFor(book) || pdfBookView.state.value != lease || !lease.canEdit ||
        owner.pdfSpread == null || owner.pdfScroll?.enabled?.value == true) return
    lifecycleScope.launch {
        pdfBookView.saveSpread(lease, !expected)
        if (surface === owner) applyPdfBookView()
    }
}

internal fun ReaderActivity.bindPdfBookView() {
    bindComicBookView()
    if (pdfBookViewSurface !== surface) {
        pdfBookView.deactivate()
        clearPdfNavigationResumeObserver()
        pdfBookViewSurface = surface
        val owner = surface
        val controller = owner?.pdfScroll
        if (owner != null && controller != null) {
            // One observer per surface. The child's resume can follow Activity.onResume.
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && surface === owner &&
                    owner.pdfScroll === controller) applyPdfBookView()
            }
            pdfNavigationResumeObserver = observer
            owner.fragment.lifecycle.addObserver(observer)
        }
        clearPdfCrop()
    }
    val book = (uiState as? ReaderState.Ready)?.session?.book
    if (book?.format == BookFormat.PDF && surface != null && !exiting && !loadFailed &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        pdfBookView.bind(book.id, book.sha256)
    else if (pdfBookView.state.value.status != PdfBookViewStatus.INACTIVE) pdfBookView.deactivate()
}

internal fun ReaderActivity.clearPdfNavigationResumeObserver() {
    pdfNavigationResumeObserver?.let { pdfBookViewSurface?.fragment?.lifecycle?.removeObserver(it) }
    pdfNavigationResumeObserver = null
}

internal fun ReaderActivity.applyPdfBookView(): Boolean {
    val expected = pdfBookView.state.value
    if (expected.status != PdfBookViewStatus.APPLYING) return expected.status == PdfBookViewStatus.READY
    val book = (uiState as? ReaderState.Ready)?.session?.book ?: return false
    val owner = surface ?: return false
    if (exiting || loadFailed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
    // FragmentActivity resumes its children in onPostResume, after Activity.onResume.
    // Normal restoration waits for that owner instead of reporting a native apply failure.
    if (!owner.fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
    return try {
        if (!pdfBookViewBinding.apply(owner, book, expected) {
                pdfBookView.state.value == expected && surface === owner &&
                    (uiState as? ReaderState.Ready)?.session?.book == book && !exiting && !loadFailed &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    && owner.fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            } || pdfBookView.state.value != expected) {
            pdfBookView.applyFailed(expected)
            false
        }
        else {
            pdfBookView.applied(expected)
            pdfCropApplyFailed = false
            true
        }
    } catch (cancelled: CancellationException) {
        pdfBookView.applyFailed(expected)
        throw cancelled
    }
    catch (_: Exception) {
        pdfBookView.applyFailed(expected)
        chrome = true
        false
    }
}

internal fun ReaderActivity.retryPdfBookView(expected: PdfBookViewPreferencesState) {
    val owner = surface ?: return
    if (pdfBookView.state.value != expected || !expected.failed || !canUsePdfSurface(owner)) return
    lifecycleScope.launch { pdfBookView.retry(); applyPdfBookView() }
}

internal fun ReaderActivity.togglePdfNightMode() {
    if (tapZones.state.value.open || volumeKeys.state.value.open || orientation.state.value.open || annotationActive || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null) return
    val ready = uiState as? ReaderState.Ready ?: return
    if (ready.session.book.format != BookFormat.PDF || exiting || loadFailed ||
        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    if (surface?.pdfNightMode == null || !pdfNightMode.state.value.canToggle) return
    pdfNightMode.toggle()
    applyPdfNightMode()
}

internal fun ReaderActivity.retryPdfNightMode() {
    if (tapZones.state.value.open || volumeKeys.state.value.open || orientation.state.value.open || annotationActive || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null) return
    val ready = uiState as? ReaderState.Ready ?: return
    if (ready.session.book.format != BookFormat.PDF || exiting || loadFailed ||
        surface?.pdfNightMode == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    pdfNightMode.retry()
}

internal fun ReaderActivity.applyPdfNightMode() {
    pdfNightBinding.update(surface, (uiState as? ReaderState.Ready)?.session?.book?.format,
        pdfNightMode.state.value,
        readerReady = uiState is ReaderState.Ready && !exiting && !loadFailed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
}

/** PDF highlighter: the surface owns its toolbar and gestures; reading controls step aside. */
internal fun ReaderActivity.startPdfInk() {
    val pen = surface?.pdfInk ?: return
    if (exiting || presentation.state.value.phase != ReaderPhase.Ready) return
    if (pen.enter()) chrome = false
    else android.widget.Toast.makeText(this, "Sayfa yüklenince yeniden dene.", android.widget.Toast.LENGTH_SHORT).show()
}