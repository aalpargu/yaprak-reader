package com.yaprak.reader.feature

import android.graphics.Bitmap
import androidx.fragment.app.Fragment
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.domain.settings.PdfNightSettingsRepository
import com.yaprak.reader.domain.settings.ReaderOrientation
import com.yaprak.reader.domain.settings.ReaderOrientationSettingsRepository
import com.yaprak.reader.feature.reader.PdfNightModeViewModel
import com.yaprak.reader.feature.reader.PdfBookViewPreferencesViewModel
import com.yaprak.reader.feature.reader.PdfThumbnailsRequest
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.ReaderViewModel
import com.yaprak.reader.feature.settings.ReaderOrientationViewModel
import com.yaprak.reader.reader.BookmarkNavigator
import com.yaprak.reader.reader.BookTextSearch
import com.yaprak.reader.reader.BookSearchHit
import com.yaprak.reader.reader.BookSearchResults
import com.yaprak.reader.reader.BookSearchKind
import com.yaprak.reader.reader.BookSearchException
import com.yaprak.reader.reader.BookSearchFailure
import com.yaprak.reader.feature.reader.BookSearchPhase
import com.yaprak.reader.feature.reader.BookSearchState
import com.yaprak.reader.feature.reader.SelectedTextPreview
import com.yaprak.reader.feature.reader.CopyStatus
import com.yaprak.reader.reader.SelectedText
import com.yaprak.reader.reader.TextSelectionSource
import com.yaprak.reader.reader.TextSelectionException
import com.yaprak.reader.reader.TextSelectionFailure
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.yaprak.reader.reader.PageNavigator
import com.yaprak.reader.reader.PdfNightModeController
import com.yaprak.reader.reader.PdfCropController
import com.yaprak.reader.reader.PdfSpreadController
import com.yaprak.reader.reader.PdfSwipeController
import com.yaprak.reader.reader.PdfThumbnailsSource
import com.yaprak.reader.reader.PdfViewController
import com.yaprak.reader.reader.ReaderLoader
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Real Activity/lifecycle gates with a controlled valid reader boundary, not native PDF evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
class PdfThumbnailsActivityTest {
    private class Storage : ReadingStateRepository {
        val records = mutableListOf<ReadingSnapshot>()
        override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
        override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) {
            records += snapshot
        }
    }

    private class Orientation : ReaderOrientationSettingsRepository {
        override val orientation = MutableStateFlow(ReaderOrientation.AUTO)
        val writes = mutableListOf<ReaderOrientation>()
        override suspend fun saveOrientation(orientation: ReaderOrientation) {
            writes += orientation; this.orientation.value = orientation
        }
    }

    private class Night : PdfNightSettingsRepository {
        override val pdfNightMode = MutableStateFlow(false)
        val writes = mutableListOf<Boolean>()
        override suspend fun savePdfNightMode(enabled: Boolean) {
            writes += enabled; pdfNightMode.value = enabled
        }
    }

    private class Source(override var pageCount: Int = 12) : PdfThumbnailsSource {
        val image = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        val calls = mutableListOf<Int>()
        var renderPage: suspend (Int) -> Bitmap = { image }
        override suspend fun render(pageIndex: Int): Bitmap {
            calls += pageIndex
            return renderPage(pageIndex)
        }
    }

    private class Navigator(override var pageCount: Int = 12) : PageNavigator {
        override var currentPage: Int? = 2
        var stablePage: Int? = 2
        var accepts = true
        var requestedPage = 2
        var onRequest: () -> Unit = {}
        val calls = mutableListOf<Int>()
        override fun captureStablePage() = stablePage
        override fun goTo(pageIndex: Int): Boolean {
            calls += pageIndex
            if (accepts && pageIndex != requestedPage) {
                requestedPage = pageIndex; currentPage = null; stablePage = null
                onRequest()
            }
            // An accepted request is not an observed/rendered destination.
            return accepts
        }
    }

    private class Surface(val tap: () -> Unit) : ReaderSurface {
        override val fragment = Fragment()
        override val renderingFailed = MutableStateFlow(false)
        val previews = Source()
        val pages = Navigator()
        val links = LinkSource(pages)
        override val pdfPageLinks = links
        val selections = SelectionSource()
        override var textSelection: TextSelectionSource? = selections
        val ocr = OcrSource { pages.stablePage }
        override var pdfOcr: com.yaprak.reader.reader.PdfOcrSource? = ocr
        val searchCalls = mutableListOf<String>()
        val searchNavigation = mutableListOf<ReadingLocation>()
        var searchSupported = true
        var searchResult: suspend (String) -> BookSearchResults = { BookSearchResults(listOf(BookSearchHit("Sayfa 8", "an alpha", location(7))), false) }
        override val bookSearch = object : BookTextSearch {
            override val kind = BookSearchKind.PDF
            override val supported get() = searchSupported
            override suspend fun find(query: String): BookSearchResults { searchCalls += query; return searchResult(query) }
            override fun goTo(location: ReadingLocation): Boolean {
                searchNavigation += location
                return pages.goTo(org.json.JSONObject(location.locatorJson).getInt("page"))
            }
        }
        override var pdfThumbnails: PdfThumbnailsSource? = previews
        override var pageNavigator: PageNavigator? = pages
        var swipeAllowed: (() -> Boolean)? = null
        var coverageAllowed: (() -> Boolean)? = null
        private val actualRendered = MutableStateFlow<ReadingSnapshot?>(null)
        var observations = 0
        var fits = 0
        var forwards = 0
        val nightApplies = mutableListOf<Boolean>()
        var onCapture: () -> Unit = {}
        init { pages.onRequest = { actualRendered.value = null } }
        override val pdfSwipe = PdfSwipeController { swipeAllowed = it }
        override val pdfCrop = PdfCropController { }
        override val pdfSpread = PdfSpreadController { }
        override val pdfScroll = ReaderTestPdfScrollController()
        override val pdfView = object : PdfViewController {
            override fun fitPage() { fits++ }
            override fun fitWidth() { fits++ }
        }
        override val pdfNightMode = PdfNightModeController { nightApplies += it }
        override val bookmarkNavigator = object : BookmarkNavigator {
            override fun capture(): BookmarkTarget? {
                onCapture()
                return pages.stablePage?.let { BookmarkTarget("Sayfa ${it + 1}", location(it)) }
            }
            override fun goTo(location: ReadingLocation) = true
        }
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
            observations++
            this.coverageAllowed = coverageAllowed
            // Like PdfReaderSession's real renderedPage StateFlow, a new subscription
            // immediately replays the actual page. Preview-only actions must not restart it.
            actualRendered.filterNotNull().collect { onSnapshot(it) }
        }
        fun reportActualPage(page: Int) {
            pages.stablePage = page; pages.currentPage = page; pages.requestedPage = page
            actualRendered.value = ReadingSnapshot(location(page))
        }
        override fun forward(): Boolean { forwards++; return true }
        override fun backward() = true
    }

    private class LinkSource(private val pages: Navigator) : com.yaprak.reader.reader.PdfPageLinksSource {
        override val supported = true
        var calls = 0
        var resolveTarget: suspend () -> ReadingLocation = { location(7) }
        override suspend fun load(pageIndex: Int): List<com.yaprak.reader.reader.PdfPageLink> {
            calls++; return listOf(com.yaprak.reader.reader.PdfPageLink("link", 1, 7))
        }
        override suspend fun resolve(pageIndex: Int, linkId: String) = resolveTarget()
        override fun goTo(location: ReadingLocation) = pages.goTo(org.json.JSONObject(location.locatorJson).getInt("page"))
    }

    @Test fun pdfPageLinksBlockCompetingToolsAndExplicitNavigationKeepsJumpBackHistory() = runTest {
        withHost {
            surface.reportActualPage(2); runCurrent()
            val previousRecords = storage.records.toList()
            openLinks(activity, surface, 2); runCurrent()
            val controller = property(activity, "getPdfPageLinks") as com.yaprak.reader.feature.reader.PdfPageLinksController
            val ready = controller.state.value
            assertEquals(7, ready.links.single().targetPageIndex)
            assertFalse(surface.swipeAllowed!!.invoke()); assertFalse(surface.coverageAllowed!!.invoke())
            assertFalse(navigate(activity) { surface.forward() })
            invoke(activity, "openPdfThumbnails"); invoke(activity, "openBookSearch"); invoke(activity, "openOrientation")
            assertNull(request(activity)); assertFalse(property(activity, "getBookSearchOpen") as Boolean)
            assertFalse(orientation.state.value.open)
            assertEquals(previousRecords, storage.records)
            controller.select(ready, "link"); runCurrent()
            assertEquals(listOf(7), surface.pages.calls)
            assertFalse(controller.state.value.open)
            assertTrue(property(activity, "getCanJumpBack") as Boolean)
            assertEquals(previousRecords, storage.records)
        }
    }

    @Test fun pdfPageLinksPauseCancelsDelayedDestinationAndOldCallbacksCannotReopenIt() = runTest {
        withHost {
            val delayed = CompletableDeferred<ReadingLocation>()
            surface.links.resolveTarget = { withContext(NonCancellable) { delayed.await() } }
            openLinks(activity, surface, 2); runCurrent()
            val controller = property(activity, "getPdfPageLinks") as com.yaprak.reader.feature.reader.PdfPageLinksController
            val ready = controller.state.value
            controller.select(ready, "link"); runCurrent()
            host.pause(); runCurrent(); assertFalse(controller.state.value.open)
            host.resume(); runCurrent()
            delayed.complete(location(7)); runCurrent()
            controller.select(ready, "link"); controller.retry(ready); runCurrent()
            assertTrue(surface.pages.calls.isEmpty()); assertFalse(controller.state.value.open)
        }
    }

    @Test fun pdfPageLinksRejectWrongSourcePageAndPageChangedDuringResolution() = runTest {
        withHost {
            openLinks(activity, surface, 3); runCurrent()
            val controller = property(activity, "getPdfPageLinks") as com.yaprak.reader.feature.reader.PdfPageLinksController
            assertFalse(controller.state.value.open); assertEquals(0, surface.links.calls)
            val delayed = CompletableDeferred<ReadingLocation>()
            surface.links.resolveTarget = { delayed.await() }
            openLinks(activity, surface, 2); runCurrent()
            controller.select(controller.state.value, "link"); runCurrent()
            surface.reportActualPage(3); runCurrent()
            delayed.complete(location(7)); runCurrent()
            assertTrue(surface.pages.calls.isEmpty()); assertFalse(controller.state.value.open)
        }
    }

    private fun openLinks(activity: ReaderActivity, owner: ReaderSurface, page: Int) {
        activity.javaClass.getDeclaredMethod("openPdfPageLinks", ReaderSurface::class.java, Int::class.javaPrimitiveType!!)
            .apply { isAccessible = true }.invoke(activity, owner, page)
    }

    @Test fun nativePdfAnchorSurvivesPreviewAndItsAnnotationWindowBlocksCompetingActionsThenCancelsOnPause() = runTest {
        withHost {
            surface.tap(); runCurrent()
            surface.selections.captureText = { SelectedText(PDF_TEST_TEXT, pdfPageIndex = 3,
                sourceSelectionId = surface.selections.id, pdfSourceAnchor = PDF_TEST_ANCHOR) }
            surface.selections.accept(); runCurrent()
            val preview = property(activity, "getSelectionPreview") as SelectedTextPreview.Ready
            assertEquals(PDF_TEST_ANCHOR, preview.pdfSourceAnchor)
            invoke(activity, "annotatePdfSelection"); runCurrent()
            val window = property(activity, "getPdfAnnotationRequest") as com.yaprak.reader.feature.pdfannotations.PdfAnnotationRequest
            assertEquals(PDF_TEST_TEXT, window.text); assertEquals(3, window.anchor.pageIndex)
            assertNull(property(activity, "getSelectionPreview")); assertEquals(2, surface.pages.currentPage)
            assertFalse(surface.swipeAllowed!!.invoke()); assertFalse(surface.coverageAllowed!!.invoke())
            assertFalse(navigate(activity) { surface.forward() })
            invoke(activity, "openPdfThumbnails"); invoke(activity, "openBookNotes"); invoke(activity, "openOrientation")
            assertNull(request(activity)); assertFalse(property(activity, "getBookNotesOpen") as Boolean); assertFalse(orientation.state.value.open)
            host.pause(); runCurrent(); assertFalse(window.lifetime.isActive); assertNull(property(activity, "getPdfAnnotationRequest"))
            host.resume(); runCurrent(); assertNull(property(activity, "getPdfAnnotationRequest")); assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun pdfAnnotationRequiresActualNativeGeometryAndCannotBorrowAnEpubAnchorOrAnotherPdfPage() = runTest {
        withHost {
            surface.tap(); runCurrent(); surface.selections.accept(); runCurrent()
            invoke(activity, "annotatePdfSelection"); assertNull(property(activity, "getPdfAnnotationRequest"))
            invoke(activity, "clearSelectionPreview"); runCurrent()
            surface.selections.captureText = { SelectedText(PDF_TEST_TEXT, pdfPageIndex = 2,
                sourceSelectionId = surface.selections.id, pdfSourceAnchor = PDF_TEST_ANCHOR) }
            surface.selections.accept(); runCurrent(); assertTrue(property(activity, "getSelectionPreview") is SelectedTextPreview.Error)
            invoke(activity, "annotatePdfSelection"); assertNull(property(activity, "getPdfAnnotationRequest"))
            invoke(activity, "clearSelectionPreview"); runCurrent()
            surface.selections.captureText = { SelectedText("Leaf", sourceAnchor = com.yaprak.reader.reader.SourceTextAnchor("chapter.xhtml",0,4,"a".repeat(64)),
                pdfPageIndex = 3, sourceSelectionId = surface.selections.id) }
            surface.selections.accept(); runCurrent(); assertTrue(property(activity, "getSelectionPreview") is SelectedTextPreview.Error)
            assertTrue(surface.pages.calls.isEmpty()); assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun pdfAnnotationListBlocksGenericNavigationAndPauseRevokesItsWindowLifetime() = runTest {
        withHost {
            surface.tap(); runCurrent(); invoke(activity,"openPdfAnnotations"); runCurrent()
            val window=property(activity,"getPdfAnnotationsRequest") as com.yaprak.reader.feature.pdfannotations.PdfAnnotationsWindow
            assertFalse(surface.swipeAllowed!!.invoke()); assertFalse(surface.coverageAllowed!!.invoke())
            assertFalse(navigate(activity) { surface.forward() }); assertEquals(0,surface.forwards)
            host.pause(); runCurrent(); assertFalse(window.lifetime.isActive); assertNull(property(activity,"getPdfAnnotationsRequest"))
            host.resume(); runCurrent(); assertNull(property(activity,"getPdfAnnotationsRequest")); assertTrue(storage.records.isEmpty())
        }
    }
    private class OcrSource(val page: () -> Int?) : com.yaprak.reader.reader.PdfOcrSource {
        var id: String? = null; var captures = 0; var prepares = 0; var index = 0
        var captureText: suspend () -> SelectedText? = {
            SelectedText("OCR Türkçe 🌿", pdfPageIndex = index, sourceSelectionId = id, pdfOcr = true)
        }
        override fun prepare(pageIndex: Int): Boolean {
            if (page() != pageIndex) return false
            prepares++; index = pageIndex; id = UUID.randomUUID().toString(); return true
        }
        override fun isCurrent(requestId: String?) = id != null && page() == index && (requestId == null || id == requestId)
        override fun clear() { id = null }
        override suspend fun capture(): SelectedText? { captures++; return captureText() }
    }
    private fun ocr(activity: ReaderActivity, expected: ReaderSurface, page: Int) {
        activity.javaClass.getDeclaredMethod("openPdfOcr", ReaderSurface::class.java, Int::class.javaPrimitiveType!!)
            .apply { isAccessible = true }.invoke(activity, expected, page)
    }
    @Test fun ocrGeneralNoteTransferWorksWithoutTheAndroidFifteenNativeSelectionCapability() = runTest {
        withHost {
            surface.textSelection = null; surface.tap(); runCurrent(); ocr(activity, surface, 2); runCurrent()
            invoke(activity, "transferSelectedNote"); runCurrent()
            assertTrue(property(activity, "getBookNotesOpen") as Boolean)
            assertEquals("OCR Türkçe 🌿", property(activity, "getBookNotePrefill"))
            assertNull(property(activity, "getSelectionPreview")); assertNull(surface.ocr.id)
            assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun ocrWordTransferWorksWithoutNativeSelectionAndDoesNotSaveAnUnconfirmedWord() = runTest {
        withHost {
            surface.textSelection = null; surface.tap(); runCurrent()
            surface.ocr.captureText = { SelectedText("alpha", pdfPageIndex = 2, sourceSelectionId = surface.ocr.id, pdfOcr = true) }
            ocr(activity, surface, 2); runCurrent(); invoke(activity, "transferSelectedWord"); runCurrent()
            assertTrue(property(activity, "getSavedWordsOpen") as Boolean)
            assertEquals("alpha", property(activity, "getSavedWordPrefill"))
            assertNull(property(activity, "getSelectionPreview")); assertNull(surface.ocr.id); assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun ocrRequiresAnExplicitCurrentStablePageAndUsesTheExistingModalWithoutChangingProgress() = runTest {
        withHost {
            surface.tap(); runCurrent()
            assertEquals(0, surface.ocr.captures); assertEquals(0, surface.ocr.prepares)
            val finish = CompletableDeferred<SelectedText?>(); surface.ocr.captureText = { finish.await() }
            ocr(activity, surface, 2); runCurrent()
            val loading = property(activity, "getSelectionPreview") as SelectedTextPreview.Loading; assertTrue(loading.pdfOcr)
            val id = surface.ocr.id
            assertFalse(surface.coverageAllowed!!.invoke()); assertFalse(surface.swipeAllowed!!.invoke())
            invoke(activity, "openBookNotes"); invoke(activity, "openPdfThumbnails"); invoke(activity, "openOrientation")
            assertFalse(property(activity, "getBookNotesOpen") as Boolean); assertNull(request(activity)); assertFalse(orientation.state.value.open)
            assertFalse(navigate(activity) { surface.forward() }); ocr(activity, surface, 2); assertEquals(1, surface.ocr.captures)
            finish.complete(SelectedText("OCR Türkçe 🌿", pdfPageIndex = 2, sourceSelectionId = id, pdfOcr = true)); runCurrent()
            val ready = property(activity, "getSelectionPreview") as SelectedTextPreview.Ready
            assertTrue(ready.pdfOcr); assertEquals(loading.sessionId, ready.sessionId); assertEquals(2, ready.pdfPageIndex)
            assertNull(ready.sourceAnchor); assertNull(ready.pdfSourceAnchor); assertNull(ready.rangeCandidate)
            assertTrue(property(activity, "isSelectionCurrent") as Boolean)
            invoke(activity, "annotateSelectedText"); invoke(activity, "annotatePdfSelection"); invoke(activity, "openSelectionAssistant")
            assertNull(property(activity, "getAnnotationRequest")); assertNull(property(activity, "getPdfAnnotationRequest"))
            assertNull(property(activity, "getSelectionAssistantState")); assertSame(ready, property(activity, "getSelectionPreview"))
            assertEquals(2, surface.pages.currentPage); assertTrue(surface.pages.calls.isEmpty()); assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun ocrPauseClearsTheRequestAndLateNativeCompletionCannotReopenOrReplaceTheNewPreview() = runTest {
        withHost {
            surface.tap(); runCurrent()
            val finish = CompletableDeferred<SelectedText?>(); surface.ocr.captureText = { withContext(NonCancellable) { finish.await() } }
            ocr(activity, surface, 2); runCurrent(); val oldId = surface.ocr.id
            host.pause(); runCurrent(); assertNull(surface.ocr.id); assertNull(property(activity, "getSelectionPreview"))
            ocr(activity, surface, 2); assertEquals(1, surface.ocr.captures)
            host.resume(); runCurrent()
            surface.ocr.captureText = { SelectedText("new OCR", pdfPageIndex = 2, sourceSelectionId = surface.ocr.id, pdfOcr = true) }
            ocr(activity, surface, 2); runCurrent(); val current = property(activity, "getSelectionPreview")
            finish.complete(SelectedText("late OCR", pdfPageIndex = 2, sourceSelectionId = oldId, pdfOcr = true)); runCurrent()
            assertSame(current, property(activity, "getSelectionPreview")); assertEquals("new OCR", (current as SelectedTextPreview.Ready).text)
            assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun ocrOldPageOwnerMissingCapabilityAndCompetingModalCannotStartWork() = runTest {
        withHost {
            surface.tap(); runCurrent()
            ocr(activity, surface, 1); ocr(activity, Surface {}, 2); assertEquals(0, surface.ocr.prepares)
            surface.pdfOcr = null; ocr(activity, surface, 2); assertEquals(0, surface.ocr.prepares)
            surface.pdfOcr = surface.ocr
            invoke(activity, "openPdfThumbnails"); ocr(activity, surface, 2); assertEquals(0, surface.ocr.prepares)
            dismiss(activity, request(activity)!!); runCurrent()
            session.book = session.book.copy(format = BookFormat.EPUB); ocr(activity, surface, 2); assertEquals(0, surface.ocr.prepares)
        }
    }
    @Test fun ocrInvalidOutputCannotBorrowAnAnchorAnotherPageOrAnUnmarkedNativeResult() = runTest {
        withHost {
            surface.tap(); runCurrent()
            for (forge in listOf<(String?) -> SelectedText>(
                { SelectedText("OCR", pdfPageIndex = 1, sourceSelectionId = it, pdfOcr = true) },
                { SelectedText("OCR", pdfPageIndex = 2, sourceSelectionId = it) },
                { SelectedText("Leaf", sourceAnchor = com.yaprak.reader.reader.SourceTextAnchor("chapter.xhtml", 0, 4, "a".repeat(64)),
                    pdfPageIndex = 2, sourceSelectionId = it, pdfOcr = true) },
                { SelectedText(PDF_TEST_TEXT, pdfPageIndex = 2, sourceSelectionId = it, pdfSourceAnchor = PDF_TEST_ANCHOR, pdfOcr = true) },
                { SelectedText("a".repeat(5001), pdfPageIndex = 2, sourceSelectionId = it, pdfOcr = true) },
                { SelectedText("a\u0000", pdfPageIndex = 2, sourceSelectionId = it, pdfOcr = true) })) {
                surface.ocr.captureText = { forge(surface.ocr.id) }; ocr(activity, surface, 2); runCurrent()
                assertTrue(property(activity, "getSelectionPreview") is SelectedTextPreview.Error)
                invoke(activity, "clearSelectionPreview"); invoke(activity, "observeSurface"); runCurrent()
            }
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }
    @Test fun ocrCurrentActionLeaseRequiresTheActualCapabilityAndStableSourcePage() = runTest {
        withHost {
            surface.tap(); runCurrent(); ocr(activity, surface, 2); runCurrent()
            assertTrue(property(activity, "isSelectionCurrent") as Boolean)
            surface.pdfOcr = null; assertFalse(property(activity, "isSelectionCurrent") as Boolean)
            surface.pdfOcr = surface.ocr; assertTrue(property(activity, "isSelectionCurrent") as Boolean)
            surface.pages.stablePage = 1; assertFalse(property(activity, "isSelectionCurrent") as Boolean)
            invoke(activity, "copySelectedText"); assertEquals(CopyStatus.NONE, (property(activity, "getSelectionPreview") as SelectedTextPreview.Ready).copyStatus)
            assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun emptyAndBusyOcrShowErrorsWithNoInventedResultAndCanCloseForRetry() = runTest {
        withHost {
            surface.tap(); runCurrent()
            surface.ocr.captureText = { null }; ocr(activity, surface, 2); runCurrent()
            assertTrue((property(activity, "getSelectionPreview") as SelectedTextPreview.Error).message.contains("OCR metni bulunamadı"))
            invoke(activity, "clearSelectionPreview"); invoke(activity, "observeSurface"); runCurrent()
            surface.ocr.captureText = { throw com.yaprak.reader.reader.PdfOcrException(com.yaprak.reader.reader.PdfOcrFailure.BUSY) }
            ocr(activity, surface, 2); runCurrent()
            assertTrue((property(activity, "getSelectionPreview") as SelectedTextPreview.Error).message.contains("kaynaklarını bırakmayı"))
            invoke(activity, "clearSelectionPreview"); invoke(activity, "observeSurface"); runCurrent()
            assertTrue(surface.coverageAllowed!!.invoke()); assertTrue(surface.swipeAllowed!!.invoke())
        }
    }
    private class SelectionSource : TextSelectionSource {
        override val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var id: String? = null
        var captures = 0
        var clears = 0
        var captureText: suspend () -> SelectedText? = { SelectedText("alpha 🌿", pdfPageIndex = 3, sourceSelectionId = id) }
        fun accept(): String {
            return UUID.randomUUID().toString().also { id = it; requests.tryEmit(Unit) }
        }
        override fun isCurrent(requestId: String?) = id != null && (requestId == null || id == requestId)
        override suspend fun capture(): SelectedText? { captures++; return captureText() }
        override fun clear() { clears++; id = null }
    }

    private class Session : ReaderSession {
        override var book = Book("thumbnail-book", "PDF integration", "İstanbul", BookFormat.PDF,
            "thumbnails.pdf", "thumbnails.pdf", 1, "a".repeat(64), 1, pageCount = 12)
        override val resume = location(2)
        val initials = mutableListOf<ReadingLocation?>()
        val surfaces = mutableListOf<Surface>()
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit,
            onError: () -> Unit): ReaderSurface {
            initials += initial
            return Surface(onTap).also { surfaces += it }
        }
        override fun close() = Unit
    }

    private class Fixture(
        val host: ActivityController<ReaderActivity>,
        val session: Session,
        val storage: Storage,
        val reader: ReaderViewModel,
        val orientation: ReaderOrientationViewModel,
        val orientationPreferences: Orientation,
        val nightPreferences: Night,
        val translation: com.yaprak.reader.feature.translation.TranslationViewModel,
    ) {
        val activity: ReaderActivity get() = host.get()
        val surface: Surface get() = session.surfaces.last()
    }

    private class TranslationService : com.yaprak.reader.domain.translation.EnglishTurkishTranslation {
        var checks = 0; var downloads = 0; var deletes = 0
        val texts = mutableListOf<String>()
        var run: suspend (String) -> String = { "Türkçe 🌿" }
        override suspend fun models(): com.yaprak.reader.domain.translation.TranslationModels {
            checks++; return com.yaprak.reader.domain.translation.TranslationModels.READY
        }
        override suspend fun downloadOverWifi() { downloads++ }
        override suspend fun translate(text: String): String { texts += text; return run(text) }
        override suspend fun deleteTurkishModel() { deletes++ }
    }
    @Test fun translatedNativeWordOpensTwoReviewableFieldsInTheSameBookWithoutSavingOrRecordingProgress() = runTest {
        val service = TranslationService().apply { run = { " yaprak " } }
        withHost(translationService = service) {
            surface.selections.captureText = { SelectedText(" Leaf ", pdfPageIndex = 3, sourceSelectionId = surface.selections.id) }
            surface.selections.accept(); runCurrent(); invoke(activity, "openSelectionTranslation"); runCurrent()
            translation.translate(translation.state.value!!); runCurrent(); val complete = translation.state.value!!
            invoke(activity, "transferTranslatedWord", complete.javaClass, complete.copy())
            assertFalse(property(activity, "getSavedWordsOpen") as Boolean)
            invoke(activity, "transferTranslatedWord", complete.javaClass, complete); runCurrent()
            val request = property(activity, "getTranslatedWordPrefill") as com.yaprak.reader.feature.vocabulary.TranslatedWordPrefill
            assertTrue(request.matches(session.book)); assertEquals("Leaf", request.draft.english); assertEquals("yaprak", request.draft.turkish)
            assertNull(property(activity, "getSavedWordPrefill")); assertTrue(property(activity, "getSavedWordsOpen") as Boolean)
            assertNull(translation.state.value); assertNull(property(activity, "getSelectionPreview"))
            assertFalse(surface.coverageAllowed!!.invoke()); assertFalse(surface.swipeAllowed!!.invoke())
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty()); assertEquals(1, service.checks)
            assertFalse(translationCurrent(activity, complete)); assertFalse(request.matches(session.book.copy(sha256 = "b".repeat(64))))
        }
    }
    @Test fun translatedWordTransferRejectsParagraphsInvalidMeaningAndLostSourceWithoutOpeningTheForm() = runTest {
        for ((source, output) in listOf("two words" to "iki kelime", "Leaf" to "two\nlines", "Leaf" to "a".repeat(201))) {
            val service = TranslationService().apply { run = { output } }
            withHost(translationService = service) {
                surface.selections.captureText = { SelectedText(source, pdfPageIndex = 3, sourceSelectionId = surface.selections.id) }
                surface.selections.accept(); runCurrent(); invoke(activity, "openSelectionTranslation"); runCurrent()
                translation.translate(translation.state.value!!); runCurrent(); val complete = translation.state.value!!
                invoke(activity, "transferTranslatedWord", complete.javaClass, complete)
                assertFalse(property(activity, "getSavedWordsOpen") as Boolean); assertNull(property(activity, "getTranslatedWordPrefill"))
                assertSame(complete, translation.state.value); assertTrue(storage.records.isEmpty())
            }
        }
        withHost(translationService = TranslationService().apply { run = { "yaprak" } }) {
            surface.selections.captureText = { SelectedText("Leaf", pdfPageIndex = 3, sourceSelectionId = surface.selections.id) }
            surface.selections.accept(); runCurrent(); invoke(activity, "openSelectionTranslation"); runCurrent()
            translation.translate(translation.state.value!!); runCurrent(); val complete = translation.state.value!!
            surface.selections.clear(); invoke(activity, "transferTranslatedWord", complete.javaClass, complete)
            assertFalse(property(activity, "getSavedWordsOpen") as Boolean); assertNull(property(activity, "getTranslatedWordPrefill"))
        }
    }
    @Test fun translatedOcrWordWorksOnOldPdfApisAndLegacyTransferCannotBorrowItsMeaning() = runTest {
        withHost(translationService = TranslationService().apply { run = { "yaprak" } }) {
            surface.textSelection = null
            surface.ocr.captureText = { SelectedText("Leaf", pdfPageIndex = 2, sourceSelectionId = surface.ocr.id, pdfOcr = true) }
            surface.tap(); runCurrent(); ocr(activity, surface, 2); runCurrent()
            invoke(activity, "openSelectionTranslation"); runCurrent(); translation.translate(translation.state.value!!); runCurrent()
            val complete = translation.state.value!!
            invoke(activity, "transferTranslatedWord", complete.javaClass, complete); runCurrent()
            val request = property(activity, "getTranslatedWordPrefill") as com.yaprak.reader.feature.vocabulary.TranslatedWordPrefill
            assertEquals("Leaf", request.draft.english); assertEquals("yaprak", request.draft.turkish)
            assertNull(property(activity, "getSelectionAssistantState")); assertNull(property(activity, "getPdfAnnotationRequest"))
            // Simulate closing the form and a fresh legacy transfer; its request carries only English.
            invoke(activity, "setSavedWordsOpen", Boolean::class.javaPrimitiveType!!, false)
            invoke(activity, "observeSurface"); runCurrent(); ocr(activity, surface, 2); runCurrent()
            invoke(activity, "transferSelectedWord"); runCurrent()
            assertEquals("Leaf", property(activity, "getSavedWordPrefill")); assertNull(property(activity, "getTranslatedWordPrefill"))
            assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun translationUsesTheExactCurrentSelectionAndOriginalCopyDoesNotRevokeItsPendingResult() = runTest {
        val service = TranslationService(); val late = CompletableDeferred<String>()
        service.run = { late.await() }
        withHost(translationService = service) {
            assertEquals(0, service.checks)
            surface.tap(); runCurrent(); surface.selections.accept(); runCurrent()
            val preview = property(activity, "getSelectionPreview") as SelectedTextPreview.Ready
            invoke(activity, "openSelectionTranslation"); runCurrent()
            assertEquals(1, service.checks); assertTrue(service.texts.isEmpty()); assertEquals(0, service.downloads)
            val ready = translation.state.value!!
            assertTrue(translationCurrent(activity, ready)); assertFalse(translationCurrent(activity, ready.copy()))
            translation.translate(ready); runCurrent()
            invoke(activity, "copySelectedText"); runCurrent()
            val copied = property(activity, "getSelectionPreview") as SelectedTextPreview.Ready
            assertEquals(preview.sessionId, copied.sessionId); assertEquals(CopyStatus.COPIED, copied.copyStatus)
            late.complete("Türkçe 🌿"); runCurrent()
            val completed = translation.state.value!!
            assertSame(completed, property(activity, "getTranslationState")); assertTrue(translationCurrent(activity, completed))
            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(preview.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(listOf(preview.text), service.texts); assertEquals(preview.text, copied.text)
            assertNull(copied.sourceAnchor); assertNull(property(activity, "getAnnotationRequest"))
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }
    @Test fun translationPauseDropsLateCompletionAndAReopenedSelectionRequiresItsOwnExplicitAction() = runTest {
        val service = TranslationService(); val late = CompletableDeferred<String>()
        service.run = { withContext(NonCancellable) { late.await() } }
        withHost(translationService = service) {
            surface.selections.accept(); runCurrent(); invoke(activity, "openSelectionTranslation"); runCurrent()
            val old = translation.state.value!!; translation.translate(old); runCurrent()
            host.pause(); runCurrent(); assertNull(translation.state.value); assertNull(property(activity, "getTranslationState"))
            host.resume(); runCurrent(); surface.selections.accept(); runCurrent()
            val current = property(activity, "getSelectionPreview")
            late.complete("obsolete"); runCurrent()
            assertSame(current, property(activity, "getSelectionPreview")); assertNull(translation.state.value)
            assertFalse(translationCurrent(activity, old)); assertEquals(1, service.checks)
            invoke(activity, "openSelectionTranslation"); runCurrent(); assertEquals(2, service.checks)
            assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun translationCannotStartWithoutAReadySourceAndLosingItsNativeLeaseRejectsAllActions() = runTest {
        val service = TranslationService()
        withHost(translationService = service) {
            invoke(activity, "openSelectionTranslation"); assertNull(translation.state.value); assertEquals(0, service.checks)
            surface.selections.accept(); runCurrent(); invoke(activity, "openSelectionTranslation"); runCurrent()
            val old = translation.state.value!!
            surface.selections.clear()
            assertFalse(translationCurrent(activity, old)); assertFalse(translation.translate(old))
            assertFalse(translation.deleteModel(old)); assertFalse(translation.copy(old) { fail("stale clipboard") })
            assertTrue(service.texts.isEmpty()); assertEquals(0, service.deletes)
            invoke(activity, "clearSelectionPreview"); runCurrent(); assertNull(translation.state.value)
            assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun ocrTranslationWorksWithoutNativePdfSelectionAndCannotBecomeAnAiOrAnnotationAnchor() = runTest {
        val service = TranslationService()
        withHost(translationService = service) {
            surface.textSelection = null
            surface.ocr.captureText = { SelectedText("English page", pdfPageIndex = 2, sourceSelectionId = surface.ocr.id, pdfOcr = true) }
            surface.tap(); runCurrent(); ocr(activity, surface, 2); runCurrent()
            val preview = property(activity, "getSelectionPreview") as SelectedTextPreview.Ready
            invoke(activity, "openSelectionTranslation"); runCurrent(); translation.translate(translation.state.value!!); runCurrent()
            assertEquals(listOf("English page"), service.texts)
            assertTrue(translationCurrent(activity, translation.state.value!!)); assertEquals(preview, property(activity, "getSelectionPreview"))
            invoke(activity, "openSelectionAssistant"); invoke(activity, "annotateSelectedText"); invoke(activity, "annotatePdfSelection")
            assertNull(property(activity, "getSelectionAssistantState")); assertNull(property(activity, "getAnnotationRequest"))
            assertNull(property(activity, "getPdfAnnotationRequest")); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `browsing and rendering previews do not navigate or record and modal blocks reader tools and coverage`() = runTest {
        withHost {
            surface.tap(); runCurrent()
            assertTrue(surface.swipeAllowed!!.invoke())
            assertTrue(surface.coverageAllowed!!.invoke())
            val applies = surface.nightApplies.toList()
            invoke(activity, "openPdfThumbnails"); runCurrent()
            val request = request(activity)!!
            assertEquals(12, request.pageCount); assertEquals(2, request.currentPage)
            assertEquals(request.id, UUID.fromString(request.id).toString())
            assertTrue(current(activity, request))
            assertSame(surface.previews.image, render(activity, request, 1))
            assertSame(surface.previews.image, render(activity, request, 10))
            assertEquals(listOf(1, 10), surface.previews.calls)
            assertFalse(surface.swipeAllowed!!.invoke()); assertFalse(surface.coverageAllowed!!.invoke())
            invoke(activity, "fitPdf", Boolean::class.javaPrimitiveType!!, true)
            invoke(activity, "fitPdf", Boolean::class.javaPrimitiveType!!, false)
            assertFalse(navigate(activity) { surface.forward() })
            var toolbarCalls = 0
            activity.javaClass.getDeclaredMethod("readerAction", Function0::class.java)
                .apply { isAccessible = true }.invoke(activity, { toolbarCalls++ })
            invoke(activity, "openOrientation"); invoke(activity, "openBrightness")
            invoke(activity, "openBookNotes"); invoke(activity, "togglePdfNightMode")
            invoke(activity, "retryPdfNightMode"); runCurrent()
            assertFalse(orientation.state.value.open)
            assertFalse(property(activity, "getBrightnessOpen") as Boolean)
            assertFalse(property(activity, "getBookNotesOpen") as Boolean)
            assertEquals(0, toolbarCalls); assertEquals(1, surface.fits); assertEquals(0, surface.forwards)
            assertEquals(applies, surface.nightApplies)
            assertTrue(nightPreferences.writes.isEmpty()); assertTrue(orientationPreferences.writes.isEmpty())
            assertTrue(surface.pages.calls.isEmpty()); assertTrue(storage.records.isEmpty())
            dismiss(activity, request); runCurrent()
            assertNull(request(activity)); assertTrue(surface.swipeAllowed!!.invoke())
            assertTrue(surface.coverageAllowed!!.invoke()); assertFalse(surface.previews.image.isRecycled)
        }
    }

    @Test fun `non PDF missing capability and mismatched page counts never admit a browser`() = runTest {
        withHost {
            val book = session.book
            session.book = book.copy(format = BookFormat.EPUB, pageCount = null)
            invoke(activity, "openPdfThumbnails"); assertNull(request(activity))
            session.book = book
            surface.pdfThumbnails = null
            invoke(activity, "openPdfThumbnails"); assertNull(request(activity))
            surface.pdfThumbnails = surface.previews
            surface.pageNavigator = null
            invoke(activity, "openPdfThumbnails"); assertNull(request(activity))
            surface.pageNavigator = surface.pages
            surface.previews.pageCount = 13
            invoke(activity, "openPdfThumbnails"); assertNull(request(activity))
            surface.previews.pageCount = 0
            invoke(activity, "openPdfThumbnails"); assertNull(request(activity))
            surface.previews.pageCount = 12
            invoke(activity, "openPdfThumbnails"); assertTrue(current(activity, request(activity)!!))
            assertTrue(storage.records.isEmpty()); assertTrue(surface.previews.calls.isEmpty())
        }
    }

    @Test fun `same book reopen has a fresh UUID and old or forged request cannot select dismiss or render it`() = runTest {
        withHost {
            invoke(activity, "openPdfThumbnails"); val old = request(activity)!!
            dismiss(activity, old)
            invoke(activity, "openPdfThumbnails"); val next = request(activity)!!
            assertNotSame(old, next); assertNotEquals(old.id, next.id)
            val forged = PdfThumbnailsRequest(next.book, next.surface, next.source, next.navigator,
                next.pageCount, next.currentPage, next.id)
            for (obsolete in listOf(old, forged)) {
                assertFalse(current(activity, obsolete)); assertFalse(select(activity, obsolete, 4))
                dismiss(activity, obsolete); assertSame(next, request(activity))
                expectCancelled { render(activity, obsolete, 4) }
            }
            assertTrue(current(activity, next)); assertTrue(surface.pages.calls.isEmpty())
            assertTrue(surface.previews.calls.isEmpty()); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `full Book hash exact surface source and navigator are rechecked by every request action`() = runTest {
        withHost {
            invoke(activity, "openPdfThumbnails"); val expected = request(activity)!!
            val book = session.book
            suspend fun reject() {
                assertFalse(current(activity, expected)); assertFalse(select(activity, expected, 3))
                dismiss(activity, expected); assertSame(expected, request(activity))
                expectCancelled { render(activity, expected, 3) }
            }
            for (replacement in listOf(book.copy(title = "Changed metadata"),
                book.copy(sha256 = "b".repeat(64)), book.copy(id = "other-book"))) {
                session.book = replacement; reject(); session.book = book
            }
            val originalSurface = surface
            setSurface(activity, Surface {})
            reject(); setSurface(activity, originalSurface)
            originalSurface.pdfThumbnails = Source()
            reject(); originalSurface.pdfThumbnails = originalSurface.previews
            originalSurface.pageNavigator = Navigator()
            reject(); originalSurface.pageNavigator = originalSurface.pages
            setNavigator(activity, Navigator())
            reject(); setNavigator(activity, originalSurface.pages)
            originalSurface.previews.pageCount = 11
            reject(); originalSurface.previews.pageCount = 12
            assertTrue(current(activity, expected))
            assertTrue(originalSurface.pages.calls.isEmpty()); assertTrue(originalSurface.previews.calls.isEmpty())
            assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `late thumbnail after dismissal and reopen cannot enter a newer request or recycle shared image`() = runTest {
        withHost {
            val delivered = CompletableDeferred<Bitmap>()
            surface.previews.renderPage = { delivered.await() }
            invoke(activity, "openPdfThumbnails"); val old = request(activity)!!
            val pending = async { render(activity, old, 5) }
            runCurrent(); assertEquals(listOf(5), surface.previews.calls)
            dismiss(activity, old)
            invoke(activity, "openPdfThumbnails"); val next = request(activity)!!
            delivered.complete(surface.previews.image); runCurrent()
            expectCancelled { pending.await() }
            assertSame(next, request(activity)); assertTrue(current(activity, next))
            assertFalse(surface.previews.image.isRecycled)
            assertTrue(surface.pages.calls.isEmpty()); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `backgrounding clears the request and rejects a suspended preview result without recycling it`() = runTest {
        withHost {
            val delivered = CompletableDeferred<Bitmap>()
            surface.previews.renderPage = { delivered.await() }
            invoke(activity, "openPdfThumbnails"); val old = request(activity)!!
            val pending = async { render(activity, old, 6) }
            runCurrent()
            host.pause(); runCurrent()
            assertNull(request(activity)); assertFalse(current(activity, old))
            assertFalse(select(activity, old, 6))
            delivered.complete(surface.previews.image); runCurrent()
            expectCancelled { pending.await() }
            assertFalse(surface.previews.image.isRecycled)
            host.resume(); runCurrent()
            assertNull(request(activity)); assertFalse(current(activity, old))
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }

    @Test fun `pause stop and recreation discard previews while new surface resumes actual latest recorded location`() = runTest {
        withHost {
            invoke(activity, "openPdfThumbnails"); val beforePause = request(activity)!!
            host.pause(); runCurrent()
            assertNull(request(activity)); assertFalse(current(activity, beforePause))
            host.resume(); runCurrent()
            invoke(activity, "openPdfThumbnails"); val beforeStop = request(activity)!!
            host.pause().stop(); runCurrent()
            assertNull(request(activity)); assertFalse(current(activity, beforeStop))
            host.restart().start().resume(); runCurrent()
            assertNull(request(activity)); assertTrue(storage.records.isEmpty())
            surface.reportActualPage(8); runCurrent()
            assertEquals(location(8), storage.records.single().location)
            invoke(activity, "openPdfThumbnails"); val beforeRecreate = request(activity)!!
            val oldActivity = activity
            host.recreate(); runCurrent()
            assertNotSame(oldActivity, activity)
            assertNull(request(activity)); assertFalse(current(activity, beforeRecreate))
            assertFalse(select(oldActivity, beforeRecreate, 7))
            assertEquals(location(8), session.initials.last()); assertEquals(2, session.initials.size)
            assertEquals(1, storage.records.size)
        }
    }

    @Test fun `bounds and rejected navigation preserve browser while accepted go closes and only actual snapshot records progress`() = runTest {
        withHost {
            surface.renderingFailed.value = true; runCurrent()
            invoke(activity, "openPdfThumbnails"); val expected = request(activity)!!
            // A recoverable current-page failure still allows choosing another PDF page.
            assertTrue(current(activity, expected))
            assertFalse(select(activity, expected, -1)); assertFalse(select(activity, expected, 12))
            assertTrue(surface.pages.calls.isEmpty()); assertSame(expected, request(activity))
            surface.pages.accepts = false
            assertFalse(select(activity, expected, 7)); runCurrent()
            assertSame(expected, request(activity)); assertTrue(current(activity, expected))
            assertEquals(listOf(7), surface.pages.calls); assertTrue(storage.records.isEmpty())
            surface.pages.accepts = true
            assertTrue(select(activity, expected, 7)); runCurrent()
            assertNull(request(activity)); assertEquals(listOf(7, 7), surface.pages.calls)
            assertTrue(storage.records.isEmpty()); assertEquals(session.resume, reader.latestLocation)
            surface.renderingFailed.value = false
            surface.reportActualPage(7); runCurrent()
            assertEquals(location(7), storage.records.single().location)
            assertEquals(location(7), reader.latestLocation)
            assertTrue(storage.records.single().spans.isEmpty())
        }
    }

    @Test fun `bookmark capture clearing or replacing request surface cannot execute old page navigation`() = runTest {
        withHost {
            invoke(activity, "openPdfThumbnails"); val cleared = request(activity)!!
            surface.onCapture = { dismiss(activity, cleared) }
            assertFalse(select(activity, cleared, 9)); runCurrent()
            assertNull(request(activity)); assertTrue(surface.pages.calls.isEmpty())
            surface.onCapture = {}
            invoke(activity, "openPdfThumbnails"); val replaced = request(activity)!!
            val originalSurface = surface
            originalSurface.onCapture = { setSurface(activity, Surface {}) }
            assertFalse(select(activity, replaced, 9)); runCurrent()
            assertTrue(originalSurface.pages.calls.isEmpty()); assertTrue(storage.records.isEmpty())
            setSurface(activity, originalSurface)
            assertTrue(current(activity, replaced))
            originalSurface.onCapture = {}
            dismiss(activity, replaced)
        }
    }

    @Test fun `replayable actual page flow is not resubscribed by browsing dismiss rejected or same page go`() = runTest {
        withHost {
            surface.reportActualPage(2); runCurrent()
            assertEquals(location(2), storage.records.single().location)
            val subscriptions = surface.observations
            invoke(activity, "openPdfThumbnails"); val first = request(activity)!!
            render(activity, first, 5)
            dismiss(activity, first); runCurrent()
            assertEquals(subscriptions, surface.observations); assertEquals(1, storage.records.size)
            invoke(activity, "openPdfThumbnails"); val second = request(activity)!!
            surface.pages.accepts = false
            assertFalse(select(activity, second, 7)); runCurrent()
            assertSame(second, request(activity))
            assertEquals(subscriptions, surface.observations); assertEquals(1, storage.records.size)
            surface.pages.accepts = true
            assertTrue(select(activity, second, 2)); runCurrent()
            assertNull(request(activity))
            assertEquals(subscriptions, surface.observations); assertEquals(1, storage.records.size)
            invoke(activity, "openPdfThumbnails"); val third = request(activity)!!
            assertTrue(select(activity, third, 7)); runCurrent()
            assertNull(request(activity)); assertEquals(subscriptions, surface.observations)
            assertEquals(1, storage.records.size); assertEquals(location(2), reader.latestLocation)
            surface.reportActualPage(7); runCurrent()
            assertEquals(listOf(location(2), location(7)), storage.records.map { it.location })
            assertEquals(location(7), reader.latestLocation)
            // A legitimate completed page must also be delivered while previews are open.
            assertTrue(surface.pages.goTo(8))
            invoke(activity, "openPdfThumbnails"); val pendingPage = request(activity)!!
            surface.reportActualPage(8); runCurrent()
            assertSame(pendingPage, request(activity)); assertTrue(current(activity, pendingPage))
            assertEquals(listOf(location(2), location(7), location(8)), storage.records.map { it.location })
            assertEquals(subscriptions, surface.observations)
        }
    }

    @Test fun pdfSearchPreviewBlocksToolsAndOnlyCurrentExactResultCanNavigate() = runTest {
        withHost {
            surface.tap(); runCurrent()
            invoke(activity, "openBookSearch"); runCurrent()
            invoke(activity, "changeBookSearchQuery", String::class.java, "alpha")
            assertTrue(surface.searchCalls.isEmpty()); assertTrue(surface.searchNavigation.isEmpty())
            assertFalse(surface.coverageAllowed!!.invoke()); assertFalse(surface.swipeAllowed!!.invoke())
            invoke(activity, "openOrientation"); assertFalse(orientation.state.value.open)
            invoke(activity, "runBookSearch"); runCurrent()
            val ready = (property(activity, "getBookSearchState") as BookSearchState).phase as BookSearchPhase.Ready
            val hit = ready.results.hits.single()
            invoke(activity, "selectBookSearchHit", BookSearchHit::class.java, hit.copy())
            assertTrue(surface.searchNavigation.isEmpty())
            assertTrue(storage.records.isEmpty()); assertEquals(listOf("alpha"), surface.searchCalls)
            invoke(activity, "selectBookSearchHit", BookSearchHit::class.java, hit); runCurrent()
            assertEquals(listOf(location(7)), surface.searchNavigation)
            assertFalse(property(activity, "getBookSearchOpen") as Boolean)
            assertTrue(storage.records.isEmpty())
            surface.reportActualPage(7); runCurrent(); assertEquals(listOf(location(7)), storage.records.map { it.location })
        }
    }
    @Test fun pausedPdfSearchDropsLateResultAndCannotNavigate() = runTest {
        withHost {
            val late = CompletableDeferred<BookSearchResults>()
            surface.searchResult = { withContext(NonCancellable) { late.await() } }
            invoke(activity, "openBookSearch")
            invoke(activity, "changeBookSearchQuery", String::class.java, "alpha")
            invoke(activity, "runBookSearch"); runCurrent()
            assertTrue((property(activity, "getBookSearchState") as BookSearchState).phase is BookSearchPhase.Searching)
            host.pause(); runCurrent()
            val hit = BookSearchHit("Sayfa 8", "alpha", location(7))
            late.complete(BookSearchResults(listOf(hit), false)); runCurrent()
            assertTrue((property(activity, "getBookSearchState") as BookSearchState).phase is BookSearchPhase.Idle)
            invoke(activity, "selectBookSearchHit", BookSearchHit::class.java, hit)
            assertTrue(surface.searchNavigation.isEmpty()); assertTrue(storage.records.isEmpty())
            host.resume(); runCurrent()
            assertTrue((property(activity, "getBookSearchState") as BookSearchState).phase is BookSearchPhase.Idle)
        }
    }
    @Test fun unsupportedPdfAndNativeLimitAreVisibleWithoutFakeEmptySuccess() = runTest {
        withHost {
            surface.searchSupported = false
            invoke(activity, "openBookSearch")
            invoke(activity, "changeBookSearchQuery", String::class.java, "alpha")
            invoke(activity, "runBookSearch"); runCurrent()
            assertTrue((property(activity, "getBookSearchState") as BookSearchState).phase is BookSearchPhase.Unsupported)
            assertTrue(surface.searchCalls.isEmpty())
            invoke(activity, "dismissBookSearch"); surface.searchSupported = true
            surface.searchResult = { throw BookSearchException(BookSearchFailure.LIMIT) }
            invoke(activity, "openBookSearch")
            invoke(activity, "changeBookSearchQuery", String::class.java, "alpha")
            invoke(activity, "runBookSearch"); runCurrent()
            assertTrue((property(activity, "getBookSearchState") as BookSearchState).phase is BookSearchPhase.Limit)
            assertEquals(listOf("alpha"), surface.searchCalls)
            assertTrue(surface.searchNavigation.isEmpty()); assertTrue(storage.records.isEmpty())
        }
    }
    @Test fun nativeSelectionNotificationOpensLoadingImmediatelyAndRightPageDoesNotChangeReadingAnchor() = runTest {
        withHost {
            surface.tap(); runCurrent()
            val late = CompletableDeferred<SelectedText?>()
            surface.selections.captureText = { late.await() }
            val sourceId = surface.selections.accept(); runCurrent()
            val loading = property(activity, "getSelectionPreview") as SelectedTextPreview.Loading
            assertEquals(1, surface.selections.captures)
            assertFalse(surface.coverageAllowed!!.invoke()); assertFalse(surface.swipeAllowed!!.invoke())
            invoke(activity, "openBookNotes"); invoke(activity, "openPdfThumbnails"); invoke(activity, "openOrientation")
            assertFalse(property(activity, "getBookNotesOpen") as Boolean); assertNull(request(activity))
            assertFalse(orientation.state.value.open); assertFalse(navigate(activity) { surface.forward() })
            invoke(activity, "captureSelectedText"); assertEquals(1, surface.selections.captures)
            late.complete(SelectedText("alpha 🌿", pdfPageIndex = 3, sourceSelectionId = sourceId)); runCurrent()
            val ready = property(activity, "getSelectionPreview") as SelectedTextPreview.Ready
            assertEquals(loading.sessionId, ready.sessionId); assertEquals(3, ready.pdfPageIndex)
            assertEquals(sourceId, ready.sourceSelectionId); assertNull(ready.sourceAnchor)
            assertEquals("alpha 🌿", ready.text); assertEquals(2, surface.pages.currentPage)
            assertTrue(surface.pages.calls.isEmpty()); assertTrue(storage.records.isEmpty())
            invoke(activity, "annotateSelectedText"); invoke(activity, "openSelectionAssistant"); runCurrent()
            assertSame(ready, property(activity, "getSelectionPreview"))
            assertNull(property(activity, "getAnnotationRequest")); assertNull(property(activity, "getSelectionAssistantState"))
        }
    }

    @Test fun closingPendingSelectionCancelsCaptureAndLateUninterruptibleResultCannotReplaceNewSelection() = runTest {
        withHost {
            surface.tap(); runCurrent()
            val late = CompletableDeferred<SelectedText?>()
            surface.selections.captureText = { withContext(NonCancellable) { late.await() } }
            val old = surface.selections.accept(); runCurrent()
            assertTrue(property(activity, "getSelectionPreview") is SelectedTextPreview.Loading)
            invoke(activity, "clearSelectionPreview"); invoke(activity, "observeSurface"); runCurrent()
            assertNull(property(activity, "getSelectionPreview")); assertNull(surface.selections.id)
            assertTrue(surface.coverageAllowed!!.invoke()); assertTrue(surface.swipeAllowed!!.invoke())
            surface.selections.captureText = { SelectedText("new", pdfPageIndex = 2, sourceSelectionId = surface.selections.id) }
            surface.selections.accept(); runCurrent()
            val current = property(activity, "getSelectionPreview") as SelectedTextPreview.Ready
            late.complete(SelectedText("old", pdfPageIndex = 3, sourceSelectionId = old)); runCurrent()
            assertSame(current, property(activity, "getSelectionPreview")); assertEquals("new", current.text)
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }

    @Test fun pauseClearsNativeLeaseAndNoNotificationOrLateCaptureCanReopenItUntilANewGesture() = runTest {
        withHost {
            val late = CompletableDeferred<SelectedText?>()
            surface.selections.captureText = { withContext(NonCancellable) { late.await() } }
            val old = surface.selections.accept(); runCurrent()
            host.pause(); runCurrent()
            assertNull(property(activity, "getSelectionPreview")); assertNull(surface.selections.id)
            surface.selections.requests.tryEmit(Unit); runCurrent(); assertEquals(1, surface.selections.captures)
            late.complete(SelectedText("old", pdfPageIndex = 3, sourceSelectionId = old)); runCurrent()
            host.resume(); runCurrent()
            assertNull(property(activity, "getSelectionPreview")); assertEquals(1, surface.selections.captures)
            surface.selections.captureText = { SelectedText("new", pdfPageIndex = 3, sourceSelectionId = surface.selections.id) }
            surface.selections.accept(); runCurrent()
            assertEquals("new", (property(activity, "getSelectionPreview") as SelectedTextPreview.Ready).text)
            assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun malformedPdfPageOrMissingRequestIdentityCannotBecomeAReadySelection() = runTest {
        withHost {
            for (invalid in listOf(
                SelectedText("alpha", pdfPageIndex = -1, sourceSelectionId = UUID.randomUUID().toString()),
                SelectedText("alpha", pdfPageIndex = 12, sourceSelectionId = UUID.randomUUID().toString()),
                SelectedText("alpha", pdfPageIndex = 3),
                SelectedText("alpha", pdfPageIndex = 3, sourceSelectionId = "invalid"),
            )) {
                surface.selections.captureText = { invalid }
                surface.selections.accept(); runCurrent()
                assertTrue(property(activity, "getSelectionPreview") is SelectedTextPreview.Error)
                assertNull(property(activity, "getSelectionTools"))
                invoke(activity, "clearSelectionPreview"); invoke(activity, "observeSurface"); runCurrent()
            }
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }

    @Test fun nativeNoTextLimitAndUnsupportedAreVisibleErrorsAndDoNotCreateNotesOrProgress() = runTest {
        withHost {
            val captures = listOf<suspend () -> SelectedText?>(
                { null }, { throw TextSelectionException(TextSelectionFailure.TOO_LONG) },
                { throw TextSelectionException(TextSelectionFailure.UNSUPPORTED) },
            )
            val phrases = listOf("OCR", "5000", "Android 15")
            captures.forEachIndexed { index, capture ->
                surface.selections.captureText = capture
                surface.selections.accept(); runCurrent()
                val error = property(activity, "getSelectionPreview") as SelectedTextPreview.Error
                assertTrue(error.message.contains(phrases[index]))
                invoke(activity, "annotateSelectedText"); assertNull(property(activity, "getAnnotationRequest"))
                invoke(activity, "clearSelectionPreview"); invoke(activity, "observeSurface"); runCurrent()
            }
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }

    private suspend fun TestScope.withHost(session: Session = Session(), translationService: TranslationService = TranslationService(),
        test: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val storage = Storage(); val orientationPreferences = Orientation(); val nightPreferences = Night()
            val reader = ReaderViewModel(session.book.id, ReaderLoader { session }, storage)
            val orientation = ReaderOrientationViewModel(orientationPreferences)
            val night = PdfNightModeViewModel(nightPreferences)
            val translation = com.yaprak.reader.feature.translation.TranslationViewModel(translationService)
            host.create()
            fun install(model: androidx.lifecycle.ViewModel) {
                host.get().viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${model.javaClass.canonicalName}", model)
            }
            install(reader); install(orientation); install(night); install(translation)
            install(PdfBookViewPreferencesViewModel(ReaderTestPdfViewPreferences()))
            host.start().resume(); runCurrent()
            assertEquals(session.resume, session.initials.single())
            Fixture(host, session, storage, reader, orientation, orientationPreferences, nightPreferences, translation).test()
        } finally {
            host.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    private fun invoke(activity: ReaderActivity, name: String) {
        activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }
    private fun invoke(activity: ReaderActivity, name: String, type: Class<*>, argument: Any) {
        activity.javaClass.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(activity, argument)
    }
    private fun property(activity: ReaderActivity, getter: String): Any? =
        activity.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(activity)
    private fun translationCurrent(activity: ReaderActivity, expected: com.yaprak.reader.feature.translation.TranslationState): Boolean =
        activity.javaClass.getDeclaredMethod("isTranslationCurrent", com.yaprak.reader.feature.translation.TranslationState::class.java)
            .apply { isAccessible = true }.invoke(activity, expected) as Boolean
    private fun request(activity: ReaderActivity) = property(activity, "getPdfThumbnailsRequest") as PdfThumbnailsRequest?
    private fun current(activity: ReaderActivity, expected: PdfThumbnailsRequest): Boolean =
        activity.javaClass.getDeclaredMethod("isPdfThumbnailsCurrent", PdfThumbnailsRequest::class.java)
            .apply { isAccessible = true }.invoke(activity, expected) as Boolean
    private fun select(activity: ReaderActivity, expected: PdfThumbnailsRequest, page: Int): Boolean =
        activity.javaClass.getDeclaredMethod("selectPdfThumbnail", PdfThumbnailsRequest::class.java,
            Int::class.javaPrimitiveType!!).apply { isAccessible = true }.invoke(activity, expected, page) as Boolean
    private fun dismiss(activity: ReaderActivity, expected: PdfThumbnailsRequest) {
        activity.javaClass.getDeclaredMethod("dismissPdfThumbnails", PdfThumbnailsRequest::class.java)
            .apply { isAccessible = true }.invoke(activity, expected)
    }
    private fun navigate(activity: ReaderActivity, action: () -> Boolean): Boolean =
        activity.javaClass.getDeclaredMethod("navigate", Function0::class.java)
            .apply { isAccessible = true }.invoke(activity, action) as Boolean
    private fun setSurface(activity: ReaderActivity, surface: ReaderSurface) {
        activity.javaClass.getDeclaredField("surface").apply { isAccessible = true }.set(activity, surface)
    }
    private fun setNavigator(activity: ReaderActivity, navigator: PageNavigator) {
        activity.javaClass.getDeclaredMethod("setPageNavigator", PageNavigator::class.java)
            .apply { isAccessible = true }.invoke(activity, navigator)
    }
    private suspend fun render(activity: ReaderActivity, expected: PdfThumbnailsRequest, page: Int): Bitmap =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                activity.javaClass.getDeclaredMethod("renderPdfThumbnail", PdfThumbnailsRequest::class.java,
                    Int::class.javaPrimitiveType!!, Continuation::class.java).apply { isAccessible = true }
                    .invoke(activity, expected, page, continuation)
            } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
        }
    private suspend fun expectCancelled(action: suspend () -> Any?) {
        try { action(); fail("An obsolete thumbnail request must be cancelled") }
        catch (_: CancellationException) { }
    }

    private companion object {
        fun location(page: Int) = ReadingLocation("{\"page\":$page}", "pdf")
    }
}
