package com.yaprak.reader.formats.cbz

import com.yaprak.reader.feature.readerToolNodeWithText
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.reader.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CbzSpreadUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }
    private val doubleLabel get() = RuntimeEnvironment.getApplication().getString(R.string.cbz_spread_double)
    private val singleLabel get() = RuntimeEnvironment.getApplication().getString(R.string.cbz_spread_single)
    private val help get() = RuntimeEnvironment.getApplication().getString(R.string.cbz_spread_help)
    private fun largeFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
    }
    private fun ready(format: BookFormat = BookFormat.CBZ) = ReaderState.Ready(object : ReaderSession {
        override val book = Book("comic-ui", "Çizgi", null, format, "comic", "comic", 100, "hash", 1, 5)
        override val resume: ReadingLocation? = null
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface = error("UI only")
        override fun close() = Unit
    })
    @Composable private fun Chrome(state: ReaderState, visible: Boolean = true, failed: Boolean = false, saveFailed: Boolean = false,
        preferred: Boolean = false, callback: (() -> Unit)? = null) {
        ReaderChrome(state, visible, saveFailed, failed, {}, {}, {}, {}, {}, {}, {}, pageLabel = "1–2 / 5",
            comicDoublePagePreferred = preferred, onComicSpread = callback,
            onPdfSpread = { error("Comic UI must not invoke PDF") }, onPdfCrop = {}, onPdfOcr = {})
    }
    private fun readable(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.readerToolNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    @Test fun actualDoubleTypeWrapsChoiceAndScopeAndDisposedCallbackCannotRetarget() {
        largeFont(); val state = ready(); val events = mutableListOf<String>()
        var preferred by mutableStateOf(false)
        var callback by mutableStateOf<(() -> Unit)?>({ events += "first" })
        compose.setContent { YaprakTheme { Chrome(state, preferred = preferred, callback = callback) } }
        readable(doubleLabel); readable(help)
        compose.readerToolNodeWithText(doubleLabel).performScrollTo().assertIsDisplayed()
        val old = compose.readerToolNodeWithText(doubleLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { callback = { events += "second" } }; compose.waitForIdle(); compose.runOnIdle { old() }
        assertTrue(events.isEmpty())
        compose.readerToolNodeWithText(doubleLabel).performScrollTo().performClick(); assertEquals(listOf("second"), events)
        compose.runOnIdle { preferred = true }; readable(singleLabel)
        compose.runOnIdle { old() }; assertEquals(listOf("second"), events)
        compose.readerToolNodeWithText(singleLabel).performScrollTo().performClick(); assertEquals(listOf("second", "second"), events)
        compose.runOnIdle { callback = null }; compose.readerToolNodeWithText(singleLabel).assertDoesNotExist(); compose.readerToolNodeWithText(help).assertDoesNotExist()
    }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun shortLandscapeKeepsDoubleTypeActionReachableAndGenericPairCaption() {
        largeFont(); val state = ready(); var calls = 0
        compose.setContent { YaprakTheme { Chrome(state, preferred = true, callback = { calls++ }) } }
        readable(help); readable(singleLabel)
        compose.readerToolNodeWithText(singleLabel).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Sayfa 1–2 / 5").assertExists()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File(System.getProperty("yaprak.screenshotDir"), "cbz-spread-chrome-double-type.png")
            file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        compose.readerToolNodeWithText(singleLabel).performClick(); assertEquals(1, calls)
    }
    @Test fun onlyVisibleReadyComicCapabilityOffersSpreadAndOldHiddenActionIsRevoked() {
        var state by mutableStateOf<ReaderState>(ready()); var visible by mutableStateOf(true)
        var failed by mutableStateOf(false); var saveFailed by mutableStateOf(false); var calls = 0
        compose.setContent { YaprakTheme { Chrome(state, visible, failed, saveFailed, callback = { calls++ }) } }
        val old = compose.readerToolNodeWithText(doubleLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { visible = false }; compose.runOnIdle { old() }; assertEquals(0, calls)
        compose.readerToolNodeWithText(doubleLabel).assertDoesNotExist()
        compose.runOnIdle { visible = true; failed = true }; compose.readerToolNodeWithText(doubleLabel).assertDoesNotExist()
        compose.runOnIdle { failed = false; saveFailed = true }; compose.readerToolNodeWithText(doubleLabel).assertDoesNotExist()
        compose.runOnIdle { saveFailed = false; state = ready(BookFormat.PDF) }; compose.readerToolNodeWithText(doubleLabel).assertDoesNotExist()
        compose.runOnIdle { state = ready(BookFormat.EPUB) }; compose.readerToolNodeWithText(doubleLabel).assertDoesNotExist()
        compose.runOnIdle { state = ready(BookFormat.TXT) }; compose.readerToolNodeWithText(doubleLabel).assertDoesNotExist()
        compose.runOnIdle { state = ReaderState.Loading }; compose.readerToolNodeWithText(doubleLabel).assertDoesNotExist(); assertEquals(0, calls)
    }
}
