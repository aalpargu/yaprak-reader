package com.yaprak.reader.feature.reader

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
    var expanded by mutableStateOf(false)
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
    val toolsScroll = remember(additionalTools) { ScrollState(0) }
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
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
        val barHeight = maxHeight / 2
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
                Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(max = barHeight)
                    .verticalScroll(rememberScrollState()), tonalElevation = 3.dp) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { if (additionalTools.active) { additionalTools.close(); onBack() } }) { Text("Kitaplık") }
                            Text(state.session.book.title, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { if (additionalTools.active) { additionalTools.close(); onHide() } }) { Text("Gizle") }
                        }
                        if (loadFailed) Text("Bu bölüm yüklenemedi. Kitaplığa dönüp yeniden açmayı dene.", color = MaterialTheme.colorScheme.error)
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
                    }
                }
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = barHeight)
                    .verticalScroll(toolsScroll), tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
                        FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            if (onContents != null) TextButton(onClick = onContents) { Text("İçindekiler") }
                            if (onBookmarks != null) TextButton(onClick = onBookmarks) { Text("Yer imleri") }
                            if (onPageJump != null) TextButton(onClick = onPageJump,
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Sayfaya git", Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            if (state.session.book.format in setOf(BookFormat.PDF, BookFormat.CBZ, BookFormat.CBR) && onPdfThumbnails != null &&
                                visible && !loadFailed && !saveFailed)
                                TextButton(onClick = onPdfThumbnails, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.pdf_thumbnails_title), Modifier.fillMaxWidth(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            if (state.session.book.format.reflowable && onBookPositionJump != null)
                                TextButton(onClick = onBookPositionJump) { Text("Kitap yüzdesine git") }
                            if (onJumpBack != null) TextButton(onClick = onJumpBack,
                                modifier = Modifier.semantics { contentDescription = "Atlamadan önceki konuma dön" }) {
                                Text("Atlamadan önceki konuma dön")
                            }
                            if (onSettings != null) TextButton(onClick = onSettings) { Text("Görünüm") }
                            if (onBookAppearance != null) TextButton(onClick = onBookAppearance) { Text("Kitap görünümü") }
                            if (onSelectedText != null) TextButton(onClick = onSelectedText) { Text("Seçili metni göster") }
                            if (onSavedWords != null) TextButton(onClick = onSavedWords) { Text("Kelimeler") }
                            if (onBookNotes != null) TextButton(onClick = onBookNotes) { Text("Notlar") }
                            if (onPdfAnnotations != null) TextButton(onClick = onPdfAnnotations) { Text("PDF vurguları ve notlar") }
                            if (onEpubPassages != null) TextButton(onClick = onEpubPassages) { Text(stringResource(R.string.annotations_title)) }
                            if (onBookSearch != null) TextButton(onClick = onBookSearch) { Text("Kitapta ara") }
                            if (onBookAssistant != null && visible && !loadFailed && !saveFailed &&
                                com.yaprak.reader.domain.search.supportsBookSourceRetrieval(state.session.book.format)) {
                                key(state.session, onBookAssistant) {
                                    val lease = remember { PdfSpreadActionLease() }
                                    DisposableEffect(lease) { onDispose { lease.active = false } }
                                    TextButton(onClick = { if (lease.active) onBookAssistant() }, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.reader_book_assistant), Modifier.fillMaxWidth(),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                }
                            }
                        }
                        if (visible && !loadFailed && !saveFailed && toolCapabilities.any { it }) {
                            val toggleRevision = additionalTools.revision
                            val description = stringResource(if (additionalTools.expanded)
                                R.string.reader_additional_tools_expanded else R.string.reader_additional_tools_collapsed)
                            TextButton(onClick = { additionalTools.toggle(toggleRevision) }, modifier = Modifier.fillMaxWidth()
                                .testTag("reader-additional-tools").semantics { stateDescription = description }) {
                                Text(stringResource(if (additionalTools.expanded) R.string.reader_additional_tools_close
                                    else R.string.reader_additional_tools_open), Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        if (additionalTools.expanded || cropRecovery) {
                            FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                                if (state.session.book.format == BookFormat.PDF && extraPdfPageLinks != null &&
                                    visible && !loadFailed && !saveFailed) key(state.session, extraPdfPageLinks) {
                                    val lease = remember { PdfSpreadActionLease() }
                                    DisposableEffect(lease) { onDispose { lease.active = false } }
                                    TextButton({ if (lease.active) extraPdfPageLinks() }, Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.pdf_page_links_title))
                                    }
                                }
                                if (state.session.book.format == BookFormat.PDF && extraPdfCrop != null &&
                                    (visible || pdfCropApplyFailed) && !loadFailed && !saveFailed)
                                    key(state.session, pdfBookViewState, extraPdfCrop) {
                                        val lease = remember { PdfSpreadActionLease() }
                                        DisposableEffect(lease) { onDispose { lease.active = false } }
                                        TextButton(onClick = { if (lease.active) extraPdfCrop() },
                                            enabled = pdfBookViewState?.canEdit != false, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.pdf_crop_title), Modifier.fillMaxWidth(),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                    }
                                if (extraScreenAwake != null) TextButton(onClick = extraScreenAwake) { Text("Ekran") }
                                if (extraOrientation != null && visible && !loadFailed && !saveFailed)
                                    TextButton(onClick = extraOrientation) { Text(stringResource(R.string.reader_orientation_title)) }
                                if (extraBrightness != null && visible && !loadFailed && !saveFailed)
                                    TextButton(onClick = extraBrightness) { Text("Parlaklık") }
                                if (state.session.book.format == BookFormat.EPUB && extraEpubImages != null &&
                                    visible && !loadFailed && !saveFailed)
                                    TextButton(onClick = extraEpubImages) { Text("Koyu tema görselleri") }
                                if (state.session.book.format.comic && extraComicSpread != null && visible && !loadFailed && !saveFailed) {
                                    key(state.session, comicDoublePagePreferred, extraComicSpread, comicBookViewState) {
                                        val lease = remember { PdfSpreadActionLease() }
                                        DisposableEffect(lease) { onDispose { lease.active = false } }
                                        TextButton(onClick = { if (lease.active) extraComicSpread() },
                                            enabled = comicBookViewState?.canEdit != false, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(if (comicDoublePagePreferred) R.string.cbz_spread_single else R.string.cbz_spread_double),
                                                Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                    }
                                    Text(stringResource(R.string.cbz_spread_help), Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (state.session.book.format == BookFormat.PDF) {
                                    if (extraPdfNavigation != null && visible && !loadFailed && !saveFailed) {
                                        PdfNavigationChoices(pdfNavigationMode, pdfBookViewState?.canEdit != false, extraPdfNavigation)
                                    } else if (extraPdfScroll != null && visible && !loadFailed && !saveFailed) {
                                        key(state.session, pdfScrolling, pdfBookViewState) {
                                            val lease = remember { PdfSpreadActionLease() }
                                            DisposableEffect(lease) { onDispose { lease.active = false } }
                                            TextButton(onClick = { if (lease.active) extraPdfScroll() },
                                                enabled = pdfBookViewState?.canEdit != false, modifier = Modifier.fillMaxWidth()) {
                                                Text(stringResource(if (pdfScrolling) R.string.pdf_scroll_disable else R.string.pdf_scroll_enable),
                                                    Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                            }
                                        }
                                    }
                                    if (extraPdfSpread != null && visible && !loadFailed && !saveFailed) {
                                        key(state.session, pdfDoublePagePreferred, pdfBookViewState, pdfScrolling) {
                                            val lease = remember { PdfSpreadActionLease() }
                                            DisposableEffect(lease) { onDispose { lease.active = false } }
                                            TextButton(onClick = { if (lease.active) extraPdfSpread() },
                                                enabled = !pdfScrolling && pdfBookViewState?.canEdit != false, modifier = Modifier.fillMaxWidth()) {
                                                Text(stringResource(if (pdfDoublePagePreferred) R.string.pdf_spread_single
                                                    else R.string.pdf_spread_double), Modifier.fillMaxWidth(),
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                            }
                                        }
                                    }
                                    key(state.session, pdfBookViewState, extraPdfCrop) {
                                        val lease = remember { PdfSpreadActionLease() }
                                        DisposableEffect(lease) { onDispose { lease.active = false } }
                                        if (extraPdfFitPage != null) TextButton(onClick = { if (lease.active) extraPdfFitPage() },
                                            enabled = pdfBookViewState?.canEdit != false, modifier = Modifier.fillMaxWidth()) {
                                            Text("Sayfaya sığdır", Modifier.fillMaxWidth(),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                        if (extraPdfFitWidth != null) TextButton(onClick = { if (lease.active) extraPdfFitWidth() },
                                            enabled = pdfBookViewState?.canEdit != false, modifier = Modifier.fillMaxWidth()) {
                                            Text("Genişliğe sığdır", Modifier.fillMaxWidth(),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                    }
                                }
                                if (state.session.book.format == BookFormat.PDF && extraPdfNightMode != null) {
                                    TextButton(onClick = extraPdfNightMode,
                                        enabled = pdfNightModeStatus != PdfNightModeStatus.Loading &&
                                            pdfNightModeStatus != PdfNightModeStatus.LoadFailed) {
                                        Text(if (pdfNightMode) "Özgün renkler" else "Gece görünümü")
                                    }
                                }
                                if (extraTapZones != null && visible && !loadFailed && !saveFailed)
                                    TextButton(onClick = extraTapZones, modifier = Modifier.fillMaxWidth()) {
                                        Text("Dokunma bölgeleri")
                                    }
                                if (extraReaderControls != null && visible && !loadFailed && !saveFailed)
                                    TextButton(onClick = extraReaderControls, modifier = Modifier.fillMaxWidth()) {
                                        Text("Okuma kontrolleri")
                                    }
                                if (state.session.book.format == BookFormat.PDF && extraPdfOcr != null &&
                                    visible && !loadFailed && !saveFailed) key(extraPdfOcr) {
                                    val lease = remember { PdfSpreadActionLease() }
                                    DisposableEffect(lease) { onDispose { lease.active = false } }
                                    Column(Modifier.fillMaxWidth()) {
                                        Text("OCR, bu sayfadaki Latin harfli yazıyı tanımayı dener. Türkçe ve İngilizce desteklenir; sonuçları kontrol et. Görsel ve metin cihazda işlenir. Google ML Kit kullanım ve performans ölçümlerini Google ile paylaşabilir.",
                                            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                                        TextButton({ if (lease.active) extraPdfOcr() }, Modifier.fillMaxWidth()) {
                                            Text("Bu sayfada OCR · isteğe bağlı", Modifier.fillMaxWidth(),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                    }
                                }
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
                        pageLabel?.let {
                            Text(it, Modifier.padding(horizontal = 8.dp).semantics {
                                contentDescription = if (state.session.book.format == BookFormat.PDF) "PDF sayfası $it" else "Sayfa $it"
                            }, style = MaterialTheme.typography.labelLarge)
                        }
                        if (state.session.book.format.reflowable) {
                            bookPositionPercent?.takeIf { it in 0..100 }?.let { percent ->
                                Text("Yaklaşık kitap konumu: %$percent",
                                    Modifier.padding(horizontal = 8.dp).semantics {
                                        contentDescription = "Kitaptaki yaklaşık konum yüzde $percent"
                                    }, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (state.session.book.format.comic || state.session.book.format == BookFormat.PDF) {
                            bookPositionPercent?.takeIf { it in 0..100 }?.let { percent ->
                                val description = stringResource(if (state.session.book.format == BookFormat.PDF)
                                    R.string.pdf_page_position_description else R.string.cbz_page_position_description, percent)
                                Text(stringResource(R.string.fixed_page_position_percent, percent),
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp).semantics { contentDescription = description },
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                            TextButton(onClick = onPrevious, modifier = Modifier.fillMaxWidth()) {
                                Text("Önceki", Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                                Text("Sonraki", Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        Text(if (state.session.book.format == BookFormat.PDF) "Sola/sağa kaydır · İki parmakla yakınlaştır · Dokunarak araçları göster"
                            else "Sayfayı kaydır · Ortaya dokunarak araçları göster", Modifier.padding(bottom = 12.dp, start = 12.dp, end = 12.dp), style = MaterialTheme.typography.bodySmall)
                        Text("Araçlar gizliyken tam ekran okuma · Sistem çubukları için ekran kenarından kaydır",
                            Modifier.padding(bottom = 12.dp, start = 12.dp, end = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
