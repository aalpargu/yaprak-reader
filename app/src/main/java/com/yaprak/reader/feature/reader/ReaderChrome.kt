package com.yaprak.reader.feature.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import com.yaprak.reader.domain.settings.PdfNavigationMode
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.yaprak.reader.R
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.EpubHighlightStatus
import com.yaprak.reader.reader.EpubHighlightFailure
import com.yaprak.reader.domain.library.BookFormat

@Composable
internal fun ReaderChrome(
    state: ReaderState, visible: Boolean, saveFailed: Boolean, loadFailed: Boolean,
    onBack: () -> Unit, onRetry: () -> Unit, onRetrySave: () -> Unit, onLeaveUnsaved: () -> Unit,
    onHide: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    pageLabel: String? = null,
    onSettings: (() -> Unit)? = null,
    onContents: (() -> Unit)? = null,
    onBookmarks: (() -> Unit)? = null,
    onPageJump: (() -> Unit)? = null,
    onBookAppearance: (() -> Unit)? = null,
    onSelectedText: (() -> Unit)? = null,
    onSavedWords: (() -> Unit)? = null,
    onBookNotes: (() -> Unit)? = null,
    onEpubPassages: (() -> Unit)? = null,
    onBookSearch: (() -> Unit)? = null,
    bookPositionPercent: Int? = null,
    onJumpBack: (() -> Unit)? = null,
    onScreenAwake: (() -> Unit)? = null,
    onPdfNightMode: (() -> Unit)? = null,
    pdfNightMode: Boolean = false,
    pdfNightModeStatus: PdfNightModeStatus = PdfNightModeStatus.Ready,
    onRetryPdfNightMode: (() -> Unit)? = null,
    onBookPositionJump: (() -> Unit)? = null,
    onPdfFitPage: (() -> Unit)? = null,
    onPdfFitWidth: (() -> Unit)? = null,
    onBrightness: (() -> Unit)? = null,
    onEpubImages: (() -> Unit)? = null,
    onOrientation: (() -> Unit)? = null,
    orientationApplyFailed: Boolean = false,
    onRetryOrientation: (() -> Unit)? = null,
    highlightStatus: EpubHighlightStatus? = null,
    onRetryHighlights: (() -> Unit)? = null,
    highlightRetryBusy: Boolean = false,
    onPdfThumbnails: (() -> Unit)? = null,
    onPdfCrop: (() -> Unit)? = null,
    pdfCropApplyFailed: Boolean = false,
    indexedChapterFailure: IndexedChapterFailure? = null,
    indexedChapterVerifying: Boolean = false,
    onRetryIndexedChapter: (() -> Unit)? = null,
    durationFailure: com.yaprak.reader.domain.statistics.ReadingDurationFailure? = null,
    onRetryDuration: (() -> Unit)? = null,
    pdfDoublePagePreferred: Boolean = false,
    onPdfSpread: (() -> Unit)? = null,
    pdfBookViewState: PdfBookViewPreferencesState? = null,
    onRetryPdfBookView: (() -> Unit)? = null,
    onPdfAnnotations: (() -> Unit)? = null,
    onPdfOcr: (() -> Unit)? = null,
    onReaderControls: (() -> Unit)? = null,
    onTapZones: (() -> Unit)? = null,
    comicDoublePagePreferred: Boolean = false,
    onComicSpread: (() -> Unit)? = null,
    comicBookViewState: ComicBookViewPreferencesState? = null,
    onRetryComicBookView: (() -> Unit)? = null,
    pdfScrolling: Boolean = false,
    onPdfScroll: (() -> Unit)? = null,
    onPdfPageLinks: (() -> Unit)? = null,
    pdfNavigationMode: com.yaprak.reader.domain.settings.PdfNavigationMode =
        if (pdfScrolling) com.yaprak.reader.domain.settings.PdfNavigationMode.VERTICAL_SCROLL else com.yaprak.reader.domain.settings.PdfNavigationMode.PAGED,
    onPdfNavigation: ((com.yaprak.reader.domain.settings.PdfNavigationMode) -> Unit)? = null,
    onBookAssistant: (() -> Unit)? = null,
    readingTime: ReadingTimeEstimate? = null,
    presentationController: ReaderPresentationController? = null,
    currentRequests: () -> Set<ReaderPanelKind> = { emptySet() },
    onRetryPresentationRestore: (() -> Unit)? = null,
    seek: ReaderSeek? = null,
    quickAppearance: ReaderQuickAppearance? = null,
    onQuickBookmark: (() -> Unit)? = null,
    chapterTitle: String? = null,
    onReadAloud: (() -> Unit)? = null,
    readAloudActive: Boolean = false,
    onPdfHighlighter: (() -> Unit)? = null,
    finishOffer: FinishBookState? = null,
    onMarkFinished: () -> Unit = {},
    onDismissFinish: () -> Unit = {},
) {
    val fallback = remember((state as? ReaderState.Ready)?.session) { ReaderPresentationController().apply { attach("standalone") } }
    val controller = presentationController ?: fallback
    val presentation by controller.state.collectAsState()
    val menuEntry = presentation.activePanel?.takeIf { it.kind.isMenu }
    val panel = menuEntry?.kind
    val latestRequests by rememberUpdatedState(currentRequests)
    val savedMenus = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val knownMenus = remember { mutableSetOf<Long>() }
    androidx.compose.runtime.SideEffect {
        val live = presentation.panels.filter { it.kind.isMenu }.map { it.id }.toSet()
        knownMenus.filterNot { it in live }.forEach(savedMenus::removeState)
        knownMenus.retainAll(live)
        menuEntry?.let { knownMenus += it.id }
    }
    androidx.compose.runtime.SideEffect {
        if (presentationController == null) {
            val phase = when {
                state is ReaderState.Error -> ReaderPhase.BlockingError(ReaderBlockingReason.OPEN)
                state !is ReaderState.Ready -> ReaderPhase.Loading
                loadFailed -> ReaderPhase.BlockingError(ReaderBlockingReason.SOURCE)
                saveFailed -> ReaderPhase.BlockingError(ReaderBlockingReason.SAVE)
                else -> ReaderPhase.Ready
            }
            val notice = when {
                saveFailed -> ReaderNoticeKind.SAVE
                loadFailed -> ReaderNoticeKind.LOAD
                durationFailure != null -> ReaderNoticeKind.DURATION
                indexedChapterFailure != null -> ReaderNoticeKind.INDEXED_SOURCE
                pdfBookViewState?.failed == true -> ReaderNoticeKind.PDF_APPEARANCE
                comicBookViewState?.failed == true -> ReaderNoticeKind.COMIC_APPEARANCE
                orientationApplyFailed -> ReaderNoticeKind.ORIENTATION
                pdfCropApplyFailed -> ReaderNoticeKind.PDF_CROP
                else -> null
            }
            controller.updateStatus(phase, notice)
            if (controller.state.value.controlsVisible != visible) controller.setControls(visible)
        }
    }
    fun closePanel() { menuEntry?.let(controller::closeMenu) }
    val hostView = androidx.compose.ui.platform.LocalView.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var pendingAction by remember((state as? ReaderState.Ready)?.session, visible) { mutableStateOf<ReaderPanelAction?>(null) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { pendingAction = null; controller.pause() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(pendingAction) {
        val expected = pendingAction ?: return@LaunchedEffect
        awaitReaderPanelFocus(hostView)
        kotlinx.coroutines.yield() // Let the same native focus event finish updating the Activity.
        if (pendingAction === expected && hostView.isAttachedToWindow && hostView.hasWindowFocus() &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            pendingAction = null
            if (controller.consume(expected.effect)) {
                try { expected.action() }
                finally { controller.finish(expected.effect, latestRequests()) }
            }
        }
    }
    fun launchPanelAction(action: (() -> Unit)?, kind: ReaderPanelKind? = null, focusKey: String? = kind?.focusKey) {
        if (action != null) menuEntry?.let { controller.defer(it, focusKey) }?.let { effect ->
            pendingAction = ReaderPanelAction(effect, action)
        }
    }
    fun deferred(action: (() -> Unit)?, kind: ReaderPanelKind? = null, focusKey: String? = kind?.focusKey): (() -> Unit)? = action?.let { original -> { launchPanelAction(original, kind, focusKey) } }
    if (state !is ReaderState.Ready) {
        ReaderToolsContent(
                state = state,
                visible = visible,
                saveFailed = saveFailed,
                loadFailed = loadFailed,
                onBack = onBack,
                onRetry = onRetry,
                onRetrySave = onRetrySave,
                onLeaveUnsaved = onLeaveUnsaved,
                onHide = onHide,
                onPrevious = onPrevious,
                onNext = onNext,
                pageLabel = pageLabel,
                onSettings = onSettings,
                onContents = onContents,
                onBookmarks = onBookmarks,
                onPageJump = onPageJump,
                onBookAppearance = onBookAppearance,
                onSelectedText = onSelectedText,
                onSavedWords = onSavedWords,
                onBookNotes = onBookNotes,
                onEpubPassages = onEpubPassages,
                onBookSearch = onBookSearch,
                bookPositionPercent = bookPositionPercent,
                onJumpBack = onJumpBack,
                onScreenAwake = onScreenAwake,
                onPdfNightMode = onPdfNightMode,
                pdfNightMode = pdfNightMode,
                pdfNightModeStatus = pdfNightModeStatus,
                onRetryPdfNightMode = onRetryPdfNightMode,
                onBookPositionJump = onBookPositionJump,
                onPdfFitPage = onPdfFitPage,
                onPdfFitWidth = onPdfFitWidth,
                onBrightness = onBrightness,
                onEpubImages = onEpubImages,
                onOrientation = onOrientation,
                orientationApplyFailed = orientationApplyFailed,
                onRetryOrientation = onRetryOrientation,
                highlightStatus = highlightStatus,
                onRetryHighlights = onRetryHighlights,
                highlightRetryBusy = highlightRetryBusy,
                onPdfThumbnails = onPdfThumbnails,
                onPdfCrop = onPdfCrop,
                pdfCropApplyFailed = pdfCropApplyFailed,
                indexedChapterFailure = indexedChapterFailure,
                indexedChapterVerifying = indexedChapterVerifying,
                onRetryIndexedChapter = onRetryIndexedChapter,
                durationFailure = durationFailure,
                onRetryDuration = onRetryDuration,
                pdfDoublePagePreferred = pdfDoublePagePreferred,
                onPdfSpread = onPdfSpread,
                pdfBookViewState = pdfBookViewState,
                onRetryPdfBookView = onRetryPdfBookView,
                onPdfAnnotations = onPdfAnnotations,
                onPdfOcr = onPdfOcr,
                onReaderControls = onReaderControls,
                onTapZones = onTapZones,
                comicDoublePagePreferred = comicDoublePagePreferred,
                onComicSpread = onComicSpread,
                comicBookViewState = comicBookViewState,
                onRetryComicBookView = onRetryComicBookView,
                pdfScrolling = pdfScrolling,
                onPdfScroll = onPdfScroll,
                onPdfPageLinks = onPdfPageLinks,
                pdfNavigationMode = pdfNavigationMode,
                onPdfNavigation = onPdfNavigation,
                onBookAssistant = onBookAssistant,
                readingTime = readingTime)
        return
    }
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        if (presentation.phase == ReaderPhase.Loading) CircularProgressIndicator(
            Modifier.align(Alignment.Center).testTag("reader-presentation-restoring"))
        if (visible && presentation.phase == ReaderPhase.Ready) {
            ReaderTopBar(state.session.book.title, onBack, onHide, Modifier.align(Alignment.TopCenter), onBookmark = onQuickBookmark)
            val validPercent = bookPositionPercent?.takeIf { it in 0..100 }
            val format = state.session.book.format
            val percentDescription = validPercent?.let { percent ->
                when (format) {
                    BookFormat.PDF -> stringResource(R.string.pdf_page_position_description, percent)
                    BookFormat.CBZ, BookFormat.CBR -> stringResource(R.string.cbz_page_position_description, percent)
                    BookFormat.EPUB -> stringResource(R.string.epub_book_position_description, percent)
                    else -> null
                }
            }
            val pageDescription = pageLabel?.let { if (format == BookFormat.PDF) "PDF sayfası $it" else "Sayfa $it" }
            val positionDescription = listOfNotNull(pageDescription, percentDescription).joinToString(", ").ifBlank { null }
            ReaderBottomBar(
                onContent = { controller.openMenu(ReaderPanelKind.CONTENT) }, onSearch = onBookSearch,
                onAppearance = { controller.openMenu(ReaderPanelKind.APPEARANCE) },
                onTools = { controller.openMenu(ReaderPanelKind.TOOLS) },
                onPrevious = onPrevious, onNext = onNext, onPosition = onPageJump ?: onBookPositionJump,
                pageLabel = pageLabel, positionPercent = validPercent, positionDescription = positionDescription,
                modifier = Modifier.align(Alignment.BottomCenter),
                positionKind = if (onPageJump != null) ReaderPanelKind.PAGE_JUMP else ReaderPanelKind.POSITION,
                focusTarget = { kind -> Modifier.readerReturnFocusTarget(controller, kind.focusKey) },
                seek = seek, remainingTime = readingTimeShortLabel(readingTime), chapterTitle = chapterTitle)
        }
        if (readAloudActive && onReadAloud != null) Surface(onClick = onReadAloud, shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp, shadowElevation = 4.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (visible) 64.dp else 8.dp).testTag("reader-read-aloud-stop")) {
            Text("Sesli okunuyor · Durdur", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
        // End of the book: one quiet offer to mark it finished (statistics count finished books).
        if (finishOffer != null && presentation.activePanel == null && presentation.phase == ReaderPhase.Ready)
            ReaderFinishOffer(finishOffer, onMarkFinished, onDismissFinish,
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (visible) 200.dp else 24.dp, start = 16.dp, end = 16.dp))
        // Transient: a search result's source is being verified; visible even with hidden controls.
        if (indexedChapterVerifying && presentation.activePanel == null) Surface(shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp, shadowElevation = 4.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (visible) 64.dp else 8.dp, start = 16.dp, end = 16.dp)
                .testTag("reader-indexed-verifying")) {
            Text(stringResource(R.string.indexed_chapter_verifying), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge)
        }
        // Quiet position footer while reading full screen, like a printed page number.
        if (!visible && presentation.phase == ReaderPhase.Ready) {
            val footer = pageLabel ?: bookPositionPercent?.takeIf { it in 0..100 }?.let { "%$it" }
            if (footer != null) Text(footer, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                .testTag("reader-footer-position"), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f))
        }
        val notice = presentation.notice
        if (presentation.activePanel == null && presentation.pending == null) ReaderNoticeHost(notice, onRetry = when (notice?.kind) {
            ReaderNoticeKind.SAVE -> onRetrySave
            ReaderNoticeKind.PRESENTATION_RESTORE -> onRetryPresentationRestore
            ReaderNoticeKind.DURATION -> onRetryDuration
            ReaderNoticeKind.INDEXED_SOURCE -> onRetryIndexedChapter
            ReaderNoticeKind.PDF_APPEARANCE -> onRetryPdfBookView
            ReaderNoticeKind.COMIC_APPEARANCE -> onRetryComicBookView
            ReaderNoticeKind.ORIENTATION -> onRetryOrientation
            else -> null
        }, onDetails = { controller.openMenu(ReaderPanelKind.TOOLS) },
            onLeave = if (presentation.phase is ReaderPhase.BlockingError) onBack else null,
            modifier = Modifier.align(Alignment.Center))
    }
    panel?.let { current ->
        savedMenus.SaveableStateProvider(requireNotNull(menuEntry).id) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { closePanel() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(12.dp).then(if (current == ReaderPanelKind.TOOLS) Modifier.fillMaxHeight(.88f)
                else Modifier.heightIn(max = 560.dp)), shape = MaterialTheme.shapes.extraLarge) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when(current) { ReaderPanelKind.CONTENT -> "Kitap içeriği"; ReaderPanelKind.APPEARANCE -> "Okuma görünümü"; ReaderPanelKind.TOOLS -> "Okuma araçları"; else -> "Okuma araçları" }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton({ closePanel() }, Modifier.readerReturnFocusTarget(controller, current.menuFocusKey)) {
                            Icon(Icons.Default.Close, contentDescription = "Kapat")
                        }
                    }
                    if (current == ReaderPanelKind.TOOLS) Box(Modifier.weight(1f)) {
                        ReaderToolsContent(
                    state = state,
                    visible = true,
                    saveFailed = saveFailed,
                    loadFailed = loadFailed,
                    onBack = { closePanel() },
                    onRetry = { launchPanelAction(onRetry) },
                    onRetrySave = { launchPanelAction(onRetrySave) },
                    onLeaveUnsaved = { launchPanelAction(onLeaveUnsaved) },
                    onHide = { closePanel() },
                    onPrevious = { launchPanelAction(onPrevious) },
                    onNext = { launchPanelAction(onNext) },
                    pageLabel = pageLabel,
                    onSettings = deferred(onSettings, ReaderPanelKind.SETTINGS),
                    onContents = deferred(onContents, ReaderPanelKind.CONTENTS),
                    onBookmarks = deferred(onBookmarks, ReaderPanelKind.BOOKMARKS),
                    onPageJump = deferred(onPageJump, ReaderPanelKind.PAGE_JUMP),
                    onBookAppearance = deferred(onBookAppearance, ReaderPanelKind.BOOK_APPEARANCE),
                    onSelectedText = deferred(onSelectedText, ReaderPanelKind.SELECTION),
                    onSavedWords = deferred(onSavedWords, ReaderPanelKind.WORDS),
                    onBookNotes = deferred(onBookNotes, ReaderPanelKind.NOTES),
                    onEpubPassages = deferred(onEpubPassages, ReaderPanelKind.PASSAGES),
                    onBookSearch = deferred(onBookSearch, ReaderPanelKind.SEARCH),
                    bookPositionPercent = bookPositionPercent,
                    onJumpBack = deferred(onJumpBack),
                    onScreenAwake = deferred(onScreenAwake, ReaderPanelKind.SCREEN_AWAKE),
                    onPdfNightMode = deferred(onPdfNightMode, ReaderPanelKind.TOOLS),
                    pdfNightMode = pdfNightMode,
                    pdfNightModeStatus = pdfNightModeStatus,
                    onRetryPdfNightMode = deferred(onRetryPdfNightMode),
                    onBookPositionJump = deferred(onBookPositionJump, ReaderPanelKind.POSITION),
                    onPdfFitPage = deferred(onPdfFitPage, ReaderPanelKind.TOOLS),
                    onPdfFitWidth = deferred(onPdfFitWidth, ReaderPanelKind.TOOLS),
                    onBrightness = deferred(onBrightness, ReaderPanelKind.BRIGHTNESS),
                    onEpubImages = deferred(onEpubImages, ReaderPanelKind.IMAGES),
                    onOrientation = deferred(onOrientation, ReaderPanelKind.ORIENTATION),
                    orientationApplyFailed = orientationApplyFailed,
                    onRetryOrientation = deferred(onRetryOrientation),
                    highlightStatus = highlightStatus,
                    onRetryHighlights = deferred(onRetryHighlights),
                    highlightRetryBusy = highlightRetryBusy,
                    onPdfThumbnails = deferred(onPdfThumbnails, ReaderPanelKind.PDF_THUMBNAILS),
                    onPdfCrop = deferred(onPdfCrop, ReaderPanelKind.PDF_CROP),
                    pdfCropApplyFailed = pdfCropApplyFailed,
                    indexedChapterFailure = indexedChapterFailure,
                    indexedChapterVerifying = indexedChapterVerifying,
                    onRetryIndexedChapter = deferred(onRetryIndexedChapter),
                    durationFailure = durationFailure,
                    onRetryDuration = deferred(onRetryDuration),
                    pdfDoublePagePreferred = pdfDoublePagePreferred,
                    onPdfSpread = deferred(onPdfSpread, ReaderPanelKind.TOOLS),
                    pdfBookViewState = pdfBookViewState,
                    onRetryPdfBookView = deferred(onRetryPdfBookView),
                    onPdfAnnotations = deferred(onPdfAnnotations, ReaderPanelKind.PDF_ANNOTATIONS),
                    onPdfOcr = deferred(onPdfOcr, ReaderPanelKind.SELECTION, "reader-focus-pdf_ocr"),
                    onReaderControls = deferred(onReaderControls, ReaderPanelKind.VOLUME_KEYS),
                    onTapZones = deferred(onTapZones, ReaderPanelKind.TAP_ZONES),
                    comicDoublePagePreferred = comicDoublePagePreferred,
                    onComicSpread = deferred(onComicSpread, ReaderPanelKind.TOOLS),
                    comicBookViewState = comicBookViewState,
                    onRetryComicBookView = deferred(onRetryComicBookView),
                    pdfScrolling = pdfScrolling,
                    onPdfScroll = deferred(onPdfScroll, ReaderPanelKind.TOOLS),
                    onPdfPageLinks = deferred(onPdfPageLinks, ReaderPanelKind.PDF_LINKS),
                    pdfNavigationMode = pdfNavigationMode,
                    onPdfNavigation = onPdfNavigation?.let { change -> { mode: PdfNavigationMode -> launchPanelAction({ change(mode) }) } },
                    onBookAssistant = deferred(onBookAssistant, ReaderPanelKind.TOOLS),
                    readingTime = readingTime,
                    recoveryOnly = presentation.phase != ReaderPhase.Ready,
                    renderingFailed = presentation.phase == ReaderPhase.BlockingError(ReaderBlockingReason.RENDER),
                    onLibrary = onBack,
                    pdfOcrFocusTarget = { Modifier.readerReturnFocusTarget(controller, "reader-focus-pdf_ocr") },
                    focusTarget = { kind -> Modifier.readerReturnFocusTarget(controller, kind.focusKey) },
                    onReadAloud = deferred(onReadAloud), readAloudActive = readAloudActive,
                    onPdfHighlighter = deferred(onPdfHighlighter))
                    } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        fun launch(action: (() -> Unit)?, kind: ReaderPanelKind? = null) { launchPanelAction(action, kind) }
                        if (current == ReaderPanelKind.CONTENT) {
                            onContents?.let { ReaderMenuRow("İçindekiler", "Bölümlere göz at", Icons.AutoMirrored.Filled.List, { launch(it, ReaderPanelKind.CONTENTS) }, Modifier.readerReturnFocusTarget(controller, ReaderPanelKind.CONTENTS.focusKey)) }
                            onBookmarks?.let { ReaderMenuRow("Yer imleri", "Kaydettiğin konumlar", Icons.Default.Star, { launch(it, ReaderPanelKind.BOOKMARKS) }, Modifier.readerReturnFocusTarget(controller, ReaderPanelKind.BOOKMARKS.focusKey)) }
                            onEpubPassages?.let { ReaderMenuRow("Vurgular", "İşaretlediğin pasajlar", Icons.Default.Create, { launch(it, ReaderPanelKind.PASSAGES) }, Modifier.readerReturnFocusTarget(controller, ReaderPanelKind.PASSAGES.focusKey)) }
                            onPdfAnnotations?.let { ReaderMenuRow("Vurgular", "PDF'te işaretlediklerin", Icons.Default.Create, { launch(it, ReaderPanelKind.PDF_ANNOTATIONS) }, Modifier.readerReturnFocusTarget(controller, ReaderPanelKind.PDF_ANNOTATIONS.focusKey)) }
                            onBookNotes?.let { ReaderMenuRow("Notlar", "Bu kitaba yazdığın notlar", Icons.Default.MailOutline, { launch(it, ReaderPanelKind.NOTES) }, Modifier.readerReturnFocusTarget(controller, ReaderPanelKind.NOTES.focusKey)) }
                            onPdfThumbnails?.let { ReaderMenuRow("Sayfalar", "Küçük resimlerle sayfa seç", Icons.Default.Menu, { launch(it, ReaderPanelKind.PDF_THUMBNAILS) }, Modifier.readerReturnFocusTarget(controller, ReaderPanelKind.PDF_THUMBNAILS.focusKey)) }
                            onJumpBack?.let { ReaderMenuRow("Önceki konuma dön", "Son atlamadan önceki yer", Icons.AutoMirrored.Filled.ArrowBack, { launch(it) }) }
                        } else {
                            quickAppearance?.let { ReaderQuickAppearanceControls(it) }
                            onBookAppearance?.let { ReaderMenuRow("Diğer yazı ayarları", "Yazı tipi, kenarlar, satır aralığı, hizalama", Icons.Default.Create, { launch(it, ReaderPanelKind.BOOK_APPEARANCE) }, Modifier.readerReturnFocusTarget(controller, ReaderPanelKind.BOOK_APPEARANCE.focusKey)) }
                            onSettings?.let { ReaderMenuRow("Varsayılan yazı ve tema", "Tüm kitaplar için", Icons.Default.Settings, { launch(it, ReaderPanelKind.SETTINGS) }, Modifier.readerReturnFocusTarget(controller, ReaderPanelKind.SETTINGS.focusKey)) }
                            ReaderMenuRow("Ekran ve sayfa", "Parlaklık, yön, dokunma bölgeleri", Icons.Default.Build, { controller.openMenu(ReaderPanelKind.TOOLS) })
                        }
                    }
                }
            }
        }
    }
    }
}

private class ReaderPanelAction(val effect: ReaderPanelEffect, val action: () -> Unit)

/** A dialog's disposal precedes focus return. Keep the Activity's input gates intact. */
internal suspend fun awaitReaderPanelFocus(view: android.view.View) {
    if (view.isAttachedToWindow && view.hasWindowFocus()) return
    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
        val tree = view.viewTreeObserver
        lateinit var listener: android.view.ViewTreeObserver.OnWindowFocusChangeListener
        fun remove() { if (tree.isAlive) tree.removeOnWindowFocusChangeListener(listener) }
        listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && view.isAttachedToWindow && continuation.isActive) {
                remove(); continuation.resumeWith(Result.success(Unit))
            }
        }
        tree.addOnWindowFocusChangeListener(listener)
        continuation.invokeOnCancellation { remove() }
        if (view.isAttachedToWindow && view.hasWindowFocus() && continuation.isActive) {
            remove(); continuation.resumeWith(Result.success(Unit))
        }
    }
}

@Composable
private fun ReaderFinishOffer(state: FinishBookState, onMark: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    Surface(modifier.testTag("reader-finish-offer"), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                state.done -> "Bitti olarak işaretlendi."
                state.failed -> "Kaydedilemedi."
                else -> "Kitabı bitirdin mi?"
            }, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium)
            if (!state.done) TextButton(onMark, enabled = !state.saving) {
                Text(if (state.failed) "Yeniden dene" else "Bitti olarak işaretle")
            }
            TextButton(onDismiss) { Text(if (state.done) "Tamam" else "Şimdi değil") }
        }
    }
}