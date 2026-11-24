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

/** Verified EPUB chapter handoff from library text search. */

internal fun ReaderActivity.indexedChapterToolsAvailable(): Boolean = indexedChapterForeground && !exiting &&
    !loadFailed && !saveFailed && !model.saveFailed.value &&
    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
    !annotationActive && selectionPreview == null && !hasCompetingSelectionModal()

internal fun ReaderActivity.isIndexedChapterCurrent(request: IndexedChapterRequest, attempt: String, generation: Int,
    book: Book, owner: ReaderSurface, navigator: Any): Boolean =
    indexedChapterToolsAvailable() && indexedChapterGeneration == generation &&
        indexedChapterAttempt == attempt && model.indexedChapterOpen.isVerifying(request, attempt) &&
        (uiState as? ReaderState.Ready)?.session?.book == book && surface === owner &&
        indexedNavigator(owner, request.target.sourceFormat) === navigator && book.id == request.target.bookId &&
        book.sha256 == request.target.contentSha256 && book.format == request.target.sourceFormat

internal fun ReaderActivity.indexedNavigator(owner: ReaderSurface, format: BookFormat): Any? = when (format) {
    BookFormat.EPUB -> owner.indexedChapterNavigator
    BookFormat.PDF -> owner.indexedPdfPageNavigator
    BookFormat.TXT, BookFormat.FB2, BookFormat.DOCX -> owner.indexedChapterNavigator
    BookFormat.CBZ, BookFormat.CBR -> null
}

internal fun ReaderActivity.startIndexedChapter() {
    val open = model.indexedChapterOpen
    val state = open.state.value
    if (state.phase != IndexedChapterPhase.PENDING || !indexedChapterToolsAvailable()) return
    val request = state.request ?: return
    val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
    val owner = surface ?: return
    val jumpOwner = navigationOwner() ?: return
    if (book.id != request.target.bookId || book.sha256 != request.target.contentSha256) {
        open.failPending(request, IndexedChapterFailure.SOURCE); return
    }
    val navigator = indexedNavigator(owner, request.target.sourceFormat)
    if (book.format != request.target.sourceFormat || navigator == null ||
        (navigator is IndexedPdfPageNavigator && !navigator.supported)) {
        open.failPending(request, IndexedChapterFailure.UNSUPPORTED); return
    }
    val generation = indexedChapterGeneration
    val attempt = open.begin(request) ?: return
    indexedChapterAttempt = attempt
    indexedChapterJob = lifecycleScope.launch {
        try {
            // Activity RESUMED may precede a just-installed native Fragment's resume.
            owner.fragment.lifecycle.withResumed { Unit }
            if (!isIndexedChapterCurrent(request, attempt, generation, book, owner, navigator)) {
                open.fail(request, attempt, IndexedChapterFailure.INTERRUPTED); return@launch
            }
            val sourceMatches = try { when (navigator) {
                is IndexedChapterNavigator -> navigator.verifiedChapterHref(request.target) == request.target.resourceHref
                is IndexedPdfPageNavigator -> navigator.verifiedPageIndex(request.target) == request.target.pdfPageIndex
                else -> false
            } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            currentCoroutineContext().ensureActive()
            if (!isIndexedChapterCurrent(request, attempt, generation, book, owner, navigator)) {
                open.fail(request, attempt, IndexedChapterFailure.INTERRUPTED); return@launch
            }
            if (!sourceMatches) {
                open.fail(request, attempt, IndexedChapterFailure.SOURCE); return@launch
            }
            val bookmarks = bookmarkNavigator?.takeIf { owner.bookmarkNavigator === it }
            val source = try { bookmarks?.capture()?.location }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            // SDK capture is external code and may replace the surface or open a modal.
            if (!isIndexedChapterCurrent(request, attempt, generation, book, owner, navigator)) {
                open.fail(request, attempt, IndexedChapterFailure.INTERRUPTED); return@launch
            }
            // Ready installed the real locator observer; this jump keeps its subscription.
            if (!open.consume(request, attempt)) return@launch
            val accepted = try { when (navigator) {
                is IndexedChapterNavigator -> navigator.goToChapter(request.target.resourceHref)
                is IndexedPdfPageNavigator -> navigator.goToPage(requireNotNull(request.target.pdfPageIndex))
                else -> false
            } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            chrome = true
            navigation.record(jumpOwner, source, accepted && !loadFailed && surface === owner &&
                owner.bookmarkNavigator === bookmarks)
            if (!accepted) open.fail(request, attempt, IndexedChapterFailure.NAVIGATION)
        } catch (cancelled: CancellationException) {
            open.cancel(attempt)
            throw cancelled
        } finally {
            if (indexedChapterAttempt == attempt) {
                indexedChapterAttempt = null
                indexedChapterJob = null
            }
        }
    }
}

internal fun ReaderActivity.retryIndexedChapter(expected: IndexedChapterState): Boolean {
    if (!indexedChapterToolsAvailable() || uiState !is ReaderState.Ready || surface == null) return false
    return model.indexedChapterOpen.retry(expected)
}

internal fun ReaderActivity.cancelIndexedChapter() {
    indexedChapterForeground = false
    indexedChapterGeneration++
    val attempt = indexedChapterAttempt
    indexedChapterAttempt = null
    indexedChapterJob?.cancel()
    indexedChapterJob = null
    attempt?.let { model.indexedChapterOpen.cancel(it) }
}
