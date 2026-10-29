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
import com.yaprak.reader.reader.TextSelectionException
import com.yaprak.reader.reader.TextSelectionFailure
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
import com.yaprak.reader.feature.settings.appearanceResetObserved
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.domain.settings.withBookFontSize
import com.yaprak.reader.domain.settings.withBookFont
import com.yaprak.reader.domain.settings.withBookMargins
import com.yaprak.reader.domain.settings.withBookLineHeight
import com.yaprak.reader.domain.settings.withBookSpacing
import com.yaprak.reader.domain.settings.withBookTextAlignment
import com.yaprak.reader.domain.settings.withBookReadingMode
import com.yaprak.reader.domain.settings.withBookTheme
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
    private val pdfBookView: PdfBookViewPreferencesViewModel by viewModels {
        viewModelFactory { initializer {
            PdfBookViewPreferencesViewModel((application as YaprakApplication).container.bookPdfViewPreferences)
        } }
    }
    private var pdfBookViewState by mutableStateOf(PdfBookViewPreferencesState())
    private val pdfBookViewBinding = ReaderPdfBookViewPreferencesBinding()
    private var pdfBookViewSurface: ReaderSurface? = null
    private var pdfNavigationResumeObserver: androidx.lifecycle.LifecycleEventObserver? = null
    private val comicBookView: ComicBookViewPreferencesViewModel by viewModels {
        viewModelFactory { initializer {
            ComicBookViewPreferencesViewModel((application as YaprakApplication).container.bookComicViewPreferences)
        } }
    }
    private var comicBookViewState by mutableStateOf(ComicBookViewPreferencesState())
    private val comicBookViewBinding = ReaderComicBookViewPreferencesBinding()
    private var comicBookViewSurface: ReaderSurface? = null
    private var restoredPdfNightChoice: Boolean? = null
    private val pdfNightMode: PdfNightModeViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer {
            val restored = restoredPdfNightChoice
            val handle = if (restored == null) SavedStateHandle() else
                SavedStateHandle(mapOf(PdfNightModeViewModel.SAVED_STATE_KEY to restored))
            PdfNightModeViewModel(container.pdfNightSettings, handle)
        } }
    }
    private val volumeKeys: VolumeKeyViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { VolumeKeyViewModel(container.volumeKeys) } }
    }
    private val tapZones: TapZoneViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { TapZoneViewModel(container.tapZones) } }
    }
    private var tapZoneState by mutableStateOf(TapZoneState())
    private val volumeKeyController = ReaderVolumeKeyController()
    private var originalKeyWindowCallback: Window.Callback? = null
    private var installedKeyWindowCallback: Window.Callback? = null
    private var volumeKeyState by mutableStateOf(VolumeKeyState())
    private var readerImeVisible = false
    private var readerTouchActive = false
    private var nativeActionMode: ActionMode? = null
    private val screenAwake: ScreenAwakeViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { ScreenAwakeViewModel(container.screenAwake) } }
    }
    private val orientation: ReaderOrientationViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { ReaderOrientationViewModel(container.readerOrientation) } }
    }
    private val settings: EpubSettingsViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { EpubSettingsViewModel(container.settings) } }
    }
    private val bookTheme: BookThemeViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookThemeViewModel(container.bookThemes) } }
    }
    private val bookFontSize: BookFontSizeViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookFontSizeViewModel(container.bookFontSizes) } }
    }
    private val bookFont: BookFontViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookFontViewModel(container.bookFonts) } }
    }
    private val bookMargins: BookMarginsViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookMarginsViewModel(container.bookMargins) } }
    }
    private val bookLineHeight: BookLineHeightViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookLineHeightViewModel(container.bookLineHeights) } }
    }
    private val bookAppearanceReset: BookAppearanceResetViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookAppearanceResetViewModel(container.bookAppearanceReset) } }
    }
    private val bookReadingMode: BookReadingModeViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookReadingModeViewModel(container.bookReadingModes) } }
    }
    private val bookTextAlignment: BookTextAlignmentViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookTextAlignmentViewModel(container.bookTextAlignments) } }
    }
    private val bookSpacing: BookSpacingViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { BookSpacingViewModel(container.bookSpacings) } }
    }
    private val dictionary: DictionaryLookupViewModel by viewModels {
        viewModelFactory { initializer {
            DictionaryLookupViewModel((application as YaprakApplication).container.englishDictionary)
        } }
    }
    private var dictionaryState by mutableStateOf<DictionaryLookupState?>(null)
    private val translation: com.yaprak.reader.feature.translation.TranslationViewModel by viewModels {
        viewModelFactory { initializer {
            com.yaprak.reader.feature.translation.TranslationViewModel((application as YaprakApplication).container.englishTurkishTranslation)
        } }
    }
    private var translationState by mutableStateOf<com.yaprak.reader.feature.translation.TranslationState?>(null)
    private val selectionAssistant: com.yaprak.reader.feature.assistant.SelectionAssistantViewModel by viewModels {
        viewModelFactory { initializer {
            val container = (application as YaprakApplication).container
            com.yaprak.reader.feature.assistant.SelectionAssistantViewModel(container.selectedTextSources, container.selectedTextAnswers)
        } }
    }
    private var selectionAssistantState by mutableStateOf<com.yaprak.reader.feature.assistant.SelectionAssistantState?>(null)
    private val model: ReaderViewModel by viewModels {
        val container = (application as YaprakApplication).container
        viewModelFactory { initializer { ReaderViewModel(intent.getStringExtra(BOOK_ID).orEmpty(), container.reader, container.reading,
            container.readingDuration) } }
    }
    private var uiState by mutableStateOf<ReaderState>(ReaderState.Loading)
    private var chrome by mutableStateOf(true)
    private var saveFailed by mutableStateOf(false)
    private var loadFailed by mutableStateOf(false)
    private var renderingFailed by mutableStateOf(false)
    private var pageLabel by mutableStateOf<String?>(null)
    private var comicDoublePagePreferred by mutableStateOf(false)
    private var comicSpreadPreferences: Job? = null
    private var bookPositionPercent by mutableStateOf<Int?>(null)
    private lateinit var positionJump: BookPositionJumpController
    private var pdfNightState by mutableStateOf(PdfNightModeState())
    private val pdfNightBinding = ReaderPdfNightModeBinding()
    private var appearanceState by mutableStateOf(EpubSettingsState())
    private var screenAwakeState by mutableStateOf(ScreenAwakeState())
    private lateinit var screenAwakeController: ReaderScreenAwakeController
    private var orientationState by mutableStateOf(ReaderOrientationState())
    private var orientationApplyFailed by mutableStateOf(false)
    private lateinit var orientationController: ReaderOrientationController
    private lateinit var immersiveController: ReaderImmersiveController
    private lateinit var brightnessController: ReaderBrightnessController
    private var brightnessPercent by mutableStateOf<Int?>(null)
    private var brightnessDraft by mutableStateOf<Int?>(null)
    private var brightnessOpen by mutableStateOf(false)
    private var brightnessGesturesEnabled by mutableStateOf(false)
    private var brightnessGestureDraft by mutableStateOf(false)
    private var brightnessGesturePreview by mutableStateOf<Int?>(null)
    private var brightnessRequestId by mutableStateOf("")
    private var brightnessGestureLayout: ReaderBrightnessGestureLayout? = null
    private var epubImageFilterMode by mutableStateOf(EpubImageFilterMode.ORIGINAL)
    private var epubImageFilterDraft by mutableStateOf(EpubImageFilterMode.ORIGINAL)
    private var epubImagesOpen by mutableStateOf(false)
    private var epubImagesFailed by mutableStateOf(false)
    private var bookThemeState by mutableStateOf(BookThemeState())
    private var bookFontSizeState by mutableStateOf(BookFontSizeState())
    private var bookFontState by mutableStateOf(BookFontState())
    private var bookMarginsState by mutableStateOf(BookMarginsState())
    private var bookLineHeightState by mutableStateOf(BookLineHeightState())
    private var bookSpacingState by mutableStateOf(BookSpacingState())
    private var bookTextAlignmentState by mutableStateOf(BookTextAlignmentState())
    private var bookReadingModeState by mutableStateOf(BookReadingModeState())
    private var bookAppearanceResetState by mutableStateOf(BookAppearanceResetState())
    private var bookAppearanceOpen by mutableStateOf(false)
    private var resetPending by mutableStateOf(false)
    private var resetRefreshFailed by mutableStateOf(false)
    private var selectionPreview by mutableStateOf<SelectedTextPreview?>(null)
    private var selectionTools by mutableStateOf<SelectedTextToolsState?>(null)
    private var selectionToolsJob: kotlinx.coroutines.Job? = null
    private var selectionToolsGeneration = 0L
    private var selectionExternalLaunch = false
    private var bookAssistantEpoch by mutableStateOf(0L)
    private val externalTextTools by lazy { AndroidSelectedTextTools(this) }
    private var selectionRequestGeneration = 0
    private var selectionCaptureJob: Job? = null
    private var selectionNotificationsJob: Job? = null
    private var selectionSurface: ReaderSurface? = null
    private var selectionCaptureSource: com.yaprak.reader.reader.TextSelectionSource? = null
    private var selectionBookIdentity: Pair<String, String>? = null
    private var annotationRequest by mutableStateOf<EpubAnnotationRequest?>(null)
    private var annotationSurface: ReaderSurface? = null
    private var pdfAnnotationRequest by mutableStateOf<com.yaprak.reader.feature.pdfannotations.PdfAnnotationRequest?>(null)
    private var pdfAnnotationsRequest by mutableStateOf<com.yaprak.reader.feature.pdfannotations.PdfAnnotationsWindow?>(null)
    private var pdfAnnotationSurface: ReaderSurface? = null
    private val annotationActive get() = annotationRequest != null || pdfAnnotationRequest != null || pdfAnnotationsRequest != null
    private var highlightStatus by mutableStateOf<EpubHighlightStatus?>(null)
    private var highlightRetryBusy by mutableStateOf(false)
    private var canCustomize by mutableStateOf(false)
    private var appliedSettings: EpubSettings? = null
    private var contentsNavigator by mutableStateOf<ContentsNavigator?>(null)
    private val contentsLoader by lazy { ContentsController(lifecycleScope) { open ->
        contentsOpen = open
        updateDuration()
    } }
    private var contentsOpen by mutableStateOf(false)
    private var bookmarkNavigator by mutableStateOf<BookmarkNavigator?>(null)
    private val jumpHistory = JumpHistory()
    private var canJumpBack by mutableStateOf(false)
    private var bookmarksOpen by mutableStateOf(false)
    private var savedWordsOpen by mutableStateOf(false)
    private var bookNotesOpen by mutableStateOf(false)
    private var passagesOpen by mutableStateOf(false)
    private var savedWordPrefill by mutableStateOf<String?>(null)
    private var translatedWordPrefill by mutableStateOf<com.yaprak.reader.feature.vocabulary.TranslatedWordPrefill?>(null)
    private var bookNotePrefill by mutableStateOf<String?>(null)
    private var passagePrefill by mutableStateOf<PassageDraft?>(null)
    private var passageNavigator by mutableStateOf<PassageNavigator?>(null)
    private var pageNavigator by mutableStateOf<PageNavigator?>(null)
    private var pageJumpOpen by mutableStateOf(false)
    private val pdfPageLinks by lazy { PdfPageLinksController(lifecycleScope) {
        updateDuration(); updatePdfThumbnailsEffects()
    } }
    private var pdfThumbnailsRequest by mutableStateOf<PdfThumbnailsRequest?>(null)
    private var pdfCropRequest by mutableStateOf<PdfCropRequest?>(null)
    private var pdfCropSavingRequest: PdfCropRequest? = null
    private var pdfCropApplyFailed by mutableStateOf(false)
    private var bookSearchOpen by mutableStateOf(false)
    private var bookSearchState by mutableStateOf(BookSearchState())
    private var bookSearch: BookTextSearch? = null
    private val bookSearchJobs = mutableListOf<Job>()
    private var bookSearchGeneration = 0
    private var surface: ReaderSurface? = null
    private var observation: Job? = null
    private var indexedChapterLaunch: IndexedChapterRequest? = null
    private var restoredIndexedChapter: IndexedChapterState? = null
    private var indexedChapterState by mutableStateOf(IndexedChapterState())
    private var indexedChapterForeground = false
    private var indexedChapterGeneration = 0
    private var indexedChapterJob: Job? = null
    private var indexedChapterAttempt: String? = null
    private var exiting = false
    private val durationOwner = java.util.UUID.randomUUID().toString()
    private val pace by lazy { ReaderPaceController(lifecycleScope, android.os.SystemClock::elapsedRealtime) }
    private var durationForeground = false
    private var durationFocused = false
    private var durationVisible = false
    private var durationSurface: ReaderSurface? = null
    private var durationBook: com.yaprak.reader.domain.library.Book? = null
    private var durationSession: com.yaprak.reader.reader.ReaderSession? = null
    private var durationVisibility: Job? = null
    private var durationFailure by mutableStateOf<com.yaprak.reader.domain.statistics.ReadingDurationFailure?>(null)
    private val contentId = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
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
        positionJump = BookPositionJumpController(lifecycleScope)
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
                    val readingTime by pace.state.collectAsState()
                    val pdfLinksState by pdfPageLinks.state.collectAsState()
                    val positionJumpState by positionJump.state.collectAsState()
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
                        readingTime = if (loadFailed || saveFailed || renderingFailed) null else readingTime)
                    ReaderOrientationDialog(orientationState, { choice -> orientationAction { orientation.edit(choice) } },
                        { orientationAction { orientation.save() } },
                        { orientation.dismiss(); if (!orientation.state.value.open && !annotationActive) observeSurface() },
                        { orientationAction { orientation.reload() } })
                    ReaderBrightnessDialog(brightnessOpen, brightnessDraft, ::editBrightness,
                        ::applyBrightness, ::dismissBrightness, brightnessGestureDraft, ::editBrightnessGesture, brightnessRequestId)
                    brightnessGesturePreview?.let { ReaderBrightnessGestureIndicator(it) }
                    EpubImageFilterDialog(epubImagesOpen, epubImageFilterDraft, ::editEpubImageFilter,
                        ::applyEpubImageFilter, ::dismissEpubImages, failed = epubImagesFailed)
                    BookPositionJumpDialog(positionJumpState, positionJump::edit,
                        ::submitBookPositionJump, ::dismissBookPositionJump)
                    TapZoneDialog(tapZoneState, tapZones::edit, tapZones::save,
                        { tapZones.dismiss(); if (!tapZones.state.value.open) observeSurface() }, tapZones::reload)
                    VolumeKeyDialog(volumeKeyState, volumeKeys::edit, volumeKeys::save,
                        { volumeKeys.dismiss(); if (!tapZones.state.value.open && !volumeKeys.state.value.open) observeSurface() }, volumeKeys::reload)
                    ScreenAwakeDialog(screenAwakeState, screenAwake::edit, screenAwake::save,
                        { screenAwake.dismiss(); observeSurface() }, screenAwake::reload)
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
                    SelectedTextDialog(selectedPreview, {
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
                                    translation.copy(expected) { text -> copyPlainSelectedText(this@ReaderActivity, text, "Yaprak Türkçe çeviri") } } },
                                onClose = { expected -> selectionAction { if (isTranslationCurrent(expected)) {
                                    translation.close(); translationState = null
                                } } },
                                onTransferWord = { expected -> selectionAction { transferTranslatedWord(expected) } })
                        }) else null)
                    annotationRequest?.let { request ->
                        EpubAnnotationRoute(request, (application as YaprakApplication).container.epubPassages,
                            canMutate = { canMutateAnnotation(request) },
                            onDismiss = { if (annotationRequest === request) { clearAnnotation(); observeSurface() } })
                    }
                    pdfAnnotationRequest?.let { request ->
                        com.yaprak.reader.feature.pdfannotations.PdfAnnotationRoute(request,
                            (application as YaprakApplication).container.pdfAnnotations,
                            { canMutatePdfAnnotations(request.bookId, request.hash) && pdfAnnotationRequest === request },
                            { if (pdfAnnotationRequest === request) { clearPdfAnnotations(); observeSurface() } })
                    }
                    pdfAnnotationsRequest?.let { request ->
                        com.yaprak.reader.feature.pdfannotations.PdfAnnotationsRoute(request,
                            (application as YaprakApplication).container.pdfAnnotations,
                            { canMutatePdfAnnotations(request.bookId, request.hash) && pdfAnnotationsRequest === request },
                            { page -> openPdfAnnotationPage(request, page) },
                            { if (pdfAnnotationsRequest === request) { clearPdfAnnotations(); observeSurface() } },
                            { text -> if (pdfAnnotationsRequest === request && canMutatePdfAnnotations(request.bookId, request.hash)) copyPlainSelectedText(this@ReaderActivity, text, "Yaprak PDF kaydı") })
                    }
                    if (canCustomize) EpubSettingsDialog(appearanceState, settings::edit, settings::save,
                        settings::dismiss, settings::reset, settings::reload)
                    if (canCustomize) BookAppearanceDialog(bookAppearanceOpen,
                        { bookAppearanceOpen = false; bookTheme.open() },
                        { bookAppearanceOpen = false; bookFontSize.open() },
                        { bookAppearanceOpen = false; bookFont.open() },
                        { bookAppearanceOpen = false; bookMargins.open() },
                        { bookAppearanceOpen = false; bookLineHeight.open() },
                        { bookAppearanceOpen = false; bookSpacing.open() },
                        { bookAppearanceOpen = false; resetPending = true; bookAppearanceReset.open() },
                        { bookAppearanceOpen = false; observeSurface() },
                        onTextAlignment = { bookAppearanceOpen = false; bookTextAlignment.open() },
                        onReadingMode = { bookAppearanceOpen = false; bookReadingMode.open() })
                    if (canCustomize) BookAppearanceResetDialog(bookAppearanceResetState,
                        bookAppearanceReset::confirm,
                        {
                            bookAppearanceReset.dismiss()
                            if (!bookAppearanceReset.state.value.open) {
                                resetPending = false
                                observeSurface()
                            }
                        })
                    if (canCustomize) BookAppearanceResetRefreshDialog(resetRefreshFailed,
                        appearanceState.loading || bookThemeState.loading || bookFontSizeState.loading || bookFontState.loading ||
                            bookMarginsState.loading || bookLineHeightState.loading || bookSpacingState.loading || bookTextAlignmentState.loading || bookReadingModeState.loading,
                        {
                            settings.reload()
                            bookTheme.reload()
                            bookFontSize.reload()
                            bookFont.reload()
                            bookMargins.reload()
                            bookLineHeight.reload()
                            bookSpacing.reload()
                            bookTextAlignment.reload()
                            bookReadingMode.reload()
                        }, ::leave)
                    if (canCustomize) BookThemeDialog(bookThemeState, bookTheme::edit, bookTheme::save,
                        { bookTheme.dismiss(); observeSurface() }, bookTheme::reload)
                    if (canCustomize) BookFontSizeDialog(bookFontSizeState, bookFontSize::edit, bookFontSize::save,
                        { bookFontSize.dismiss(); observeSurface() }, bookFontSize::reload,
                        appearanceState.saved.fontSizePercent)
                    if (canCustomize) BookFontDialog(bookFontState, bookFont::edit, bookFont::save,
                        { bookFont.dismiss(); observeSurface() }, bookFont::reload)
                    if (canCustomize) BookMarginsDialog(bookMarginsState, bookMargins::edit, bookMargins::save,
                        { bookMargins.dismiss(); observeSurface() }, bookMargins::reload,
                        appearanceState.saved.marginsPercent)
                    if (canCustomize) BookLineHeightDialog(bookLineHeightState, bookLineHeight::edit, bookLineHeight::save,
                        { bookLineHeight.dismiss(); observeSurface() }, bookLineHeight::reload,
                        appearanceState.saved.lineHeightPercent)
                    if (canCustomize) BookSpacingDialog(bookSpacingState, bookSpacing::edit, bookSpacing::save,
                        { bookSpacing.dismiss(); observeSurface() }, bookSpacing::reload, appearanceState.saved)
                    if (canCustomize) BookTextAlignmentDialog(bookTextAlignmentState, bookTextAlignment::edit,
                        bookTextAlignment::save, { bookTextAlignment.dismiss(); observeSurface() }, bookTextAlignment::reload)
                    if (canCustomize) BookReadingModeDialog(bookReadingModeState, bookReadingMode::edit,
                        bookReadingMode::save, { bookReadingMode.dismiss(); observeSurface() }, bookReadingMode::reload)
                    val contentsState by contentsLoader.state.collectAsState()
                    contentsState.request?.takeIf { contentsOpen }?.let { request ->
                        if (contentsLoader.isCurrent(request)) LoadingContentsDialog(contentsState,
                            ::selectContentsEntry, contentsLoader::retry, ::closeContents)
                        else SideEffect { closeContents(request) }
                    }
                    if (bookSearchOpen) BookSearchDialog(bookSearchState, ::changeBookSearchQuery,
                        ::runBookSearch, ::cancelBookSearch, ::selectBookSearchHit, ::dismissBookSearch,
                        kind = bookSearch?.kind ?: com.yaprak.reader.reader.BookSearchKind.EPUB)
                    val ready = uiState as? ReaderState.Ready
                    pageNavigator?.takeIf { pageJumpOpen }?.let { navigator ->
                        PageJumpDialog(navigator.pageCount, navigator.currentPage, ::selectPage, { pageJumpOpen = false })
                    }
                    pdfLinksState.request?.let { request ->
                        if (pdfPageLinks.isCurrent(request)) PdfPageLinksDialog(pdfLinksState,
                            pdfPageLinks::select, pdfPageLinks::retry, { pdfPageLinks.close(request) })
                        else SideEffect { pdfPageLinks.close(request) }
                    }
                    pdfThumbnailsRequest?.let { request ->
                        if (isPdfThumbnailsCurrent(request)) PdfThumbnailsDialog(
                            request.id, request.pageCount,
                            request.navigator.currentPage?.takeIf { it in 0 until request.pageCount },
                            render = { page -> renderPdfThumbnail(request, page) },
                            isCurrent = { isPdfThumbnailsCurrent(request) },
                            onGo = { page -> selectPdfThumbnail(request, page) },
                            onDismiss = { dismissPdfThumbnails(request) },
                            initialPage = request.currentPage,
                        ) else SideEffect { clearPdfThumbnails(request) }
                    }
                    pdfCropRequest?.let { request ->
                        if (isPdfCropCurrent(request)) PdfCropDialog(
                            request.id, request.saved, request.previewPage,
                            renderPreview = if (request.previewSource != null && request.previewPage != null)
                                ({ renderPdfCropPreview(request) }) else null,
                            isCurrent = { isPdfCropCurrent(request) },
                            onApply = { crop -> applyPdfCrop(request, crop) },
                            onDismiss = { dismissPdfCrop(request) },
                            preferenceState = pdfBookViewState,
                        ) else SideEffect { clearPdfCrop(request) }
                    }
                    if (bookmarksOpen && ready != null) bookmarkNavigator?.let { navigator ->
                        BookmarksRoute(ready.session.book, (application as YaprakApplication).container.bookmarks,
                            navigator, ::selectBookmark, { bookmarksOpen = false })
                    }
                    if (savedWordsOpen && ready != null) {
                        val incomingTranslation = translatedWordPrefill
                        if (incomingTranslation != null && !incomingTranslation.matches(ready.session.book)) SideEffect {
                            savedWordsOpen = false; translatedWordPrefill = null
                        } else {
                            SavedWordsRoute(ready.session.book, (application as YaprakApplication).container.savedWords,
                                { savedWordsOpen = false; savedWordPrefill = null; translatedWordPrefill = null; observeSurface() },
                                prefillEnglish = savedWordPrefill,
                                prefillTranslation = incomingTranslation?.draft,
                                onCopy = { text ->
                                    check(savedWordsOpen && !orientation.state.value.open && !annotationActive && !exiting && !loadFailed && !saveFailed &&
                                        uiState is ReaderState.Ready && surface != null &&
                                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                                    copyPlainSelectedText(this@ReaderActivity, text, label = "Yaprak kelime kaydı")
                                })
                        }
                    }
                    if (bookNotesOpen && ready != null) {
                        BookNotesRoute(ready.session.book, (application as YaprakApplication).container.bookNotes,
                            { bookNotesOpen = false; bookNotePrefill = null; observeSurface() },
                            prefillText = bookNotePrefill,
                            capturePdfPage = if (ready.session.book.format == BookFormat.PDF && pageNavigator != null) ::capturePdfNotePage else null,
                            onGoToPdfPage = if (ready.session.book.format == BookFormat.PDF && pageNavigator != null) ::selectPage else null,
                            onCopy = { text ->
                                check(bookNotesOpen && !orientation.state.value.open && !annotationActive && !exiting && !loadFailed && !saveFailed &&
                                    uiState is ReaderState.Ready && surface != null &&
                                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                                copyPlainSelectedText(this@ReaderActivity, text, label = "Yaprak notu")
                            })
                    }
                    if (passagesOpen && ready != null) {
                        EpubPassagesRoute(ready.session.book, (application as YaprakApplication).container.epubPassages,
                            { passagesOpen = false; passagePrefill = null; observeSurface() },
                            prefillText = passagePrefill?.text, prefillAnchor = passagePrefill?.anchor,
                            onOpenChapter = if (passageNavigator != null) ::selectPassageChapter else null,
                            onCopy = { text ->
                                check(!orientation.state.value.open && !annotationActive && !exiting && !loadFailed && uiState is ReaderState.Ready &&
                                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                                copyPlainSelectedText(this@ReaderActivity, text)
                            }, canMutate = { passagesOpen && isBrightnessReaderReady() &&
                                !orientation.state.value.open && !annotationActive &&
                                (uiState as? ReaderState.Ready)?.session === ready.session &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) })
                    }
                }
            }
        }, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        immersiveController.updateWindowFocus(window.decorView.hasWindowFocus())
        brightnessController.updateWindowFocus(window.decorView.hasWindowFocus())
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (annotationActive) Unit // The dialog owns cancel and blocks back during its admitted write.
                else if (orientation.state.value.open) orientation.dismiss()
                else if (epubImagesOpen) dismissEpubImages()
                else if (brightnessOpen) dismissBrightness()
                else if (positionJump.state.value.open) dismissBookPositionJump()
                else if (bookSearchOpen) dismissBookSearch() else leave()
            }
        })
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
                    saveFailed = failed
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
                    if (localReset.saving || localReset.completed) resetPending = true
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
                                pdfBookView.deactivate()
                                comicBookView.deactivate()
                                updateDuration()
                                updateScreenAwake()
                                jumpHistory.clear()
                                canJumpBack = false
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
                        bindComicSpread(created)
                        bindSelectionNotifications(created)
                        bindDurationVisibility(created)
                        created.epubImageFilter?.apply(epubImageFilterMode)
                        renderingFailed = false
                        applyPdfNightMode()
                        updateScreenAwake()
                        jumpHistory.clear()
                        canJumpBack = false
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
                        observeSurface()
                    }
                    bindPdfBookView()
                    val appearance = surface?.appearance
                    if (resetPending && localReset.completed &&
                        (preferences.loadFailed || localTheme.loadFailed || localFont.loadFailed || localFamily.loadFailed ||
                            localMargins.loadFailed || localLineHeight.loadFailed || localSpacing.loadFailed || localAlignment.loadFailed || localMode.loadFailed)) {
                        resetRefreshFailed = true
                    }
                    val resetFinished = resetPending && appearanceResetObserved(localReset, localTheme,
                        localFont, localFamily, localMargins, localLineHeight, localSpacing, localAlignment, localMode)
                    if (resetPending && !resetFinished) return@collect
                    if (appearance != null && !preferences.loading && !preferences.loadFailed &&
                        !localTheme.loading && !localTheme.loadFailed && !localFont.loading && !localFont.loadFailed &&
                        !localFamily.loading && !localFamily.loadFailed && !localMargins.loading && !localMargins.loadFailed &&
                        !localLineHeight.loading && !localLineHeight.loadFailed && !localSpacing.loading && !localSpacing.loadFailed &&
                        !localAlignment.loading && !localAlignment.loadFailed && !localMode.loading && !localMode.loadFailed) {
                        val effective = preferences.saved.withBookTheme(localTheme.saved)
                            .withBookFontSize(localFont.saved).withBookFont(localFamily.saved)
                            .withBookMargins(localMargins.saved).withBookLineHeight(localLineHeight.saved)
                            .withBookSpacing(localSpacing.saved).withBookTextAlignment(localAlignment.saved).withBookReadingMode(localMode.saved)
                        val settingsChanged = appliedSettings != effective
                        if (settingsChanged) {
                            // Cancel sampling across a reflow. Start again with the new Navigator locator.
                            clearSelectionPreview()
                            clearAnnotation()
                            observation?.cancel()
                            try {
                                appearance.apply(effective)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                if (resetPending) {
                                    resetRefreshFailed = true
                                    return@collect
                                }
                                throw failure
                            }
                            appliedSettings = effective
                        }
                        if (resetFinished) {
                            bookAppearanceReset.ackApplied()
                            resetPending = false
                            resetRefreshFailed = false
                        }
                        if (settingsChanged || resetFinished) observeSurface()
                    }
                    startIndexedChapter()
                }
            }
        }
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
        if (bookSearchJobs.any { it.isActive }) cancelBookSearch()
        positionJump.cancel()
        clearSelectionPreview()
        clearAnnotation()
        pdfPageLinks.close()
        closeContents()
        clearPdfThumbnails()
        clearPdfCrop()
        super.onStop()
    }

    override fun onPause() {
        bookAssistantEpoch++
        volumeKeyController.reset(); readerTouchActive = false
        if (bookSearchJobs.any { it.isActive }) cancelBookSearch()
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
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
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

    private fun indexedChapterToolsAvailable(): Boolean = indexedChapterForeground && !exiting &&
        !loadFailed && !saveFailed && !model.saveFailed.value &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !annotationActive && selectionPreview == null && !hasCompetingSelectionModal()

    private fun isIndexedChapterCurrent(request: IndexedChapterRequest, attempt: String, generation: Int,
        book: Book, owner: ReaderSurface, navigator: Any): Boolean =
        indexedChapterToolsAvailable() && indexedChapterGeneration == generation &&
            indexedChapterAttempt == attempt && model.indexedChapterOpen.isVerifying(request, attempt) &&
            (uiState as? ReaderState.Ready)?.session?.book == book && surface === owner &&
            indexedNavigator(owner, request.target.sourceFormat) === navigator && book.id == request.target.bookId &&
            book.sha256 == request.target.contentSha256 && book.format == request.target.sourceFormat

    private fun indexedNavigator(owner: ReaderSurface, format: BookFormat): Any? = when (format) {
        BookFormat.EPUB -> owner.indexedChapterNavigator
        BookFormat.PDF -> owner.indexedPdfPageNavigator
        BookFormat.TXT, BookFormat.FB2, BookFormat.DOCX -> owner.indexedChapterNavigator
        BookFormat.CBZ, BookFormat.CBR -> null
    }

    private fun startIndexedChapter() {
        val open = model.indexedChapterOpen
        val state = open.state.value
        if (state.phase != IndexedChapterPhase.PENDING || !indexedChapterToolsAvailable()) return
        val request = state.request ?: return
        val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
        val owner = surface ?: return
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
                jumpHistory.record(source, accepted && !loadFailed && surface === owner &&
                    owner.bookmarkNavigator === bookmarks)
                canJumpBack = jumpHistory.canReturn
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

    private fun retryIndexedChapter(expected: IndexedChapterState): Boolean {
        if (!indexedChapterToolsAvailable() || uiState !is ReaderState.Ready || surface == null) return false
        return model.indexedChapterOpen.retry(expected)
    }

    private fun cancelIndexedChapter() {
        indexedChapterForeground = false
        indexedChapterGeneration++
        val attempt = indexedChapterAttempt
        indexedChapterAttempt = null
        indexedChapterJob?.cancel()
        indexedChapterJob = null
        attempt?.let { model.indexedChapterOpen.cancel(it) }
    }

    private fun canUsePdfSurface(candidate: ReaderSurface): Boolean =
        (uiState as? ReaderState.Ready)?.session?.book?.format == BookFormat.PDF && canUsePageSurface(candidate)

    private fun bindComicSpread(owner: ReaderSurface) {
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

    private fun toggleComicSpread(owner: ReaderSurface, expected: Boolean) {
        toggleComicSpread(owner, expected, comicBookView.state.value)
    }

    private fun toggleComicSpread(owner: ReaderSurface, expected: Boolean, lease: ComicBookViewPreferencesState) {
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

    private fun bindComicBookView() {
        if (comicBookViewSurface !== surface) { comicBookView.deactivate(); comicBookViewSurface = surface }
        val book = (uiState as? ReaderState.Ready)?.session?.book
        if (book?.format?.comic == true && surface != null && !exiting && !loadFailed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) comicBookView.bind(book)
        else if (comicBookView.state.value.status != ComicBookViewStatus.INACTIVE) comicBookView.deactivate()
    }

    private fun applyComicBookView(): Boolean {
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

    private fun retryComicBookView(owner: ReaderSurface, expected: ComicBookViewPreferencesState) {
        if (!chrome || !canUsePageSurface(owner) || !window.decorView.hasWindowFocus() ||
            comicBookView.state.value != expected || !expected.failed) return
        lifecycleScope.launch { comicBookView.retry(); applyComicBookView() }
    }

    private fun comicBookViewSettled(): Boolean =
        (uiState as? ReaderState.Ready)?.session?.book?.format?.comic != true ||
            comicBookView.state.value.status == ComicBookViewStatus.READY

    private fun canUsePageSurface(candidate: ReaderSurface): Boolean =
        (uiState as? ReaderState.Ready)?.session?.book?.format in setOf(BookFormat.PDF, BookFormat.CBZ, BookFormat.CBR) &&
            surface === candidate && !exiting && !loadFailed && !saveFailed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !epubImagesOpen && !brightnessOpen && selectionPreview == null && !resetPending && !resetRefreshFailed &&
            !settings.state.value.open && !screenAwake.state.value.open && !tapZones.state.value.open && !volumeKeys.state.value.open && !orientation.state.value.open && !annotationActive && !bookAppearanceOpen &&
            !bookAppearanceReset.state.value.open && !bookTheme.state.value.open &&
            !bookFontSize.state.value.open && !bookFont.state.value.open &&
            !bookMargins.state.value.open && !bookLineHeight.state.value.open && !bookSpacing.state.value.open && !bookTextAlignment.state.value.open && !bookReadingMode.state.value.open &&
            !contentsOpen && !bookSearchOpen && !bookmarksOpen && !savedWordsOpen &&
            !bookNotesOpen && !passagesOpen && !pageJumpOpen && pdfThumbnailsRequest == null && !pdfPageLinks.state.value.open && pdfCropRequest == null && !positionJump.state.value.open

    private fun fitPdf(width: Boolean) {
        fitPdf(surface, pdfBookView.state.value, width)
    }

    private fun fitPdf(expectedOwner: ReaderSurface?, expected: PdfBookViewPreferencesState, width: Boolean) {
        val active = surface ?: return
        if (active !== expectedOwner || !canUsePdfSurface(active) || active.pdfView == null ||
            pdfBookView.state.value != expected || !expected.canEdit) return
        lifecycleScope.launch {
            pdfBookView.saveFit(expected, if (width) PdfFitPreference.WIDTH else PdfFitPreference.PAGE)
            if (surface === active) applyPdfBookView()
        }
    }

    private fun changePdfNavigation(expectedOwner: ReaderSurface?, source: com.yaprak.reader.reader.PdfScrollController,
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

    private fun togglePdfScroll(expectedOwner: ReaderSurface?, source: com.yaprak.reader.reader.PdfScrollController,
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

    private fun spreadFor(book: Book): Boolean {
        val current = pdfBookView.state.value
        return if (current.owner?.bookId == book.id && current.owner?.contentSha256 == book.sha256)
            current.applied?.doublePagePreferred ?: false else false
    }

    private fun togglePdfSpread(book: Book, owner: ReaderSurface, expected: Boolean) {
        togglePdfSpread(book, owner, expected, pdfBookView.state.value)
    }

    private fun togglePdfSpread(book: Book, owner: ReaderSurface, expected: Boolean,
        lease: PdfBookViewPreferencesState) {
        if (!chrome || !canUsePdfSurface(owner) || (uiState as? ReaderState.Ready)?.session?.book != book ||
            expected != spreadFor(book) || pdfBookView.state.value != lease || !lease.canEdit ||
            owner.pdfSpread == null || owner.pdfScroll?.enabled?.value == true) return
        lifecycleScope.launch {
            pdfBookView.saveSpread(lease, !expected)
            if (surface === owner) applyPdfBookView()
        }
    }

    private fun bindPdfBookView() {
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

    private fun clearPdfNavigationResumeObserver() {
        pdfNavigationResumeObserver?.let { pdfBookViewSurface?.fragment?.lifecycle?.removeObserver(it) }
        pdfNavigationResumeObserver = null
    }

    private fun applyPdfBookView(): Boolean {
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

    private fun retryPdfBookView(expected: PdfBookViewPreferencesState) {
        val owner = surface ?: return
        if (pdfBookView.state.value != expected || !expected.failed || !canUsePdfSurface(owner)) return
        lifecycleScope.launch { pdfBookView.retry(); applyPdfBookView() }
    }

    private fun togglePdfNightMode() {
        if (tapZones.state.value.open || volumeKeys.state.value.open || orientation.state.value.open || annotationActive || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null) return
        val ready = uiState as? ReaderState.Ready ?: return
        if (ready.session.book.format != BookFormat.PDF || exiting || loadFailed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (surface?.pdfNightMode == null || !pdfNightMode.state.value.canToggle) return
        pdfNightMode.toggle()
        applyPdfNightMode()
    }

    private fun retryPdfNightMode() {
        if (tapZones.state.value.open || volumeKeys.state.value.open || orientation.state.value.open || annotationActive || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null) return
        val ready = uiState as? ReaderState.Ready ?: return
        if (ready.session.book.format != BookFormat.PDF || exiting || loadFailed ||
            surface?.pdfNightMode == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        pdfNightMode.retry()
    }

    private fun applyPdfNightMode() {
        pdfNightBinding.update(surface, (uiState as? ReaderState.Ready)?.session?.book?.format,
            pdfNightMode.state.value,
            readerReady = uiState is ReaderState.Ready && !exiting && !loadFailed &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    private fun updateScreenAwake() {
        screenAwakeController.update(
            enabled = screenAwakeState.saved && !screenAwakeState.loading && !screenAwakeState.loadFailed,
            readerReady = uiState is ReaderState.Ready && surface != null && !loadFailed && !exiting,
        )
        if (::orientationController.isInitialized) updateOrientation()
        if (::immersiveController.isInitialized) updateImmersive()
        if (::brightnessController.isInitialized) updateBrightness()
    }

    private fun updateOrientation() {
        orientationController.update(orientationState.saved,
            preferenceReady = !orientationState.loading && !orientationState.loadFailed && !orientationState.saving,
            readerReady = isBrightnessReaderReady())
    }

    private fun readerAction(action: () -> Unit) {
        if (!tapZones.state.value.open && !volumeKeys.state.value.open && !orientation.state.value.open && selectionPreview == null && !annotationActive && pdfThumbnailsRequest == null && !pdfPageLinks.state.value.open && pdfCropRequest == null) {
            action(); updateDuration()
        }
    }

    private fun installVolumeKeyDispatch() {
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
    }

    override fun onActionModeFinished(mode: ActionMode) {
        if (nativeActionMode === mode) nativeActionMode = null
        super.onActionModeFinished(mode)
    }

    private fun openReaderControls() {
        if (!isBrightnessReaderReady() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            selectionExternalLaunch || selectionPreview != null || annotationActive || hasCompetingSelectionModal() ||
            indexedChapterState.phase in setOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.FAILED)) return
        observation?.cancel()
        volumeKeys.open()
        updateDuration()
    }

    private fun openTapZones() {
        if (surface?.taps == null || !isBrightnessReaderReady() ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || selectionExternalLaunch ||
            selectionPreview != null || annotationActive || hasCompetingSelectionModal() ||
            indexedChapterState.phase in setOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.FAILED)) return
        observation?.cancel()
        tapZones.open()
        updateDuration()
    }

    private fun handleCustomTap(owner: com.yaprak.reader.reader.ReaderSurface, tap: ReaderTap): Boolean {
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
        if (chrome) {
            chrome = false
            observeSurface(); updateDuration()
            return true
        }
        when (preference.saved.action(zone)) {
            TapZoneAction.MENU -> { chrome = true; observeSurface(); updateDuration() }
            TapZoneAction.PREVIOUS -> navigateWithReadingInput { owner.backward() }
            TapZoneAction.NEXT -> navigateWithReadingInput { owner.forward() }
            TapZoneAction.NONE -> Unit
        }
        return true
    }

    private fun canTurnWithVolumeKey(): Boolean {
        val preference = volumeKeys.state.value
        return preference.saved && !preference.loading && !preference.loadFailed && !preference.saving &&
            canNavigateWithReadingInput(requireHiddenChrome = true)
    }

    private fun canNavigateWithReadingInput(requireHiddenChrome: Boolean): Boolean {
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

    private fun turnWithVolumeKey(direction: ReaderVolumeKeyController.Direction) {
        if (!canTurnWithVolumeKey()) return
        val active = surface ?: return
        navigateWithReadingInput {
            when (direction) {
                ReaderVolumeKeyController.Direction.PREVIOUS -> active.backward()
                ReaderVolumeKeyController.Direction.NEXT -> active.forward()
            }
        }
    }

    private fun navigateWithReadingInput(action: () -> Boolean) {
        observation?.cancel()
        try { action() } finally {
            observeSurface()
            updateDuration()
        }
    }

    private fun orientationAction(action: () -> Unit) {
        if (orientation.state.value.open && isBrightnessReaderReady() &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action()
    }

    private fun openOrientation() {
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

    private fun retryOrientation() {
        if (!isBrightnessReaderReady() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            orientation.state.value.open || annotationActive || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null) return
        updateOrientation()
        orientationController.retry()
    }

    private fun isBrightnessReaderReady(): Boolean =
        uiState is ReaderState.Ready && surface != null && !loadFailed && !saveFailed &&
            !renderingFailed && !exiting

    private fun updateBrightness() {
        brightnessController.update(brightnessGesturePreview ?: brightnessPercent, readerReady = isBrightnessReaderReady())
    }

    private fun canGestureBrightness(): Boolean = brightnessGesturesEnabled && brightnessPercent != null &&
        !chrome && isBrightnessReaderReady() && window.decorView.hasWindowFocus() &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !selectionExternalLaunch &&
        selectionPreview == null && !annotationActive && !hasCompetingSelectionModal() &&
        indexedChapterState.phase !in setOf(IndexedChapterPhase.VERIFYING, IndexedChapterPhase.FAILED) &&
        durationFailure == null && !pdfCropApplyFailed

    private fun previewGestureBrightness(percent: Int) {
        if (!canGestureBrightness() || percent !in 10..100) return
        brightnessGesturePreview = percent
        updateBrightness(); updateDuration()
    }
    private fun commitGestureBrightness(percent: Int) {
        if (canGestureBrightness() && brightnessGesturePreview != null && percent in 10..100) brightnessPercent = percent
        brightnessGesturePreview = null
        updateBrightness(); updateDuration()
    }
    private fun cancelGestureBrightness() {
        brightnessGesturePreview = null
        if (::brightnessController.isInitialized) updateBrightness()
        updateDuration()
    }

    private fun openBrightness() {
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

    private fun editBrightness(percent: Int?) {
        if (brightnessOpen && (percent == null || percent in 10..100)) {
            brightnessDraft = percent
            if (percent == null) brightnessGestureDraft = false
        }
    }

    private fun editBrightnessGesture(enabled: Boolean) {
        if (brightnessOpen && brightnessDraft != null) brightnessGestureDraft = enabled
    }

    private fun applyBrightness(percent: Int?) {
        if (!brightnessOpen || (percent != null && percent !in 10..100)) return
        if (isBrightnessReaderReady() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            brightnessPercent = percent
            brightnessGesturesEnabled = percent != null && brightnessGestureDraft
        }
        dismissBrightness()
        updateBrightness()
    }

    private fun dismissBrightness() {
        brightnessOpen = false
        brightnessDraft = null
        brightnessGestureDraft = false
    }

    private fun canSetEpubImageFilter(): Boolean =
        isBrightnessReaderReady() &&
            (uiState as? ReaderState.Ready)?.session?.book?.format == BookFormat.EPUB &&
            surface?.epubImageFilter != null

    private fun openEpubImages() {
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

    private fun editEpubImageFilter(mode: EpubImageFilterMode) {
        if (epubImagesOpen) {
            epubImageFilterDraft = mode
            epubImagesFailed = false
        }
    }

    private fun applyEpubImageFilter(mode: EpubImageFilterMode) {
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

    private fun dismissEpubImages() {
        epubImagesOpen = false
        epubImageFilterDraft = EpubImageFilterMode.ORIGINAL
        epubImagesFailed = false
    }

    private fun updateImmersive() {
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

    private fun observeSurface() {
        observation?.cancel()
        val active = surface ?: return
        observation = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { active.pageLabel.collect { pageLabel = it } }
                launch { active.renderingFailed.collect {
                    renderingFailed = it
                    updateDuration()
                    updateImmersive()
                    updateBrightness()
                    updateOrientation()
                } }
                launch { active.bookPositionPercent.collect { bookPositionPercent = it } }
                active.epubHighlights?.let { highlights -> launch {
                    highlights.status.collect { if (surface === active) highlightStatus = it }
                } } ?: run { highlightStatus = null }
                if (!loadFailed) active.observe({ !selectionExternalLaunch && brightnessGesturePreview == null && !chrome && !pdfBookView.state.value.failed && comicBookViewSettled() && !saveFailed && !loadFailed && !epubImagesOpen && !brightnessOpen && selectionPreview == null && !resetPending && !settings.state.value.open && !screenAwake.state.value.open && !tapZones.state.value.open && !volumeKeys.state.value.open && !orientation.state.value.open && !annotationActive && !bookAppearanceOpen && !bookAppearanceReset.state.value.open && !bookTheme.state.value.open && !bookFontSize.state.value.open && !bookFont.state.value.open && !bookMargins.state.value.open && !bookLineHeight.state.value.open && !bookSpacing.state.value.open && !bookTextAlignment.state.value.open && !bookReadingMode.state.value.open && !contentsOpen && !bookSearchOpen && !bookmarksOpen && !savedWordsOpen && !bookNotesOpen && !passagesOpen && !pageJumpOpen && pdfThumbnailsRequest == null && !pdfPageLinks.state.value.open && pdfCropRequest == null && !positionJump.state.value.open }, model::observe)
            }
        }
    }

    /** Independent of locator subscriptions: timing cannot replay or manufacture reading progress. */
    private fun bindDurationVisibility(active: ReaderSurface) {
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

    private fun updateDuration() {
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

    private fun searchSelectedText(expected: SelectedTextPreview.Ready, owner: ReaderSurface, source: BookTextSearch) {
        if (!isSelectionCurrent() || selectionPreview !== expected || surface !== owner ||
            bookSearch !== source || !source.supported || bookSearchOpen) return
        val draft = selectedBookSearchDraft(expected.text) ?: return
        bookSearchGeneration++
        stopBookSearchJobs()
        clearSelectionPreview()
        if (surface !== owner || bookSearch !== source || exiting || loadFailed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        observation?.cancel()
        bookSearchState = draft
        bookSearchOpen = true
        updateDuration()
    }

    private fun openBookAssistant(action: ReaderBookAssistantAction) {
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

    private fun failedBookAssistantLaunch() {
        selectionExternalLaunch = false
        bookAssistantEpoch++
        observeSurface()
        updateDuration()
        android.widget.Toast.makeText(this, R.string.reader_book_assistant_open_failed, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun openBookSearch() {
        if (tapZones.state.value.open || volumeKeys.state.value.open || orientation.state.value.open || annotationActive || (pdfThumbnailsRequest != null || pdfPageLinks.state.value.open) || pdfCropRequest != null || epubImagesOpen || brightnessOpen || positionJump.state.value.open) return
        if (bookSearch == null || exiting || loadFailed || uiState !is ReaderState.Ready ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        observation?.cancel()
        bookSearchState = BookSearchState(phase = if (bookSearch?.supported == false)
            BookSearchPhase.Unsupported else BookSearchPhase.Idle)
        bookSearchOpen = true
        updateDuration()
    }

    private fun canNavigateBookPosition(navigator: BookPositionNavigator): Boolean =
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

    private fun openBookPositionJump() {
        val navigator = surface?.bookPositionNavigator ?: return
        if (positionJump.state.value.open || !canNavigateBookPosition(navigator)) return
        observation?.cancel()
        positionJump.open(navigator, bookPositionPercent)
        updateDuration()
    }

    private fun submitBookPositionJump() {
        positionJump.submit(::canNavigateBookPosition) { navigator, target ->
            if (!canNavigateBookPosition(navigator)) false
            else navigateJump { navigator.goTo(target) }
        }
    }

    private fun dismissBookPositionJump() {
        positionJump.dismiss()
        observeSurface()
    }

    private fun changeBookSearchQuery(value: String) {
        val limited = limitBookSearchQuery(value)
        if (limited == bookSearchState.query) return
        stopBookSearchJobs()
        bookSearchGeneration++
        bookSearchState = BookSearchState(query = limited, phase = if (bookSearch?.supported == false)
            BookSearchPhase.Unsupported else BookSearchPhase.Idle)
    }

    private fun runBookSearch() {
        if (orientation.state.value.open || annotationActive) return
        val source = bookSearch ?: return
        if (!bookSearchOpen || exiting || loadFailed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!source.supported) {
            bookSearchState = bookSearchState.copy(phase = BookSearchPhase.Unsupported, navigationFailed = false)
            return
        }
        val query = bookSearchState.query.trim()
        if (query.isEmpty()) return
        stopBookSearchJobs()
        val generation = ++bookSearchGeneration
        bookSearchState = bookSearchState.copy(phase = BookSearchPhase.Searching, navigationFailed = false)
        val job = lifecycleScope.launch {
            val phase = try {
                // Each format serializes its search; PDF text shares the renderer's actual page lease.
                BookSearchPhase.Ready(source.find(query))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: com.yaprak.reader.reader.BookSearchException) {
                when (failure.reason) {
                    com.yaprak.reader.reader.BookSearchFailure.UNSUPPORTED -> BookSearchPhase.Unsupported
                    com.yaprak.reader.reader.BookSearchFailure.LIMIT -> BookSearchPhase.Limit
                    else -> BookSearchPhase.Failed
                }
            } catch (_: Exception) {
                BookSearchPhase.Failed
            }
            if (generation == bookSearchGeneration && bookSearchOpen && bookSearch === source && !exiting) {
                bookSearchState = bookSearchState.copy(phase = phase)
            }
        }
        bookSearchJobs.removeAll { it.isCompleted }
        bookSearchJobs += job
    }

    /** Keep every unfinished job so exit waits for an older cancelled iterator as well. */
    private fun stopBookSearchJobs(): List<Job> {
        bookSearchJobs.removeAll { it.isCompleted }
        return bookSearchJobs.toList().also { jobs -> jobs.forEach { it.cancel() } }
    }

    private fun cancelBookSearch() {
        stopBookSearchJobs()
        bookSearchGeneration++
        bookSearchState = bookSearchState.copy(phase = BookSearchPhase.Idle, navigationFailed = false)
    }

    private fun dismissBookSearch() {
        cancelBookSearch()
        bookSearchState = BookSearchState()
        bookSearchOpen = false
        observeSurface()
    }

    private fun selectBookSearchHit(hit: BookSearchHit) {
        if (orientation.state.value.open || annotationActive) return
        if (!bookSearchOpen || exiting || loadFailed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val results = (bookSearchState.phase as? BookSearchPhase.Ready)?.results ?: return
        if (results.hits.none { it === hit }) return
        val source = bookSearch ?: return
        if (navigateJump { source.goTo(hit.location) }) dismissBookSearch()
        else bookSearchState = bookSearchState.copy(navigationFailed = true)
    }

    private fun openContents() {
        if (exiting || loadFailed || saveFailed || uiState !is ReaderState.Ready ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val source = contentsNavigator ?: return
        val owner = surface ?: return
        contentsLoader.open(source) {
            surface === owner && contentsNavigator === source && !exiting && !loadFailed && !saveFailed &&
                uiState is ReaderState.Ready && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }

    private fun closeContents(expected: ContentsRequest? = null) {
        if (expected != null && contentsLoader.state.value.request !== expected) return
        contentsOpen = false
        contentsLoader.close(expected)
    }

    private fun selectContentsEntry(expected: ContentsState, id: String): Boolean {
        if (!contentsLoader.canSelect(expected, id)) return false
        val navigator = contentsNavigator ?: return false
        if (expected.request?.source !== navigator) return false
        return navigateJump { navigator.goTo(id) }
    }

    private fun leave() {
        if (orientation.state.value.open || annotationActive) return
        if (exiting) return
        exiting = true
        pdfBookView.deactivate()
        model.suspendDuration(durationOwner)
        updateScreenAwake()
        val pendingSearches = stopBookSearchJobs() + positionJump.stopJobs()
        bookSearchGeneration++
        clearSelectionPreview()
        observation?.cancel()
        lifecycleScope.launch {
            pendingSearches.joinAll()
            if (model.flush()) finish()
            else { chrome = true; exiting = false; bindPdfBookView(); updateScreenAwake() }
        }
    }

    private fun leaveUnsaved() {
        if (orientation.state.value.open || annotationActive) return
        if (exiting) return
        exiting = true
        pdfBookView.deactivate()
        model.suspendDuration(durationOwner)
        updateScreenAwake()
        val pendingSearches = stopBookSearchJobs() + positionJump.stopJobs()
        bookSearchGeneration++
        clearSelectionPreview()
        observation?.cancel()
        lifecycleScope.launch {
            pendingSearches.joinAll()
            finish()
        }
    }

    private fun selectBookmark(location: ReadingLocation): Boolean {
        val navigator = bookmarkNavigator ?: return false
        return navigateJump { navigator.goTo(location) }
    }

    private suspend fun selectPassageChapter(passage: EpubPassage): Boolean {
        if (orientation.state.value.open || annotationActive) return false
        val navigator = passageNavigator ?: return false
        if (!passagesOpen || exiting || loadFailed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val href = navigator.verifiedChapterHref(passage) ?: return false
        if (passageNavigator !== navigator || surface?.passageNavigator !== navigator || !passagesOpen) return false
        return navigateJump { navigator.goToChapter(href) }
    }

    private fun selectPage(pageIndex: Int): Boolean {
        val navigator = pageNavigator ?: return false
        return navigateJump { navigator.goTo(pageIndex) }
    }

    private fun cropFor(book: Book): PdfPageCrop {
        val current = pdfBookView.state.value
        val preference = current.preferences?.takeIf {
            current.owner?.bookId == book.id && current.owner?.contentSha256 == book.sha256
        } ?: return PdfPageCrop()
        return PdfPageCrop(preference.horizontalPercent, preference.verticalPercent)
    }

    private fun openPdfCrop() {
        openPdfCrop(surface, pdfBookView.state.value)
    }

    private fun openPdfCrop(expectedOwner: ReaderSurface?, expected: PdfBookViewPreferencesState) {
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

    private fun isPdfCropCurrent(request: PdfCropRequest): Boolean =
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

    private suspend fun renderPdfCropPreview(request: PdfCropRequest): Bitmap {
        if (!isPdfCropCurrent(request)) throw CancellationException("Obsolete PDF crop preview")
        val source = checkNotNull(request.previewSource)
        val page = checkNotNull(request.previewPage)
        val image = source.render(page)
        currentCoroutineContext().ensureActive()
        if (!isPdfCropCurrent(request)) throw CancellationException("Obsolete PDF crop image")
        return image
    }

    private suspend fun applyPdfCrop(request: PdfCropRequest, crop: PdfPageCrop): Boolean {
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

    private fun dismissPdfCrop(request: PdfCropRequest) {
        if (isPdfCropCurrent(request)) clearPdfCrop(request)
    }

    private fun clearPdfCrop(expected: PdfCropRequest? = null) {
        if (expected == null || pdfCropRequest === expected) {
            pdfCropRequest = null
            updatePdfThumbnailsEffects()
        }
    }

    private fun openPdfPageLinks(owner: ReaderSurface, page: Int) {
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

    private fun openPdfThumbnails() {
        val owner = surface ?: return
        if (!canUsePageSurface(owner)) return
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

    private fun isPdfThumbnailsCurrent(request: PdfThumbnailsRequest): Boolean =
        pdfThumbnailsRequest === request && pdfThumbnailsRequest?.id == request.id &&
            (uiState as? ReaderState.Ready)?.session?.book == request.book &&
            request.book.format in setOf(BookFormat.PDF, BookFormat.CBZ, BookFormat.CBR) && surface === request.surface &&
            surface?.pageThumbnails === request.source && pageNavigator === request.navigator &&
            surface?.pageNavigator === request.navigator && request.pageCount > 0 &&
            request.source.pageCount == request.pageCount && request.navigator.pageCount == request.pageCount &&
            !exiting && !loadFailed && !saveFailed && !model.saveFailed.value &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !annotationActive && selectionPreview == null && !hasCompetingSelectionModal(includeThumbnails = false)

    private suspend fun renderPdfThumbnail(request: PdfThumbnailsRequest, pageIndex: Int): Bitmap {
        if (!isPdfThumbnailsCurrent(request)) throw CancellationException("Obsolete PDF thumbnail request")
        require(pageIndex in 0 until request.pageCount)
        val image = request.source.render(pageIndex)
        currentCoroutineContext().ensureActive()
        if (!isPdfThumbnailsCurrent(request)) throw CancellationException("Obsolete PDF thumbnail result")
        // The source owns shared images. Rejecting delivery must never recycle a cached bitmap.
        return image
    }

    private fun selectPdfThumbnail(request: PdfThumbnailsRequest, pageIndex: Int): Boolean {
        if (!isPdfThumbnailsCurrent(request) || pageIndex !in 0 until request.pageCount) return false
        val accepted = navigateJump({ request.navigator.goTo(pageIndex) }, request)
        if (accepted && pdfThumbnailsRequest === request) {
            clearPdfThumbnails(request)
        }
        return accepted
    }

    private fun dismissPdfThumbnails(request: PdfThumbnailsRequest) {
        if (!isPdfThumbnailsCurrent(request)) return
        clearPdfThumbnails(request)
    }

    private fun clearPdfThumbnails(expected: PdfThumbnailsRequest? = null) {
        if (expected == null || pdfThumbnailsRequest === expected) {
            pdfThumbnailsRequest = null
            updatePdfThumbnailsEffects()
        }
    }

    private fun updatePdfThumbnailsEffects() {
        // Coverage/swipe predicates read the live modal state. Resubscribing to a PDF's
        // rendered-page StateFlow would replay its old page and write reading state again.
        if (::immersiveController.isInitialized) updateImmersive()
        if (::brightnessController.isInitialized) updateBrightness()
    }

    private fun capturePdfNotePage(): Int? {
        if (orientation.state.value.open || annotationActive) return null
        if (exiting || loadFailed || !bookNotesOpen ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
        return pageNavigator?.captureStablePage()
    }

    private fun navigate(request: () -> Boolean): Boolean = navigate(request, null)

    private fun navigate(request: () -> Boolean, thumbnails: PdfThumbnailsRequest?, pageLinks: PdfPageLinksRequest? = null): Boolean {
        if (thumbnails != null && !isPdfThumbnailsCurrent(thumbnails)) return false
        if (pageLinks != null && !pdfPageLinks.isCurrent(pageLinks)) return false
        if (pdfCropRequest != null || (pdfThumbnailsRequest != null && thumbnails == null) ||
            (pdfPageLinks.state.value.open && pageLinks == null)) return false
        if (tapZones.state.value.open || volumeKeys.state.value.open || orientation.state.value.open || selectionPreview != null || annotationActive || exiting || loadFailed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        // Navigation requests never fabricate progress or coverage; observe the real destination.
        clearSelectionPreview()
        if (thumbnails == null) observation?.cancel()
        val accepted = request()
        chrome = true
        updateDuration()
        if (thumbnails == null) observeSurface()
        else {
            updatePdfThumbnailsEffects()
            // Normally the browser keeps its existing collector. If an earlier tool had
            // stopped observation, accepted navigation must still observe the real render.
            if (accepted && observation?.isActive != true) observeSurface()
        }
        return accepted
    }

    /** Preserve only deliberate jumps; ordinary next/previous page turns do not add history. */
    private fun navigateJump(request: () -> Boolean): Boolean = navigateJump(request, null)

    private fun navigateJump(request: () -> Boolean, thumbnails: PdfThumbnailsRequest?, pageLinks: PdfPageLinksRequest? = null): Boolean {
        if (thumbnails != null && !isPdfThumbnailsCurrent(thumbnails)) return false
        if (pageLinks != null && !pdfPageLinks.isCurrent(pageLinks)) return false
        if (pdfCropRequest != null || (pdfThumbnailsRequest != null && thumbnails == null) ||
            (pdfPageLinks.state.value.open && pageLinks == null)) return false
        if (tapZones.state.value.open || volumeKeys.state.value.open || orientation.state.value.open || selectionPreview != null || annotationActive || exiting || loadFailed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val navigator = bookmarkNavigator?.takeIf { surface?.bookmarkNavigator === it }
        val source = try {
            navigator?.capture()?.location
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val accepted = navigate(request, thumbnails, pageLinks)
        jumpHistory.record(source, accepted && !loadFailed && surface?.bookmarkNavigator === navigator)
        canJumpBack = jumpHistory.canReturn
        return accepted
    }

    private fun returnBeforeJump() {
        val navigator = bookmarkNavigator?.takeIf { surface?.bookmarkNavigator === it } ?: return
        navigate {
            try {
                jumpHistory.returnTo(navigator::goTo)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
        canJumpBack = jumpHistory.canReturn
    }

    private fun bindSelectionNotifications(expected: ReaderSurface) {
        selectionNotificationsJob?.cancel()
        val source = expected.textSelection ?: return
        selectionNotificationsJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                source.requests.collect {
                    if (surface === expected && surface?.textSelection === source && source.isCurrent(null)) captureSelectedText()
                }
            }
        }
    }

    private fun captureSelectedText() {
        val active = surface ?: return
        val source = active.textSelection ?: return
        captureSelection(active, source, ocr = false)
    }

    private fun openPdfOcr(expected: ReaderSurface, page: Int) {
        if (surface !== expected || expected.pageNavigator?.captureStablePage() != page ||
            !isBrightnessReaderReady() || annotationActive || selectionPreview != null || hasCompetingSelectionModal() ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if ((uiState as? ReaderState.Ready)?.session?.book?.format != BookFormat.PDF) return
        val source = expected.pdfOcr ?: return
        expected.textSelection?.clear()
        if (source.prepare(page)) captureSelection(expected, source, ocr = true)
    }

    private fun captureSelection(active: ReaderSurface, source: com.yaprak.reader.reader.TextSelectionSource, ocr: Boolean) {
        if (surface !== active || (if (ocr) active.pdfOcr else active.textSelection) !== source) return
        if (!isBrightnessReaderReady() || annotationActive || selectionPreview != null ||
            hasCompetingSelectionModal() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
        val generation = ++selectionRequestGeneration
        if (!ocr) active.pdfOcr?.clear()
        val loading = SelectedTextPreview.Loading(pdfOcr = ocr)
        selectionSurface = active
        selectionCaptureSource = source
        selectionBookIdentity = book.id to book.sha256
        selectionPreview = loading
        updateDuration()
        observation?.cancel()
        val job = lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
          fun owned() = generation == selectionRequestGeneration && surface === active && selectionPreview === loading
          try {
            val preview = try {
                source.capture()?.let {
                    currentCoroutineContext().ensureActive()
                    if (it.pdfOcr != ocr || (ocr && (it.sourceAnchor != null || it.rangeCandidate != null || it.pdfSourceAnchor != null)))
                        throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
                    if (ocr && (com.yaprak.reader.reader.validatedPdfOcrText(it.text) == null ||
                        active.pageNavigator?.captureStablePage() != it.pdfPageIndex))
                        throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
                    if (book.format == BookFormat.PDF) {
                        val page = it.pdfPageIndex
                        val id = it.sourceSelectionId
                        if (page == null || page !in 0 until (active.pageNavigator?.pageCount ?: 0) || id == null ||
                            runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false) != true)
                            throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
                        if (it.sourceAnchor != null || (it.pdfSourceAnchor != null && it.pdfSourceAnchor.pageIndex != page))
                            throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
                    } else if (it.pdfPageIndex != null || it.pdfSourceAnchor != null) throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
                    if (!source.isCurrent(it.sourceSelectionId)) throw CancellationException("Selected source changed")
                    SelectedTextPreview.Ready(it.text, rangeCandidate = it.rangeCandidate, sourceAnchor = it.sourceAnchor,
                        sessionId = loading.sessionId, pdfPageIndex = it.pdfPageIndex, sourceSelectionId = it.sourceSelectionId,
                        pdfSourceAnchor = it.pdfSourceAnchor, pdfOcr = ocr)
                } ?: SelectedTextPreview.Error(if (ocr) "Bu sayfada OCR metni bulunamadı. Daha net, Latin harfli bir sayfada yeniden dene."
                    else if (book.format == BookFormat.PDF)
                    "Seçili PDF metni bulunamadı. Yazıya uzun bas veya basılı tutup sürükle. Resim olarak taranmış sayfalarda OCR yapılmaz."
                    else "Seçili metin bulunamadı. Araçlar açıkken kitap metnini seçip yeniden dene.", loading.sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: com.yaprak.reader.reader.PdfOcrException) {
                SelectedTextPreview.Error(when (failure.reason) {
                    com.yaprak.reader.reader.PdfOcrFailure.BUSY -> "Önceki OCR işlemi kaynaklarını bırakmayı bekliyor. Biraz sonra yeniden dene."
                    com.yaprak.reader.reader.PdfOcrFailure.LIMIT -> "OCR metni çok uzun. En fazla 5000 karakterlik bir sayfa işlenebilir."
                    com.yaprak.reader.reader.PdfOcrFailure.SOURCE -> "OCR sayfası veya özgün PDF dosyası doğrulanamadı. Kitabı yeniden aç."
                    com.yaprak.reader.reader.PdfOcrFailure.MODEL -> "OCR motoru metni çıkaramadı. Yeniden dene."
                }, loading.sessionId)
            } catch (failure: TextSelectionException) {
                SelectedTextPreview.Error(when (failure.reason) {
                    TextSelectionFailure.TOO_LONG -> "Seçim çok uzun. En fazla 5000 karakter seç."
                    TextSelectionFailure.INVALID_RESOURCE -> "Seçim, açık kitabın okuma içeriğinde doğrulanamadı. Yeniden seç."
                    TextSelectionFailure.UNSUPPORTED -> "PDF metin seçimi Android 15 veya sonrasında kullanılabilir."
                }, loading.sessionId)
            } catch (_: Exception) {
                SelectedTextPreview.Error("Seçili metin alınamadı. Yeniden dene.", loading.sessionId)
            }
            currentCoroutineContext().ensureActive()
            if (!owned()) return@launch
            if (annotationActive || hasCompetingSelectionModal() || exiting) {
                clearSelectionPreview()
                // Capture suspended observation. A different modal can open while the SDK works;
                // its simple close action must not leave progress/status observation stopped.
                // The observer's live modal predicate still prevents coverage sampling here.
                if (!exiting && !loadFailed && uiState is ReaderState.Ready &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && observation?.isActive != true)
                    observeSurface()
                return@launch
            }
            if (!isBrightnessReaderReady() || (uiState as? ReaderState.Ready)?.session?.book?.let {
                it.id == book.id && it.sha256 == book.sha256 } != true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                clearSelectionPreview()
                observeSurface()
                return@launch
            }
            selectionPreview = preview
            updateDuration()
            if (preview is SelectedTextPreview.Ready) discoverSelectionTools(preview)
          } catch (cancelled: CancellationException) {
              if (owned()) {
                  clearSelectionPreview()
                  if (!exiting && !loadFailed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) observeSurface()
              }
              throw cancelled
          } finally {
              if (generation == selectionRequestGeneration) selectionCaptureJob = null
          }
        }
        selectionCaptureJob = job; job.start()
    }

    private fun clearSelectionPreview() {
        selectionCaptureJob?.cancel()
        selectionCaptureJob = null
        selectionCaptureSource?.clear()
        selectionCaptureSource = null
        selectionSurface?.textSelection?.clear()
        selectionAssistant.close()
        selectionAssistantState = null
        dictionary.close()
        dictionaryState = null
        translation.close()
        translationState = null
        selectionRequestGeneration++
        selectionToolsGeneration++
        selectionToolsJob?.cancel()
        selectionToolsJob = null
        selectionTools = null
        selectionPreview = null
        selectionSurface = null
        selectionBookIdentity = null
    }

    private fun openSelectionTranslation() {
        if (!isSelectionCurrent()) return
        val expected = selectionPreview as? SelectedTextPreview.Ready ?: return
        translation.open(expected.sessionId, expected.text) {
            val actual = selectionPreview as? SelectedTextPreview.Ready
            actual?.sessionId == expected.sessionId && actual.text == expected.text && isSelectionCurrent()
        }
    }
    private fun isTranslationCurrent(expected: com.yaprak.reader.feature.translation.TranslationState): Boolean {
        val actual = selectionPreview as? SelectedTextPreview.Ready ?: return false
        return isSelectionCurrent() && translation.state.value === expected &&
            actual.sessionId == expected.requestId && actual.text == expected.sourceText
    }
    private fun transferTranslatedWord(expected: com.yaprak.reader.feature.translation.TranslationState) {
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

    private fun lookupSelectionDictionary() {
        if (!isSelectionCurrent()) return
        val preview = selectionPreview as? SelectedTextPreview.Ready ?: return
        dictionary.lookup(preview.sessionId, preview.text)
    }

    private fun openSelectionAssistant() {
        if (!isSelectionCurrent()) return
        val preview = selectionPreview as? SelectedTextPreview.Ready ?: return
        val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
        val request = selectedAssistantRequest(book, preview) ?: return
        if (!isSelectionAssistantCurrent(request)) return
        selectionAssistant.open(request, getString(R.string.selected_ai_default_question)) { isSelectionAssistantCurrent(request) }
    }

    private fun isSelectionAssistantCurrent(request: com.yaprak.reader.domain.assistant.SelectedTextSourceRequest): Boolean {
        val preview = selectionPreview as? SelectedTextPreview.Ready ?: return false
        val book = (uiState as? ReaderState.Ready)?.session?.book ?: return false
        return isSelectionCurrent() && request.selectionId == preview.sessionId && request.preview == preview.text &&
            request == selectedAssistantRequest(book, preview) && selectionBookIdentity == (request.bookId to request.contentSha256) &&
            (application as YaprakApplication).container.startup.state.value == com.yaprak.reader.core.StartupState.Ready
    }

    private fun retrySelectionDictionary(expected: DictionaryLookupState) {
        if (!isSelectionCurrent() || expected.requestId != selectionPreview?.sessionId) return
        dictionary.retry(expected)
    }

    private fun discoverSelectionTools(preview: SelectedTextPreview.Ready) {
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

    private fun launchSelectionTool(tool: SelectedTextTool?) {
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

    private fun isSelectionCurrent(): Boolean = !selectionExternalLaunch && selectionPreview != null && selectionSurface === surface &&
        isBrightnessReaderReady() && !hasCompetingSelectionModal() && !annotationActive &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        (uiState as? ReaderState.Ready)?.session?.book?.let { (it.id to it.sha256) == selectionBookIdentity } == true &&
        ((selectionPreview as? SelectedTextPreview.Ready)?.let {
            val expected = if (it.pdfOcr) surface?.pdfOcr else surface?.textSelection
            if (it.pdfOcr) expected != null && selectionCaptureSource === expected &&
                expected.isCurrent(it.sourceSelectionId) && surface?.pageNavigator?.captureStablePage() == it.pdfPageIndex
            else (selectionCaptureSource == null || selectionCaptureSource === expected) && expected?.isCurrent(it.sourceSelectionId) != false
        } != false)

    private fun annotateSelectedText() {
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

    private fun canMutateAnnotation(request: EpubAnnotationRequest): Boolean = annotationRequest === request &&
        annotationSurface === surface && isBrightnessReaderReady() && !hasCompetingSelectionModal() &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        (uiState as? ReaderState.Ready)?.session?.book?.let {
            it.format == BookFormat.EPUB && it.id == request.bookId && it.sha256 == request.contentSha256 } == true

    /** Every selection stage uses the same gates, including delivery after an SDK suspension. */
    private fun hasCompetingSelectionModal(includeThumbnails: Boolean = true, includeCrop: Boolean = true, includePdfAnnotations: Boolean = true, includePdfPageLinks: Boolean = true): Boolean =
        (includePdfAnnotations && (pdfAnnotationRequest != null || pdfAnnotationsRequest != null)) ||
        (includeThumbnails && pdfThumbnailsRequest != null) || (includePdfPageLinks && pdfPageLinks.state.value.open) || (includeCrop && pdfCropRequest != null) || orientation.state.value.open || epubImagesOpen || brightnessOpen ||
        passagesOpen || savedWordsOpen || bookNotesOpen || contentsOpen || bookSearchOpen || bookmarksOpen ||
        pageJumpOpen || resetPending || resetRefreshFailed || settings.state.value.open || screenAwake.state.value.open || volumeKeys.state.value.open || tapZones.state.value.open ||
        bookAppearanceOpen || bookTheme.state.value.open || bookFontSize.state.value.open || bookFont.state.value.open ||
        bookMargins.state.value.open || bookLineHeight.state.value.open || bookSpacing.state.value.open || bookTextAlignment.state.value.open || bookReadingMode.state.value.open || bookAppearanceReset.state.value.open ||
        positionJump.state.value.open

    private fun openBookNotes() {
        if (!isBrightnessReaderReady() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        readerAction { observation?.cancel(); bookNotePrefill = null; bookNotesOpen = true }
    }

    private fun clearAnnotation() {
        annotationRequest = null
        annotationSurface = null
        clearPdfAnnotations()
    }

    private fun annotatePdfSelection() {
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
    private fun openPdfAnnotations() {
        val owner = surface ?: return
        if (!canUsePdfSurface(owner)) return
        val book = (uiState as? ReaderState.Ready)?.session?.book ?: return
        pdfAnnotationSurface = owner
        pdfAnnotationsRequest = com.yaprak.reader.feature.pdfannotations.PdfAnnotationsWindow(book.id, book.sha256, owner.textSelection != null)
        updateDuration()
        if (observation?.isActive != true) observeSurface()
    }
    private fun canMutatePdfAnnotations(bookId: String, hash: String): Boolean =
        pdfAnnotationSurface != null && pdfAnnotationSurface === surface && isBrightnessReaderReady() &&
        !hasCompetingSelectionModal(includePdfAnnotations = false) && annotationRequest == null && selectionPreview == null &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        (application as YaprakApplication).container.startup.state.value == com.yaprak.reader.core.StartupState.Ready &&
        (uiState as? ReaderState.Ready)?.session?.book?.let { it.format == BookFormat.PDF && it.id == bookId && it.sha256 == hash } == true
    private fun openPdfAnnotationPage(request: com.yaprak.reader.feature.pdfannotations.PdfAnnotationsWindow,
        anchor: com.yaprak.reader.reader.PdfSourceAnchor): Boolean {
        if (pdfAnnotationsRequest !== request || !canMutatePdfAnnotations(request.bookId, request.hash)) return false
        val owner = surface ?: return false
        val pages = owner.pageNavigator ?: return false
        if (anchor.pageIndex !in 0 until pages.pageCount) return false
        val point = owner.pdfAnnotationNavigator.takeIf { request.editingSupported }
        clearPdfAnnotations()
        val accepted = navigateJump { if (point != null) point.goTo(anchor) else pages.goTo(anchor.pageIndex) }
        observeSurface()
        return accepted
    }
    private fun clearPdfAnnotations() {
        pdfAnnotationRequest?.close(); pdfAnnotationsRequest?.close()
        pdfAnnotationRequest = null; pdfAnnotationsRequest = null; pdfAnnotationSurface = null
    }

    private fun retryHighlights() {
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

    private fun copySelectedText() {
        if (!isSelectionCurrent()) return
        if (orientation.state.value.open || annotationActive) return
        val ready = selectionPreview as? SelectedTextPreview.Ready ?: return
        if (exiting || loadFailed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        selectionPreview = try {
            copyPlainSelectedText(this, ready.text)
            ready.copy(copyStatus = CopyStatus.COPIED)
        } catch (_: Exception) {
            ready.copy(copyStatus = CopyStatus.FAILED)
        }
    }

    private fun transferSelectedWord() {
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

    private fun transferSelectedNote() {
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

    private fun transferSelectedPassage() {
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

    companion object {
        private const val BOOK_ID = "bookId"
        private const val PDF_NIGHT_CHOICE = "readerPdfNightChoice"
        private const val BRIGHTNESS_CHOICE = "readerBrightnessPercent"
        private const val BRIGHTNESS_GESTURE_CHOICE = "readerBrightnessGestures"
        private const val EPUB_IMAGE_CHOICE = "readerEpubImageFilter"
        fun intent(context: Context, bookId: String) = Intent(context, ReaderActivity::class.java).putExtra(BOOK_ID, bookId)
        fun intent(context: Context, target: LibraryTextTarget): Intent =
            IndexedChapterLaunch.addTo(intent(context, target.bookId), target)
    }
}

private data class ReaderInputs(
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
