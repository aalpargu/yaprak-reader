package com.yaprak.reader.feature

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.statistics.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Real Activity/native Fragment lifecycle; controlled visibility/clock/storage boundaries.
 * This does not claim EPUB or PDF native pixel acceptance or inferred attention.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
class ReadingDurationActivityTest {
    private class Clock : ReadingDurationClock {
        var elapsed = 0L
        private val started = Instant.parse("2026-10-05T09:00:00Z").toEpochMilli()
        override fun now() = DurationMoment(elapsed, started + elapsed, "UTC")
    }
    private class Storage : ReadingDurationRepository {
        val receipts = LinkedHashMap<String, ReadingDurationReceipt>()
        var save: suspend (ReadingDurationReceipt) -> Unit = {}
        override fun observe() = flowOf(ReadingDurationSnapshot(emptyList(), emptyList(), 0))
        override suspend fun record(receipt: ReadingDurationReceipt) {
            save(receipt); validateReadingDurationReceipt(receipt)
            val old = receipts[receipt.id]
            if (old == null || receipt.elapsedMillis >= old.elapsedMillis) receipts[receipt.id] = receipt
        }
        val total get() = receipts.values.sumOf { it.elapsedMillis }
    }
    private class Progress : ReadingStateRepository {
        val records = mutableListOf<ReadingSnapshot>()
        override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
        override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) { records += snapshot }
    }
    private class Surface : ReaderSurface {
        override val fragment = Fragment()
        override val renderingFailed = MutableStateFlow(false)
        val visible = MutableStateFlow(false)
        var visibilitySubscriptions = 0
        override val readingVisible = flow { visibilitySubscriptions++; visible.collect { emit(it) } }
        var observations = 0
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
            observations++; awaitCancellation()
        }
        override fun forward() = true
        override fun backward() = true
    }
    private class Session : ReaderSession {
        override var book = Book("duration-book", "Foreground duration", null, BookFormat.EPUB,
            "duration.epub", "duration.epub", 1, "a".repeat(64), 1)
        override val resume = ReadingLocation("{\"href\":\"chapter.xhtml\"}", "chapter.xhtml")
        val surfaces = mutableListOf<Surface>()
        val initials = mutableListOf<ReadingLocation?>()
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface {
            initials += initial; return Surface().also { surfaces += it }
        }
        override fun close() = Unit
    }
    private class AndroidMainDispatcher : CoroutineDispatcher() {
        private val handler = Handler(Looper.getMainLooper())
        override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() !== handler.looper
        override fun dispatch(context: CoroutineContext, block: Runnable) { check(handler.post(block)) }
    }
    private class Fixture(val host: ActivityController<ReaderActivity>, val session: Session,
        val storage: Storage, val progress: Progress, val reader: ReaderViewModel, val clock: Clock) {
        val activity get() = host.get()
        val surface get() = session.surfaces.last()
        fun focus(value: Boolean) { activity.onWindowFocusChanged(value); tick() }
        fun hideTools() { flag(activity, "setChrome", false); invoke(activity, "updateDuration"); tick() }
        fun refresh() { invoke(activity, "updateDuration"); tick() }
        fun advance(millis: Long) { clock.elapsed += millis }
        fun enable() { focus(true); surface.visible.value = true; tick(); hideTools() }
    }

    @Test fun `successful external text launch stops timer before asynchronous Activity pause`() = runTest {
        withHost {
            enable(); advance(1000)
            org.robolectric.Shadows.shadowOf(activity.packageManager).addResolveInfoForIntent(
                android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH), android.content.pm.ResolveInfo().apply {
                    activityInfo = android.content.pm.ActivityInfo().apply {
                        packageName = "web.fixture"; name = "web.fixture.Search"; exported = true; enabled = true
                        applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "web.fixture"; enabled = true }
                    }
                })
            val preview = SelectedTextPreview.Ready("Explicit selection")
            activity.javaClass.getDeclaredMethod("setSelectionPreview", SelectedTextPreview::class.java)
                .apply { isAccessible = true }.invoke(activity, preview)
            activity.javaClass.getDeclaredField("selectionSurface").apply { isAccessible = true }.set(activity, surface)
            activity.javaClass.getDeclaredField("selectionBookIdentity").apply { isAccessible = true }
                .set(activity, session.book.id to session.book.sha256)
            activity.javaClass.getDeclaredMethod("setSelectionTools", SelectedTextToolsState::class.java)
                .apply { isAccessible = true }.invoke(activity, SelectedTextToolsState(preview.sessionId, loading = false))
            refresh(); assertEquals(1000L, storage.total)
            activity.javaClass.getDeclaredMethod("launchSelectionTool", SelectedTextTool::class.java)
                .apply { isAccessible = true }.invoke(activity, null)
            tick(); advance(2000); refresh()
            assertEquals(1000L, storage.total); assertTrue(progress.records.isEmpty())
            host.pause().resume(); tick(); focus(true)
            advance(500); focus(false); assertEquals(1500L, storage.total)
        }
    }

    @Test fun `brightness gesture preview excludes drag time and commit resumes without locator subscription or progress`() = runTest {
        withHost {
            enable(); val observations = surface.observations; advance(1000)
            val previewSetter = activity.javaClass.getDeclaredMethod("setBrightnessGesturePreview", Int::class.javaObjectType).apply { isAccessible = true }
            previewSetter.invoke(activity, 60); refresh(); assertEquals(1000L, storage.total)
            advance(2000); refresh(); assertEquals(1000L, storage.total)
            previewSetter.invoke(activity, null); refresh(); advance(500); focus(false)
            assertEquals(1500L, storage.total); assertEquals(observations, surface.observations); assertTrue(progress.records.isEmpty())
        }
    }

    @Test fun `duration needs native visibility focus and hidden tools and never writes reading progress or restarts observer`() = runTest {
        withHost {
            val subscriptions = surface.observations
            focus(true); hideTools(); advance(1000); focus(false)
            assertEquals(0L, storage.total)
            surface.visible.value = true; tick(); advance(1000); refresh()
            assertEquals(0L, storage.total)
            focus(true); advance(1500); focus(false)
            assertEquals(1500L, storage.total)
            assertEquals(subscriptions, surface.observations); assertEquals(1, surface.visibilitySubscriptions)
            assertEquals(session.resume, reader.latestLocation); assertTrue(progress.records.isEmpty())
        }
    }

    @Test fun `modal chrome rendering save open crop or indexed banner gates stop duration at the last real interval`() = runTest {
        for (gate in listOf("modal", "chrome", "render", "save", "open", "crop", "indexed")) {
            withHost {
                enable(); val subscriptions = surface.observations
                advance(1000)
                when (gate) {
                    "modal" -> flag(activity, "setContentsOpen", true)
                    "chrome" -> flag(activity, "setChrome", true)
                    "render" -> surface.renderingFailed.value = true
                    "save" -> {
                        @Suppress("UNCHECKED_CAST")
                        val failed = ReaderViewModel::class.java.getDeclaredField("_saveFailed")
                            .apply { isAccessible = true }.get(reader) as MutableStateFlow<Boolean>
                        failed.value = true
                    }
                    "open" -> flag(activity, "setLoadFailed", true)
                    "crop" -> flag(activity, "setPdfCropApplyFailed", true)
                    "indexed" -> activity.javaClass.getDeclaredMethod("setIndexedChapterState", IndexedChapterState::class.java)
                        .apply { isAccessible = true }.invoke(activity, IndexedChapterState(phase = IndexedChapterPhase.FAILED,
                            failure = IndexedChapterFailure.SOURCE))
                }
                refresh(); assertEquals(1000L, storage.total)
                advance(3000); refresh(); assertEquals(1000L, storage.total)
                assertEquals(subscriptions, surface.observations); assertTrue(progress.records.isEmpty())
            }
        }
    }

    @Test fun `lost native visibility pauses measurement and legitimate recovery resumes without duplicate locator subscription`() = runTest {
        withHost {
            enable(); val subscriptions = surface.observations
            advance(1250); surface.visible.value = false; tick(); assertEquals(1250L, storage.total)
            advance(4000); surface.visible.value = true; tick()
            advance(750); focus(false); assertEquals(2000L, storage.total)
            assertEquals(subscriptions, surface.observations); assertTrue(progress.records.isEmpty())
        }
    }

    @Test fun `pause and stop settle synchronously and background callbacks cannot reopen an interval`() = runTest {
        withHost {
            enable(); advance(900); host.pause(); tick(); assertEquals(900L, storage.total)
            advance(4000); surface.visible.value = false; surface.visible.value = true; tick(); refresh()
            assertEquals(900L, storage.total)
            host.stop(); tick(); advance(4000); refresh(); assertEquals(900L, storage.total)
            host.start().resume(); tick(); focus(true); hideTools(); surface.visible.value = true; tick()
            advance(600); focus(false); assertEquals(1500L, storage.total)
            assertTrue(progress.records.isEmpty())
        }
    }

    @Test fun `recreation hands owner to new surface and old visibility callback cannot credit hidden new document`() = runTest {
        withHost {
            enable(); advance(1100); val old = surface
            host.recreate(); awaitSurface(activity, session, 2); tick()
            assertEquals(1100L, storage.total); assertEquals(session.resume, session.initials.last())
            focus(true); hideTools(); advance(500)
            old.visible.value = false; old.visible.value = true; tick(); refresh(); assertEquals(1100L, storage.total)
            surface.visible.value = true; tick(); advance(900); focus(false)
            assertEquals(2000L, storage.total); assertEquals(1, surface.visibilitySubscriptions)
            assertTrue(progress.records.isEmpty())
        }
    }

    @Test fun `changed bound full book hash id or surface cannot credit a new identity from an old visible document`() = runTest {
        for (change in listOf("title", "hash", "id", "surface")) {
            withHost {
                enable(); advance(1000)
                when (change) {
                    "title" -> session.book = session.book.copy(title = "Changed")
                    "hash" -> session.book = session.book.copy(sha256 = "b".repeat(64))
                    "id" -> session.book = session.book.copy(id = "other")
                    "surface" -> activity.javaClass.getDeclaredField("surface").apply { isAccessible = true }.set(activity, Surface())
                }
                refresh(); assertEquals(1000L, storage.total)
                advance(1000); refresh(); assertEquals(1000L, storage.total)
                assertTrue(storage.receipts.values.all { it.bookId == "duration-book" && it.contentSha256 == "a".repeat(64) })
                assertTrue(progress.records.isEmpty())
            }
        }
    }

    @Test fun `normal library exit waits for durable duration checkpoint without inventing a locator record`() = runTest {
        withHost {
            val persisted = CompletableDeferred<Unit>(); storage.save = { persisted.await() }
            enable(); advance(1000); invoke(activity, "leave"); tick()
            assertFalse(activity.isFinishing); assertEquals(0L, storage.total)
            advance(4000); surface.visible.value = true; refresh(); assertEquals(0L, storage.total)
            persisted.complete(Unit); tick()
            assertTrue(activity.isFinishing); assertEquals(1000L, storage.total)
            assertTrue(progress.records.isEmpty())
        }
    }

    @Test fun `duration storage failure keeps reader open and explicit retry persists only measured elapsed`() = runTest {
        withHost {
            storage.save = { throw ReadingDurationException(ReadingDurationFailure.SOURCE) }
            enable(); advance(1000); invoke(activity, "leave"); tick()
            assertFalse(activity.isFinishing); assertEquals(ReadingDurationFailure.SOURCE, reader.durationFailure.value)
            assertEquals(0L, storage.total); advance(4000); refresh()
            storage.save = {}; reader.retryDuration(); tick()
            assertNull(reader.durationFailure.value); assertEquals(1000L, storage.total)
            invoke(activity, "leave"); tick(); assertTrue(activity.isFinishing)
            assertEquals(1000L, storage.total); assertTrue(progress.records.isEmpty())
        }
    }

    private suspend fun TestScope.withHost(test: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(AndroidMainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(); val storage = Storage(); val progress = Progress(); val clock = Clock()
            val reader = ReaderViewModel(session.book.id, ReaderLoader { session }, progress, storage)
            // Inject only the clock at the controller boundary before any native owner can measure.
            val duration = ReaderViewModel::class.java.getDeclaredField("duration").apply { isAccessible = true }.get(reader)
            ReaderDurationController::class.java.getDeclaredField("clock").apply { isAccessible = true }.set(duration, clock)
            host.create(); host.get().viewModelStore.put(
                "androidx.lifecycle.ViewModelProvider.DefaultKey:${ReaderViewModel::class.java.canonicalName}", reader)
            host.start().resume(); awaitSurface(host.get(), session)
            Fixture(host, session, storage, progress, reader, clock).test()
        } finally { host.pause().stop().destroy(); tick(); Dispatchers.resetMain() }
    }
    private companion object {
        fun tick() { ShadowLooper.idleMainLooper() }
        fun flag(activity: ReaderActivity, setter: String, value: Boolean) {
            activity.javaClass.getDeclaredMethod(setter, Boolean::class.javaPrimitiveType!!)
                .apply { isAccessible = true }.invoke(activity, value)
        }
        fun invoke(activity: ReaderActivity, method: String) {
            activity.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
        }
        fun awaitSurface(activity: ReaderActivity, session: Session, count: Int = 1) {
            val field = activity.javaClass.getDeclaredField("surface").apply { isAccessible = true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (field.get(activity) == null || session.surfaces.size < count) {
                tick(); check(System.nanoTime() < deadline) { "Reader did not install its actual surface" }; Thread.yield()
            }
            assertEquals(count, session.surfaces.size); assertSame(session.surfaces.last(), field.get(activity)); tick()
        }
    }
}
