package com.yaprak.reader.feature

import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.domain.settings.ReaderOrientation
import com.yaprak.reader.domain.settings.ReaderOrientationSettingsRepository
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.PdfBookViewPreferencesViewModel
import com.yaprak.reader.feature.reader.ReaderViewModel
import com.yaprak.reader.feature.settings.ReaderOrientationViewModel
import com.yaprak.reader.reader.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Actual Activity lifecycle and gates with a controlled valid reader boundary, not a renderer/device test.
 * Main runs eagerly like Android Main.immediate. ActivityController.recreate internally calls visible()
 * and drains Robolectric's main Looper; deferring Ready to a separate test scheduler leaves the loading
 * Compose animation producing frames while that drain waits. Native dialog tests cover visual layout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
class ReaderOrientationActivityTest {
    // Main.immediate must post IO completions to Android Main, never resume FragmentManager on IO.
    private class AndroidMainDispatcher : CoroutineDispatcher() {
        private val handler = Handler(Looper.getMainLooper())
        override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() !== handler.looper
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            check(handler.post(block)) { "Main Looper rejected test continuation" }
        }
    }
    private class Preferences : ReaderOrientationSettingsRepository {
        override val orientation = MutableStateFlow(ReaderOrientation.LANDSCAPE)
        val writes = mutableListOf<ReaderOrientation>()
        override suspend fun saveOrientation(orientation: ReaderOrientation) { writes += orientation; this.orientation.value = orientation }
    }
    private class Storage : ReadingStateRepository {
        val recorded = mutableListOf<ReadingSnapshot>()
        override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
        override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) { recorded += snapshot }
    }
    private class Surface(val tap: () -> Unit) : ReaderSurface {
        override val fragment = Fragment()
        override val renderingFailed = MutableStateFlow(false)
        var swipeAllowed: (() -> Boolean)? = null
        var coverageAllowed: (() -> Boolean)? = null
        var fits = 0
        var forwardCalls = 0
        val selection = CompletableDeferred<SelectedText?>()
        override val textSelection = TextSelectionSource { selection.await() }
        override val pdfSwipe = PdfSwipeController { swipeAllowed = it }
        override val pdfCrop = PdfCropController { }
        override val pdfSpread = PdfSpreadController { }
        override val pdfScroll = ReaderTestPdfScrollController()
        override val pdfView = object : PdfViewController {
            override fun fitPage() { fits++ }
            override fun fitWidth() { fits++ }
        }
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
            this.coverageAllowed = coverageAllowed
            awaitCancellation()
        }
        override fun forward(): Boolean { forwardCalls++; return true }
        override fun backward() = true
    }
    private class Session : ReaderSession {
        override val book = Book("orientation-book", "Reader integration", null, BookFormat.PDF,
            "orientation.pdf", "orientation.pdf", 1, "a".repeat(64), 1, pageCount = 12)
        override val resume = ReadingLocation("{\"page\":2}", "pdf")
        val initials = mutableListOf<ReadingLocation?>()
        val surfaces = mutableListOf<Surface>()
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface {
            initials += initial
            return Surface(onTap).also { surfaces += it }
        }
        override fun close() = Unit
    }

    @Test fun `real Activity modal blocks PDF gesture fit navigation and late selection while cancel preserves saved orientation`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val preferences = Preferences(); val storage = Storage()
            val reader = ReaderViewModel(session.book.id, ReaderLoader { session }, storage)
            val orientation = ReaderOrientationViewModel(preferences)
            host.create()
            install(host.get(), reader, orientation)
            host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            assertEquals(session.resume, session.initials.single())
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, host.get().requestedOrientation)
            val surface = session.surfaces.single()
            surface.tap(); runCurrent()
            assertTrue(surface.swipeAllowed!!.invoke())
            assertTrue(surface.coverageAllowed!!.invoke())
            invoke(host.get(), "captureSelectedText"); runCurrent()
            invoke(host.get(), "openOrientation"); runCurrent()
            assertFalse(orientation.state.value.open)
            assertTrue(property(host.get(), "getSelectionPreview") is com.yaprak.reader.feature.reader.SelectedTextPreview.Loading)
            invoke(host.get(), "clearSelectionPreview"); invoke(host.get(), "observeSurface"); runCurrent()
            invoke(host.get(), "openOrientation"); runCurrent()
            assertTrue(orientation.state.value.open)
            assertFalse(surface.swipeAllowed!!.invoke()); assertFalse(surface.coverageAllowed!!.invoke())
            invoke(host.get(), "fitPdf", Boolean::class.javaPrimitiveType!!, true)
            val navigation = host.get().javaClass.getDeclaredMethod("navigate", Function0::class.java).apply { isAccessible = true }
            assertFalse(navigation.invoke(host.get(), { surface.forward() }) as Boolean)
            assertEquals(1, surface.fits); assertEquals(0, surface.forwardCalls)
            surface.selection.complete(SelectedText("Late result")); runCurrent()
            assertNull(property(host.get(), "getSelectionPreview"))
            orientation.edit(ReaderOrientation.PORTRAIT); runCurrent()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, host.get().requestedOrientation)
            host.get().onBackPressedDispatcher.onBackPressed(); runCurrent()
            assertFalse(orientation.state.value.open)
            assertTrue(preferences.writes.isEmpty()); assertTrue(storage.recorded.isEmpty())
            assertEquals(ReaderOrientation.LANDSCAPE, orientation.state.value.saved)
        } finally { host.pause().stop().destroy(); ShadowLooper.idleMainLooper(); Dispatchers.resetMain() }
    }

    @Test fun `PDF render recovery applies waiting saved choice and recreated surface uses latest recorded location`() = runTest {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val preferences = Preferences(); val storage = Storage()
            val reader = ReaderViewModel(session.book.id, ReaderLoader { session }, storage)
            val orientation = ReaderOrientationViewModel(preferences)
            host.create(); install(host.get(), reader, orientation); host.start().resume(); awaitSurface(host.get(), session); runCurrent()
            val surface = session.surfaces.single()
            surface.renderingFailed.value = true; runCurrent()
            preferences.orientation.value = ReaderOrientation.PORTRAIT; runCurrent()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, host.get().requestedOrientation)
            surface.renderingFailed.value = false; runCurrent()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, host.get().requestedOrientation)
            val latest = ReadingLocation("{\"page\":8}", "pdf")
            reader.observe(ReadingSnapshot(latest)); runCurrent()
            assertEquals(latest, storage.recorded.single().location)
            val before = host.get()
            host.recreate(); awaitSurface(host.get(), session, 2); runCurrent()
            assertNotSame(before, host.get())
            assertEquals(latest, session.initials.last())
            assertEquals(2, session.initials.size)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, host.get().requestedOrientation)
            assertEquals(1, storage.recorded.size)
        } finally { host.pause().stop().destroy(); ShadowLooper.idleMainLooper(); Dispatchers.resetMain() }
    }

    private fun install(activity: ReaderActivity, reader: ReaderViewModel, orientation: ReaderOrientationViewModel) {
        activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${ReaderViewModel::class.java.canonicalName}", reader)
        activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${ReaderOrientationViewModel::class.java.canonicalName}", orientation)
        activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:${PdfBookViewPreferencesViewModel::class.java.canonicalName}",
            PdfBookViewPreferencesViewModel(ReaderTestPdfViewPreferences()))
        assertSame(reader, ViewModelProvider(activity)[ReaderViewModel::class.java])
    }

    private fun awaitSurface(activity: ReaderActivity, session: Session, expectedCount: Int = 1) {
        val field = activity.javaClass.getDeclaredField("surface").apply { isAccessible = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (field.get(activity) == null || session.surfaces.size < expectedCount) {
            ShadowLooper.idleMainLooper()
            check(System.nanoTime() < deadline) { "Reader did not install its expected surface on Android Main" }
            Thread.yield()
        }
        assertEquals(expectedCount, session.surfaces.size)
        assertSame(session.surfaces.last(), field.get(activity))
        ShadowLooper.idleMainLooper()
    }

    private fun invoke(activity: ReaderActivity, name: String) {
        activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }
    private fun invoke(activity: ReaderActivity, name: String, type: Class<*>, argument: Any) {
        activity.javaClass.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(activity, argument)
    }
    private fun property(activity: ReaderActivity, getter: String): Any? =
        activity.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(activity)
}
