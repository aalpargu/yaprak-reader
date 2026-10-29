package com.yaprak.reader.feature

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.core.StartupState
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.*
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.feature.settings.*
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Real Activity/AppContainer/Room alignment and recreation; rendering is a controlled engine seam. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
class BookTextAlignmentActivityTest {
    private class MainDispatcher : CoroutineDispatcher() {
        private val handler = Handler(Looper.getMainLooper())
        override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() !== handler.looper
        override fun dispatch(context: CoroutineContext, block: Runnable) { check(handler.post(block)) }
    }
    private class Settings : EpubSettingsRepository {
        override val settings = MutableStateFlow(EpubSettings(fontSizePercent = 145, theme = ReadingTheme.AMOLED,
            scroll = true, textAlignment = ReadingTextAlignment.JUSTIFY))
        var writes = 0
        override suspend fun save(settings: EpubSettings) { writes++; this.settings.value = settings }
    }
    private class Keys : VolumeKeySettingsRepository {
        override val volumePageTurning = flowOf(true)
        override suspend fun saveVolumePageTurning(enabled: Boolean) = error("No key preference write")
    }
    private class Progress : ReadingStateRepository {
        val writes = mutableListOf<ReadingSnapshot>()
        override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
        override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) { writes += snapshot }
    }
    private class Surface : ReaderSurface {
        override val fragment = Fragment()
        val submitted = mutableListOf<EpubSettings>()
        override val appearance = EpubAppearanceController { submitted += it }
        override val readingVisible = MutableStateFlow(true)
        var allowed: (() -> Boolean)? = null
        var forwards = 0
        override suspend fun observe(coverageAllowed: () -> Boolean, onSnapshot: (ReadingSnapshot) -> Unit) {
            allowed = coverageAllowed; awaitCancellation()
        }
        override fun forward(): Boolean { forwards++; return true }
        override fun backward() = true
    }
    private class Session(format: BookFormat) : ReaderSession {
        override val book = Book(format.name + "-alignment", "Alignment", null, format, "source", "source",
            1, (if (format == BookFormat.EPUB) "a" else "b").repeat(64), 1)
        override val resume: ReadingLocation? = null
        val surfaces = mutableListOf<Surface>()
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit) = Surface().also { surfaces += it }
        override fun close() = Unit
    }
    @Test fun actualBookAlignmentFactoryAppliesOnlySavedChoicesTracksInheritanceAndSurvivesActivityRecreation() = runTest {
        for (format in listOf(BookFormat.EPUB, BookFormat.TXT)) {
            Dispatchers.setMain(MainDispatcher())
            val host = Robolectric.buildActivity(ReaderActivity::class.java)
            try {
                host.create()
                val container = (host.get().application as YaprakApplication).container
                await { container.startup.state.value == StartupState.Ready }
                val session = Session(format); val progress = Progress(); val settings = Settings()
                container.library.add(session.book)
                fun install(vm: ViewModel) {
                    host.get().viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + vm.javaClass.canonicalName, vm)
                }
                install(ReaderViewModel(session.book.id, ReaderLoader { session }, progress))
                install(EpubSettingsViewModel(settings)); install(VolumeKeyViewModel(Keys()))
                host.start().resume().visible().windowFocusChanged(true)
                await { session.surfaces.lastOrNull()?.submitted?.isNotEmpty() == true }
                var activity = host.get()
                fun editor(): BookTextAlignmentViewModel = activity.javaClass.getDeclaredMethod("getBookTextAlignment")
                    .apply { isAccessible = true }.invoke(activity) as BookTextAlignmentViewModel
                val editor = editor()
                assertFalse(editor.state.value.loading)
                assertEquals(settings.settings.value, session.surfaces.last().submitted.last())
                editor.open(); editor.edit(ReadingTextAlignment.PUBLISHER); tick()
                assertNull(container.bookTextAlignments.observe(session.book.id, session.book.sha256).first())
                assertEquals(ReadingTextAlignment.JUSTIFY, session.surfaces.last().submitted.last().textAlignment)
                flag(activity, "chrome", false); tick()
                assertFalse(requireNotNull(session.surfaces.last().allowed).invoke())
                val key = KeyEvent(11, 11, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0)
                activity.window.callback.dispatchKeyEvent(key)
                assertEquals(0, session.surfaces.last().forwards)
                activity.javaClass.getDeclaredMethod("openBrightness").apply { isAccessible = true }.invoke(activity)
                assertEquals(false, activity.javaClass.getDeclaredMethod("getBrightnessOpen").apply { isAccessible = true }.invoke(activity))
                editor.dismiss(); tick()
                assertNull(container.bookTextAlignments.observe(session.book.id, session.book.sha256).first())
                editor.open(); editor.edit(ReadingTextAlignment.PUBLISHER); editor.save()
                await { !editor.state.value.open && session.surfaces.last().submitted.last().textAlignment == ReadingTextAlignment.PUBLISHER }
                assertEquals(ReadingTextAlignment.PUBLISHER, container.bookTextAlignments.observe(session.book.id, session.book.sha256).first())
                val changed = settings.settings.value.copy(textAlignment = ReadingTextAlignment.START, marginsPercent = 120)
                settings.settings.value = changed
                await { session.surfaces.last().submitted.last() == changed.withBookTextAlignment(ReadingTextAlignment.PUBLISHER) }
                val old = session.surfaces.last()
                host.recreate().windowFocusChanged(true); activity = host.get()
                await { session.surfaces.last() !== old && session.surfaces.last().submitted.isNotEmpty() }
                assertSame(editor, editor())
                assertEquals(changed.withBookTextAlignment(ReadingTextAlignment.PUBLISHER), session.surfaces.last().submitted.last())
                editor.open(); editor.edit(null); editor.save()
                await { !editor.state.value.open && session.surfaces.last().submitted.last() == changed }
                assertNull(container.bookTextAlignments.observe(session.book.id, session.book.sha256).first())
                assertEquals(0, settings.writes); assertTrue(progress.writes.isEmpty())
            } finally { host.pause().stop().destroy(); tick(); Dispatchers.resetMain() }
        }
    }
    private companion object {
        fun tick() { ShadowLooper.idleMainLooper() }
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!condition()) { tick(); check(System.nanoTime() < deadline) { "Activity alignment did not settle" }; Thread.yield() }
            tick()
        }
        fun flag(activity: ReaderActivity, name: String, value: Boolean) = activity.javaClass
            .getDeclaredMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType!!)
            .apply { isAccessible = true }.invoke(activity, value)
    }
}
