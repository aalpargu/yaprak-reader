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

/** Text selection, selection tools and annotation entry points of the reader. */

internal fun ReaderActivity.selectionOwner(): ReaderSelectionOwner? {
    val ready = uiState as? ReaderState.Ready ?: return null
    val active = surface ?: return null
    if (selectionExternalLaunch || !isBrightnessReaderReady() || annotationActive || hasCompetingSelectionModal() ||
        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
        (model.state.value as? ReaderState.Ready)?.session !== ready.session) return null
    return ReaderSelectionOwner(ready.session, active)
}

internal fun ReaderActivity.bindSelectionNotifications(expected: ReaderSurface) { selection.bindNotifications(expected) }

internal fun ReaderActivity.captureSelectedText() {
    val active = surface ?: return
    val source = active.textSelection ?: return
    captureSelection(active, source, ocr = false)
}

internal fun ReaderActivity.openPdfOcr(expected: ReaderSurface, page: Int) {
    if (surface !== expected || expected.pageNavigator?.captureStablePage() != page ||
        !isBrightnessReaderReady() || annotationActive || selectionPreview != null || hasCompetingSelectionModal() ||
        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    if ((uiState as? ReaderState.Ready)?.session?.book?.format != BookFormat.PDF) return
    val source = expected.pdfOcr ?: return
    expected.textSelection?.clear()
    if (source.prepare(page)) captureSelection(expected, source, ocr = true)
}

internal fun ReaderActivity.captureSelection(active: ReaderSurface, source: com.yaprak.reader.reader.TextSelectionSource, ocr: Boolean) {
    selection.capture(active, source, ocr)
}

internal fun ReaderActivity.clearSelectionPreview() { selection.clear() }

internal fun ReaderActivity.clearSelectionTools() {
    selectionAssistant.close()
    selectionAssistantState = null
    dictionary.close()
    dictionaryState = null
    translation.close()
    translationState = null
    selectionToolsGeneration++
    selectionToolsJob?.cancel()
    selectionToolsJob = null
    selectionTools = null
}

internal fun ReaderActivity.openSelectionTranslation() {
    if (!isSelectionCurrent()) return
    val expected = selectionPreview as? SelectedTextPreview.Ready ?: return
    translation.open(expected.sessionId, expected.text) {
        val actual = selectionPreview as? SelectedTextPreview.Ready
        actual?.sessionId == expected.sessionId && actual.text == expected.text && isSelectionCurrent()
    }
}
internal fun ReaderActivity.isTranslationCurrent(expected: com.yaprak.reader.feature.translation.TranslationState): Boolean {
    val actual = selectionPreview as? SelectedTextPreview.Ready ?: return false
    return isSelectionCurrent() && translation.state.value === expected &&
        actual.sessionId == expected.requestId && actual.text == expected.sourceText
}
internal fun ReaderActivity.transferTranslatedWord(expected: com.yaprak.reader.feature.translation.TranslationState) {
    if (!isTranslationCurrent(expected)) return
    val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
    val draft = com.yaprak.reader.feature.vocabulary.translatedWordDraft(expected) ?: return
    clearSelectionPreview()
    observation?.cancel()
    savedWordPrefill = null
    translatedWordPrefill = com.yaprak.reader.feature.vocabulary.TranslatedWordPrefill(book.id, book.sha256, draft)
    savedWordsOpen = true
    updateDuration()
}

internal fun ReaderActivity.lookupSelectionDictionary() {
    if (!isSelectionCurrent()) return
    val preview = selectionPreview as? SelectedTextPreview.Ready ?: return
    dictionary.lookup(preview.sessionId, preview.text)
}

internal fun ReaderActivity.openSelectionAssistant() {
    if (!isSelectionCurrent()) return
    val preview = selectionPreview as? SelectedTextPreview.Ready ?: return
    val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
    val request = selectedAssistantRequest(book, preview) ?: return
    if (!isSelectionAssistantCurrent(request)) return
    selectionAssistant.open(request, getString(R.string.selected_ai_default_question)) { isSelectionAssistantCurrent(request) }
}

internal fun ReaderActivity.isSelectionAssistantCurrent(request: com.yaprak.reader.domain.assistant.SelectedTextSourceRequest): Boolean {
    val preview = selectionPreview as? SelectedTextPreview.Ready ?: return false
    val book = (uiState as? ReaderState.Ready)?.session?.book ?: return false
    return isSelectionCurrent() && request.selectionId == preview.sessionId && request.preview == preview.text &&
        request == selectedAssistantRequest(book, preview) && selectionBookIdentity == (request.bookId to request.contentSha256) &&
        (application as YaprakApplication).container.startup.state.value == com.yaprak.reader.core.StartupState.Ready
}

internal fun ReaderActivity.retrySelectionDictionary(expected: DictionaryLookupState) {
    if (!isSelectionCurrent() || expected.requestId != selectionPreview?.sessionId) return
    dictionary.retry(expected)
}

internal fun ReaderActivity.discoverSelectionTools(preview: SelectedTextPreview.Ready) {
    if (!isSelectionCurrent() || (selectionPreview as? SelectedTextPreview.Ready)?.sessionId != preview.sessionId) return
    selectionToolsJob?.cancel()
    val generation = ++selectionToolsGeneration
    val request = SelectedTextToolsState(preview.sessionId)
    selectionTools = request
    selectionToolsJob = lifecycleScope.launch {
        val result = try { request.copy(loading = false, tools = externalTextTools.discover()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SelectedTextToolException) { request.copy(loading = false, discoveryFailure = failure.reason) }
        catch (_: Exception) { request.copy(loading = false, discoveryFailure = SelectedTextToolFailure.SOURCE) }
        val current = selectionPreview as? SelectedTextPreview.Ready
        if (generation == selectionToolsGeneration && current?.sessionId == preview.sessionId && current.text == preview.text && isSelectionCurrent())
            selectionTools = result.copy(launchFailure = selectionTools?.launchFailure)
    }
}

internal fun ReaderActivity.launchSelectionTool(tool: SelectedTextTool?) {
    if (!isSelectionCurrent()) return
    val preview = selectionPreview as? SelectedTextPreview.Ready ?: return
    val tools = selectionTools?.takeIf { it.requestId == preview.sessionId } ?: return
    if (tool != null && (tools.loading || tools.discoveryFailure != null || tool !in tools.tools)) return
    try {
        if (tool == null) externalTextTools.searchWeb(preview.text) else externalTextTools.process(tool, preview.text)
        selectionExternalLaunch = true
        clearSelectionPreview()
        // The external Activity is about to take focus. Keep the interval closed now.
        model.suspendDuration(durationOwner)
        if (!exiting) observeSurface()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SelectedTextToolException) {
        if (selectionTools === tools && selectionPreview == preview && isSelectionCurrent())
            selectionTools = tools.copy(launchFailure = failure.reason)
    }
}

internal fun ReaderActivity.isSelectionCurrent(): Boolean = selection.isCurrent()

internal fun ReaderActivity.annotateSelectedText() {
    if (!isSelectionCurrent()) return
    val preview = selectionPreview as? SelectedTextPreview.Ready ?: return
    val book = (uiState as? ReaderState.Ready)?.session?.book?.takeIf { it.format == BookFormat.EPUB } ?: return
    val anchor = eligiblePassageAnchor(preview.text, preview.sourceAnchor) ?: return
    val owner = surface ?: return
    val request = EpubAnnotationRequest(preview.sessionId, book.id, book.sha256, preview.text, anchor)
    clearSelectionPreview()
    annotationSurface = owner
    annotationRequest = request
    updateDuration()
    if (observation?.isActive != true) observeSurface()
}

internal fun ReaderActivity.canMutateAnnotation(request: EpubAnnotationRequest): Boolean = annotationRequest === request &&
    annotationSurface === surface && isBrightnessReaderReady() && !hasCompetingSelectionModal() &&
    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
    (uiState as? ReaderState.Ready)?.session?.book?.let {
        it.format == BookFormat.EPUB && it.id == request.bookId && it.sha256 == request.contentSha256 } == true

/** Every selection stage uses the same gates, including delivery after an SDK suspension. */
internal fun ReaderActivity.hasCompetingSelectionModal(includeThumbnails: Boolean = true, includeCrop: Boolean = true, includePdfAnnotations: Boolean = true, includePdfPageLinks: Boolean = true): Boolean =
    (includePdfAnnotations && (pdfAnnotationRequest != null || pdfAnnotationsRequest != null)) ||
    (includeThumbnails && pdfThumbnailsRequest != null) || (includePdfPageLinks && pdfPageLinks.state.value.open) || (includeCrop && pdfCropRequest != null) || orientation.state.value.open || epubImagesOpen || brightnessOpen ||
    passagesOpen || savedWordsOpen || bookNotesOpen || contentsOpen || bookSearchOpen || bookmarksOpen ||
    pageJumpOpen || resetPending || resetRefreshFailed || settings.state.value.open || screenAwake.state.value.open || volumeKeys.state.value.open || tapZones.state.value.open ||
    bookAppearanceOpen || bookTheme.state.value.open || bookFontSize.state.value.open || bookFont.state.value.open ||
    bookMargins.state.value.open || bookLineHeight.state.value.open || bookSpacing.state.value.open || bookTextAlignment.state.value.open || bookReadingMode.state.value.open || bookAppearanceReset.state.value.open ||
    positionJump.state.value.open

internal fun ReaderActivity.openBookNotes() {
    if (!isBrightnessReaderReady() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    readerAction { observation?.cancel(); bookNotePrefill = null; bookNotesOpen = true }
}

internal fun ReaderActivity.clearAnnotation() {
    annotationRequest = null
    annotationSurface = null
    clearPdfAnnotations()
}

internal fun ReaderActivity.annotatePdfSelection() {
    if (!isSelectionCurrent()) return
    val preview = selectionPreview as? SelectedTextPreview.Ready ?: return
    val book = (uiState as? ReaderState.Ready)?.session?.book?.takeIf { it.format == BookFormat.PDF } ?: return
    val anchor = eligiblePdfAnnotationAnchor(preview) ?: return
    val owner = surface ?: return
    clearSelectionPreview()
    pdfAnnotationSurface = owner
    pdfAnnotationRequest = com.yaprak.reader.feature.pdfannotations.PdfAnnotationRequest(book.id, book.sha256, preview.text, anchor)
    updateDuration()
    if (observation?.isActive != true) observeSurface()
}
internal fun ReaderActivity.openPdfAnnotations() {
    val owner = surface ?: return
    if (!canUsePdfSurface(owner)) return
    val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
    pdfAnnotationSurface = owner
    pdfAnnotationsRequest = com.yaprak.reader.feature.pdfannotations.PdfAnnotationsWindow(book.id, book.sha256, owner.textSelection != null)
    updateDuration()
    if (observation?.isActive != true) observeSurface()
}
internal fun ReaderActivity.canMutatePdfAnnotations(bookId: String, hash: String): Boolean =
    pdfAnnotationSurface != null && pdfAnnotationSurface === surface && isBrightnessReaderReady() &&
    !hasCompetingSelectionModal(includePdfAnnotations = false) && annotationRequest == null && selectionPreview == null &&
    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
    (application as YaprakApplication).container.startup.state.value == com.yaprak.reader.core.StartupState.Ready &&
    (uiState as? ReaderState.Ready)?.session?.book?.let { it.format == BookFormat.PDF && it.id == bookId && it.sha256 == hash } == true
internal fun ReaderActivity.openPdfAnnotationPage(request: com.yaprak.reader.feature.pdfannotations.PdfAnnotationsWindow,
    anchor: com.yaprak.reader.reader.PdfSourceAnchor): Boolean {
    if (pdfAnnotationsRequest !== request || !request.lifetime.isActive || !canMutatePdfAnnotations(request.bookId, request.hash)) return false
    val owner = surface ?: return false
    val pages = owner.pageNavigator ?: return false
    if (anchor.pageIndex !in 0 until pages.pageCount) return false
    val point = owner.pdfAnnotationNavigator.takeIf { request.editingSupported }
    val accepted = performNavigation({ if (point != null) point.goTo(anchor) else pages.goTo(anchor.pageIndex) },
        null, null, jump = true, annotations = request, onAccepted = {
            if (pdfAnnotationsRequest !== request || !request.lifetime.isActive || !canMutatePdfAnnotations(request.bookId, request.hash)) false
            else { clearPdfAnnotations(); true }
        })
    observeSurface()
    return accepted
}
internal fun ReaderActivity.clearPdfAnnotations() {
    pdfAnnotationRequest?.close(); pdfAnnotationsRequest?.close()
    pdfAnnotationRequest = null; pdfAnnotationsRequest = null; pdfAnnotationSurface = null
}

internal fun ReaderActivity.retryHighlights() {
    val owner = surface ?: return
    val highlights = owner.epubHighlights ?: return
    if (highlightRetryBusy || !isBrightnessReaderReady() || annotationActive || passagesOpen ||
        selectionPreview != null || orientation.state.value.open || epubImagesOpen || brightnessOpen ||
        settings.state.value.open || screenAwake.state.value.open || volumeKeys.state.value.open || tapZones.state.value.open || bookAppearanceOpen ||
        bookAppearanceReset.state.value.open || bookTheme.state.value.open || bookFontSize.state.value.open ||
        bookFont.state.value.open || bookMargins.state.value.open || bookLineHeight.state.value.open || bookSpacing.state.value.open || bookTextAlignment.state.value.open || bookReadingMode.state.value.open ||
        contentsOpen || bookSearchOpen || bookmarksOpen || savedWordsOpen || bookNotesOpen || pageJumpOpen || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null ||
        positionJump.state.value.open || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    highlightRetryBusy = true
    lifecycleScope.launch {
        try { highlights.retry() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (surface === owner) highlightStatus = EpubHighlightStatus.Failed(EpubHighlightFailure.APPLY_FAILED) }
        finally { highlightRetryBusy = false }
    }
}

internal fun ReaderActivity.copySelectedText() {
    if (!isSelectionCurrent()) return
    if (orientation.state.value.open || annotationActive) return
    val ready = selectionPreview as? SelectedTextPreview.Ready ?: return
    if (exiting || loadFailed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    val updated = try {
        copyPlainSelectedText(this, ready.text)
        ready.copy(copyStatus = CopyStatus.COPIED)
    } catch (_: Exception) {
        ready.copy(copyStatus = CopyStatus.FAILED)
    }
    selection.updatePreview(ready, updated)
}

internal fun ReaderActivity.transferSelectedWord() {
    if (!isSelectionCurrent()) return
    if (orientation.state.value.open || annotationActive) return
    val ready = selectionPreview as? SelectedTextPreview.Ready ?: return
    if (exiting || loadFailed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
        (if (ready.pdfOcr) surface?.pdfOcr == null else surface?.textSelection == null) || uiState !is ReaderState.Ready) return
    val candidate = selectedWordCandidate(ready.text) ?: return
    clearSelectionPreview()
    observation?.cancel()
    savedWordPrefill = candidate
    translatedWordPrefill = null
    savedWordsOpen = true
    updateDuration()
}

internal fun ReaderActivity.transferSelectedNote() {
    if (!isSelectionCurrent()) return
    if (orientation.state.value.open || annotationActive) return
    val ready = selectionPreview as? SelectedTextPreview.Ready ?: return
    if (exiting || loadFailed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
        (if (ready.pdfOcr) surface?.pdfOcr == null else surface?.textSelection == null) || uiState !is ReaderState.Ready) return
    val candidate = selectedNoteCandidate(ready.text) ?: return
    clearSelectionPreview()
    observation?.cancel()
    bookNotePrefill = candidate
    bookNotesOpen = true
    updateDuration()
}

internal fun ReaderActivity.transferSelectedPassage() {
    if (!isSelectionCurrent()) return
    if (orientation.state.value.open || annotationActive) return
    val ready = selectionPreview as? SelectedTextPreview.Ready ?: return
    if (exiting || loadFailed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
        surface?.textSelection == null || (uiState as? ReaderState.Ready)?.session?.book?.format !=
        BookFormat.EPUB) return
    val anchor = eligiblePassageAnchor(ready.text, ready.sourceAnchor) ?: return
    clearSelectionPreview()
    observation?.cancel()
    passagePrefill = PassageDraft(ready.text, anchor)
    passagesOpen = true
    updateDuration()
}
