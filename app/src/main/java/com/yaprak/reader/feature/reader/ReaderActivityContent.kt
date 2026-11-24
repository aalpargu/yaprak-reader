package com.yaprak.reader.feature.reader

import androidx.compose.runtime.Composable
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

/** The reader's whole Compose tree: chrome, panels and dialogs. State and actions live in [ReaderActivity]. */
@Composable
internal fun ReaderActivity.ReaderActivityContent() {
        // Callback closures must retain this exposure, not a delegated State's later value.
        val panelPresentation = presentation.state.collectAsState().value
        val panelSession = (uiState as? ReaderState.Ready)?.session
        val panelSurface = surface
        fun runPanel(kind: ReaderPanelKind, sourceReady: Boolean = false, action: () -> Unit) {
            readerPanelAction(panelPresentation, kind, panelSession, panelSurface, sourceReady, Unit, action)
        }
        fun panelCallback(kind: ReaderPanelKind, sourceReady: Boolean = false, action: () -> Unit): () -> Unit =
            { runPanel(kind, sourceReady, action) }
        fun <T> panelValue(kind: ReaderPanelKind, sourceReady: Boolean = false, action: (T) -> Unit): (T) -> Unit =
            { value -> runPanel(kind, sourceReady) { action(value) } }
        fun <T> panelTarget(kind: ReaderPanelKind, action: (T) -> Boolean): (T) -> Boolean =
            { value -> readerPanelAction(panelPresentation, kind, panelSession, panelSurface, true, false) { action(value) } }
        val readingTime by pace.state.collectAsState()
        val pdfLinksState by pdfPageLinks.state.collectAsState()
        val positionJumpState by positionJump.state.collectAsState()
        val readAloudStatus by readAloud.status.collectAsState()
        androidx.compose.runtime.LaunchedEffect(readAloudStatus) {
            if (readAloudStatus == ReadAloudStatus.FAILED) {
                android.widget.Toast.makeText(this@ReaderActivityContent,
                    "Sesli okuma başlatılamadı. Cihazda bir metin okuma (TTS) motoru kurulu olmalı.",
                    android.widget.Toast.LENGTH_LONG).show()
                readAloud.clearFailure()
            }
        }
        // Read collected states outside gated panel content, including direct footer actions.
        val panelRequests = currentPanelRequests().toMutableSet().apply {
            if (positionJumpState.open) add(ReaderPanelKind.POSITION) else remove(ReaderPanelKind.POSITION)
            if (pdfLinksState.open) add(ReaderPanelKind.PDF_LINKS) else remove(ReaderPanelKind.PDF_LINKS)
        }
        // Subscribe through the collected mirrors above, then recheck live requests before publication.
        SideEffect {
            refreshPresentation()
            if (panelRequests.isNotEmpty() || presentation.state.value.blocksReading)
                presentation.synchronizeRequests(currentPanelRequests(live = true))
        }
        SideEffect { updateImmersive(); updateBrightness(); updateOrientation(); startIndexedChapter(); updateDuration() }
        val chapterState = indexedChapterState
        val assistantAction = (uiState as? ReaderState.Ready)?.takeIf {
            chrome && isBrightnessReaderReady() && durationFailure == null &&
                chapterState.phase !in setOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.FAILED)
        }?.session?.let { session ->
            surface?.takeIf { session.book.format != BookFormat.PDF || it.bookSearch?.supported == true }
                ?.let { owner -> ReaderBookAssistantAction.create(session.book, session, owner, bookAssistantEpoch) }
        }
        val spreadOwner = surface
        val spreadBook = (uiState as? ReaderState.Ready)?.session?.book
        val spreadPreferred = spreadBook?.let(::spreadFor) ?: false
        val pdfPreferenceLease = pdfBookViewState
        val scrollSource = spreadOwner?.pdfScroll
        val pdfScrolling = scrollSource?.enabled?.collectAsState()?.value ?: false
        val pdfNavigationMode = scrollSource?.navigationMode?.collectAsState()?.value
            ?: if (pdfScrolling) com.yaprak.reader.domain.settings.PdfNavigationMode.VERTICAL_SCROLL
            else com.yaprak.reader.domain.settings.PdfNavigationMode.PAGED
        val finishState by finishBook.state.collectAsState()
        val endBook = (uiState as? ReaderState.Ready)?.session?.book
        androidx.compose.runtime.LaunchedEffect(bookPositionPercent, endBook?.id) {
            // EPUB reports the last screen's *start* (short books end near 97 %), so "near the end" counts.
            val percent = bookPositionPercent
            if (percent != null && percent >= 98 && endBook != null && !loadFailed) finishBook.reachedEnd(endBook)
        }
        ReaderChrome(uiState, chrome, saveFailed, loadFailed, ::leave, model::retryOpen,
            model::retrySave, ::leaveUnsaved, { readerAction {
                if (!pdfBookView.state.value.failed && comicBookViewSettled()) { chrome = false; observeSurface() }
            } },
            { navigate { surface?.backward() ?: false } },
            { navigate { surface?.forward() ?: false } }, pageLabel,
            if (canCustomize) ({ readerAction { settings.open() } }) else null,
            if (contentsNavigator != null) ({ readerAction { openContents() } }) else null,
            if (bookmarkNavigator != null) ({ readerAction { bookmarksOpen = true } }) else null,
            if (pageNavigator != null) ({ readerAction { pageJumpOpen = true } }) else null,
            if (canCustomize && !resetPending) ({ readerAction { observation?.cancel(); bookAppearanceOpen = true } }) else null,
            if (surface?.textSelection != null) ({ captureSelectedText() }) else null,
            if (uiState is ReaderState.Ready) ({ readerAction { observation?.cancel(); savedWordPrefill = null; translatedWordPrefill = null; savedWordsOpen = true } }) else null,
            if (uiState is ReaderState.Ready) ::openBookNotes else null,
            if ((uiState as? ReaderState.Ready)?.session?.book?.format == BookFormat.EPUB &&
                surface?.textSelection != null) {
                { readerAction { observation?.cancel(); passagePrefill = null; passagesOpen = true } }
            } else null,
            if (bookSearch != null) ({ openBookSearch() }) else null,
            if (loadFailed) null else bookPositionPercent,
            onJumpBack = if (canJumpBack && !loadFailed) ::returnBeforeJump else null,
            onTapZones = if (surface?.taps != null && isBrightnessReaderReady()) ::openTapZones else null,
            onReaderControls = if (isBrightnessReaderReady()) ::openReaderControls else null,
            onScreenAwake = if (uiState is ReaderState.Ready) ({ readerAction { observation?.cancel(); screenAwake.open() } }) else null,
            onPdfNightMode = if (surface?.pdfNightMode != null && !loadFailed) ::togglePdfNightMode else null,
            pdfNightMode = pdfNightState.enabled,
            pdfNightModeStatus = pdfNightState.status,
            onRetryPdfNightMode = ::retryPdfNightMode,
            onBookPositionJump = if (surface?.bookPositionNavigator != null && !loadFailed && !saveFailed)
                ::openBookPositionJump else null,
            onPdfFitPage = if (surface?.pdfView != null && !loadFailed && !saveFailed)
                ({ fitPdf(spreadOwner, pdfPreferenceLease, width = false) }) else null,
            onPdfFitWidth = if (surface?.pdfView != null && !loadFailed && !saveFailed)
                ({ fitPdf(spreadOwner, pdfPreferenceLease, width = true) }) else null,
            onBrightness = if (isBrightnessReaderReady()) ::openBrightness else null,
            onEpubImages = if (canSetEpubImageFilter()) ::openEpubImages else null,
            onOrientation = if (isBrightnessReaderReady()) ::openOrientation else null,
            onPdfPageLinks = surface?.takeIf { it.pdfPageLinks?.supported == true && !loadFailed && !saveFailed }
                ?.let { owner -> owner.pageNavigator?.captureStablePage()?.let { page ->
                    { openPdfPageLinks(owner, page) }
                } },
            onPdfThumbnails = if (surface?.pageThumbnails != null && pageNavigator != null)
                ::openPdfThumbnails else null,
            onPdfCrop = if (surface?.pdfCrop != null)
                ({ openPdfCrop(spreadOwner, pdfPreferenceLease) }) else null,
            pdfCropApplyFailed = pdfCropApplyFailed,
            pdfBookViewState = pdfBookViewState,
            onRetryPdfBookView = { retryPdfBookView(pdfPreferenceLease) },
            onPdfAnnotations = if ((uiState as? ReaderState.Ready)?.session?.book?.format == BookFormat.PDF && !loadFailed)
                ::openPdfAnnotations else null,
            onPdfOcr = surface?.takeIf { it.pdfOcr != null && !loadFailed }?.let { owner ->
                owner.pageNavigator?.captureStablePage()?.let { page -> { openPdfOcr(owner, page) } }
            },
            pdfDoublePagePreferred = spreadPreferred,
            pdfScrolling = pdfScrolling,
            pdfNavigationMode = pdfNavigationMode,
            onPdfNavigation = scrollSource?.takeIf { it.navigationMode != null && !loadFailed && !saveFailed }?.let { source ->
                { choice -> changePdfNavigation(spreadOwner, source, pdfNavigationMode, choice, pdfPreferenceLease) }
            },
            onPdfScroll = scrollSource?.takeIf { !loadFailed && !saveFailed }?.let { source ->
                { togglePdfScroll(spreadOwner, source, pdfScrolling, pdfPreferenceLease) }
            },
            onPdfSpread = if (spreadOwner?.pdfSpread != null && spreadBook != null &&
                !loadFailed && !saveFailed) ({ togglePdfSpread(spreadBook, spreadOwner, spreadPreferred, pdfPreferenceLease) }) else null,
            comicDoublePagePreferred = comicDoublePagePreferred,
            onComicSpread = surface?.takeIf { it.comicSpread != null && !loadFailed && !saveFailed }?.let { owner ->
                val expected = comicDoublePagePreferred
                val lease = comicBookViewState
                ({ toggleComicSpread(owner, expected, lease) })
            },
            comicBookViewState = comicBookViewState,
            onRetryComicBookView = surface?.takeIf { it.comicSpread != null && !loadFailed && !saveFailed }?.let { owner ->
                val lease = comicBookViewState
                ({ retryComicBookView(owner, lease) })
            },
            orientationApplyFailed = orientationApplyFailed,
            onRetryOrientation = ::retryOrientation,
            highlightStatus = highlightStatus,
            onRetryHighlights = if (surface?.epubHighlights != null) ::retryHighlights else null,
            highlightRetryBusy = highlightRetryBusy,
            durationFailure = durationFailure,
            onRetryDuration = { model.retryDuration(); updateDuration() },
            indexedChapterFailure = chapterState.failure,
            indexedChapterVerifying = chapterState.phase == IndexedChapterPhase.VERIFYING,
            onRetryIndexedChapter = if (chapterState.phase == IndexedChapterPhase.FAILED)
                ({ retryIndexedChapter(chapterState) }) else null,
            onBookAssistant = assistantAction?.let { action -> { openBookAssistant(action) } },
            readingTime = if (loadFailed || saveFailed || renderingFailed) null else readingTime,
            presentationController = presentation,
            currentRequests = { currentPanelRequests(live = true) },
            onRetryPresentationRestore = ::restoreReaderPresentation,
            seek = readerSeek(),
            onQuickBookmark = if (bookmarkNavigator != null && !loadFailed && !saveFailed) ::quickBookmark else null,
            chapterTitle = currentChapterTitle(),
            onReadAloud = if (surface?.readAloud != null && !loadFailed && !saveFailed) ::toggleReadAloud else null,
            onPdfHighlighter = if (surface?.pdfInk != null && !loadFailed && !saveFailed && !renderingFailed) ({ startPdfInk() }) else null,
            readAloudActive = readAloudStatus == ReadAloudStatus.STARTING || readAloudStatus == ReadAloudStatus.SPEAKING,
            finishOffer = finishState.takeIf { endBook != null && it.offeredFor == endBook.id },
            onMarkFinished = { endBook?.let(finishBook::markFinished) }, onDismissFinish = finishBook::dismiss,
            quickAppearance = if (canCustomize && !resetPending && !appearanceState.loading &&
                !bookThemeState.loading && !bookFontSizeState.loading && !bookThemeState.loadFailed &&
                !bookFontSizeState.loadFailed) ReaderQuickAppearance(
                fontSizePercent = bookFontSizeState.saved ?: appearanceState.saved.fontSizePercent,
                onFontSize = { value -> bookFontSize.quickSave((value / 5) * 5) },
                theme = (bookThemeState.saved ?: appearanceState.saved.theme).let {
                    if (it == com.yaprak.reader.domain.settings.ReadingTheme.SYSTEM)
                        (if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                            android.content.res.Configuration.UI_MODE_NIGHT_YES) com.yaprak.reader.domain.settings.ReadingTheme.DARK
                        else com.yaprak.reader.domain.settings.ReadingTheme.LIGHT) else it
                },
                onTheme = { bookTheme.quickSave(it) },
                busy = bookThemeState.saving || bookFontSizeState.saving,
            ) else null)
        ReaderPanelHost(panelPresentation, ReaderPanelKind.ORIENTATION) { ReaderOrientationDialog(orientationState, { choice -> runPanel(ReaderPanelKind.ORIENTATION) { orientationAction { orientation.edit(choice) } } },
            { runPanel(ReaderPanelKind.ORIENTATION, true) { orientationAction { orientation.save() } } },
            { runPanel(ReaderPanelKind.ORIENTATION) { orientation.dismiss(); if (!orientation.state.value.open && !annotationActive) observeSurface() } },
            { runPanel(ReaderPanelKind.ORIENTATION) { orientationAction { orientation.reload() } } }) }
        ReaderPanelHost(panelPresentation, ReaderPanelKind.BRIGHTNESS) { ReaderBrightnessDialog(brightnessOpen, brightnessDraft, panelValue(ReaderPanelKind.BRIGHTNESS, action = ::editBrightness),
            panelValue(ReaderPanelKind.BRIGHTNESS, true, ::applyBrightness), panelCallback(ReaderPanelKind.BRIGHTNESS, action = ::dismissBrightness), brightnessGestureDraft, panelValue(ReaderPanelKind.BRIGHTNESS, action = ::editBrightnessGesture), brightnessRequestId) }
        brightnessGesturePreview?.let { ReaderBrightnessGestureIndicator(it) }
        ReaderPanelHost(panelPresentation, ReaderPanelKind.IMAGES) { EpubImageFilterDialog(epubImagesOpen, epubImageFilterDraft, panelValue(ReaderPanelKind.IMAGES, action = ::editEpubImageFilter),
            panelValue(ReaderPanelKind.IMAGES, true, ::applyEpubImageFilter), panelCallback(ReaderPanelKind.IMAGES, action = ::dismissEpubImages), failed = epubImagesFailed) }
        ReaderPanelHost(panelPresentation, ReaderPanelKind.POSITION) { BookPositionJumpDialog(positionJumpState, panelValue(ReaderPanelKind.POSITION, action = positionJump::edit),
            panelCallback(ReaderPanelKind.POSITION, true, ::submitBookPositionJump), panelCallback(ReaderPanelKind.POSITION, action = ::dismissBookPositionJump)) }
        ReaderPanelHost(panelPresentation, ReaderPanelKind.TAP_ZONES) { TapZoneDialog(tapZoneState, panelValue(ReaderPanelKind.TAP_ZONES, action = tapZones::edit), panelCallback(ReaderPanelKind.TAP_ZONES, true, tapZones::save),
            { runPanel(ReaderPanelKind.TAP_ZONES) { tapZones.dismiss(); if (!tapZones.state.value.open) observeSurface() } }, panelCallback(ReaderPanelKind.TAP_ZONES, action = tapZones::reload)) }
        ReaderPanelHost(panelPresentation, ReaderPanelKind.VOLUME_KEYS) { VolumeKeyDialog(volumeKeyState, panelValue(ReaderPanelKind.VOLUME_KEYS, action = volumeKeys::edit), panelCallback(ReaderPanelKind.VOLUME_KEYS, true, volumeKeys::save),
            { runPanel(ReaderPanelKind.VOLUME_KEYS) { volumeKeys.dismiss(); if (!tapZones.state.value.open && !volumeKeys.state.value.open) observeSurface() } }, panelCallback(ReaderPanelKind.VOLUME_KEYS, action = volumeKeys::reload)) }
        ReaderPanelHost(panelPresentation, ReaderPanelKind.SCREEN_AWAKE) { ScreenAwakeDialog(screenAwakeState, panelValue(ReaderPanelKind.SCREEN_AWAKE, action = screenAwake::edit), panelCallback(ReaderPanelKind.SCREEN_AWAKE, true, screenAwake::save),
            { runPanel(ReaderPanelKind.SCREEN_AWAKE) { screenAwake.dismiss(); observeSurface() } }, panelCallback(ReaderPanelKind.SCREEN_AWAKE, action = screenAwake::reload)) }
        val transferCandidate = (selectionPreview as? SelectedTextPreview.Ready)?.text?.let(::selectedWordCandidate)
        val noteCandidate = (selectionPreview as? SelectedTextPreview.Ready)?.text?.let(::selectedNoteCandidate)
        val passageCandidate = (selectionPreview as? SelectedTextPreview.Ready)?.takeIf {
            (uiState as? ReaderState.Ready)?.session?.book?.format == BookFormat.EPUB
        }?.let {
            eligiblePassageAnchor(it.text, it.sourceAnchor)
        }
        val selectedPreview = selectionPreview
        val selectedSearchOwner = surface
        val selectedSearchSource = bookSearch
        val pdfAnnotationCandidate = (selectedPreview as? SelectedTextPreview.Ready)?.let(::eligiblePdfAnnotationAnchor)
        val assistantCandidate = (uiState as? ReaderState.Ready)?.session?.book?.let { book ->
            (selectedPreview as? SelectedTextPreview.Ready)?.let { selectedAssistantRequest(book, it) }
        }
        fun selectionAction(action: () -> Unit) {
            if (selectedPreview != null && selectionPreview == selectedPreview && isSelectionCurrent()) {
                action(); updateDuration()
            }
        }
        ReaderPanelHost(panelPresentation, ReaderPanelKind.SELECTION) { SelectedTextDialog(selectedPreview, {
            if (selectedPreview != null && selectionPreview === selectedPreview) { clearSelectionPreview(); observeSurface() }
        },
            { selectionAction(::copySelectedText) },
            if (transferCandidate != null) ({ selectionAction(::transferSelectedWord) }) else null,
            if (noteCandidate != null) ({ selectionAction(::transferSelectedNote) }) else null,
            if (passageCandidate != null) ({ selectionAction(::transferSelectedPassage) }) else null,
            onAnnotate = when {
                passageCandidate != null -> ({ selectionAction(::annotateSelectedText) })
                pdfAnnotationCandidate != null && (uiState as? ReaderState.Ready)?.session?.book?.format == BookFormat.PDF -> ({ selectionAction(::annotatePdfSelection) })
                else -> null
            },
            showAnnotationHint = (uiState as? ReaderState.Ready)?.session?.book != null,
            pdfAnnotationHint = (uiState as? ReaderState.Ready)?.session?.book?.format == BookFormat.PDF,
            toolsState = selectionTools,
            onBookSearch = if (selectedPreview is SelectedTextPreview.Ready && selectedSearchOwner != null &&
                selectedSearchSource?.supported == true) ({
                selectionAction { searchSelectedText(selectedPreview, selectedSearchOwner, selectedSearchSource) }
            }) else null,
            onWebSearch = if (selectedPreview is SelectedTextPreview.Ready) ({ selectionAction { launchSelectionTool(null) } }) else null,
            onTextTool = if (selectedPreview is SelectedTextPreview.Ready) ({ tool -> selectionAction { launchSelectionTool(tool) } }) else null,
            onRetryTools = if (selectedPreview is SelectedTextPreview.Ready) ({ selectionAction { discoverSelectionTools(selectedPreview) } }) else null,
            dictionaryState = dictionaryState,
            onDictionary = if (selectedPreview is SelectedTextPreview.Ready) ({ selectionAction(::lookupSelectionDictionary) }) else null,
            onRetryDictionary = if (selectedPreview is SelectedTextPreview.Ready) ({ expected -> selectionAction { retrySelectionDictionary(expected) } }) else null,
            assistantContent = if (assistantCandidate != null && selectedPreview is SelectedTextPreview.Ready) ({
                com.yaprak.reader.feature.assistant.SelectionAssistantPanel(selectionAssistantState,
                    isCurrent = { selectionPreview == selectedPreview && isSelectionCurrent() &&
                        (application as YaprakApplication).container.startup.state.value == com.yaprak.reader.core.StartupState.Ready },
                    onOpen = { selectionAction(::openSelectionAssistant) },
                    onPrepare = { expected -> selectionAction { selectionAssistant.prepare(expected) { isSelectionAssistantCurrent(expected.request) } } },
                    onSend = { expected -> selectionAction { selectionAssistant.send(expected) { isSelectionAssistantCurrent(expected.request) } } },
                    onClose = { expected -> selectionAction { selectionAssistant.close(expected) } },
                    onQuestion = { expected, value -> selectionAction { selectionAssistant.editQuestion(expected, value) } },
                    onEndpoint = { expected, value -> selectionAction { selectionAssistant.editEndpoint(expected, value) } },
                    onToken = { expected, value -> selectionAction { selectionAssistant.editToken(expected, value) } })
            }) else null,
            translationContent = if (selectedPreview is SelectedTextPreview.Ready) ({
                com.yaprak.reader.feature.translation.TranslationPanel(translationState,
                    isCurrent = { selectionPreview == selectedPreview && isSelectionCurrent() },
                    onOpen = { selectionAction(::openSelectionTranslation) },
                    onCheck = { expected -> selectionAction { if (isTranslationCurrent(expected)) translation.checkModels(expected) } },
                    onDownload = { expected -> selectionAction { if (isTranslationCurrent(expected)) translation.download(expected) } },
                    onTranslate = { expected -> selectionAction { if (isTranslationCurrent(expected)) translation.translate(expected) } },
                    onDelete = { expected -> selectionAction { if (isTranslationCurrent(expected)) translation.deleteModel(expected) } },
                    onCopy = { expected -> selectionAction { if (isTranslationCurrent(expected))
                        translation.copy(expected) { text -> copyPlainSelectedText(this@ReaderActivityContent, text, "Yaprak Türkçe çeviri") } } },
                    onClose = { expected -> selectionAction { if (isTranslationCurrent(expected)) {
                        translation.close(); translationState = null
                    } } },
                    onTransferWord = { expected -> selectionAction { transferTranslatedWord(expected) } })
            }) else null) }
        // A tool picked from the native selection toolbar runs once its captured text is ready.
        androidx.compose.runtime.LaunchedEffect(selectedPreview?.sessionId, selectedPreview is SelectedTextPreview.Ready) {
            val quick = pendingQuickSelection ?: return@LaunchedEffect
            when (selectedPreview) {
                is SelectedTextPreview.Error -> pendingQuickSelection = null
                is SelectedTextPreview.Ready -> {
                    pendingQuickSelection = null
                    // The text is captured; drop the native toolbar so it never floats over the result.
                    nativeActionMode?.finish()
                    when (quick) {
                        ReaderActivity.QuickSelectionAction.HIGHLIGHT -> when {
                            passageCandidate != null -> selectionAction(::annotateSelectedText)
                            pdfAnnotationCandidate != null -> selectionAction(::annotatePdfSelection)
                            else -> Unit
                        }
                        ReaderActivity.QuickSelectionAction.DICTIONARY -> selectionAction(::lookupSelectionDictionary)
                        ReaderActivity.QuickSelectionAction.TRANSLATE -> selectionAction(::openSelectionTranslation)
                        ReaderActivity.QuickSelectionAction.OPEN -> Unit
                    }
                }
                else -> Unit
            }
        }
        annotationRequest?.let { request ->
            ReaderPanelHost(panelPresentation, ReaderPanelKind.ANNOTATION) { EpubAnnotationRoute(request, (application as YaprakApplication).container.epubPassages,
                canMutate = { canMutateAnnotation(request) },
                onDismiss = { if (annotationRequest === request) { clearAnnotation(); observeSurface() } }) }
        }
        pdfAnnotationRequest?.let { request ->
            ReaderPanelHost(panelPresentation, ReaderPanelKind.PDF_ANNOTATION) { com.yaprak.reader.feature.pdfannotations.PdfAnnotationRoute(request,
                (application as YaprakApplication).container.pdfAnnotations,
                { canMutatePdfAnnotations(request.bookId, request.hash) && pdfAnnotationRequest === request },
                { if (pdfAnnotationRequest === request) { clearPdfAnnotations(); observeSurface() } }) }
        }
        pdfAnnotationsRequest?.let { request ->
            ReaderPanelHost(panelPresentation, ReaderPanelKind.PDF_ANNOTATIONS) { com.yaprak.reader.feature.pdfannotations.PdfAnnotationsRoute(request,
                (application as YaprakApplication).container.pdfAnnotations,
                { canMutatePdfAnnotations(request.bookId, request.hash) && pdfAnnotationsRequest === request },
                { page -> openPdfAnnotationPage(request, page) },
                { if (pdfAnnotationsRequest === request) { clearPdfAnnotations(); observeSurface() } },
                { text -> if (pdfAnnotationsRequest === request && canMutatePdfAnnotations(request.bookId, request.hash)) copyPlainSelectedText(this@ReaderActivityContent, text, "Yaprak PDF kaydı") }) }
        }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.SETTINGS) { EpubSettingsDialog(appearanceState, panelValue(ReaderPanelKind.SETTINGS, action = settings::edit), panelCallback(ReaderPanelKind.SETTINGS, true, settings::save),
            panelCallback(ReaderPanelKind.SETTINGS, action = settings::dismiss), panelCallback(ReaderPanelKind.SETTINGS, action = settings::reset), panelCallback(ReaderPanelKind.SETTINGS, action = settings::reload)) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOK_APPEARANCE) { BookAppearanceDialog(bookAppearanceOpen,
            { runPanel(ReaderPanelKind.BOOK_APPEARANCE, true) { bookTheme.open() } },
            { runPanel(ReaderPanelKind.BOOK_APPEARANCE, true) { bookFontSize.open() } },
            { runPanel(ReaderPanelKind.BOOK_APPEARANCE, true) { bookFont.open() } },
            { runPanel(ReaderPanelKind.BOOK_APPEARANCE, true) { bookMargins.open() } },
            { runPanel(ReaderPanelKind.BOOK_APPEARANCE, true) { bookLineHeight.open() } },
            { runPanel(ReaderPanelKind.BOOK_APPEARANCE, true) { bookSpacing.open() } },
            { runPanel(ReaderPanelKind.BOOK_APPEARANCE, true) { appearanceCoordinator.beginReset(); bookAppearanceReset.open() } },
            { runPanel(ReaderPanelKind.BOOK_APPEARANCE) { bookAppearanceOpen = false; observeSurface() } },
            onTextAlignment = { runPanel(ReaderPanelKind.BOOK_APPEARANCE, true) { bookTextAlignment.open() } },
            onReadingMode = { runPanel(ReaderPanelKind.BOOK_APPEARANCE, true) { bookReadingMode.open() } },
            busy = resetPending,
            canOpen = panelPresentation.phase == ReaderPhase.Ready,
            focusTarget = { key -> androidx.compose.ui.Modifier.readerReturnFocusTarget(presentation, "reader-focus-$key") }) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOK_RESET) { BookAppearanceResetDialog(bookAppearanceResetState,
            panelCallback(ReaderPanelKind.BOOK_RESET, true, bookAppearanceReset::confirm),
            { runPanel(ReaderPanelKind.BOOK_RESET) {
                bookAppearanceReset.dismiss()
                if (!bookAppearanceReset.state.value.open) {
                    appearanceCoordinator.cancelReset()
                    observeSurface()
                }
            } }) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.RESET_REFRESH) { BookAppearanceResetRefreshDialog(resetRefreshFailed,
            appearanceState.loading || bookThemeState.loading || bookFontSizeState.loading || bookFontState.loading ||
                bookMarginsState.loading || bookLineHeightState.loading || bookSpacingState.loading || bookTextAlignmentState.loading || bookReadingModeState.loading,
            { runPanel(ReaderPanelKind.RESET_REFRESH) {
                settings.reload()
                bookTheme.reload()
                bookFontSize.reload()
                bookFont.reload()
                bookMargins.reload()
                bookLineHeight.reload()
                bookSpacing.reload()
                bookTextAlignment.reload()
                bookReadingMode.reload()
            } }, panelCallback(ReaderPanelKind.RESET_REFRESH, action = ::leave)) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOK_THEME) { BookThemeDialog(bookThemeState, panelValue(ReaderPanelKind.BOOK_THEME, action = bookTheme::edit), panelCallback(ReaderPanelKind.BOOK_THEME, true, bookTheme::save),
            { runPanel(ReaderPanelKind.BOOK_THEME) { bookTheme.dismiss(); observeSurface() } }, panelCallback(ReaderPanelKind.BOOK_THEME, action = bookTheme::reload)) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOK_FONT_SIZE) { BookFontSizeDialog(bookFontSizeState, panelValue(ReaderPanelKind.BOOK_FONT_SIZE, action = bookFontSize::edit), panelCallback(ReaderPanelKind.BOOK_FONT_SIZE, true, bookFontSize::save),
            { runPanel(ReaderPanelKind.BOOK_FONT_SIZE) { bookFontSize.dismiss(); observeSurface() } }, panelCallback(ReaderPanelKind.BOOK_FONT_SIZE, action = bookFontSize::reload),
            appearanceState.saved.fontSizePercent) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOK_FONT) { BookFontDialog(bookFontState, panelValue(ReaderPanelKind.BOOK_FONT, action = bookFont::edit), panelCallback(ReaderPanelKind.BOOK_FONT, true, bookFont::save),
            { runPanel(ReaderPanelKind.BOOK_FONT) { bookFont.dismiss(); observeSurface() } }, panelCallback(ReaderPanelKind.BOOK_FONT, action = bookFont::reload)) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOK_MARGINS) { BookMarginsDialog(bookMarginsState, panelValue(ReaderPanelKind.BOOK_MARGINS, action = bookMargins::edit), panelCallback(ReaderPanelKind.BOOK_MARGINS, true, bookMargins::save),
            { runPanel(ReaderPanelKind.BOOK_MARGINS) { bookMargins.dismiss(); observeSurface() } }, panelCallback(ReaderPanelKind.BOOK_MARGINS, action = bookMargins::reload),
            appearanceState.saved.marginsPercent) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOK_LINE_HEIGHT) { BookLineHeightDialog(bookLineHeightState, panelValue(ReaderPanelKind.BOOK_LINE_HEIGHT, action = bookLineHeight::edit), panelCallback(ReaderPanelKind.BOOK_LINE_HEIGHT, true, bookLineHeight::save),
            { runPanel(ReaderPanelKind.BOOK_LINE_HEIGHT) { bookLineHeight.dismiss(); observeSurface() } }, panelCallback(ReaderPanelKind.BOOK_LINE_HEIGHT, action = bookLineHeight::reload),
            appearanceState.saved.lineHeightPercent) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOK_SPACING) { BookSpacingDialog(bookSpacingState, panelValue(ReaderPanelKind.BOOK_SPACING, action = bookSpacing::edit), panelCallback(ReaderPanelKind.BOOK_SPACING, true, bookSpacing::save),
            { runPanel(ReaderPanelKind.BOOK_SPACING) { bookSpacing.dismiss(); observeSurface() } }, panelCallback(ReaderPanelKind.BOOK_SPACING, action = bookSpacing::reload), appearanceState.saved) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOK_ALIGNMENT) { BookTextAlignmentDialog(bookTextAlignmentState, panelValue(ReaderPanelKind.BOOK_ALIGNMENT, action = bookTextAlignment::edit),
            panelCallback(ReaderPanelKind.BOOK_ALIGNMENT, true, bookTextAlignment::save), { runPanel(ReaderPanelKind.BOOK_ALIGNMENT) { bookTextAlignment.dismiss(); observeSurface() } }, panelCallback(ReaderPanelKind.BOOK_ALIGNMENT, action = bookTextAlignment::reload)) }
        if (canCustomize) ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOK_READING_MODE) { BookReadingModeDialog(bookReadingModeState, panelValue(ReaderPanelKind.BOOK_READING_MODE, action = bookReadingMode::edit),
            panelCallback(ReaderPanelKind.BOOK_READING_MODE, true, bookReadingMode::save), { runPanel(ReaderPanelKind.BOOK_READING_MODE) { bookReadingMode.dismiss(); observeSurface() } }, panelCallback(ReaderPanelKind.BOOK_READING_MODE, action = bookReadingMode::reload)) }
        val contentsState by contentsLoader.state.collectAsState()
        contentsState.request?.takeIf { contentsOpen }?.let { request ->
            if (contentsLoader.isCurrent(request)) ReaderPanelHost(panelPresentation, ReaderPanelKind.CONTENTS) { LoadingContentsDialog(contentsState,
                { expected, id -> readerPanelAction(panelPresentation, ReaderPanelKind.CONTENTS,
                    panelSession, panelSurface, true, false) {
                        val accepted = selectContentsEntry(expected, id)
                        if (accepted) closeContents(expected.request)
                        accepted
                    } },
                panelValue(ReaderPanelKind.CONTENTS, action = contentsLoader::retry),
                panelValue(ReaderPanelKind.CONTENTS, action = ::closeContents),
                currentId = androidx.compose.runtime.remember(request) { contentsNavigator?.currentEntryId() }) }
            else SideEffect { closeContents(request) }
        }
        if (bookSearchOpen) ReaderPanelHost(panelPresentation, ReaderPanelKind.SEARCH) { BookSearchDialog(bookSearchState, panelValue(ReaderPanelKind.SEARCH, action = ::changeBookSearchQuery),
            panelCallback(ReaderPanelKind.SEARCH, true, ::runBookSearch), panelCallback(ReaderPanelKind.SEARCH, action = ::cancelBookSearch), panelValue(ReaderPanelKind.SEARCH, true, ::selectBookSearchHit), panelCallback(ReaderPanelKind.SEARCH, action = ::dismissBookSearch),
            kind = bookSearch?.kind ?: com.yaprak.reader.reader.BookSearchKind.EPUB) }
        val ready = uiState as? ReaderState.Ready
        val readySurface = surface
        pageNavigator?.takeIf { pageJumpOpen }?.let { navigator ->
            ReaderPanelHost(panelPresentation, ReaderPanelKind.PAGE_JUMP) { PageJumpDialog(navigator.pageCount, navigator.currentPage, panelTarget(ReaderPanelKind.PAGE_JUMP) { page ->
                    val accepted = selectPage(page)
                    if (accepted) pageJumpOpen = false
                    accepted
                },
                { runPanel(ReaderPanelKind.PAGE_JUMP) { pageJumpOpen = false } }) }
        }
        pdfLinksState.request?.let { request ->
            if (pdfPageLinks.isCurrent(request)) ReaderPanelHost(panelPresentation, ReaderPanelKind.PDF_LINKS) { PdfPageLinksDialog(pdfLinksState,
                pdfPageLinks::select, pdfPageLinks::retry, { pdfPageLinks.close(request) }) }
            else SideEffect { pdfPageLinks.close(request) }
        }
        pdfThumbnailsRequest?.let { request ->
            if (isPdfThumbnailsCurrent(request)) ReaderPanelHost(panelPresentation, ReaderPanelKind.PDF_THUMBNAILS) { PdfThumbnailsDialog(
                request.id, request.pageCount,
                request.navigator.currentPage?.takeIf { it in 0 until request.pageCount },
                render = { page -> renderPdfThumbnail(request, page) },
                isCurrent = { isPdfThumbnailsCurrent(request) },
                onGo = { page -> selectPdfThumbnail(request, page) },
                onDismiss = { dismissPdfThumbnails(request) },
                initialPage = request.currentPage,
            ) } else SideEffect { clearPdfThumbnails(request) }
        }
        pdfCropRequest?.let { request ->
            if (isPdfCropCurrent(request)) ReaderPanelHost(panelPresentation, ReaderPanelKind.PDF_CROP) { PdfCropDialog(
                request.id, request.saved, request.previewPage,
                renderPreview = if (request.previewSource != null && request.previewPage != null)
                    ({ renderPdfCropPreview(request) }) else null,
                isCurrent = { isPdfCropCurrent(request) },
                onApply = { crop -> applyPdfCrop(request, crop) },
                onDismiss = { dismissPdfCrop(request) },
                preferenceState = pdfBookViewState,
            ) } else SideEffect { clearPdfCrop(request) }
        }
        if (bookmarksOpen && ready != null) bookmarkNavigator?.let { navigator ->
            ReaderPanelHost(panelPresentation, ReaderPanelKind.BOOKMARKS) { BookmarksRoute(ready.session.book, (application as YaprakApplication).container.bookmarks,
                navigator, ::selectBookmark, { bookmarksOpen = false; clearPanelDraft(ReaderPanelKind.BOOKMARKS) },
                restoration = restoredPanelDrafts[ReaderPanelKind.BOOKMARKS],
                onRestorationChange = { if (isCurrentPanelRequest(ReaderPanelKind.BOOKMARKS, ready.session, panelPresentation)) rememberPanelDraft(ReaderPanelKind.BOOKMARKS, it) },
                canMutate = { canUsePanelRequest(ReaderPanelKind.BOOKMARKS, ready.session, readySurface, panelPresentation) },
                canInteract = { isCurrentPanelRequest(ReaderPanelKind.BOOKMARKS, ready.session, panelPresentation) },
                presentationEpoch = panelPresentation.epoch) }
        }
        if (savedWordsOpen && ready != null) {
            val incomingTranslation = translatedWordPrefill
            if (incomingTranslation != null && !incomingTranslation.matches(ready.session.book)) SideEffect {
                savedWordsOpen = false; translatedWordPrefill = null
            } else {
                ReaderPanelHost(panelPresentation, ReaderPanelKind.WORDS) { SavedWordsRoute(ready.session.book, (application as YaprakApplication).container.savedWords,
                    { savedWordsOpen = false; savedWordPrefill = null; translatedWordPrefill = null; clearPanelDraft(ReaderPanelKind.WORDS); observeSurface() },
                    prefillEnglish = savedWordPrefill,
                    prefillTranslation = incomingTranslation?.draft,
                    onCopy = { text ->
                        check(savedWordsOpen && !orientation.state.value.open && !annotationActive && !exiting && !loadFailed && !saveFailed &&
                            uiState is ReaderState.Ready && surface != null &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        copyPlainSelectedText(this@ReaderActivityContent, text, label = "Yaprak kelime kaydı")
                    }, restoration = restoredPanelDrafts[ReaderPanelKind.WORDS],
                    onRestorationChange = { if (isCurrentPanelRequest(ReaderPanelKind.WORDS, ready.session, panelPresentation)) rememberPanelDraft(ReaderPanelKind.WORDS, it) },
                    canMutate = { canUsePanelRequest(ReaderPanelKind.WORDS, ready.session, readySurface, panelPresentation) },
                canInteract = { isCurrentPanelRequest(ReaderPanelKind.WORDS, ready.session, panelPresentation) },
                presentationEpoch = panelPresentation.epoch) }
            }
        }
        if (bookNotesOpen && ready != null) {
            ReaderPanelHost(panelPresentation, ReaderPanelKind.NOTES) { BookNotesRoute(ready.session.book, (application as YaprakApplication).container.bookNotes,
                { bookNotesOpen = false; bookNotePrefill = null; clearPanelDraft(ReaderPanelKind.NOTES); observeSurface() },
                prefillText = bookNotePrefill,
                capturePdfPage = if (ready.session.book.format == BookFormat.PDF && pageNavigator != null) ::capturePdfNotePage else null,
                onGoToPdfPage = if (ready.session.book.format == BookFormat.PDF && pageNavigator != null) ::selectPage else null,
                onCopy = { text ->
                    check(bookNotesOpen && !orientation.state.value.open && !annotationActive && !exiting && !loadFailed && !saveFailed &&
                        uiState is ReaderState.Ready && surface != null &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    copyPlainSelectedText(this@ReaderActivityContent, text, label = "Yaprak notu")
                }, restoration = restoredPanelDrafts[ReaderPanelKind.NOTES],
                onRestorationChange = { if (isCurrentPanelRequest(ReaderPanelKind.NOTES, ready.session, panelPresentation)) rememberPanelDraft(ReaderPanelKind.NOTES, it) },
                canMutate = { canUsePanelRequest(ReaderPanelKind.NOTES, ready.session, readySurface, panelPresentation) },
                canInteract = { isCurrentPanelRequest(ReaderPanelKind.NOTES, ready.session, panelPresentation) },
                presentationEpoch = panelPresentation.epoch) }
        }
        if (passagesOpen && ready != null) {
            ReaderPanelHost(panelPresentation, ReaderPanelKind.PASSAGES) { EpubPassagesRoute(ready.session.book, (application as YaprakApplication).container.epubPassages,
                { passagesOpen = false; passagePrefill = null; clearPanelDraft(ReaderPanelKind.PASSAGES); observeSurface() },
                prefillText = passagePrefill?.text, prefillAnchor = passagePrefill?.anchor,
                onOpenChapter = if (passageNavigator != null) ::selectPassageChapter else null,
                onCopy = { text ->
                    check(!orientation.state.value.open && !annotationActive && !exiting && !loadFailed && uiState is ReaderState.Ready &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    copyPlainSelectedText(this@ReaderActivityContent, text)
                }, canMutate = { canUsePanelRequest(ReaderPanelKind.PASSAGES, ready.session, readySurface, panelPresentation) },
                restoration = restoredPanelDrafts[ReaderPanelKind.PASSAGES],
                onRestorationChange = { if (isCurrentPanelRequest(ReaderPanelKind.PASSAGES, ready.session, panelPresentation)) rememberPanelDraft(ReaderPanelKind.PASSAGES, it) },
                canInteract = { isCurrentPanelRequest(ReaderPanelKind.PASSAGES, ready.session, panelPresentation) },
                presentationEpoch = panelPresentation.epoch) }
        }
}
