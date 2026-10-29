package com.yaprak.reader.reader

import androidx.fragment.app.Fragment
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.domain.reader.BookContents
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.passages.EpubPassage

/** Android rendering boundary. Readium types do not reach features or persisted models. */
interface ReaderSession : Closeable {
    val book: Book
    val resume: ReadingLocation?
    fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface
}

interface ReaderSurface {
    val fragment: Fragment
    val taps: ReaderTapController? get() = null
    val contentsNavigator: ContentsNavigator? get() = null
    val bookSearch: BookTextSearch? get() = null
    val bookmarkNavigator: BookmarkNavigator? get() = null
    val passageNavigator: PassageNavigator? get() = null
    val indexedChapterNavigator: IndexedChapterNavigator? get() = null
    val indexedPdfPageNavigator: IndexedPdfPageNavigator? get() = null
    val epubHighlights: EpubHighlights? get() = null
    val pageNavigator: PageNavigator? get() = null
    val bookPositionNavigator: BookPositionNavigator? get() = null
    val appearance: EpubAppearanceController? get() = null
    val epubImageFilter: EpubImageFilterController? get() = null
    val pdfNightMode: PdfNightModeController? get() = null
    val pdfSwipe: PdfSwipeController? get() = null
    val pdfView: PdfViewController? get() = null
    val pageThumbnails: PageThumbnailsSource? get() = pdfThumbnails
    val pdfThumbnails: PdfThumbnailsSource? get() = null
    val pdfPageLinks: PdfPageLinksSource? get() = null
    val pdfCrop: PdfCropController? get() = null
    val pdfSpread: PdfSpreadController? get() = null
    val pdfScroll: PdfScrollController? get() = null
    val comicSpread: ComicSpreadController? get() = null
    val textSelection: TextSelectionSource? get() = null
    val pdfOcr: PdfOcrSource? get() = null
    /** Opens already verified original annotation geometry; it does not create text-selection evidence. */
    val pdfAnnotationNavigator: PdfAnnotationNavigator? get() = null
    val pageLabel: Flow<String?> get() = flowOf(null)
    /** Recoverable page drawing failure, separate from a session opening or resource failure. */
    val renderingFailed: Flow<Boolean> get() = flowOf(false)
    /** Actual loaded document visibility; a locator or accepted navigation is insufficient proof. */
    val readingVisible: Flow<Boolean> get() = flowOf(false)
    /** EPUB/TXT approximate engine position, or PDF/CBZ exact drawn original page position. Never read coverage. */
    val bookPositionPercent: Flow<Int?> get() = flowOf(null)
    /** Optional precise live progress for transient pace; separate from saved position and coverage. */
    val readingPace: ReadingPaceSource? get() = null
    suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit)
    fun forward(): Boolean
    fun backward(): Boolean
}

/** Caller supplies a freshly verified current annotation; formats owns the original-point target. */
fun interface PdfAnnotationNavigator { fun goTo(anchor: PdfSourceAnchor): Boolean }

/** Transient results from the currently open book. Nothing here is persisted as reading progress. */
data class BookSearchHit(val title: String, val snippet: String?, val location: ReadingLocation)
data class BookSearchResults(val hits: List<BookSearchHit>, val truncated: Boolean)

interface BookTextSearch {
    val kind: BookSearchKind get() = BookSearchKind.EPUB
    val supported: Boolean get() = true
    suspend fun find(query: String): BookSearchResults
    /** Only a validated location in this publication may be passed to the renderer. */
    fun goTo(location: ReadingLocation): Boolean
}

enum class BookSearchKind { EPUB, PDF, TXT, FB2, DOCX }
enum class BookSearchFailure { UNSUPPORTED, LIMIT, INVALID_QUERY, SOURCE }
class BookSearchException(val reason: BookSearchFailure) : Exception(reason.name)

/** Optional capability: PDF and fixed-layout EPUB do not expose text preferences. */
fun interface EpubAppearanceController { fun apply(settings: EpubSettings) }

/** Temporary image treatment, effective only in a reflowable EPUB's dark theme. */
enum class EpubImageFilterMode { ORIGINAL, DARKEN, INVERT }

fun interface EpubImageFilterController { fun apply(mode: EpubImageFilterMode) }

/** Optional, manual PDF color transformation for the current rendering surface only. */
fun interface PdfNightModeController { fun apply(enabled: Boolean) }

/** Session choice; the renderer uses two real pages only when its viewport is wide enough. */
fun interface PdfSpreadController { fun apply(enabled: Boolean) }

/** Current open PDF session choice; a command never proves a visible page. */
interface PdfScrollController {
    val enabled: kotlinx.coroutines.flow.StateFlow<Boolean>
    fun apply(enabled: Boolean): Boolean
    /** Optional exact mode acknowledgement; legacy surfaces cannot accept a horizontal request. */
    val navigationMode: kotlinx.coroutines.flow.StateFlow<com.yaprak.reader.domain.settings.PdfNavigationMode>? get() = null
    fun apply(mode: com.yaprak.reader.domain.settings.PdfNavigationMode): Boolean =
        mode != com.yaprak.reader.domain.settings.PdfNavigationMode.HORIZONTAL_SCROLL &&
            apply(mode != com.yaprak.reader.domain.settings.PdfNavigationMode.PAGED)
}

/** CBZ session choice. A narrow viewport preserves the same exact primary page. */
interface ComicSpreadController {
    val preferred: kotlinx.coroutines.flow.StateFlow<Boolean>
    fun apply(enabled: Boolean): Boolean
    /** Admit an already verified saved preference without treating an initial page as drawn. */
    fun restore(enabled: Boolean): Boolean
}

/** The Activity supplies the current navigation gate; the PDF surface owns touch detection. */
fun interface PdfSwipeController { fun allowWhile(condition: () -> Boolean) }

/** Transient geometry commands; repeating a command resets manual zoom and pan. */
interface PdfViewController {
    fun fitPage()
    fun fitWidth()
}

/** A transient, user-made selection. It is not a durable annotation or reading anchor. */
data class SelectedText(
    val text: String,
    val rangeCandidate: SelectionRangeCandidate? = null,
    val sourceAnchor: EpubSourceAnchor? = null,
    val pdfPageIndex: Int? = null,
    val sourceSelectionId: String? = null,
    val pdfSourceAnchor: PdfSourceAnchor? = null,
    val pdfOcr: Boolean = false,
)

fun interface TextSelectionSource {
    /** Synchronous native selection ownership, used to protect keyboard reading navigation. */
    val active: Boolean get() = false
    suspend fun capture(): SelectedText?
    /** PDF long-press completion requests preview; EPUB's native selection stays explicit. */
    val requests: Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()
    fun clear() = Unit
    fun isCurrent(requestId: String?): Boolean = true
}

enum class TextSelectionFailure { TOO_LONG, INVALID_RESOURCE, UNSUPPORTED }
class TextSelectionException(val reason: TextSelectionFailure) : Exception(reason.name)

interface ContentsNavigator {
    val contents: BookContents
    /** Called only after opening the contents panel; adapters keep file/SDK work off the UI thread. */
    suspend fun loadContents(): BookContents = contents
    /** Exact native TOC mapping for original-source adapters; titles and ordinals are insufficient. */
    fun entryIdForResource(href: String): String? = null
    /** True means the engine accepted navigation; actual loading can still report an error. */
    fun goTo(entryId: String): Boolean
}

interface BookmarkNavigator {
    fun capture(): BookmarkTarget?
    fun goTo(location: ReadingLocation): Boolean
}

/** Opens only the containing EPUB chapter after rechecking the saved source match. */
interface PassageNavigator {
    suspend fun verifiedChapterHref(passage: EpubPassage): String?
    fun goToChapter(href: String): Boolean
}

/** Native paint is an optional surface capability, separate from durable annotation storage. */
interface EpubHighlights {
    val status: Flow<EpubHighlightStatus>
    suspend fun retry()
}

sealed interface EpubHighlightStatus {
    data object Loading : EpubHighlightStatus
    data class Ready(val total: Int) : EpubHighlightStatus
    data class Partial(val unresolved: Int, val total: Int) : EpubHighlightStatus
    data object Unsupported : EpubHighlightStatus
    data class Failed(val reason: EpubHighlightFailure) : EpubHighlightStatus
}

enum class EpubHighlightFailure { SOURCE_LIMIT, DOM_LIMIT, SOURCE_UNAVAILABLE, APPLY_FAILED }

/** Fixed-page documents only. All indices at this boundary are zero-based. */
interface PageNavigator {
    val pageCount: Int
    val currentPage: Int?
    /** A page that is actually rendered and still requested; null during loading or failure. */
    fun captureStablePage(): Int?
    fun goTo(pageIndex: Int): Boolean
}

/** Approximate whole-book positions in reflowable EPUB; never read-text coverage. */
interface BookPositionNavigator {
    /** Resolve against the current publication before entering the Activity's navigation gate. */
    suspend fun resolvePercent(percent: Int): ReadingLocation?
    /** True means accepted navigation; the real resource may still report a loading error. */
    fun goTo(location: ReadingLocation): Boolean
}

fun interface ReaderLoader { suspend fun open(bookId: String): ReaderSession }
enum class ReaderFailure { MISSING, CHANGED, INVALID, UNSUPPORTED, RECOVERY }
class ReaderOpenException(val reason: ReaderFailure) : Exception(reason.name)
