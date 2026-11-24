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

/** Recreates only the rendering surface; the retained VM owns the publication and writes.
 * On process recreation we reopen the verified file and durable locator, rather than asking
 * Android to instantiate Readium's constructor-injected fragments without a publication.
 */
class ReaderActivity : FragmentActivity() {
    internal val pdfBookView: PdfBookViewPreferencesViewModel by viewModels {
        viewModelFactory { initializer {
            PdfBookViewPreferencesViewModel((application as YaprakApplication).container.bookPdfViewPreferences)
        } }
    }
    internal var pdfBookViewState by mutableStateOf(PdfBookViewPreferencesState())
    internal val pdfBookViewBinding = ReaderPdfBookViewPreferencesBinding()
    internal var pdfBookViewSurface: ReaderSurface? = null
    internal var pdfNavigationResumeObserver: androidx.lifecycle.LifecycleEventObserver? = null
    internal val comicBookView: ComicBookViewPreferencesViewModel by viewModels {
        viewModelFactory { initializer {
            ComicBookViewPreferencesViewModel((application as YaprakApplication).container.bookComicViewPreferences)
        } }
    }
    internal var comicBookViewState by mutableStateOf(ComicBookViewPreferencesState())
    internal val comicBookViewBinding = ReaderComicBookViewPreferencesBinding()
    internal var comicBookViewSurface: ReaderSurface? = null
    internal var restoredPdfNightChoice: Boolean? = null
    internal val pdfNightMode: PdfNightModeViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer {
            val restored = restoredPdfNightChoice
            val handle = if (restored == null) SavedStateHandle() else
                SavedStateHandle(mapOf(PdfNightModeViewModel.SAVED_STATE_KEY to restored))
            PdfNightModeViewModel(container.pdfNightSettings, handle)
        } }
    }
    internal val volumeKeys: VolumeKeyViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { VolumeKeyViewModel(container.volumeKeys) } }
    }
    internal val tapZones: TapZoneViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { TapZoneViewModel(container.tapZones) } }
    }
    internal var tapZoneState by mutableStateOf(TapZoneState())
    internal val finishBook: FinishBookViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { FinishBookViewModel(container.library) } }
    }
    internal val volumeKeyController = ReaderVolumeKeyController()
    internal var originalKeyWindowCallback: Window.Callback? = null
    internal var installedKeyWindowCallback: Window.Callback? = null
    internal var volumeKeyState by mutableStateOf(VolumeKeyState())
    internal var readerImeVisible = false
    internal var readerTouchActive = false
    internal var nativeActionMode: ActionMode? = null
    internal val screenAwake: ScreenAwakeViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { ScreenAwakeViewModel(container.screenAwake) } }
    }
    internal val orientation: ReaderOrientationViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { ReaderOrientationViewModel(container.readerOrientation) } }
    }
    internal val settings: EpubSettingsViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { EpubSettingsViewModel(container.settings) } }
    }
    internal val bookTheme: BookThemeViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookThemeViewModel(container.bookThemes) } }
    }
    internal val bookFontSize: BookFontSizeViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookFontSizeViewModel(container.bookFontSizes) } }
    }
    internal val bookFont: BookFontViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookFontViewModel(container.bookFonts) } }
    }
    internal val bookMargins: BookMarginsViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookMarginsViewModel(container.bookMargins) } }
    }
    internal val bookLineHeight: BookLineHeightViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookLineHeightViewModel(container.bookLineHeights) } }
    }
    internal val bookAppearanceReset: BookAppearanceResetViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookAppearanceResetViewModel(container.bookAppearanceReset) } }
    }
    internal val bookReadingMode: BookReadingModeViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookReadingModeViewModel(container.bookReadingModes) } }
    }
    internal val bookTextAlignment: BookTextAlignmentViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookTextAlignmentViewModel(container.bookTextAlignments) } }
    }
    internal val bookSpacing: BookSpacingViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookSpacingViewModel(container.bookSpacings) } }
    }
    internal val dictionary: DictionaryLookupViewModel by viewModels {
        viewModelFactory { initializer {
            DictionaryLookupViewModel((application as YaprakApplication).container.englishDictionary)
        } }
    }
    internal var dictionaryState by mutableStateOf<DictionaryLookupState?>(null)
    internal val translation: com.yaprak.reader.feature.translation.TranslationViewModel by viewModels {
        viewModelFactory { initializer {
            com.yaprak.reader.feature.translation.TranslationViewModel((application as YaprakApplication).container.englishTurkishTranslation)
        } }
    }
    internal var translationState by mutableStateOf<com.yaprak.reader.feature.translation.TranslationState?>(null)
    internal val selectionAssistant: com.yaprak.reader.feature.assistant.SelectionAssistantViewModel by viewModels {
        viewModelFactory { initializer {
            val container = (application as YaprakApplication).container
            com.yaprak.reader.feature.assistant.SelectionAssistantViewModel(container.selectedTextSources, container.selectedTextAnswers)
        } }
    }
    internal var selectionAssistantState by mutableStateOf<com.yaprak.reader.feature.assistant.SelectionAssistantState?>(null)
    internal val model: ReaderViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { ReaderViewModel(intent.getStringExtra(BOOK_ID).orEmpty(), container.reader, container.reading,
            container.readingDuration) } }
    }
    internal var uiState by mutableStateOf<ReaderState>(ReaderState.Loading)
    internal val presentation = ReaderPresentationController()
    internal var pendingPresentationRestore: ReaderPresentationSavedState? = null
    internal var restoringPresentation by mutableStateOf(false)
    internal var presentationRestoreFailed by mutableStateOf(false)
    internal var presentationRestoreJob: Job? = null
    internal var presentationRestoreGeneration = 0L
    internal val restoredPanelDrafts = mutableMapOf<ReaderPanelKind, Bundle>()
    internal val panelDraftSnapshots = mutableMapOf<ReaderPanelKind, Bundle>()
    internal var chrome: Boolean
        get() = presentation.state.value.controlsVisible
        set(value) { presentation.setControls(value) }
    internal var saveFailed by mutableStateOf(false)
    internal var loadFailed by mutableStateOf(false)
    internal var renderingFailed by mutableStateOf(false)
    internal var pageLabel by mutableStateOf<String?>(null)
    internal var comicDoublePagePreferred by mutableStateOf(false)
    internal var comicSpreadPreferences: Job? = null
    internal var bookPositionPercent by mutableStateOf<Int?>(null)
    internal val navigation by lazy { ReaderNavigationCoordinator(lifecycleScope, ::navigationOwner) }
    internal val positionJump get() = navigation.positionJump
    internal var pdfNightState by mutableStateOf(PdfNightModeState())
    internal val pdfNightBinding = ReaderPdfNightModeBinding()
    internal var appearanceState by mutableStateOf(EpubSettingsState())
    internal var screenAwakeState by mutableStateOf(ScreenAwakeState())
    internal lateinit var screenAwakeController: ReaderScreenAwakeController
    internal var orientationState by mutableStateOf(ReaderOrientationState())
    internal var orientationApplyFailed by mutableStateOf(false)
    internal lateinit var orientationController: ReaderOrientationController
    internal lateinit var immersiveController: ReaderImmersiveController
    internal val immersiveControllerReady: Boolean get() = ::immersiveController.isInitialized
    internal lateinit var brightnessController: ReaderBrightnessController
    internal val brightnessControllerReady: Boolean get() = ::brightnessController.isInitialized
    internal var brightnessPercent by mutableStateOf<Int?>(null)
    internal var brightnessDraft by mutableStateOf<Int?>(null)
    internal var brightnessOpen: Boolean
        get() = presentation.hasRequest(ReaderPanelKind.BRIGHTNESS)
        set(value) { presentation.request(ReaderPanelKind.BRIGHTNESS, value) }
    internal var brightnessGesturesEnabled by mutableStateOf(false)
    internal var brightnessGestureDraft by mutableStateOf(false)
    internal var brightnessGesturePreview by mutableStateOf<Int?>(null)
    internal var brightnessRequestId by mutableStateOf("")
    internal var brightnessGestureLayout: ReaderBrightnessGestureLayout? = null
    internal var epubImageFilterMode by mutableStateOf(EpubImageFilterMode.ORIGINAL)
    internal var epubImageFilterDraft by mutableStateOf(EpubImageFilterMode.ORIGINAL)
    internal var epubImagesOpen: Boolean
        get() = presentation.hasRequest(ReaderPanelKind.IMAGES)
        set(value) { presentation.request(ReaderPanelKind.IMAGES, value) }
    internal var epubImagesFailed by mutableStateOf(false)
    internal var bookThemeState by mutableStateOf(BookThemeState())
    internal var bookFontSizeState by mutableStateOf(BookFontSizeState())
    internal var bookFontState by mutableStateOf(BookFontState())
    internal var bookMarginsState by mutableStateOf(BookMarginsState())
    internal var bookLineHeightState by mutableStateOf(BookLineHeightState())
    internal var bookSpacingState by mutableStateOf(BookSpacingState())
    internal var bookTextAlignmentState by mutableStateOf(BookTextAlignmentState())
    internal var bookReadingModeState by mutableStateOf(BookReadingModeState())
    internal var bookAppearanceResetState by mutableStateOf(BookAppearanceResetState())
    internal var bookAppearanceOpen: Boolean
        get() = presentation.hasRequest(ReaderPanelKind.BOOK_APPEARANCE)
        set(value) { presentation.request(ReaderPanelKind.BOOK_APPEARANCE, value) }
    internal val appearanceCoordinator = ReaderAppearanceCoordinator()
    internal var continueShortcutBookId: String? = null
    internal val resetPending get() = appearanceCoordinator.resetPending
    internal val resetRefreshFailed get() = appearanceCoordinator.resetRefreshFailed
    internal val selection by lazy { ReaderSelectionCoordinator(lifecycleScope, lifecycle, ::selectionOwner,
        onCaptureStarted = { updateDuration(); observation?.cancel() },
        onCleared = ::clearSelectionTools,
        onReady = { preview -> updateDuration(); discoverSelectionTools(preview) },
        onRejected = {
            if (!exiting && !loadFailed && uiState is ReaderState.Ready &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && observation?.isActive != true) observeSurface()
        }) }
    internal val selectionPreview get() = selection.preview
    internal val selectionBookIdentity get() = selection.bookIdentity
    internal var selectionTools by mutableStateOf<SelectedTextToolsState?>(null)
    internal var selectionToolsJob: kotlinx.coroutines.Job? = null
    internal var selectionToolsGeneration = 0L
    internal var selectionExternalLaunch = false
    internal var bookAssistantEpoch by mutableStateOf(0L)
    internal val externalTextTools by lazy { AndroidSelectedTextTools(this) }
    internal var annotationRequest by mutableStateOf<EpubAnnotationRequest?>(null)
    internal var annotationSurface: ReaderSurface? = null
    internal var pdfAnnotationRequest by mutableStateOf<com.yaprak.reader.feature.pdfannotations.PdfAnnotationRequest?>(null)
    internal var pdfAnnotationsRequest by mutableStateOf<com.yaprak.reader.feature.pdfannotations.PdfAnnotationsWindow?>(null)
    internal var pdfAnnotationSurface: ReaderSurface? = null
    internal val annotationActive get() = annotationRequest != null || pdfAnnotationRequest != null || pdfAnnotationsRequest != null
    internal var highlightStatus by mutableStateOf<EpubHighlightStatus?>(null)
    internal var highlightRetryBusy by mutableStateOf(false)
    internal var canCustomize by mutableStateOf(false)
    internal var contentsNavigator by mutableStateOf<ContentsNavigator?>(null)
    internal val contentsLoader by lazy { ContentsController(lifecycleScope) { open ->
        contentsOpen = open
        updateDuration()
    } }
    internal var contentsOpen: Boolean
        get() = presentation.hasRequest(ReaderPanelKind.CONTENTS)
        set(value) { presentation.request(ReaderPanelKind.CONTENTS, value) }
    internal var bookmarkNavigator by mutableStateOf<BookmarkNavigator?>(null)
    internal val canJumpBack get() = navigation.canReturn
    internal var bookmarksOpen: Boolean
        get() = presentation.hasRequest(ReaderPanelKind.BOOKMARKS)
        set(value) { presentation.request(ReaderPanelKind.BOOKMARKS, value) }
    internal var savedWordsOpen: Boolean
        get() = presentation.hasRequest(ReaderPanelKind.WORDS)
        set(value) { presentation.request(ReaderPanelKind.WORDS, value) }
    internal var bookNotesOpen: Boolean
        get() = presentation.hasRequest(ReaderPanelKind.NOTES)
        set(value) { presentation.request(ReaderPanelKind.NOTES, value) }
    internal var passagesOpen: Boolean
        get() = presentation.hasRequest(ReaderPanelKind.PASSAGES)
        set(value) { presentation.request(ReaderPanelKind.PASSAGES, value) }
    internal var savedWordPrefill by mutableStateOf<String?>(null)
    internal var translatedWordPrefill by mutableStateOf<com.yaprak.reader.feature.vocabulary.TranslatedWordPrefill?>(null)
    internal var bookNotePrefill by mutableStateOf<String?>(null)
    internal var passagePrefill by mutableStateOf<PassageDraft?>(null)
    internal var passageNavigator by mutableStateOf<PassageNavigator?>(null)
    internal var pageNavigator by mutableStateOf<PageNavigator?>(null)
    internal var pageJumpOpen: Boolean
        get() = presentation.hasRequest(ReaderPanelKind.PAGE_JUMP)
        set(value) { presentation.request(ReaderPanelKind.PAGE_JUMP, value) }
    internal val pdfPageLinks by lazy { PdfPageLinksController(lifecycleScope) {
        updateDuration(); updatePdfThumbnailsEffects()
    } }
    internal var pdfThumbnailsRequest by mutableStateOf<PdfThumbnailsRequest?>(null)
    internal var pdfCropRequest by mutableStateOf<PdfCropRequest?>(null)
    internal var pdfCropSavingRequest: PdfCropRequest? = null
    internal var pdfCropApplyFailed by mutableStateOf(false)
    internal var bookSearchOpen: Boolean
        get() = presentation.hasRequest(ReaderPanelKind.SEARCH)
        set(value) { presentation.request(ReaderPanelKind.SEARCH, value) }
    internal val bookSearchState get() = navigation.search.state
    internal var bookSearch: BookTextSearch? = null
    internal var surface: ReaderSurface? = null
    internal var observation: Job? = null
    internal var indexedChapterLaunch: IndexedChapterRequest? = null
    internal var restoredIndexedChapter: IndexedChapterState? = null
    internal var indexedChapterState by mutableStateOf(IndexedChapterState())
    internal var indexedChapterForeground = false
    internal var indexedChapterGeneration = 0
    internal var indexedChapterJob: Job? = null
    internal var indexedChapterAttempt: String? = null
    internal var exiting = false
    internal val durationOwner = java.util.UUID.randomUUID().toString()
    internal val pace by lazy { ReaderPaceController(lifecycleScope, android.os.SystemClock::elapsedRealtime) }
    internal var durationForeground = false
    internal var durationFocused = false
    internal var durationVisible = false
    internal var durationSurface: ReaderSurface? = null
    internal var durationBook: com.yaprak.reader.domain.library.Book? = null
    internal var durationSession: com.yaprak.reader.reader.ReaderSession? = null
    internal var durationVisibility: Job? = null
    internal var durationFailure by mutableStateOf<com.yaprak.reader.domain.statistics.ReadingDurationFailure?>(null)
    internal val contentId = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        pendingPresentationRestore = ReaderPresentationSavedState.restore(savedInstanceState,
            intent.getStringExtra(BOOK_ID).orEmpty())
        indexedChapterLaunch = IndexedChapterLaunch.read(intent)
        restoredIndexedChapter = IndexedChapterLaunch.restore(savedInstanceState, indexedChapterLaunch)
        // Restore only our preference choice. Readium fragments still reopen through the
        // verified session rather than Android's no-argument fragment reconstruction.
        restoredPdfNightChoice = savedInstanceState?.takeIf { it.containsKey(PDF_NIGHT_CHOICE) }
            ?.getBoolean(PDF_NIGHT_CHOICE)
        brightnessPercent = savedInstanceState?.takeIf { it.containsKey(BRIGHTNESS_CHOICE) }
            ?.getInt(BRIGHTNESS_CHOICE)?.takeIf { it in 10..100 }
        brightnessGesturesEnabled = brightnessPercent != null && savedInstanceState?.getBoolean(BRIGHTNESS_GESTURE_CHOICE) == true
        epubImageFilterMode = EpubImageFilterMode.entries.firstOrNull {
            it.name == savedInstanceState?.getString(EPUB_IMAGE_CHOICE)
        } ?: EpubImageFilterMode.ORIGINAL
        super.onCreate(null)
        installVolumeKeyDispatch()
        screenAwakeController = ReaderScreenAwakeController(window, lifecycle)
        orientationController = ReaderOrientationController(this, lifecycle)
        enableEdgeToEdge()
        immersiveController = ReaderImmersiveController(window, lifecycle)
        brightnessController = ReaderBrightnessController(window, lifecycle)
        val root = ReaderBrightnessGestureLayout(this, ::canGestureBrightness, { surface }, { brightnessPercent },
            ::previewGestureBrightness, ::commitGestureBrightness, ::cancelGestureBrightness).also { brightnessGestureLayout = it }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            readerImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            immersiveController.updateImeVisible(readerImeVisible)
            insets
        }
        root.addView(FragmentContainerView(this).apply { id = contentId }, FrameLayout.LayoutParams(-1, -1))
        root.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                YaprakTheme {
                    ReaderActivityContent()
                }
            }
        }, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        immersiveController.updateWindowFocus(window.decorView.hasWindowFocus())
        brightnessController.updateWindowFocus(window.decorView.hasWindowFocus())
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                refreshPresentation()
                // A panel opened since the last frame must be closed by Back, not skipped into an exit.
                presentation.synchronizeRequests(currentPanelRequests(live = true))
                when (val decision = presentation.back(readerImeVisible,
                    nativeActionMode != null || surface?.textSelection?.active == true)) {
                    ReaderBackDecision.HideIme -> ViewCompat.getWindowInsetsController(window.decorView)
                        ?.hide(WindowInsetsCompat.Type.ime())
                    ReaderBackDecision.CancelDeferred -> presentation.pause()
                    is ReaderBackDecision.ClosePanel -> closeReaderPanel(decision.entry)
                    ReaderBackDecision.ClearSelection -> {
                        clearSelectionPreview()
                        surface?.textSelection?.clear()
                        nativeActionMode?.finish()
                        observeSurface()
                    }
                    ReaderBackDecision.HideControls -> { chrome = false; observeSurface() }
                    ReaderBackDecision.Exit -> leave()
                }
            }
        })
        observeReaderSession()
    }

    override fun onResume() {
        bookAssistantEpoch++
        selectionExternalLaunch = false
        model.indexedChapterOpen.initialize(indexedChapterLaunch, restoredIndexedChapter)
        indexedChapterForeground = true
        super.onResume()
        bindPdfBookView()
        durationForeground = true
        model.claimDuration(durationOwner)
        durationFocused = window.decorView.hasWindowFocus()
        updateDuration()
        if (::brightnessController.isInitialized) {
            brightnessController.updateWindowFocus(window.decorView.hasWindowFocus())
            updateBrightness()
        }
        startIndexedChapter()
        if (!presentationRestoreFailed) restoreReaderPresentation()
    }

    override fun onStop() {
        pdfBookView.deactivate()
        comicBookView.deactivate()
        durationForeground = false
        pace.suspend(surface)
        model.suspendDuration(durationOwner)
        cancelIndexedChapter()
        // Search can scan a large book. Keep the typed query, but release its cursor
        // when the reader leaves the foreground.
        if (navigation.search.active) cancelBookSearch()
        positionJump.cancel()
        clearSelectionPreview()
        clearAnnotation()
        pdfPageLinks.close()
        closeContents()
        clearPdfThumbnails()
        clearPdfCrop()
        // The home screen widget shows the latest progress once the reader leaves the foreground.
        com.yaprak.reader.feature.widget.ContinueReadingWidget.refresh(this)
        super.onStop()
    }

    override fun onPause() {
        readAloud.stop()
        presentationRestoreGeneration++
        presentationRestoreJob?.cancel()
        presentationRestoreJob = null
        restoringPresentation = false
        presentation.pause()
        bookAssistantEpoch++
        volumeKeyController.reset(); readerTouchActive = false
        if (navigation.search.active) cancelBookSearch()
        pdfBookView.deactivate()
        comicBookView.deactivate()
        durationForeground = false
        pace.suspend(surface)
        brightnessGestureLayout?.cancelGesture()
        dismissBrightness()
        model.suspendDuration(durationOwner)
        clearSelectionPreview()
        clearPdfAnnotations()
        // A selection capture cancels this job. Install its lifecycle-aware replacement
        // while paused so returning to this same surface resumes actual observations.
        if (surface != null && observation?.isActive != true && !exiting && !loadFailed) observeSurface()
        cancelIndexedChapter()
        pdfPageLinks.close()
        closeContents()
        clearPdfThumbnails()
        clearPdfCrop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        durationFocused = hasFocus
        if (!hasFocus) { volumeKeyController.reset(); readerTouchActive = false; brightnessGestureLayout?.cancelGesture() }
        if (durationForeground) updateDuration()
        if (::immersiveController.isInitialized) {
            updateImmersive()
            immersiveController.updateWindowFocus(hasFocus)
        }
        if (::brightnessController.isInitialized) {
            updateBrightness()
            brightnessController.updateWindowFocus(hasFocus)
        }
    }

    override fun onDestroy() {
        readAloud.release()
        pdfPageLinks.close()
        closeContents()
        surface?.taps?.setHandler(null)
        volumeKeyController.reset()
        if (window.callback === installedKeyWindowCallback) window.callback = originalKeyWindowCallback
        installedKeyWindowCallback = null
        originalKeyWindowCallback = null
        durationForeground = false
        model.releaseDuration(durationOwner)
        pace.detach()
        durationVisibility?.cancel()
        comicSpreadPreferences?.cancel()
        clearPdfNavigationResumeObserver()
        cancelIndexedChapter()
        positionJump.stopJobs()
        selection.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val book = (uiState as? ReaderState.Ready)?.session?.book
        (pendingPresentationRestore ?: book?.let {
            ReaderPresentationSavedState.capture(it, presentation.state.value, panelDraftSnapshots)
        })?.save(outState)
        val chapterState = model.indexedChapterOpen.state.value
        IndexedChapterLaunch.save(outState, if (chapterState.request == indexedChapterLaunch) chapterState
            else restoredIndexedChapter ?: IndexedChapterState(indexedChapterLaunch, IndexedChapterPhase.PENDING))
        // These fields are durable. A stale Android Bundle must never overwrite the stored row.
        PdfCropSavedState.write(outState, null)
        PdfSpreadSavedState.write(outState, null)
        pdfNightMode.saveInstanceChoice()?.let { outState.putBoolean(PDF_NIGHT_CHOICE, it) }
        brightnessPercent?.let { outState.putInt(BRIGHTNESS_CHOICE, it) }
            ?: outState.remove(BRIGHTNESS_CHOICE)
        outState.putBoolean(BRIGHTNESS_GESTURE_CHOICE, brightnessGesturesEnabled && brightnessPercent != null)
        if (epubImageFilterMode == EpubImageFilterMode.ORIGINAL) outState.remove(EPUB_IMAGE_CHOICE)
        else outState.putString(EPUB_IMAGE_CHOICE, epubImageFilterMode.name)
        super.onSaveInstanceState(outState)
    }

    internal fun updateScreenAwake() {
        screenAwakeController.update(
            enabled = screenAwakeState.saved && !screenAwakeState.loading && !screenAwakeState.loadFailed,
            readerReady = uiState is ReaderState.Ready && surface != null && !loadFailed && !exiting,
        )
        if (::orientationController.isInitialized) updateOrientation()
        if (::immersiveController.isInitialized) updateImmersive()
        if (::brightnessController.isInitialized) updateBrightness()
    }

    internal fun updateOrientation() {
        orientationController.update(orientationState.saved,
            preferenceReady = !orientationState.loading && !orientationState.loadFailed && !orientationState.saving,
            readerReady = isBrightnessReaderReady())
    }

    /** A callback belongs to this exact exposure, including pauses and same-kind reopenings. */
    internal fun <T> readerPanelAction(expected: ReaderPresentationState, kind: ReaderPanelKind,
        session: com.yaprak.reader.reader.ReaderSession?, owner: ReaderSurface?, sourceReady: Boolean,
        rejected: T, action: () -> T): T {
        val current = presentation.state.value
        if (expected.activePanel?.kind != kind || expected.activePanel != current.activePanel ||
            expected.owner == null || current.owner != expected.owner || current.epoch != expected.epoch ||
            session == null || owner == null || surface !== owner || exiting ||
            (uiState as? ReaderState.Ready)?.session !== session ||
            (model.state.value as? ReaderState.Ready)?.session !== session ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            (sourceReady && (!isBrightnessReaderReady() || model.saveFailed.value))) return rejected
        val result = action()
        // Model requests own drafts and work. Publish their accepted transition before another button can run.
        presentation.synchronizeRequests(currentPanelRequests(live = true))
        updateDuration()
        return result
    }

    internal fun rememberPanelDraft(kind: ReaderPanelKind, bundle: Bundle?) {
        if (kind !in ReaderPresentationSavedState.draftKinds || !presentation.hasRequest(kind)) return
        val bounded = ReaderDraftSnapshot.fromBundle(bundle)?.toBundle()
        if (bounded == null) panelDraftSnapshots.remove(kind) else panelDraftSnapshots[kind] = bounded
    }

    internal fun canUsePanelRequest(kind: ReaderPanelKind, session: com.yaprak.reader.reader.ReaderSession,
        owner: ReaderSurface?, expected: ReaderPresentationState): Boolean = owner != null && surface === owner &&
        isCurrentPanelRequest(kind, session, expected) && isBrightnessReaderReady()

    internal fun isCurrentPanelRequest(kind: ReaderPanelKind, session: com.yaprak.reader.reader.ReaderSession,
        expected: ReaderPresentationState): Boolean = expected.activePanel?.kind == kind &&
        presentation.state.value.activePanel == expected.activePanel && presentation.state.value.owner == expected.owner &&
        presentation.state.value.epoch == expected.epoch &&
        !exiting && (uiState as? ReaderState.Ready)?.session === session &&
            (model.state.value as? ReaderState.Ready)?.session === session &&
            presentation.state.value.activePanel?.kind == kind &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    internal fun clearPanelDraft(kind: ReaderPanelKind) {
        restoredPanelDrafts.remove(kind)
        panelDraftSnapshots.remove(kind)
    }

    internal fun restoreReaderPresentation() {
        val saved = pendingPresentationRestore ?: return
        val ready = uiState as? ReaderState.Ready ?: return
        val owner = surface ?: return
        if (presentationRestoreJob?.isActive == true || exiting || loadFailed || saveFailed || model.saveFailed.value ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!saved.matches(ready.session.book)) {
            // A different import may use the same Activity Intent; its old form is never attached.
            presentationRestoreFailed = true
            refreshPresentation()
            return
        }
        restoringPresentation = true
        val generation = ++presentationRestoreGeneration
        presentationRestoreFailed = false
        refreshPresentation()
        presentationRestoreJob = lifecycleScope.launch {
            try {
                val verified = model.verifyRestorationSource(ready.session.book)
                currentCoroutineContext().ensureActive()
                if (generation != presentationRestoreGeneration || pendingPresentationRestore !== saved || surface !== owner ||
                    (uiState as? ReaderState.Ready)?.session !== ready.session ||
                    (model.state.value as? ReaderState.Ready)?.session !== ready.session || exiting || loadFailed ||
                    saveFailed || model.saveFailed.value || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if (!verified) { presentationRestoreFailed = true; return@launch }
                val available = currentPanelRequests(live = true).toMutableSet().apply {
                    saved.panels.forEach { kind -> when (kind) {
                        ReaderPanelKind.BOOKMARKS -> if (bookmarkNavigator != null) add(kind)
                        ReaderPanelKind.PASSAGES -> if (ready.session.book.format == BookFormat.EPUB) add(kind)
                        ReaderPanelKind.NOTES, ReaderPanelKind.WORDS -> add(kind)
                        ReaderPanelKind.BOOK_APPEARANCE -> if (canCustomize) add(kind)
                        else -> Unit // Preference models restore only their own currently admitted forms.
                    } }
                }
                restoredPanelDrafts.clear()
                restoredPanelDrafts.putAll(saved.drafts.filterKeys { it in available })
                panelDraftSnapshots.clear()
                panelDraftSnapshots.putAll(restoredPanelDrafts)
                restoringPresentation = false
                refreshPresentation()
                if (presentation.restorePanels(saved.controlsVisible, saved.panels, available))
                    pendingPresentationRestore = null
                else presentationRestoreFailed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == presentationRestoreGeneration && pendingPresentationRestore === saved && surface === owner)
                    presentationRestoreFailed = true
            } finally {
                if (generation == presentationRestoreGeneration && surface === owner) {
                    restoringPresentation = false
                    refreshPresentation()
                    updateDuration()
                }
            }
        }
    }

    /** Adapter inventory: controllers own valid requests; presentation owns the one visible Window. */
    internal fun refreshPresentation() {
        val phase = when {
            uiState is ReaderState.Error -> ReaderPhase.BlockingError(ReaderBlockingReason.OPEN)
            loadFailed -> ReaderPhase.BlockingError(ReaderBlockingReason.SOURCE)
            saveFailed || model.saveFailed.value -> ReaderPhase.BlockingError(ReaderBlockingReason.SAVE)
            renderingFailed -> ReaderPhase.BlockingError(ReaderBlockingReason.RENDER)
            presentationRestoreFailed && pendingPresentationRestore != null -> ReaderPhase.BlockingError(ReaderBlockingReason.SOURCE)
            uiState !is ReaderState.Ready || surface == null || restoringPresentation -> ReaderPhase.Loading
            else -> ReaderPhase.Ready
        }
        val notice = when {
            saveFailed || model.saveFailed.value -> ReaderNoticeKind.SAVE
            loadFailed -> ReaderNoticeKind.LOAD
            renderingFailed -> ReaderNoticeKind.RENDER
            presentationRestoreFailed -> ReaderNoticeKind.PRESENTATION_RESTORE
            durationFailure != null -> ReaderNoticeKind.DURATION
            indexedChapterState.failure != null -> ReaderNoticeKind.INDEXED_SOURCE
            pdfBookViewState.failed -> ReaderNoticeKind.PDF_APPEARANCE
            comicBookViewState.failed -> ReaderNoticeKind.COMIC_APPEARANCE
            orientationApplyFailed -> ReaderNoticeKind.ORIENTATION
            pdfCropApplyFailed -> ReaderNoticeKind.PDF_CROP
            else -> null
        }
        presentation.updateStatus(phase, notice)
        presentation.setSelection(selectionPreview?.let { ReaderSelectionRef(it.sessionId) })
    }

    internal fun closeReaderPanel(expected: ReaderPanelEntry) {
        if (presentation.state.value.activePanel != expected) return
        when (expected.kind) {
            ReaderPanelKind.CONTENT, ReaderPanelKind.APPEARANCE, ReaderPanelKind.TOOLS -> presentation.closeMenu(expected)
            ReaderPanelKind.SETTINGS -> settings.dismiss()
            ReaderPanelKind.BOOK_APPEARANCE -> if (!resetPending) bookAppearanceOpen = false
            ReaderPanelKind.BOOK_RESET -> {
                bookAppearanceReset.dismiss()
                if (!bookAppearanceReset.state.value.open) appearanceCoordinator.cancelReset()
            }
            ReaderPanelKind.RESET_REFRESH -> leave()
            ReaderPanelKind.BOOK_THEME -> bookTheme.dismiss()
            ReaderPanelKind.BOOK_FONT_SIZE -> bookFontSize.dismiss()
            ReaderPanelKind.BOOK_FONT -> bookFont.dismiss()
            ReaderPanelKind.BOOK_MARGINS -> bookMargins.dismiss()
            ReaderPanelKind.BOOK_LINE_HEIGHT -> bookLineHeight.dismiss()
            ReaderPanelKind.BOOK_SPACING -> bookSpacing.dismiss()
            ReaderPanelKind.BOOK_ALIGNMENT -> bookTextAlignment.dismiss()
            ReaderPanelKind.BOOK_READING_MODE -> bookReadingMode.dismiss()
            ReaderPanelKind.ORIENTATION -> orientation.dismiss()
            ReaderPanelKind.BRIGHTNESS -> dismissBrightness()
            ReaderPanelKind.IMAGES -> dismissEpubImages()
            ReaderPanelKind.POSITION -> dismissBookPositionJump()
            ReaderPanelKind.TAP_ZONES -> tapZones.dismiss()
            ReaderPanelKind.VOLUME_KEYS -> volumeKeys.dismiss()
            ReaderPanelKind.SCREEN_AWAKE -> screenAwake.dismiss()
            ReaderPanelKind.SELECTION -> clearSelectionPreview()
            ReaderPanelKind.CONTENTS -> closeContents()
            ReaderPanelKind.SEARCH -> dismissBookSearch()
            ReaderPanelKind.PAGE_JUMP -> pageJumpOpen = false
            ReaderPanelKind.PDF_LINKS -> pdfPageLinks.close()
            ReaderPanelKind.PDF_THUMBNAILS -> pdfThumbnailsRequest?.let(::dismissPdfThumbnails)
            ReaderPanelKind.PDF_CROP -> pdfCropRequest?.let(::dismissPdfCrop)
            // These route-owned editors arbitrate cancellation against their admitted writes.
            ReaderPanelKind.ANNOTATION, ReaderPanelKind.PDF_ANNOTATION, ReaderPanelKind.PDF_ANNOTATIONS,
            ReaderPanelKind.BOOKMARKS, ReaderPanelKind.WORDS, ReaderPanelKind.NOTES, ReaderPanelKind.PASSAGES -> Unit
        }
        updateDuration()
        if (!exiting && !loadFailed && observation?.isActive != true) observeSurface()
    }

    internal fun currentPanelRequests(live: Boolean = false): Set<ReaderPanelKind> = buildSet {
        if ((if (live) settings.state.value.open else appearanceState.open)) add(ReaderPanelKind.SETTINGS)
        if (bookAppearanceOpen) add(ReaderPanelKind.BOOK_APPEARANCE)
        if ((if (live) bookAppearanceReset.state.value.open else bookAppearanceResetState.open)) add(ReaderPanelKind.BOOK_RESET)
        if (resetRefreshFailed) add(ReaderPanelKind.RESET_REFRESH)
        if ((if (live) bookTheme.state.value.open else bookThemeState.open)) add(ReaderPanelKind.BOOK_THEME)
        if ((if (live) bookFontSize.state.value.open else bookFontSizeState.open)) add(ReaderPanelKind.BOOK_FONT_SIZE)
        if ((if (live) bookFont.state.value.open else bookFontState.open)) add(ReaderPanelKind.BOOK_FONT)
        if ((if (live) bookMargins.state.value.open else bookMarginsState.open)) add(ReaderPanelKind.BOOK_MARGINS)
        if ((if (live) bookLineHeight.state.value.open else bookLineHeightState.open)) add(ReaderPanelKind.BOOK_LINE_HEIGHT)
        if ((if (live) bookSpacing.state.value.open else bookSpacingState.open)) add(ReaderPanelKind.BOOK_SPACING)
        if ((if (live) bookTextAlignment.state.value.open else bookTextAlignmentState.open)) add(ReaderPanelKind.BOOK_ALIGNMENT)
        if ((if (live) bookReadingMode.state.value.open else bookReadingModeState.open)) add(ReaderPanelKind.BOOK_READING_MODE)
        if ((if (live) orientation.state.value.open else orientationState.open)) add(ReaderPanelKind.ORIENTATION)
        if (brightnessOpen) add(ReaderPanelKind.BRIGHTNESS)
        if (epubImagesOpen) add(ReaderPanelKind.IMAGES)
        if ((positionJump.state.value.open)) add(ReaderPanelKind.POSITION)
        if ((if (live) tapZones.state.value.open else tapZoneState.open)) add(ReaderPanelKind.TAP_ZONES)
        if ((if (live) volumeKeys.state.value.open else volumeKeyState.open)) add(ReaderPanelKind.VOLUME_KEYS)
        if ((if (live) screenAwake.state.value.open else screenAwakeState.open)) add(ReaderPanelKind.SCREEN_AWAKE)
        if (selectionPreview != null) add(ReaderPanelKind.SELECTION)
        if (annotationRequest != null) add(ReaderPanelKind.ANNOTATION)
        if (pdfAnnotationRequest != null) add(ReaderPanelKind.PDF_ANNOTATION)
        if (pdfAnnotationsRequest != null) add(ReaderPanelKind.PDF_ANNOTATIONS)
        if (contentsOpen) add(ReaderPanelKind.CONTENTS)
        if (bookSearchOpen) add(ReaderPanelKind.SEARCH)
        if (pageJumpOpen) add(ReaderPanelKind.PAGE_JUMP)
        if (pdfPageLinks.state.value.open) add(ReaderPanelKind.PDF_LINKS)
        if (pdfThumbnailsRequest != null) add(ReaderPanelKind.PDF_THUMBNAILS)
        if (pdfCropRequest != null) add(ReaderPanelKind.PDF_CROP)
        if (bookmarksOpen) add(ReaderPanelKind.BOOKMARKS)
        if (savedWordsOpen) add(ReaderPanelKind.WORDS)
        if (bookNotesOpen) add(ReaderPanelKind.NOTES)
        if (passagesOpen) add(ReaderPanelKind.PASSAGES)
    }

    internal fun readerAction(action: () -> Unit) {
        if (presentation.state.value.phase == ReaderPhase.Ready && !tapZones.state.value.open && !volumeKeys.state.value.open && !orientation.state.value.open && selectionPreview == null && !annotationActive && pdfThumbnailsRequest == null && !pdfPageLinks.state.value.open && pdfCropRequest == null) {
            action(); updateDuration()
        }
    }

    internal fun installVolumeKeyDispatch() {
        val original = checkNotNull(window.callback)
        originalKeyWindowCallback = original
        // Use the public window boundary; the inherited AndroidX core dispatcher is restricted.
        val callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                volumeKeyController.dispatch(event, ::canTurnWithVolumeKey, ::turnWithVolumeKey) ||
                    original.dispatchKeyEvent(event)
        }
        installedKeyWindowCallback = callback
        window.callback = callback
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> readerTouchActive = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> readerTouchActive = false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        nativeActionMode = mode
        val editing = window.decorView.findFocus()?.onCheckIsTextEditor() == true
        if (!hasCompetingSelectionModal() && !annotationActive && selectionPreview == null && !editing) chrome = false
        if (!editing && surface?.textSelection != null) addQuickSelectionActions(mode)
    }

    /**
     * The book's own selection toolbar carries Yaprak's most used tools, so the reader never has to
     * go through "Diğer > Seçili metin". Items use click listeners: the WebView's callback never sees them.
     */
    internal fun addQuickSelectionActions(mode: ActionMode) {
        val menu = mode.menu
        if (menu.findItem(QUICK_SELECTION_OPEN) != null) return
        val format = (uiState as? ReaderState.Ready)?.session?.book?.format
        fun add(id: Int, order: Int, title: String, action: QuickSelectionAction) {
            menu.add(QUICK_SELECTION_GROUP, id, order, title).setOnMenuItemClickListener {
                pendingQuickSelection = action
                captureSelectedText()
                true
            }
        }
        if (format == BookFormat.EPUB) add(QUICK_SELECTION_HIGHLIGHT, 0, "Vurgula", QuickSelectionAction.HIGHLIGHT)
        add(QUICK_SELECTION_DICTIONARY, 1, "Sözlük", QuickSelectionAction.DICTIONARY)
        add(QUICK_SELECTION_TRANSLATE, 2, "Çevir", QuickSelectionAction.TRANSLATE)
        add(QUICK_SELECTION_OPEN, 3, "Yaprak…", QuickSelectionAction.OPEN)
        mode.invalidateContentRect()
    }

    internal enum class QuickSelectionAction { HIGHLIGHT, DICTIONARY, TRANSLATE, OPEN }
    internal var pendingQuickSelection: QuickSelectionAction? = null

    override fun onActionModeFinished(mode: ActionMode) {
        if (nativeActionMode === mode) nativeActionMode = null
        super.onActionModeFinished(mode)
    }

    internal fun openReaderControls() {
        if (!isBrightnessReaderReady() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            selectionExternalLaunch || selectionPreview != null || annotationActive || hasCompetingSelectionModal() ||
            indexedChapterState.phase in setOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.FAILED)) return
        observation?.cancel()
        volumeKeys.open()
        updateDuration()
    }

    internal fun openTapZones() {
        if (surface?.taps == null || !isBrightnessReaderReady() ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || selectionExternalLaunch ||
            selectionPreview != null || annotationActive || hasCompetingSelectionModal() ||
            indexedChapterState.phase in setOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.FAILED)) return
        observation?.cancel()
        tapZones.open()
        updateDuration()
    }

    internal fun handleCustomTap(owner: com.yaprak.reader.reader.ReaderSurface, tap: ReaderTap): Boolean {
        if (surface !== owner) return true
        val preference = tapZones.state.value
        if (!preference.saved.enabled && !preference.loading && !preference.loadFailed) return false
        if (preference.loadFailed) {
            // Keep the recovery editor reachable even when the unreadable mapping used to hide tools.
            if (canNavigateWithReadingInput(requireHiddenChrome = false)) {
                chrome = true
                observeSurface(); updateDuration()
            }
            return true
        }
        // An enabled/unknown mapping owns the event, including a no-op or temporarily blocked tap.
        // Falling through here would let the SDK's directional adapter turn a page anyway.
        if (preference.loading || preference.loadFailed || preference.saving ||
            !canNavigateWithReadingInput(requireHiddenChrome = false)) return true
        val zone = tap.zone ?: return true
        when (preference.saved.action(zone)) {
            TapZoneAction.MENU -> { chrome = !chrome; observeSurface(); updateDuration() }
            TapZoneAction.PREVIOUS -> { chrome = false; navigateWithReadingInput { owner.backward() } }
            TapZoneAction.NEXT -> { chrome = false; navigateWithReadingInput { owner.forward() } }
            TapZoneAction.NONE -> Unit
        }
        return true
    }

    internal fun canTurnWithVolumeKey(): Boolean {
        val preference = volumeKeys.state.value
        return preference.saved && !preference.loading && !preference.loadFailed && !preference.saving &&
            canNavigateWithReadingInput(requireHiddenChrome = true)
    }

    internal fun canNavigateWithReadingInput(requireHiddenChrome: Boolean): Boolean {
        val ready = uiState as? ReaderState.Ready ?: return false
        val active = surface ?: return false
        return (!requireHiddenChrome || !chrome) && durationForeground && durationFocused && window.decorView.hasWindowFocus() &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && model.state.value == ready &&
            ready.session === durationSession && ready.session.book == durationBook && active === durationSurface &&
            active.fragment.isResumed && durationVisible && isBrightnessReaderReady() && !model.saveFailed.value &&
            durationFailure == null && model.durationFailure.value == null &&
            !readerImeVisible && window.decorView.findFocus()?.onCheckIsTextEditor() != true && !readerTouchActive && nativeActionMode == null && active.textSelection?.active != true &&
            !selectionExternalLaunch && selectionPreview == null && !annotationActive && !hasCompetingSelectionModal() &&
            brightnessGesturePreview == null && !orientationApplyFailed && !pdfCropApplyFailed && !pdfBookView.state.value.failed && comicBookViewSettled() &&
            indexedChapterState.phase !in setOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.FAILED) &&
            (application as YaprakApplication).container.startup.state.value == com.yaprak.reader.core.StartupState.Ready
    }

    internal fun turnWithVolumeKey(direction: ReaderVolumeKeyController.Direction) {
        if (!canTurnWithVolumeKey()) return
        val active = surface ?: return
        navigateWithReadingInput {
            when (direction) {
                ReaderVolumeKeyController.Direction.PREVIOUS -> active.backward()
                ReaderVolumeKeyController.Direction.NEXT -> active.forward()
            }
        }
    }

    internal fun navigateWithReadingInput(action: () -> Boolean) {
        observation?.cancel()
        try { action() } finally {
            observeSurface()
            updateDuration()
        }
    }

    internal fun updateImmersive() {
        immersiveController.update(
            readerReady = uiState is ReaderState.Ready && surface != null && !loadFailed && !exiting,
            chromeVisible = chrome || saveFailed || loadFailed || pdfBookView.state.value.failed || !comicBookViewSettled(),
            blocked = epubImagesOpen || brightnessOpen || renderingFailed || selectionPreview != null || resetPending || resetRefreshFailed ||
                settings.state.value.open || screenAwake.state.value.open || volumeKeys.state.value.open || tapZones.state.value.open || orientation.state.value.open || annotationActive || bookAppearanceOpen ||
                bookAppearanceReset.state.value.open || bookTheme.state.value.open ||
                bookFontSize.state.value.open || bookFont.state.value.open || bookMargins.state.value.open ||
                bookLineHeight.state.value.open || bookSpacing.state.value.open || bookTextAlignment.state.value.open || bookReadingMode.state.value.open || contentsOpen || bookSearchOpen || bookmarksOpen ||
                savedWordsOpen || bookNotesOpen || passagesOpen || pageJumpOpen || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null || positionJump.state.value.open,
        )
    }

    internal fun observeSurface() {
        observation?.cancel()
        val active = surface ?: return
        observation = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { active.pageLabel.collect { pageLabel = it } }
                launch { active.renderingFailed.collect {
                    renderingFailed = it
                    refreshPresentation()
                    updateDuration()
                    updateImmersive()
                    updateBrightness()
                    updateOrientation()
                } }
                launch { active.bookPositionPercent.collect { bookPositionPercent = it } }
                active.epubHighlights?.let { highlights -> launch {
                    highlights.status.collect { if (surface === active) highlightStatus = it }
                } } ?: run { highlightStatus = null }
                if (!loadFailed) active.observe({ !presentation.state.value.blocksReading && !selectionExternalLaunch && brightnessGesturePreview == null && !chrome && !pdfBookView.state.value.failed && comicBookViewSettled() && !saveFailed && !loadFailed && !epubImagesOpen && !brightnessOpen && selectionPreview == null && !resetPending && !settings.state.value.open && !screenAwake.state.value.open && !tapZones.state.value.open && !volumeKeys.state.value.open && !orientation.state.value.open && !annotationActive && !bookAppearanceOpen && !bookAppearanceReset.state.value.open && !bookTheme.state.value.open && !bookFontSize.state.value.open && !bookFont.state.value.open && !bookMargins.state.value.open && !bookLineHeight.state.value.open && !bookSpacing.state.value.open && !bookTextAlignment.state.value.open && !bookReadingMode.state.value.open && !contentsOpen && !bookSearchOpen && !bookmarksOpen && !savedWordsOpen && !bookNotesOpen && !passagesOpen && !pageJumpOpen && pdfThumbnailsRequest == null && !pdfPageLinks.state.value.open && pdfCropRequest == null && !positionJump.state.value.open }, model::observe)
            }
        }
    }

    /** Independent of locator subscriptions: timing cannot replay or manufacture reading progress. */
    internal fun bindDurationVisibility(active: ReaderSurface) {
        if (durationSurface === active) return
        model.suspendDuration(durationOwner)
        durationVisibility?.cancel()
        durationSurface = active
        durationSession = (uiState as? ReaderState.Ready)?.session
        durationBook = durationSession?.book
        durationBook?.let { pace.bind(active, PaceIdentity(it.id, it.sha256), active.readingPace) }
            ?: pace.detach()
        durationVisible = false
        durationVisibility = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                active.fragment.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    try {
                        active.readingVisible.collect { visible ->
                            if (surface === active && durationSurface === active) {
                                durationVisible = visible
                                updateDuration()
                            }
                        }
                    } finally {
                        if (durationSurface === active) {
                            durationVisible = false
                            model.suspendDuration(durationOwner)
                        }
                    }
                }
            }
        }
    }

    internal fun updateDuration() {
        if (!durationForeground) return
        val ready = uiState as? ReaderState.Ready ?: run {
            model.suspendDuration(durationOwner); pace.suspend(surface); return
        }
        val active = surface
        val permitted = !selectionExternalLaunch && brightnessGesturePreview == null && durationFocused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            model.state.value == ready && ready.session === durationSession && ready.session.book == durationBook &&
            active != null && active === durationSurface && active.fragment.isResumed &&
            !exiting && !chrome && !loadFailed && !renderingFailed && !saveFailed &&
            !model.saveFailed.value && durationFailure == null && model.durationFailure.value == null &&
            !pdfCropApplyFailed && !pdfBookView.state.value.failed && comicBookViewSettled() && !hasCompetingSelectionModal() && selectionPreview == null && !annotationActive &&
            indexedChapterState.phase !in setOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.FAILED)
        pace.permit(active, PaceIdentity(ready.session.book.id, ready.session.book.sha256), permitted)
        model.updateDuration(durationOwner, ready.session.book, permitted && durationVisible)
    }

    internal fun searchSelectedText(expected: SelectedTextPreview.Ready, owner: ReaderSurface, source: BookTextSearch) {
        if (!isSelectionCurrent() || selectionPreview !== expected || surface !== owner ||
            bookSearch !== source || !source.supported || bookSearchOpen) return
        val draft = selectedBookSearchDraft(expected.text) ?: return
        clearSelectionPreview()
        if (surface !== owner || bookSearch !== source || exiting || loadFailed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        observation?.cancel()
        if (!navigation.search.open(source, draft)) { observeSurface(); return }
        bookSearchOpen = true
        updateDuration()
    }

    internal fun openBookAssistant(action: ReaderBookAssistantAction) {
        val ready = uiState as? ReaderState.Ready
        val current = model.state.value as? ReaderState.Ready
        val owner = surface
        val allowed = ready != null && current?.session === ready.session && chrome &&
            isBrightnessReaderReady() && owner != null && owner.fragment.isResumed &&
            (ready.session.book.format != BookFormat.PDF || owner.bookSearch?.supported == true) &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && window.decorView.hasWindowFocus() &&
            !selectionExternalLaunch && !hasCompetingSelectionModal() && selectionPreview == null && !annotationActive &&
            indexedChapterState.phase !in setOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.FAILED) &&
            !model.saveFailed.value && durationFailure == null && model.durationFailure.value == null &&
            !pdfCropApplyFailed && !pdfBookView.state.value.failed && comicBookViewSettled() &&
            (application as YaprakApplication).container.startup.state.value == com.yaprak.reader.core.StartupState.Ready
        action.open(ready?.session?.book, ready?.session, owner, bookAssistantEpoch, allowed) { scope ->
            selectionExternalLaunch = true
            observation?.cancel()
            updateDuration()
            try {
                startActivity(com.yaprak.reader.feature.assistant.BookAssistantActivity.intent(this, scope, ""))
            } catch (_: android.content.ActivityNotFoundException) {
                failedBookAssistantLaunch()
            } catch (_: SecurityException) {
                failedBookAssistantLaunch()
            }
        }
    }

    internal fun failedBookAssistantLaunch() {
        selectionExternalLaunch = false
        bookAssistantEpoch++
        observeSurface()
        updateDuration()
        android.widget.Toast.makeText(this, R.string.reader_book_assistant_open_failed, android.widget.Toast.LENGTH_SHORT).show()
    }

    internal fun openBookSearch() {
        if (tapZones.state.value.open || volumeKeys.state.value.open || orientation.state.value.open || annotationActive || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null || epubImagesOpen || brightnessOpen || positionJump.state.value.open) return
        if (bookSearch == null || exiting || loadFailed || uiState !is ReaderState.Ready ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        observation?.cancel()
        val source = bookSearch ?: return
        if (!navigation.search.open(source)) { observeSurface(); return }
        bookSearchOpen = true
        updateDuration()
    }

    internal fun canNavigateBookPosition(navigator: BookPositionNavigator): Boolean =
        presentation.state.value.phase == ReaderPhase.Ready &&
        (uiState as? ReaderState.Ready)?.session?.book?.format?.reflowable == true &&
            surface?.bookPositionNavigator === navigator && !exiting && !loadFailed &&
            !saveFailed && !model.saveFailed.value && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !epubImagesOpen && !brightnessOpen && selectionPreview == null && !resetPending && !resetRefreshFailed &&
            !settings.state.value.open && !screenAwake.state.value.open && !tapZones.state.value.open && !volumeKeys.state.value.open && !orientation.state.value.open && !annotationActive && !bookAppearanceOpen &&
            !bookAppearanceReset.state.value.open && !bookTheme.state.value.open &&
            !bookFontSize.state.value.open && !bookFont.state.value.open &&
            !bookMargins.state.value.open && !bookLineHeight.state.value.open && !bookSpacing.state.value.open && !bookTextAlignment.state.value.open && !bookReadingMode.state.value.open &&
            !contentsOpen && !bookSearchOpen && !bookmarksOpen && !savedWordsOpen &&
            !bookNotesOpen && !passagesOpen && !pageJumpOpen && pdfThumbnailsRequest == null && !pdfPageLinks.state.value.open && pdfCropRequest == null

    /**
     * Bottom-bar scrubber. Paged formats seek by page through the same jump path as "go to page";
     * reflowable formats seek by book percent through the format's own percent resolver.
     * Reads [pageLabel]/[bookPositionPercent] so the thumb follows real navigation.
     */
    internal fun readerSeek(): ReaderSeek? {
        @Suppress("UNUSED_VARIABLE") val observedLabel = pageLabel
        if (loadFailed || saveFailed || exiting) return null
        pageNavigator?.let { navigator ->
            val count = navigator.pageCount
            val current = navigator.currentPage ?: return null
            if (count < 2) return null
            return ReaderSeek(current.coerceIn(0, count - 1), count - 1, { "Sayfa ${it + 1} / $count" }) { target ->
                // A scrub keeps the controls up so the user can fine-tune the position.
                readerAction { if (selectPage(target)) chrome = true }
            }
        }
        val percentNavigator = surface?.bookPositionNavigator ?: return null
        val percent = bookPositionPercent ?: return null
        return ReaderSeek(percent.coerceIn(0, 100), 100, { "%$it" }) { target -> seekBookPercent(percentNavigator, target) }
    }

    internal var seekJob: Job? = null

    /** One tap in the top bar saves the current position, same record as "Yer imleri > Bu konumu kaydet". */
    internal val readAloud by lazy { ReadAloudController(this, lifecycleScope) }

    /** Title of the TOC entry being read; re-read whenever the visible position changes. */
    internal fun currentChapterTitle(): String? {
        @Suppress("UNUSED_VARIABLE") val observed = bookPositionPercent to pageLabel
        val navigator = contentsNavigator ?: return null
        val id = navigator.currentEntryId() ?: return null
        return navigator.contents.entries.firstOrNull { it.id == id }?.title
    }
    internal fun toggleReadAloud() {
        val source = surface?.readAloud ?: return
        if (exiting || presentation.state.value.phase != ReaderPhase.Ready) return
        readAloud.toggle(source)
        if (readAloud.active) chrome = false
    }

    internal var quickBookmarkJob: Job? = null
    internal fun quickBookmark() {
        val navigator = bookmarkNavigator ?: return
        val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
        if (quickBookmarkJob?.isActive == true || presentation.state.value.phase != ReaderPhase.Ready || exiting) return
        val target = navigator.capture()
        if (target == null) {
            android.widget.Toast.makeText(this, "Konum henüz hazır değil; sayfa çizilince yeniden dene.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        quickBookmarkJob = lifecycleScope.launch {
            val message = try {
                (application as YaprakApplication).container.bookmarks.add(book.id, book.sha256, target)
                "Yer imi eklendi · ${target.label}"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { "Yer imi kaydedilemedi. Yeniden deneyebilirsin." }
            android.widget.Toast.makeText(this@ReaderActivity, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    internal fun seekBookPercent(navigator: BookPositionNavigator, percent: Int) {
        if (!canNavigateBookPosition(navigator)) return
        seekJob?.cancel()
        seekJob = lifecycleScope.launch {
            val location = try { navigator.resolvePercent(percent) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (location != null && canNavigateBookPosition(navigator) && navigateJump { navigator.goTo(location) })
                chrome = true
        }
    }

    internal fun openBookPositionJump() {
        val navigator = surface?.bookPositionNavigator ?: return
        if (positionJump.state.value.open || !canNavigateBookPosition(navigator)) return
        observation?.cancel()
        positionJump.open(navigator, bookPositionPercent)
        updateDuration()
    }

    internal fun submitBookPositionJump() {
        positionJump.submit(::canNavigateBookPosition) { navigator, target ->
            if (!canNavigateBookPosition(navigator)) false
            else navigateJump { navigator.goTo(target) }
        }
    }

    internal fun dismissBookPositionJump() {
        positionJump.dismiss()
        observeSurface()
    }

    internal fun changeBookSearchQuery(value: String) { navigation.search.edit(value, bookSearchOpen) }

    internal fun canUseBookSearch(): Boolean = bookSearchOpen && !orientation.state.value.open && !annotationActive &&
        !exiting && !loadFailed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    internal fun runBookSearch() { navigation.search.run(::canUseBookSearch) }
    internal fun stopBookSearchJobs(): List<Job> = navigation.search.stopJobs()
    internal fun cancelBookSearch() { navigation.search.cancel() }

    internal fun dismissBookSearch() {
        navigation.search.close()
        bookSearchOpen = false
        observeSurface()
    }

    internal fun selectBookSearchHit(hit: BookSearchHit) {
        if (navigation.search.select(hit, ::canUseBookSearch) { source, target -> navigateJump { source.goTo(target.location) } })
            dismissBookSearch()
    }

    internal fun openContents() {
        if (exiting || loadFailed || saveFailed || uiState !is ReaderState.Ready ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val source = contentsNavigator ?: return
        val owner = surface ?: return
        contentsLoader.open(source) {
            surface === owner && contentsNavigator === source && !exiting && !loadFailed && !saveFailed &&
                uiState is ReaderState.Ready && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }

    internal fun closeContents(expected: ContentsRequest? = null) {
        if (expected != null && contentsLoader.state.value.request !== expected) return
        contentsOpen = false
        contentsLoader.close(expected)
    }

    internal fun selectContentsEntry(expected: ContentsState, id: String): Boolean {
        if (!contentsLoader.canSelect(expected, id)) return false
        val navigator = contentsNavigator ?: return false
        if (expected.request?.source !== navigator) return false
        return navigateJump { navigator.goTo(id) }
    }

    internal fun leave() {
        if (orientation.state.value.open || annotationActive) return
        if (exiting) return
        readAloud.stop()
        exiting = true
        pdfBookView.deactivate()
        model.suspendDuration(durationOwner)
        updateScreenAwake()
        val pendingSearches = stopBookSearchJobs() + positionJump.stopJobs()
        navigation.search.cancel()
        clearSelectionPreview()
        observation?.cancel()
        lifecycleScope.launch {
            pendingSearches.joinAll()
            if (model.flush()) finish()
            else { chrome = true; exiting = false; bindPdfBookView(); updateScreenAwake() }
        }
    }

    internal fun leaveUnsaved() {
        if (orientation.state.value.open || annotationActive) return
        if (exiting) return
        exiting = true
        pdfBookView.deactivate()
        model.suspendDuration(durationOwner)
        updateScreenAwake()
        val pendingSearches = stopBookSearchJobs() + positionJump.stopJobs()
        navigation.search.cancel()
        clearSelectionPreview()
        observation?.cancel()
        lifecycleScope.launch {
            pendingSearches.joinAll()
            finish()
        }
    }

    internal fun selectBookmark(location: ReadingLocation, complete: () -> Boolean): Boolean {
        val navigator = bookmarkNavigator ?: return false
        return performNavigation({ navigator.goTo(location) }, null, null, jump = true, onAccepted = complete)
    }

    internal suspend fun selectPassageChapter(passage: EpubPassage, complete: () -> Boolean): Boolean {
        if (orientation.state.value.open || annotationActive) return false
        val navigator = passageNavigator ?: return false
        if (!passagesOpen || exiting || loadFailed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val href = navigator.verifiedChapterHref(passage) ?: return false
        if (passageNavigator !== navigator || surface?.passageNavigator !== navigator || !passagesOpen) return false
        return performNavigation({ navigator.goToChapter(href) }, null, null, jump = true, onAccepted = complete)
    }

    internal fun selectPage(pageIndex: Int, complete: () -> Boolean): Boolean {
        val navigator = pageNavigator ?: return false
        return performNavigation({ navigator.goTo(pageIndex) }, null, null, jump = true, onAccepted = complete)
    }

    internal fun selectPage(pageIndex: Int): Boolean {
        val navigator = pageNavigator ?: return false
        return navigateJump { navigator.goTo(pageIndex) }
    }

    internal fun navigationOwner(): ReaderNavigationOwner? {
        val ready = uiState as? ReaderState.Ready ?: return null
        val active = surface ?: return null
        val renderRecovery = pdfThumbnailsRequest?.let { isPdfRenderRecovery() && isPdfThumbnailsCurrent(it) } == true
        if (exiting || loadFailed || saveFailed || model.saveFailed.value || renderingFailed && !renderRecovery ||
            presentation.state.value.phase != ReaderPhase.Ready && !renderRecovery ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            (model.state.value as? ReaderState.Ready)?.session !== ready.session) return null
        return ReaderNavigationOwner(ready.session, active)
    }

    internal fun canNavigate(thumbnails: PdfThumbnailsRequest?, pageLinks: PdfPageLinksRequest?,
        annotations: com.yaprak.reader.feature.pdfannotations.PdfAnnotationsWindow? = null): Boolean {
        if (thumbnails != null && !isPdfThumbnailsCurrent(thumbnails)) return false
        if (pageLinks != null && !pdfPageLinks.isCurrent(pageLinks)) return false
        val renderRecovery = thumbnails != null && isPdfRenderRecovery() && isPdfThumbnailsCurrent(thumbnails)
        val ownedAnnotations = annotations != null && annotations.lifetime.isActive && pdfAnnotationsRequest === annotations &&
            pdfAnnotationRequest == null && canMutatePdfAnnotations(annotations.bookId, annotations.hash)
        if (annotations != null && !ownedAnnotations) return false
        return pdfCropRequest == null && (pdfThumbnailsRequest == null || thumbnails != null) &&
            (!pdfPageLinks.state.value.open || pageLinks != null) && !tapZones.state.value.open &&
            !volumeKeys.state.value.open && !orientation.state.value.open && selectionPreview == null &&
            (!annotationActive || ownedAnnotations) && !exiting && !loadFailed && !saveFailed && !model.saveFailed.value &&
            (!renderingFailed || renderRecovery) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    internal fun navigate(request: () -> Boolean): Boolean = navigate(request, null)
    internal fun navigate(request: () -> Boolean, thumbnails: PdfThumbnailsRequest?, pageLinks: PdfPageLinksRequest? = null): Boolean =
        performNavigation(request, thumbnails, pageLinks, jump = false)

    internal fun navigateJump(request: () -> Boolean): Boolean = navigateJump(request, null)
    internal fun navigateJump(request: () -> Boolean, thumbnails: PdfThumbnailsRequest?, pageLinks: PdfPageLinksRequest? = null): Boolean =
        performNavigation(request, thumbnails, pageLinks, jump = true)

    /** Every path observes the real destination; an accepted command never creates progress or coverage. */
    internal fun performNavigation(request: () -> Boolean, thumbnails: PdfThumbnailsRequest?,
        pageLinks: PdfPageLinksRequest?, jump: Boolean, onAccepted: () -> Boolean = { true },
        annotations: com.yaprak.reader.feature.pdfannotations.PdfAnnotationsWindow? = null): Boolean {
        var attempted = false
        var accepted = false
        try {
            accepted = navigation.navigate(jump, { canNavigate(thumbnails, pageLinks, annotations) }) {
                attempted = true
                clearSelectionPreview()
                if (thumbnails == null) observation?.cancel()
                request()
            }
            // A route completes its admitted close while its original exposure is still current.
            // Hiding controls then revokes that exposure, including any other queued callbacks.
            if (accepted) accepted = onAccepted()
            if (accepted) chrome = false
            return accepted
        } finally {
            if (attempted) {
                updateDuration()
                if (thumbnails == null) observeSurface()
                else {
                    updatePdfThumbnailsEffects()
                    if (accepted && observation?.isActive != true) observeSurface()
                }
            }
        }
    }

    internal fun returnBeforeJump() { navigate { navigation.returnToPrevious() } }

    companion object {
        internal const val BOOK_ID = "bookId"
        internal const val PDF_NIGHT_CHOICE = "readerPdfNightChoice"
        internal const val BRIGHTNESS_CHOICE = "readerBrightnessPercent"
        internal const val BRIGHTNESS_GESTURE_CHOICE = "readerBrightnessGestures"
        internal const val EPUB_IMAGE_CHOICE = "readerEpubImageFilter"
        internal const val QUICK_SELECTION_GROUP = 0x59a0
        internal const val QUICK_SELECTION_HIGHLIGHT = 0x59a1
        internal const val QUICK_SELECTION_DICTIONARY = 0x59a2
        internal const val QUICK_SELECTION_TRANSLATE = 0x59a3
        internal const val QUICK_SELECTION_OPEN = 0x59a4
        fun intent(context: Context, bookId: String) = Intent(context, ReaderActivity::class.java).putExtra(BOOK_ID, bookId)
        fun intent(context: Context, target: LibraryTextTarget): Intent =
            IndexedChapterLaunch.addTo(intent(context, target.bookId), target)
    }
}

internal data class ReaderInputs(
    val state: ReaderState,
    val saveFailed: Boolean,
    val global: EpubSettingsState,
    val localTheme: BookThemeState,
    val localFont: BookFontSizeState,
    val localFamily: BookFontState = BookFontState(),
    val localMargins: BookMarginsState = BookMarginsState(),
    val localLineHeight: BookLineHeightState = BookLineHeightState(),
    val localSpacing: BookSpacingState = BookSpacingState(),
    val localAlignment: BookTextAlignmentState = BookTextAlignmentState(),
    val localMode: BookReadingModeState = BookReadingModeState(),
    val localReset: BookAppearanceResetState = BookAppearanceResetState(),
    val screenAwake: ScreenAwakeState = ScreenAwakeState(),
    val orientation: ReaderOrientationState = ReaderOrientationState(),
    val volumeKeys: VolumeKeyState = VolumeKeyState(),
    val tapZones: TapZoneState = TapZoneState(),
)
