package com.yaprak.reader.feature

import android.graphics.Bitmap
import android.os.Bundle
import androidx.fragment.app.Fragment
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.bookmarks.BookmarkTarget
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.domain.settings.PdfNightSettingsRepository
import com.yaprak.reader.domain.settings.BookPdfViewPreferences
import com.yaprak.reader.domain.settings.BookPdfViewPreferencesRepository
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.feature.reader.PdfBookViewPreferencesViewModel
import com.yaprak.reader.feature.reader.PdfBookViewPreferencesState
import com.yaprak.reader.feature.reader.PdfBookViewStatus
import com.yaprak.reader.domain.settings.ReaderOrientation
import com.yaprak.reader.domain.settings.ReaderOrientationSettingsRepository
import com.yaprak.reader.feature.reader.PdfCropChoice
import com.yaprak.reader.feature.reader.PdfCropRequest
import com.yaprak.reader.feature.reader.PdfCropSavedState
import com.yaprak.reader.feature.reader.PdfSpreadChoice
import com.yaprak.reader.feature.reader.PdfSpreadSavedState
import com.yaprak.reader.feature.reader.PdfNightModeViewModel
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.ReaderViewModel
import com.yaprak.reader.feature.settings.ReaderOrientationViewModel
import com.yaprak.reader.reader.BookmarkNavigator
import com.yaprak.reader.reader.PageNavigator
import com.yaprak.reader.reader.PdfCropController
import com.yaprak.reader.reader.PdfNightModeController
import com.yaprak.reader.reader.PdfPageCrop
import com.yaprak.reader.reader.PdfSwipeController
import com.yaprak.reader.reader.PdfThumbnailsSource
import com.yaprak.reader.reader.PdfViewController
import com.yaprak.reader.reader.PdfSpreadController
import com.yaprak.reader.reader.ReaderLoader
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import java.io.IOException
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

/** Actual Activity/lifecycle and replayable reader signals; not native PDF/crop pixel evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
class PdfCropActivityTest {
    private val edited = PdfPageCrop(7, 14)

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
        override suspend fun savePdfNightMode(enabled: Boolean) { writes += enabled; pdfNightMode.value = enabled }
    }
    private class PdfPreferences(initial: BookPdfViewPreferences = BookPdfViewPreferences()) : BookPdfViewPreferencesRepository {
        val value = MutableStateFlow(initial)
        val writes = mutableListOf<String>()
        var beforeSave: suspend () -> Unit = {}
        override fun observe(bookId: String, contentSha256: String) = value
        override suspend fun saveCrop(bookId: String, contentSha256: String, horizontalPercent: Int, verticalPercent: Int) {
            beforeSave(); writes += "crop"
            value.value = value.value.copy(horizontalPercent = horizontalPercent, verticalPercent = verticalPercent)
        }
        override suspend fun saveFit(bookId: String, contentSha256: String, fitMode: PdfFitPreference) {
            beforeSave(); writes += "fit"; value.value = value.value.copy(fitMode = fitMode)
        }
        override suspend fun saveSpread(bookId: String, contentSha256: String, doublePagePreferred: Boolean) {
            beforeSave(); writes += "spread"; value.value = value.value.copy(doublePagePreferred = doublePagePreferred)
        }
        override suspend fun saveNavigation(bookId: String, contentSha256: String, mode: com.yaprak.reader.domain.settings.PdfNavigationMode) {
            beforeSave(); writes += "navigation"; value.value = value.value.copy(navigationMode = mode)
        }
    }
    private class Source(override var pageCount: Int = 12) : PdfThumbnailsSource {
        val image = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        val calls = mutableListOf<Int>()
        var renderPage: suspend (Int) -> Bitmap = { image }
        override suspend fun render(pageIndex: Int): Bitmap { calls += pageIndex; return renderPage(pageIndex) }
    }
    private class Crop : PdfCropController {
        val calls = mutableListOf<PdfPageCrop>()
        var applied = PdfPageCrop()
        var failure: Exception? = null
        override fun apply(crop: PdfPageCrop) {
            calls += crop
            failure?.let { throw it }
            applied = crop
        }
    }
    private class Navigator(override var pageCount: Int = 12) : PageNavigator {
        override var currentPage: Int? = 2
        var stablePage: Int? = 2
        val calls = mutableListOf<Int>()
        override fun captureStablePage() = stablePage
        override fun goTo(pageIndex: Int): Boolean { calls += pageIndex; return true }
    }
    private class Surface(val tap: () -> Unit) : ReaderSurface {
        override val fragment = Fragment()
        override val renderingFailed = MutableStateFlow(false)
        val previews = Source()
        val pages = Navigator()
        val crop = Crop()
        override var pdfThumbnails: PdfThumbnailsSource? = previews
        override var pageNavigator: PageNavigator? = pages
        override var pdfCrop: PdfCropController? = crop
        private val actualRendered = MutableStateFlow<ReadingSnapshot?>(null)
        var observations = 0
        var swipeAllowed: (() -> Boolean)? = null
        var coverageAllowed: (() -> Boolean)? = null
        var fits = 0
        var forwards = 0
        val nightApplies = mutableListOf<Boolean>()
        val spreadApplies = mutableListOf<Boolean>()
        override val pdfSpread = PdfSpreadController { spreadApplies += it }
        override val pdfScroll = ReaderTestPdfScrollController()
        override val pdfSwipe = PdfSwipeController { swipeAllowed = it }
        override val pdfView = object : PdfViewController {
            override fun fitPage() { fits++ }
            override fun fitWidth() { fits++ }
        }
        override val pdfNightMode = PdfNightModeController { nightApplies += it }
        override val bookmarkNavigator = object : BookmarkNavigator {
            override fun capture() = pages.stablePage?.let { BookmarkTarget("Sayfa ${it + 1}", location(it)) }
            override fun goTo(location: ReadingLocation) = true
        }
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
            observations++; this.coverageAllowed = coverageAllowed
            // The real PdfReaderSession renderedPage StateFlow replays on every subscription.
            actualRendered.filterNotNull().collect { onSnapshot(it) }
        }
        fun reportActualPage(page: Int) {
            pages.stablePage = page; pages.currentPage = page
            actualRendered.value = ReadingSnapshot(location(page))
        }
        override fun forward(): Boolean { forwards++; return true }
        override fun backward() = true
    }
    private class Session(override var book: Book = defaultBook(),
        val prepareSurface: (Surface) -> Unit = {}) : ReaderSession {
        override val resume = location(2)
        val initials = mutableListOf<ReadingLocation?>()
        val surfaces = mutableListOf<Surface>()
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit,
            onError: () -> Unit): ReaderSurface {
            initials += initial
            return Surface(onTap).also { prepareSurface(it); surfaces += it }
        }
        override fun close() = Unit
    }
    private class Fixture(
        val host: ActivityController<ReaderActivity>, val session: Session, val storage: Storage,
        val reader: ReaderViewModel, val orientation: ReaderOrientationViewModel,
        val orientationPreferences: Orientation, val nightPreferences: Night,
        val pdfPreferences: PdfPreferences,
    ) {
        val activity: ReaderActivity get() = host.get()
        val surface: Surface get() = session.surfaces.last()
    }

    @Test fun `spread selection respects chrome lifecycle modal and owner gates without synthetic page records or new subscriptions`() = runTest {
        withHost {
            val book = session.book
            val subscriptions = surface.observations
            fun toggle(owner: ReaderSurface = surface, expected: Boolean = false) {
                val method = activity.javaClass.getDeclaredMethod("togglePdfSpread", Book::class.java,
                    ReaderSurface::class.java, Boolean::class.javaPrimitiveType!!).apply { isAccessible = true }
                method.invoke(activity, book, owner, expected)
            }
            assertEquals(listOf(false), surface.spreadApplies)
            toggle(); runCurrent()
            assertEquals(listOf(false, true), surface.spreadApplies)
            assertTrue(storage.records.isEmpty()); assertEquals(subscriptions, surface.observations)
            toggle(expected = false); assertEquals(2, surface.spreadApplies.size)
            surface.tap(); runCurrent() // Hidden controls cannot accept stale taps.
            toggle(expected = true); assertEquals(2, surface.spreadApplies.size)
            surface.tap(); runCurrent()
            invoke(activity, "openPdfCrop"); toggle(expected = true)
            assertEquals(2, surface.spreadApplies.size)
            dismiss(activity, request(activity)!!); runCurrent()
            toggle(Surface({}), true); assertEquals(2, surface.spreadApplies.size)
            host.pause(); runCurrent(); toggle(expected = true)
            assertEquals(2, surface.spreadApplies.size)
            host.resume(); host.visible(); runCurrent()
            val resumedSubscriptions = surface.observations
            toggle(expected = true); runCurrent()
            assertEquals(listOf(false, true, false), surface.spreadApplies)
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
            assertEquals(resumedSubscriptions, surface.observations)
            assertTrue(nightPreferences.writes.isEmpty()); assertTrue(orientationPreferences.writes.isEmpty())
        }
    }

    @Test fun `durable row wins over stale crop and spread Bundle and preferences are not written to instance state`() = runTest {
        val book = defaultBook()
        val savedChoice = PdfSpreadChoice(book.id, book.sha256, true)
        val saved = Bundle().apply {
            PdfSpreadSavedState.write(this, savedChoice)
            PdfCropSavedState.write(this, PdfCropChoice(book.id, book.sha256, edited))
        }
        val durable = BookPdfViewPreferences(2, 3, PdfFitPreference.WIDTH, false)
        withHost(saved = saved, pdfPreferences = PdfPreferences(durable)) {
            assertEquals(listOf(false), surface.spreadApplies)
            assertEquals(PdfPageCrop(2, 3), surface.crop.applied)
            val output = Bundle()
            activity.javaClass.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
                .apply { isAccessible = true }.invoke(activity, output)
            assertNull(PdfSpreadSavedState.read(output))
            assertNull(PdfCropSavedState.read(output))
            assertTrue(pdfPreferences.writes.isEmpty())
            assertTrue(storage.records.isEmpty())
        }
        withHost(session = Session(book.copy(sha256 = "b".repeat(64))), saved = saved) {
            assertEquals(listOf(false), surface.spreadApplies)
            val output = Bundle()
            activity.javaClass.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
                .apply { isAccessible = true }.invoke(activity, output)
            assertNull(PdfSpreadSavedState.read(output)); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `opening preview and cancelling draft change no crop page record or observation and modal blocks competing reader tools`() = runTest {
        withHost {
            surface.tap(); runCurrent()
            surface.reportActualPage(2); runCurrent()
            val subscriptions = surface.observations
            val calls = surface.crop.calls.toList(); val nightApplies = surface.nightApplies.toList()
            val fits = surface.fits
            assertTrue(surface.swipeAllowed!!.invoke()); assertTrue(surface.coverageAllowed!!.invoke())
            invoke(activity, "openPdfCrop"); val expected = request(activity)!!
            assertTrue(current(activity, expected)); assertEquals(PdfPageCrop(), expected.saved)
            assertEquals(2, expected.previewPage); assertEquals(12, expected.previewPageCount)
            assertSame(surface.previews.image, render(activity, expected))
            assertEquals(listOf(2), surface.previews.calls)
            assertEquals(expected.id, UUID.fromString(expected.id).toString())
            assertFalse(surface.swipeAllowed!!.invoke()); assertFalse(surface.coverageAllowed!!.invoke())
            invoke(activity, "fitPdf", Boolean::class.javaPrimitiveType!!, true)
            invoke(activity, "fitPdf", Boolean::class.javaPrimitiveType!!, false)
            assertFalse(navigate(activity) { surface.forward() })
            var toolbarCalls = 0
            activity.javaClass.getDeclaredMethod("readerAction", Function0::class.java)
                .apply { isAccessible = true }.invoke(activity, { toolbarCalls++ })
            invoke(activity, "openOrientation"); invoke(activity, "openBrightness")
            invoke(activity, "openBookNotes"); invoke(activity, "openPdfThumbnails")
            invoke(activity, "togglePdfNightMode"); invoke(activity, "retryPdfNightMode"); runCurrent()
            assertFalse(orientation.state.value.open)
            assertFalse(property(activity, "getBrightnessOpen") as Boolean)
            assertFalse(property(activity, "getBookNotesOpen") as Boolean)
            assertNull(property(activity, "getPdfThumbnailsRequest"))
            assertEquals(0, toolbarCalls); assertEquals(fits, surface.fits); assertEquals(0, surface.forwards)
            assertEquals(nightApplies, surface.nightApplies)
            assertTrue(nightPreferences.writes.isEmpty()); assertTrue(orientationPreferences.writes.isEmpty())
            dismiss(activity, expected); runCurrent()
            assertNull(request(activity)); assertEquals(calls, surface.crop.calls)
            assertEquals(PdfPageCrop(), surface.crop.applied)
            assertEquals(subscriptions, surface.observations); assertEquals(1, storage.records.size)
            assertTrue(surface.pages.calls.isEmpty()); assertFalse(surface.previews.image.isRecycled)
            assertTrue(surface.swipeAllowed!!.invoke()); assertTrue(surface.coverageAllowed!!.invoke())
            val saved = Bundle(); host.saveInstanceState(saved)
            assertNull(PdfCropSavedState.read(saved))
        }
    }

    @Test fun `successful apply and explicit original reset change only same book visual choice without page records or resubscription`() = runTest {
        withHost {
            surface.reportActualPage(2); runCurrent()
            val subscriptions = surface.observations
            invoke(activity, "openPdfCrop"); val first = request(activity)!!
            assertTrue(apply(activity, first, edited)); runCurrent()
            assertNull(request(activity)); assertFalse(failed(activity))
            assertEquals(edited, surface.crop.applied)
            assertEquals(PdfCropChoice(session.book.id, session.book.sha256, edited), choice(activity))
            invoke(activity, "openPdfCrop"); val reset = request(activity)!!
            assertEquals(edited, reset.saved)
            assertTrue(apply(activity, reset, PdfPageCrop())); runCurrent()
            assertNull(request(activity)); assertEquals(PdfPageCrop(), surface.crop.applied)
            assertEquals(PdfPageCrop(), choice(activity)?.crop ?: PdfPageCrop())
            assertEquals(subscriptions, surface.observations); assertEquals(1, storage.records.size)
            assertEquals(location(2), reader.latestLocation); assertTrue(surface.pages.calls.isEmpty())
        }
    }

    @Test fun `failed controller apply keeps old choice and editor open and successful retry preserves actual page subscription`() = runTest {
        withHost {
            invoke(activity, "openPdfCrop"); assertTrue(apply(activity, request(activity)!!, edited))
            val previous = choice(activity); val subscriptions = surface.observations
            invoke(activity, "openPdfCrop"); val expected = request(activity)!!
            val next = PdfPageCrop(15, 4)
            surface.crop.failure = IOException("Controlled crop failure")
            assertFalse(apply(activity, expected, next)); runCurrent()
            assertSame(expected, request(activity)); assertTrue(current(activity, expected))
            assertTrue(failed(activity))
            assertEquals(previous, choice(activity)); assertEquals(edited, surface.crop.applied)
            surface.crop.failure = null
            assertTrue(apply(activity, expected, next)); runCurrent()
            assertNull(request(activity)); assertFalse(failed(activity))
            assertEquals(next, surface.crop.applied); assertEquals(next, choice(activity)!!.crop)
            assertEquals(subscriptions, surface.observations)
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }

    @Test fun `reopened crop UUID and equal copied request cannot target newer editor`() = runTest {
        withHost {
            invoke(activity, "openPdfCrop"); val old = request(activity)!!
            dismiss(activity, old)
            invoke(activity, "openPdfCrop"); val next = request(activity)!!
            assertNotEquals(old.id, next.id)
            val controllerCalls = surface.crop.calls.toList()
            for (obsolete in listOf(old, next.copy())) {
                assertFalse(current(activity, obsolete)); assertFalse(apply(activity, obsolete, edited))
                dismiss(activity, obsolete); assertSame(next, request(activity))
                expectCancelled { render(activity, obsolete) }
            }
            assertTrue(current(activity, next)); assertEquals(controllerCalls, surface.crop.calls)
            assertTrue(surface.previews.calls.isEmpty()); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `full Book hash exact surface controller preview source navigator and count invalidate every captured callback`() = runTest {
        withHost {
            invoke(activity, "openPdfCrop"); val expected = request(activity)!!
            val originalBook = session.book; val originalSurface = surface
            val controllerCalls = originalSurface.crop.calls.toList()
            suspend fun reject() {
                assertFalse(current(activity, expected)); assertFalse(apply(activity, expected, edited))
                dismiss(activity, expected); assertSame(expected, request(activity))
                expectCancelled { render(activity, expected) }
            }
            for (changed in listOf(originalBook.copy(title = "New metadata"), originalBook.copy(id = "other-book"),
                originalBook.copy(sha256 = "b".repeat(64)))) {
                session.book = changed; reject(); session.book = originalBook
            }
            setSurface(activity, Surface {})
            reject(); setSurface(activity, originalSurface)
            originalSurface.pdfCrop = Crop()
            reject(); originalSurface.pdfCrop = originalSurface.crop
            originalSurface.pdfThumbnails = Source()
            reject(); originalSurface.pdfThumbnails = originalSurface.previews
            originalSurface.pageNavigator = Navigator()
            reject(); originalSurface.pageNavigator = originalSurface.pages
            setNavigator(activity, Navigator())
            reject(); setNavigator(activity, originalSurface.pages)
            originalSurface.previews.pageCount = 13
            reject(); originalSurface.previews.pageCount = 12
            originalSurface.pages.pageCount = 13
            reject(); originalSurface.pages.pageCount = 12
            assertTrue(current(activity, expected)); assertEquals(controllerCalls, originalSurface.crop.calls)
            assertTrue(originalSurface.previews.calls.isEmpty()); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `late preview after cancel and reopen is rejected and shared image is never recycled`() = runTest {
        withHost {
            val delivered = CompletableDeferred<Bitmap>()
            surface.previews.renderPage = { delivered.await() }
            invoke(activity, "openPdfCrop"); val old = request(activity)!!
            val pending = async { render(activity, old) }
            runCurrent(); assertEquals(listOf(2), surface.previews.calls)
            dismiss(activity, old)
            invoke(activity, "openPdfCrop"); val next = request(activity)!!
            delivered.complete(surface.previews.image); runCurrent()
            expectCancelled { pending.await() }
            assertSame(next, request(activity)); assertTrue(current(activity, next))
            assertFalse(surface.previews.image.isRecycled); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `pause clears draft and rejects suspended preview while applied crop remains selected on resume`() = runTest {
        withHost {
            invoke(activity, "openPdfCrop"); assertTrue(apply(activity, request(activity)!!, edited))
            val delivered = CompletableDeferred<Bitmap>()
            surface.previews.renderPage = { delivered.await() }
            invoke(activity, "openPdfCrop"); val old = request(activity)!!
            val pending = async { render(activity, old) }
            runCurrent(); host.pause(); runCurrent()
            assertNull(request(activity)); assertFalse(current(activity, old))
            assertFalse(apply(activity, old, PdfPageCrop()))
            delivered.complete(surface.previews.image); runCurrent()
            expectCancelled { pending.await() }
            assertFalse(surface.previews.image.isRecycled)
            host.resume(); runCurrent()
            assertNull(request(activity)); assertEquals(edited, choice(activity)!!.crop)
            invoke(activity, "openPdfCrop"); assertEquals(edited, request(activity)!!.saved)
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }

    @Test fun `stop and recreation discard editor but restore applied same book crop and latest actual reading location`() = runTest {
        withHost {
            invoke(activity, "openPdfCrop"); assertTrue(apply(activity, request(activity)!!, edited))
            invoke(activity, "openPdfCrop"); val stopped = request(activity)!!
            host.pause().stop(); runCurrent()
            assertNull(request(activity)); assertFalse(current(activity, stopped))
            host.restart().start().resume(); runCurrent()
            assertNull(request(activity)); assertEquals(edited, choice(activity)!!.crop)
            surface.reportActualPage(8); runCurrent()
            assertEquals(location(8), storage.records.single().location)
            invoke(activity, "openPdfCrop"); val beforeRecreate = request(activity)!!
            val oldActivity = activity
            host.recreate(); runCurrent()
            assertNotSame(oldActivity, activity)
            assertNull(request(activity)); assertFalse(current(activity, beforeRecreate))
            assertFalse(apply(oldActivity, beforeRecreate, PdfPageCrop()))
            assertEquals(edited, surface.crop.applied)
            assertEquals(location(8), session.initials.last()); assertEquals(2, session.initials.size)
            assertEquals(1, storage.records.size)
            invoke(activity, "openPdfCrop"); assertEquals(edited, request(activity)!!.saved)
        }
    }

    @Test fun `saved applied crop is ignored for another book or changed content hash`() = runTest {
        val saved = Bundle()
        withHost {
            invoke(activity, "openPdfCrop"); assertTrue(apply(activity, request(activity)!!, edited))
            host.saveInstanceState(saved)
            assertNull(PdfCropSavedState.read(saved))
        }
        val original = defaultBook()
        for (other in listOf(original.copy(id = "other-book"), original.copy(sha256 = "b".repeat(64)))) {
            withHost(Session(other), saved) {
                assertEquals(PdfPageCrop(), surface.crop.applied)
                assertTrue(surface.crop.calls.all { it.isOriginal })
                invoke(activity, "openPdfCrop"); assertEquals(PdfPageCrop(), request(activity)!!.saved)
                assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
            }
        }
    }

    @Test fun `optional absent preview leaves crop editor usable while EPUB or absent crop controller cannot open it`() = runTest {
        withHost {
            val original = session.book
            session.book = original.copy(format = BookFormat.EPUB, pageCount = null)
            invoke(activity, "openPdfCrop"); assertNull(request(activity))
            session.book = original
            surface.pdfCrop = null
            invoke(activity, "openPdfCrop"); assertNull(request(activity))
            surface.pdfCrop = surface.crop
            surface.pdfThumbnails = null; surface.pageNavigator = null
            setNavigator(activity, null)
            invoke(activity, "openPdfCrop"); val expected = request(activity)!!
            assertTrue(current(activity, expected)); assertNull(expected.previewPage)
            assertNull(expected.previewSource); assertNull(expected.previewNavigator)
            assertEquals(0, expected.previewPageCount)
            assertTrue(apply(activity, expected, edited)); assertEquals(edited, surface.crop.applied)
            assertTrue(storage.records.isEmpty()); assertTrue(surface.previews.calls.isEmpty())
        }
    }

    @Test fun `crop actions preserve replayable page subscription and opening preview stays fixed while legitimate completed page is observed`() = runTest {
        withHost {
            surface.reportActualPage(2); runCurrent()
            val subscriptions = surface.observations
            invoke(activity, "openPdfCrop"); val first = request(activity)!!
            render(activity, first); dismiss(activity, first); runCurrent()
            assertEquals(subscriptions, surface.observations); assertEquals(1, storage.records.size)
            invoke(activity, "openPdfCrop"); val applyRequest = request(activity)!!
            assertTrue(apply(activity, applyRequest, edited)); runCurrent()
            assertEquals(subscriptions, surface.observations); assertEquals(1, storage.records.size)
            invoke(activity, "openPdfCrop"); val pendingPage = request(activity)!!
            surface.reportActualPage(8); runCurrent()
            assertSame(pendingPage, request(activity)); assertTrue(current(activity, pendingPage))
            assertEquals(2, pendingPage.previewPage)
            assertSame(surface.previews.image, render(activity, pendingPage))
            assertEquals(listOf(2, 2), surface.previews.calls)
            assertEquals(listOf(location(2), location(8)), storage.records.map { it.location })
            assertEquals(location(8), reader.latestLocation)
            assertTrue(apply(activity, pendingPage, PdfPageCrop())); runCurrent()
            assertEquals(subscriptions, surface.observations); assertEquals(2, storage.records.size)
            assertTrue(surface.pages.calls.isEmpty()); assertFalse(surface.previews.image.isRecycled)
        }
    }

    @Test fun `crop controller cancellation remains cancellation without claiming applied choice or closing editor`() = runTest {
        withHost {
            invoke(activity, "openPdfCrop"); val expected = request(activity)!!
            val previous = choice(activity)
            surface.crop.failure = CancellationException("Controlled cancellation")
            expectCancelled { apply(activity, expected, edited) }
            assertSame(expected, request(activity)); assertEquals(previous, choice(activity))
            assertEquals(PdfPageCrop(), surface.crop.applied); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `failed durable crop application is visible and explicit retry applies stored row without rewriting it`() = runTest {
        val restored = PdfCropChoice(defaultBook().id, defaultBook().sha256, edited)
        val saved = Bundle().apply { PdfCropSavedState.write(this, restored) }
        val session = Session(prepareSurface = { it.crop.failure = IOException("Controlled restore failure") })
        withHost(session, saved, PdfPreferences(BookPdfViewPreferences(edited.horizontalPercent, edited.verticalPercent))) {
            assertTrue(failed(activity)); assertNull(choice(activity))
            assertEquals(PdfPageCrop(), surface.crop.applied)
            assertEquals(listOf(edited), surface.crop.calls)
            invoke(activity, "openPdfCrop"); assertNull(request(activity))
            surface.crop.failure = null
            activity.javaClass.getDeclaredMethod("retryPdfBookView", PdfBookViewPreferencesState::class.java)
                .apply { isAccessible = true }.invoke(activity, preferences(activity).state.value)
            runCurrent()
            assertFalse(failed(activity)); assertNull(request(activity))
            assertEquals(edited, surface.crop.applied); assertEquals(restored, choice(activity))
            assertTrue(pdfPreferences.writes.isEmpty())
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }

    @Test fun `suspended crop save leaves native view unchanged and pause cancels old editor without optimistic success`() = runTest {
        withHost {
            val release = CompletableDeferred<Unit>()
            pdfPreferences.beforeSave = { release.await() }
            invoke(activity, "openPdfCrop"); val expected = request(activity)!!
            val calls = surface.crop.calls.toList()
            val pending = async { apply(activity, expected, edited) }; runCurrent()
            assertSame(expected, request(activity)); assertEquals(calls, surface.crop.calls)
            assertEquals(PdfBookViewStatus.SAVING, preferences(activity).state.value.status)
            assertEquals(PdfPageCrop(), surface.crop.applied); assertTrue(pdfPreferences.writes.isEmpty())
            host.pause(); runCurrent()
            assertTrue(pending.isCancelled); assertNull(request(activity))
            release.complete(Unit); runCurrent(); host.resume(); runCurrent()
            assertNull(request(activity)); assertEquals(PdfPageCrop(), surface.crop.applied)
            assertTrue(pdfPreferences.writes.isEmpty()); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `crop save failure retains draft lease and explicit retry persists before native application`() = runTest {
        withHost {
            invoke(activity, "openPdfCrop"); val expected = request(activity)!!
            pdfPreferences.beforeSave = { throw IOException("storage") }
            assertFalse(apply(activity, expected, edited)); runCurrent()
            assertSame(expected, request(activity)); assertTrue(current(activity, expected))
            assertEquals(PdfBookViewStatus.SAVE_FAILED, preferences(activity).state.value.status)
            assertEquals(PdfPageCrop(), surface.crop.applied); assertTrue(pdfPreferences.writes.isEmpty())
            pdfPreferences.beforeSave = {}
            assertTrue(apply(activity, expected, edited)); runCurrent()
            assertNull(request(activity)); assertEquals(edited, surface.crop.applied)
            assertEquals(listOf("crop"), pdfPreferences.writes); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `observed preference revision closes old crop editor and rejects its stale apply and preview callbacks`() = runTest {
        withHost {
            invoke(activity, "openPdfCrop"); val expected = request(activity)!!
            val subscriptions = surface.observations
            pdfPreferences.value.value = BookPdfViewPreferences(3, 4, PdfFitPreference.WIDTH, true)
            runCurrent()
            assertNull(request(activity)); assertFalse(current(activity, expected))
            assertFalse(apply(activity, expected, edited)); expectCancelled { render(activity, expected) }
            assertEquals(PdfPageCrop(3, 4), surface.crop.applied)
            assertEquals(subscriptions, surface.observations)
            assertTrue(pdfPreferences.writes.isEmpty()); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun `a fresh reader session restores durable crop fit and spread without using an instance Bundle`() = runTest {
        val repository = PdfPreferences()
        withHost(pdfPreferences = repository) {
            invoke(activity, "openPdfCrop"); assertTrue(apply(activity, request(activity)!!, edited)); runCurrent()
            invoke(activity, "fitPdf", Boolean::class.javaPrimitiveType!!, true); runCurrent()
            activity.javaClass.getDeclaredMethod("togglePdfSpread", Book::class.java, ReaderSurface::class.java,
                Boolean::class.javaPrimitiveType!!).apply { isAccessible = true }.invoke(activity, session.book, surface, false)
            runCurrent()
            assertEquals(BookPdfViewPreferences(7, 14, PdfFitPreference.WIDTH, true), repository.value.value)
        }
        withHost(pdfPreferences = repository) {
            assertEquals(edited, surface.crop.applied)
            assertEquals(listOf(true), surface.spreadApplies)
            assertEquals(PdfFitPreference.WIDTH, preferences(activity).state.value.applied!!.fitMode)
            assertEquals(listOf("crop", "fit", "spread"), repository.writes)
            assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun navigationWaitsForCommitKeepsPageObservationAndRestoresInAFreshReader() = runTest {
        val repository = PdfPreferences(BookPdfViewPreferences(7, 14, PdfFitPreference.WIDTH, true))
        withHost(pdfPreferences = repository) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            repository.beforeSave = { entered.complete(Unit); release.await() }
            val subscriptions = surface.observations
            val fits = surface.fits
            val crops = surface.crop.calls.size
            val spreads = surface.spreadApplies.size
            toggleNavigation(activity, surface, false, preferences(activity).state.value)
            runCurrent(); entered.await()
            assertFalse(surface.pdfScroll.enabled.value)
            assertEquals(PdfNavigationMode.PAGED, repository.value.value.navigationMode)
            assertTrue(repository.writes.isEmpty()); assertTrue(storage.records.isEmpty())
            release.complete(Unit); runCurrent()
            assertTrue(surface.pdfScroll.enabled.value)
            assertEquals(PdfBookViewStatus.READY, preferences(activity).state.value.status)
            assertEquals(BookPdfViewPreferences(7, 14, PdfFitPreference.WIDTH, true, PdfNavigationMode.VERTICAL_SCROLL), repository.value.value)
            assertEquals(fits, surface.fits); assertEquals(crops, surface.crop.calls.size)
            assertEquals(spreads, surface.spreadApplies.size)
            assertEquals(subscriptions, surface.observations); assertTrue(storage.records.isEmpty())
            surface.tap(); runCurrent()
            val readingSubscriptions = surface.observations
            surface.reportActualPage(5); runCurrent()
            assertEquals(location(5), storage.records.last().location)
            assertEquals(readingSubscriptions, surface.observations)
        }
        withHost(pdfPreferences = repository) {
            assertTrue(surface.pdfScroll.enabled.value)
            assertEquals(PdfBookViewStatus.READY, preferences(activity).state.value.status)
            assertEquals(PdfPageCrop(7, 14), surface.crop.applied)
            assertEquals(listOf(true), surface.spreadApplies)
            assertEquals(listOf("navigation"), repository.writes)
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }

    @Test fun navigationSaveAndNativeFailureHaveDistinctRetriesWithoutOptimisticSuccess() = runTest {
        withHost {
            pdfPreferences.beforeSave = { throw IOException("controlled navigation write") }
            toggleNavigation(activity, surface, false, preferences(activity).state.value); runCurrent()
            assertEquals(PdfBookViewStatus.SAVE_FAILED, preferences(activity).state.value.status)
            assertFalse(surface.pdfScroll.enabled.value); assertTrue(pdfPreferences.writes.isEmpty())
            pdfPreferences.beforeSave = {}
            surface.pdfScroll.accepts = false
            retryPreferences(activity); runCurrent()
            assertEquals(PdfNavigationMode.VERTICAL_SCROLL, pdfPreferences.value.value.navigationMode)
            assertEquals(PdfBookViewStatus.APPLY_FAILED, preferences(activity).state.value.status)
            assertFalse(surface.pdfScroll.enabled.value); assertEquals(listOf("navigation"), pdfPreferences.writes)
            surface.pdfScroll.accepts = true
            retryPreferences(activity); runCurrent()
            assertEquals(PdfBookViewStatus.READY, preferences(activity).state.value.status)
            assertTrue(surface.pdfScroll.enabled.value)
            assertEquals(listOf("navigation"), pdfPreferences.writes)
            assertTrue(storage.records.isEmpty()); assertTrue(surface.pages.calls.isEmpty())
        }
    }

    @Test fun navigationRejectsOldRevisionHiddenControlsModalAndPausedCallbacks() = runTest {
        withHost {
            val oldLease = preferences(activity).state.value
            pdfPreferences.value.value = BookPdfViewPreferences(3, 4)
            runCurrent()
            toggleNavigation(activity, surface, false, oldLease); runCurrent()
            assertTrue(pdfPreferences.writes.isEmpty())
            val fresh = preferences(activity).state.value
            toggleNavigation(activity, Surface({}), false, fresh)
            surface.tap(); runCurrent()
            toggleNavigation(activity, surface, false, fresh)
            surface.tap(); runCurrent()
            invoke(activity, "openPdfCrop")
            toggleNavigation(activity, surface, false, preferences(activity).state.value)
            dismiss(activity, request(activity)!!); runCurrent()
            val beforePause = preferences(activity).state.value
            host.pause(); runCurrent()
            toggleNavigation(activity, surface, false, beforePause); runCurrent()
            assertTrue(pdfPreferences.writes.isEmpty()); assertFalse(surface.pdfScroll.enabled.value)
            host.resume().visible(); runCurrent()
            toggleNavigation(activity, surface, false, beforePause); runCurrent()
            assertTrue(pdfPreferences.writes.isEmpty())
            toggleNavigation(activity, surface, false, preferences(activity).state.value); runCurrent()
            assertEquals(listOf("navigation"), pdfPreferences.writes)
            assertTrue(surface.pdfScroll.enabled.value); assertTrue(storage.records.isEmpty())
        }
    }

    @Test fun pausingWhileNavigationIsQueuedCancelsTheWriteAndFreshResumeUsesTheCommittedChoice() = runTest {
        withHost {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            pdfPreferences.beforeSave = { entered.complete(Unit); release.await() }
            toggleNavigation(activity, surface, false, preferences(activity).state.value)
            runCurrent(); entered.await()
            host.pause(); runCurrent(); release.complete(Unit); runCurrent()
            assertTrue(pdfPreferences.writes.isEmpty())
            assertFalse(surface.pdfScroll.enabled.value)
            assertEquals(PdfNavigationMode.PAGED, pdfPreferences.value.value.navigationMode)
            pdfPreferences.beforeSave = {}
            host.resume().visible(); runCurrent()
            assertEquals(PdfBookViewStatus.READY, preferences(activity).state.value.status)
            assertFalse(surface.pdfScroll.enabled.value); assertTrue(storage.records.isEmpty())
        }
    }

    private fun toggleNavigation(activity: ReaderActivity, owner: Surface, expected: Boolean,
        lease: PdfBookViewPreferencesState) {
        activity.javaClass.getDeclaredMethod("togglePdfScroll", ReaderSurface::class.java,
            com.yaprak.reader.reader.PdfScrollController::class.java, Boolean::class.javaPrimitiveType!!,
            PdfBookViewPreferencesState::class.java).apply { isAccessible = true }
            .invoke(activity, owner, owner.pdfScroll, expected, lease)
    }
    private fun retryPreferences(activity: ReaderActivity) {
        activity.javaClass.getDeclaredMethod("retryPdfBookView", PdfBookViewPreferencesState::class.java)
            .apply { isAccessible = true }.invoke(activity, preferences(activity).state.value)
    }

    private suspend fun TestScope.withHost(session: Session = Session(), saved: Bundle? = null,
        pdfPreferences: PdfPreferences = PdfPreferences(),
        test: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val storage = Storage(); val orientationPreferences = Orientation(); val nightPreferences = Night()
            val reader = ReaderViewModel(session.book.id, ReaderLoader { session }, storage)
            val orientation = ReaderOrientationViewModel(orientationPreferences)
            val night = PdfNightModeViewModel(nightPreferences)
            val pdfView = PdfBookViewPreferencesViewModel(pdfPreferences)
            if (saved == null) host.create() else host.create(Bundle(saved))
            fun install(model: androidx.lifecycle.ViewModel) {
                host.get().viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${model.javaClass.canonicalName}", model)
            }
            install(reader); install(orientation); install(night); install(pdfView)
            host.start().resume(); runCurrent()
            assertEquals(session.resume, session.initials.single())
            Fixture(host, session, storage, reader, orientation, orientationPreferences, nightPreferences, pdfPreferences).test()
        } finally { host.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    private fun invoke(activity: ReaderActivity, name: String) {
        activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }
    private fun invoke(activity: ReaderActivity, name: String, type: Class<*>, argument: Any) {
        activity.javaClass.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(activity, argument)
    }
    private fun property(activity: ReaderActivity, getter: String): Any? =
        activity.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(activity)
    private fun request(activity: ReaderActivity) = property(activity, "getPdfCropRequest") as PdfCropRequest?
    private fun preferences(activity: ReaderActivity) = property(activity, "getPdfBookView") as PdfBookViewPreferencesViewModel
    private fun choice(activity: ReaderActivity): PdfCropChoice? {
        val state = preferences(activity).state.value
        val owner = state.owner ?: return null
        val applied = state.applied ?: return null
        return PdfCropChoice(owner.bookId, owner.contentSha256, PdfPageCrop(applied.horizontalPercent, applied.verticalPercent))
    }
    private fun failed(activity: ReaderActivity) = property(activity, "getPdfCropApplyFailed") as Boolean
    private fun current(activity: ReaderActivity, expected: PdfCropRequest): Boolean =
        activity.javaClass.getDeclaredMethod("isPdfCropCurrent", PdfCropRequest::class.java)
            .apply { isAccessible = true }.invoke(activity, expected) as Boolean
    private suspend fun apply(activity: ReaderActivity, expected: PdfCropRequest, crop: PdfPageCrop): Boolean =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                activity.javaClass.getDeclaredMethod("applyPdfCrop", PdfCropRequest::class.java, PdfPageCrop::class.java,
                    Continuation::class.java).apply { isAccessible = true }.invoke(activity, expected, crop, continuation)
            } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
        }
    private fun dismiss(activity: ReaderActivity, expected: PdfCropRequest) {
        activity.javaClass.getDeclaredMethod("dismissPdfCrop", PdfCropRequest::class.java)
            .apply { isAccessible = true }.invoke(activity, expected)
    }
    private fun navigate(activity: ReaderActivity, action: () -> Boolean): Boolean =
        activity.javaClass.getDeclaredMethod("navigate", Function0::class.java)
            .apply { isAccessible = true }.invoke(activity, action) as Boolean
    private fun setSurface(activity: ReaderActivity, surface: ReaderSurface) {
        activity.javaClass.getDeclaredField("surface").apply { isAccessible = true }.set(activity, surface)
    }
    private fun setNavigator(activity: ReaderActivity, navigator: PageNavigator?) {
        activity.javaClass.getDeclaredMethod("setPageNavigator", PageNavigator::class.java)
            .apply { isAccessible = true }.invoke(activity, navigator)
    }
    private suspend fun render(activity: ReaderActivity, expected: PdfCropRequest): Bitmap =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                activity.javaClass.getDeclaredMethod("renderPdfCropPreview", PdfCropRequest::class.java,
                    Continuation::class.java).apply { isAccessible = true }.invoke(activity, expected, continuation)
            } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
        }
    private suspend fun expectCancelled(action: suspend () -> Any?) {
        try { action(); fail("An obsolete or cancelled crop request must be cancelled") }
        catch (_: CancellationException) { }
    }
    private companion object {
        fun location(page: Int) = ReadingLocation("{\"page\":$page}", "pdf")
        fun defaultBook() = Book("crop-book", "PDF crop integration", "İstanbul", BookFormat.PDF,
            "crop.pdf", "crop.pdf", 1, "a".repeat(64), 1, pageCount = 12)
    }
}
