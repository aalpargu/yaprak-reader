package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yaprak.reader.domain.settings.BookReadingMode
import com.yaprak.reader.feature.settings.BookReadingModeDialog
import com.yaprak.reader.feature.settings.BookReadingModeState
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookReadingModeDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }

    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `large type offers inherited and explicit book fonts with reachable save and cancel`() {
        var state by mutableStateOf(BookReadingModeState(loading = false, open = true))
        var saves = 0
        var dismisses = 0
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        compose.setContent {
            YaprakTheme {
                BookReadingModeDialog(state, { state = state.copy(draft = it) }, { saves++ }, { dismisses++ }, {})
            }
        }
        compose.onNodeWithText("Kitap okuma modu").assertIsDisplayed()
        captureNativeWindow("book-reading-mode-title-double-type.png")
        assertWholeTitleInNativeWindow("Kitap okuma modu")
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Sayfa çevirme").performScrollTo().performClick()
        assertEquals(BookReadingMode.PAGED, state.draft)
        assertNull(state.saved)
        assertEquals(0, saves)
        compose.onNodeWithText("Dikey kaydırma").performScrollTo().performClick()
        assertEquals(BookReadingMode.SCROLL, state.draft)
        compose.onNodeWithText("Dikey kaydırma").performScrollTo().performClick()
        assertEquals(BookReadingMode.SCROLL, state.draft)
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().performClick()
        assertNull(state.draft)
        compose.onNodeWithText("Sayfa çevirme").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").assertIsDisplayed()
        // Actual native dialog is captured from its ViewRoot, preserving its separate window.
        compose.runOnIdle {
            val view = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = java.io.File(System.getProperty("yaprak.screenshotDir"), "book-reading-mode-double-type.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("Kaydet").assertIsDisplayed().performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
        assertEquals(1, dismisses)
    }

    private fun assertWholeTitleInNativeWindow(title: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val titleNode = compose.onNodeWithText(title, useUnmergedTree = true)
        titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse("Title overflow: size=${layout.size} paragraphHeight=${layout.multiParagraph.height} width=${layout.didOverflowWidth} height=${layout.didOverflowHeight} lines=${layout.lineCount} constraints=${layout.layoutInput.constraints}", layout.hasVisualOverflow)
        assertEquals(title.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        val bounds = titleNode.fetchSemanticsNode().boundsInWindow
        compose.runOnIdle {
            val view = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
            assertTrue("Title above native window: $bounds", bounds.top >= 0f)
            assertTrue("Title below native window: $bounds height=${view.height}", bounds.bottom <= view.height + 1f)
            assertEquals("Native window clipped title", layout.size.height.toFloat(), bounds.height, 1f)
        }
    }
    private fun captureNativeWindow(name: String) {
        compose.runOnIdle {
            val view = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = java.io.File(System.getProperty("yaprak.screenshotDir"), name)
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun `read error has retry and save failure keeps draft while busy state blocks actions`() {
        var state by mutableStateOf(BookReadingModeState(loading = false, loadFailed = true, open = true))
        var retries = 0
        var saves = 0
        compose.setContent {
            YaprakTheme { BookReadingModeDialog(state, { state = state.copy(draft = it) }, { saves++ }, {}, { retries++ }) }
        }
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        compose.onNodeWithText("Yeniden yükle").performScrollTo().performClick()
        assertEquals(1, retries)
        assertEquals(0, saves)
        compose.runOnIdle {
            state = state.copy(loading = false, loadFailed = false, saved = BookReadingMode.SCROLL,
                draft = BookReadingMode.SCROLL, saveFailed = true)
        }
        compose.onNodeWithText("Kitabın okuma modu kaydedilemedi. Tekrar deneyebilir veya vazgeçebilirsin.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dikey kaydırma").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Kaydet").assertIsEnabled()
        compose.runOnIdle { state = state.copy(saving = true) }
        compose.onNodeWithText("Dikey kaydırma").assertIsNotEnabled()
        compose.onNodeWithText("Kaydediliyor…").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").assertIsNotEnabled()
    }
}
