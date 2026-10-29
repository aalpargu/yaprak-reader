package com.yaprak.reader.formats

import android.app.Application
import android.graphics.*
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import com.yaprak.reader.data.persistence.BookEntity
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomBookPdfViewPreferencesRepository
import com.yaprak.reader.domain.settings.PdfNavigationMode
import com.yaprak.reader.domain.settings.PdfFitPreference
import com.yaprak.reader.feature.reader.PdfBookViewOwner
import com.yaprak.reader.feature.reader.PdfBookViewPreferencesState
import com.yaprak.reader.feature.reader.PdfBookViewStatus
import com.yaprak.reader.feature.reader.ReaderPdfBookViewPreferencesBinding
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*

/** Actual Fragment/session/Compose/native host draw and pointer dispatch. The native backend is controlled. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PdfScrollUiTest {
    @After fun resetFontScale() { RuntimeEnvironment.setFontScale(1f) }
    // Keep real native IO on workers, but enqueue composition continuations on the controlled UI clock.
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    @get:Rule val temporary = org.junit.rules.TemporaryFolder()
    private fun waitFor(condition: () -> Boolean) { compose.waitForIdle(); compose.waitUntil(10_000, condition); compose.waitForIdle() }
    private fun status(f: Fixture) = PdfReaderFragment::class.java.getDeclaredField("status")
        .apply { isAccessible = true }.get(f.surface.fragment) as PdfRenderStatus
    @Suppress("UNCHECKED_CAST")
    private fun <T> fragmentPreference(f: Fixture, name: String): T =
        (PdfReaderFragment::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(f.surface.fragment) as kotlinx.coroutines.flow.StateFlow<T>).value
    private fun hide(f: Fixture) = compose.runOnUiThread {
        f.surface.fragment.requireView().apply { alpha = 0f; viewTreeObserver.dispatchOnPreDraw() }
    }
    private fun draw(f: Fixture, name: String? = null, sampleX: Int? = null): Int {
        var sample = Color.TRANSPARENT
        compose.waitForIdle()
        compose.runOnUiThread {
        f.surface.fragment.requireView().alpha = 1f
        val view = f.host.get().window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            sample = image.getPixel(sampleX ?: image.width / 2, image.height / 2)
            if (name != null) {
                val directory = if (name.startsWith("zoom-resume-")) "pdf-zoom-resume-0.154.0" else "pdf-scroll-0.138.0"
                val file = File(System.getProperty("yaprak.screenshotDir"), "$directory/$name.png")
                file.parentFile!!.mkdirs(); file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { image.recycle() }
        }
        return sample
    }
    private suspend fun hidden(f: Fixture) {
        assertFalse(f.surface.readingVisible.first()); assertNull(f.surface.pageNavigator!!.captureStablePage())
        assertNull(f.surface.pageLabel.first()); assertNull(f.surface.bookmarkNavigator!!.capture())
    }
    private suspend fun shown(f: Fixture, page: Int) {
        assertTrue(f.surface.readingVisible.first()); assertEquals(page, f.surface.pageNavigator!!.captureStablePage())
        assertEquals("${page + 1} / 100", f.surface.pageLabel.first())
        assertEquals(page + 1, f.surface.bookPositionPercent.first())
        val bookmark = requireNotNull(f.surface.bookmarkNavigator!!.capture())
        val point = status(f).drawnPosition.value ?: PdfResumePosition(page)
        assertEquals(page, point.pageIndex)
        assertEquals(PdfPosition.encode(point), bookmark.location)
        assertEquals(if (point.anchorY != null) "Sayfa ${page + 1} · okuma noktası" else "Sayfa ${page + 1}", bookmark.label)
    }
    private fun go(f: Fixture, page: Int) {
        hide(f); compose.runOnUiThread { assertTrue(f.surface.pageNavigator!!.goTo(page)) }
        waitFor { status(f).requestedPage.value == page && status(f).renderedPage.value == page && status(f).scrollNavigationSettled }
    }
    private fun open(native: ScrollNative = ScrollNative(), scrolling: Boolean = true,
        initial: ReadingLocation = PdfPosition.encode(0), applyPreferences: Boolean = true,
        viewportWidth: Int? = null): Fixture {
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        val document = PdfDocument(native)
        val session = PdfReaderSession(Book("scroll-pdf", "Scroll", null, BookFormat.PDF, "scroll.pdf", "scroll.pdf",
            1, "a".repeat(64), 1, 100), document, null)
        val f = Fixture(host, session, document, native)
        compose.runOnUiThread {
            host.get().setContentView(FrameLayout(host.get()).apply { id = 1002 }, android.view.ViewGroup.LayoutParams(
                viewportWidth ?: android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
            f.surface = session.createSurface(initial, false, { f.fallbackTaps++ }, {})
            host.get().supportFragmentManager.beginTransaction().replace(1002, f.surface.fragment).commitNow()
            f.surface.fragment.requireView().alpha = 0f
            f.surface.pdfSwipe!!.allowWhile { f.allowed }
        }
        val initialPage = PdfPosition.decode(initial, 100)
        waitFor { status(f).renderedPage.value == initialPage }
        if (applyPreferences) {
            compose.runOnUiThread { assertTrue(f.surface.pdfScroll!!.apply(scrolling)) }
            waitFor { status(f).scrolling == scrolling && status(f).renderedPage.value == initialPage && status(f).scrollNavigationSettled }
        }
        f.scope.launch { f.surface.observe({ true }) { f.snapshots += it } }
        return f
    }
    private inner class Fixture(val host: ActivityController<FragmentActivity>, val session: PdfReaderSession,
        val document: PdfDocument, val native: ScrollNative) {
        lateinit var surface: ReaderSurface
        var allowed = true // Deliberately not Compose state: current owner gates must work without recomposition.
        var fallbackTaps = 0
        val snapshots = CopyOnWriteArrayList<ReadingSnapshot>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        suspend fun close() {
            compose.waitForIdle()
            compose.runOnUiThread { scope.cancel(); host.pause().stop().destroy(); session.close() }
            document.closeAndAwait()
            native.bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        }
    }
    @Test fun durableRoomModeDrivesActualNativeSurfaceAndRestoresAfterDatabaseAndSessionReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // Native SQLite WAL sidecars on Windows also need a bounded test path.
        val name = File(temporary.root, "pn-ui.db").absolutePath
        val parent = requireNotNull(context.getDatabasePath(name).parentFile)
        check(parent.isDirectory || parent.mkdirs())
        var db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        val hash = "a".repeat(64)
        try {
            db.books().insert(BookEntity("scroll-pdf", "Scroll", null, "PDF", "scroll.pdf", "scroll.pdf", 1, hash, 1, 100))
            val repository = RoomBookPdfViewPreferencesRepository(db)
            repository.saveCrop("scroll-pdf", hash, 10, 0)
            repository.saveFit("scroll-pdf", hash, PdfFitPreference.WIDTH)
            repository.saveSpread("scroll-pdf", hash, true)
            repository.saveNavigation("scroll-pdf", hash, PdfNavigationMode.VERTICAL_SCROLL)
            val initial = repository.observe("scroll-pdf", hash).first()
            suspend fun verifyFreshSurface(preferences: com.yaprak.reader.domain.settings.BookPdfViewPreferences) {
                val f = open(scrolling = false)
                try {
                    hidden(f); assertTrue(f.snapshots.isEmpty())
                    assertFalse(f.surface.pdfScroll!!.enabled.value)
                    val state = PdfBookViewPreferencesState(PdfBookViewOwner("scroll-pdf", hash, 1),
                        preferences, status = PdfBookViewStatus.APPLYING)
                    val binding = ReaderPdfBookViewPreferencesBinding()
                    compose.runOnUiThread { assertTrue(binding.apply(f.surface, f.session.book, state) { true }) }
                    waitFor { status(f).scrolling && status(f).renderedPage.value == 0 }
                    assertTrue(f.surface.pdfScroll!!.enabled.value)
                    assertEquals(PdfPageCrop(10, 0), fragmentPreference<PdfCropRequest>(f, "cropRequest").crop)
                    assertTrue(fragmentPreference(f, "doublePagePreferred"))
                    assertFalse(status(f).doublePage.value)
                    hidden(f); assertTrue(f.snapshots.isEmpty())
                    draw(f); shown(f, 0)
                    assertEquals(0, PdfPosition.decodeOrNull(f.snapshots.last().location, 100))
                    assertEquals(0L, db.openHelper.readableDatabase.compileStatement("SELECT COUNT(*) FROM reading_progress").simpleQueryForLong())
                    // No saved spread is discarded when returning to the normal page mode.
                    val paged = state.copy(preferences = preferences.copy(navigationMode = PdfNavigationMode.PAGED))
                    hide(f)
                    compose.runOnUiThread { assertTrue(binding.apply(f.surface, f.session.book, paged) { true }) }
                    waitFor { !status(f).scrolling && status(f).renderedPage.value == 0 }
                    assertTrue(fragmentPreference(f, "doublePagePreferred"))
                    assertEquals(PdfNavigationMode.VERTICAL_SCROLL, RoomBookPdfViewPreferencesRepository(db).observe("scroll-pdf", hash).first().navigationMode)
                } finally { f.close() }
            }
            verifyFreshSurface(initial)
            db.close(); db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            assertEquals(initial, RoomBookPdfViewPreferencesRepository(db).observe("scroll-pdf", hash).first())
            verifyFreshSurface(RoomBookPdfViewPreferencesRepository(db).observe("scroll-pdf", hash).first())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun realVerticalDragsMoveOriginalPagesWhileOnlyNativePrimaryDrawPublishesPosition() = runBlocking {
        val f = open()
        try {
            hidden(f); assertTrue(f.snapshots.isEmpty()); assertFalse(status(f).doublePage.value)
            assertTrue(f.native.events.filter { it.startsWith("render:") }.map { it.substringAfter(':') }.distinct().size < 8)
            draw(f, "first"); shown(f, 0)
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput { swipeUp(durationMillis = 800) }
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput { swipeUp(durationMillis = 800) }
            waitFor { status(f).requestedPage.value > 0 }
            val page = status(f).requestedPage.value
            waitFor { status(f).renderedPage.value == page }; draw(f, "drag"); shown(f, page)
            assertEquals(page, PdfPosition.decodeOrNull(f.snapshots.last().location, 100))
            assertNotNull(PdfPosition.decodeResumeOrNull(f.snapshots.last().location, 100)!!.anchorY)
            assertTrue(f.native.events.filter { it.startsWith("render:") }.map { it.substringAfter(':') }.distinct().size < 12)
        } finally { f.close() }
    }
    @Test fun shortExplicitTargetsBookmarksThumbnailsAndLastPageKeepTheirActualPageWithoutFeedbackJumps() = runBlocking {
        val f = open()
        try {
            go(f, 1); hidden(f); draw(f, "short-target"); shown(f, 1)
            // The taller visible neighbor has more pixels, but must not steal this explicit short target.
            compose.waitForIdle(); assertEquals(1, status(f).requestedPage.value)
            val command = status(f).scrollNavigation.value
            f.surface.pdfThumbnails!!.render(90)
            assertEquals(command, status(f).scrollNavigation.value); shown(f, 1)
            hide(f); assertTrue(f.surface.bookSearch!!.goTo(PdfPosition.encode(1)))
            waitFor { status(f).renderedPage.value == 1 && status(f).requestedPage.value == 1 && status(f).scrollNavigationSettled }; draw(f); shown(f, 1)
            hide(f); assertTrue(f.surface.bookmarkNavigator!!.goTo(PdfPosition.encode(3)))
            waitFor { status(f).renderedPage.value == 3 && status(f).requestedPage.value == 3 && status(f).scrollNavigationSettled }; hidden(f)
            draw(f); shown(f, 3)
            go(f, 99); draw(f, "last"); shown(f, 99); assertFalse(f.surface.forward())
            hide(f); assertTrue(f.surface.backward()); waitFor { status(f).renderedPage.value == 98 && status(f).scrollNavigationSettled }; draw(f); shown(f, 98)
            assertFalse(f.surface.pageNavigator!!.goTo(100)); shown(f, 98)
        } finally { f.close() }
    }
    @Test fun horizontalRasterRetryReappliesThePendingOriginalPointAfterTheErrorViewportClampedItsScroll() = runBlocking {
        val native = ScrollNative().apply { markResumeLines = true }
        val expected = PdfResumePosition(2, 600_000)
        val f = open(native = native, initial = PdfPosition.encode(expected), scrolling = false, applyPreferences = false)
        try {
            native.renderFailures.add(2)
            compose.runOnUiThread {
                f.surface.pdfView!!.fitWidth()
                assertTrue(f.surface.pdfScroll!!.apply(PdfNavigationMode.HORIZONTAL_SCROLL))
            }
            waitFor { status(f).renderFailed.value && status(f).requestedPage.value == 2 }
            hidden(f); assertTrue(f.snapshots.isEmpty())
            compose.onNodeWithTag("pdf-horizontal-pages").assertExists()
            native.renderFailures.remove(2)
            compose.onNodeWithText("Yeniden dene").performScrollTo().assertIsDisplayed().performClick()
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            hidden(f)
            sameOriginalPoint(expected, paintedPoint(f, fit = PdfFitMode.WIDTH))
            assertEquals(PdfNavigationMode.HORIZONTAL_SCROLL, f.surface.pdfScroll!!.navigationMode!!.value)
            assertTrue(f.snapshots.all { it.spans.isEmpty() })
        } finally { f.close() }
    }

    @Test fun failedRasterAndDimensionRequestsHaveExplicitRetryWithoutSavingAnOldPage() = runBlocking {
        val native = ScrollNative().apply { renderFailures.add(1) }
        val f = open(native)
        try {
            draw(f); shown(f, 0)
            hide(f); assertTrue(f.surface.pageNavigator!!.goTo(1))
            waitFor { status(f).requestedPage.value == 1 && status(f).renderFailed.value }; hidden(f)
            assertFalse(f.snapshots.any { PdfPosition.decodeOrNull(it.location, 100) == 1 })
            native.renderFailures.remove(1)
            compose.onNodeWithText("Yeniden dene").performScrollTo().assertIsDisplayed().performClick()
            waitFor { status(f).renderedPage.value == 1 }; hidden(f); draw(f); shown(f, 1)
            native.dimensionFailures.add(50); hide(f); assertTrue(f.surface.pageNavigator!!.goTo(50))
            waitFor { status(f).requestedPage.value == 50 && status(f).renderFailed.value }; hidden(f)
            compose.onNodeWithText("Bu PDF sayfasının boyutları okunamadı.").assertExists()
            native.dimensionFailures.remove(50); compose.onNodeWithText("Yeniden dene").performScrollTo().assertIsDisplayed().performClick()
            waitFor { status(f).renderedPage.value == 50 }; draw(f); shown(f, 50)
            assertTrue(native.bitmaps.any { it.isRecycled }) // Failed undelivered raster was cleaned up.
        } finally { f.close() }
    }
    @Test @Config(qualifiers = "w900dp-h600dp-land-mdpi")
    fun modeCropNightPauseAndSurfaceReplacementPreserveTheExactPageAndRevokeObsoleteControllers() = runBlocking {
        val f = open()
        try {
            go(f, 3); draw(f); shown(f, 3)
            hide(f)
            compose.runOnUiThread {
                f.surface.pdfSpread!!.apply(true); f.surface.pdfCrop!!.apply(PdfPageCrop(10, 0))
                f.surface.pdfNightMode!!.apply(true)
            }
            // Native pixels, not only a queued preference, prove night inversion and asymmetric crop.
            // Uncropped magenta occupies x=300..600; the cropped page occupies x=330..570.
            compose.waitUntil(10_000) {
                draw(f) == Color.GREEN && draw(f, sampleX = 590) == Color.rgb(35, 39, 37)
            }
            assertFalse(status(f).doublePage.value)
            draw(f, "crop-night"); shown(f, 3)
            hide(f); assertTrue(f.surface.pdfScroll!!.apply(false))
            waitFor { status(f).doublePage.value && status(f).renderedPage.value == 3 }; assertEquals(3, status(f).requestedPage.value)
            assertTrue(f.surface.pdfScroll!!.apply(true)); waitFor { status(f).scrolling && status(f).renderedPage.value == 3 }
            draw(f); shown(f, 3)
            val obsolete = f.surface; val oldController = obsolete.pdfScroll!!
            f.host.pause(); hidden(f); assertFalse(oldController.apply(false)); f.host.resume().visible()
            hide(f); draw(f); shown(f, 3)
            compose.runOnUiThread {
                f.surface = f.session.createSurface(PdfPosition.encode(3), false, {}, {})
                f.host.get().supportFragmentManager.beginTransaction().replace(1002, f.surface.fragment).commitNow()
                f.surface.fragment.requireView().alpha = 0f; f.surface.pdfSwipe!!.allowWhile { true }
            }
            waitFor { status(f).renderedPage.value == 3 }
            assertTrue(f.surface.pdfScroll!!.enabled.value); assertFalse(oldController.apply(false))
            assertFalse(obsolete.readingVisible.first()); assertNull(obsolete.pageNavigator!!.currentPage)
            hidden(f); draw(f); shown(f, 3)
            f.session.close(); hidden(f); assertFalse(f.surface.pdfScroll!!.apply(false))
        } finally { f.close() }
    }
    @Test fun longPressCanFocusAVisibleNativeNeighborWhileZoomedSingleFingerPanDoesNotScrollTheList() = runBlocking {
        val f = open()
        try {
            go(f, 1); draw(f); shown(f, 1)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("PDF sayfası 3").fetchSemanticsNodes().isNotEmpty()
            }
            draw(f) // The neighbor, as well as the primary, must actually have been prepared and painted.
            // Two gestures before the neighbor receives a primary draw must keep only the latest points.
            val latestStart = PdfTextPoint(.25, .25)
            val latestStop = PdfTextPoint(.75, .4)
            val select = PdfReaderFragment::class.java.getDeclaredMethod("select", Int::class.javaPrimitiveType,
                PdfTextPoint::class.java, PdfTextPoint::class.java).apply { isAccessible = true }
            compose.runOnUiThread {
                select.invoke(f.surface.fragment, 2, PdfTextPoint(.1, .1), PdfTextPoint(.2, .2))
                assertEquals(2, status(f).requestedPage.value)
                select.invoke(f.surface.fragment, 2, latestStart, latestStop)
                assertFalse(f.surface.textSelection!!.active)
            }
            draw(f); waitFor { f.surface.textSelection!!.active }
            val latest = f.surface.textSelection!!.capture()!!.pdfSourceAnchor!!
            assertEquals(nativePdfPoint(latestStart, 200, 1200), latest.start)
            assertEquals(nativePdfPoint(latestStop, 200, 1200), latest.stop)
            f.surface.textSelection!!.clear()
            go(f, 1); draw(f); shown(f, 1)
            compose.onNodeWithContentDescription("PDF sayfası 3").performTouchInput {
                down(Offset(center.x, 120f)); advanceEventTime(650); up()
            }
            waitFor { status(f).requestedPage.value == 2 }; draw(f)
            waitFor { f.surface.textSelection!!.active }
            val selected = f.surface.textSelection!!.capture()!!
            assertEquals(2, selected.pdfPageIndex); assertEquals("Native page 2", selected.text)
            assertEquals(2, selected.pdfSourceAnchor!!.pageIndex); assertNotNull(selected.sourceSelectionId)
            f.surface.textSelection!!.clear()
            go(f, 3); draw(f)
            compose.onNodeWithContentDescription("PDF sayfası 4").performTouchInput { doubleClick(center) }
            val before = compose.onNodeWithTag("pdf-continuous-pages").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.onNodeWithContentDescription("PDF sayfası 4").performTouchInput {
                down(center); moveTo(center - Offset(0f, 100f)); up()
            }
            compose.waitForIdle()
            val after = compose.onNodeWithTag("pdf-continuous-pages").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals(before, after, .001f); assertEquals(3, status(f).requestedPage.value)
            draw(f)
            assertEquals(PdfPosition.encode(3), f.surface.bookmarkNavigator!!.capture()!!.location)
        } finally { f.close() }
    }
    @Test fun rapidExternalNavigationCannotPublishACancelledOffscreenNativeRender() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val native = ScrollNative().apply { beforeRender = { if (it == 90) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } } }
        val f = open(native)
        try {
            draw(f); hide(f); compose.runOnUiThread { assertTrue(f.surface.pageNavigator!!.goTo(90)) }
            waitFor { entered.count == 0L }; hidden(f)
            compose.runOnUiThread {
                assertTrue(f.surface.pageNavigator!!.goTo(91)); assertTrue(f.surface.pageNavigator!!.goTo(4))
            }
            release.countDown()
            waitFor { status(f).requestedPage.value == 4 && status(f).renderedPage.value == 4 }; hidden(f)
            draw(f, "latest-target"); shown(f, 4)
            assertFalse(f.snapshots.any { PdfPosition.decodeOrNull(it.location, 100) in setOf(90, 91) })
            assertEquals(4, PdfPosition.decodeOrNull(f.snapshots.last().location, 100))
        } finally { release.countDown(); f.close() }
    }
    @Test fun customTapUsesTheClippedWholeViewportAndTheCurrentModalGateStopsListDrags() = runBlocking {
        val f = open()
        try {
            go(f, 2)
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth() }
            compose.waitUntil(10_000) {
                compose.onNodeWithContentDescription("PDF sayfası 3").fetchSemanticsNode().size.height > 1600
            }
            compose.waitForIdle(); draw(f)
            compose.onNodeWithTag("pdf-continuous-pages").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
            compose.waitForIdle(); draw(f); shown(f, 2)
            val taps = mutableListOf<ReaderTap>()
            compose.runOnUiThread { f.surface.taps!!.setHandler { taps += it; true } }
            val node = compose.onNodeWithContentDescription("PDF sayfası 3")
            val pageBounds = node.fetchSemanticsNode().boundsInRoot
            val listBounds = compose.onNodeWithTag("pdf-continuous-pages").fetchSemanticsNode().boundsInRoot
            // Choose a point in the clipped visible overlap, even when the source page exceeds the viewport.
            val visibleTop = maxOf(pageBounds.top, listBounds.top)
            val visibleBottom = minOf(pageBounds.bottom, listBounds.bottom)
            assertTrue(visibleBottom > visibleTop)
            val point = Offset(pageBounds.width * .9f,
                visibleTop + (visibleBottom - visibleTop) * .4f - pageBounds.top)
            val viewportPoint = Offset(pageBounds.left + point.x - listBounds.left,
                pageBounds.top + point.y - listBounds.top)
            draw(f, "tap-target")
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput {
                down(viewportPoint); advanceEventTime(32); up()
            }; compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            assertEquals("page=$pageBounds list=$listBounds point=$viewportPoint fallback=${f.fallbackTaps}", 1, taps.size)
            val tap = taps.single()
            assertEquals("tap=$tap page=$pageBounds list=$listBounds size=${node.fetchSemanticsNode().size}",
                com.yaprak.reader.domain.settings.TapZone.RIGHT, tap.zone)
            assertEquals((pageBounds.left + point.x - listBounds.left) / listBounds.width, tap.xFraction, .01f)
            assertEquals((pageBounds.top + point.y - listBounds.top) / listBounds.height, tap.yFraction, .01f)
            assertEquals(0, f.fallbackTaps)
            compose.runOnUiThread { f.surface.taps!!.setHandler { false } }
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput {
                down(viewportPoint); advanceEventTime(32); up()
            }; compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            assertEquals(1, f.fallbackTaps)
            compose.runOnUiThread { f.allowed = false }; compose.waitForIdle()
            val range = compose.onNodeWithTag("pdf-continuous-pages").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput { swipeUp(durationMillis = 800) }
            compose.waitForIdle()
            assertEquals(range, compose.onNodeWithTag("pdf-continuous-pages").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
            assertEquals(2, status(f).requestedPage.value)
            compose.runOnUiThread { f.allowed = true }
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput {
                down(center); moveTo(center - Offset(0f, 60f))
            }
            compose.waitForIdle()
            val duringDrag = compose.onNodeWithTag("pdf-continuous-pages").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.runOnUiThread { f.allowed = false }
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput {
                moveTo(center - Offset(0f, 260f)); up()
            }
            compose.waitForIdle()
            assertEquals(duringDrag, compose.onNodeWithTag("pdf-continuous-pages").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        } finally { f.close() }
    }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun shortLandscapeDoubleTypeFailedShortPageHasReachableRealRetryAndToolActions() = runBlocking {
        RuntimeEnvironment.setFontScale(2f)
        val native = ScrollNative().apply { renderFailures.add(1) }
        val f = open(native)
        try {
            assertEquals(2f, f.host.get().resources.configuration.fontScale, 0f)
            hide(f); assertTrue(f.surface.pageNavigator!!.goTo(1))
            waitFor { status(f).requestedPage.value == 1 && status(f).renderFailed.value }; hidden(f)
            compose.onNodeWithText("Araçları göster / gizle").performScrollTo().assertIsDisplayed().performClick()
            assertEquals(1, f.fallbackTaps)
            compose.onNodeWithText("Yeniden dene").performScrollTo().assertIsDisplayed()
            native.renderFailures.remove(1)
            compose.onNodeWithText("Yeniden dene").performClick()
            waitFor { status(f).renderedPage.value == 1 }; hidden(f); draw(f, "failure-retry-double-type"); shown(f, 1)
        } finally { f.close() }
    }
    @Test fun movingWithinOneLongPageSavesANewOriginalAnchorOnlyAfterNativePaint() = runBlocking {
        val f = open(native = ScrollNative().apply { markResumeLines = true }, initial = PdfPosition.encode(2))
        try {
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth() }
            waitFor { status(f).renderedPage.value == 2 }
            draw(f)
            val before = PdfPosition.decodeResumeOrNull(f.snapshots.last().location, 100)!!
            assertEquals(2, before.pageIndex); assertNotNull(before.anchorY)
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput {
                swipe(start = Offset(center.x, height - 160f), end = Offset(center.x, height - 400f), durationMillis = 1200)
            }
            waitFor { status(f).requestedPage.value == 2 }
            hide(f); assertEquals(before, PdfPosition.decodeResumeOrNull(f.snapshots.last().location, 100))
            draw(f, "within-page-resume")
            val after = PdfPosition.decodeResumeOrNull(f.snapshots.last().location, 100)!!
            assertEquals(2, after.pageIndex); assertTrue(after.anchorY!! > before.anchorY!! + 50_000)
            assertTrue(f.snapshots.all { it.spans.isEmpty() })
            assertEquals(PdfPosition.encode(after), f.surface.bookmarkNavigator!!.capture()!!.location)
        } finally { f.close() }
    }

    @Test fun freshSurfaceRestoresThePaintedOriginalPointAcrossWidthAndCropAfterAuthoritativePreferences() = runBlocking {
        var saved = PdfPosition.encode(PdfResumePosition(2, 600_000))
        for (width in listOf(400, 240)) {
            val f = open(native = ScrollNative().apply { markResumeLines = true }, initial = saved, scrolling = false, applyPreferences = false, viewportWidth = width)
            try {
                draw(f); assertTrue(f.snapshots.isEmpty())
                compose.runOnUiThread {
                    f.surface.pdfCrop!!.apply(if (width == 400) PdfPageCrop() else PdfPageCrop(10, 15))
                    f.surface.pdfView!!.fitWidth()
                }
                compose.waitForIdle(); draw(f); assertTrue(f.snapshots.isEmpty())
                compose.runOnUiThread { assertTrue(f.surface.pdfScroll!!.apply(true)) }
                waitFor { status(f).renderedPage.value == 2 }
                compose.waitUntil(10_000) { draw(f, "resume-width-$width"); status(f).drawnPosition.value != null }
                assertEquals(width, f.surface.fragment.requireView().width)
                val actual = PdfPosition.decodeResumeOrNull(f.snapshots.last().location, 100)!!
                assertEquals(2, actual.pageIndex); assertEquals(600_000.0, actual.anchorY!!.toDouble(), 3000.0)
                assertTrue(f.snapshots.all { it.spans.isEmpty() })
                saved = f.snapshots.last().location
            } finally { f.close() }
        }
    }

    @Test fun failedOriginalDimensionsCannotOverwriteThePendingAnchorAndRetryRestoresIt() = runBlocking {
        val f = open(initial = PdfPosition.encode(PdfResumePosition(2, 600_000)), scrolling = false, applyPreferences = false)
        try {
            f.native.dimensionFailures.add(2)
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderFailed.value }
            draw(f); assertTrue(f.snapshots.isEmpty())
            f.native.dimensionFailures.remove(2)
            compose.onNodeWithText("Yeniden dene").performScrollTo().performClick()
            waitFor { status(f).renderedPage.value == 2 }
            compose.waitUntil(10_000) { draw(f); status(f).drawnPosition.value != null }
            val actual = PdfPosition.decodeResumeOrNull(f.snapshots.last().location, 100)!!
            assertEquals(600_000.0, actual.anchorY!!.toDouble(), 3000.0)
        } finally { f.close() }
    }

    @Test fun explicitJumpSupersedesThePendingResumeAndClosedSessionCannotPublishItsLatePoint() = runBlocking {
        val f = open(initial = PdfPosition.encode(PdfResumePosition(2, 600_000)), scrolling = false, applyPreferences = false)
        try {
            f.native.dimensionFailures.add(2)
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderFailed.value }
            compose.runOnUiThread { assertTrue(f.surface.pageNavigator!!.goTo(3)) }
            waitFor { status(f).renderedPage.value == 3 }
            compose.waitUntil(10_000) { draw(f); status(f).drawnPosition.value != null }
            assertEquals(3, PdfPosition.decodeOrNull(f.snapshots.last().location, 100))
            assertFalse(f.snapshots.any { PdfPosition.decodeOrNull(it.location, 100) == 2 })
            f.native.dimensionFailures.remove(2)
            f.session.close(); draw(f)
            assertNull(status(f).drawnPosition.value)
            assertEquals(3, PdfPosition.decodeOrNull(f.snapshots.last().location, 100))
        } finally { f.close() }
    }

    @Test fun pausedNavigationCannotCancelTheSavedOriginalPointBeforePreferencesAreAcknowledged() = runBlocking {
        val initial = PdfPosition.encode(PdfResumePosition(2, 600_000))
        val f = open(initial = initial, applyPreferences = false)
        try {
            draw(f); assertTrue(f.snapshots.isEmpty())
            val beforePage = status(f).requestedPage.value
            val beforeNavigation = status(f).scrollNavigation.value
            compose.runOnUiThread {
                f.host.pause()
                assertFalse(f.surface.pageNavigator!!.goTo(3))
                assertFalse(f.surface.bookmarkNavigator!!.goTo(PdfPosition.encode(4)))
                assertFalse(f.surface.bookSearch!!.goTo(PdfPosition.encode(5)))
                assertFalse(f.surface.forward()); assertFalse(f.surface.backward())
                val fragment = f.surface.fragment as PdfReaderFragment
                assertFalse(fragment.goTo(6)); assertFalse(fragment.move(1)); assertFalse(fragment.turn(-1))
                assertEquals(beforePage, status(f).requestedPage.value)
                assertEquals(beforeNavigation, status(f).scrollNavigation.value)
            }
            draw(f); assertTrue(f.snapshots.isEmpty()); hidden(f)
            compose.runOnUiThread {
                f.host.resume().visible()
                assertTrue(f.surface.pdfScroll!!.apply(true))
            }
            waitFor { status(f).scrolling && status(f).scrollNavigationSettled && status(f).renderedPage.value == 2 }
            draw(f); shown(f, 2)
            waitFor { f.snapshots.isNotEmpty() }
            val restored = PdfPosition.decodeResume(f.snapshots.last().location, 100)
            assertEquals(2, restored.pageIndex)
            assertTrue(kotlin.math.abs(requireNotNull(restored.anchorY) - 600_000) <= 3_000)
            assertTrue(f.snapshots.all { it.spans.isEmpty() })
        } finally { f.close() }
    }

    @Test fun pausedPagedAndContinuousNavigationLeaveTheSamePageUntilTheReaderResumes() = runBlocking {
        for (scrolling in listOf(false, true)) {
            val f = open(scrolling = scrolling)
            try {
                go(f, 2); draw(f); shown(f, 2)
                val count = f.snapshots.size
                val command = status(f).scrollNavigation.value
                compose.runOnUiThread {
                    f.host.pause()
                    assertFalse(f.surface.pageNavigator!!.goTo(10))
                    assertFalse(f.surface.bookmarkNavigator!!.goTo(PdfPosition.encode(11)))
                    assertFalse(f.surface.bookSearch!!.goTo(PdfPosition.encode(12)))
                    assertFalse(f.surface.forward()); assertFalse(f.surface.backward())
                    val fragment = f.surface.fragment as PdfReaderFragment
                    assertFalse(fragment.goTo(13)); assertFalse(fragment.move(-1)); assertFalse(fragment.turn(1))
                    assertEquals(2, status(f).requestedPage.value)
                    assertEquals(command, status(f).scrollNavigation.value)
                }
                draw(f); hidden(f); assertEquals(count, f.snapshots.size)
                compose.runOnUiThread { f.host.resume().visible() }
                draw(f); shown(f, 2)
                go(f, 3); draw(f); shown(f, 3)
                hide(f)
                compose.runOnUiThread { assertTrue(f.surface.bookSearch!!.goTo(PdfPosition.encode(4))) }
                waitFor { status(f).renderedPage.value == 4 && status(f).scrollNavigationSettled }
                draw(f); shown(f, 4)
            } finally { f.close() }
        }
    }

    @Test fun unattachedObsoleteAndClosedFragmentsRejectNavigationWithoutMutatingTheirRequestedPage() = runBlocking {
        val f = open()
        try {
            go(f, 2); draw(f); shown(f, 2)
            val obsolete = f.surface
            val oldStatus = status(f)
            compose.runOnUiThread {
                f.surface = f.session.createSurface(PdfPosition.encode(3), false, {}, {})
                val fresh = f.surface.fragment as PdfReaderFragment
                assertFalse(f.surface.pageNavigator!!.goTo(20))
                assertFalse(f.surface.bookmarkNavigator!!.goTo(PdfPosition.encode(21)))
                assertFalse(f.surface.bookSearch!!.goTo(PdfPosition.encode(22)))
                assertFalse(f.surface.forward()); assertFalse(f.surface.backward())
                assertFalse(fresh.goTo(23)); assertFalse(fresh.move(1)); assertFalse(fresh.turn(-1))
                assertEquals(3, status(f).requestedPage.value)
                // The old child is still RESUMED, but its session generation is already obsolete.
                assertTrue(obsolete.fragment.isResumed)
                val old = obsolete.fragment as PdfReaderFragment
                assertFalse(old.goTo(24)); assertFalse(old.move(1)); assertFalse(old.turn(-1))
                assertEquals(2, oldStatus.requestedPage.value)
                f.host.get().supportFragmentManager.beginTransaction().replace(1002, fresh).commitNow()
                fresh.requireView().alpha = 0f; f.surface.pdfSwipe!!.allowWhile { true }
                assertTrue(f.surface.pdfScroll!!.apply(true))
            }
            waitFor { status(f).renderedPage.value == 3 && status(f).scrollNavigationSettled }
            draw(f); shown(f, 3)
            compose.runOnUiThread {
                f.session.close()
                val closed = f.surface.fragment as PdfReaderFragment
                assertFalse(closed.goTo(25)); assertFalse(closed.move(1)); assertFalse(closed.turn(-1))
                assertFalse(f.surface.pageNavigator!!.goTo(26)); assertFalse(f.surface.forward()); assertFalse(f.surface.backward())
                assertEquals(3, status(f).requestedPage.value)
            }
            hidden(f)
        } finally { f.close() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun paintedGeometry(f: Fixture): PdfDrawGeometry? {
        val host = f.surface.fragment.requireView() as PdfDrawProofView
        val witnesses = PdfDrawProofView::class.java.getDeclaredField("witnesses").apply { isAccessible = true }
            .get(host) as Map<Int, PdfPageDrawWitness>
        val witness = witnesses[status(f).requestedPage.value] ?: return null
        if (witness.paintedTicket == null) return null
        return PdfPageDrawWitness::class.java.getDeclaredField("geometry").apply { isAccessible = true }.get(witness) as? PdfDrawGeometry
    }
    private fun paintedPoint(f: Fixture, name: String? = null, fit: PdfFitMode? = null, crop: PdfPageCrop? = null): PdfResumePosition {
        compose.waitUntil(10_000) {
            draw(f, name)
            val geometry = paintedGeometry(f)
            status(f).drawnPosition.value != null && geometry != null &&
                geometry.viewport.width == f.surface.fragment.requireView().width &&
                (fit == null || geometry.fit == fit) && (crop == null || geometry.crop == crop)
        }
        return requireNotNull(PdfPosition.decodeResumeOrNull(f.snapshots.last().location, 100))
    }
    private fun resizeViewport(f: Fixture, width: Int, height: Int = android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
        compose.runOnUiThread {
            val root = f.host.get().findViewById<FrameLayout>(1002)
            root.layoutParams = root.layoutParams.apply { this.width = width; this.height = height }
            root.requestLayout()
        }
        waitFor { f.surface.fragment.requireView().width == width && (height < 0 || f.surface.fragment.requireView().height == height) }
    }
    private fun sameOriginalPoint(expected: PdfResumePosition, actual: PdfResumePosition) {
        assertEquals(expected.pageIndex, actual.pageIndex)
        assertEquals(requireNotNull(expected.anchorY).toDouble(), requireNotNull(actual.anchorY).toDouble(), 3_000.0)
    }

    @Test fun liveViewportWidthAndHeightKeepTheLatestPaintedPointRatherThanReplayTheStartupAnchor() = runBlocking {
        val f = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 600_000)), scrolling = false, applyPreferences = false)
        try {
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            val initial = paintedPoint(f, "live-width-400")
            sameOriginalPoint(PdfResumePosition(2, 600_000), initial)
            resizeViewport(f, 240)
            waitFor { status(f).renderedPage.value != null && status(f).scrollNavigationSettled }
            sameOriginalPoint(initial, paintedPoint(f, "live-width-240"))
            resizeViewport(f, 240, 480)
            waitFor { status(f).renderedPage.value != null && status(f).scrollNavigationSettled }
            sameOriginalPoint(initial, paintedPoint(f, "live-height-480"))
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput {
                swipe(start = Offset(center.x, height - 80f), end = Offset(center.x, height - 260f), durationMillis = 1200)
            }
            val moved = paintedPoint(f)
            assertEquals(2, moved.pageIndex); assertTrue(moved.anchorY!! > initial.anchorY!! + 25_000)
            resizeViewport(f, 400)
            waitFor { status(f).renderedPage.value != null && status(f).scrollNavigationSettled }
            sameOriginalPoint(moved, paintedPoint(f, "live-latest-point"))
            assertTrue(f.snapshots.all { it.spans.isEmpty() })
            shown(f, 2)
        } finally { f.close() }
    }

    @Test fun liveCropAndFitChangesKeepTheLatestOriginalPointAfterTheReaderHasMovedWithinThePage() = runBlocking {
        val f = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 200_000)), scrolling = false, applyPreferences = false)
        try {
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            val initial = paintedPoint(f)
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput {
                swipe(start = Offset(center.x, height - 160f), end = Offset(center.x, height - 400f), durationMillis = 1200)
            }
            val moved = paintedPoint(f)
            assertTrue(moved.anchorY!! > initial.anchorY!! + 50_000)
            compose.runOnUiThread { f.surface.pdfCrop!!.apply(PdfPageCrop(10, 15)) }
            waitFor { status(f).renderedPage.value != null && status(f).scrollNavigationSettled }
            sameOriginalPoint(moved, paintedPoint(f, "live-crop", PdfFitMode.WIDTH, PdfPageCrop(10, 15)))
            compose.runOnUiThread { f.surface.pdfView!!.fitPage() }
            waitFor { status(f).renderedPage.value != null && status(f).scrollNavigationSettled }
            sameOriginalPoint(moved, paintedPoint(f, "live-fit-page", PdfFitMode.PAGE, PdfPageCrop(10, 15)))
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); f.surface.pdfCrop!!.apply(PdfPageCrop()) }
            waitFor { status(f).renderedPage.value != null && status(f).scrollNavigationSettled }
            sameOriginalPoint(moved, paintedPoint(f, fit = PdfFitMode.WIDTH, crop = PdfPageCrop()))
            sameOriginalPoint(moved, PdfPosition.decodeResume(f.surface.bookmarkNavigator!!.capture()!!.location, 100))
        } finally { f.close() }
    }

    @Test fun resizingWhileOriginalDimensionsAreUnavailableUsesTheNewViewportWhenTheSavedPointRetries() = runBlocking {
        val f = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 600_000)), scrolling = false, applyPreferences = false)
        try {
            f.native.dimensionFailures.add(2)
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderFailed.value }
            draw(f); assertTrue(f.snapshots.isEmpty())
            resizeViewport(f, 240)
            draw(f); assertTrue(f.snapshots.isEmpty())
            f.native.dimensionFailures.remove(2)
            compose.onNodeWithText("Yeniden dene").performScrollTo().performClick()
            waitFor { status(f).renderedPage.value != null && status(f).scrollNavigationSettled }
            sameOriginalPoint(PdfResumePosition(2, 600_000), paintedPoint(f, "live-retry-width-240"))
            assertTrue(f.snapshots.all { it.spans.isEmpty() })
        } finally { f.close() }
    }

    @Test fun continuousBookmarkReturnsToTheExactPaintedPointWithinThePageAndAfterLeavingIt() = runBlocking {
        val f = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 600_000)), scrolling = false, applyPreferences = false)
        try {
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            val point = paintedPoint(f)
            val bookmark = requireNotNull(f.surface.bookmarkNavigator!!.capture())
            sameOriginalPoint(point, PdfPosition.decodeResume(bookmark.location, 100))
            assertEquals("Sayfa 3 · okuma noktası", bookmark.label)
            compose.onNodeWithTag("pdf-continuous-pages").performTouchInput {
                swipe(start = Offset(center.x, height - 160f), end = Offset(center.x, height - 400f), durationMillis = 1200)
            }
            val moved = paintedPoint(f); assertTrue(moved.anchorY!! > point.anchorY!! + 50_000)
            hide(f); compose.runOnUiThread { assertTrue(f.surface.bookmarkNavigator!!.goTo(bookmark.location)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            sameOriginalPoint(point, paintedPoint(f, "bookmark-within-page"))
            go(f, 8); draw(f); shown(f, 8)
            hide(f); compose.runOnUiThread { assertTrue(f.surface.bookmarkNavigator!!.goTo(bookmark.location)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            sameOriginalPoint(point, paintedPoint(f, "bookmark-return"))
            assertTrue(f.snapshots.all { it.spans.isEmpty() })
        } finally { f.close() }
    }

    @Test fun nativeBookmarkPointDeduplicatesInRealRoomSurvivesReopenAndOpensWithDifferentWidthAndCrop() = runBlocking {
        val original = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 600_000)), scrolling = false, applyPreferences = false)
        var originalClosed = false
        val context = RuntimeEnvironment.getApplication()
        val path = temporary.newFile("bm.db").absolutePath
        var db = Room.databaseBuilder(context, LibraryDatabase::class.java, path).build()
        try {
            compose.runOnUiThread { original.surface.pdfView!!.fitWidth(); assertTrue(original.surface.pdfScroll!!.apply(true)) }
            waitFor { status(original).renderedPage.value == 2 && status(original).scrollNavigationSettled }
            val point = paintedPoint(original)
            val target = requireNotNull(original.surface.bookmarkNavigator!!.capture())
            sameOriginalPoint(point, PdfPosition.decodeResume(target.location, 100))
            val book = original.session.book
            com.yaprak.reader.data.persistence.RoomLibraryRepository(db.books()).add(book)
            val reading = com.yaprak.reader.data.persistence.RoomReadingStateRepository(db)
            reading.record(book.id, book.sha256, ReadingSnapshot(PdfPosition.encode(77)))
            val repository = com.yaprak.reader.data.persistence.RoomBookmarkRepository(db)
            coroutineScope {
                awaitAll(async { repository.add(book.id, book.sha256, target) }, async { repository.add(book.id, book.sha256, target) })
            }
            assertEquals(target, repository.observe(book.id, book.sha256).first().single().target)
            assertNotNull(runCatching { repository.add(book.id, "b".repeat(64), target) }.exceptionOrNull())
            assertEquals(PdfPosition.encode(77), reading.resume(book.id, book.sha256))
            db.close(); db = Room.databaseBuilder(context, LibraryDatabase::class.java, path).build()
            val restored = com.yaprak.reader.data.persistence.RoomBookmarkRepository(db)
                .observe(book.id, book.sha256).first().single().target
            assertEquals(target, restored)
            assertEquals(PdfPosition.encode(77), com.yaprak.reader.data.persistence.RoomReadingStateRepository(db).resume(book.id, book.sha256))
            assertTrue(db.reading().observed(book.id, book.sha256).isEmpty())
            original.close(); originalClosed = true
            val next = open(native = ScrollNative().apply { markResumeLines = true },
                scrolling = false, applyPreferences = false, viewportWidth = 240)
            try {
                compose.runOnUiThread {
                    next.surface.pdfCrop!!.apply(PdfPageCrop(10, 15)); next.surface.pdfView!!.fitWidth()
                    assertTrue(next.surface.pdfScroll!!.apply(true))
                    assertTrue(next.surface.bookmarkNavigator!!.goTo(restored.location))
                }
                waitFor { status(next).renderedPage.value == 2 && status(next).scrollNavigationSettled }
                sameOriginalPoint(point, paintedPoint(next, "bookmark-room-width-240", PdfFitMode.WIDTH, PdfPageCrop(10, 15)))
            } finally { next.close() }
        } finally { if (!originalClosed) original.close(); db.close() }
    }

    @Test fun pagedBookmarksRemainLegacyAndInvalidOrInactiveOriginalPointTargetsCannotMoveTheReader() = runBlocking {
        val f = open(scrolling = false, initial = PdfPosition.encode(2))
        try {
            draw(f); shown(f, 2)
            assertEquals(PdfPosition.encode(2), f.surface.bookmarkNavigator!!.capture()!!.location)
            val point = PdfPosition.encode(PdfResumePosition(2, 600_000))
            compose.runOnUiThread { assertTrue(f.surface.bookmarkNavigator!!.goTo(point)) }
            draw(f); shown(f, 2); assertFalse(f.surface.pdfScroll!!.enabled.value)
            assertEquals(PdfPosition.encode(2), f.surface.bookmarkNavigator!!.capture()!!.location)
            val before = status(f).requestedPage.value
            val bad = point.copy(locatorJson = point.locatorJson.replace("600000", "-1"))
            compose.runOnUiThread {
                assertFalse(f.surface.bookmarkNavigator!!.goTo(bad)); assertEquals(before, status(f).requestedPage.value)
                f.host.pause()
                assertNull(f.surface.bookmarkNavigator!!.capture()); assertFalse(f.surface.bookmarkNavigator!!.goTo(point))
                assertEquals(before, status(f).requestedPage.value)
                f.host.resume().visible()
            }
            draw(f); shown(f, 2)
            f.session.close(); assertNull(f.surface.bookmarkNavigator!!.capture())
            assertFalse(f.surface.bookmarkNavigator!!.goTo(point))
        } finally { f.close() }
    }

    @Test fun actualJumpHistoryReturnsToTheOriginalNativePdfPointAfterASearchPageJump() = runBlocking {
        val f = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 600_000)), scrolling = false, applyPreferences = false)
        try {
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            val point = paintedPoint(f)
            val history = com.yaprak.reader.feature.reader.JumpHistory()
            val source = f.surface.bookmarkNavigator!!.capture()!!.location
            hide(f)
            compose.runOnUiThread { history.record(source, f.surface.bookSearch!!.goTo(PdfPosition.encode(8))) }
            waitFor { status(f).renderedPage.value == 8 && status(f).scrollNavigationSettled }
            draw(f); shown(f, 8); assertTrue(history.canReturn)
            hide(f); compose.runOnUiThread { assertTrue(history.returnTo(f.surface.bookmarkNavigator!!::goTo)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            sameOriginalPoint(point, paintedPoint(f, "bookmark-jump-return")); assertFalse(history.canReturn)
        } finally { f.close() }
    }

    private fun noteSource(page: Int = 2) = PdfSourceAnchor(page, 200, 1200,
        com.yaprak.reader.reader.PdfPagePoint(100, 1000), com.yaprak.reader.reader.PdfPagePoint(20, 720),
        listOf(com.yaprak.reader.reader.PdfTextBounds(20.0, 840.0, 100.0, 860.0),
            com.yaprak.reader.reader.PdfTextBounds(20.0, 720.0, 100.0, 740.0)))

    @Test fun verifiedAnnotationSourceOpensItsEarliestOriginalLineWithinThePageAfterLeavingAndAcrossCropThenReturns() = runBlocking {
        val f = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 300_000)), scrolling = false, applyPreferences = false)
        try {
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            val before = paintedPoint(f); val source = f.surface.bookmarkNavigator!!.capture()!!.location
            val navigator = requireNotNull(f.surface.pdfAnnotationNavigator)
            val history = com.yaprak.reader.feature.reader.JumpHistory()
            hide(f); compose.runOnUiThread { history.record(source, navigator.goTo(noteSource())) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            sameOriginalPoint(PdfResumePosition(2, 600_000), paintedPoint(f, "annotation-original-point"))
            go(f, 8); draw(f); shown(f, 8)
            hide(f); compose.runOnUiThread { assertTrue(navigator.goTo(noteSource())) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            sameOriginalPoint(PdfResumePosition(2, 600_000), paintedPoint(f))
            resizeViewport(f, 240); compose.runOnUiThread { f.surface.pdfCrop!!.apply(PdfPageCrop(10, 15)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            sameOriginalPoint(PdfResumePosition(2, 600_000), paintedPoint(f, "annotation-original-crop-240", PdfFitMode.WIDTH, PdfPageCrop(10, 15)))
            hide(f); compose.runOnUiThread { assertTrue(history.returnTo(f.surface.bookmarkNavigator!!::goTo)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            sameOriginalPoint(before, paintedPoint(f, "annotation-return-point", PdfFitMode.WIDTH, PdfPageCrop(10, 15)))
            assertFalse(history.canReturn); assertTrue(f.snapshots.all { it.spans.isEmpty() })
            assertFalse(f.surface.textSelection!!.active)
        } finally { f.close() }
    }

    @Test fun annotationSourceNavigationKeepsPagedModeAndRejectsUnsupportedPausedObsoleteClosedAndForeignPages() = runBlocking {
        val f = open(scrolling = false)
        try {
            val navigator = requireNotNull(f.surface.pdfAnnotationNavigator)
            compose.runOnUiThread { assertTrue(navigator.goTo(noteSource())) }
            waitFor { status(f).renderedPage.value == 2 }; draw(f); shown(f, 2)
            assertFalse(f.surface.pdfScroll!!.enabled.value)
            assertEquals(PdfPosition.encode(2), f.surface.bookmarkNavigator!!.capture()!!.location)
            val revision = status(f).scrollNavigation.value
            compose.runOnUiThread {
                assertFalse(navigator.goTo(noteSource(100)))
                f.host.pause(); assertFalse(navigator.goTo(noteSource(3)))
                assertEquals(2, status(f).requestedPage.value); assertEquals(revision, status(f).scrollNavigation.value)
                f.host.resume().visible()
                f.session.createSurface(PdfPosition.encode(0), false, {}, {})
                assertTrue(f.surface.fragment.isResumed); assertFalse(navigator.goTo(noteSource(3)))
            }
            f.session.close(); assertFalse(navigator.goTo(noteSource(3)))
        } finally { f.close() }
        val legacy = open(native = ScrollNative(textAvailable = false))
        try {
            assertNull(legacy.surface.pdfAnnotationNavigator); assertNull(legacy.surface.textSelection)
            go(legacy, 2); draw(legacy); shown(legacy, 2)
        } finally { legacy.close() }
    }

    private fun zoomAndPanCurrentTallPage(f: Fixture) {
        val list = compose.onNodeWithTag("pdf-continuous-pages")
        list.performTouchInput { doubleClick(center) }
        draw(f)
        assertEquals(2f, requireNotNull(paintedGeometry(f)).zoom, .001f)
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        list.performTouchInput {
            down(center); moveTo(center + Offset(20f, -30f), 100)
            moveTo(center + Offset(60f, -100f), 100); up()
        }
        draw(f)
        val geometry = requireNotNull(paintedGeometry(f))
        assertEquals(2f, geometry.zoom, .001f); assertNotEquals(Offset.Zero, geometry.pan)
        assertEquals(before, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        assertNotNull(requireNotNull(status(f).drawnPosition.value).anchorY)
    }

    @Test fun pointBookmarkClearsSamePageZoomAndPanOnlyAfterFreshNativePaint() = runBlocking {
        val f = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 600_000)), scrolling = false, applyPreferences = false)
        try {
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            val point = paintedPoint(f)
            val bookmark = requireNotNull(f.surface.bookmarkNavigator!!.capture())
            repeat(2) { attempt ->
                zoomAndPanCurrentTallPage(f)
                assertEquals(PdfPosition.encode(requireNotNull(status(f).drawnPosition.value)), f.surface.bookmarkNavigator!!.capture()!!.location)
                hide(f)
                compose.runOnUiThread {
                    assertTrue(f.surface.bookmarkNavigator!!.goTo(bookmark.location))
                    assertNull(status(f).drawnPosition.value)
                    assertNull(f.surface.bookmarkNavigator!!.capture())
                }
                waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
                hidden(f)
                val returned = paintedPoint(f, "zoom-point-bookmark-$attempt")
                assertEquals(1f, requireNotNull(paintedGeometry(f)).zoom, .001f)
                assertEquals(Offset.Zero, requireNotNull(paintedGeometry(f)).pan)
                sameOriginalPoint(point, returned)
            }
            assertTrue(f.snapshots.all { it.spans.isEmpty() })
        } finally { f.close() }
    }

    @Test fun annotationAndJumpReturnClearSamePageZoomAtCurrentWidthAndCrop() = runBlocking {
        val crop = PdfPageCrop(10, 15)
        val f = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 300_000)), scrolling = false,
            applyPreferences = false, viewportWidth = 240)
        try {
            compose.runOnUiThread {
                f.surface.pdfCrop!!.apply(crop); f.surface.pdfView!!.fitWidth()
                assertTrue(f.surface.pdfScroll!!.apply(true))
            }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            val before = paintedPoint(f, fit = PdfFitMode.WIDTH, crop = crop)
            val source = f.surface.bookmarkNavigator!!.capture()!!.location
            val history = com.yaprak.reader.feature.reader.JumpHistory()
            val navigator = requireNotNull(f.surface.pdfAnnotationNavigator)
            zoomAndPanCurrentTallPage(f)
            hide(f); compose.runOnUiThread { history.record(source, navigator.goTo(noteSource())) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            val notePoint = paintedPoint(f, "zoom-point-annotation-width-240", PdfFitMode.WIDTH, crop)
            assertEquals(1f, requireNotNull(paintedGeometry(f)).zoom, .001f)
            assertEquals(Offset.Zero, requireNotNull(paintedGeometry(f)).pan)
            sameOriginalPoint(PdfResumePosition(2, 600_000), notePoint)
            zoomAndPanCurrentTallPage(f)
            hide(f); compose.runOnUiThread { assertTrue(history.returnTo(f.surface.bookmarkNavigator!!::goTo)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            val returned = paintedPoint(f, "zoom-point-history-width-240", PdfFitMode.WIDTH, crop)
            assertEquals(1f, requireNotNull(paintedGeometry(f)).zoom, .001f)
            assertEquals(Offset.Zero, requireNotNull(paintedGeometry(f)).pan)
            sameOriginalPoint(before, returned)
            assertFalse(history.canReturn); assertFalse(f.surface.textSelection!!.active)
            assertTrue(f.snapshots.all { it.spans.isEmpty() })
        } finally { f.close() }
    }

    @Test fun invalidPausedAndPageOnlyCommandsKeepTheCurrentZoomedTransform() = runBlocking {
        val f = open(initial = PdfPosition.encode(PdfResumePosition(2, 300_000)),
            scrolling = false, applyPreferences = false)
        try {
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            paintedPoint(f); zoomAndPanCurrentTallPage(f)
            val transform = requireNotNull(paintedGeometry(f))
            val command = status(f).scrollNavigation.value
            val point = PdfPosition.encode(PdfResumePosition(2, 600_000))
            val bad = point.copy(locatorJson = point.locatorJson.replace("600000", "-1"))
            compose.runOnUiThread {
                assertFalse(f.surface.bookmarkNavigator!!.goTo(bad))
                assertFalse(f.surface.pdfAnnotationNavigator!!.goTo(noteSource(100)))
                assertEquals(command, status(f).scrollNavigation.value)
                f.host.pause()
                assertFalse(f.surface.bookmarkNavigator!!.goTo(point))
                assertFalse(f.surface.pdfAnnotationNavigator!!.goTo(noteSource()))
                assertEquals(command, status(f).scrollNavigation.value)
                f.host.resume().visible()
            }
            draw(f)
            assertEquals(transform, paintedGeometry(f))
            val zoomedPoint = requireNotNull(status(f).drawnPosition.value)
            assertNotNull(zoomedPoint.anchorY)
            hide(f); compose.runOnUiThread { assertTrue(f.surface.pageNavigator!!.goTo(2)) }
            waitFor { status(f).scrollNavigationSettled }; draw(f)
            assertEquals(transform, paintedGeometry(f))
            assertEquals(PdfPosition.encode(zoomedPoint), f.surface.bookmarkNavigator!!.capture()!!.location)
        } finally { f.close() }
    }


    private fun paintedOriginalPoint(f: Fixture, name: String? = null, crop: PdfPageCrop? = null): PdfResumePosition {
        paintedPoint(f, name, crop = crop)
        val actual = requireNotNull(status(f).drawnPosition.value)
        assertNotNull("A visible zoomed page must retain its original vertical point", actual.anchorY)
        waitFor { f.snapshots.lastOrNull()?.location == PdfPosition.encode(actual) }
        return actual
    }

    @Test fun zoomedPdfPointSurvivesRoomAndReopenAtBaseline() = runBlocking {
        val original = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 600_000)), scrolling = false, applyPreferences = false)
        var originalClosed = false
        val context = RuntimeEnvironment.getApplication()
        val path = temporary.newFile("zr.db").absolutePath
        var db = Room.databaseBuilder(context, LibraryDatabase::class.java, path).build()
        try {
            compose.runOnUiThread { original.surface.pdfView!!.fitWidth(); assertTrue(original.surface.pdfScroll!!.apply(true)) }
            waitFor { status(original).renderedPage.value == 2 && status(original).scrollNavigationSettled }
            val before = paintedOriginalPoint(original)
            zoomAndPanCurrentTallPage(original)
            val point = paintedOriginalPoint(original, "zoom-resume-original")
            assertTrue(kotlin.math.abs(requireNotNull(point.anchorY) - requireNotNull(before.anchorY)) > 5_000)
            val target = requireNotNull(original.surface.bookmarkNavigator!!.capture())
            assertEquals(PdfPosition.encode(point), target.location)
            val book = original.session.book
            com.yaprak.reader.data.persistence.RoomLibraryRepository(db.books()).add(book)
            com.yaprak.reader.data.persistence.RoomReadingStateRepository(db).record(book.id, book.sha256, original.snapshots.last())
            com.yaprak.reader.data.persistence.RoomBookmarkRepository(db).add(book.id, book.sha256, target)
            db.close(); db = Room.databaseBuilder(context, LibraryDatabase::class.java, path).build()
            val restored = requireNotNull(com.yaprak.reader.data.persistence.RoomReadingStateRepository(db).resume(book.id, book.sha256))
            assertEquals(target.location, restored)
            assertEquals(target, com.yaprak.reader.data.persistence.RoomBookmarkRepository(db).observe(book.id, book.sha256).first().single().target)
            assertTrue(db.reading().observed(book.id, book.sha256).isEmpty())
            original.close(); originalClosed = true
            val crop = PdfPageCrop(10, 15)
            val next = open(native = ScrollNative().apply { markResumeLines = true }, initial = restored,
                scrolling = false, applyPreferences = false, viewportWidth = 240)
            try {
                compose.runOnUiThread { next.surface.pdfCrop!!.apply(crop); next.surface.pdfView!!.fitWidth(); assertTrue(next.surface.pdfScroll!!.apply(true)) }
                waitFor { status(next).renderedPage.value == 2 && status(next).scrollNavigationSettled }
                sameOriginalPoint(point, paintedOriginalPoint(next, "zoom-resume-reopened", crop))
                assertEquals(1f, requireNotNull(paintedGeometry(next)).zoom, .001f)
                assertEquals(Offset.Zero, requireNotNull(paintedGeometry(next)).pan)
                assertTrue(next.snapshots.all { it.spans.isEmpty() })
            } finally { next.close() }
        } finally { if (!originalClosed) original.close(); db.close() }
    }

    @Test fun zoomedPdfPointSurvivesLiveWidthHeightAndCropReflowWithFreshBaselinePaint() = runBlocking {
        val f = open(native = ScrollNative().apply { markResumeLines = true },
            initial = PdfPosition.encode(PdfResumePosition(2, 600_000)), scrolling = false, applyPreferences = false)
        try {
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            paintedOriginalPoint(f); zoomAndPanCurrentTallPage(f)
            val point = paintedOriginalPoint(f)
            hide(f); resizeViewport(f, 240, 600)
            waitFor { status(f).scrollNavigationSettled }; hidden(f)
            sameOriginalPoint(point, paintedOriginalPoint(f, "zoom-resume-resized"))
            assertEquals(1f, requireNotNull(paintedGeometry(f)).zoom, .001f)
            assertEquals(Offset.Zero, requireNotNull(paintedGeometry(f)).pan)
            zoomAndPanCurrentTallPage(f)
            val moved = paintedOriginalPoint(f)
            val crop = PdfPageCrop(10, 15)
            hide(f); compose.runOnUiThread { f.surface.pdfCrop!!.apply(crop) }
            waitFor { status(f).scrollNavigationSettled }; hidden(f)
            sameOriginalPoint(moved, paintedOriginalPoint(f, "zoom-resume-cropped", crop))
            assertEquals(1f, requireNotNull(paintedGeometry(f)).zoom, .001f)
            assertEquals(Offset.Zero, requireNotNull(paintedGeometry(f)).pan)
            assertTrue(f.snapshots.all { it.spans.isEmpty() })
        } finally { f.close() }
    }

    @Test fun zoomedPdfPositionStillRequiresCurrentVisiblePaintAndResumedOwner() = runBlocking {
        val f = open(initial = PdfPosition.encode(PdfResumePosition(2, 600_000)),
            scrolling = false, applyPreferences = false)
        try {
            compose.runOnUiThread { f.surface.pdfView!!.fitWidth(); assertTrue(f.surface.pdfScroll!!.apply(true)) }
            waitFor { status(f).renderedPage.value == 2 && status(f).scrollNavigationSettled }
            paintedPoint(f); zoomAndPanCurrentTallPage(f)
            hide(f); hidden(f)
            val count = f.snapshots.size
            compose.runOnUiThread { f.host.pause() }; draw(f); hidden(f)
            assertEquals(count, f.snapshots.size)
            compose.runOnUiThread { f.host.resume().visible() }; draw(f); shown(f, 2)
            val old = f.surface
            compose.runOnUiThread { f.session.createSurface(PdfPosition.encode(0), false, {}, {}) }
            assertNull(old.bookmarkNavigator!!.capture()); assertNull(old.pageNavigator!!.captureStablePage())
            assertFalse(old.readingVisible.first())
            f.session.close(); assertNull(old.bookmarkNavigator!!.capture())
            assertTrue(f.snapshots.all { it.spans.isEmpty() })
        } finally { f.close() }
    }

    private class ScrollNative(override val textAvailable: Boolean = true) : PdfNativeRenderer {
        override val pageCount = 100
        val events = CopyOnWriteArrayList<String>()
        val bitmaps = CopyOnWriteArrayList<Bitmap>()
        val renderFailures = ConcurrentHashMap.newKeySet<Int>()
        val dimensionFailures = ConcurrentHashMap.newKeySet<Int>()
        @Volatile var beforeRender: (Int) -> Unit = {}
        var markResumeLines = false
        private var active = false
        private var closed = false
        override fun openPage(pageIndex: Int): PdfNativePage {
            check(!active && !closed); active = true; events += "open:$pageIndex"
            return object : PdfNativePage {
                override val width: Int get() { if (pageIndex in dimensionFailures) throw IOException("Controlled dimensions"); return 200 }
                override val height get() = when (pageIndex) { 1, 99 -> 50; 2 -> 1200; else -> 400 }
                override fun render(bitmap: Bitmap) {
                    events += "render:$pageIndex"; bitmaps += bitmap; beforeRender(pageIndex)
                    if (pageIndex in renderFailures) throw IOException("Controlled raster")
                    bitmap.eraseColor(when (pageIndex) { 0 -> Color.RED; 1 -> Color.BLUE; 2 -> Color.GREEN; else -> Color.MAGENTA })
                    if (markResumeLines && pageIndex == 2) {
                        val canvas = Canvas(bitmap)
                        val paint = Paint().apply { color = Color.BLACK }
                        for (fraction in listOf(.1f, .2f, .6f)) canvas.drawRect(0f,
                            bitmap.height * (fraction - .003f), bitmap.width.toFloat(), bitmap.height * (fraction + .003f), paint)
                    }
                }
                override fun selectText(start: PdfTextPoint, stop: PdfTextPoint): PdfSelectedText = PdfSelectedText(pageIndex,
                    "Native page $pageIndex", PdfSourceAnchor(pageIndex, width, height, nativePdfPoint(start, width, height),
                        nativePdfPoint(stop, width, height), listOf(PdfTextBounds(10.0, 10.0, 30.0, 20.0))))
                override fun close() { check(active); active = false; events += "page-close:$pageIndex" }
            }
        }
        override fun close() { check(!active && !closed); closed = true; events += "document-close" }
    }
}
