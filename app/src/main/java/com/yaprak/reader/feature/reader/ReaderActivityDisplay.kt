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

/** Screen orientation, brightness and EPUB image filter. */

internal fun ReaderActivity.orientationAction(action: () -> Unit) {
    if (orientation.state.value.open && isBrightnessReaderReady() &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action()
}

internal fun ReaderActivity.openOrientation() {
    if (!isBrightnessReaderReady() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
        orientation.state.value.open || annotationActive || epubImagesOpen || brightnessOpen || selectionPreview != null ||
        resetPending || resetRefreshFailed || settings.state.value.open || screenAwake.state.value.open || volumeKeys.state.value.open || tapZones.state.value.open ||
        bookAppearanceOpen || bookAppearanceReset.state.value.open || bookTheme.state.value.open ||
        bookFontSize.state.value.open || bookFont.state.value.open || bookMargins.state.value.open ||
        bookLineHeight.state.value.open || bookSpacing.state.value.open || bookTextAlignment.state.value.open || bookReadingMode.state.value.open || contentsOpen || bookSearchOpen || bookmarksOpen || savedWordsOpen ||
        bookNotesOpen || passagesOpen || pageJumpOpen || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null || positionJump.state.value.open) return
    clearSelectionPreview()
    orientation.open()
    updateDuration()
    if (observation?.isActive != true) observeSurface()
}

internal fun ReaderActivity.retryOrientation() {
    if (!isBrightnessReaderReady() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
        orientation.state.value.open || annotationActive || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null) return
    updateOrientation()
    orientationController.retry()
}

internal fun ReaderActivity.isBrightnessReaderReady(): Boolean =
    uiState is ReaderState.Ready && surface != null && !loadFailed && !saveFailed &&
        !renderingFailed && !exiting && presentation.state.value.phase == ReaderPhase.Ready

internal fun ReaderActivity.updateBrightness() {
    brightnessController.update(brightnessGesturePreview ?: brightnessPercent, readerReady = isBrightnessReaderReady())
}

internal fun ReaderActivity.canGestureBrightness(): Boolean = brightnessGesturesEnabled && brightnessPercent != null &&
    !chrome && surface?.pdfInk?.active?.value != true && isBrightnessReaderReady() && window.decorView.hasWindowFocus() &&
    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !selectionExternalLaunch &&
    selectionPreview == null && !annotationActive && !hasCompetingSelectionModal() &&
    indexedChapterState.phase !in setOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.FAILED) &&
    durationFailure == null && !pdfCropApplyFailed

internal fun ReaderActivity.previewGestureBrightness(percent: Int) {
    if (!canGestureBrightness() || percent !in 10..100) return
    brightnessGesturePreview = percent
    updateBrightness(); updateDuration()
}
internal fun ReaderActivity.commitGestureBrightness(percent: Int) {
    if (canGestureBrightness() && brightnessGesturePreview != null && percent in 10..100) brightnessPercent = percent
    brightnessGesturePreview = null
    updateBrightness(); updateDuration()
}
internal fun ReaderActivity.cancelGestureBrightness() {
    brightnessGesturePreview = null
    if (brightnessControllerReady) updateBrightness()
    updateDuration()
}

internal fun ReaderActivity.openBrightness() {
    if (!isBrightnessReaderReady() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
        epubImagesOpen || brightnessOpen || selectionPreview != null || resetPending || resetRefreshFailed ||
        settings.state.value.open || screenAwake.state.value.open || volumeKeys.state.value.open || tapZones.state.value.open || orientation.state.value.open || annotationActive || bookAppearanceOpen ||
        bookAppearanceReset.state.value.open || bookTheme.state.value.open ||
        bookFontSize.state.value.open || bookFont.state.value.open || bookMargins.state.value.open ||
        bookLineHeight.state.value.open || bookSpacing.state.value.open || bookTextAlignment.state.value.open || bookReadingMode.state.value.open || contentsOpen || bookSearchOpen || bookmarksOpen ||
        savedWordsOpen || bookNotesOpen || passagesOpen || pageJumpOpen || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null || positionJump.state.value.open) return
    brightnessDraft = brightnessPercent
    brightnessGestureDraft = brightnessGesturesEnabled
    brightnessRequestId = java.util.UUID.randomUUID().toString()
    clearSelectionPreview()
    brightnessOpen = true
    updateDuration()
    // Keep failure/status flows alive; the observation predicate blocks modal sampling.
    if (observation?.isActive != true) observeSurface()
}

internal fun ReaderActivity.editBrightness(percent: Int?) {
    if (brightnessOpen && (percent == null || percent in 10..100)) {
        brightnessDraft = percent
        if (percent == null) brightnessGestureDraft = false
    }
}

internal fun ReaderActivity.editBrightnessGesture(enabled: Boolean) {
    if (brightnessOpen && brightnessDraft != null) brightnessGestureDraft = enabled
}

internal fun ReaderActivity.applyBrightness(percent: Int?) {
    if (!brightnessOpen || (percent != null && percent !in 10..100)) return
    if (isBrightnessReaderReady() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        brightnessPercent = percent
        brightnessGesturesEnabled = percent != null && brightnessGestureDraft
    }
    dismissBrightness()
    updateBrightness()
}

internal fun ReaderActivity.dismissBrightness() {
    brightnessOpen = false
    brightnessDraft = null
    brightnessGestureDraft = false
}

internal fun ReaderActivity.canSetEpubImageFilter(): Boolean =
    isBrightnessReaderReady() &&
        (uiState as? ReaderState.Ready)?.session?.book?.format == BookFormat.EPUB &&
        surface?.epubImageFilter != null

internal fun ReaderActivity.openEpubImages() {
    if (!canSetEpubImageFilter() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
        epubImagesOpen || brightnessOpen || selectionPreview != null || resetPending || resetRefreshFailed ||
        settings.state.value.open || screenAwake.state.value.open || volumeKeys.state.value.open || tapZones.state.value.open || orientation.state.value.open || annotationActive || bookAppearanceOpen ||
        bookAppearanceReset.state.value.open || bookTheme.state.value.open ||
        bookFontSize.state.value.open || bookFont.state.value.open || bookMargins.state.value.open ||
        bookLineHeight.state.value.open || bookSpacing.state.value.open || bookTextAlignment.state.value.open || bookReadingMode.state.value.open || contentsOpen || bookSearchOpen || bookmarksOpen ||
        savedWordsOpen || bookNotesOpen || passagesOpen || pageJumpOpen || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null || positionJump.state.value.open) return
    clearSelectionPreview()
    epubImageFilterDraft = epubImageFilterMode
    epubImagesFailed = false
    epubImagesOpen = true
    updateDuration()
    if (observation?.isActive != true) observeSurface()
}

internal fun ReaderActivity.editEpubImageFilter(mode: EpubImageFilterMode) {
    if (epubImagesOpen) {
        epubImageFilterDraft = mode
        epubImagesFailed = false
    }
}

internal fun ReaderActivity.applyEpubImageFilter(mode: EpubImageFilterMode) {
    if (!epubImagesOpen || !canSetEpubImageFilter() ||
        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    val controller = surface?.epubImageFilter ?: return
    try {
        controller.apply(mode)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        epubImagesFailed = true
        return
    }
    epubImageFilterMode = mode
    dismissEpubImages()
}

internal fun ReaderActivity.dismissEpubImages() {
    epubImagesOpen = false
    epubImageFilterDraft = EpubImageFilterMode.ORIGINAL
    epubImagesFailed = false
}
