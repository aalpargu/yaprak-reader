package com.yaprak.reader.feature

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
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CbzPagePositionChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }
    private fun ready(format: BookFormat = BookFormat.CBZ) = ReaderState.Ready(object : ReaderSession {
        override val book = Book("comic", "Çizgi", null, format, "comic.cbz", "comic.cbz", 1, "a".repeat(64), 1, 5)
        override val resume: ReadingLocation? = null
        override fun close() = Unit
        override fun createSurface(initial: ReadingLocation?, dark: Boolean, onTap: () -> Unit, onError: () -> Unit): ReaderSurface = error("UI only")
    })
    private fun label(percent: Int) = RuntimeEnvironment.getApplication().getString(R.string.fixed_page_position_percent, percent)
    private fun description(percent: Int) = RuntimeEnvironment.getApplication().getString(R.string.cbz_page_position_description, percent)
    @Composable private fun Chrome(reader: ReaderState, percent: Int?, visible: Boolean = true) {
        ReaderChrome(reader, visible, false, false, {}, {}, {}, {}, {}, {}, {}, bookPositionPercent = percent)
    }
    private fun nativeLayout(name: String) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var percent by mutableStateOf<Int?>(null); val reader = ready()
        compose.setContent { YaprakTheme { Chrome(reader, percent) } }
        compose.onNodeWithText(label(40)).assertDoesNotExist(); compose.runOnIdle { percent = 40 }
        val node = compose.onNodeWithText(label(40), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse(layout.didOverflowWidth); assertFalse(layout.didOverflowHeight)
        assertEquals(label(40).length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        compose.onNodeWithContentDescription(description(40)).assertExists()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom && bounds.left >= root.left && bounds.right <= root.right)
        compose.runOnIdle {
            val view = compose.activity.window.decorView; val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try { view.draw(Canvas(bitmap)); val file = File(System.getProperty("yaprak.screenshotDir"), "$name.png")
                file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        compose.runOnIdle { percent = null }; compose.onNodeWithText(label(40)).assertDoesNotExist()
    }
    @Test fun narrowDoubleTypeShowsOnlyCurrentPositionAndCompleteAccessibleLabel() { nativeLayout("cbz-position-narrow") }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun shortLandscapeDoubleTypeKeepsPositionWholeAndReachable() { nativeLayout("cbz-position-landscape") }
    @Test fun unknownInvalidHiddenOrWrongFormatCannotDisplayAnOldComicPosition() {
        var reader by mutableStateOf<ReaderState>(ready()); var percent by mutableStateOf<Int?>(40); var visible by mutableStateOf(true)
        compose.setContent { YaprakTheme { Chrome(reader, percent, visible) } }
        compose.onNodeWithText(label(40)).assertExists()
        for (invalid in listOf<Int?>(null, -1, 101)) {
            compose.runOnIdle { percent = invalid }; compose.onNodeWithText(label(40)).assertDoesNotExist()
        }
        compose.runOnIdle { percent = 100 }; compose.onNodeWithText(label(100)).assertExists()
        compose.runOnIdle { visible = false }; compose.onNodeWithText(label(100)).assertDoesNotExist()
        compose.runOnIdle { visible = true; reader = ready(BookFormat.PDF) }
        compose.onNodeWithText(label(100)).assertExists()
        compose.onNodeWithContentDescription(description(100)).assertDoesNotExist()
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.pdf_page_position_description, 100)).assertExists()
        compose.runOnIdle { reader = ready(BookFormat.TXT) }
        compose.onNodeWithText(label(100)).assertDoesNotExist()
        compose.runOnIdle { reader = ready(BookFormat.EPUB); percent = 40 }
        compose.onNodeWithText("Yaklaşık kitap konumu: %40").assertExists(); compose.onNodeWithText(label(40)).assertDoesNotExist()
    }
}
