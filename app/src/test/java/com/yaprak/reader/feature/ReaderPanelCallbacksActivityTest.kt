package com.yaprak.reader.feature

import com.yaprak.reader.readerMethod
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.core.StartupState
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.domain.reader.ReadingSnapshot
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.domain.settings.BookThemeRepository
import com.yaprak.reader.domain.settings.ReadingTheme
import com.yaprak.reader.feature.reader.ReaderActivity
import com.yaprak.reader.feature.reader.ReaderViewModel
import com.yaprak.reader.feature.settings.BookThemeViewModel
import com.yaprak.reader.reader.EpubAppearanceController
import com.yaprak.reader.reader.ReaderLoader
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Activity adapters and lifecycle, with a controlled renderer and preference repository. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPanelCallbacksActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private class MainDispatcher : CoroutineDispatcher() {
        private val handler = Handler(Looper.getMainLooper())
        override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() !== handler.looper
        override fun dispatch(context: CoroutineContext, block: Runnable) { check(handler.post(block)) }
    }

    private class Themes : BookThemeRepository {
        val saved = MutableStateFlow<ReadingTheme?>(null)
        var writes = 0
        override fun observe(bookId: String, contentSha256: String) = saved
        override suspend fun save(bookId: String, contentSha256: String, theme: ReadingTheme?) {
            writes++
            saved.value = theme
        }
    }

    private class Session : ReaderSession {
        override val book = Book("panel-callbacks", "Callback fixture", null, BookFormat.EPUB,
            "controlled.epub", "controlled.epub", 1, "a".repeat(64), 1)
        override val resume: ReadingLocation? = null
        override fun createSurface(initial: ReadingLocation?, dark: Boolean,
            onTap: () -> Unit, onError: () -> Unit) = object : ReaderSurface {
            override val fragment = Fragment()
            override val appearance = EpubAppearanceController {}
            override suspend fun observe(coverageAllowed: () -> Boolean,
                onSnapshot: (ReadingSnapshot) -> Unit) { awaitCancellation() }
            override fun forward() = false
            override fun backward() = false
        }
        override fun close() = Unit
    }

    @Test fun actualPanelCallbacksCannotEditSaveOrDismissALaterFormOrAPostPauseExposure() {
        Dispatchers.setMain(MainDispatcher())
        val host = Robolectric.buildActivity(ReaderActivity::class.java)
        try {
            compose.runOnUiThread { host.create() }
            val activity = host.get()
            val container = (activity.application as YaprakApplication).container
            compose.waitUntil(15_000) { container.startup.state.value == StartupState.Ready }
            val session = Session()
            val themes = Themes()
            val editor = BookThemeViewModel(themes)
            runBlocking { container.library.add(session.book) }
            compose.runOnUiThread {
                fun install(model: ViewModel) {
                    activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" +
                        model.javaClass.canonicalName, model)
                }
                val reader = ReaderViewModel(session.book.id, ReaderLoader { session }, object : ReadingStateRepository {
                    override suspend fun resume(bookId: String, contentSha256: String): ReadingLocation? = null
                    override suspend fun record(bookId: String, contentSha256: String, snapshot: ReadingSnapshot) =
                        error("The controlled renderer cannot emit reading progress")
                })
                install(reader)
                install(editor)
                host.start().resume().visible().windowFocusChanged(true)
                assertSame(reader, activity.javaClass.readerMethod("getModel")
                    .apply { isAccessible = true }.invoke(activity))
                assertSame(editor, activity.javaClass.readerMethod("getBookTheme")
                    .apply { isAccessible = true }.invoke(activity))
            }
            compose.waitUntil(15_000) { !editor.state.value.loading }
            compose.runOnUiThread { editor.open() }
            awaitChoices()
            val retired = callbacks()
            compose.onNodeWithText("Vazgeç").performClick()
            compose.waitUntil(10_000) { !editor.state.value.open }
            compose.onNodeWithText("Kitap teması").assertDoesNotExist()
            compose.runOnUiThread { editor.open() }
            awaitChoices()
            compose.runOnIdle { retired.forEach { it() } }
            assertUntouched(editor, themes)

            val beforePause = callbacks()
            compose.runOnUiThread { host.pause().resume().visible().windowFocusChanged(true) }
            awaitChoices()
            compose.runOnIdle { beforePause.forEach { it() } }
            assertUntouched(editor, themes)

            compose.onNodeWithText("Koyu").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(ReadingTheme.DARK, editor.state.value.draft) }
            compose.onNodeWithText("Kaydet").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { !editor.state.value.open }
            compose.runOnIdle {
                assertEquals(1, themes.writes)
                assertEquals(ReadingTheme.DARK, themes.saved.value)
            }
        } finally {
            compose.runOnUiThread { host.pause().stop().destroy() }
            Dispatchers.resetMain()
        }
    }

    private fun awaitChoices() = compose.waitUntil(10_000) {
        runCatching {
            compose.onNodeWithText("Kitap teması").assertIsDisplayed()
            compose.onNodeWithText("Koyu").assertExists().assertIsEnabled()
            true
        }.getOrDefault(false)
    }

    private fun assertUntouched(editor: BookThemeViewModel, themes: Themes) {
        compose.onNodeWithText("Kitap teması").assertIsDisplayed()
        compose.onNodeWithText("Koyu").performScrollTo().assertIsNotSelected()
        compose.runOnIdle {
            assertTrue(editor.state.value.open)
            assertNull(editor.state.value.draft)
            assertNull(themes.saved.value)
            assertEquals(0, themes.writes)
        }
    }

    private fun callbacks(): List<() -> Unit> {
        compose.onNodeWithText("Koyu").performScrollTo()
        return listOf("Koyu", "Kaydet", "Vazgeç").map(::applicationClick)
    }

    /** A Material semantics action reads its node's latest handler. Retain the application's
     * current handler itself so this test actually exercises callbacks from the old exposure. */
    @Suppress("UNCHECKED_CAST")
    private fun applicationClick(label: String): () -> Unit {
        val semantics = compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        check(semantics.javaClass.name == "androidx.compose.foundation.AbstractClickableNode\$applySemantics\$1")
        val node = semantics.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(semantics)
        val clickable = generateSequence(node.javaClass as Class<*>?) { it.superclass }
            .first { it.name == "androidx.compose.foundation.AbstractClickableNode" }
        val getter = clickable.readerMethod("getOnClick")
        return getter.apply { isAccessible = true }.invoke(node) as () -> Unit
    }
}
