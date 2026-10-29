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
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.settings.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmoledThemeDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun restoreFont() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(1f) } }

    @Test fun globalAmoledChoiceIsReadableAtDoubleFontAndDoesNotApplyWithoutSave() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(EpubSettingsState(loading = false, open = true)); var saves = 0
        compose.setContent { YaprakTheme { EpubSettingsDialog(state, { state = state.copy(draft = it) }, { saves++ },
            { state = state.copy(open = false, draft = state.saved) }, {}, {}) } }
        compose.onNodeWithText("AMOLED siyah").performScrollTo().performClick().assertIsSelected()
        readable("AMOLED siyah"); assertEquals(ReadingTheme.AMOLED, state.draft.theme)
        assertEquals(ReadingTheme.SYSTEM, state.saved.theme); assertEquals(0, saves)
        compose.onNodeWithText("Kaydet").assertIsDisplayed(); readable("Kaydet")
        compose.onNodeWithText("Vazgeç").assertIsDisplayed(); readable("Vazgeç"); capture("amoled-global-font2")
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(0, saves); assertEquals(ReadingTheme.SYSTEM, state.draft.theme)
    }

    @Test fun bookAmoledChoiceAllowsInheritanceAndKeepsSaveAndCancelReachableAtDoubleFont() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate()
        var state by mutableStateOf(BookThemeState(loading = false, open = true)); var saves = 0
        compose.setContent { YaprakTheme { BookThemeDialog(state, { state = state.copy(draft = it) }, { saves++ }, {}, {}) } }
        compose.onNodeWithText("AMOLED siyah").performScrollTo().performClick().assertIsSelected()
        readable("AMOLED siyah"); assertNull(state.saved); assertEquals(ReadingTheme.AMOLED, state.draft)
        assertEquals(0, saves); compose.onNodeWithText("Kaydet").assertIsDisplayed(); readable("Kaydet")
        compose.onNodeWithText("Vazgeç").assertIsDisplayed(); readable("Vazgeç"); capture("amoled-book-font2")
        compose.onNodeWithText("Genel ayarı kullan").performScrollTo().performClick().assertIsSelected()
        readable("Genel ayarı kullan")
        assertNull(state.draft); compose.onNodeWithText("Kaydet").performClick(); assertEquals(1, saves)
    }

    private fun readable(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(2f, layout.layoutInput.density.fontScale)
        assertFalse("Height overflow: " + text, layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse("Ellipsis: " + text, layout.isLineEllipsized(line))
            // Native paragraph widths are floats; TextLayoutResult.size rounds them to integer pixels.
            assertTrue("Clipped line: " + text, layout.getLineLeft(line) >= -0.5f &&
                layout.getLineRight(line) <= layout.size.width + 0.5f)
        }
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory = File(requireNotNull(System.getProperty("yaprak.screenshotDir"))).apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
