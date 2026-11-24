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

/** Collects the reader session, settings and tool states while the reader is resumed. Called once from onCreate. */
internal fun ReaderActivity.observeReaderSession() {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { dictionary.state.collect { state ->
                dictionaryState = state?.takeIf { it.requestId == selectionPreview?.sessionId && isSelectionCurrent() }
            } }
            launch { translation.state.collect { state ->
                translationState = state?.takeIf { isTranslationCurrent(it) }
            } }
            launch { selectionAssistant.state.collect { state ->
                selectionAssistantState = state?.takeIf { isSelectionAssistantCurrent(it.request) }
            } }
            launch { model.durationFailure.collect { durationFailure = it; updateDuration() } }
            launch {
                model.indexedChapterOpen.state.collect {
                    indexedChapterState = it
                    startIndexedChapter()
                }
            }
            launch {
                pdfNightMode.state.collect {
                    pdfNightState = it
                    applyPdfNightMode()
                }
            }
            launch {
                pdfBookView.state.collect {
                    pdfBookViewState = it
                    pdfCropApplyFailed = it.status == PdfBookViewStatus.APPLY_FAILED
                    if (pdfCropRequest?.let { request -> request.preferencesOwner != it.owner ||
                            request.preferencesRevision != it.revision } == true &&
                        pdfCropSavingRequest !== pdfCropRequest)
                        clearPdfCrop()
                    if (pdfCropSavingRequest == null) applyPdfBookView()
                    if (it.failed) chrome = true
                    updateDuration()
                    updateImmersive()
                }
            }
            launch {
                comicBookView.state.collect {
                    comicBookViewState = it
                    applyComicBookView()
                    if (it.failed) chrome = true
                    updateDuration()
                    updateImmersive()
                }
            }
            launch {
                orientationController.applyFailed.collect { orientationApplyFailed = it }
            }
            combine(model.state, model.saveFailed, settings.state, bookTheme.state, bookFontSize.state) { state, failed, preferences, localTheme, localFont ->
                ReaderInputs(state, failed, preferences, localTheme, localFont)
            }.combine(bookFont.state) { inputs, family -> inputs.copy(localFamily = family) }
                .combine(bookMargins.state) { inputs, margins -> inputs.copy(localMargins = margins) }
                .combine(bookLineHeight.state) { inputs, lineHeight -> inputs.copy(localLineHeight = lineHeight) }
                .combine(bookSpacing.state) { inputs, spacing -> inputs.copy(localSpacing = spacing) }
                .combine(bookTextAlignment.state) { inputs, alignment -> inputs.copy(localAlignment = alignment) }
                .combine(bookReadingMode.state) { inputs, mode -> inputs.copy(localMode = mode) }
                .combine(bookAppearanceReset.state) { inputs, reset -> inputs.copy(localReset = reset) }
                .combine(screenAwake.state) { inputs, awake -> inputs.copy(screenAwake = awake) }
                .combine(orientation.state) { inputs, direction -> inputs.copy(orientation = direction) }
                .combine(volumeKeys.state) { inputs, keys -> inputs.copy(volumeKeys = keys) }
                .combine(tapZones.state) { inputs, taps -> inputs.copy(tapZones = taps) }
                .collect { (state, failed, preferences, localTheme, localFont, localFamily, localMargins, localLineHeight, localSpacing, localAlignment, localMode, localReset, awake, direction, keys, taps) ->
                val bookDialogClosed = (bookThemeState.open && !localTheme.open) ||
                    (bookFontSizeState.open && !localFont.open) ||
                    (bookFontState.open && !localFamily.open) ||
                    (bookMarginsState.open && !localMargins.open) ||
                    (bookLineHeightState.open && !localLineHeight.open) ||
                    (bookSpacingState.open && !localSpacing.open) ||
                    (bookTextAlignmentState.open && !localAlignment.open) ||
                    (bookReadingModeState.open && !localMode.open) ||
                    (screenAwakeState.open && !awake.open) || (orientationState.open && !direction.open) || (volumeKeyState.open && !keys.open) || (tapZoneState.open && !taps.open)
                uiState = state
                (state as? ReaderState.Ready)?.session?.book?.takeIf { it.id != continueShortcutBookId }?.let { book ->
                    continueShortcutBookId = book.id
                    ContinueReadingShortcut.publish(this@observeReaderSession, book)
                    com.yaprak.reader.feature.widget.ContinueReadingWidget.refresh(this@observeReaderSession)
                }
                saveFailed = failed
                refreshPresentation()
                appearanceState = preferences
                volumeKeyState = keys
                tapZoneState = taps
                if (taps.loadFailed) chrome = true
                screenAwakeState = awake
                orientationState = direction
                updateScreenAwake()
                bookThemeState = localTheme
                bookFontSizeState = localFont
                bookFontState = localFamily
                bookMarginsState = localMargins
                bookLineHeightState = localLineHeight
                bookSpacingState = localSpacing
                bookTextAlignmentState = localAlignment
                bookReadingModeState = localMode
                bookAppearanceResetState = localReset
                if (localReset.saving || localReset.completed) appearanceCoordinator.beginReset()
                if (bookDialogClosed) observeSurface()
                if (state is ReaderState.Ready && surface == null) {
                    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                    val created = state.session.createSurface(model.latestLocation, dark,
                        onTap = { runOnUiThread { readerAction {
                            chrome = if (pdfBookView.state.value.failed || !comicBookViewSettled()) true else !chrome
                            observeSurface()
                        } } },
                        onError = { runOnUiThread {
                            loadFailed = true
                            refreshPresentation()
                            pdfBookView.deactivate()
                            comicBookView.deactivate()
                            updateDuration()
                            updateScreenAwake()
                            navigation.clear()
                            bookPositionPercent = null
                            positionJump.dismiss()
                            if (bookSearchOpen) {
                                cancelBookSearch()
                                bookSearchOpen = false
                            }
                            clearSelectionPreview()
                            clearAnnotation()
                            observation?.cancel()
                        } })
                    created.taps?.setHandler { tap -> handleCustomTap(created, tap) }
                    created.pdfSwipe?.allowWhile { canUsePdfSurface(created) }
                    supportFragmentManager.beginTransaction().replace(contentId, created.fragment).commitNow()
                    bookPositionPercent = null
                    positionJump.dismiss()
                    surface = created
                    presentation.attach(java.util.UUID.randomUUID().toString())
                    refreshPresentation()
                    bindComicSpread(created)
                    bindSelectionNotifications(created)
                    bindDurationVisibility(created)
                    created.epubImageFilter?.apply(epubImageFilterMode)
                    renderingFailed = false
                    applyPdfNightMode()
                    updateScreenAwake()
                    navigation.clear()
                    canCustomize = created.appearance != null
                    if (canCustomize) {
                        bookTheme.bind(state.session.book)
                        bookFontSize.bind(state.session.book)
                        bookFont.bind(state.session.book)
                        bookMargins.bind(state.session.book)
                        bookLineHeight.bind(state.session.book)
                        bookSpacing.bind(state.session.book)
                        bookTextAlignment.bind(state.session.book)
                        bookReadingMode.bind(state.session.book)
                        bookAppearanceReset.bind(state.session.book)
                    }
                    contentsNavigator = created.contentsNavigator
                    bookSearch = created.bookSearch
                    bookmarkNavigator = created.bookmarkNavigator
                    passageNavigator = created.passageNavigator
                    pdfPageLinks.close()
                    closeContents()
                    clearPdfThumbnails()
                    clearPdfCrop()
                    pageNavigator = created.pageNavigator
                    bindPdfBookView()
                    restoreReaderPresentation()
                    observeSurface()
                }
                bindPdfBookView()
                val appearanceOwner = surface
                val appearanceSession = (state as? ReaderState.Ready)?.session
                val appearanceResult = appearanceCoordinator.apply(appearanceOwner?.appearance,
                    ReaderAppearanceInputs(preferences, localTheme, localFont, localFamily, localMargins,
                        localLineHeight, localSpacing, localAlignment, localMode, localReset),
                    isCurrent = {
                        appearanceOwner != null && surface === appearanceOwner && !exiting && !loadFailed &&
                            (uiState as? ReaderState.Ready)?.session === appearanceSession &&
                            (model.state.value as? ReaderState.Ready)?.session === appearanceSession &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }, beforeReflow = {
                        clearSelectionPreview()
                        clearAnnotation()
                        observation?.cancel()
                    })
                when (appearanceResult) {
                    ReaderAppearanceResult.RESET_APPLIED -> { bookAppearanceReset.ackApplied(); observeSurface() }
                    ReaderAppearanceResult.APPLIED -> observeSurface()
                    ReaderAppearanceResult.WAITING, ReaderAppearanceResult.RESET_FAILED -> if (resetPending) return@collect
                    ReaderAppearanceResult.UNCHANGED -> Unit
                }
                startIndexedChapter()
                if (!presentationRestoreFailed) restoreReaderPresentation()
            }
        }
    }
}
