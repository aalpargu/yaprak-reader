package com.yaprak.reader.feature

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.fragment.app.Fragment
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.feature.settings.VolumeKeyViewModel
import com.yaprak.reader.feature.settings.TapZoneViewModel
import com.yaprak.reader.reader.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Actual Activity dispatch/lifecycle with controlled engine seams; no physical reader claims. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
class ReaderVolumeKeyActivityTest {
    private class Repository : VolumeKeySettingsRepository {
        val values = MutableStateFlow(false)
        override val volumePageTurning = values
        override suspend fun saveVolumePageTurning(enabled: Boolean) { values.value = enabled }
    }
    private class TapRepository : TapZoneSettingsRepository {
        private val values = MutableStateFlow(TapZoneSettings())
        var failRead = false
        override val tapZones = flow { if (failRead) throw java.io.IOException(); emitAll(values) }
        override suspend fun saveTapZones(settings: TapZoneSettings) { values.value = settings }
    }
    private class Progress : ReadingStateRepository {
        val records = mutableListOf<ReadingSnapshot>()
        override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
        override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) { records += snapshot }
    }
    private class Surface(pdf: Boolean = false, comic: Boolean = false) : ReaderSurface {
        override val fragment = Fragment()
        var tapHandler: ((ReaderTap) -> Boolean)? = null
        override val taps = ReaderTapController { tapHandler = it }
        override val comicSpread = if (comic) object : ComicSpreadController {
            override val preferred = MutableStateFlow(false)
            override fun apply(enabled: Boolean): Boolean = error("No comic setting edit in input fixture")
            override fun restore(enabled: Boolean): Boolean { preferred.value = enabled; return true }
        } else null
        fun tap(x: Float) = checkNotNull(tapHandler)(ReaderTap(x, .5f))
        val pdfCommands = mutableListOf<String>()
        override val pdfCrop = if (pdf) PdfCropController { pdfCommands += "crop" } else null
        override val pdfSpread = if (pdf) PdfSpreadController { pdfCommands += "spread" } else null
        override val pdfScroll = if (pdf) ReaderTestPdfScrollController() else null
        override val pdfView = if (pdf) object : PdfViewController {
            override fun fitPage() { pdfCommands += "fit" }
            override fun fitWidth() { pdfCommands += "fit" }
        } else null
        val visible = MutableStateFlow(false)
        override val readingVisible = visible
        var selection = false
        override val textSelection = object : TextSelectionSource {
            override val active get() = selection
            override suspend fun capture(): SelectedText? = null
        }
        var forwards = 0; var backwards = 0; var observations = 0
        var accepts = true
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
            observations++; awaitCancellation()
        }
        override fun forward(): Boolean { forwards++; return accepts }
        override fun backward(): Boolean { backwards++; return accepts }
    }
    private class Session(format: BookFormat) : ReaderSession {
        override var book = Book("volume-book", "Volume controls", null, format, "book", "book", 1, "a".repeat(64), 1)
        override val resume = ReadingLocation("{\"href\":\"chapter.xhtml\"}", "chapter.xhtml")
        val surfaces = mutableListOf<Surface>()
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit) =
            Surface(book.format == BookFormat.PDF, book.format == BookFormat.CBZ).also { surfaces += it }
        override fun close() = Unit
    }
    private class MainDispatcher : CoroutineDispatcher() {
        private val handler = Handler(Looper.getMainLooper())
        override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() !== handler.looper
        override fun dispatch(context: CoroutineContext, block: Runnable) { check(handler.post(block)) }
    }
    private class PdfPreferences : BookPdfViewPreferencesRepository {
        override fun observe(bookId: String, contentSha256: String) = flowOf(BookPdfViewPreferences())
        override suspend fun saveCrop(bookId: String, contentSha256: String, horizontalPercent: Int, verticalPercent: Int) = error("No write")
        override suspend fun saveFit(bookId: String, contentSha256: String, fitMode: PdfFitPreference) = error("No write")
        override suspend fun saveSpread(bookId: String, contentSha256: String, doublePagePreferred: Boolean) = error("No write")
        override suspend fun saveNavigation(bookId: String, contentSha256: String, mode: com.yaprak.reader.domain.settings.PdfNavigationMode) = error("No write")
    }
    private class ComicPreferences : BookComicViewPreferencesRepository {
        override fun observe(bookId: String, contentSha256: String) = flowOf(BookComicViewPreferences())
        override suspend fun saveSpread(bookId: String, contentSha256: String, doublePagePreferred: Boolean) = error("No write")
    }
    @Test fun customTapMappingsRouteBothFormatsAndShowOrHideToolsWithoutSyntheticProgress() = runTest {
        for (format in BookFormat.entries) withHost(format) { activity, session, _, progress, _ ->
            val surface = session.surfaces.last()
            val zones = activity.javaClass.getDeclaredMethod("getTapZones").apply { isAccessible = true }
                .invoke(activity) as TapZoneViewModel
            surface.visible.value = true; tick(); flag(activity, "chrome", false)
            assertFalse(surface.tap(.1f)); assertEquals(0, surface.backwards)
            zones.open(); zones.edit(TapZoneSettings(true, TapZoneAction.NEXT, TapZoneAction.MENU, TapZoneAction.PREVIOUS))
            zones.save(); tick()
            assertTrue(surface.tap(.1f)); assertEquals(1, surface.forwards)
            assertTrue(surface.tap(.9f)); assertEquals(1, surface.backwards)
            assertTrue(surface.tap(.5f)); assertTrue(getFlag(activity, "chrome"))
            assertTrue(surface.tap(.9f)); assertFalse(getFlag(activity, "chrome"))
            assertEquals(1, surface.backwards)
            zones.open(); zones.edit(TapZoneSettings(true, TapZoneAction.NONE, TapZoneAction.MENU, TapZoneAction.NEXT))
            zones.save(); tick()
            assertTrue(surface.tap(.1f)); assertEquals(1, surface.forwards)
            surface.accepts = false; assertTrue(surface.tap(.9f)); assertEquals(2, surface.forwards)
            assertTrue(surface.tap(Float.NaN)); assertEquals(2, surface.forwards)
            assertTrue(progress.records.isEmpty())
        }
    }
    @Test fun enabledTapCannotFallThroughWhenSelectionModalVisibilityOrFocusBlocksIt() = runTest {
        withHost { activity, session, keys, progress, host ->
            val surface = session.surfaces.last()
            val zones = activity.javaClass.getDeclaredMethod("getTapZones").apply { isAccessible = true }
                .invoke(activity) as TapZoneViewModel
            zones.open(); zones.edit(TapZoneSettings(true)); zones.save(); tick()
            surface.visible.value = true; tick(); flag(activity, "chrome", false)
            for (name in listOf("contentsOpen", "bookSearchOpen", "brightnessOpen", "loadFailed", "saveFailed",
                "renderingFailed", "orientationApplyFailed", "pdfCropApplyFailed", "selectionExternalLaunch",
                "readerImeVisible", "readerTouchActive")) {
                flag(activity, name, true); assertTrue(surface.tap(.9f)); assertEquals(name, 0, surface.forwards)
                flag(activity, name, false)
            }
            surface.selection = true; assertTrue(surface.tap(.9f)); assertEquals(0, surface.forwards)
            surface.selection = false; zones.open(); tick()
            assertTrue(surface.tap(.9f)); assertEquals(0, surface.forwards)
            invoke(activity, "openBrightness"); assertFalse(getFlag(activity, "brightnessOpen"))
            keys.open(); keys.edit(true); keys.save(); tick()
            assertFalse(send(activity, down = 987))
            zones.dismiss(); tick(); surface.visible.value = false; tick()
            assertTrue(surface.tap(.9f)); assertEquals(0, surface.forwards)
            surface.visible.value = true; tick()
            host.windowFocusChanged(false); assertTrue(surface.tap(.9f)); assertEquals(0, surface.forwards)
            host.windowFocusChanged(true); tick(); assertTrue(surface.tap(.9f)); assertEquals(1, surface.forwards)
            val repository = zones.javaClass.getDeclaredField("repository").apply { isAccessible = true }
                .get(zones) as TapRepository
            repository.failRead = true; zones.reload(); tick()
            assertTrue(zones.state.value.loadFailed); assertTrue(getFlag(activity, "chrome"))
            flag(activity, "chrome", false); assertTrue(surface.tap(.9f))
            assertTrue(getFlag(activity, "chrome")); assertEquals(1, surface.forwards)
            invoke(activity, "openTapZones"); tick(); assertTrue(zones.state.value.open)
            repository.failRead = false; zones.reload(); tick(); assertFalse(zones.state.value.loadFailed)
            zones.dismiss(); tick(); flag(activity, "chrome", false)
            assertTrue(surface.tap(.9f)); assertEquals(2, surface.forwards)
            assertTrue(progress.records.isEmpty())
        }
    }
    @Test fun realDispatchRoutesBothFormatsOncePerPressKeepsChromeHiddenAndNeverInventsProgress() = runTest {
        for (format in BookFormat.entries) withHost(format) { activity, session, keys, progress, host ->
            val surface = session.surfaces.last()
            keys.open(); keys.edit(true); keys.save(); tick()
            flag(activity, "chrome", false)
            send(activity, down = 10); assertEquals(0, surface.forwards)
            surface.visible.value = true; tick()
            assertTrue("Volume down for $format", send(activity, down = 20)); assertTrue(send(activity, down = 20, repeat = 1))
            assertTrue(send(activity, down = 20, action = KeyEvent.ACTION_UP))
            assertEquals(1, surface.forwards); assertEquals(0, surface.backwards)
            assertTrue(send(activity, code = KeyEvent.KEYCODE_VOLUME_UP, down = 30))
            send(activity, code = KeyEvent.KEYCODE_VOLUME_UP, down = 30, action = KeyEvent.ACTION_UP)
            assertEquals(1, surface.backwards); assertTrue(surface.observations >= 3)
            assertFalse(getFlag(activity, "chrome"))
            surface.accepts = false
            assertTrue(send(activity, down = 40)); assertEquals(2, surface.forwards)
            assertTrue(progress.records.isEmpty())
        }
    }
    @Test fun modalErrorsTextSelectionImeAndTouchCannotTurnHiddenReader() = runTest {
        withHost { activity, session, keys, progress, host ->
            keys.open(); keys.edit(true); keys.save(); tick()
            val surface = session.surfaces.last(); surface.visible.value = true; tick(); flag(activity, "chrome", false)
            var down = 100L
            for (name in listOf("chrome", "contentsOpen", "bookSearchOpen", "bookmarksOpen", "savedWordsOpen",
                "bookNotesOpen", "passagesOpen", "pageJumpOpen", "brightnessOpen", "epubImagesOpen",
                "bookAppearanceOpen", "resetPending", "resetRefreshFailed", "loadFailed", "saveFailed",
                "renderingFailed", "orientationApplyFailed", "pdfCropApplyFailed", "selectionExternalLaunch",
                "readerImeVisible", "readerTouchActive")) {
                flag(activity, name, true); send(activity, down = down++)
                assertEquals("Blocked by $name", 0, surface.forwards)
                flag(activity, name, false)
            }
            val durationError = activity.javaClass.getDeclaredMethod("setDurationFailure",
                com.yaprak.reader.domain.statistics.ReadingDurationFailure::class.java).apply { isAccessible = true }
            durationError.invoke(activity, com.yaprak.reader.domain.statistics.ReadingDurationFailure.SOURCE)
            send(activity, down = down++); assertEquals(0, surface.forwards)
            durationError.invoke(activity, null)
            surface.selection = true; send(activity, down = down++); assertEquals(0, surface.forwards)
            surface.selection = false
            keys.open(); tick(); send(activity, down = down++); assertEquals(0, surface.forwards)
            invoke(activity, "openBrightness"); assertFalse(getFlag(activity, "brightnessOpen"))
            invoke(activity, "openBookSearch"); assertFalse(getFlag(activity, "bookSearchOpen"))
            keys.dismiss(); tick(); assertTrue(send(activity, down = down)); assertEquals(1, surface.forwards)
            assertTrue(progress.records.isEmpty())
        }
    }
    @Test fun disabledKeysDelegateToActualFocusedViewAndOwnedReleaseSurvivesEditorOpening() = runTest {
        withHost { activity, session, keys, _, _ ->
            var delegated = 0; var editor = false
            val view = object : android.view.View(activity) {
                override fun onCheckIsTextEditor() = editor
                override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean { delegated++; return true }
            }.apply { isFocusableInTouchMode = true }
            activity.findViewById<android.widget.FrameLayout>(android.R.id.content).addView(view)
            view.requestFocus(); tick()
            flag(activity, "chrome", false); session.surfaces.last().visible.value = true; tick()
            assertTrue(send(activity, down = 100)); assertEquals(1, delegated)
            keys.open(); keys.edit(true); keys.save(); tick()
            assertTrue(send(activity, down = 200)); assertEquals(1, delegated)
            keys.open(); tick()
            assertTrue(send(activity, down = 200, action = KeyEvent.ACTION_UP))
            // Late repeats after release must delegate, without turning again.
            send(activity, down = 200, repeat = 2)
            assertEquals(1, session.surfaces.last().forwards); assertEquals(2, delegated)
            send(activity, code = KeyEvent.KEYCODE_A, down = 300); assertEquals(3, delegated)
            keys.dismiss(); tick(); view.requestFocus(); editor = true
            assertSame(view, activity.window.decorView.findFocus()); assertTrue(view.onCheckIsTextEditor())
            send(activity, down = 400); assertEquals(4, delegated); assertEquals(1, session.surfaces.last().forwards)
            editor = false
            assertTrue(send(activity, down = 500)); assertEquals(2, session.surfaces.last().forwards)
        }
    }
    @Test fun lostFocusPausedLifecycleAndChangedBookIdentityRejectKeysAndDropOwnedRepeats() = runTest {
        withHost { activity, session, keys, progress, host ->
            keys.open(); keys.edit(true); keys.save(); tick()
            val surface = session.surfaces.last(); surface.visible.value = true; tick(); flag(activity, "chrome", false)
            assertTrue(send(activity, down = 10))
            activity.onWindowFocusChanged(false); send(activity, down = 20); send(activity, down = 10, repeat = 1)
            assertEquals(1, surface.forwards); activity.onWindowFocusChanged(true)
            val original = session.book; session.book = original.copy(sha256 = "b".repeat(64))
            send(activity, down = 30); assertEquals(1, surface.forwards); session.book = original
            session.book = original.copy(title = "Changed"); send(activity, down = 40); assertEquals(1, surface.forwards)
            session.book = original
            host.pause(); send(activity, down = 50); send(activity, down = 10, repeat = 2); assertEquals(1, surface.forwards)
            host.resume().windowFocusChanged(true); tick()
            assertTrue(progress.records.isEmpty())
        }
    }
    @Test fun actualTouchAndNativeActionModeCallbacksProtectSelectionUntilTheyFinish() = runTest {
        withHost { activity, session, keys, _, _ ->
            keys.open(); keys.edit(true); keys.save(); tick()
            val surface = session.surfaces.last(); surface.visible.value = true; tick(); flag(activity, "chrome", false)
            val view = android.view.View(activity).apply {
                isFocusableInTouchMode = true
                setOnTouchListener { _, _ -> true }
            }
            activity.findViewById<android.widget.FrameLayout>(android.R.id.content).addView(view)
            view.requestFocus(); tick()
            fun touch(action: Int) {
                val event = android.view.MotionEvent.obtain(10, 10, action, 80f, 450f, 0)
                try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            touch(android.view.MotionEvent.ACTION_DOWN)
            send(activity, down = 100); assertEquals(0, surface.forwards)
            touch(android.view.MotionEvent.ACTION_CANCEL)
            assertTrue(send(activity, down = 200)); assertEquals(1, surface.forwards)
            val callback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                    menu.add("Copy"); return true
                }
                override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = true
                override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = false
                override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
            }
            val mode = requireNotNull(activity.startActionMode(callback, android.view.ActionMode.TYPE_FLOATING))
            send(activity, down = 300); assertEquals(1, surface.forwards)
            mode.finish(); tick(); activity.onWindowFocusChanged(true)
            assertTrue(send(activity, down = 400)); assertEquals(2, surface.forwards)
        }
    }

    private suspend fun withHost(format: BookFormat = BookFormat.EPUB,
        block: suspend (ReaderActivity, Session, VolumeKeyViewModel, Progress, org.robolectric.android.controller.ActivityController<ReaderActivity>) -> Unit) {
        Dispatchers.setMain(MainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            val session = Session(format); val progress = Progress()
            val reader = ReaderViewModel(session.book.id, ReaderLoader { session }, progress)
            val keys = VolumeKeyViewModel(Repository())
            host.create()
            fun install(vm: androidx.lifecycle.ViewModel) {
                host.get().viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + vm.javaClass.canonicalName, vm)
            }
            install(reader); install(keys); install(TapZoneViewModel(TapRepository())); install(PdfBookViewPreferencesViewModel(PdfPreferences()))
            install(ComicBookViewPreferencesViewModel(ComicPreferences()))
            host.start().resume().visible().windowFocusChanged(true)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            val field = ReaderActivity::class.java.getDeclaredField("surface").apply { isAccessible = true }
            while (field.get(host.get()) == null ||
                (host.get().application as YaprakApplication).container.startup.state.value != com.yaprak.reader.core.StartupState.Ready) {
                tick(); check(System.nanoTime() < deadline) { "Reader/startup not ready" }; Thread.yield()
            }
            tick(); block(host.get(), session, keys, progress, host)
        } finally {
            val activity = host.get()
            val original = activity.javaClass.getDeclaredField("originalKeyWindowCallback").apply { isAccessible = true }.get(activity)
            host.pause().stop().destroy(); tick()
            assertSame(original, activity.window.callback)
            Dispatchers.resetMain()
        }
    }
    private companion object {
        fun tick() { ShadowLooper.idleMainLooper() }
        fun send(activity: ReaderActivity, code: Int = KeyEvent.KEYCODE_VOLUME_DOWN, down: Long,
            action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
            activity.window.callback.dispatchKeyEvent(KeyEvent(down, down + repeat, action, code, repeat))
        fun flag(activity: ReaderActivity, name: String, value: Boolean) {
            val setter = "set" + name.replaceFirstChar { it.uppercase() }
            val method = activity.javaClass.declaredMethods.firstOrNull { it.name == setter }
            if (method != null) method.apply { isAccessible = true }.invoke(activity, value)
            else activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.setBoolean(activity, value)
        }
        fun getFlag(activity: ReaderActivity, name: String) =
            activity.javaClass.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() })
                .apply { isAccessible = true }.invoke(activity) as Boolean
        fun invoke(activity: ReaderActivity, name: String) =
            activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }
}
