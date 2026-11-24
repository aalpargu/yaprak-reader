package com.yaprak.reader.feature.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
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

/** One visible reader exposure; reopening never revives callbacks from a prior expansion. */
private class ReaderAdditionalTools {
    var active = true
        private set
    var revision by mutableLongStateOf(0L)
        private set
    var expanded by mutableStateOf(true)
        private set
    fun toggle(expected: Long) {
        if (!active || revision != expected) return
        revision++; expanded = !expanded
    }
    fun close() { revision++; expanded = false }
    fun dispose() { active = false }
    fun admits(expected: Long) = active && expanded && revision == expected
}

@Composable
private fun additionalToolAction(owner: ReaderAdditionalTools, action: (() -> Unit)?): (() -> Unit)? {
    val latest = rememberUpdatedState(action)
    val revision = owner.revision
    val lease = remember(owner, revision, action) { PdfSpreadActionLease() }
    DisposableEffect(lease) { onDispose { lease.active = false } }
    if (!owner.expanded || action == null) return null
    return remember(owner, revision, lease) {
        { latest.value?.let { current ->
            if (lease.active && owner.admits(revision) && current == action) current()
        }; Unit }
    }
}

@Composable
private fun additionalNavigationAction(owner: ReaderAdditionalTools,
    action: ((PdfNavigationMode) -> Unit)?): ((PdfNavigationMode) -> Unit)? {
    val latest = rememberUpdatedState(action)
    val revision = owner.revision
    val lease = remember(owner, revision, action) { PdfSpreadActionLease() }
    DisposableEffect(lease) { onDispose { lease.active = false } }
    if (!owner.expanded || action == null) return null
    return remember(owner, revision, lease) {
        { mode -> latest.value?.let { current ->
            if (lease.active && owner.admits(revision) && current == action) current(mode)
        }; Unit }
    }
}

private class PdfSpreadActionLease { var active = true }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderToolsContent(
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
    recoveryOnly: Boolean = false,
    renderingFailed: Boolean = false,
    onLibrary: (() -> Unit)? = null,
    focusTarget: @Composable (ReaderPanelKind) -> Modifier = { Modifier },
    pdfOcrFocusTarget: @Composable () -> Modifier = { Modifier },
    onReadAloud: (() -> Unit)? = null,
    readAloudActive: Boolean = false,
    onPdfHighlighter: (() -> Unit)? = null,
) {
    val readySession = (state as? ReaderState.Ready)?.session
    val format = readySession?.book?.format
    val toolCapabilities = listOf(onScreenAwake != null, onOrientation != null, onBrightness != null,
        onReaderControls != null, onTapZones != null,
        format == BookFormat.EPUB && onEpubImages != null,
        format?.comic == true && onComicSpread != null,
        format == BookFormat.PDF && onPdfPageLinks != null,
        format == BookFormat.PDF && onPdfCrop != null,
        format == BookFormat.PDF && onPdfScroll != null,
        format == BookFormat.PDF && onPdfSpread != null,
        format == BookFormat.PDF && onPdfFitPage != null,
        format == BookFormat.PDF && onPdfFitWidth != null,
        format == BookFormat.PDF && onPdfNightMode != null,
        format == BookFormat.PDF && onPdfNavigation != null,
        format == BookFormat.PDF && onPdfOcr != null)
    val additionalTools = remember(readySession, format, visible, saveFailed, loadFailed, toolCapabilities.any { it }) {
        ReaderAdditionalTools()
    }
    DisposableEffect(additionalTools) { onDispose { additionalTools.dispose() } }
    val toolsScroll = rememberScrollState()
    val cropRecovery = format == BookFormat.PDF && pdfCropApplyFailed && onPdfCrop != null
    val extraScreenAwake = additionalToolAction(additionalTools, onScreenAwake)
    val extraOrientation = additionalToolAction(additionalTools, onOrientation)
    val extraBrightness = additionalToolAction(additionalTools, onBrightness)
    val extraEpubImages = additionalToolAction(additionalTools, onEpubImages)
    val extraComicSpread = additionalToolAction(additionalTools, onComicSpread)
    val extraPdfPageLinks = additionalToolAction(additionalTools, onPdfPageLinks)
    val guardedPdfCrop = additionalToolAction(additionalTools, onPdfCrop)
    val extraPdfCrop = if (cropRecovery) onPdfCrop else guardedPdfCrop
    val extraPdfScroll = additionalToolAction(additionalTools, onPdfScroll)
    val extraPdfSpread = additionalToolAction(additionalTools, onPdfSpread)
    val extraPdfFitPage = additionalToolAction(additionalTools, onPdfFitPage)
    val extraPdfFitWidth = additionalToolAction(additionalTools, onPdfFitWidth)
    val extraPdfNightMode = additionalToolAction(additionalTools, onPdfNightMode)
    val extraTapZones = additionalToolAction(additionalTools, onTapZones)
    val extraReaderControls = additionalToolAction(additionalTools, onReaderControls)
    val extraPdfOcr = additionalToolAction(additionalTools, onPdfOcr)
    val extraPdfNavigation = additionalNavigationAction(additionalTools, onPdfNavigation)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val barHeight = maxHeight
        when (state) {
            ReaderState.Loading -> Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Text("Kitap açılıyor…", Modifier.padding(24.dp))
                    TextButton(onClick = onBack) { Text("Kitaplığa dön") }
                }
            }
            is ReaderState.Error -> Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(when (state.reason) {
                        ReaderFailure.MISSING -> "Kitabın yerel dosyası bulunamadı."
                        ReaderFailure.CHANGED -> "Kitabın içeriği değişmiş. Yanlış konum açmamak için okuma durduruldu."
                        ReaderFailure.UNSUPPORTED -> "Bu biçim için okuyucu henüz eklenmedi."
                        ReaderFailure.INVALID -> "Kitap açılamadı. Dosya bozuk veya erişilemiyor olabilir."
                        ReaderFailure.RECOVERY -> "Kitaplık kurtarma işlemi tamamlanamadı. Kitaplığa dönüp yeniden dene."
                    })
                    Button(onClick = onRetry, Modifier.padding(top = 24.dp)) { Text("Yeniden dene") }
                    TextButton(onClick = onBack) { Text("Kitaplığa dön") }
                }
            }
            is ReaderState.Ready -> if (visible || saveFailed || loadFailed || durationFailure != null || indexedChapterFailure != null || indexedChapterVerifying ||
                (state.session.book.format == BookFormat.PDF && (pdfCropApplyFailed || pdfBookViewState?.failed == true))) {
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = barHeight)
                    .verticalScroll(toolsScroll), tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (loadFailed) Text("Bu bölüm yüklenemedi. Kitaplığa dönüp yeniden açmayı dene.", color = MaterialTheme.colorScheme.error)
                        if (renderingFailed) Text("Bu sayfa çizilemedi. Kitaplığa dönüp yeniden açmayı dene.", color = MaterialTheme.colorScheme.error)
                        if (recoveryOnly) onLibrary?.let { leave -> TextButton(leave) { Text("Kitaplığa dön") } }
                        if (recoveryOnly && renderingFailed && format == BookFormat.PDF && onPdfThumbnails != null &&
                            visible && !loadFailed && !saveFailed)
                            TextButton(onClick = onPdfThumbnails, modifier = focusTarget(ReaderPanelKind.PDF_THUMBNAILS).fillMaxWidth()) {
                                Text(stringResource(R.string.pdf_thumbnails_title), Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        if (durationFailure != null) {
                            Text(stringResource(R.string.reading_duration_save_failed), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                            onRetryDuration?.let { retry -> TextButton(retry, Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.reading_duration_retry), Modifier.fillMaxWidth())
                            } }
                            TextButton(onLeaveUnsaved, Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.reading_duration_leave_unsaved), Modifier.fillMaxWidth())
                            }
                        }
                        if (saveFailed) {
                            Text("Okuma konumu kaydedilemedi.", color = MaterialTheme.colorScheme.error)
                            FlowRow(horizontalArrangement = Arrangement.Center) {
                                TextButton(onClick = onRetrySave) { Text("Tekrar kaydet") }
                                TextButton(onClick = onLeaveUnsaved) { Text("Kaydetmeden çık") }
                            }
                        }

                        if (indexedChapterVerifying) Text(stringResource(R.string.indexed_chapter_verifying),
                            Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        indexedChapterFailure?.let { failure ->
                            Text(stringResource(when (failure) {
                                IndexedChapterFailure.SOURCE -> R.string.indexed_chapter_source_error
                                IndexedChapterFailure.UNSUPPORTED -> R.string.indexed_chapter_unsupported
                                IndexedChapterFailure.NAVIGATION -> R.string.indexed_chapter_navigation_error
                                IndexedChapterFailure.INTERRUPTED -> R.string.indexed_chapter_interrupted
                            }), Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            if (onRetryIndexedChapter != null) TextButton(onClick = onRetryIndexedChapter,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.indexed_chapter_retry))
                            }
                        }
                        if (!recoveryOnly) {
                            val fmt = state.session.book.format
                            val ready = visible && !loadFailed && !saveFailed
                            ReaderToolsSection("Gezinme")
                            if (onPageJump != null) ReaderMenuRow("Sayfaya git", pageLabel?.let { "Şu an: $it" },
                                Icons.AutoMirrored.Filled.ArrowForward, onPageJump, focusTarget(ReaderPanelKind.PAGE_JUMP))
                            if (fmt.reflowable && onBookPositionJump != null) ReaderMenuRow("Kitap yüzdesine git",
                                bookPositionPercent?.let { "Şu an: %$it" }, Icons.AutoMirrored.Filled.ArrowForward,
                                onBookPositionJump, focusTarget(ReaderPanelKind.POSITION))
                            if (fmt in setOf(BookFormat.PDF, BookFormat.CBZ, BookFormat.CBR) && onPdfThumbnails != null && ready)
                                ReaderMenuRow(stringResource(R.string.pdf_thumbnails_title), "Sayfaya küçük resimlerden git",
                                    Icons.Default.Menu, onPdfThumbnails, focusTarget(ReaderPanelKind.PDF_THUMBNAILS))
                            if (fmt == BookFormat.PDF && extraPdfPageLinks != null && ready) key(state.session, extraPdfPageLinks) {
                                val lease = remember { PdfSpreadActionLease() }
                                DisposableEffect(lease) { onDispose { lease.active = false } }
                                ReaderMenuRow(stringResource(R.string.pdf_page_links_title), "Bu sayfadaki bağlantılar", Icons.Default.Share,
                                    { if (lease.active) extraPdfPageLinks() }, focusTarget(ReaderPanelKind.PDF_LINKS))
                            }
                            if (onJumpBack != null) ReaderMenuRow("Önceki konuma dön", "Son atlamadan önceki yere",
                                Icons.AutoMirrored.Filled.ArrowBack, onJumpBack,
                                Modifier.semantics { contentDescription = "Atlamadan önceki konuma dön" })

                            ReaderToolsSection("Çalışma")
                            if (onBookAssistant != null && ready &&
                                com.yaprak.reader.domain.search.supportsBookSourceRetrieval(fmt)) {
                                key(state.session, onBookAssistant) {
                                    val lease = remember { PdfSpreadActionLease() }
                                    DisposableEffect(lease) { onDispose { lease.active = false } }
                                    ReaderMenuRow(stringResource(R.string.reader_book_assistant), "Cevaplar kitaptan, kaynak gösterilerek",
                                        Icons.Default.Face, { if (lease.active) onBookAssistant() })
                                }
                            }
                            if (onReadAloud != null && ready) ReaderMenuRow(if (readAloudActive) "Sesli okumayı durdur" else "Sesli oku",
                                if (readAloudActive) null else "Bulunduğun yerden itibaren, cihazın sesiyle",
                                Icons.Default.PlayArrow, onReadAloud, Modifier.testTag("reader-read-aloud"))
                            if (fmt == BookFormat.PDF && onPdfHighlighter != null && ready) ReaderMenuRow("Fosforlu kalem",
                                "Parmağınla satırların üstünü boya", Icons.Default.Edit, onPdfHighlighter,
                                Modifier.testTag("reader-pdf-highlighter"))
                            if (onSavedWords != null) ReaderMenuRow("Kelimelerim", "Bu kitapta kaydettiğin kelimeler",
                                Icons.Default.Favorite, onSavedWords, focusTarget(ReaderPanelKind.WORDS))
                            if (onSelectedText != null) ReaderMenuRow("Seçili metinle çalış", "Önce metne uzun basıp seç",
                                Icons.Default.Search, onSelectedText, focusTarget(ReaderPanelKind.SELECTION))
                            if (fmt == BookFormat.PDF && extraPdfOcr != null && ready) key(extraPdfOcr) {
                                val lease = remember { PdfSpreadActionLease() }
                                DisposableEffect(lease) { onDispose { lease.active = false } }
                                ReaderMenuRow("Bu sayfada yazı tanı (OCR)",
                                    "OCR, bu sayfadaki Latin harfli yazıyı tanımayı dener. Türkçe ve İngilizce desteklenir; sonuçları kontrol et. Görsel ve metin cihazda işlenir. Google ML Kit kullanım ve performans ölçümlerini Google ile paylaşabilir.",
                                    Icons.Default.Search, { if (lease.active) extraPdfOcr() }, pdfOcrFocusTarget())
                            }

                            if (fmt == BookFormat.PDF && ready && (extraPdfNavigation != null || extraPdfScroll != null ||
                                    extraPdfSpread != null || extraPdfFitPage != null || extraPdfCrop != null || extraPdfNightMode != null)) {
                                ReaderToolsSection("PDF sayfası")
                                if (extraPdfNavigation != null) {
                                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                        PdfNavigationChoices(pdfNavigationMode, pdfBookViewState?.canEdit != false, extraPdfNavigation)
                                    }
                                } else if (extraPdfScroll != null) key(state.session, pdfScrolling, pdfBookViewState) {
                                    val lease = remember { PdfSpreadActionLease() }
                                    DisposableEffect(lease) { onDispose { lease.active = false } }
                                    ReaderMenuRow(stringResource(if (pdfScrolling) R.string.pdf_scroll_disable else R.string.pdf_scroll_enable),
                                        null, Icons.Default.KeyboardArrowDown, { if (lease.active) extraPdfScroll() },
                                        enabled = pdfBookViewState?.canEdit != false)
                                }
                                if (extraPdfSpread != null) key(state.session, pdfDoublePagePreferred, pdfBookViewState, pdfScrolling) {
                                    val lease = remember { PdfSpreadActionLease() }
                                    DisposableEffect(lease) { onDispose { lease.active = false } }
                                    ReaderMenuRow(stringResource(if (pdfDoublePagePreferred) R.string.pdf_spread_single else R.string.pdf_spread_double),
                                        if (pdfScrolling) "Kaydırmalı modda kullanılamaz" else null, Icons.Default.DateRange,
                                        { if (lease.active) extraPdfSpread() }, enabled = !pdfScrolling && pdfBookViewState?.canEdit != false)
                                }
                                key(state.session, pdfBookViewState, extraPdfCrop) {
                                    val lease = remember { PdfSpreadActionLease() }
                                    DisposableEffect(lease) { onDispose { lease.active = false } }
                                    if (extraPdfFitPage != null) ReaderMenuRow("Sayfaya sığdır", null, Icons.Default.Check,
                                        { if (lease.active) extraPdfFitPage() }, enabled = pdfBookViewState?.canEdit != false)
                                    if (extraPdfFitWidth != null) ReaderMenuRow("Genişliğe sığdır", null, Icons.Default.Check,
                                        { if (lease.active) extraPdfFitWidth() }, enabled = pdfBookViewState?.canEdit != false)
                                }
                                if (extraPdfCrop != null && (visible || pdfCropApplyFailed)) key(state.session, pdfBookViewState, extraPdfCrop) {
                                    val lease = remember { PdfSpreadActionLease() }
                                    DisposableEffect(lease) { onDispose { lease.active = false } }
                                    ReaderMenuRow(stringResource(R.string.pdf_crop_title), "Beyaz kenarları gizle", Icons.Default.Edit,
                                        { if (lease.active) extraPdfCrop() }, focusTarget(ReaderPanelKind.PDF_CROP),
                                        enabled = pdfBookViewState?.canEdit != false)
                                }
                                if (extraPdfNightMode != null) ReaderMenuRow(if (pdfNightMode) "Özgün renklere dön" else "Gece görünümü",
                                    if (pdfNightMode) null else "Sayfa renklerini koyulaştır", Icons.Default.Star, extraPdfNightMode,
                                    enabled = pdfNightModeStatus != PdfNightModeStatus.Loading && pdfNightModeStatus != PdfNightModeStatus.LoadFailed)
                            } else if (fmt == BookFormat.PDF && cropRecovery && extraPdfCrop != null) {
                                ReaderMenuRow(stringResource(R.string.pdf_crop_title), null, Icons.Default.Edit, extraPdfCrop,
                                    focusTarget(ReaderPanelKind.PDF_CROP), enabled = pdfBookViewState?.canEdit != false)
                            }
                            if (fmt.comic && extraComicSpread != null && ready) {
                                ReaderToolsSection("Çizgi roman")
                                key(state.session, comicDoublePagePreferred, extraComicSpread, comicBookViewState) {
                                    val lease = remember { PdfSpreadActionLease() }
                                    DisposableEffect(lease) { onDispose { lease.active = false } }
                                    ReaderMenuRow(stringResource(if (comicDoublePagePreferred) R.string.cbz_spread_single else R.string.cbz_spread_double),
                                        stringResource(R.string.cbz_spread_help), Icons.Default.DateRange,
                                        { if (lease.active) extraComicSpread() }, enabled = comicBookViewState?.canEdit != false)
                                }
                            }

                            if (ready && listOf(extraBrightness, extraOrientation, extraScreenAwake, extraTapZones,
                                    extraReaderControls, extraEpubImages).any { it != null }) {
                                ReaderToolsSection("Ekran ve kontroller")
                                if (extraBrightness != null) ReaderMenuRow("Parlaklık", "Uygulamaya özel ekran parlaklığı",
                                    Icons.Default.Star, extraBrightness, focusTarget(ReaderPanelKind.BRIGHTNESS))
                                if (extraOrientation != null) ReaderMenuRow(stringResource(R.string.reader_orientation_title), "Dikey, yatay veya otomatik",
                                    Icons.Default.Refresh, extraOrientation, focusTarget(ReaderPanelKind.ORIENTATION))
                                if (extraScreenAwake != null) ReaderMenuRow("Ekranı açık tut", "Okurken ekranın kapanmasını engelle",
                                    Icons.Default.Lock, extraScreenAwake, focusTarget(ReaderPanelKind.SCREEN_AWAKE))
                                if (fmt == BookFormat.EPUB && extraEpubImages != null) ReaderMenuRow("Koyu temada görseller",
                                    "Resimlerin karanlık temada nasıl görüneceği", Icons.Default.Face, extraEpubImages,
                                    focusTarget(ReaderPanelKind.IMAGES))
                                if (extraTapZones != null) ReaderMenuRow("Dokunma bölgeleri", "Kenarlara dokununca ne olacağı",
                                    Icons.Default.Place, extraTapZones, focusTarget(ReaderPanelKind.TAP_ZONES))
                                if (extraReaderControls != null) ReaderMenuRow("Okuma kontrolleri", "Ses tuşlarıyla sayfa çevirme",
                                    Icons.Default.Settings, extraReaderControls, focusTarget(ReaderPanelKind.VOLUME_KEYS))
                            }
                        }
                        if (state.session.book.format.comic && visible && !loadFailed && !saveFailed) {
                            comicBookViewState?.let { preferences ->
                                ComicBookViewPreferenceMessage(preferences)
                                if (preferences.failed && onRetryComicBookView != null) {
                                    key(state.session, preferences, onRetryComicBookView) {
                                        val lease = remember { PdfSpreadActionLease() }
                                        DisposableEffect(lease) { onDispose { lease.active = false } }
                                        TextButton(onClick = { if (lease.active) onRetryComicBookView() }, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.cbz_spread_retry), Modifier.fillMaxWidth(),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                        if (orientationApplyFailed) {
                            Text(stringResource(R.string.reader_orientation_apply_failed),
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                            if (onRetryOrientation != null) TextButton(onClick = onRetryOrientation) {
                                Text(stringResource(R.string.reader_orientation_apply_retry))
                            }
                        }
                        if (state.session.book.format == BookFormat.PDF && pdfScrolling && onPdfScroll != null)
                            Text(stringResource(if (pdfNavigationMode == com.yaprak.reader.domain.settings.PdfNavigationMode.HORIZONTAL_SCROLL)
                                R.string.pdf_horizontal_scroll_help else R.string.pdf_scroll_help), Modifier.fillMaxWidth().padding(12.dp),
                                style = MaterialTheme.typography.bodySmall)
                        if (state.session.book.format == BookFormat.PDF && !pdfScrolling && pdfDoublePagePreferred && onPdfSpread != null)
                            Text(stringResource(R.string.pdf_spread_help), Modifier.fillMaxWidth().padding(12.dp),
                                style = MaterialTheme.typography.bodySmall)
                        if (state.session.book.format == BookFormat.PDF && pdfCropApplyFailed && pdfBookViewState == null) {
                            Text(stringResource(R.string.pdf_crop_restore_error),
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.session.book.format == BookFormat.PDF && pdfBookViewState != null) {
                            PdfBookViewPreferenceMessage(pdfBookViewState)
                            if (pdfBookViewState.failed && onRetryPdfBookView != null) {
                                key(state.session, pdfBookViewState) {
                                    val lease = remember { PdfSpreadActionLease() }
                                    DisposableEffect(lease) { onDispose { lease.active = false } }
                                    TextButton(onClick = { if (lease.active) onRetryPdfBookView() },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.pdf_book_view_retry), Modifier.fillMaxWidth(),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                }
                            }
                        }
                        if (state.session.book.format == BookFormat.EPUB && highlightStatus != null) {
                            val message = when (highlightStatus) {
                                EpubHighlightStatus.Loading -> stringResource(R.string.highlights_loading)
                                is EpubHighlightStatus.Ready -> if (highlightStatus.total > 0)
                                    stringResource(R.string.highlights_ready, highlightStatus.total) else null
                                is EpubHighlightStatus.Partial -> stringResource(R.string.highlights_partial,
                                    highlightStatus.unresolved, highlightStatus.total)
                                EpubHighlightStatus.Unsupported -> stringResource(R.string.highlights_unsupported)
                                is EpubHighlightStatus.Failed -> stringResource(when (highlightStatus.reason) {
                                    EpubHighlightFailure.SOURCE_LIMIT -> R.string.highlights_source_limit
                                    EpubHighlightFailure.DOM_LIMIT -> R.string.highlights_dom_limit
                                    EpubHighlightFailure.SOURCE_UNAVAILABLE -> R.string.highlights_source_unavailable
                                    EpubHighlightFailure.APPLY_FAILED -> R.string.highlights_apply_failed
                                })
                            }
                            message?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall) }
                            if (onRetryHighlights != null && (highlightStatus is EpubHighlightStatus.Partial ||
                                highlightStatus is EpubHighlightStatus.Failed || highlightStatus == EpubHighlightStatus.Unsupported))
                                TextButton(onClick = onRetryHighlights, enabled = !highlightRetryBusy,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.highlights_retry), Modifier.fillMaxWidth(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                        }
                        if (state.session.book.format == BookFormat.PDF && onPdfNightMode != null) {
                            val statusMessage = when (pdfNightModeStatus) {
                                PdfNightModeStatus.Loading -> "PDF gece görünümü tercihi yükleniyor…"
                                PdfNightModeStatus.Saving -> "PDF gece görünümü tercihi kaydediliyor…"
                                PdfNightModeStatus.LoadFailed -> "PDF gece görünümü tercihi okunamadı."
                                PdfNightModeStatus.SaveFailed -> "PDF gece görünümü tercihi kaydedilemedi. Seçim bu oturumda sürüyor."
                                PdfNightModeStatus.Ready -> null
                            }
                            statusMessage?.let {
                                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    color = if (pdfNightModeStatus == PdfNightModeStatus.LoadFailed ||
                                        pdfNightModeStatus == PdfNightModeStatus.SaveFailed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if ((pdfNightModeStatus == PdfNightModeStatus.LoadFailed ||
                                pdfNightModeStatus == PdfNightModeStatus.SaveFailed) && onRetryPdfNightMode != null) {
                                TextButton(onClick = onRetryPdfNightMode) { Text("Tercihi yeniden dene") }
                            }
                            if (pdfNightMode && pdfNightModeStatus != PdfNightModeStatus.Loading &&
                                pdfNightModeStatus != PdfNightModeStatus.LoadFailed) {
                                Text("Gece görünümünde görsel ve diyagram renkleri de ters çevrilir.",
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (state.session.book.format.reflowable && !loadFailed && !saveFailed) {
                            readingTime?.let { ReadingTimeStatus(it) }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}


@Composable
private fun ReaderToolsSection(title: String) {
    Text(title.uppercase(java.util.Locale.forLanguageTag("tr")), Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}
